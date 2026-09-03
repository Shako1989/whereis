# Build prompt — `whereis` Android app

> Hand this whole document to the developer (human or AI agent) who will build the Android client.
> It is self-contained: product intent, the exact backend contract as implemented, the client-side
> rules that fall out of that contract, the screen inventory, and the definition of done.
> Sections 3–5 are backend truth extracted from the running code, not aspiration — do not change
> them unilaterally; if the app needs something different, raise a backend change request.

---

## 1. Your assignment

Build a production-quality **native Android application** for `whereis` — a personal
"Where did I put this item?" tracker. The backend is complete and running (Java 21 / Spring Boot 3.5,
REST under `/api/v1`, JWT bearer auth). You are building the client only. Do not modify the backend;
if you find a gap, list it in `docs/BACKEND_REQUESTS.md` and work around it (see §7 for the ones
already known).

The app must make one thing effortless: **you put something somewhere, you tell the app in plain
language, and later the app tells you where it is.** Everything else — spaces, location trees,
photos, history — exists to serve that sentence.

---

## 2. Product context

### 2.1 The domain

- A **Space** is a physical place you own: `HOME`, `OFFICE`, `CAR`, `GARAGE`, `WAREHOUSE`, `OTHER`.
  One user owns many spaces. Space names are unique per user (case/whitespace-normalized).
- A **Location** is a node in a recursive tree inside one space — unlimited depth. Types:
  `ROOM`, `FURNITURE`, `CABINET`, `DRAWER`, `SHELF`, `BOX`, `DESK`, `BAG`, `CONTAINER`, `OTHER`.
  A location's parent is always in the same space. Sibling names are unique at each level.
  Example chain: `Home > Bedroom > Wardrobe > Top drawer`.
- An **Item** lives at exactly **one** current location and carries a **complete movement history**.
  History rows survive location deletion (they store a path snapshot).
- **Photos** attach to items and are stored in MinIO; the database holds only metadata.
- The **AI assistant** does two things: turn a sentence into a stored item ("remember"), and answer
  a question from stored rows ("search"). It never invents facts and never writes without validation.

### 2.2 The journey the app must nail (this is the acceptance test)

1. Register a new account.
2. Create a space called **Home**.
3. Say: *"I put my passport in the bedroom wardrobe top drawer."* → the app shows the item created and
   the location chain `Bedroom > Wardrobe > Top drawer` that was auto-created.
4. Ask: *"Where is my passport?"* → the app answers with the real stored path.
5. Move the passport to `Office > Desk > Drawer` through the move flow.
6. Open history → the old path is shown as a closed period, the new one as the current, open period.
7. Nothing from another user is ever visible.

Ship the app only when this journey passes as an automated instrumented test against a real backend.

### 2.3 Design principles for the client

- **Capture in under five seconds.** The home screen's primary action is a single text/voice field
  wired to the assistant. Manual forms are the fallback, not the default.
- **Retrieval is the payoff.** Search results must show item name, full location path, and photo.
- **Never lose history.** Moves are first-class and always recorded; the app must never "edit" an
  item's location silently.
- **Offline-tolerant reads.** The user often searches in a basement with no signal. Cached reads
  must work; writes may require connectivity in v1.
- **Trust the server.** The device never decides ownership, never sends a user id, never assumes a
  cached row is still authoritative after a 401/404.

---

## 3. Backend contract (as implemented — authoritative)

Base URL: `{host}/api/v1`. All bodies JSON UTF-8 unless stated. All timestamps are **UTC ISO-8601
instants** (`2026-08-30T12:34:56.789Z`). All ids are **UUID v4 strings**.

### 3.1 Authentication

| Method | Path | Auth | Body | Success |
|---|---|---|---|---|
| POST | `/auth/register` | none | `{email, password, firstName?, lastName?}` | **201** `TokenPair` |
| POST | `/auth/login` | none | `{email, password}` | **200** `TokenPair` |
| POST | `/auth/refresh` | none | `{refreshToken}` | **200** `TokenPair` |

```jsonc
// TokenPair
{ "accessToken": "eyJ...", "refreshToken": "opaque-string",
  "tokenType": "Bearer", "expiresInSeconds": 900 }
```

Constraints: `email` ≤ 320 and RFC-valid; `password` 8–72 chars; `firstName`/`lastName` ≤ 100;
`refreshToken` ≤ 512.

- Access token is a **HS256 JWT**, TTL **15 minutes** by default. Its `sub` claim is the user id and it
  carries an `email` claim. Treat both as read-only display/telemetry data.
- Refresh token is an **opaque rotating** string, TTL **30 days**. Every successful refresh returns a
  **new** refresh token and invalidates the old one.
- **Reuse of an already-rotated refresh token revokes the entire token family** — the user is logged
  out everywhere. This single fact drives §4.1; read it before writing any networking code.
- Login is enumeration-safe: wrong email and wrong password both return `INVALID_CREDENTIALS`.
  Do not write UI copy that distinguishes them.

Every other endpoint requires `Authorization: Bearer {accessToken}`.

### 3.2 Spaces — `/spaces`

| Method | Path | Body | Success |
|---|---|---|---|
| POST | `/spaces` | `{name, description?, type}` | 201 `Space` |
| GET | `/spaces` | — | 200 `Space[]` |
| GET | `/spaces/{spaceId}` | — | 200 `Space` |
| PUT | `/spaces/{spaceId}` | `{name, description?, type}` | 200 `Space` |
| DELETE | `/spaces/{spaceId}` | — | **204** |

```jsonc
// Space
{ "id":"uuid", "name":"Home", "description":null, "type":"HOME",
  "createdAt":"…Z", "updatedAt":"…Z" }
```

`name` ≤ 80 required; `description` ≤ 500; `type` required, one of the six `SpaceType` values.
`PUT` is a **full replace** — always send all three fields.
DELETE returns **409 `SPACE_NOT_EMPTY`** while the space still has locations.
Duplicate name for the same user → **409 `DUPLICATE_NAME`**.

### 3.3 Locations

| Method | Path | Body | Success |
|---|---|---|---|
| POST | `/spaces/{spaceId}/locations` | `{name, description?, type, parentLocationId?}` | 201 `Location` |
| GET | `/spaces/{spaceId}/locations` | — | 200 `Location[]` (flat) |
| GET | `/spaces/{spaceId}/location-tree` | — | 200 `TreeNode[]` (nested) |
| GET | `/locations/{locationId}` | — | 200 `Location` |
| GET | `/locations/{locationId}/children` | — | 200 `Location[]` |
| PUT | `/locations/{locationId}` | `{name, description?, type, parentLocationId?}` | 200 `Location` |
| DELETE | `/locations/{locationId}` | — | **204** |

```jsonc
// Location
{ "id":"uuid", "spaceId":"uuid", "parentLocationId":"uuid|null", "name":"Top drawer",
  "description":null, "type":"DRAWER", "createdAt":"…Z", "updatedAt":"…Z" }

// TreeNode (recursive)
{ "id":"uuid", "name":"Bedroom", "type":"ROOM", "children":[ { … } ] }
```

`name` ≤ 80 required; `description` ≤ 500; `type` required.
`parentLocationId` omitted/`null` on POST = **root of the space**; on PUT it is **applied as given**,
so sending `null` **re-parents the location to the space root**. Never omit it accidentally on edit.
Errors: **409 `LOCATION_NOT_EMPTY`** (has children or items), **400 `CYCLE_DETECTED`**,
**400 `INVALID_LOCATION_HIERARCHY`** (parent in another space), **409 `DUPLICATE_NAME`** (sibling clash).

### 3.4 Items — `/items`

| Method | Path | Body / Query | Success |
|---|---|---|---|
| POST | `/items` | `{name, description?, category?, locationId}` | 201 `Item` |
| GET | `/items` | `?page=0&size=20&sort=updatedAt,desc&includeArchived=false` | 200 **Spring `Page<Item>`** |
| GET | `/items/search` | `?q=…&limit=20` | 200 `SearchResult[]` |
| GET | `/items/{itemId}` | — | 200 `Item` |
| PUT | `/items/{itemId}` | `{name, description?, category?, archived?}` | 200 `Item` |
| POST | `/items/{itemId}/move` | `{locationId, note?}` | 200 `Item` |
| GET | `/items/{itemId}/history` | — | 200 `History[]` |
| DELETE | `/items/{itemId}` | — | **204** |

```jsonc
// Item
{ "id":"uuid", "name":"Passport", "description":null, "category":"documents",
  "currentLocationId":"uuid",
  "locationPath":["Bedroom","Wardrobe","Top drawer"],   // ARRAY of segments
  "archived":false, "createdAt":"…Z", "updatedAt":"…Z" }

// SearchResult
{ "id":"uuid", "name":"Passport",
  "locationPath":["Bedroom","Wardrobe","Top drawer"],   // ARRAY
  "primaryImageUrl":"https://…presigned…|null", "updatedAt":"…Z" }

// History
{ "id":"uuid", "locationId":"uuid|null",
  "locationPath":"Bedroom > Wardrobe > Top drawer",      // STRING, " > " separated
  "note":"moved for trip", "placedAt":"…Z", "removedAt":"…Z|null" }
```

**Watch the inconsistency:** `locationPath` is a `List<String>` on `Item`/`SearchResult` but a single
`" > "`-joined `String` on `History`. Model them as two distinct types; render both through one
formatter so the UI looks uniform.

- **`locationPath` includes the SPACE name as its first segment** — verified against a running
  backend: `["Home", "Bedroom", "Wardrobe", "Top Drawer"]`, not `["Bedroom", ...]`. So the path is
  space-qualified and already unambiguous across spaces; do not prepend the space name yourself, and
  remember `LocationPathChip` elision must keep the first segment (the space) visible.
- `name` ≤ 120 required; `description` ≤ 2000; `category` ≤ 100; `note` ≤ 500.
- **`PUT /items/{id}` cannot change location.** Location changes go through `/move` only, because
  `/move` is what writes history. Enforce this in the client's repository layer, not just the UI.
- `history` is ordered newest-first; exactly one row has `removedAt == null` (the current placement).
- Sort: `sort={property},{asc|desc}`. Allowed properties: **`name`, `category`, `createdAt`,
  `updatedAt`**. Anything else is silently coerced to `updatedAt`. Direction defaults to `desc`.
- `size` is clamped server-side to a **maximum of 100**.
- `GET /items` returns Spring's page envelope — `{content:[…], totalElements, totalPages, number,
  size, first, last, numberOfElements, empty, sort:{…}, pageable:{…}}`. Deserialize only the fields
  you need and ignore the rest.
- `GET /items/search` returns a **plain array**, not a page. `q` is required; `limit` defaults to 20.
  Matching is PostgreSQL trigram-ranked over item fields **and** items inside any matching location's
  subtree — so searching "wardrobe" finds items in the wardrobe.

### 3.5 Item files — `/items/{itemId}/files`

| Method | Path | Request | Success |
|---|---|---|---|
| POST | `/items/{itemId}/files?primary=false` | `multipart/form-data`, part name **`file`** | 201 `ItemFile` |
| GET | `/items/{itemId}/files` | — | 200 `ItemFile[]` |
| DELETE | `/items/{itemId}/files/{fileId}` | — | **204** |
| GET | `/items/{itemId}/files/{fileId}/url` | — | 200 `{url, expiresAt}` |

```jsonc
// ItemFile
{ "id":"uuid", "itemId":"uuid", "originalFileName":"IMG_0042.jpg",
  "contentType":"image/jpeg", "fileSize":184320, "isPrimary":true, "createdAt":"…Z" }
```

- Multipart part name must be exactly **`file`**. `primary` is a **query parameter**, not a part.
- Allowed types: **JPEG, PNG, WebP only**, verified by magic bytes — a renamed `.jpg` that is really
  a PDF or SVG is rejected with **415 `UNSUPPORTED_MEDIA_TYPE`**. HEIC from the camera **must be
  transcoded to JPEG on-device before upload.**
- Max file size **10 MB**, max request **12 MB** → **413 `FILE_TOO_LARGE`**.
- At most one primary photo per item; uploading with `primary=true` moves the flag.
- `GET …/url` returns a **presigned URL valid for ~10 minutes**. It is a direct object-storage URL —
  do **not** attach the `Authorization` header to it, and do **not** persist it as durable state.

### 3.6 Assistant — `/assistant`

| Method | Path | Request | Success |
|---|---|---|---|
| POST | `/assistant/remember` | `{message}` (≤ 1000), optional `spaceId` | 200 `RememberResponse` |
| POST | `/assistant/search` | `{query}` (≤ 500) | 200 `{answer, items:SearchResult[]}` |
| POST | `/assistant/images/analyze` | multipart, part `file` | 200 `{suggestions:[{name,category}], note}` |

```jsonc
// RememberResponse
{ "status":"CREATED|NEEDS_CONFIRMATION|NOT_UNDERSTOOD",
  "message":"human-readable explanation",
  "item": { …Item… } | null,
  "createdLocations": ["Bedroom","Wardrobe","Top drawer"],   // newly auto-created, may be empty
  "candidateSpaces": [ {"id":"uuid","name":"Home"} ] }        // only on NEEDS_CONFIRMATION
```

- `CREATED` — item stored. Show it, show `createdLocations` as "I also created: …", offer Undo
  (Undo = `DELETE /items/{id}`; the auto-created locations stay, which is acceptable).
- `NEEDS_CONFIRMATION` — the message was understood but the target space was ambiguous.
  **Answer it by resending the same `message` with `spaceId` set to the chosen `candidateSpaces[].id`.**
  The id settles the space outright; the assistant does not ask again. A space that is not yours
  is a 404 like every other ownership miss. The `message` distinguishes three cases: no spaces yet,
  a named space that does not exist yet ("You don't have a space for \"Office\"..."), and a
  genuinely ambiguous one — render it verbatim above the picker.
  **Zero writes happened.** Show `candidateSpaces` as a picker (see §7.1 for the required workaround).
- `NOT_UNDERSTOOD` — show `message` and fall back to the manual add-item form, pre-filled with
  whatever the user typed as the item name.
- `search.answer` is composed from database rows only, never free-form AI text. Render it as the
  headline, `items` as the result list.
- `images/analyze` returns **suggestions only** — nothing is persisted. The user must confirm each
  suggestion, which creates items via the normal `POST /items` path. Against both real providers
  (`openai` and `claude`) this endpoint currently returns **501 `AI_NOT_IMPLEMENTED`** — handle that as a graceful
  "not available yet" state, not a crash.

### 3.7 Errors — one shape everywhere

```jsonc
{ "timestamp":"2026-08-30T12:34:56.789Z", "status":404, "code":"ITEM_NOT_FOUND",
  "message":"…", "path":"/api/v1/items/…" }
```

`code` is one of:
`VALIDATION_ERROR`, `USER_NOT_FOUND`, `SPACE_NOT_FOUND`, `LOCATION_NOT_FOUND`, `ITEM_NOT_FOUND`,
`FILE_NOT_FOUND`, `EMAIL_IN_USE`, `DUPLICATE_NAME`, `INVALID_CREDENTIALS`, `TOKEN_INVALID`,
`TOKEN_EXPIRED`, `INVALID_LOCATION_HIERARCHY`, `CYCLE_DETECTED`, `LOCATION_NOT_EMPTY`,
`SPACE_NOT_EMPTY`, `FILE_TOO_LARGE`, `UNSUPPORTED_MEDIA_TYPE`, `STORAGE_ERROR`, `AI_UNAVAILABLE`,
`AI_NOT_IMPLEMENTED`, `CONFLICT`, `INTERNAL_ERROR`.

Map every one of these to a specific, human, localized string. Never surface a raw `code` or the
server `message` verbatim to the user; never show a stack trace or an HTTP number.

**Ownership misses return 404, not 403 — deliberately.** A resource that belongs to someone else is
indistinguishable from one that does not exist. Your UI must respect that: on 404 say
*"This item is no longer available"* and pop back to the list. Never say "you don't have permission".

---

## 4. Client rules that follow from the contract (non-negotiable)

### 4.1 Single-flight token refresh — the most important rule in this document

Refresh tokens rotate, and **presenting a rotated token revokes the whole family**. If two requests
401 at the same time and both call `/auth/refresh`, the second one presents an already-consumed token
and logs the user out of every device. This is not theoretical; it is the default outcome of a naive
`Authenticator`.

Required implementation:

- One OkHttp `Authenticator` (or interceptor) guarded by a **`Mutex`**. The first caller refreshes;
  every other caller **awaits the same result** and retries with whatever token that produced.
- Re-read the stored token **inside** the mutex before deciding to refresh — if another coroutine
  already rotated it, just retry with the new one.
- Cap retries (`responseCount >= 2` → give up) so a permanently invalid token cannot loop.
- On refresh failure (`401`/`TOKEN_INVALID`/`TOKEN_EXPIRED`): wipe tokens, clear the local database,
  and navigate to Login. There is no silent recovery.
- Proactively refresh when the access token has < 60 s left, to keep uploads from dying mid-stream.
- Write a unit test that fires 20 concurrent 401s and asserts **exactly one** `/auth/refresh` call.

### 4.2 Never send a user id

The server derives ownership solely from the JWT subject. There is no user id field in any request
body or query. If you find yourself wanting one, you have misread the contract.

### 4.3 Presigned image URLs are ephemeral

`primaryImageUrl` and `…/files/{id}/url` expire in ~10 minutes.

- **Cache key must be the `fileId`, never the URL** — otherwise every re-presign is a cache miss and
  the user re-downloads their whole library.
- On `403`/`404` from object storage, re-fetch the presigned URL once and retry.
- Never attach `Authorization` to these requests.
- Never store the URL in Room as if it were durable. Store `fileId`; resolve the URL on demand.

### 4.4 Forward-compatible enums

`SpaceType` and `LocationType` will gain values. Deserialize unknown values to `OTHER` rather than
throwing, and keep the raw string so a round-trip `PUT` does not silently downgrade the record.

### 4.5 Full-replace PUTs

`PUT /spaces/{id}` and `PUT /locations/{id}` replace all fields. Always send the complete object,
seeded from the currently loaded entity. Partial payloads will null out `description` and, on
locations, silently re-parent to root.

### 4.6 Correlation ids

Send a UUID `X-Correlation-Id` header on every request and log it with client-side errors. The
backend echoes it into its logs — it turns "the app broke" into a one-grep investigation.

### 4.7 Privacy in logs

Never log tokens, passwords, presigned URLs, or the user's assistant messages above `DEBUG`, and
strip all of it from release builds. The backend holds itself to this rule; the client must match it.

---

## 5. Technical requirements

### 5.1 Stack

| Concern | Choice |
|---|---|
| Language | Kotlin 2.x, coroutines + Flow, explicit API mode on |
| Min / target SDK | `minSdk 26`, `targetSdk`/`compileSdk` = current stable (36) |
| Build | Gradle Kotlin DSL + **version catalog** (`libs.versions.toml`), JDK 17 toolchain |
| UI | **Jetpack Compose** + Material 3, dynamic color, dark mode, edge-to-edge |
| Navigation | Navigation Compose with **type-safe routes** (`@Serializable` destinations) |
| DI | Hilt |
| Network | Retrofit + OkHttp + `kotlinx.serialization` (`ignoreUnknownKeys = true`, `explicitNulls = false`) |
| Local cache | Room (spaces, location tree, items, files metadata) + Paging 3 `RemoteMediator` for the item list |
| Token storage | DataStore encrypted with an **Android Keystore** AES/GCM key. Do **not** use `androidx.security:security-crypto` (deprecated). |
| Images | Coil 3 with a custom keyer (§4.3) |
| Camera | CameraX; transcode to JPEG ≤ 10 MB before upload |
| Background | WorkManager for retryable photo uploads |
| Tests | JUnit + Turbine + MockWebServer + Compose UI tests + one instrumented E2E |

No RxJava, no LiveData, no XML layouts, no `GlobalScope`.

### 5.2 Architecture

MVVM with unidirectional data flow: `UI → Event → ViewModel → UseCase/Repository → DataSource`,
state exposed as a single immutable `UiState` per screen via `StateFlow`.

**Package by feature**, mirroring the backend so the two codebases stay legible together:

```
az.technest.whereis.android/
  core/network/      Retrofit services, DTOs, AuthAuthenticator, error mapping, correlation id
  core/database/     Room entities, DAOs, converters
  core/datastore/    encrypted token + settings store
  core/designsystem/ theme, tokens, shared composables (LocationPathChip, TypeIcon, EmptyState…)
  core/common/       Result wrapper, dispatchers, UUID/Instant helpers
  feature/auth/      login, register, session gate
  feature/space/     list, create, edit, delete
  feature/location/  tree browser, node CRUD, location picker
  feature/item/      list, detail, create, edit, move, history
  feature/file/      capture, upload, gallery, primary selection
  feature/assistant/ remember, ask, image analyze
  feature/settings/  account, language, theme, logout, debug host
```

Repositories return a `Result<T>` / sealed `ApiError` type — **never** raw `Response` or thrown
`HttpException` — so every ViewModel handles failure explicitly. One `ErrorMapper` translates
HTTP status + `code` into a localized user-facing message; it is the only place that knows about
`ErrorCode`.

Offline policy for v1: **cache reads, require connectivity for writes.** Show cached data with a
"last updated" affordance; queue only photo uploads. Do not build offline write reconciliation —
server-side normalized-name uniqueness makes naive replay produce `DUPLICATE_NAME` storms.

### 5.3 Configuration

- Build flavors / build config for `baseUrl`. Emulator → `http://10.0.2.2:8080/api/v1`.
- Debug builds: a Settings field to override the host, so QA can point at a LAN backend.
- Cleartext HTTP allowed in **debug only** via a network security config; release is HTTPS-only with
  cleartext disabled.
- **Deployment note for whoever runs the backend:** presigned URLs are generated against
  `MINIO_EXTERNAL_ENDPOINT`. If it stays `localhost:9000`, every phone and emulator will fail to load
  images. It must be set to a host the device can reach.

### 5.4 Quality bar

- `./gradlew build` green; ktlint/detekt clean; no compiler warnings in app code.
- Unit tests for: the refresh mutex (§4.1), `ErrorMapper` across all 22 codes, sort-param building,
  location-tree flattening, path formatting for both `locationPath` shapes.
- MockWebServer tests for every repository, including the 404-as-not-found and 409-guard paths.
- One instrumented end-to-end test executing §2.2 against a real backend.
- Accessibility: content descriptions on all icon buttons, ≥ 48 dp touch targets, TalkBack pass on
  the capture and search flows, text scaling to 200% without clipping.
- Localization: **English, Azerbaijani, Russian**. No hardcoded strings. Locale-aware dates.

---

## 6. Screens

1. **Session gate / splash** — valid token → Home; else Login. No visible flash.
2. **Login** — email + password, inline validation, one generic credentials error (§3.1).
3. **Register** — email, password (8–72, strength meter), optional first/last name. `EMAIL_IN_USE`
   maps to a field-level error on email.
4. **Home / Ask** — the hero screen. A prominent input bound to the assistant, with a mode toggle
   *Remember* ↔ *Ask*, voice input via the platform recognizer, and below it: recent items and
   quick actions. Assistant responses render inline as cards.
5. **Remember result** — `CREATED`: item card + "also created: Bedroom › Wardrobe › Top drawer" +
   Undo. `NEEDS_CONFIRMATION`: space picker built from `candidateSpaces` (§7.1).
   `NOT_UNDERSTOOD`: explanation + "Add manually" leading to screen 9 pre-filled.
6. **Ask result** — the `answer` headline, then result cards (photo, name, full path, updated-at),
   tapping through to item detail.
7. **Spaces** — list with type icons and item counts; create/edit sheet; delete with a confirmation
   that explains the `SPACE_NOT_EMPTY` guard *before* attempting it.
8. **Location tree** — expandable tree per space with type icons, inline add-child, edit, delete
   (explaining `LOCATION_NOT_EMPTY`), and drag-or-menu re-parent. A reusable
   **LocationPicker** variant of this screen is used by add-item and move.
9. **Items list** — Paging 3 list, sort menu (name / category / created / updated × asc/desc),
   include-archived toggle, search field wired to `/items/search`, swipe-to-archive.
10. **Item detail** — photo carousel with primary badge, name/category/description, full location
    path as tappable breadcrumbs, and actions: Move, Add photo, Edit, Archive, Delete.
11. **Move item** — LocationPicker + optional note (≤ 500), preview of "from → to", confirm.
12. **History** — vertical timeline: current open placement highlighted at top, then closed periods
    with path snapshot, note, placed/removed timestamps and duration. Handle `locationId == null`
    (the location was deleted) by showing the snapshot path in a muted style.
13. **Add / edit item** — name, description, category (with suggestions from existing categories),
    LocationPicker, photo capture/pick. Edit hides location and points to Move (§4.5, §3.4).
14. **Photo capture & analyze** — CameraX capture or gallery pick → optional "What's in this photo?"
    → suggestion chips the user confirms individually into items. Degrade gracefully on
    `AI_NOT_IMPLEMENTED`.
15. **Settings** — account (email from the JWT `email` claim), language, theme, logout (wipes tokens
    **and** the local database), debug host field, about/version.

Cross-cutting states: every list has explicit **loading / empty / error / offline** states with a
retry affordance. Empty states teach the next action ("Create your first space").

---

## 7. Known gaps and required workarounds

Log each of these in `docs/BACKEND_REQUESTS.md` as you go.

### 7.1 `NEEDS_CONFIRMATION` confirmation channel — RESOLVED

**Resolved 2026-09-03.** `POST /assistant/remember` now accepts an optional `spaceId`; resend the
same message with the picked id. The workaround described below is no longer necessary — kept for
history. Two further server-side improvements landed with it: the provider is given the user's own
space names, so a mention in another language (`"evdə"`) matches an existing space (`Home`) without
any confirmation round-trip at all; and location names now come back in base dictionary form, so
two phrasings of the same shelf no longer create two locations.

### 7.1a Original report (historical)

### `NEEDS_CONFIRMATION` had no confirmation channel

`POST /assistant/remember` accepts **only** `{message}`. When the response is `NEEDS_CONFIRMATION`
with `candidateSpaces`, there is no `spaceId` field to send back.

**Workaround for v1:** after the user picks a space, re-send the original message prefixed with the
chosen space name (e.g. `"In Home: I put my passport in the bedroom wardrobe top drawer"`) and
handle a second `NEEDS_CONFIRMATION` by falling back to the manual add-item form with the picked
space preselected. Never loop more than once.

**Backend request:** add an optional `spaceId` to `RememberRequest` so confirmation is deterministic.

### 7.2 Other known gaps

- **No user profile endpoint.** `firstName`/`lastName` are accepted at registration but never
  returned. Show the `email` claim from the JWT; keep the display name locally.
- **No account deletion / logout endpoint.** Client-side logout = wipe tokens + wipe the local
  database. The refresh token remains valid server-side until it expires — flag this to the backend
  team as a security follow-up before public release.
- **No item-count aggregates.** Space and location item counts must be derived client-side or
  omitted. Do not N+1 the API to compute them; prefer omitting them in v1.
- **`primaryImageUrl` only appears in search results**, not in `GET /items` or `GET /items/{id}`.
  For the item list and detail, fetch `GET /items/{id}/files` and presign as needed.
- **Assistant image analysis returns 501 on both real providers (`openai`, `claude`).** Feature-flag
  the UI. Only `ai.provider=mock` answers it, with canned suggestions.

---

## 8. Delivery plan

| Milestone | Scope | Done when |
|---|---|---|
| **M0 — Foundation** | Project skeleton, version catalog, Hilt, theme, Retrofit + OkHttp + serialization, `ErrorMapper`, encrypted token store, **single-flight refresh with its concurrency test** | 20-concurrent-401 test passes; app boots to Login |
| **M1 — Auth & spaces** | Register/Login/session gate, spaces CRUD with guard messaging | A user can register and create "Home" |
| **M2 — Locations** | Tree browser, node CRUD, re-parent, reusable LocationPicker | The `Bedroom > Wardrobe > Top drawer` chain is creatable and browsable |
| **M3 — Items** | Paged list, detail, create/edit, search, move, history timeline | §2.2 steps 5–6 work manually |
| **M4 — Assistant** | Remember, Ask, result rendering, confirmation workaround (§7.1) | §2.2 steps 3–4 work; full journey E2E test green |
| **M5 — Photos** | CameraX capture, transcode, upload with WorkManager retry, gallery, primary flag, Coil keyer | Photos survive app restart and token refresh |
| **M6 — Polish** | Offline cache, localization (EN/AZ/RU), a11y pass, empty/error states, release build hardening | Quality bar in §5.4 fully met |

Report at each milestone: what shipped, what was cut, and any new entry in `BACKEND_REQUESTS.md`.

---

## 9. Definition of done

- The §2.2 journey passes as an automated instrumented test against a real backend.
- The concurrent-refresh test proves exactly one `/auth/refresh` per burst.
- Every one of the 22 `ErrorCode` values renders a localized, human message; none leaks a code,
  status number, or server string to the user.
- Ownership misses (404) never present as permission errors anywhere in the UI.
- No user id is transmitted in any request.
- Image cache keys are file ids; expired presigns recover with a single silent retry.
- HEIC never reaches the network; nothing over 10 MB is ever uploaded.
- `PUT` on an item can never change its location, by construction.
- Release build: HTTPS-only, no cleartext, no debug logging of tokens, URLs, or user messages.
- EN/AZ/RU complete; TalkBack usable on capture and search; 200% text scaling without clipping.

---

## 10. Note on the iOS app

Sections 2, 3, 4, 6, 7 and 9 are platform-independent and are the shared contract for the iOS
client (SwiftUI + async/await, `URLSession`, Keychain token storage, an actor-serialized refresh
that mirrors §4.1 exactly). Keep this document as the single source of truth for both clients:
when the backend contract changes, update §3 here first, then both apps.
