package com.storyarc.app

import android.content.Context
import com.storyarc.app.data.db.StoryDatabase
import com.storyarc.app.data.repo.StoryRepository
import com.storyarc.app.data.settings.SettingsRepository

/** Manual dependency graph; one instance per process, owned by [StoryArcApp]. */
class AppContainer(val context: Context) {
    val database by lazy { StoryDatabase.create(context) }
    val repository by lazy { StoryRepository(database) }
    val settings by lazy { SettingsRepository(context) }
}
