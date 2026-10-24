# Technical plan

Architecture, data model, core algorithms and milestones for StoryArc. Implements [product-spec.md](product-spec.md). Drafted 2026-10-24.

## Stack and toolchain

- Kotlin, Jetpack Compose (Material 3), Room, kotlinx.serialization, DataStore (Preferences), Navigation Compose.
- compileSdk/targetSdk 36, minSdk 26, JDK 21 (installed). Android SDK at `~/Android/Sdk`.
- Gradle Kotlin DSL with a version catalog (`gradle/libs.versions.toml`). Exact library versions are pinned at scaffold time.
- Dependency injection: manual (`AppContainer` held by the `Application`). The graph is small; Hilt can be adopted later if it grows.
- Previews: `./gradlew assembleDebug` → `app/build/outputs/apk/debug/app-debug.apk`; the user installs it. `./gradlew installDebug` works when a device is connected over adb.

## App icon

- Source: `IconKitchen-Output.zip` in the repo root, supplied by the user (white book glyph on a teal/black harlequin background).
- Use only `android/res/` from it: copy the `mipmap-*` folders into `app/src/main/res/`. It is a full adaptive icon (background, foreground and monochrome layers, plus legacy PNGs for each density). There is no separate round variant, so the manifest sets only `android:icon="@mipmap/ic_launcher"`.
- `android/play_store_512.png` is the store listing icon. The `ios/` and `web/` folders are unused.

## Module layout

| Module | Type | Contents |
|---|---|---|
| `:board` | Pure Kotlin/JVM | Board engine: immutable board state and all layout operations (insert, push, collapse, move, group/marker rules). No Android dependencies, so it is fast to unit test. |
| `:app` | Android application | Room database, repositories, change sets and undo/redo, backup, settings, Compose UI. |

Packages in `:app`: `data/db` (entities, DAOs), `data/repo`, `data/backup`, `domain/undo`, `ui/<screen>`, `ui/board` (canvas and drag), `ui/theme`.

Pattern: single activity, MVVM. ViewModels expose `StateFlow` UI state; repositories expose Room `Flow`s.

## Data model (Room)

All primary keys are UUID strings. This makes backup import and merge safe from ID collisions.

### Projects, worlds and views
- `Project(id, name, createdAt, updatedAt)`
- `World(id, name)`: a world library. Creating a project also creates a default world and links it.
- `ProjectWorld(projectId, worldId)`: many-to-many link (shared worlds).
- `View(id, projectId, name, ordinal)`
- `ViewSettings(viewId, hiddenTagIds…)`: per-view filter state. Stored as a join table `ViewHiddenTag(viewId, tagId)`.

### Timelines and nodes (shared content, per-view position)
- `Timeline(id, projectId, name, color?)`: shared content.
- `ViewTimeline(viewId, timelineId, ordinal)`: membership and order within a view (timelines are reorderable per view).
- `Node(id, projectId, text, color?)`: shared content. `color` is the override color.
- `Placement(viewId, nodeId, timelineId, slot, groupId?)`: the node's position in a view and its innermost group. Primary key `(viewId, nodeId)`. There is deliberately no unique index on `(viewId, timelineId, slot)`: slot renumbering inside a transaction would collide mid-update. The board engine enforces one node per cell.

### Positional constructs (per-view)
- `Marker(id, viewId, slot, label, color?)`. Markers are per-view. When a view is created by import, markers are copied.
- `NodeGroup(id, viewId, timelineId, parentId?, label, color?)`
- Membership is `Placement.groupId` (the innermost group); there is no separate member table. Ancestry comes from `parentId`.

### Tags
- `Tag(id, projectId?, worldId?, name, color?, linkedNoteId?)`. Exactly one owner is set:
  - project-owned tags are things like "Scene" and "POV: Mara";
  - world-owned tags are things like "Magic: Bloodbinding" and travel with the world.

  A project sees its own tags plus the tags of every world it links.
- Assignment tables, one per target type so foreign keys keep their integrity: `NodeTag`, `MarkerTag`, `GroupTag` and `NoteTag`, each `(targetId, tagId, ordinal)`. `ordinal = 0` is the primary tag.
- Displayed color = item `color` override ?: primary tag's `color` ?: default.

### Communal notes
- `Category(id, worldId, parentId?, name, ordinal)`
- `Note(id, worldId, categoryId?, title, body)`
- `NoteLink(fromNoteId, toNoteId)`

Room schema export is enabled from day one (`room.schemaLocation`), and migrations are tested.

Foreign keys are all `deferred`, so a change set can write rows in any order inside one transaction. `CASCADE` is used only for ownership (project, world, view, and rows owned by a node, timeline, marker, group or tag). References between peers (group parent, a placement's group, note category, tag → note link) have no action, so an incomplete change set fails at commit instead of silently rewriting rows. The change-set writer applies all upserts before any deletes, and uses `@Upsert`, never `REPLACE`, which would trigger cascades.

## Board engine (`:board`)

### Slots are implicit
There is no slot table. A view's slots are the dense indices `0..n-1` used by placements and markers. Invariants, checked after every operation:
1. Indices are dense: every slot in `0..n-1` holds at least one placement or exactly one marker.
2. A marker slot holds no placements.
3. At most one placement per `(timeline, slot)`.
4. Group contiguity: a group's members form a consecutive run within its timeline's node sequence. No non-member node sits between two members; empty slots inside a group are allowed.
5. A group's nodes all sit on the group's timeline, and nested groups lie within their parent's run.

Integer indices are renumbered in one transaction on insert/collapse. Projects are book-scale (hundreds to low thousands of nodes per view), so this is cheap and much simpler than fractional keys.

### State and operations
`BoardState` is an immutable in-memory model of one view: timelines in order, `timeline → sortedMap(slot → nodeId)`, markers by slot, and the group tree. The whole view is loaded into memory. Operations are pure functions `BoardState → BoardState`:

- **Insert column at `s`**: shift every placement and marker with `slot ≥ s` by +w.
- **Place on timeline T at `s` with push (width w)**: walk T's nodes from `s` in order, with `newSlot = max(oldSlot, prevNewSlot + 1)`, so empty slots absorb the push.
  - If the next position is a marker slot, insert columns immediately before the marker. Pushes never cross a marker.
  - Past the end, append columns.
- **Collapse**: remove every slot that has no placement and no marker, and renumber.
- **Move item** (node or group; a group is a block of width `lastSlot − firstSlot + 1` that keeps its internal offsets), in this order:
  1. remove the source;
  2. resolve the target in **pre-removal** coordinates;
  3. apply the drop (below);
  4. collapse.
- **Drop types** (resolved by the UI from pointer position):
  - `Between(s)`: insert w columns at `s` across all timelines, then place.
  - `Onto(T, s)` on an occupied slot: place with push on T only.
  - `Onto(T, s)` on an empty slot: place without push if the block fits; otherwise push.
  - A drop onto a marker slot resolves to `Between` on the nearer side.
- **Groups and drops**: dropping a node inside a group's run makes it a member of the innermost group that spans the drop point. Dropping at a group's edge keeps it outside the group.
- **Move marker**: a marker always takes its own slot, so a marker drop is always a `Between(s)` insert followed by a collapse.
- **Timeline reorder, add, remove**: changes the order only. Removing a timeline from a view removes its placements, then collapses.

The engine is tested with example-based unit tests for each rule, plus seeded random-sequence tests (`BoardFuzzTest`) that check invariants and relative-order preservation after every step.

## Persistence, change sets and undo/redo

- Every user edit goes through a `Command` that produces a `ChangeSet`: an ordered list of row-level `Insert(row)`, `Update(before, after)` and `Delete(row)` changes.
  - Board operations produce their change set by diffing the old and new `BoardState`.
  - Content edits (text, tags, notes) build theirs directly.
- `ChangeSet`s are applied inside one Room transaction. Undo applies the inverse; redo re-applies.
- `UndoManager`, one per open project:
  - an in-memory stack, bounded to 200 entries, cleared on process death;
  - text edits are coalesced per field per editing session.
  - Persisting undo history across restarts is deferred.
- **Delete from view** removes the `Placement` (and with it the group membership). **Delete everywhere** removes the `Node` (cascading to all placements, members and tags). Removing a node from its last view asks for confirmation and then deletes it everywhere. Both are undoable.

## UI

### Screens
1. **Projects**: list, create, rename, delete.
2. **Project home**: views list, timelines, world libraries linked to the project, and tag manager entry points.
3. **Board**: the main canvas for one view.
4. **Node editor**: a bottom sheet with plain text and tags.
5. **World library**: categories tree, notes list, note editor, note links.
6. **Tag manager**: create, edit, color, link to a note, choose an owner (project or world).
7. **Settings**: marker-crossing delay toggle, delay duration, and backup/restore.

### Board canvas
- Timelines are horizontal rows; slots are columns that scroll left to right. Markers render as full-height vertical bars in their own column.
- The timeline header column on the left is pinned, and so is the marker/slot header strip on top.
- 2D scrolling plus pinch zoom, which scales slot width and row height. Only the visible cells are composed, using a custom 2D `LazyLayout`. Portrait and landscape share the same layout logic.
- Tag filters never affect structure: an item carrying any hidden tag is drawn faded (about 20% opacity) and stays interactive. Collapse looks at all timelines, including hidden ones.
- The board top bar keeps only undo and redo visible. Add marker, select, filter and timelines live in a "More" menu, because six icons left no room for the view switcher on a phone.

### Drag and drop
- Gesture: `detectDragGesturesAfterLongPress`. The picked item lifts as an overlay that follows the pointer.
- Hit testing is pure geometry:
  - `slot = floor(x / slotWidth)`;
  - the outer ~20% of a slot on either side means `Between`, the center means `Onto`;
  - `row = floor(y / rowHeight)` → target timeline.
- Live preview: while dragging, the engine computes the result of the drop and the board animates to it (`animateItem`-style offsets), so pushes and inserts are visible before release.
- Auto-scroll when the pointer nears a viewport edge.
- **Marker barrier**: when the pointer crosses a marker column, the target clamps to the near side. After a 500 ms dwell, a haptic tick fires and the crossing unlocks. With the setting off, items cross immediately.

### Settings
DataStore Preferences: `barrierDelayEnabled` (default true) and `barrierDelayMs` (default 500).

## Backup and restore

- Format: a `.zip` containing `manifest.json` (format version, app version, export time, contents) plus `projects/<id>.json` and `worlds/<id>.json`. The Room entities are `@Serializable` and stored directly; `BackupManager.FORMAT_VERSION` guards the layout, so a schema change that alters entity fields must bump it and convert old files.
- Scopes:
  - per project, including the world libraries it links;
  - everything.
- Files are written and read through the Storage Access Framework (`ACTION_CREATE_DOCUMENT` / `ACTION_OPEN_DOCUMENT`), so no storage permissions are needed.
- Restore is validated before anything is written: format version and referential integrity are checked.
  - If an incoming ID already exists, the user chooses **replace** or **import as copy**.
  - Replace syncs rows: existing rows of those projects and worlds are diffed against the backup, rather than deleted and re-inserted, so other projects' assignments of a shared world's tags survive.
  - Copy re-maps every project-owned id and appends " (copy)" to the name; world libraries that already exist are kept and reused.
  - References the backup can't satisfy (tags or notes it doesn't contain and the device doesn't have) are dropped or cleared. The import runs in one transaction, and all undo history is cleared afterwards.
- Round-trip tests: export → import → deep-equal.

## Testing

- `:board`: JUnit 5, with example tests plus seeded fuzz tests (no property-testing library). This is the bulk of the logic tests.
- `:app`: Room DAOs, migrations, change-set/undo and backup round-trip, run on the JVM with Robolectric (no device needed). Compose UI tests for the drag hit-testing and key flows also run under Robolectric.
- Manual verification on the user's phone via debug APKs at each milestone.

## Milestones

Each milestone ends with a buildable debug APK and a commit.

| # | Milestone | Outcome |
|---|---|---|
| M0 | Scaffold | Gradle project, version catalog, `:app` + `:board`, placeholder Compose screen, app icon installed (see App icon), debug APK builds. |
| M1 | Board engine | All layout operations and invariants in `:board`, fully unit and property tested. |
| M2 | Persistence and undo | Room schema, repositories, change sets, UndoManager, DAO tests. |
| M3 | Projects and views | Project list/create, default world, project home, timelines CRUD and reorder. |
| M4 | Board rendering | Read-only canvas: rows, columns, markers, 2D scroll, zoom, both orientations. Create/edit node text. |
| M5 | Drag and drop | Long press, snap, Between/Onto, push, collapse, cross-timeline moves, marker create/move, barrier dwell + haptics + setting, live preview, auto-scroll, undo/redo controls. |
| M6 | Groups | Create from selection, nest, move as a unit, membership on drop. |
| M7 | Multiple views | Create a view with import (shared nodes and timelines, copied markers), switch views, delete from view vs. delete everywhere. |
| M8 | Tags and color | Tag manager, assignment, primary-tag color, overrides, per-view filters. |
| M9 | World libraries | Communal notes, categories, note links, tag → note navigation, link/unlink worlds across projects. |
| M10 | Backup and restore | Zip export/import, both scopes, conflict handling. |
| M11 | Polish | Accessibility (TalkBack alternatives to drag, e.g. "Move to…" actions), performance pass on large boards, empty states. |

Deferred (from the spec): rich node content, templates, search, Markdown/PDF export, tablet layouts, persistent undo history.

## Decisions made in this plan (not explicit in the spec)

- Slots are implicit dense indices, not stored rows.
- Group contiguity means a consecutive run in the timeline's node order; empty slots inside a group are allowed.
- A node dropped inside a group's run joins the innermost spanning group.
- Markers and groups are per-view and are copied (new ids, tags included) when a view is created with "Timelines and nodes". View creation offers Nothing / Timelines / Timelines and nodes.
- Deleting a view also deletes nodes and timelines no other view uses. A project always keeps at least one view.
- Every project gets a default world. Tags are owned by either a project or a world.
- Removing a node from its last view deletes it everywhere, after a confirmation.
- Undo history is in-memory per project, bounded to 200 entries.
- World library edits are recorded on the undo stack of the project they were made from. Unlinking a world from a project removes that project's uses of the world's tags and is undoable. Deleting a world deletes it everywhere, is not undoable, and clears every project's undo history.
- Notes link only to other notes in the same world library. A note's "Create a tag for this note" makes a world-owned tag that refers to it. A node's editor lists the notes its tags refer to.
- Manual DI instead of Hilt.
