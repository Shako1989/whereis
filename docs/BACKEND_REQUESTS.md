# Backend change requests — from the mobile clients

Gaps found while building the Android client against the frozen backend. Each entry states the
endpoint, the shape the client needs, why, and the workaround shipped in the meantime. Entries that
have since been implemented say so in their heading; the apps work around the rest.

Append new entries at the end. One `##` heading per request.

## BR-1 — No logout endpoint: refresh tokens survive sign-out (security)

**Raised by:** Android client, `feature/settings` (sign-out flow).
**Severity:** security follow-up — should be closed before public release.
**Contract reference:** `docs/ANDROID_APP_PROMPT.md` §7.2.

### What the client can do today

Signing out is entirely client-side: `SettingsRepository.signOut()` runs every registered
`SignOutCleanupTask` and then calls `SessionManager.signOut()`, which wipes the Keystore-encrypted
`TokenStore`. That destroys this device's copy of the credentials and nothing else.

### The gap

There is no `POST /auth/logout`, and no way to revoke a refresh token or a token family from the
client. The refresh token the device was holding **stays valid server-side for the remainder of its
30-day TTL**. Anyone who extracted it before sign-out — a stolen device image, a rooted phone, a
backup captured while the user was still signed in — can keep minting 15-minute access tokens for
up to a month after the user believes they have signed out. "Sign out" is therefore a local UI
state change, not a security boundary, and the client cannot honestly promise otherwise.

The refresh-token *rotation* design makes this worse rather than better in one respect: since the
client cannot spend or invalidate the token on the way out, there is no path that ends the family.

### What the client needs

```
POST /api/v1/auth/logout
Authorization: Bearer {accessToken}
{ "refreshToken": "opaque-string" }      // ≤ 512, the pair this device is holding

204 No Content   — the presented refresh token and its whole rotation family are revoked
401              — access token invalid/expired (client wipes locally regardless)
```

Two properties matter to the client:

1. **Idempotent.** Calling it with an already-revoked token must still return 204, not 409. The app
   fires it on a best-effort basis and must not show the user an error for it.
2. **Revokes the family, not just the presented token**, matching the existing reuse-detection
   semantics, so a leaked ancestor token dies with it.

A companion `POST /auth/logout-all` (revoke every family for the JWT subject) would let Settings
offer "Sign out of all devices", which is the standard remedy after a lost phone. Not required for
BR-1 to be closed.

### Workaround shipped

`DefaultSettingsRepository.signOut()` wipes the token store and the registered cleanup tasks, and
the sign-out confirmation copy promises only that *"everything this app stored on the phone is
removed"* — it deliberately does not claim the session has ended everywhere. The call to the new
endpoint slots in ahead of the local wipe, best-effort and failure-tolerant, when it exists.


## BR-2 — `NEEDS_CONFIRMATION` has no confirmation channel — **IMPLEMENTED 2026-09-03**

**Status:** shipped. `POST /api/v1/assistant/remember` now takes an optional `spaceId`; the client
resends the same `message` with the id it picked from `candidateSpaces` and the space is settled
without consulting the AI. Ownership is enforced the usual way — another user's id is a 404.
Covered by `AssistantServiceTest`. Alongside it, the provider now receives the user's own space
names, so `"evdə"` resolves to a space called `Home` and most confirmations never happen at all
(`ClaudeLiveApiTest#aForeignLanguageSpaceMentionResolvesToAnExistingSpace`). Original report below.

**Raised by:** Android client, `feature/assistant` (Remember flow).
**Severity:** correctness — the workaround is lossy and can strand a capture.
**Contract reference:** `docs/ANDROID_APP_PROMPT.md` §3.6, §7.1.

### The gap

`POST /api/v1/assistant/remember` accepts exactly one field:

```jsonc
{ "message": "I put my passport in the bedroom wardrobe top drawer" }
```

When the assistant understands the sentence but cannot tell which space it belongs to, it answers
`status: "NEEDS_CONFIRMATION"` with a `candidateSpaces` list and performs **zero writes**. The
client shows that list, the user picks a space — and then there is nowhere to put the answer. The
request has no `spaceId`, and no other endpoint accepts a resolved ambiguity. The one piece of
information the round trip was asked for cannot be sent back.

### What the client needs

```jsonc
POST /api/v1/assistant/remember
{ "message": "…", "spaceId": "uuid|null" }   // optional; when present the parser MUST NOT
                                             // re-derive the space, and MUST 404 if the space
                                             // is not the caller's
```

With `spaceId` present, `NEEDS_CONFIRMATION` becomes unreachable for that call: the response is
`CREATED` or `NOT_UNDERSTOOD`. That makes confirmation deterministic and removes the natural-language
round trip described below.

### Workaround shipped

`DefaultAssistantRepository.rememberInSpace()` re-sends the **original sentence with the chosen
space name folded into the front of it**, using a `translatable="false"` template so the payload
stays in the language the backend parser reads:

```
In Home: I put my passport in the bedroom wardrobe top drawer
```

`AssistantViewModel` re-sends **exactly once** (`ConfirmationPhase.CONFIRMING` →
`EXHAUSTED`); a second `NEEDS_CONFIRMATION` stops the flow and hands the user the manual add-item
form. There is no third attempt by construction, and
`AssistantViewModelTest."a second NEEDS_CONFIRMATION stops instead of looping"` locks that in.

Three things the workaround cannot do, and which `spaceId` would fix:

1. **It is a guess, not an instruction.** The prefix is parsed like any other prose; the model may
   ignore it, and a space whose name collides with a location name ("Office" as both a space and a
   room) can re-resolve to the wrong place.
2. **It consumes the message budget.** `message` is capped at 1000 characters, so a long sentence
   loses its tail to the prefix.
3. **It costs a second AI call** for something the client already knows the answer to.

### Related, smaller

The manual fallback would also like to pre-select the space the user picked. The client's
navigation contract for the assistant screen currently passes only a prefill *name* to the add-item
form, so the picked space is dropped at that hand-off; that is a client-side limitation to close
alongside this request, not a backend one.

## BR-3 — `primaryImageUrl` missing from `ItemResponse` (N+1 in the items list) — **IMPLEMENTED 2026-09-13**

**Status:** shipped. `ItemResponse` now carries **two** nullable cover-photo fields:

```jsonc
"primaryFileId":  "uuid|null",                   // stable
"primaryImageUrl":"https://…presigned…|null"     // ~10 min TTL
```

Both are populated on `GET /items`, `GET /items/{id}`, `PUT /items/{id}` and
`POST /items/{id}/move`, and are null for an item without a cover photo. `POST /items` returns
nulls without issuing any lookup — a just-created item cannot have a photo yet.

**Why the file id and not just the URL, as asked.** The presigned URL rotates every
`minio.presign-ttl` (~10 minutes), so it is useless as a cache key: the client keys its Coil disk
cache on `fileId` (`ItemPhotoFetcher` / `StableImageModel`), and a URL-only response would force it
to parse the object key back out of a signed URL, while any client-side cached list would hold dead
URLs. Returning the stable id alongside keeps the existing client-side cache working unchanged.

**How it is resolved.** `FileStorageService.primaryImages(Collection<UUID>)` returns
`Map<UUID, ItemPrimaryImage>` (`fileId` + presigned `url`) for the items that have a cover, in **one
query for the whole page** — originally the same `ItemFileRepository.findAllByItemIdInAndIsPrimaryTrue`
lookup search already used (since replaced, see the semantics note below), now the single shared
implementation: `PostgresSearchService` was refactored
onto it and no longer touches `ItemFileRepository`/`MinioAdapter` itself. The `item` module crosses
the module boundary through that public service only. Presigning is a local HMAC computation, not a
MinIO round-trip, which is why it is allowed inside `list()`'s read-only transaction. No migration,
no new index; the cover lookup is still exactly one query per page (its shape changed with the
semantics note below). `ItemListCoverPhotoIT` pins the behaviour and asserts that the JDBC
statement count for a page does not grow with page size (verified to fail when a per-row lookup is
reintroduced).

**Cover semantics changed 2026-09-13: primary, else oldest.** The first cut returned only files
with `is_primary = true`, but most photos in production were uploaded with `primary=false` (the
Android `PhotoCaptureViewModel` defaulted `useAsCover=false` until recently), so items such as
"Passport" and "Termos" showed a placeholder in the list while the detail screen — which lists
every file — still showed the photo. `primaryImages()` now returns the primary photo if the item
has one, otherwise its OLDEST photo, otherwise nothing — exactly what the retired `ItemThumbnails`
workaround did, so every existing item regains its cover without a data migration. Still one
query per page: `findAllByItemIdInOrderByIsPrimaryDescCreatedAtAscIdAsc` fetches the items'
files winner-first (primary, then oldest, then lowest id as the tie-break) and the service keeps
the first row per item. The JSON field names are unchanged.

**Original report below.**

**Raised:** 2026-08-30, from the Android items list.

**Today (before the fix):** `primaryImageUrl` exists only on `ItemSearchResult`
(`GET /items/search`). Neither `GET /items` nor `GET /items/{itemId}` carries a thumbnail.

**Why it matters:** the items list shows each item's cover photo. With no thumbnail in the page
payload, the only way to learn whether an item has one is `GET /items/{id}/files` — **one extra call
per row**. That is an N+1 against data the backend already resolves in a single batch query for
search results.

**Asked for:** add `primaryImageUrl` (presigned, nullable) to `ItemResponse`, resolved with the same
batch lookup `PostgresSearchService` performs today — one query per page, not one per row.

**Client workaround shipped:** `ItemThumbnails` resolves the cover per visible row and caches it —
found photos indefinitely (a `fileId` is stable), misses for 30s. Only rows actually scrolled past
cost a call. It can now be retired in favour of the fields on `ItemResponse`.

**Client: retired 2026-09-13.** `ItemThumbnails` and its Hilt entry point are deleted. The items
list builds its row image model from `primaryFileId` (memory and disk cache key) plus
`primaryImageUrl` (first download attempt, no API call); a URL that storage rejects as expired falls
back to `GET …/files/{fileId}/url` exactly once through the existing `ItemPhotoFetcher` retry.

## BR-4 — Non-English sentences are not understood by the `mock` provider

**Raised:** 2026-08-30, verified against the running backend.

**Today:** with `AI_PROVIDER=mock`, `MockAiAssistant.PLACEMENT` matches hardcoded English verbs
(`put|placed|left|stored|keep|kept|moved`) and prepositions (`in|into|inside|on|at|to`). A natural
Azerbaijani sentence returns `NOT_UNDERSTOOD`. Azerbaijani is verb-final and marks place with case
suffixes, so this regex shape cannot be extended to cover it.

**Not a defect in the trust boundary:** `InterpretationValidator.SAFE_NAME` uses `\p{L}`, so
Azerbaijani characters pass validation. Verified — `"I put my çantamı in the qonaq otağı şkafı"` was
stored as `Çantamı` in `Ev > Qonaq Otağı Şkafı`.

**Asked for:** nothing structural; a real provider handles it. Recorded so it is not
rediscovered as a client bug. The mock's English stopword list limits `assistant/search` the same
way, so both modes start working together.

**Status (2026-09-02):** two providers now answer this — `AI_PROVIDER=openai` and
`AI_PROVIDER=claude`. The Claude provider's prompts instruct the model to keep the user's own
words and script and never translate, and the charset boundary was already permissive
(`\p{L}`). Left open until an Azerbaijani sentence has been run against `claude` on a live key:
whether Haiku 4.5 segments a verb-final, case-marking sentence into the right containment chain
is model behaviour, not something the offline suite can prove. **The check now exists** —
`ClaudeLiveApiTest` asserts both the segmentation and that the user's own script survives
untranslated; run it with `AI_CLAUDE_API_KEY=... ./gradlew liveAiTest`.

**Verified 2026-09-02 — BR-4 is answered for the placement path.** Haiku 4.5 segments a verb-final,
case-marking sentence correctly and never translates: `"çantamı qonaq otağındakı şkafa qoydum"` →
`Çanta @ Qonaq otağı(ROOM) > Şkaf(FURNITURE)`; a three-level sentence resolves to `Yataq otağı >
Şkaf > Yuxarı siyirtmə`. Two follow-ups remain open, both new:
- ~~Case suffixes are kept in names~~ — **fixed 2026-09-03.** The prompts now require the base
  dictionary form, and `ClaudeLiveApiTest#theSameDrawerGetsTheSameNameFromTwoDifferentSentences`
  pins it: two phrasings of one drawer must produce one name, or the tree grows a duplicate.
- ~~Search keywords unreliable in Azerbaijani~~ — **fixed 2026-09-03.** The search prompt lists
  Azerbaijani question words and forbids returning a whole sentence as a keyword.

## BR-5 — Account deletion (Google Play requirement) — **IMPLEMENTED 2026-09-14**

**Status:** shipped. Play requires an in-app deletion path AND a web URL; both exist now.

```
DELETE /api/v1/users/me
Authorization: Bearer {accessToken}
Content-Type: application/json
{ "password": "the user's current password" }

204 No Content   — account and EVERYTHING under it are gone, irreversibly, in one transaction
401 INVALID_CREDENTIALS — wrong, blank or missing password, or the account no longer exists;
                          NOTHING is deleted. Same uniform body as a failed login.
401 (resource-server) — no/expired access token: refresh first, then retry
409 CONFLICT     — only under a concurrent write from another device; nothing deleted, safe to retry
```

**Semantics.** Hard delete, no grace period: user row, all spaces, the whole location tree, all
items, the complete movement history, all photo metadata and every refresh token (all devices are
signed out server-side — this also closes the BR-1 "sign-out is only local" concern for the deletion
case). Photo binaries leave object storage **asynchronously shortly after** (outbox + janitor); the
delete itself never talks to MinIO, so storage being down cannot block it. The user id is the JWT
subject — there is no id in the path and none in the body.

**What the client must do.**
- Settings → **Delete account**: explain what is deleted and that it is irreversible, ask for the
  password, one explicit confirmation, then call the endpoint. Show a blocking progress state — a
  large account is a few seconds, and the production VM's cold start (20–50 s after idle) can be in
  front of it; set a generous timeout for this one call.
- On **204**: wipe the encrypted token store and the local database exactly as sign-out does, and
  return to the login screen. Do NOT call `/auth/refresh` afterwards — it will 401 `TOKEN_INVALID`.
- On **401 INVALID_CREDENTIALS**: show "wrong password", keep the user signed in, let them retry.
  (Do not distinguish "account already gone" — the server deliberately does not.)
- Retrofit: a DELETE with a body needs `@HTTP(method = "DELETE", path = "users/me", hasBody = true)`.
- The still-valid access token is NOT revoked (stateless JWT, 15-minute TTL); the wipe above is what
  ends the session on the device.

**Web routes for the Play Console** (served by the API host, no auth):
`https://{WHEREIS_API_HOST}/legal/delete-account` (data-deletion URL) and
`https://{WHEREIS_API_HOST}/legal/privacy` (privacy policy URL). Both are bilingual AZ/EN.

## BR-6 — Assistant provenance: `sourceMessage` on item detail + `GET /assistant/messages` — **PARTIALLY IMPLEMENTED 2026-09-16**

> **Correction (2026-09-17): the client-facing half of this entry was never built.** What shipped is
> write-only: the `assistant_messages` table (V8) and the write discipline. There is **no**
> `GET /assistant/messages` endpoint and **no** `sourceMessage` on `ItemResponse` — two rival agent
> designs existed and the richer one was deliberately not taken (see the skill's §8). Do not build a
> client against the "What the client gets" section below; it describes a proposal, not an API.
> Read the rows from the database until an endpoint exists.

**Status:** shipped (V8 `assistant_messages`). **Raised:** backend-side, from a real incident on
2026-09-16 — 13 items filed through the assistant, 4 landed in the wrong space ("Work" instead of the
listed "Xalqlar"; twice the model created "Xalqlar" as a ROOM inside Work), one physical box became four
location rows ("Ashagi kladovka > Karobka" vs "Kladovka > Karobka" under two spaces), a bag "Chanta" was
created twice, and one item name absorbed a fragment of the space name ("Silicon vuran xalq"). None of it
could be diagnosed: the sentence and the model's raw interpretation were stored nowhere (and must never be
logged above DEBUG). The user asked for the original sentence to be kept.

### What the client gets

```jsonc
// GET /items/{id}, PUT /items/{id}, POST /items/{id}/move  — the DETAIL shape gains one nullable key
{ …, "primaryImageUrl": "…|null", "sourceMessage": "I put my passport in …|null", "archived": false, … }
// GET /items — list rows ALWAYS carry "sourceMessage": null (by design; see below)

// GET /assistant/messages?page=0&size=20  — Spring Page<AssistantMessage>, newest first, own rows only
{ "id":"uuid", "mode":"REMEMBER|SEARCH", "message":"…exactly as typed…",
  "outcome":"CREATED|NEEDS_CONFIRMATION|NOT_UNDERSTOOD|ANSWERED|FAILED",
  "confidence":0.9|null, "itemId":"uuid|null", "spaceId":"uuid|null", "createdAt":"…Z" }
```

- `size` is clamped to `[1,100]` exactly like `GET /items`; fixed sort `createdAt desc, id desc`, no
  `sort` parameter. Another user gets an empty page — there is no by-id form, so nothing to 404.
- The client may show **"You said: …"** on the item detail screen (from `sourceMessage`) and a
  **history screen** (from `/assistant/messages`).

### What the backend stores (not exposed)

One row per request that reaches `AssistantService.remember/search` — including validator rejections
(`NOT_UNDERSTOOD`, with the model's RAW answer so the mistake is inspectable), the zero-write
`NEEDS_CONFIRMATION`, and provider failures (`FAILED`, interpretation NULL). Each row also carries the
validated interpretation (item name, space name, typed location segments, or the search keywords) as
`jsonb`, the provider, the live model id, and `prompt_version` — a SHA-256 prefix of the immutable
system-prompt text, so stored sentences can be re-run against a given prompt. These diagnostics are
**not** in the client contract on purpose: publishing a free-form `jsonb` would promise a schema that
has to stay free to change.

### Rules and decisions the client must know

1. **Two rows for one confirmed placement.** A `NEEDS_CONFIRMATION` answered by resending with
   `spaceId` yields one `NEEDS_CONFIRMATION` row and one `CREATED` row with the same `message`. That is
   the honest record; group them or label the first — do not render one capture as two.
2. **`FAILED` is not "no answer".** For `SEARCH`, the backend still answered from the plain-text
   fallback; the row records that the model was unreachable.
3. **The list is null on purpose.** `sourceMessage` is ignored by the list mapper so it can never turn
   into a per-row lookup (the N+1 BR-3 was filed to remove). Read it from the detail endpoint.
4. **What writes no row:** a blank/control-only message (400 before any model call — there is no
   sentence to keep), `POST /assistant/images/analyze` (no sentence), and a `remember` whose `spaceId`
   is not the caller's (404 — no outcome value fits; adding one is a V9).
5. **Retention: kept for the life of the account, no purge job** (decision, 2026-09-16). Rows are
   deleted explicitly by `DELETE /users/me` before the item cascade, and the `users` FK cascade is
   the backstop. Both legal pages (`/legal/privacy`, `/legal/delete-account`, AZ + EN) now disclose
   that assistant sentences are stored with the account and deleted with it.
6. **Never log `message` above `DEBUG`** on the client either (§4.7 of the app prompt still applies).

---

## BR-7 — A pinned `locationId` on `/assistant/remember` — **IMPLEMENTED 2026-09-17**

### Why

Filing many items into the same physical place is the common case, and each sentence used to
re-resolve that place independently through the model. Thirteen sentences into one box are thirteen
independent chances to get the box wrong — which is what the 2026-09-16 incident was. Every other
mitigation reduces a probability; letting the caller name the destination removes the operation.

### What the client gets

```jsonc
{ "message": "kabel 20A", "locationId": "uuid-of-Karobka" }
```

**With `locationId` present, no model is called at all and the text IS the item name, stored exactly
as typed.** No parsing, no title-casing, no description extraction (`description` comes back null).

That rule was settled by the user after the first cut shipped and failed on the very input it
existed for: a pin was set, `kabel 20A` was typed, and the answer was `NOT_UNDERSTOOD`. The cause
was that validation ran before the pinned branch and rejects an interpretation naming no location —
and a bare noun phrase is legitimately not a placement statement, so a prompt calibrated to score
placement statements was right to score it low. Once the place is chosen there is nothing left to
interpret, so nothing interprets it.

Consequences, all deliberate:

* `resolveOrCreateChain` — the only auto-creation path in the system — is never entered, so **no
  location can be created by such a request** and `createdLocations` is always empty by construction.
* The text must still pass the item-name rule: non-blank, ≤ 120 characters, matching
  `^[\p{L}\p{N}][\p{L}\p{N} .,'&()\-]*$`. This is not model distrust — it is what the column and
  the UI can hold. A failure answers **`NOT_UNDERSTOOD`** (not 400: the client already renders that
  status with the message and an "Add manually" fallback, and the user did nothing malformed).
  The charset is also what keeps a question out, in any language and with no model: `?` has never
  been allowed in a name.
* **A full sentence sent with a pin becomes an item named after the whole sentence.** That is the
  accepted consequence of "no syntax analysis", tested rather than hidden; the remedy is the Undo
  the created card already offers.
* `NEEDS_CONFIRMATION` cannot happen on this path.
* Mutually exclusive with `spaceId`: both is a 400 `VALIDATION_ERROR`. A location already names its
  space, so the pair can only be redundant or contradictory.
* A foreign or deleted `locationId` is a 404 and leaves a `FAILED` row with a NULL `space_id` — the
  ownership lookup happens inside the executor's transaction.

### What the UI must do

The pin changes what the input field is asking for. While a pin is set, the composer must ask for an
item name, not a placement — the first user to hit this had a pin pill on screen and a placeholder
below it still reading "Tell me where you put it…", which is what produced the sentence.

### Provenance

One row as always, with `provider = 'none'`, `model = 'none'`, `prompt_version = 'none'` and a NULL
`interpretation`. Those together are the discriminator for this path: a NULL interpretation with a
*real* provider means the provider failed instead. What is given up is the earlier design's free
labelled dataset ("what would the model have answered for a sentence whose right destination is
known") — a deliberate trade for a path that must be fast, free and literal.

### Building the chips

`GET /spaces/{spaceId}/location-tree` already returns the tree; flatten it to full paths. There is
no item-count per location and none was added — rank by **recently pinned**, kept on the client.

### Tests

`AssistantServiceTest` (7 cases: the provider is never touched, the text is the name, a sentence is
filed unparsed, a text that cannot be a name is NOT_UNDERSTOOD and writes nothing, one too long is
too, the row records no provider and no interpretation, a foreign id records FAILED with no space,
and the both-ids contradiction) and `AssistantPinnedLocationIT` (5 cases, each asserting the
`locations` table is unchanged as well as the item — an item-only assertion would still pass if a
chain had been created and then ignored).

---

## BR-8 — No cheap way to resolve a location's full path (found while building BR-7's chips)

### The gap

The BR-7 pin chips show a space-qualified path (`Xalqlar > Ashagi kladovka > Karobka`) and the
client persists that string with the id, because there is no way to ask for it again affordably.
`GET /locations/{id}` returns `LocationResponse` — `{id, spaceId, parentLocationId, name, …}` — with
no path, so rebuilding one label costs `GET /locations/{id}` + `GET /spaces/{spaceId}` + walking the
space tree, **per chip**.

Consequence today: a location renamed or re-parented somewhere else keeps its old label in the chip
row until it is pinned again (re-pinning refreshes the stored path). A deleted one is noticed only
when a pinned send answers 404, which the client handles by unpinning. Cosmetic, not corrupting —
the id is what travels, the label is only what is shown.

### What the client needs

A batch resolve, because the chip row wants five at once and the whole point is one call:

```
GET /api/v1/locations/paths?ids=a,b,c      →  { "a": ["Xalqlar","Ashagi kladovka","Karobka"], … }
```

Ownership-scoped like everything else; unknown or foreign ids simply absent from the map rather
than a 404, so one dead chip does not fail the batch.

### Why this shape

`LocationTreeDao.resolvePaths(Collection<UUID>)` already exists and already returns exactly this —
it is what `ItemResponse.locationPath` is built from, in one query per page. The endpoint is a thin
wrapper over a method that is already there and already batched.

Adding `path` to `LocationResponse` instead would be the obvious move and is the wrong one:
`GET /spaces/{id}/locations` returns every location in the space, so a per-row path would
re-introduce exactly the N+1 that BR-3 was filed to remove.

### Priority

Low. The stale label is visible for one pin and self-heals on re-pin.

---

## BR-9 — `locationId` filter on `GET /items` — **IMPLEMENTED 2026-09-17**

### Why

Tapping a location in the client's location tree did nothing, because nothing answered "what is in
this place?". `GET /items` had no location filter, `ItemRepository` knew only
`existsByCurrentLocationId` (a boolean, for the delete guard), and `GET /items/search?q=` is the
wrong tool: it matches a keyword over a location's whole **subtree** and by name, so searching for
"Drawer" also returns items from every other similarly-named drawer in every space.

### The contract

```
GET /api/v1/items?locationId={uuid}&page=&size=&sort=&includeArchived=
```

* `locationId` is **optional**. Absent → the previous behaviour, byte for byte.
* Present → only items whose `currentLocationId` **equals** it.
* Everything else is unchanged: the same Spring `Page<Item>` envelope, the same sort whitelist
  (`name`, `category`, `createdAt`, `updatedAt`, anything else coerced to `updatedAt`, direction
  preserved), `size` clamped to 100, the same `includeArchived` semantics, and the same two batch
  queries behind `locationPath` and `primaryImageUrl` — a filtered page costs exactly one statement
  more than an unfiltered one (the ownership lookup), regardless of page size.

### Rules the client must know

* **A `locationId` that is not the caller's — or does not exist — is a `404` with code
  `LOCATION_NOT_FOUND`.** Not a `403`, and deliberately **not** an empty page: an empty page would
  answer "does this id exist?" about another user's tree. Treat it the same way BR-7 treats a dead
  pin — drop the selection and refresh the tree, do not render "0 items".
* A non-UUID value is a `400 VALIDATION_ERROR` ("Malformed request"); an **empty** value
  (`?locationId=`) is converted to null, i.e. no filter at all, not an error.
* Archived items at that location still obey `includeArchived` (default `false`).

### What it deliberately does NOT do

**No subtree walk.** Asking for a location returns only what sits *directly* in it — a coat in the
wardrobe is not returned when the top drawer inside it is queried, and vice versa. The client is
expected to call this **only for a leaf** (a location with no children), where by definition there
are no descendants, so a recursive CTE would be a query that cannot change the result. For "show me
everything under this branch" there is no endpoint; that is a separate request if the UI ever wants
it. Nor is there an item **count** per location — the tree still cannot show badges without one call
per node, same conclusion as BR-7's chips.

### Tests

`ItemServiceTest` (5 cases: an absent id touches `LocationService` not at all, a present one
verifies ownership and uses the userId-scoped finder, a foreign one throws before any item is read,
the sort whitelist and size clamp still apply, and `includeArchived` picks its own finder) and
`ItemListByLocationIT` (6 cases: siblings excluded, the equality-not-subtree property in both
directions, a foreign location 404 that leaks neither the item name nor the location name, unknown
404 / malformed 400 / empty-value unfiltered, sort and clamp, archived hidden unless asked).
`ItemListCoverPhotoIT#theQueryCountOfALocationFilteredPageDoesNotGrowWithPageSize` extends BR-3's
mutation-checked statement-count guard onto the filtered path.

---

## BR-10 — Free-tier limits: 1 space, 100 active items — **IMPLEMENTED 2026-09-19**

> **EXTENDED BY BR-12 (2026-09-19).** The two-valued FREE/UNLIMITED model is now a five-tier ladder
> and the wall has a door. Everything below still holds for a `FREE` account; the 409 message now
> names the caller's TIER rather than always saying "Free plan", and it offers an upgrade only where
> a higher purchasable tier actually raises that allowance.

### Why

Nothing capped what one account could create. Storage, MinIO objects and (on a Claude/OpenAI
provider) per-request AI cost all scale with the number of items, and the app is about to enter
closed testing with no ceiling of any kind. This is the enforcement half of a paid tier; **billing
itself is a later change** — there is no Play Billing integration, no subscription table and no
purchase endpoint in this change.

### The rule

A `FREE` account may hold **1 space** and **100 ACTIVE items**. Creation beyond either is refused;
nothing existing is deleted, hidden or archived by the change. An `UNLIMITED` account is exempt.

* **Only ACTIVE items count.** Archiving an item frees room, which is what makes the item limit a
  wall the user can get past rather than a dead end. Archived rows keep existing and stay readable
  through `GET /items?includeArchived=true`.
* **Locations are not limited.** Any depth, any number, inside the space the user has.
* Deleting a space or an item frees room the same way.

### The contract

Three endpoints can now answer `409`:

```
POST /api/v1/spaces               409 { "code": "PLAN_LIMIT_REACHED", "message": "Free plan limit reached: 1 space. Subscribe for unlimited spaces." }
POST /api/v1/items                409 { "code": "PLAN_LIMIT_REACHED", "message": "Free plan limit reached: 100 active items. Archive an item to free room, or subscribe for unlimited items." }
POST /api/v1/assistant/remember   409 { "code": "PLAN_LIMIT_REACHED", ... }   // both the chain path and a pinned locationId (BR-7)
```

* **409, not 402.** The client branches on `code`, not on the status, this API already answers every
  other guard violation with 409 (`SPACE_NOT_EMPTY`, `DUPLICATE_NAME`), and a 402 would advertise a
  payment path that does not exist yet.
* The `message` names what was hit and what the limit is, and is meant to be shown to the user —
  it is the text that precedes a paywall.
* Ownership still wins: a foreign or unknown `locationId` on `POST /items` or on a pinned
  `/assistant/remember` is the established `404 LOCATION_NOT_FOUND`, even for an account at its
  limit.
* Re-sending the name of the space you already own is still `409 DUPLICATE_NAME`, not
  `PLAN_LIMIT_REACHED` — the more specific answer wins.
* A refused `/assistant/remember` leaves **no partial data**: no item, and none of the locations the
  chain resolver had just created (one transaction, one rollback). It does leave one
  `assistant_messages` row with `outcome = 'FAILED'` and `error_code = 'PLAN_LIMIT_REACHED'` (that
  table is still write-only — no endpoint reads it).

### Granting

`users.plan` is `FREE` or `UNLIMITED`, defaults to `FREE`, and **nothing was grandfathered** —
migration `V9` adds the column with `DEFAULT 'FREE'` and contains no `UPDATE`. UNLIMITED is a grant
an operator makes for a specific account (internal users, testers, acquaintances) with one SQL
statement; the SQL is in `deploy/README.md`. There is no endpoint and no admin API for it, by
design. The column is written by the migration or an operator and by nothing else: when billing
lands, an RTDN expiry writing this column would silently erase a hand-made grant, so the rule
becomes `plan = 'UNLIMITED' OR active subscription` inside
`PlanLimitEnforcer#hasUnlimitedEntitlement`, and the subscription half gets its own state.

### What the client must do

See `docs/ANDROID_APP_PROMPT.md` §3.8. Short version: on `409 PLAN_LIMIT_REACHED`, show the
`message`, and for the item limit offer "archive something" as the action that actually works today.
**There is no paywall to open yet** — do not ship a button that leads nowhere.

### Tests

`PlanLimitEnforcerTest` (7 cases: both boundaries, the message content, 409/`PLAN_LIMIT_REACHED`,
UNLIMITED bypassing without even counting, a vanished account defaulting to FREE, only the
archived-excluding finder being consulted, and a configured limit below 1 refusing at startup),
`PlanTest` (the V9 CHECK vs the enum, and that V9 grants nothing), `ItemServiceTest` +3,
`SpaceServiceTest` +2, `AssistantServiceTest` +2 (the FAILED/`PLAN_LIMIT_REACHED` row on both
remember paths), and `PlanLimitIT` (8 cases, run against the REAL production limits).

## BR-11 — `GET /users/me/plan`: what plan am I on, and how much have I used — **IMPLEMENTED 2026-09-19**

> **PARTLY SUPERSEDED BY BR-12 (2026-09-19).** The route, the usage semantics and the
> "effective entitlement, not the column" rule are unchanged. **The `limits` shape changed** — it is
> now always an object with two nullable members — and two fields were added (`source`,
> `subscription`). Read BR-12 before building anything against this entry.

**Raised by:** Android client, the upgrade screen.
**Contract reference:** `docs/ANDROID_APP_PROMPT.md` §3.1a (the route) and §3.8 (the shape).

### Why

BR-10 shipped the wall without a way to describe it. The client could not tell a `FREE` account from
a granted `UNLIMITED` one, so it would have offered an upgrade to someone who already had one; it
did not know the limits, so it would have hardcoded two numbers that live in server configuration
and can be tuned without an app release; and it could not render "1 of 1 spaces used" at all. The
trigger was a real user: they created a second space on a `FREE` account, it was refused, and the
app showed a generic error because it had no idea why.

### The contract

```
GET /api/v1/users/me/plan          Authorization: Bearer {accessToken}

200 { "plan": "FREE",
      "limits": { "spaces": 1, "items": 100 },
      "usage":  { "spaces": 1, "activeItems": 19 } }

200 { "plan": "UNLIMITED",
      "limits": null,
      "usage":  { "spaces": 4, "activeItems": 19 } }

401  anonymously (standard error shape) — there is no unauthenticated variant
```

* > **SUPERSEDED BY BR-12 (2026-09-19): `limits` is now ALWAYS an object and the nullability moved
  > to its two MEMBERS.** The reasoning below still holds for why a null beats a flag; what changed
  > is that `MAX` ("ten spaces, unlimited ITEMS") cannot be expressed by a whole-object null at all.
  > Do not build a client against the paragraph that follows.
* **`limits` is `null` for an `UNLIMITED` account, and the key is always present.** Branch on
  `limits == null`. Two alternatives were considered and rejected: *omitting* the key means "missing
  is null", which is a decoder setting on the client rather than a property of the wire; *numbers
  plus an `unlimited` flag* states one fact twice, so `{"unlimited": true, "spaces": 1}` is
  representable and self-contradictory, and a client ignoring the flag would render a limit nobody
  enforces.
* **`usage` is present on both plans.** A granted account still shows "4 spaces, 19 items"; it just
  has no ceiling to show them against.
* **`usage.activeItems` counts exactly what the wall counts** — non-archived items — because the
  endpoint and the guard share the same two count expressions inside `PlanLimitEnforcer`. So
  `usage.spaces == limits.spaces` is true exactly when the next `POST /spaces` is a 409. A second
  `count` written separately is how the screen would start lying, and the integration tests assert
  the agreement rather than the arithmetic (create up to the limit, get refused, then read the
  endpoint back).
* **`plan` is the effective entitlement, not a copy of `users.plan`.** It is whatever
  `PlanLimitEnforcer#hasUnlimitedEntitlement` answers, which is the method the guards ask. Today
  that is exactly the column; when billing lands and the rule becomes "granted OR subscribed", a
  subscriber will read `UNLIMITED` here while `users.plan` stays `FREE`. **Telling a grant apart
  from a subscription is a different question** (it needs a "manage subscription" button) and will
  get its own field when there is a subscription to manage — do not infer it from this one.
* The naming asymmetry (`limits.items` vs `usage.activeItems`) is deliberate: the limit mirrors the
  configuration key `whereis.plans.free.items` (renamed from `whereis.limits.free.items` by
  BR-12), while the usage field has to say precisely what it
  counted, because "items" reads as "all items" and archived ones do not count.
* No path or query parameter: the account is the JWT subject, so asking about another user is
  unrepresentable rather than merely refused. The call is a pure read — it never grants or upgrades.
* Cost: three statements (one plan lookup + the two `count`s the guard itself runs). No new table,
  no new column, **no migration**. `users.plan` is still written only by V9's default or an
  operator's UPDATE.

### What the client must do

Fetch it when the upgrade/settings screen opens and after a `409 PLAN_LIMIT_REACHED`; do not poll it
on every list render and do not keep a local counter (it disagrees with the server the moment
another device creates an item).

> The rest of this paragraph is **superseded by BR-12**, which is the subscription contract it
> promised. `limits` is never null as a whole any more, so "when `limits == null`, show usage alone
> and no upgrade offer" is no longer the upgrade gate — use the ladder-index rule in
> `docs/ANDROID_APP_PROMPT.md` §3.8b. And there IS a paywall now:
> `POST /users/me/plan/purchases`.

---

## BR-12 — The four-tier ladder and Play purchase verification — **IMPLEMENTED 2026-09-19**

**Raised by:** the product decision that replaced FREE/UNLIMITED with a paid ladder.
**Contract reference:** `docs/ANDROID_APP_PROMPT.md` §3.1a (the routes) and §3.8a–c (the shapes).

### Why

BR-10 built a wall and BR-11 described it, but the wall still had no door: a `FREE` account at
either limit could not pay to get past it. Shipping that past closed testing is a Play policy
problem as well as a product one.

### The ladder

| tier | spaces | active items | Play product |
|---|---|---|---|
| `FREE` | 1 | 100 | — |
| `STANDARD` | 3 | 300 | `whereis_standard_annual` |
| `PRO` | 5 | 600 | `whereis_pro_annual` |
| `MAX` | 10 | **no limit** | `whereis_max_annual` |
| `UNLIMITED` | no limit | no limit | **operator-only, never purchasable** |

All three products are annual, auto-renewing, one base plan each (`annual`), each with a free trial
configured as an offer in Play Console. The trial length is **never** hardcoded anywhere — the app
renders it from the pricing phases.

### Three contract changes

**1. `limits` is now always an object; nullability moved to its two members. This SUPERSEDES BR-11.**
BR-11 said "`limits` is `null` for `UNLIMITED`" and told the upgrade screen to branch on it. `MAX`
is "ten spaces, unlimited ITEMS", which a whole-object null cannot express at all, so per-allowance
nulls became mandatory. Keeping both would give "everything is unlimited" two encodings — precisely
the "states one fact twice, so the wire can contradict itself" defect BR-11 rejected the flag for.
One rule survives and it is simpler than the one it replaces: **a null is exactly one thing, no
ceiling on that allowance.**

*The shipped Android build reads the new shape unchanged*, verified against the actual files rather
than assumed: `PlanLimitsDto(spaces: Int? = null, items: Int? = null)` already declares both members
nullable, `PlanAllowance(used, limit: Int?)` already means "unlimited" per allowance, and
`ignoreUnknownKeys = true` drops the new fields. A stale build shows a new subscriber "a plan this
version does not know about" and offers them nothing, which is the correct degradation — and it is
never shown FREE's numbers, because the limits come from the same response.

**2. Two new fields on `PlanStatus`.** `source` (`NONE | GRANT | SUBSCRIPTION`) answers the question
BR-11 explicitly deferred — "may this account manage a subscription?" — and `subscription` carries
the row itself. `subscription` is non-null exactly when the caller has an entitling row, **regardless
of which side won the max()**: a paid subscription must always be manageable, even by an account
that also holds a higher operator grant. The client's rule is the simple one — show "Manage
subscription" iff `subscription != null`; `source` is only for copy.

Deliberately NOT added: `inTrial`, `autoRenewing`, `formattedPrice`, `trialDays`. The first two are
unbacked by a column or derivable from `state`, and inventing state the schema does not carry is how
a report starts lying. Prices and trial lengths may come ONLY from Play `ProductDetails` on the
device — a server-side price is wrong for most countries and is grounds for store rejection.

**3. Two new endpoints.**

```
GET  /api/v1/plans                       -> the four-tier ladder, in ladder order, with product ids
POST /api/v1/users/me/plan/purchases     {purchaseToken, productId} -> 200 = the full PlanStatus
```

`GET /plans` costs zero statements (it is pure configuration) and is **the only source of tier
ordering a client may use**: a client that re-encodes the ladder is deciding a fact only the server
owns, and getting it wrong means offering a downgrade as an upgrade. `UNLIMITED` is absent — it
cannot be bought.

The purchase endpoint answers the full `PlanStatus`, byte-identical to `GET /users/me/plan`, so the
purchase result and the plan screen cannot disagree. **Adopt that body; do not issue a second GET.**

### The entitlement rule

```
effectiveTier = max( tier from users.plan , tier from any entitling subscription )
                over FREE < STANDARD < PRO < MAX < UNLIMITED
```

A row is ENTITLING when `entitled_until > now()` **and** `state IN (ACTIVE, IN_GRACE_PERIOD,
CANCELED)` **and** `voided_at IS NULL` **and** `superseded_by IS NULL`. `CANCELED` entitles —
auto-renew is off but the paid term is not over. `PAUSED` and `ON_HOLD` do not, even with a future
expiry. `entitled_until > now()` is the fail-closed guard: even if every notification is lost,
entitlement lapses on its own.

**`users.plan` is still never written by billing**, and the max() is why: an operator grant always
wins, a subscription can never downgrade a granted account, and revoking a grant leaves a paying
subscriber on their paid tier. `UNLIMITED` is unrepresentable in `user_subscriptions.tier` (a
database CHECK, not a convention), so "granted" and "paid" stay distinguishable forever.

### Failure modes and what the client does

See `docs/ANDROID_APP_PROMPT.md` §3.8c for the full table. The six new `ErrorCode` values are
`PLAY_UNAVAILABLE` (502), `PLAY_PURCHASE_INVALID` (400), `PLAY_PURCHASE_NOT_ACTIVE` (409),
`PLAY_PRODUCT_UNKNOWN` (400), `PLAY_PRODUCT_MISMATCH` (400) and `PLAN_PURCHASE_NOT_OWNED` (409).
**They must be added to the Android `ApiErrorCode` enum in the same wave** — without them every
branch of the retry policy collapses into `UNKNOWN`.

Two decisions worth naming:

* **State is evaluated before product.** A non-entitling purchase is always 409 (retryable, token
  kept), never a 400, so a state problem can never be answered with a code that tells the client to
  throw the token away. And the tier is resolved from **Google's** line item, not the client's claim,
  so a deferred downgrade — which keeps the OLD product on the token until the term ends — is
  accepted rather than discarded as a mismatch.
* **`acknowledged` is on the wire.** Google auto-refunds and revokes an unacknowledged purchase after
  3 days, and after **5 minutes** for a test purchase, which is every purchase on a closed track.
  The server acknowledges synchronously with a short bounded retry and always re-verifies an
  entitling row it finds with `acknowledged = false`. While the client sees `false` it must ignore
  its own 24-hour re-post rule and re-post on the next foreground. Those three things together are
  the **interim substitute for the reconciler**, which is out of scope this wave — not an
  optimisation.

### `obfuscatedAccountId` — the bytes are the contract

`BillingFlowParams.setObfuscatedAccountId(sha256Hex(userId))`, where `userId` is the JWT `sub`
claim, the hash input is the UUID's canonical `toString()` (36 chars, lowercase, hyphenated) encoded
as UTF-8, and the output is lowercase hex, exactly 64 characters. Golden vectors are asserted by
`PlayAccountHashTest` and quoted in §3.8c; copy them verbatim into an Android test. A mismatch is
409 `PLAN_PURCHASE_NOT_OWNED`, logged at WARN server-side with the user id and a twelve-hex token
digest; the client retries it on the normal 24-hour schedule rather than never, so a canonicalisation
slip is recoverable by a release instead of permanent.

An absent value is accepted — a promo code redeemed in the Play Store legitimately carries none —
and the token binding remains the binding protection: `user_id` and `purchase_token` are
`updatable = false`, so a token bound to one account can never be re-bound.

### Out of scope at the time — **ALL FOUR CLOSED BY BR-13 on 2026-09-19**

Left broken by this wave, and recorded as promotion gates:

* **The RTDN webhook handler.** `play_notifications` existed and was empty; nothing read the Pub/Sub
  topic. Pauses, holds, refunds and upgrades were not reflected.
* **Refund sweeps.** `voided_at` was never written, so a refunded annual purchase kept entitling for
  up to a year. (`purchases.voidedpurchases.list` defaults to `type=0`, one-time products, and needs
  `type=1` for subscriptions.)
* **The acknowledgement reconciler.** If the app was never reopened, a failed acknowledgement was
  never retried.
* **Upgrade/downgrade proration.** The schema supported it (`linked_purchase_token`,
  `superseded_by`); the replacement mode was a pricing decision.

**See BR-13**, which also closed a fifth problem this wave did not list: `AccountDeletionService`
had no billing awareness, so `DELETE /users/me` left Google charging a card for an account that no
longer existed, and neither legal page mentioned subscriptions. The remaining gates in
`deploy/README.md` are now about Play Console access rather than about missing code.

### Open questions

* Play Console does not exist yet, so the three product ids, the base plan id `annual` and the trial
  offers are **assumptions**. A wrong id renders as a tier with limits and no price and no button.
  `whereis.plans.*.product-id` is the single place to correct them and no release is needed — but
  somebody must check the console against that config before the first paid build.
* Who creates the Play service account and the RTDN Pub/Sub topic, and where `PLAY_SERVICE_ACCOUNT_JSON`
  lives. `deploy/.env` is on the VM's only disk and the nightly backup archives it; a Play publishing
  credential in an unencrypted backup on the same box is a different risk class from a database password.
* Privacy and legal: `user_subscriptions` stores a Google purchase token and `play_notifications` will
  store raw RTDN payloads, neither of which the privacy page mentions. Account deletion does not remove
  `play_notifications` rows (there is deliberately no `user_id` on that table — a notification can
  arrive for a token this server has never seen). Legal review before the Data safety form is submitted.
* **Unarchiving is deliberately unguarded**, so an account at its item ceiling can unarchive to one
  over. The item exists and the user owns it; refusing would make an item they own permanently
  unrestorable, which IS taking something away. Pinned by `PlanTierTransitionIT` so nobody "fixes" it.
  Confirm with the product owner that this is the intended reading.
* A per-user abuse cap beyond the 60-second re-verify window. An unknown token still costs one Google
  API call against a per-project quota everyone shares.
* `source = SUBSCRIPTION` wins the tie when a grant and a subscription name the same tier. Confirm the
  support copy ("your Pro access is a grant" vs "manage your Pro subscription") for that account.

---

## BR-13 — The RTDN lifecycle: notifications, refunds, the reconciler, tier changes, and deletion with a live subscription — **IMPLEMENTED 2026-09-19**

**Raised by:** the four promotion gates BR-12 left open in `deploy/README.md`.
**Contract reference:** `docs/ANDROID_APP_PROMPT.md` §3.8d (the subscription strip and tier changes).

### Why

BR-12 shipped the ladder, the schema and the verify endpoint, and closed with four gates. Three of
them cost real money on a closed track:

* **no RTDN handler.** `play_notifications` existed and was empty. Pauses, holds, refunds, upgrades
  and revocations were invisible; only the fail-closed `entitled_until > now()` predicate worked.
* **no voided-purchase handling.** A refunded annual purchase kept entitling for **up to a year**.
* **no acknowledgement reconciler.** Google auto-refunds an unacknowledged purchase after 3 days —
  **5 minutes** for a test purchase, i.e. every purchase a license tester makes — so one transient
  5xx left an account this database said was entitled for a year and Google had silently refunded.

And one that is indefensible rather than expensive: **`AccountDeletionService` had no billing
awareness at all.** `user_subscriptions.user_id` is `ON DELETE CASCADE`, so the row vanished with the
account and Google kept charging the card for a product the person could no longer sign in to.
`src/main/resources/legal/delete-account.html` — the page Play links to from the Data-safety form —
did not contain the word "subscription" in either language.

### What the client needs to know (the whole wire change)

**`PlanStatusResponse.subscription` is now non-null whenever a LIVE Play purchase exists, not only an
entitling one.** This is a deliberate WIDENING and it supersedes BR-12's "non-null exactly when an
entitling row exists". The predicate is
`purchase_token IS NOT NULL AND voided_at IS NULL AND superseded_by IS NULL AND state NOT IN (EXPIRED, PENDING_PURCHASE_CANCELED)`.

The reason is a real defect in the shipped build: an `ON_HOLD` subscriber is **not** entitled, so
their badge reads `FREE` — and wave 1 reported `subscription: null` for them, which took away the
only control that could fix their failed payment, from an account that was still being charged.

Three new fields carry it:

| field | type | meaning |
|---|---|---|
| `entitling` | `boolean` | whether THIS row currently entitles. The difference between "you keep Pro until 14 March" and "Pro is paused" — **never** used to compute a tier |
| `pendingProductId` | `string?` | Google's product id for a DEFERRED change that takes effect at `entitledUntil` |
| `pendingTier` | `PlanTier?` | the tier behind it, resolved at READ time through the catalog. **Null rather than wrong** for a product this deployment does not configure |

**THE ONE RULE THE PLAN SCREEN HANGS ON.** The badge describes the ENTITLEMENT (`plan`), the strip
describes the SUBSCRIPTION (`subscription`), and **neither is derived from the other**. An `ON_HOLD`
PRO subscriber reads `plan: "FREE"` and `subscription: {tier: "PRO", state: "ON_HOLD", entitling:
false}` at the same time, on the same screen. That pair is not a contradiction — it is the honest
answer, and showing the paid tier because a subscription object exists is the exact lie this change
exists to prevent.

`plan`, `limits` and `usage` still come from the entitling finder ALONE. A unit test pins it: an
entitling `STANDARD` row beside a live `ON_HOLD` `MAX` row must badge `STANDARD`.

### What was built

* **`POST /play/rtdn`** — the Pub/Sub push endpoint, outside `/api/v1` because it is not part of the
  client contract and has no JWT. TWO INDEPENDENT authentication checks (a shared secret in the push
  URL's query string, compared in constant time, and Google's OIDC push token), its own `@Order(0)`
  security chain so the resource-server filter never sees Google's RS256 token, an exhaustive ack
  decision table, and a controller-local exception handler so nothing escapes to the global advice.
* **The full notification state machine.** The notification is a TRIGGER and an ORDERING TOKEN, not a
  fact: every type except revocation re-reads `subscriptionsv2.get` and writes what Google says
  through the same writer the verify endpoint uses. A type added after this ships is REFRESHED, not
  ignored.
* **Refunds, through two independent entry points** — `voidedPurchaseNotification` (immediate, no
  Google call, so a Play outage cannot keep a refunded user entitled) and a six-hourly sweep of
  `purchases.voidedpurchases.list` **with `type=1`** over a fixed 7-day look-back.
* **`SubscriptionReconciler`** — drift repair plus the acknowledgement retry, unacknowledged rows
  first.
* **Tier changes** — `linkedPurchaseToken` resolved with a userId-SCOPED finder (V10's hard
  contract) and `superseded_by` set so the old row stops entitling at once; the server has no branch
  anywhere on "upgrade or downgrade".
* **Account deletion** — one new first step, `SubscriptionCancellationService.enqueueFor`, writing an
  outbox row that `PlayCancellationJanitor` drains against
  `purchases.subscriptions.cancel`. Both legal pages now say what happens, including what happens
  when the cancellation never succeeds.

### Decisions worth keeping

* **A REVOKE is never gated on the monotonic watermark.** A refund and its accompanying
  `SUBSCRIPTION_CANCELED` are emitted milliseconds apart and Pub/Sub guarantees no order; if the
  cancellation landed first, a watermark-guarded revoke would be discarded as stale, leaving the row
  `CANCELED` with a future expiry — one of the three ENTITLING states. Write-once `voided_at` already
  gives redelivery safety, so the watermark buys nothing there and could only lose refunds.
* **A void is decided by the TOKEN, not by `productType`.** `productType != 1` fails OPEN: an absent
  field makes the test true and every refund is silently ignored.
* **A refresh that matches no offered line item keeps the row's frozen tier**, rather than throwing
  `PLAY_PRODUCT_MISMATCH`. `EXPIRED` and `REVOKED` are exactly when Google is most likely to return
  no usable line item, and the exception produced a 400 the ledger never recorded plus a reconciler
  batch that aborted on the same row forever.
* **Nothing is ever revoked on the strength of a Google ERROR.** A 404 ends the message; a 400 is
  retried with a ceiling, because a 400 can be our own bug.
* **The cancellation enqueue predicate is only `purchase_token IS NOT NULL`.** Every narrower version
  was rejected: a `state IN (…)` list decides from local state that may be stale, and even
  `voided_at IS NULL` is wrong — a refund of one payment without revocation leaves auto-renew ON.
  Cancelling twice is a no-op at Google; not cancelling once costs the user a year of charges.
* **The janitor re-checks the token before cancelling.** The legal page invites the person to
  re-register, and the client re-posts the same token on the first foreground, so without the check
  the janitor would turn auto-renew off for somebody who did not ask.
* **`voided_at` has an operator repair, documented in `deploy/README.md` Step 11d.** A revocation is
  the only thing applied from a notification with no corroboration, so an erroneous one was
  permanent; every applied revoke is now logged at WARN so it can be FOUND, and clearing
  `verified_at` alongside `voided_at` lets Google — not the operator — restore the true state.

### Still open

* **Play Console access.** Everything in `deploy/README.md` Step 11 — the Pub/Sub topic, the push
  subscription with an EXPLICIT audience, the ack deadline, the backoff, the 7-day retention — is
  Console and Cloud work this repository cannot do or verify. The handler receives nothing until it
  is done, and a `play_notifications` table that is still empty a day after the first tester purchase
  is the symptom.
* **The audience trap.** Cloud Pub/Sub makes the OIDC audience optional and fills `aud` with the full
  push URL — shared secret included — when it is left blank. The app refuses to boot on a URL-shaped
  audience, but nothing can stop the subscription being created without one.
* **Upgrade/downgrade replacement modes are unverified against real Play behaviour**, and whether a
  DEFERRED downgrade issues a NEW token or retains the old one is not settled. The server is correct
  either way; one real upgrade and one real downgrade on the closed track would replace the
  assumption with an observation.
* **Account deletion refunds nothing.** `subscriptionsv2.revoke` exists in the pinned client and
  would refund and end access immediately; cancelling instead is a COMMERCIAL decision, and it is now
  stated on a public page. A human should sign it off.
* **Nothing notices if RTDN stops arriving.** A ledger with no rows for 48 hours is indistinguishable
  from a quiet week at this user count, and the reconciler papers over it.
* **The reconciler's throughput ceiling is ~1,200 live subscriptions** (batch 25 every 15 minutes
  against a 12-hour staleness target). Past that it silently stops keeping up; a WARN fires when a
  full batch coincides with an over-stale oldest candidate, which is the only observable signal.
* **`play_notifications` is not deleted by account deletion**, deliberately — there is no `user_id` on
  that table, because a notification can arrive for a token this server has never seen. The rows hold
  a purchase token and Google's payload. Legal review before the Data-safety form is submitted; the
  privacy page now names the token and Google as its processor, but not this table's retention.
