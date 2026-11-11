# Product spec

Confirmed product requirements for StoryArc, an Android app for planning creative projects (e.g. books) on parallel, user-defined timelines. Agreed with the user on 2026-10-24.

## Core model

### Projects
- Multiple projects per install.
- Each project contains one or more **views**.
- A project can link to one or more **world libraries** (see Communal notes).

### Views
- A view is an independent arrangement of timelines, nodes, markers and slots.
- Users create any number of views (e.g. chronological order vs. reading order) and switch between them.
- When creating a view, the user may import existing timelines and nodes; otherwise the view starts empty.
- Imported nodes and timelines are **shared instances**: content and properties (text, name, color, tags) are shared across views; position and order are per-view.

### Slots
- Each view has one global ordered sequence of slots (columns) shared by all its timelines.
- Slots represent order only, not literal time. Nodes in the same slot are implicitly simultaneous; there are no explicit node-to-node links.
- Slots are dynamic: a slot that is empty on every timeline collapses automatically (except marker slots, below).

### Timelines
- User-defined parallel lines (e.g. one per character, story phases, locations).
- Reorderable within a view.
- Literal time has no dedicated feature: a user can dedicate an ordinary timeline to recording times/dates.

### Nodes
- Modular notes. Plain text for now (rich content deferred).
- A node belongs to exactly one timeline and one slot at a time (per view).

### Groups
- A group is a contiguous run of nodes on a single timeline (no cross-timeline spanning, no gaps).
- Groups are nestable and move as a unit.

### Organizing lines (markers)
- A labeled marker occupying its own dedicated slot across all timelines; nodes cannot share that slot.
- Point markers, not ranges. Purely referential: they do not label or tag the surrounding nodes.
- Their type/dimension comes from tags (e.g. "Scene", "Day").
- Marker slots never auto-collapse; removal is an explicit delete.
- Markers can be long-pressed and dragged to another position like nodes.
- Markers act as **barriers** (see Interaction).

## Interaction

- **Pick up:** long press on a node, group or marker.
- **Move:** drag along the current timeline or onto another timeline; drops snap to slots.
- **Drop between slots:** inserts a new slot across all timelines.
- **Drop onto a node:** pushes that timeline's nodes forward only, cascading into empty slots.
- **Push reaching a marker:** inserts a new slot immediately before the marker (across all timelines) rather than pushing past it.
- **Crossing a marker:** a dragged item stops at the barrier and crosses only after a ~0.5 s dwell, confirmed with a haptic tick. A setting disables the delay, letting items cross freely.
- **Add button:** opens the editor for a new, unplaced node straight away. Its card floats above the editor; after "Done" it floats over the board until dragged onto the board, into the tray or onto the trash (no long press needed for it). A new node left blank (no text, tags or color) is discarded without an undo entry. Tapping an empty cell still creates a node in that cell.
- **Tray:** each view has a tray of nodes and groups that are on no timeline there. It runs along the bottom in portrait and the right edge in landscape, and collapses. Drag items between the board and the tray; groups keep their shape and nesting. A node is in a view either on the board or in its tray, never both. The tray is not copied when a view is created by import.
- **Copy:** nodes and groups can be copied (text, color, tags; for groups also nested groups, labels and spacing). Copies are independent nodes and go to the end of the tray.
- **Trash:** while anything is being dragged, a trash target appears at the tray's far end. Dropping on it deletes: a node everywhere, a group with all its nodes everywhere, a marker. No confirmation; a "Deleted · Undo" snackbar follows.
- **Timelines on the board:** everything about timelines happens on the board, so the Timelines screen is optional. Tap a timeline's header to rename it, set its color, send it to the tray or delete it. Long-press the header to drag it: across the timelines to reorder, onto the tray to remove the timeline from the view and move its nodes and groups to the tray (in slot order, groups whole; the timeline is deleted if no other view uses it), or onto the trash to delete the timeline everywhere with every node on it. A + button after the last timeline (below it on a horizontal board, to its right on a vertical one) adds a timeline, offering the name "Timeline N". A view with no timelines still shows the board and tray.
- **Tray sort and filter:** the tray header has a sort/filter button. Order: manual (drag order), A–Z (group label or node text; blank last), or by primary tag (items sharing a primary tag sit together, A–Z within; untagged last). Filter by one tag (or "no tag") and by search text over node text and group labels. A tray item's tags are its group's then its nodes'. Display only: the stored order stays manual; dragging within the tray reorders only in manual order with no filter, otherwise drops append. Sort and filter last for the board session.
- **Delete / remove:** a board node's editor offers "Move to tray" and "Delete everywhere". A tray node's editor offers "Remove from view" only when another view also holds it.
- **Undo/redo:** required in the base version, covering all structural edits (moves, pushes, inserts, collapses, deletes).

## Tags and color

- One unified tag model: each tag has a name, an optional color, and an optional link to a communal note.
- Tags express dimensions (e.g. "Scene", "POV: Mara") and references (e.g. "Magic: Bloodbinding" → communal note).
- Taggable items: nodes, markers, groups, communal notes.
- Tags drive show/hide filtering.
- Color coding is available for all taggable items. Displayed color: the item's own override color if set, otherwise its primary tag's color.

## Communal notes

- Non-temporal reference notes (magic systems, pre-story history, world rules). Plain text for now.
- Organized into user-defined categories; communal notes can link to each other.
- Live in **world libraries**, which projects link to. Sharing is live: edits appear in every linked project.

## Manuscript authoring

Agreed with the user on 2026-11-11.

- **Scenes timeline:** at most one per project, chosen from the timeline header. It is a property of the timeline, so it applies in every view.
- **Scene nodes:** a node on the scenes timeline has a card name (the existing node text, edited and shown as today) and separate manuscript prose. Tapping it opens a full-screen scene editor instead of the node sheet. Scenes keep tags and color like any node.
- **Scene editor:**
  - **Fields:** card name, tags, color, and prose with **bold** and *italic*.
  - **Saving and undo:** text saves automatically. The editor has its own undo and redo while open; closing it adds the session to board undo as one entry.
  - **Stats:** word count, and page count at 250 words per page (adjustable in Settings).
  - **Same time:** a panel lists every node on another timeline whose slots overlap the scene's, plus the nearest marker before it. Tapping an item opens it read-only.
  - **World library:** a panel browses, searches and edits the project's linked notes. Notes linked from the scene's tags are listed first.
- **On the board:** scene cards show their word count. The scenes timeline header shows the manuscript total in words and pages. A node off the scenes timeline keeps its prose, nothing is deleted, and its card shows a manuscript icon.
- **Assemble:** a button on the scenes timeline header exports the manuscript as `.odt` through the system file picker.
  - **Order:** scenes in the current view's slot order. Tray scenes are excluded.
  - **Chapters:** each scene is one chapter, headed by its card name. An empty scene keeps its heading and has no body.
  - **Title and file name:** the project name is the document title. The file is `<project name>_yyyy_mm_dd.odt`, with whitespace replaced by underscores.
  - **Layout:** standard manuscript layout on the paper size chosen in Settings (Letter, A4 and common publishing trim sizes; "Automatic" picks Letter or A4 by region): 12 pt Times New Roman, double-spaced, first-line indents, each chapter on a new page.
  - **Author option:** an export option, off by default, adds an author name. That gives a title page (name, title, word count) and a page header (surname / short title / page number). With it off, pages carry only page numbers.

## Spanning nodes

- A node can stretch along its timeline to cover several consecutive slots, per view. Drag a handle on the card's trailing edge; it snaps to slots and pushes later nodes like a drop does.
- No other node can sit in a covered slot. Covered slots do not collapse. A span cannot cross a marker: the marker blocks it.
- Spans apply to every timeline, not only scenes. "Same time" uses the overlap of spans.

## Platform and data

- Android phone; portrait and landscape both supported. Tablet-specific layouts are deferred, but as of 2026-11-11 the user runs the app on a tablet and reports it works well.
- Layout: timelines are stacked horizontal rows; slots scroll left to right; markers render as vertical bars.
- Kotlin + Jetpack Compose + Room. Minimum SDK 26 (Android 8.0).
- Application ID: `com.storyarc.app`.
- Fully offline. No sync.
- Backups: a `.zip` of JSON, with matching import/restore. Scopes: per-project backup that includes its linked world libraries, plus a "back up everything" option.
- Previews: the user installs debug APKs themselves; no emulator setup required.

## Deferred

- Rich node content (formatting, images, structured fields).
- Templates (communal notes, nodes).
- Search.
- Additional export formats (Markdown, PDF). ODT export is covered under Manuscript authoring.
- Tablet layouts.
