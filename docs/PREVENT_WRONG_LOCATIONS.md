# Preventing wrong and duplicate locations

Written after the 2026-09-16 session, where a batch of items landed in places the user never
named. The repair is done (items are back under `Xalqlar > Ashagi kladovka > …`, `bad_open_history
= 0`). This document is about the *cause*, and about which changes actually prevent a repeat.

Everything in §1 was read out of the code, not inferred. Every proposal in §3 names the file it
touches and what it costs.

---

## 1. What actually went wrong

The prompt fixes from 13 Sep (compound names, relational place words, same-language rule) were
**already deployed** when the 16 Sep failures happened. The remaining defects are in the resolver
— the deterministic Java that runs *after* the model — and no prompt wording can fix them, because
the prompt cannot see the user's location tree.

### 1.1 The space is taken from the model on trust

`AssistantService.resolveSpace` — `AssistantService.java:281`

```java
private Optional<Space> resolveSpace(UUID userId, List<Space> spaces, String spaceName) {
    if (spaceName != null) {
        return spaceRepository.findByUserIdAndNormalizedName(userId, Names.normalize(spaceName));
    }
    return spaces.size() == 1 ? Optional.of(spaces.getFirst()) : Optional.empty();
}
```

If the model returns a `spaceName` that happens to match one of the user's spaces, the item goes
there. **The user's own sentence is never consulted at this point.** The model said `Work`, the
user has a space called `Work`, so the code filed it in `Work`. There is no evidence check and no
confirmation — the `NEEDS_CONFIRMATION` path is only reached when the name matches *nothing*.

The same request also produced a chain whose first segment was the name of a *different* space
(`space = Work`, `locations = [Xalqlar, …]`). The prompt already forbids opening the chain with
the space it just reported (`ClaudePlacement.locations`), but it says nothing about opening it
with some *other* space of the user's — and it cannot, because the model is only given names, not
the rule's consequences. That combination is detectable in Java in three lines.

### 1.2 Location creation is exact-match-or-create, silently, at every depth

`LocationService.resolveOrCreateChain` — `LocationService.java:152`, `findSibling` at `:190`

```java
Optional<Location> existing = findSibling(spaceId, parentId, normalizedName);
if (existing.isPresent()) { current = existing.get(); } else { … save(…) … }
```

`findSibling` is an exact lookup on `normalized_name` among **siblings of one parent only**. Two
consequences, both observed:

* `"kladovka"` did not match the existing root `"Ashagi kladovka"`, so a second root tree was
  created next to it. The item is not lost — it is in a tree the user will never open.
* `"Chanta"` under a *different* parent did not see the existing `Ashagi kladovka > Chanta`, so a
  second `Chanta` node appeared. The user's short sentence ("çantada") names a place that exists
  exactly once in the space, but the resolver only ever looks one level down.

Nothing about this is a bug in the sense of a wrong line; it is a deliberately strict rule that
turns out to be wrong for how people actually talk.

### 1.3 The dedup key folds diacritics but not transliterations

`Names.normalize` → `foldToAsciiLetters` — `Names.java:32`, `:46`

Verified behaviour:

| written | key | written | key | same? |
|---|---|---|---|---|
| `Şkaf` | `skaf` | `şkaf` | `skaf` | yes |
| `Siyirmə` | `siyirme` | `Siyirme` | `siyirme` | yes |
| `Çanta` | `canta` | `Chanta` | `chanta` | **no** |
| `Şkaf` | `skaf` | `Shkaf` | `shkaf` | **no** |
| `Aşağı` | `asagi` | `Ashagi` | `ashagi` | **no** |

So the fold already handles "typed with or without the dots". It does **not** handle "typed as a
digraph" — which is exactly how this user types when the AZ keyboard is off, and it is why the
production tree literally contains `Ashagi kladovka` and `Chanta` rather than `Aşağı` and `Çanta`.
This is a latent duplicate source: the day the same place gets typed `Çanta`, it becomes a second
node.

### 1.5 An explicit space pick is *still* routed back through the model

`RememberRequest` has accepted an optional `spaceId` since 2026-09-03, documented as settling the
space outright so that "the AI is not consulted about it" (`RememberRequest.java`). The contract
doc records the gap as closed: `docs/ANDROID_APP_PROMPT.md` §7.1 — **"RESOLVED … The workaround
described below is no longer necessary."**

The Android client never got the message. `RememberRequestDto` still carries only `message`
(`core/network/dto/AssistantDtos.kt:7`), and the confirmation path is still the historical
workaround:

```kotlin
// AssistantRepository.kt:26
// `RememberRequest` carries only `message` — there is no `spaceId` to send back once the user
// has picked a space — so the choice is folded into the sentence itself and re-sent.
suspend fun rememberInSpace(message: String, spaceName: String): ApiResult<RememberOutcome>
```

So when the user is asked "which space?" and answers it explicitly, the answer is **rewritten into
the sentence and sent through the model a second time**. A deterministic, already-shipped channel
is bypassed in favour of another inference. The client is stale against a gap that was closed two
weeks before the 16 Sep session.

Fixing this is one field on a DTO and one repository method. It is the cheapest item in this
document.

### 1.4 What we already have, and should use

* `assistant_messages` (V8) stores the **original sentence**, the raw model interpretation,
  `offered_spaces`, `prompt_version`, and — for `CREATED` rows — the `space_id` the item actually
  landed in. Every claim in §3 can therefore be *measured* instead of argued about.
* `RememberResponse.needsConfirmation(hint, candidateSpaces)` already exists and performs **zero
  domain writes**. Any new confirmation should reuse it rather than invent a flow.
* `ix_locations_name_trgm` — a GIN trigram index on `locations.normalized_name` — already exists
  (`V7__search_indexes.sql`). Similarity matching needs no new index.
* `RememberResponse.created(item, createdLocations, message)` already tells the client which
  segments were newly created, and the Android card already renders them — as a grey
  `bodySmall` footnote (`AssistantCards.kt:168`). A user filing ten items in a row will not read it.

---

## 2. The one rule to take away

> Prevention belongs in the resolver, not in the prompt. The prompt can only be asked to report
> what the sentence says. Deciding whether that maps onto a place the user already has is a
> database question, and the database is the only party that can answer it.

Two prompt edits during this work regressed a passing test; a third would be a third coin flip.
Every proposal below except P7 is deterministic and unit-testable without a live model.

---

## 3. Proposals, ordered by prevention per unit of risk

### P0 — Measure first (0.5 day, no product change)

> **Blocked on a deploy.** `V8__assistant_messages.sql` is in `ec97bf6`, which is local-only; the
> VM is still on `ef616dd`. Until that is pushed and redeployed the table does not exist in
> production and there is nothing to measure — which also means the 16 Sep sentences themselves
> were never recorded. The baseline starts at the redeploy, not before it.

One report over `assistant_messages`, run before and after every phase below. The field that
drove the decision is the **top-level** `interpretation->>'spaceName'` (the validated value
`resolveSpace` was handed); `interpretation->'raw'->>'spaceName'` is the pre-validation answer and
is worth keeping in the output for contrast.

```sql
-- (a) the model named a space, and it is not where the item ended up
select m.created_at::date              as day,
       m.message,
       m.interpretation->>'spaceName'  as model_said,
       m.interpretation->'raw'->>'spaceName' as model_said_raw,
       s.name                          as landed_in,
       m.interpretation->'offeredSpaces'     as offered,
       m.confidence
from assistant_messages m
join spaces s on s.id = m.space_id
where m.mode = 'REMEMBER' and m.outcome = 'CREATED'
  and m.interpretation->>'spaceName' is not null
  and az_normalize(m.interpretation->>'spaceName') <> s.normalized_name   -- or compare in Java
order by day desc, m.created_at desc;

-- (b) outcome mix per day: is the confirmation path actually firing?
select created_at::date as day, mode, outcome, count(*)
from assistant_messages
group by 1, 2, 3
order by 1 desc, 2, 3;
```

Query (a) needs a normalized comparison; there is no `az_normalize` in the database, so either add
a small SQL function mirroring `Names.normalize`, or run the comparison in a throwaway script that
pulls both columns and folds them in Java/Python. The second is cheaper and carries no schema risk.

"New root created" is **not** recoverable from `assistant_messages` — the table stores the
interpretation, not which segments were created. It is recoverable from the tree itself
(`locations` with `parent_location_id is null` and a recent `created_at`), which is enough. Without this, the next round of "the results were not
great" is again a memory exercise. Nothing else on this list should ship before the baseline
exists.

### P1 — Make a new *root* loud in the app (client only, no backend change, no API change)

Derivable entirely on the client: nothing can already exist *under* a node that did not exist, so
a new top-level location was created **iff `createdLocations.size() == item.path.size() - 1`** —
`locationPath` opens with the **space** name and continues with the locations
(`LocationTreeDao.PATHS_SQL` joins `spaces`), so the path is one longer than the chain. (The
tempting `createdLocations.first() == item.path.first()` is wrong twice over: the first path element
is the space, and it also misfires on a path that repeats a name, e.g. `Şkaf > Şkaf`.) When that holds, stop rendering a grey footnote
and render a warning-tinted block: *"Bu məkanda yeni əsas yer yarandı: «Kladovka». Doğrudur?"*
with **Bəli** (dismiss) and **Düzəlt** (open the item's move flow). The `Undo` affordance already
sits in the same card (`UndoState.AVAILABLE`).

* Touches: `AssistantCards.kt`, `AssistantUiState.kt`, strings. Zero backend risk.
* Why it is second on the list: it does not prevent the write, but it moves discovery from
  "three days later, 13 items deep" to "ten seconds later, one tap". That is the difference
  between a repair script and a tap.

### P2 — Evidence-based space resolution (backend, no API change, no prompt change)

Three rules in `AssistantService`, applied in this order, all deterministic:

1. **Space-name-in-chain.** If the first chain segment's normalized name equals one of the user's
   space names, the model swapped the slots: resolve the space to *that*, and drop the segment.
   Kills `space=Work, locations=[Xalqlar, …]`.
2. **Message-first match.** Tokenize `Names.normalize(sanitized)` on non-letter/non-digit
   boundaries — `Names.normalize` folds letters but does not strip punctuation, so splitting on
   whitespace alone leaves `xalqlarda,` and the prefix test still works while the equality test
   does not. A space matches if some token *starts with* that space's normalized name
   (agglutinative suffixes: `xalqlarda` starts with `xalqlar`). Require ≥4 characters for prefix matching and whole-token equality below that, so a
   2-letter space like `Ev` cannot match `evvel`/`evez`. If **exactly one** space matches, use it —
   overriding the model, because the user's own words beat the model's guess.
3. **Otherwise fall through to today's behaviour** only when the user has exactly one space;
   with two or more, go to the existing `NEEDS_CONFIRMATION` (zero writes).

Rule 3 is the behaviour change with teeth: a model-named space that the sentence does not support
stops being obeyed silently. It costs a tap in the one case where cross-lingual mapping is real
("işdə" → a space named `Work`) — which P7 removes later, once the safer phases have landed.

* Touches: `AssistantService.java` only.
* Tests: a parameterized table of (message, spaces, model spaceName, model segments) → expected
  space, seeded with the actual 16 Sep sentences from `assistant_messages`. No live model needed.

### P3 — Unique-descendant lookup for shortened chains (backend, no API change, no new UI)

When a segment has no matching child under the current parent, look for an exact normalized match
among **all descendants** of that parent (or all locations in the space, at root depth). Exactly
one hit → use it. Two or more → fall through to P4/P5.

This is the `Chanta` fix, and it is the highest prevention-per-friction item on the list: an exact
name match that is unique in scope is not a guess, so it needs no confirmation UI at all. It also
matches how people actually speak — nobody recites the full chain for a place they use daily.

One caveat worth stating: at **root depth** the search scope is the whole space, so a match moves
the chain's anchor deep into the tree ("Stolda" anchors at `Pochtalyon otagi > Stol`). That is the
intended behaviour and it is what makes short sentences work, but it is a bigger jump than the
same rule at depth — which is why the match must stay *exact and unique*. Loosening it here would
reintroduce §5's last bullet.

* Touches: `LocationTreeDao` (one query reusing the existing `WITH RECURSIVE walk`),
  `LocationService.resolveOrCreateChain`.
* Tests: unit + one IT for the ambiguous (2+ hits) case.

### P4 — Candidate matching: containment first, trigram second (backend, read-only)

When a segment matches nothing exactly, collect candidates under the resolved parent:

1. **whole-word containment, either direction** — `kladovka` ⊂ `ashagi kladovka`. Deterministic,
   no threshold to tune, and it is precisely the 16 Sep case.
2. **`similarity(normalized_name, :proposed) >= 0.4`** via the existing trigram index, ranked
   after containment.

Additionally, apply digraph folding (`ch↔ç`, `sh↔ş`, `gh↔ğ`) **here only**, in the matcher — see
P6 for why not in the key.

Candidates are never auto-merged. They exist to make P5's question worth asking.

### P5 — Confirm a new root, but only when a candidate exists (backend + API + client)

Before `executor.place(…)`, a read-only `LocationService.previewChain(userId, spaceId, segments)`.
If the root segment matches nothing exactly **and P4 found at least one candidate**, return a
confirmation carrying the proposed new name plus the ranked candidates; the client resends with a
chosen `locationId`, or with an explicit "create it anyway".

The conditional is the whole point. An unconditional "confirm every new root" is friction on
legitimate setup — the first item in a brand-new space would always ask. Gating it on a candidate
turns it from a speed bump into a useful question: *"«Kladovka» — «Ashagi kladovka» demək
istəyirsən?"*

`previewChain` runs outside the executor's transaction and does not take the space advisory lock,
so its answer is advisory. That is fine: if another request creates the root in between, `place`
finds it and reuses it, and the only cost of the race is one unnecessary question.

* Touches: `LocationService`, `AssistantService`, `RememberResponse` (+ `candidateLocations`),
  `AssistantController`, the Android confirmation sheet (which already exists for spaces), ITs.
* This is the most expensive phase and the only one that changes the API. It should not be
  started before P0's baseline shows how often it would actually fire.

### P6 — Do **not** change the persisted dedup key (recommendation, not work)

The tempting fix for §1.3 is to add digraph folding to `Names.normalize`. Recommendation: **no.**

`normalized_name` is persisted and carries
`ux_locations_sibling UNIQUE NULLS NOT DISTINCT (space_id, parent_location_id, normalized_name)`
plus `ux_spaces_user_name`. Changing the function means a Flyway migration recomputing every row,
which can **collide**: two existing siblings may fold to the same key, and resolving that means
re-parenting items — a data migration with real failure modes, for a problem P4 solves in the
matcher with no schema risk at all.

Fold in the matcher, where a wrong fold costs a suggestion. Never in the key, where a wrong fold
costs a merge that cannot be undone.

### P7 — Span-verified cross-lingual mapping (prompt + schema change; last, separately)

Add a `spaceEvidence` field to `ClaudePlacement`: the exact substring of the user's message the
space was inferred from. Accept a model-named space only when that substring actually occurs in
the sanitized message (diacritic-folded comparison). `"işdə"` → `Work` is accepted because the
span is really there; a hallucinated `Work` with no span is rejected.

This restores the tap that P2's rule 3 costs. It is last because it is the only item that touches
the prompt and schema, `PLACEMENT_PROMPT_VERSION` will change (it hashes the schema
descriptions, so the bump is automatic), and it needs a `@Tag("live-ai")` test rather than a unit
test. Two prompt edits in this project have already caused regressions; this one should ship alone,
with the space-reporting regression test watched.

---

### H — Hints from the existing structure (the better version of P5)

The proposal: when the user is saving an item, show them what the tree already contains, instead of
letting the model guess and then asking about the guess afterwards. This attacks the problem at the
input rather than at the output, and it fits the observed scenario exactly — 16 Sep was **bulk
filing**: many items into the *same* physical place. Today each sentence re-resolves that place
independently, so thirteen sentences are thirteen independent chances to get one location wrong.
A hint that lets the place be chosen once collapses thirteen coin flips into one deliberate choice.

Three levels, cheapest first.

#### H1 — A pinned target (highest value, lowest risk) — **BACKEND SHIPPED 2026-09-17**

> Backend done: `locationId` on `RememberRequest`, `PlacementExecutor.placeAt`,
> `messagePlaceIgnored` on the response, 8 unit + 5 integration tests, suites 189/44 green. No
> migration, no prompt change. Client work (chips, sticky pin, override note) in progress.

`GET /spaces/{spaceId}/location-tree` already exists and the Android client already consumes it
(`LocationApi.kt:38`, `LocationTreeNodeDto`). Flatten it to full paths and offer them as chips
above the composer: `Xalqlar > Ashagi kladovka > Karobka`. Tapping one pins it as a pill over the
input; the user then only says the item name. **The pin stays across consecutive saves until it is
cleared**, which is what turns bulk filing into "pin once, dictate ten names".

Backend: add `UUID locationId` to `RememberRequest` with exactly the semantics `spaceId` already
has — it settles the target outright and the model is not consulted about the place. When present,
`resolveSpace` and `resolveOrCreateChain` are both skipped and `ItemService.createAt` is called
directly; ownership is checked by `locationService.requireOwned` (404 on a miss, as everywhere).

> With a pin, **no location write is possible at all**. Not "less likely" — structurally impossible.
> Every other proposal in this document reduces a probability. This one removes the operation.

Two details worth building in:

* **Stale pins are the only new failure mode.** Mitigate by rendering the pin large rather than as
  a footnote, and by keeping the full path in the success card (it is already there). Do **not**
  auto-clear the pin when the sentence seems to name somewhere else — that would restore exactly
  the model trust §1.1 is about. If the model's interpretation disagrees with the pin, honour the
  pin and *say so* in the response: the server knows both values, and reporting the conflict costs
  nothing and acts on nothing. Both halves of the interpretation are checkable with one membership
  test, because `locationPath` opens with the space name: the innermost chain segment **and** the
  space — and the space is the half that went wrong on 16 Sep.
* **Free evaluation data.** The `assistant_messages` row still stores the model's interpretation
  even when a pin decided the outcome. Every pinned save is therefore a labelled example of "what
  the model would have said vs. where it actually belongs" — the dataset needed to judge H3 and P7
  without guessing. This alone is a reason to ship H1 before them.

Ranking the chips: `LocationTreeNode` carries no item count and there is no count query, so do not
add one yet. Rank by **recently used**, derived from the client's own assistant history (each entry
already holds `item.path`). For filing, "where I just filed" beats "where I have the most things"
anyway.

#### H1a — Use the confirmation channel that already exists (do this first, it is a bug fix)

Before any of the above: send `spaceId` from the picker instead of re-running the model (§1.5).
One DTO field, one repository method, and delete `rememberInSpace`'s sentence-rewriting.

#### H2 — Inline "did you mean" while typing (client only)

The same flattened path list, token-matched against what the user is typing: typing `kladovka`
surfaces a chip for `Ashagi kladovka`; tapping it pins it. This is the case H1 misses — the user who
does not think to pin first. More UI work than H1, no backend work at all, and it reuses P4's
containment rule (`kladovka` ⊂ `ashagi kladovka`) on the client side.

#### H3 — Show the model the tree, not just the space names (last, with a real caveat)

`AiAssistant.interpretPlacement(message, knownSpaceNames)` could also receive existing location
paths, so the model returns `Ashagi kladovka` instead of inventing `Kladovka`.

The caveat is earned, not theoretical: the space-names block in this prompt has **already** proved
attention-saturating once during this work — a rule stopped being followed as the block grew, and
the fix was to move it out of the prompt and into the schema description. A 27-node tree is an
order of magnitude more text than two space names. Worse, the failure mode changes for the worse:
a model that snaps to a *wrong existing* path produces an item that is invisibly misfiled, whereas
today's wrong *new* node is at least visible (and P1 makes it loud).

If it is done: send **only the paths lexically relevant to this message** (token-overlap prefilter,
server-side), not the whole catalogue — retrieval, not a dump — and keep the block small. It needs
a `@Tag("live-ai")` test and should ship alone, like P7.

#### What this replaces

**P5 is superseded — drop it.** It was the most expensive phase and the only one that added
friction to a correct save; H1 and H2 get the same prevention by making the right target easy to
choose instead of interrogating the user after the fact.

P2 and P3 stay, and are still worth doing: they cover the user who types a free sentence and never
looks at a hint. P1 stays as the safety net. P4's matching rule is now used by H2 on the client
instead of by P5 on the server.

## 4. Decisions only you can make

1. **Should a legitimately new root ever be created silently?**
   Recommendation: yes — when P4 finds no similar candidate. Otherwise ask.
2. **Containment only, or containment + trigram?**
   Recommendation: containment only to start. It has no threshold to tune and covers the observed
   failure; add trigram when P0 shows containment missing real cases.
3. **Digraph folding in the persisted key?**
   Recommendation: no (see P6). If you want it anyway, it is a separate piece of work with a
   collision pre-check migration, not a one-line change to `Names`.
4. **Should a pinned target be sticky across saves, or reset after each one?**
   Recommendation: sticky, cleared only by an explicit tap. Sticky is the whole point for bulk
   filing; the cost is a stale pin, which the success card already makes visible on every save.
5. **How much confirmation friction is acceptable when filing ten items in a row?**
   With H1 in place this mostly stops mattering, because the common case never reaches a
   confirmation. It still sets whether P2's rule 3 ships before or after P7.

## 5. What not to do

* **Do not fix this in the prompt.** The invariant is "does this place already exist for this
  user", and the model cannot see the answer.
* **Do not auto-merge on similarity.** A wrong merge moves someone else's items.
* **Do not let the AI supply ids.** Already the rule; it stays the rule.
* **Do not widen `findSibling` to match loosely.** Loose matching in a *write* path silently files
  items into the wrong existing node — strictly worse than today's failure, which at least leaves
  the item findable under a new name.

---

## 6. Suggested order

```
H1a send spaceId (bug fix)  →  H1 pinned target [backend done]  →  P2 space resolution
   →  P3 unique descendant  →  H2 inline hints   →  P1 loud new root
   →  P0 measure (from the redeploy onward)      →  H3 / P7 (each alone, live-AI tested)

P5 dropped — superseded by H1/H2.  P6 is a "do not".  P4 lives inside H2 now.
```

H1a is a bug fix and should go out on its own. H1 is the only item that makes a wrong location
structurally impossible rather than merely less likely, which is why it now leads. P2 and P3 need
no API change and no prompt change, and together with H1 they cover every failure observed on
16 Sep.
