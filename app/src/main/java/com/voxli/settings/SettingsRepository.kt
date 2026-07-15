package com.voxli.settings

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "voxli_settings")

/**
 * Typed settings keys for DataStore.
 * Reference: roadmap §4.4 (settings table) + §10 Phase 1 DataStore.
 */
object SettingsKeys {
    val FONT_NAME = stringPreferencesKey("font_name")
    val FONT_SIZE = floatPreferencesKey("font_size")
    val BG_COLOR = intPreferencesKey("bg_color")
    val BG_BRIGHTNESS = floatPreferencesKey("bg_brightness")
    val TEXT_COLOR = intPreferencesKey("text_color")
    val TEXT_BRIGHTNESS = floatPreferencesKey("text_brightness")
    val ACTIVE_MIRROR = stringPreferencesKey("active_mirror")
    val SELECTED_GENRES = stringPreferencesKey("selected_genres")  // comma-separated
    val SORT_FIELD_AUTHORS = stringPreferencesKey("sort_field_authors")
    val SORT_FIELD_TITLES = stringPreferencesKey("sort_field_titles")
}

class SettingsRepository(private val context: Context) {

    // ---- Flows ----

    val fontName: Flow<String> = context.dataStore.data.map { prefs ->
        prefs[SettingsKeys.FONT_NAME] ?: "sans-serif"
    }

    val fontSize: Flow<Float> = context.dataStore.data.map { prefs ->
        prefs[SettingsKeys.FONT_SIZE] ?: 16f
    }

    val bgColor: Flow<Int> = context.dataStore.data.map { prefs ->
        prefs[SettingsKeys.BG_COLOR] ?: 0xFFFFFFFF.toInt()
    }

    val bgBrightness: Flow<Float> = context.dataStore.data.map { prefs ->
        prefs[SettingsKeys.BG_BRIGHTNESS] ?: 1.0f
    }

    val textColor: Flow<Int> = context.dataStore.data.map { prefs ->
        prefs[SettingsKeys.TEXT_COLOR] ?: 0xFF000000.toInt()
    }

    val textBrightness: Flow<Float> = context.dataStore.data.map { prefs ->
        prefs[SettingsKeys.TEXT_BRIGHTNESS] ?: 1.0f
    }

    val activeMirror: Flow<String> = context.dataStore.data.map { prefs ->
        prefs[SettingsKeys.ACTIVE_MIRROR] ?: "http://flibusta.is"
    }

    val selectedGenres: Flow<Set<String>> = context.dataStore.data.map { prefs ->
        prefs[SettingsKeys.SELECTED_GENRES]
            ?.split(",")
            ?.filter { it.isNotBlank() }
            ?.toSet() ?: emptySet()
    }

    val sortFieldAuthors: Flow<String> = context.dataStore.data.map { prefs ->
        prefs[SettingsKeys.SORT_FIELD_AUTHORS] ?: "popularity"
    }

    val sortFieldTitles: Flow<String> = context.dataStore.data.map { prefs ->
        prefs[SettingsKeys.SORT_FIELD_TITLES] ?: "popularity"
    }

    // ---- Setters ----

    suspend fun setFontName(name: String) {
        context.dataStore.edit { it[SettingsKeys.FONT_NAME] = name }
    }

    suspend fun setFontSize(size: Float) {
        context.dataStore.edit { it[SettingsKeys.FONT_SIZE] = size }
    }

    suspend fun setBgColor(color: Int) {
        context.dataStore.edit { it[SettingsKeys.BG_COLOR] = color }
    }

    suspend fun setBgBrightness(brightness: Float) {
        context.dataStore.edit { it[SettingsKeys.BG_BRIGHTNESS] = brightness }
    }

    suspend fun setTextColor(color: Int) {
        context.dataStore.edit { it[SettingsKeys.TEXT_COLOR] = color }
    }

    suspend fun setTextBrightness(brightness: Float) {
        context.dataStore.edit { it[SettingsKeys.TEXT_BRIGHTNESS] = brightness }
    }

    suspend fun setActiveMirror(mirror: String) {
        context.dataStore.edit { it[SettingsKeys.ACTIVE_MIRROR] = mirror }
    }

    suspend fun setSelectedGenres(genres: Set<String>) {
        context.dataStore.edit { it[SettingsKeys.SELECTED_GENRES] = genres.joinToString(",") }
    }

    suspend fun setSortFieldAuthors(field: String) {
        context.dataStore.edit { it[SettingsKeys.SORT_FIELD_AUTHORS] = field }
    }

    suspend fun setSortFieldTitles(field: String) {
        context.dataStore.edit { it[SettingsKeys.SORT_FIELD_TITLES] = field }
    }
}
