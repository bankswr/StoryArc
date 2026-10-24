package com.storyarc.app.data.settings

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

data class Settings(
    /** Whether a dragged item must dwell at a marker before crossing it. */
    val barrierDelayEnabled: Boolean = true,
    val barrierDelayMs: Long = 500,
)

private val Context.dataStore by preferencesDataStore("settings")

class SettingsRepository(private val context: Context) {
    private val delayEnabled = booleanPreferencesKey("barrier_delay_enabled")
    private val delayMs = longPreferencesKey("barrier_delay_ms")

    val settings: Flow<Settings> = context.dataStore.data.map { p ->
        Settings(p[delayEnabled] ?: true, p[delayMs] ?: 500)
    }

    suspend fun setBarrierDelayEnabled(enabled: Boolean) {
        context.dataStore.edit { it[delayEnabled] = enabled }
    }

    suspend fun setBarrierDelayMs(ms: Long) {
        context.dataStore.edit { it[delayMs] = ms }
    }
}
