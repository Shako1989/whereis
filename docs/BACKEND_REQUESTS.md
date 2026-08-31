# Backend change requests — from the mobile clients

Gaps found while building the Android client against the frozen backend. Each entry states the
endpoint, the shape the client needs, why, and the workaround shipped in the meantime. Nothing here
has been implemented on the server; the apps work around all of it.

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


## BR-2 — `NEEDS_CONFIRMATION` has no confirmation channel

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

## BR-3 — `primaryImageUrl` missing from `ItemResponse` (N+1 in the items list)

**Raised:** 2026-08-30, from the Android items list.

**Today:** `primaryImageUrl` exists only on `ItemSearchResult` (`GET /items/search`). Neither
`GET /items` nor `GET /items/{itemId}` carries a thumbnail.

**Why it matters:** the items list shows each item's cover photo. With no thumbnail in the page
payload, the only way to learn whether an item has one is `GET /items/{id}/files` — **one extra call
per row**. That is an N+1 against data the backend already resolves in a single batch query for
search results.

**Asked for:** add `primaryImageUrl` (presigned, nullable) to `ItemResponse`, resolved with the same
batch lookup `PostgresSearchService` performs today — one query per page, not one per row.

**Client workaround shipped:** `ItemThumbnails` resolves the cover per visible row and caches it —
found photos indefinitely (a `fileId` is stable), misses for 30s. Only rows actually scrolled past
cost a call.

## BR-4 — Non-English sentences are not understood by the `mock` provider

**Raised:** 2026-08-30, verified against the running backend.

**Today:** with `AI_PROVIDER=mock`, `MockAiAssistant.PLACEMENT` matches hardcoded English verbs
(`put|placed|left|stored|keep|kept|moved`) and prepositions (`in|into|inside|on|at|to`). A natural
Azerbaijani sentence returns `NOT_UNDERSTOOD`. Azerbaijani is verb-final and marks place with case
suffixes, so this regex shape cannot be extended to cover it.

**Not a defect in the trust boundary:** `InterpretationValidator.SAFE_NAME` uses `\p{L}`, so
Azerbaijani characters pass validation. Verified — `"I put my çantamı in the qonaq otağı şkafı"` was
stored as `Çantamı` in `Ev > Qonaq Otağı Şkafı`.

**Asked for:** nothing structural; `AI_PROVIDER=openai` handles it. Recorded so it is not
rediscovered as a client bug. The mock's English stopword list limits `assistant/search` the same
way, so both modes start working together.
