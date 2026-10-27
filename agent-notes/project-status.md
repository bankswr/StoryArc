# Project status

Where StoryArc stands after milestones M0–M11 (all committed 2026-10-24), and what is known to be unverified or missing.

## Built

All milestones from [technical-plan.md](technical-plan.md) are done, one commit each.

| Milestone | Delivered |
|---|---|
| M0–M2 | Scaffold, `:board` engine, Room schema with deferred FKs, change sets and undo/redo. |
| M3–M4 | Projects, project home, timelines and settings screens; board canvas. |
| M5–M6 | Drag and drop with marker barriers; groups. |
| M7 | Multiple views. |
| M8 | Tags and color. |
| M9 | World libraries. |
| M10 | Backup and restore. |
| M11 | TalkBack move actions on cards and markers; a non-collapsing drag preview, so columns don't shift under the finger; a large-board performance test (~1 ms per move at 2,000 nodes). |
| After M11 | Horizontal/vertical board layout setting (`VerticalBoardTest`). |
| After M11 | Per-view tray, floating new-node editor, copy of nodes and groups, drag-to-trash (`TrayTest`, `TrayBoardTest`, `TrayGeometryTest`, `MigrationTest`). DB schema v2, backup format 2 (format 1 still restores). |
| After M11 | Release signing (see [build-environment.md](build-environment.md)); Settings → About shows app version and DB schema version. |
| After M11 | Timeline headers: tap to edit, drag to reorder / tray / trash, + to add. Tray sort (manual, A–Z, by primary tag) and filter (tag, search) — `TrayView.kt`, `TrayViewTest`. |

## How it's verified

- `./gradlew test` runs everything on the JVM: engine unit and fuzz tests, Robolectric repository/backup tests, and Compose UI flow and gesture tests.
- `BoardScreenshotTest` writes PNGs to `app/build/screenshots/` for visual review.
- See [build-environment.md](build-environment.md) for the test-harness quirks.

## Not yet verified

- **No real device:** nothing has run on a physical device or emulator yet. Gesture feel, haptics, long-press timing, edge auto-scroll speed, IME behaviour in sheets and SAF file pickers have only been exercised under Robolectric (or not at all, for the file pickers).

## Known limitations and likely next steps

- **Schema:** Room schema is at version 2 (`StoryDatabase.SCHEMA_VERSION`, shown under Settings → About), with an auto-migration from 1. Any entity change needs a migration and, if fields change, a `BackupManager.FORMAT_VERSION` bump with conversion.
- **Undo:** history is in memory per project and lost when the process dies.
- **Drag preview:** columns inserted by the preview still shift content to the right of the finger. Only the source column is held in place.
- **Group bars:** they are grabbed in a 16dp strip at the bottom of each row. This may be a small touch target on a real phone.
- **Deferred (from the spec):** rich node content, templates, search, Markdown/PDF export, tablet layouts.
- **Toolchain:** pinned to AGP 8.13 / compileSdk 36. Newer AndroidX releases need AGP 9 (see build-environment.md).
