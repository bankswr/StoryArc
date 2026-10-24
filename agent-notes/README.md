# agent-notes

Shared knowledge base for agents working on this project. Read relevant notes before starting a task; record anything a future agent would otherwise have to rediscover.

## What belongs here

- Decisions and their rationale (why something is the way it is)
- Non-obvious gotchas, workarounds, and environment quirks
- Project context not derivable from the code or git history
- Pointers to external resources (docs, dashboards, tickets)

## What does not belong here

- Anything the code, comments, or git history already records
- Session-specific scratch work or temporary state
- Secrets or credentials

## Conventions

- One topic per file, named in kebab-case: `topic-name.md`.
- Start each file with a one-line summary, then the details.
- Use absolute dates (YYYY-MM-DD), not relative ones.
- Update an existing note rather than creating a duplicate; delete notes that become wrong.
- Add every new note to the index below.

## Index

- [product-spec.md](product-spec.md) — confirmed product requirements: data model, interactions, tags, platform
- [technical-plan.md](technical-plan.md) — architecture, Room data model, board engine algorithms, undo, backup, milestones
- [build-environment.md](build-environment.md) — JAVA_HOME requirement, build/test commands, why dependency versions are pinned where they are
- [project-status.md](project-status.md) — what's built, how it's verified, what's unverified, known limitations
