package com.storyarc.app.data.settings

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

data class Settings(
    /** Whether a dragged item must dwell at a marker before crossing it. */
    val barrierDelayEnabled: Boolean = true,
    val barrierDelayMs: Long = 500,
    /** Board layout: slots top to bottom with timelines as columns, instead of left to right. */
    val verticalBoard: Boolean = false,
    /** Words per manuscript page, for page counts. */
    val wordsPerPage: Int = DEFAULT_WORDS_PER_PAGE,
) {
    companion object {
        /** The usual standard-manuscript estimate (12 pt, double-spaced). */
        const val DEFAULT_WORDS_PER_PAGE = 250
    }
}

private val Context.dataStore by preferencesDataStore("settings")

class SettingsRepository(private val context: Context) {
    private val delayEnabled = booleanPreferencesKey("barrier_delay_enabled")
    private val delayMs = longPreferencesKey("barrier_delay_ms")
    private val vertical = booleanPreferencesKey("board_vertical")
    private val wordsPerPage = intPreferencesKey("words_per_page")

    val settings: Flow<Settings> = context.dataStore.data.map { p ->
        Settings(
            p[delayEnabled] ?: true,
            p[delayMs] ?: 500,
            p[vertical] ?: false,
            p[wordsPerPage] ?: Settings.DEFAULT_WORDS_PER_PAGE,
        )
    }

    suspend fun setWordsPerPage(words: Int) {
        context.dataStore.edit { it[wordsPerPage] = words.coerceIn(100, 600) }
    }

    suspend fun setBarrierDelayEnabled(enabled: Boolean) {
        context.dataStore.edit { it[delayEnabled] = enabled }
    }

    suspend fun setBarrierDelayMs(ms: Long) {
        context.dataStore.edit { it[delayMs] = ms }
    }

    suspend fun setVerticalBoard(enabled: Boolean) {
        context.dataStore.edit { it[vertical] = enabled }
    }
}
