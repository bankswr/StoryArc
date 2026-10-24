package com.storyarc.app

import android.content.Context
import com.storyarc.app.data.db.StoryDatabase
import com.storyarc.app.data.repo.StoryRepository
import com.storyarc.app.data.settings.Settings
import com.storyarc.app.data.settings.SettingsRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn

/** Manual dependency graph; one instance per process, owned by [StoryArcApp]. */
class AppContainer(val context: Context) {
    val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    val database by lazy { StoryDatabase.create(context) }
    val repository by lazy { StoryRepository(database) }
    val settingsRepository by lazy { SettingsRepository(context) }

    /** Current settings, collected once for the whole process so screens read a plain StateFlow. */
    val settings: StateFlow<Settings> by lazy {
        settingsRepository.settings.stateIn(appScope, SharingStarted.Eagerly, Settings())
    }
}
