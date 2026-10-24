# StoryArc

Android app (Kotlin, Jetpack Compose, Room) for planning creative projects on parallel, user-defined timelines. Modules: `:board` (pure-Kotlin layout engine) and `:app`.

## Agent notes

`agent-notes/` holds shared project knowledge for agents. Before starting a task, check `agent-notes/README.md` and read the notes relevant to it. When you learn something a future agent would need (decisions, gotchas, context not in the code), record it there following the conventions in that README.

## Build

Set `JAVA_HOME=~/android-studio/jbr` before running Gradle (the system Java has no compiler). See `agent-notes/build-environment.md`.

- `./gradlew assembleDebug`: debug APK at `app/build/outputs/apk/debug/app-debug.apk`
- `./gradlew test`: all tests (JVM and Robolectric; no device needed)
