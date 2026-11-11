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
- **Tray (schema v2):** `TrayItem(viewId, nodeId, ordinal, offset, groupId?)`, PK `(viewId, nodeId)`. Rows sharing an `ordinal` are one tray item; `offset` is the node's slot offset within it; `groupId` its innermost tray group. Tray groups are `NodeGroup` rows with `timelineId = null` (the root has `parentId = null`). `ViewRows.toTray()` / `withLayout(Layout(board, tray))` convert; tray logic lives in `data/repo/Tray.kt`. v1→v2 is a Room `AutoMigration` (new table, nullable column); `MigrationTest` builds a v1 database from `schemas/.../1.json` because Robolectric cannot read schemas as test assets.

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

`Block` is a run of nodes detached from a board (node → slot offset, the groups inside it with their parents, membership). `extract(item)` takes a node or group off the board as a `Block`; `insert(block, drop)` lands one (same rules as `move`, including joining an enclosing group). A block's internal gaps collapse when they end up empty on every timeline, as with any move.

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
- Two layouts, chosen in Settings (`Settings.verticalBoard`). Horizontal (default): timelines are rows, slots run left to right, markers are full-height bars, the timeline header is a column on the left. Vertical: timelines are columns, slots run top to bottom, markers are full-width bands, the header is a row along the top.
- The canvas works in board coordinates (along the time axis, across timelines); `BoardAxes` maps them to the screen. `BoardGeometry`, `resolveTarget`, hit testing and `BoardViewport` (`scrollAlong`/`scrollAcross`) are all in board coordinates, so the drag logic is shared and the scroll position survives a layout switch. Switching has no transition animation (by request).
- Slot length and timeline thickness swap between layouts (`BoardDims.slot/row`), so node cards are 132×88dp in both. Group bars sit on each timeline's trailing edge: the bottom of a row, or the right side of a column.
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
DataStore Preferences: `barrierDelayEnabled` (default true), `barrierDelayMs` (default 500) and `board_vertical` (default false).

## Backup and restore

- Format: a `.zip` containing `manifest.json` (format version, app version, export time, contents) plus `projects/<id>.json` and `worlds/<id>.json`. The Room entities are `@Serializable` and stored directly; `BackupManager.FORMAT_VERSION` guards the layout, so a schema change that alters entity fields must bump it and convert old files. Format 2 adds `ProjectBundle.tray` (defaulting to empty, so format 1 files read as-is) and allows null group timelines.
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

## Manuscript milestones (M12–M15)

Planned 2026-11-11 for the "Manuscript authoring" and "Spanning nodes" sections of [product-spec.md](product-spec.md). Status: approved and done 2026-11-11 (all four milestones). Each milestone ends with a buildable APK, passing `./gradlew test`, and one commit.

### M12 — Spanning nodes and schema v3
Done 2026-11-11. Rules as built (see the comment above `Drop` in `BoardOps.kt`): a drop onto any covered slot pushes the spanning node; a Between drop inside a span on its own timeline snaps to the nearer edge; columns inserted inside a span on another timeline widen it; a marker inserted inside a span cuts it short. The resize gesture reuses the drag session (`DragTarget.Span`), so it needs no long press and gets auto-scroll.
- **Goal:** nodes can stretch over several slots. The data for later milestones is in place.
- **Engine (`:board`):**
  - A placement gets `span ≥ 1`, and a node occupies `slot..slot+span-1`.
  - Invariants change: no two placements overlap on a timeline; a span never covers a marker slot; collapse treats covered slots as occupied.
  - Push and move treat a node as width `span`, reusing the group-block width logic.
  - New operation `resize(node, newSpan)`. Growing pushes later nodes on that timeline, using the same cascade as a drop. If a marker is reached, the span stops before it, so the barrier blocks it. Shrinking frees slots, which then collapse if empty everywhere.
- **Schema v3** (Room `AutoMigration` 2→3, new columns with defaults):
  - `Placement.span` and `TrayItem.span`, default 1;
  - `Timeline.isScenes`, default false;
  - new table `SceneText(nodeId PK → Node CASCADE, body, styles)`, kept separate so the board doesn't load prose.
  - "At most one scenes timeline per project" is enforced by the repository: setting one clears the previous one in the same change set.
- **Backup format 3:** adds the new fields and table. Format 1 and 2 files still restore, with defaults.
- **UI:**
  - A resize handle on the card's trailing edge. It works in both board layouts and shows a live preview while dragging.
  - Cards are drawn across the full span.
  - TalkBack actions "Widen" and "Narrow".
- **Verification:**
  - Engine unit tests for each new rule.
  - `BoardFuzzTest` extended with random resizes.
  - `MigrationTest` for 2→3, plus a backup round trip including format-2 import.
  - A Robolectric gesture test for the handle and a screenshot of spanned cards.

### M13 — Scenes timeline and scene editor
Done 2026-11-11. As built:
- `scene_text.words` caches the word count, so the board loads counts without prose (`BoardDao.sceneWordsInView`).
- The editor replaces the board inside `BoardScreen` (state `editingScene`), not as a separate route, so it shares `BoardViewModel` and `BoardUi`.
- Drafts are written with `saveSceneDraft`, which records no undo step. On close, `finishSceneEdit(before)` records one `Edit scene` change set. The text as it was when the session began is kept in `rememberSaveable`, so the step stays correct across rotation.
- Rows a node owns (tags, scene text) are deleted and copied through `ownedRowDeletes` / `ownedRowCopies`, so undoing a delete restores the prose.
- Typing undo uses `TextFieldState.undoState`.
- Tapping a node opens the scene editor if the node is on the scenes timeline or has prose; otherwise it opens the node sheet.
- **Header:** the timeline header menu gets "Use as scenes timeline" and "Stop using as scenes timeline". The scenes header shows a manuscript icon and the total words and pages.
- **Editor screen:** full screen, opened from a scene card, both in a view and from the tray. Contents:
  - the card name field (same behaviour as today's node text), tags, color, and the prose field;
  - autosave, debounced at about 500 ms and flushed on exit;
  - an editor-local undo/redo stack. On close, the session is written to `UndoManager` as one `ChangeSet` (coalesced per editing session, as text edits already are).
- **Stats bar:** words (Unicode-aware word split) and pages (`ceil(words / wordsPerPage)`). The new `Settings.wordsPerPage` defaults to 250.
- **Same-time panel:** lists nodes on other timelines in the current view whose `[slot, slot+span)` overlaps the scene's, plus the nearest marker at or before the scene's slot. Tapping an item opens a read-only preview.
- **World library panel:**
  - Shows notes from the project's linked worlds: tag-linked notes first, then search by title and body. Notes are editable in place, through the existing note repository calls.
  - On wide screens (≥ 840 dp, e.g. the user's tablet in landscape) it is a side pane; otherwise a bottom sheet.
- **Cards:** scene cards show a word-count badge. Cards with prose that aren't on the scenes timeline show a manuscript icon.
- **Prose text:** plain text in this milestone. `SceneText.styles` stays an empty JSON list until M15.
- **Verification:**
  - Repository tests: designation exclusivity, prose saving, and undo of a whole editing session.
  - Unit tests for word count and overlap.
  - A Compose flow test: open a scene, type, check the stats, close, undo.

### M14 — Assemble and ODT export
Done 2026-11-11. As built:
- Assembly lives in `StoryRepository.manuscript(viewId, author)`, not the view model. Robolectric tests run on the main thread, so `runBlocking` there deadlocks against `viewModelScope`.
- The title page uses master page `Title_20_Page` (no header). The first chapter heading switches to `Standard` and, with an author, restarts page numbering at 1.
- Blank lines in prose are dropped, because indents mark paragraphs. A line that is just `#`, `***`, `* * *`, `⁂` or `~` becomes a centred `#` scene break.
- Manual check: `OdtWriterTest.writesSamplesForManualCheck` writes `app/build/odt-samples/*.odt`. Convert them with `soffice --headless --convert-to pdf`.
- **Button and dialog:** an "Assemble" button on the scenes timeline header opens the export dialog. It has an author option (off by default) and an author name field, remembered in DataStore. Then `ACTION_CREATE_DOCUMENT` opens with the suggested file name `<project>_yyyy_mm_dd.odt`, whitespace → `_`, and characters invalid in file names removed.
- **Assembler:** a pure Kotlin function from (project, view, scenes with prose and styles, options) to a document model. It reads scenes in slot order and skips tray scenes. Each scene becomes a chapter with its card name as the heading (first line if the name has several lines); an empty body still gets its heading.
- **ODT writer:** written by hand on `java.util.zip`, with no new dependency.
  - Files: `mimetype` (stored uncompressed, first entry), `META-INF/manifest.xml`, `content.xml`, `styles.xml`, and `meta.xml` with the word count.
  - Layout: page size from `Settings.paperSize` (below), 1 in margins (0.75 in on trim sizes narrower than 8 in), Times New Roman 12 pt, double spacing, 0.5 in first-line indent, chapter headings on a new page about one third down, page numbers in the header.
  - With the author option on: a title page (author, title, approximate word count) and the header "Surname / TITLE / page".
- **Verification:**
  - Unit tests unzip the output and check entry order, that `mimetype` is stored uncompressed, that the XML is well formed, the chapter order, and the file-name sanitizing.
  - An ODT generated from a fixture is converted to PDF with the local LibreOffice (`soffice --headless --convert-to pdf`) as a manual check. The user then confirms the result on the tablet.

### M15 — Bold and italic
Done 2026-11-11. As built:
- `RichProse` wraps a `TextFieldState`.
  - Its `InputTransformation` reads the buffer's exact change list and moves style ranges with `StyledText.edit`. Replacing text keeps the style of the first replaced character; inserting continues the style of the character before. A B/I toggle at a collapsed cursor overrides this for the next text typed there.
  - Its `OutputTransformation` draws the styles with `addStyle`, and re-runs when `styles` changes.
- `TextFieldState`'s own undo is not used, because it would restore text without styles. `RichProse` keeps snapshots of text, styles and selection; typing within 1 s coalesces. Ctrl+Z/Y are intercepted in `onPreviewKeyEvent`.
- **Style model:** `styles` is a JSON list of `(start, end, bold|italic)` ranges over `body`. A pure Kotlin `StyledText` keeps ranges consistent through inserts and deletes: a range grows when text is typed at its end while that style is active, and ranges merge and split as text changes. It is fully unit-tested.
- **Editor:**
  - The view uses `BasicTextField` on `TextFieldState`; styles are applied through an `OutputTransformation` or annotated rendering. This choice is made at implementation time after a spike, and the result recorded in agent-notes.
  - A toolbar with B and I toggles that apply to the selection, or set the style for the next text typed.
  - Ctrl+B and Ctrl+I for hardware keyboards.
- **Export:** styled ranges become `text:span` elements with bold and italic automatic styles.
- **Verification:** `StyledText` unit and fuzz tests (random edits keep ranges valid); editor tests for toggling and typing; an ODT test checking the spans.
- **Sizing:** comparable to M14. It is the riskiest milestone because Compose has no built-in rich-text editing, and it comes last so it can be dropped or deferred without affecting the others.

- **Paper size setting:** `Settings.paperSize`. "Automatic" (the default) uses Letter in the US and Canada and A4 elsewhere. The other options are:
  - manuscript sizes: US Letter, A4;
  - common publishing trim sizes: 5×8 in, 5.25×8 in, 5.5×8.5 in (Digest), 6×9 in (US Trade), 6.14×9.21 in (Royal), 7×10 in, A5, B-format (129×198 mm), Demy (138×216 mm), Royal UK (156×234 mm).

  The word count per page is a separate setting, so page counts don't depend on the trim size.

### Open questions
- None.

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
