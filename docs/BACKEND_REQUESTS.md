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
