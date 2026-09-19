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

#### 3.1a Account — `/users/me`

| Method | Path | Auth | Body | Success |
|---|---|---|---|---|
| DELETE | `/users/me` | bearer | `{password}` | **204** (empty) |
| GET | `/users/me/plan` | bearer | — | 200 `PlanStatus` (§3.8a) |
| POST | `/users/me/plan/purchases` | bearer | `{purchaseToken, productId}` | 200 `PlanStatus` (§3.8c) |
| GET | `/plans` | bearer | — | 200 `[LadderRow]` (§3.8b) — note: NOT under `/users/me` |

- Re-authentication is mandatory: the body carries the user's **current password**. A wrong, blank or
  missing password — and a token whose account no longer exists — all return **401
  `INVALID_CREDENTIALS`** with the standard error shape and **nothing is deleted**. Map it to "wrong
  password"; never tell the user which of those it was.
- The deletion is a **hard delete in one transaction, no grace period**: account, spaces, location
  tree, items, history, photo metadata and every refresh token (all devices are signed out
  server-side). Photo binaries are removed from storage asynchronously shortly after.
- The user id comes from the JWT subject; there is no id in the path or the body (§4.2).
- Retrofit requires `@HTTP(method = "DELETE", path = "users/me", hasBody = true)` — a plain `@DELETE`
  cannot carry a body.
- The still-valid access token is **not** revoked (stateless JWT). After a 204 the client wipes its
  token store and local database and returns to Login — exactly the sign-out path — and must NOT call
  `/auth/refresh` (it would 401 `TOKEN_INVALID`).
- A **409 `CONFLICT`** is possible only if another device of the same user wrote data during the
  delete; nothing was deleted, so "try again" is the right copy.
- Give this one call a generous timeout and a blocking progress state: the cascade is a few seconds
  for a large account and the production cold start can sit in front of it.

The public pages Play links to are served by the same host, unauthenticated:
`/legal/delete-account` and `/legal/privacy` (bilingual AZ/EN). Settings may deep-link to them.

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
A free account may hold **one** space; a second POST is **409 `PLAN_LIMIT_REACHED`** — see §3.8.
(Re-sending the name of the space you already own is still `DUPLICATE_NAME`, not the plan limit.)

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
| POST | `/items` | `{name, description?, category?, locationId}` | 201 `Item`, **409 `PLAN_LIMIT_REACHED`** (§3.8) |
| GET | `/items` | `?locationId=uuid?&page=0&size=20&sort=updatedAt,desc&includeArchived=false` | 200 **Spring `Page<Item>`** |
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
  "primaryFileId":"uuid|null",                          // stable — cache key for the cover photo
  "primaryImageUrl":"https://…presigned…|null",         // rotates every ~10 min
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
- **`locationId` on `GET /items` (BR-9) is optional and filters by EQUALITY on
  `currentLocationId` — no subtree.** Send it only for a **leaf** location (no children); for a
  branch it returns just what sits directly in it, not what is inside its children. Everything else
  (sort, clamp, `includeArchived`, the page envelope) behaves identically with the filter on.
  **A `locationId` that is not the caller's, or does not exist, is a `404 LOCATION_NOT_FOUND`, not an
  empty page** — handle it like a dead BR-7 pin (clear the selection and reload the tree); never
  render it as "this location is empty". A non-UUID value is a `400`; an empty value
  (`?locationId=`) is simply no filter. There is still no per-location item count.
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
| POST | `/assistant/remember` | `{message}` (≤ 1000), optional `spaceId` **or** `locationId` | 200 `RememberResponse`, **409 `PLAN_LIMIT_REACHED`** (§3.8) |
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
  A **new top-level location** was created iff `createdLocations.length == item.locationPath.length - 1`
  — nothing can already exist under a node that did not exist, and `locationPath` opens with the
  space name. That is the case worth a loud confirmation rather than a grey footnote: it strands the
  item in a tree the user will not open. (`createdLocations[0] == item.locationPath[1]` looks
  equivalent and is not — it misfires on a path that repeats a name, e.g. `Şkaf > Şkaf`.)
- `NEEDS_CONFIRMATION` — the message was understood but the target space was ambiguous.
  **Answer it by resending the same `message` with `spaceId` set to the chosen `candidateSpaces[].id`.**
  The id settles the space outright; the assistant does not ask again. A space that is not yours
  is a 404 like every other ownership miss. The `message` distinguishes three cases: no spaces yet,
  a named space that does not exist yet ("You don't have a space for \"Office\"..."), and a
  genuinely ambiguous one — render it verbatim above the picker.
  **Zero writes happened.** Show `candidateSpaces` as a picker. §7.1 is CLOSED — send `spaceId`;
  do NOT fold the space name into the sentence and re-send it through the model.
- **Pinned destination — `locationId` (BR-7).** Send it when the user picked the exact place
  before speaking (the "filed here recently" chips). **With it, the backend calls no model at all
  and the text IS the item name, stored exactly as typed** — no parsing, no title-casing, no
  description. Once the place is chosen there is nothing to interpret, and interpreting anyway is
  what made a pinned `kabel 20A` answer NOT_UNDERSTOOD on the first cut. Rules:
  - **The composer must ask for an item name while a pin is set**, not for a placement. A pin pill
    above a "Tell me where you put it…" placeholder is the bug, not a cosmetic issue — it is what
    produced the sentence that failed.
  - The text must be non-blank, ≤ 120 chars, and match `^[\p{L}\p{N}][\p{L}\p{N} .,'&()\-]*$`.
    A failure is **`NOT_UNDERSTOOD`** with a message saying so — render it like any other; the user
    did nothing malformed. That charset is also what keeps a question out (`?` is not allowed in a
    name), in every language and with no model involved.
  - A full sentence sent with a pin becomes an item named after the whole sentence. Accepted and
    tested; Undo is the remedy.
  - `createdLocations` is always empty and `NEEDS_CONFIRMATION` cannot happen.
  - **Mutually exclusive with `spaceId`** — sending both is a 400 `VALIDATION_ERROR`.
  - A location that is not yours is a 404, like every other ownership miss.
  - Keep the pin across consecutive saves until the user clears it. That is the point: filing ten
    things into one box should resolve the box once, not ten times.
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
`SPACE_NOT_EMPTY`, `PLAN_LIMIT_REACHED`, `FILE_TOO_LARGE`, `UNSUPPORTED_MEDIA_TYPE`,
`STORAGE_ERROR`, `AI_UNAVAILABLE`, `AI_NOT_IMPLEMENTED`, `CONFLICT`, `INTERNAL_ERROR`,
plus the billing codes of §3.8c: `PLAY_UNAVAILABLE`, `PLAY_PURCHASE_INVALID`,
`PLAY_PURCHASE_NOT_ACTIVE`, `PLAY_PRODUCT_UNKNOWN`, `PLAY_PRODUCT_MISMATCH`,
`PLAY_BILLING_NOT_CONFIGURED`, `PLAN_PURCHASE_NOT_OWNED`.

Map every one of these to a specific, human, localized string. Never surface a raw `code` or the
server `message` verbatim to the user; never show a stack trace or an HTTP number.

**Ownership misses return 404, not 403 — deliberately.** A resource that belongs to someone else is
indistinguishable from one that does not exist. Your UI must respect that: on 404 say
*"This item is no longer available"* and pop back to the list. Never say "you don't have permission".

### 3.8 The tier ladder (409 `PLAN_LIMIT_REACHED`, and how to get past it)

**SUPERSEDES the two-valued free-tier contract.** There are now five tiers, four of which a client
may render:

| tier | spaces | active items | Play product |
|---|---|---|---|
| `FREE` | 1 | 100 | — |
| `STANDARD` | 3 | 300 | `whereis_standard_annual` |
| `PRO` | 5 | 600 | `whereis_pro_annual` |
| `MAX` | 10 | **no limit** | `whereis_max_annual` |
| `UNLIMITED` | no limit | no limit | **never purchasable — an operator grant** |

All three paid products are **annual, auto-renewing, one base plan each with base plan id
`annual`**, each with a free trial configured as an offer in Play Console. **Never hardcode the
trial length**: read it from the pricing phases (§3.8c).

Beyond a tier's ceiling, the three creation calls answer **409 `PLAN_LIMIT_REACHED`** with a
`message` that names the tier and the limit. Nothing existing is ever taken away.

#### 3.8a `GET /users/me/plan` — what am I on, and how much have I used

Ask the server; never hardcode a limit and never keep a running counter of your own.

```jsonc
// PlanStatus — GET /api/v1/users/me/plan, 200. Same body as POST /users/me/plan/purchases.
{ "plan": "PRO",                                  // the EFFECTIVE tier; unknown values are NOT FREE
  "limits": { "spaces": 5, "items": 600 },        // always an OBJECT; a null MEMBER = no ceiling there
  "usage":  { "spaces": 2, "activeItems": 143 },
  "source": "SUBSCRIPTION",                       // NONE | GRANT | SUBSCRIPTION — for copy only
  "subscription": {                               // null when there is no LIVE Play purchase
    "productId": "whereis_pro_annual",
    "tier": "PRO",
    "state": "ACTIVE",                            // the nine states in §3.8d
    "entitledUntil": "2027-09-19T10:04:00Z",
    "provenance": "PLAY_PURCHASE",                // PLAY_PURCHASE | PROMO_CODE | OPERATOR
    "acknowledged": true,
    "entitling": true,                            // NEW — does THIS row entitle right now?
    "pendingProductId": null,                     // NEW — a DEFERRED change at entitledUntil
    "pendingTier": null } }                       // NEW — its tier, or null rather than wrong

// AN ON_HOLD SUBSCRIBER. plan is FREE and subscription.tier is PRO AT THE SAME TIME. Not a
// contradiction — see §3.8d.
{ "plan": "FREE", "limits": {"spaces": 1, "items": 100}, "usage": {"spaces": 3, "activeItems": 412},
  "source": "NONE",
  "subscription": {"productId": "whereis_pro_annual", "tier": "PRO", "state": "ON_HOLD",
                   "entitledUntil": "2027-09-19T10:04:00Z", "provenance": "PLAY_PURCHASE",
                   "acknowledged": true, "entitling": false,
                   "pendingProductId": null, "pendingTier": null} }

// A DEFERRED DOWNGRADE. Still PRO, and the screen can finally say what happens next.
{ "plan": "PRO", "limits": {"spaces": 5, "items": 600}, "usage": {...}, "source": "SUBSCRIPTION",
  "subscription": {"productId": "whereis_pro_annual", "tier": "PRO", "state": "ACTIVE",
                   "entitledUntil": "2027-03-14T00:00:00Z", "provenance": "PLAY_PURCHASE",
                   "acknowledged": true, "entitling": true,
                   "pendingProductId": "whereis_standard_annual", "pendingTier": "STANDARD"} }

// Max: a finite space ceiling beside no item ceiling at all
{ "plan": "MAX", "limits": {"spaces": 10, "items": null}, "usage": {...},
  "source": "SUBSCRIPTION", "subscription": {...} }

// an operator grant
{ "plan": "UNLIMITED", "limits": {"spaces": null, "items": null}, "usage": {...},
  "source": "GRANT", "subscription": null }

// free
{ "plan": "FREE", "limits": {"spaces": 1, "items": 100}, "usage": {...},
  "source": "NONE", "subscription": null }
```

- **`limits` IS ALWAYS AN OBJECT AND THE NULLABILITY MOVED TO ITS TWO MEMBERS.** BR-11 said
  "`limits` is `null` for `UNLIMITED`" and told the upgrade screen to branch on that; **that rule is
  dead.** Max is "ten spaces, unlimited items", which a whole-object null cannot express at all. The
  rule that survives is simpler: **a null is exactly one thing — no ceiling on THAT allowance.**
  Render `{"spaces": 10, "items": null}` as "2 of 10 spaces" beside "143 items, no limit".
  *The already-shipped client needs no change to read this:* `PlanLimitsDto` declares both members
  nullable and `PlanAllowance(used, limit: Int?)` already means "unlimited" per allowance.
- **Do not use `limits` to decide whether to offer an upgrade.** It is wrong in both directions now:
  `MAX` has a non-null object and must never be offered anything. The rule is §3.8b's: offer a tier
  only when it is strictly above the caller's on the ladder returned by `GET /plans`.
- **An unrecognised `plan` is NOT `FREE`.** §4.4's "unknown becomes `OTHER`" does not transfer here.
  Map anything unrecognised, blank or missing to your own `UNKNOWN` state, render it as "a plan this
  version does not know about", and **offer nothing**. Defaulting to `FREE` would offer more room to
  an account that already paid for it.
- **`source` is for copy, never for behaviour.** Show "Manage subscription" **iff `subscription !=
  null`** — a paid subscription must stay manageable even for an account that also holds a higher
  operator grant, which is exactly the `source: "GRANT"` + non-null `subscription` case.
- **`subscription` IS NOW NON-NULL FOR A LIVE PURCHASE THAT DOES NOT ENTITLE. This supersedes
  BR-12's "non-null exactly when an entitling row exists".** It additionally covers `ON_HOLD`,
  `PAUSED` and `PENDING` — the three states where the subscription is not entitling and the user
  most needs to act on it. Reporting `null` for an `ON_HOLD` subscriber, as the previous contract
  did, took away the only control that could fix their failed payment from an account that was
  still being charged. It is `null` for a refunded, superseded, `EXPIRED` or
  `PENDING_PURCHASE_CANCELED` purchase: those have nothing left to manage, which is what stops a
  lapsed account carrying a permanent, actionless Manage button.
- **`entitling` is rendered, never computed with.** It is the difference between "you keep Pro until
  14 March" and "Pro is paused" — and **it must never be used to derive a tier**. The client does
  not compute tiers at all.
- **`subscription.acknowledged == false` means the purchase is not yet confirmed to Google.** Google
  auto-refunds and revokes an unacknowledged purchase (3 days; **5 minutes** for a test purchase on a
  closed track). A server-side reconciler now retries it every 15 minutes, ahead of everything else,
  so a re-POST is no longer the only repair — but it is still the fastest one, so while this reads
  `false` the client should **ignore its own 24-hour re-post rule and re-POST the token on the next
  foreground.**
- **`usage` is always present, on every tier, and is NEVER CLAMPED.** `{"limits": {"spaces": 3},
  "usage": {"spaces": 5}}` is a valid, expected body for an account that dropped from `PRO` to
  `STANDARD`. Draw it as full, not as overflowing (the shipped `PlanAllowance.fraction` already
  coerces to `0f..1f`).
- `plan` is the caller's **effective entitlement** — `max(operator grant, best entitling
  subscription)` — computed by the same code that refuses a creation, so `usage.spaces ==
  limits.spaces` is true exactly when the next `POST /spaces` will be a 409.
- No path or query parameter: the account is the JWT subject. **401** anonymously.
- It is a **read**: it never grants, never upgrades and never changes anything.

#### 3.8b `GET /plans` — the ladder, and the ONLY source of tier ordering

```jsonc
// GET /api/v1/plans, 200 — authenticated, no parameters, in LADDER ORDER
[ {"tier": "FREE",     "productId": null,                      "limits": {"spaces": 1,  "items": 100}},
  {"tier": "STANDARD", "productId": "whereis_standard_annual", "limits": {"spaces": 3,  "items": 300}},
  {"tier": "PRO",      "productId": "whereis_pro_annual",      "limits": {"spaces": 5,  "items": 600}},
  {"tier": "MAX",      "productId": "whereis_max_annual",      "limits": {"spaces": 10, "items": null}} ]
```

- **Derive a tier's rank from its INDEX in this array.** Do not hardcode the ordering: the server
  owns it, and a client that re-encodes it will eventually offer a downgrade as an upgrade.
- **A tier is an upgrade iff its index is greater than the caller's.** A caller whose tier is **not
  in this list at all** — `UNLIMITED`, or a tier this build has never heard of — is offered
  **nothing**, and no row is badged "Your plan". Render the ladder under a header that frames it as
  what the paid plans offer (or behind a disclosure) rather than misplacing the badge; that is the
  default view for every operator-granted tester, which is the whole audience of this wave.
- `productId != null` is what makes a row purchasable. **Product ids are never hardcoded in the
  app**: they come from here, so re-pointing one is a server environment change with no release.
- `UNLIMITED` is deliberately absent — it cannot be bought, and listing it would invite a client to
  render it as an option.
- No prices. A server-side price is wrong for most countries and is grounds for store rejection.

#### 3.8c `POST /users/me/plan/purchases` — link a Play purchase to this account

```jsonc
// request
{ "purchaseToken": "opaque-token-from-Play", "productId": "whereis_pro_annual" }
// 200: the FULL PlanStatus body of §3.8a. ADOPT IT — do not issue a second GET.
```

`productId` is sent even though the server could look it up: acknowledgement needs it, and the
server cross-checks the claim against Google's own line items. **The answer is never taken from the
request** — tier, state, expiry, acknowledgement, test flag and promo marker all come from Google.

| situation | status | `code` | what the client does |
|---|---|---|---|
| verified and active | 200 | — | adopt the body; mark the token synced |
| replay within 60 s, still entitling | 200 | — | adopt the body |
| pending / paused / on hold / expired / unknown state | 409 | `PLAY_PURCHASE_NOT_ACTIVE` | **keep the token**, short bounded backoff (30 s / 60 s / 120 s for the first ten minutes, then hourly) — a PENDING payment becomes ACTIVE later, and the 5-minute test-purchase fuse makes an hour too slow |
| token already linked to another account | 409 | `PLAN_PURCHASE_NOT_OWNED` | show "this purchase belongs to another account"; keep the token but retry only on the normal 24-hour schedule, never in a loop |
| `productId` is not one this server offers | 400 | `PLAY_PRODUCT_UNKNOWN` | a client bug — you sent a product that is not in `GET /plans` |
| none of the purchase's products is ours | 400 | `PLAY_PRODUCT_MISMATCH` | drop the token |
| Google does not know the token | 400 | `PLAY_PURCHASE_INVALID` | drop the token |
| Google unreachable / 5xx / timeout | 502 | `PLAY_UNAVAILABLE` | retry with backoff; **do not** start the 24-hour clock |
| **billing is not configured on this server** | **501** | **`PLAY_BILLING_NOT_CONFIGURED`** | **keep the token, stop asking this session.** NOT an error to show as a failure and NOT a reason to drop the token — see below |
| body missing or malformed | 400 | `VALIDATION_ERROR` | client bug |
| anonymous / expired token | 401 | standard | §4.1 |

**Seven `ApiErrorCode` values** (`PLAY_UNAVAILABLE`, `PLAY_PURCHASE_INVALID`,
`PLAY_PURCHASE_NOT_ACTIVE`, `PLAY_PRODUCT_UNKNOWN`, `PLAY_PRODUCT_MISMATCH`,
`PLAY_BILLING_NOT_CONFIGURED`, `PLAN_PURCHASE_NOT_OWNED`) must be in the client enum. Without them
every branch above collapses into `UNKNOWN` and the retry policy is unimplementable.

**501 `PLAY_BILLING_NOT_CONFIGURED` — the state the server is deployed in right now
(2026-09-20).** The backend runs with `whereis.play.provider=disabled` until the Google Cloud
service account and the Pub/Sub topic exist, so **every** purchase POST answers 501 today,
whatever the token. The client contract:

* **Keep the purchase token.** It is not invalid — nobody has looked at it. It will be accepted
  unchanged once billing is switched on, and `queryPurchasesAsync` will keep reporting it on every
  foreground, which is the natural re-post point.
* **Do not retry within the session**, and do not start the 24-hour clock either. No amount of
  retrying creates a Google Cloud project. The next cold start is soon enough.
* **Do not show a payment failure.** Nothing was charged and nothing failed on the user's side.
  Treat it exactly like the `501 AI_NOT_IMPLEMENTED` precedent: a feature this deployment does not
  offer. "Subscriptions aren't available yet" is the honest string.
* **It can never be a grant.** 501 carries an `ApiError`, never a `PlanStatus`, so there is no
  branch in which this response raises a tier. The plan screen keeps rendering from
  `GET /users/me/plan`, which is unaffected.

Everything non-billing is unaffected by this mode — `GET /users/me/plan`, `GET /plans`, the
per-tier limits, `usage`, and 409 `PLAN_LIMIT_REACHED` all behave exactly as documented. The
upgrade screen should still render; whether it hides the purchase button behind a 501 it has
already seen is a client decision, and a one-line feature flag either way.

**`obfuscatedAccountId` — the bytes are the contract.** Set
`BillingFlowParams.setObfuscatedAccountId(sha256Hex(userId))`; the server recomputes the same value
from the JWT subject and refuses a purchase that names another account.

- **Where `userId` comes from:** the JWT `sub` claim of the access token. The client already decodes
  that payload for the e-mail claim; extend that reader to expose `sub` and store it with the
  session. There is no `/users/me` profile endpoint and none is needed.
- **The exact input:** SHA-256 over the **UTF-8 bytes of the UUID's canonical `toString()` form** —
  36 characters, lowercase, hyphenated — rendered as **lowercase hex, exactly 64 characters**. Not
  the raw `sub` string as received, not dashes stripped, not uppercase.
- **Golden vectors, asserted by `PlayAccountHashTest` on the backend and to be copied verbatim into
  an Android test:**
  - `00000000-0000-0000-0000-000000000000` → `12b9377cbe7e5c94e8a70d9d23929523d14afa954793130f8a3959c7b849aca8`
  - `3f2504e0-4f89-41d3-9a0c-0305e82c3301` → `16362f566387b3cf5a6e92fb0a986c76ca20eb3a0c12cbdfbd0b29501e0c18df`
- The server compares case-insensitively on the hex, so an upper-cased digest still matches.
- **Fail closed:** if the user id is not available, do not launch the billing flow at all.

**Where price and trial actually live.** `ProductDetails.formattedPrice` does **not** exist for
subscriptions. Read
`getSubscriptionOfferDetails() → getPricingPhases().getPricingPhaseList()`:

- renewal price = `formattedPrice` of the phase whose `recurrenceMode == INFINITE_RECURRING`;
- trial = the phase with `priceAmountMicros == 0L`; its length is `getBillingPeriod()`, an ISO-8601
  period. **Carry the period, never `Period.parse(...).getDays()`** — `P1M` and `P1Y` both have a
  `days` field of 0, so a trial changed in Play Console from 14 days to 1 month would render
  "First 0 days free" next to a live purchase button. Format `P14D` / `P2W` / `P1M` with unit-aware
  plurals, and render no trial line at all when there is no free phase.
- `offerToken` is **mandatory** on `launchBillingFlow`, and `getSubscriptionOfferDetails()` **order
  is not guaranteed** — selecting `[0]` is a bug that works on the test device. Select by
  `basePlanId` (`"annual"`), preferring an offer with a free phase, else the bare base plan
  (`offerId == null`).
- A promo-code trial is **not** visible here and must never be inferred from a price: during one,
  Google reports the FULL price server-side. `signupPromotion` is the only marker and it is the
  backend's job — it surfaces as `subscription.provenance == "PROMO_CODE"`.

**Play Billing Library 9.1.0 shapes, which differ from every pre-8.0 sample:**
`enablePendingPurchases(PendingPurchasesParams.newBuilder()...build())` is **required** on the
builder (the no-arg overload was removed), and `queryProductDetailsAsync` delivers a
`QueryProductDetailsResult` carrying **both** `getProductDetailsList()` and
`getUnfetchedProductList()`. Log every unfetched product id with its status at WARN and surface it
in that ladder row's copy — a mistyped `product-id` is otherwise a silent blank card on a build that
can only be tested by hand on a signed release. Distinguish "billing unavailable / connection
failed" (transient, offer a retry) from "this product was not returned" (permanent for this build,
show limits only).

**`queryPurchasesAsync` on every foreground is mandatory, not an optimisation.** Promo codes are
redeemed in the Play Store or in-app and produce a purchase with the app closed, and a flow
interrupted by process death completes on Google's side with nothing to tell the app.

**Never gate the paid tier locally.** Entitlement is the server's answer, from `GET
/users/me/plan`; a purchase is not access until the server says so.

**Server-side facts the client can rely on**

* Only **ACTIVE** items count. Archiving (`PUT /items/{id}` with `archived: true`) frees room
  immediately; the item is not deleted and still shows under `GET /items?includeArchived=true`.
* **Locations are not limited** at any depth or number.
* **Nothing existing is ever taken away.** An account over its ceiling after any downgrade — an
  expiry, a cancellation, a refund, a pause, a revoked grant, a retuned limit — keeps every space,
  location, item, photo and history record and may still read, rename, move, archive, **unarchive**
  and delete them. Only `POST /spaces`, `POST /items` and `POST /assistant/remember` can answer 409.
* Ownership still wins: a foreign or unknown `locationId` is `404 LOCATION_NOT_FOUND` even at the
  limit. Handle the 404 first.
* A refused `/assistant/remember` creates **nothing** — no item and none of the locations the
  sentence implied. Do not optimistically insert a row and then reconcile.
* `CANCELED` entitles: auto-renew is off but the paid term is not over. `PAUSED` and `ON_HOLD` do
  not, even with a future `entitledUntil`.

#### 3.8d The subscription strip, and changing tiers (BR-13)

##### The one rule the whole screen hangs on

**The badge describes the ENTITLEMENT. The strip describes the SUBSCRIPTION. Neither is derived from
the other.**

`plan.tier` is the server's effective entitlement — `max(users.plan, best entitling subscription)` —
and **it is the only thing that may badge a row or phrase what the account can do**.
`plan.subscription` is a separate object describing a Play purchase that may or may not currently
entitle. An `ON_HOLD` subscriber must see the tier badge read **Free** *and* a strip saying their
subscription is on hold, **at the same time, on the same screen**. Showing the paid tier because a
subscription object exists is the exact lie this section exists to prevent, and it is the easy
mistake to make.

`subscription.entitling` is rendered only as the difference between *"you keep Pro until…"* and
*"Pro is paused"*. It is **never** used to compute a tier.

##### Typed state, replacing the raw String

`PlanSubscription.state` is a `String?` in the shipped build. Make it a typed `SubscriptionState`
with the app's established `fromWire` + `UNKNOWN` shape (`PlanTier` and `EntitlementSource` already
do this) — a raw string reaching a `when` is how a state Google adds later ends up rendering as
nothing at all.

```kotlin
enum class SubscriptionState { ACTIVE, CANCELED, IN_GRACE_PERIOD, ON_HOLD, PAUSED,
                               EXPIRED, PENDING, PENDING_PURCHASE_CANCELED, UNKNOWN }
```

##### The strip — one line, at most one action

Render it whenever `plan.subscription != null`, between the current-plan section and usage.
**Every `<tier>` token below is `subscription.tier`, never `plan.tier`** — applying the governing
rule literally to `ON_HOLD` (where `plan.tier` is `FREE` by design) would produce "Free is paused
until it goes through", which is nonsense. The badge and every allowance number stay on `plan.tier`.

| state | line | action |
|---|---|---|
| `ACTIVE` | "Renews on \<entitledUntil\>." | Manage |
| `CANCELED` **and `entitling`** | "Cancelled. You keep \<tier\> until \<entitledUntil\>." | Manage (resubscribe lives there) |
| `IN_GRACE_PERIOD` **and `entitling`** | "There's a problem with your payment method. Fix it by \<entitledUntil\> to keep \<tier\>." | **Fix payment** — the same Play deep link, differently labelled, because "Manage" does not tell somebody their card is failing |
| `ON_HOLD` | "Your subscription is on hold — Google couldn't take the payment. \<tier\> is paused until it goes through." | **Fix payment** |
| `PAUSED` | "Paused in Google Play. Resume it there when you want \<tier\> back." | Manage |
| `PENDING` | "Waiting for your payment to be confirmed." | none |
| `PENDING_PURCHASE_CANCELED` | "Your pending payment was cancelled, so the subscription never started." | none |
| `EXPIRED` | strip hidden | — |
| `UNKNOWN` | "Managed in Google Play." | Manage |
| `CANCELED` or `IN_GRACE_PERIOD` with **`entitling == false`** | "Your subscription ended on \<entitledUntil\>." | Manage |

**That last row is not a formality.** Entitlement is fail-closed on `entitledUntil > now`, but
`state` only moves to `EXPIRED` when Google tells us — so a `CANCELED` row whose term has just run
out (an RTDN lost, or the reconciler's 15-minute window not yet elapsed) would otherwise render
"Cancelled. You keep Pro until 14 March 2026" with a date in the past, directly beneath a badge
reading **Free**. That is the collapse this whole section exists to prevent, in the most confusing
direction: the strip promising access the wall has already refused. **Drive both deadline lines off
`entitling`, not off `state` alone.**

Grace and hold read as **fixable problems with a deadline**, not as error states. They are the two
moments where good copy actually recovers revenue, and both currently render as nothing at all.

##### The pending change, above the strip

Whenever `subscription.pendingProductId != null`:

> **Pro until 14 March 2027, then Standard.** · *Change or cancel in Google Play*

If `pendingTier` is null (a product this build cannot name) degrade to *"Your plan changes on 14
March 2027. See Google Play for details."* — **never** to an invented tier.

##### Upgrades and downgrades

The server never learns the replacement mode and must not need to: it refreshes from Google and
resolves `linkedPurchaseToken`. So the client owns the choice.

- **Upgrade → `ReplacementMode.CHARGE_PRORATED_PRICE`.** Access is immediate, the user pays only the
  difference for the remainder of the period, and the renewal date does not move. All plans are
  annual, so the same-billing-period precondition always holds.
- **Downgrade → `ReplacementMode.DEFERRED`.** The user keeps what they paid for until the term ends,
  then the lower plan starts. Nothing is taken away and no refund is owed.

`ReplacementModeSelector` is a **pure function** in the `OfferSelector` mould, taking the two ranks
from `GET /plans` (never a hardcoded order):

```kotlin
fun modeFor(currentRank: Int?, targetRank: Int?): Int? = when {
    currentRank == null || targetRank == null -> null   // not a change: plain purchase
    targetRank > currentRank -> ReplacementMode.CHARGE_PRORATED_PRICE
    targetRank < currentRank -> ReplacementMode.DEFERRED
    else -> null
}
```

- **The old token comes from Play, never from the server.** `PlanSubscription` carries no purchase
  token and must never carry one. Use `BillingRepository.activePurchases()`: exactly one PURCHASED
  subscription → use its token; zero → a plain purchase with no update params; **more than one →
  refuse to launch and log WARN**, because guessing which subscription is being replaced is how the
  wrong one gets cancelled.
- `setObfuscatedAccountId` stays **mandatory** on a change as well as on a first purchase. It is the
  only thing that lets the server attribute the new token without waiting for the app to reopen —
  and the server **fails closed** on a missing hash there, unlike at the verify endpoint, precisely
  because our own client always sets it.
- **A confirmation dialog before any change**, because this costs money and the two outcomes differ:
  - upgrade: *"You'll get \<target\> right away. Google charges the difference for the rest of your
    current period; your renewal date doesn't change."*
  - downgrade: *"You keep \<current\> until \<entitledUntil\>. From then on you'll be on
    \<target\> at its price. Nothing you've saved is removed — you just can't add more than
    \<target\> allows."*

##### When a downgrade row is offered at all

`LadderRowState` gains **`DOWNGRADE_OFFERED`**. A row strictly *below* the caller's tier is offered
iff **all** of: `plan.subscription != null`, `plan.source == SUBSCRIPTION`, the row is purchasable,
an offer and a live billing connection exist, and `subscription.pendingProductId == null`. Otherwise
`NOT_AN_UPGRADE`, exactly as today.

The `source == SUBSCRIPTION` clause matters: a *granted* `PRO` account is on the ladder, and offering
it a "downgrade to Standard" would be offering a brand-new purchase dressed up as a reduction.

While a deferred change is pending, **no row offers a downgrade** — a second one can only confuse,
and Play is where a pending change is altered. Upgrades stay offered: upgrading out of a pending
downgrade is legitimate.

**Cancelling entirely is not a ladder row.** There is no `FREE` product, so one line under the
ladder says: *"To stop paying, cancel in Google Play — you keep your plan until the period you've
paid for ends."*

##### One more thing `rowStateOf` must handle

After the `CURRENT` branch: when `plan.subscription != null && !plan.subscription.entitling &&
row.tier == plan.subscription.tier`, return a **non-buyable** state (reuse `NOT_AN_UPGRADE`, or add
`SUBSCRIPTION_INACTIVE`). Without it, an `ON_HOLD` PRO subscriber reads `plan.tier == FREE`, so the
PRO row evaluates to `UPGRADE_OFFERED` and renders a live Subscribe button — Play answers
`ITEM_ALREADY_OWNED`, and the dead end sits directly beside a strip saying "Fix payment". Two
controls contradicting each other on the same screen.

##### Tests worth naming

- `PlanUiStateTest`: `rowStateOf` across grant-vs-subscription × pending-change × billing
  availability × above/below/equal, plus `ON_HOLD` and `PAUSED` rows asserting **no buy button on
  the subscription's own tier**.
- The strip's state → (line, action) mapping as a parameterised test over **all nine** states, so a
  state added later fails the test instead of rendering blank — including the `entitling == false`
  variants of `CANCELED` and `IN_GRACE_PERIOD`.
- `SubscriptionStateTest` (client): `fromWire` never throws; unknown → `UNKNOWN`.
- `ReplacementModeSelectorTest`: every rank pair including nulls and equality.
- `PlanViewModelTest`: zero / one / two active purchases at change time.
- **The one regression test worth writing explicitly:** an `ON_HOLD` subscription with
  `plan.tier == FREE` renders the **Free badge and the hold strip together**, and the hold line names
  **Pro** while the badge names **Free**. That is the assertion that would have caught the mistake
  this section exists to prevent.

##### After Play settles

`PurchaseSyncer` posts the new token unchanged. Its per-`(account, token)` records already handle the
old token simply disappearing from `queryPurchasesAsync`, and `PLAY_PRODUCT_MISMATCH` already stays
in the retry bucket — which is exactly right for a deferred downgrade, where Google legitimately
keeps reporting the old product.

Every new string goes into all three locales (`values/`, `values-az/`, `values-ru/`
`strings_plan.xml`), and dates use the existing date formatter, never `toString()` on the ISO
instant.

**What the UI must do**

1. Show the limit, not the error. Render your own localized copy; §3.7's rule still applies (never
   surface a raw `code`). The server `message` names the tier and the number for the case where the
   refusal arrives before the plan does.
2. **Always offer archiving for the item limit**, on every tier — it is the action that works
   without a purchase. For the space limit there is no such action.
3. **Offer an upgrade only when one exists.** Use §3.8b's index rule. `MAX` and `UNLIMITED` are
   offered nothing; so is `UNKNOWN`.
4. **Model "charged by Play, not yet confirmed by our server" explicitly.** A 502 or a dropped
   connection after a successful purchase leaves the user paid and the plan reading `FREE` — the
   worst state this screen can reach. Render "your purchase is being confirmed", rebuild that state
   on every launch from `queryPurchasesAsync` so it survives process death, and stamp the
   "already sent" record only on a **terminal** outcome (200, or a 400/409 you are told to stop on),
   never on a transport failure.
5. **Scope the sent-token record to the account.** A shared device signs one user out and another
   in; a `PLAN_PURCHASE_NOT_OWNED` recorded against a bare token would put the FIRST user's own
   purchase on the ignore list when they sign back in. Key it by `(account, token)` or clear it on
   sign-out.
6. **Gate the whole billing subsystem on `BILLING_ENABLED` and an authenticated session.** The debug
   build has `applicationIdSuffix = ".debug"`, so every billing call fails there; without the gate,
   every developer and CI foreground starts a doomed connect-retry loop, and a logged-out cold start
   fires an authenticated POST that wakes the token refresher for nothing.

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
    **and** the local database), **Delete account** (§3.1a: a destructive-styled entry that explains
    what is deleted and that it is irreversible, asks for the password, requires one explicit
    confirmation, calls `DELETE /users/me`, and on 204 runs the same wipe as logout and returns to
    Login; 401 → "wrong password", stay signed in), links to the privacy notice and the deletion page,
    debug host field, about/version.

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
- **~~No account deletion~~ — fixed (BR-5, 2026-09-14):** `DELETE /users/me` with the password
  (§3.1a) deletes the account and revokes every refresh token. **No logout endpoint** remains open
  (BR-1): client-side logout = wipe tokens + wipe the local database, and the refresh token stays
  valid server-side until it expires.
- **No item-count aggregates.** Space and location item counts must be derived client-side or
  omitted. Do not N+1 the API to compute them; prefer omitting them in v1. The one aggregate that
  does exist is account-wide: `usage.activeItems` / `usage.spaces` from `GET /users/me/plan`
  (BR-11). It is a quota reading, not a per-space or per-location count.
- **~~`primaryImageUrl` only appears in search results~~ — fixed (BR-3, 2026-09-13).** `ItemResponse`
  now carries `primaryFileId` + `primaryImageUrl`, resolved in one batch query per page, so the
  list and detail screens need no `GET /items/{id}/files` call to show a cover photo. Key image
  caches on `primaryFileId`; the URL expires in ~10 minutes.
- **Assistant image analysis returns 501 on both real providers (`openai`, `claude`).** Feature-flag
  the UI. Only `ai.provider=mock` answers it, with canned suggestions.
- **Purchases return 501 `PLAY_BILLING_NOT_CONFIGURED` on the deployed server (2026-09-20).** Play
  Billing is switched off until the Google Cloud service account and the Pub/Sub topic exist.
  Keep the token, do not retry in-session, do not show a payment failure — §3.8c. Nothing else
  about the plan surface changes, and switching it on later is an environment change with no new
  API shape.

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
