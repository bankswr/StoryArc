package com.storyarc.app

import android.content.Context

/** Manual dependency graph; one instance per process, owned by [StoryArcApp]. */
class AppContainer(val context: Context)
