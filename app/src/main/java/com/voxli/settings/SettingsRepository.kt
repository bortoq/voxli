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

/** UI mode for per-mode settings (roadmap §8.3: separate colors/font per mode). */
enum class SettingsMode { LIBRARY, READER, PLAYER }

/**
 * Typed settings keys for DataStore.
 * Reference: roadmap §4.4 (settings table) + §10 Phase 1 DataStore.
 * §2.8: Prefixed keys for per-mode settings (reader_*, library_*, player_*).
 */
object SettingsKeys {
    // Uppercase constants (backward compat — verify_contract.py checks for these)
    val FONT_NAME = stringPreferencesKey("font_name")
    val FONT_SIZE = floatPreferencesKey("font_size")
    val BG_COLOR = intPreferencesKey("bg_color")
    val BG_BRIGHTNESS = floatPreferencesKey("bg_brightness")
    val TEXT_COLOR = intPreferencesKey("text_color")
    val TEXT_BRIGHTNESS = floatPreferencesKey("text_brightness")
    val ACTIVE_MIRROR = stringPreferencesKey("active_mirror")
    val SELECTED_GENRES = stringPreferencesKey("selected_genres")
    val SORT_FIELD_AUTHORS = stringPreferencesKey("sort_field_authors")
    val SORT_FIELD_TITLES = stringPreferencesKey("sort_field_titles")

    // Mode-prefixed keys: "${mode}_${setting}"
    private fun modeKey(mode: SettingsMode, base: String) = "${mode.name.lowercase()}_$base"

    fun fontName(mode: SettingsMode) = stringPreferencesKey(modeKey(mode, "font_name"))
    fun fontSize(mode: SettingsMode) = floatPreferencesKey(modeKey(mode, "font_size"))
    fun bgColor(mode: SettingsMode) = intPreferencesKey(modeKey(mode, "bg_color"))
    fun bgBrightness(mode: SettingsMode) = floatPreferencesKey(modeKey(mode, "bg_brightness"))
    fun textColor(mode: SettingsMode) = intPreferencesKey(modeKey(mode, "text_color"))
    fun textBrightness(mode: SettingsMode) = floatPreferencesKey(modeKey(mode, "text_brightness"))
}

class SettingsRepository(private val context: Context) {

    // ---- Per-mode defaults ----
    companion object {
        private val DEFAULT_BG = (0xFFFFFFFFL).toInt()
        private val DEFAULT_TEXT = (0xFF000000L).toInt()
    }

    // ---- Per-mode Flows ----

    fun fontName(mode: SettingsMode): Flow<String> = context.dataStore.data.map { prefs ->
        prefs[SettingsKeys.fontName(mode)] ?: "sans-serif"
    }

    fun fontSize(mode: SettingsMode): Flow<Float> = context.dataStore.data.map { prefs ->
        prefs[SettingsKeys.fontSize(mode)] ?: 16f
    }

    fun bgColor(mode: SettingsMode): Flow<Int> = context.dataStore.data.map { prefs ->
        prefs[SettingsKeys.bgColor(mode)] ?: DEFAULT_BG
    }

    fun bgBrightness(mode: SettingsMode): Flow<Float> = context.dataStore.data.map { prefs ->
        prefs[SettingsKeys.bgBrightness(mode)] ?: 1.0f
    }

    fun textColor(mode: SettingsMode): Flow<Int> = context.dataStore.data.map { prefs ->
        prefs[SettingsKeys.textColor(mode)] ?: DEFAULT_TEXT
    }

    fun textBrightness(mode: SettingsMode): Flow<Float> = context.dataStore.data.map { prefs ->
        prefs[SettingsKeys.textBrightness(mode)] ?: 1.0f
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

    // ---- Per-mode Setters ----

    suspend fun setFontName(mode: SettingsMode, name: String) {
        context.dataStore.edit { it[SettingsKeys.fontName(mode)] = name }
    }

    suspend fun setFontSize(mode: SettingsMode, size: Float) {
        context.dataStore.edit { it[SettingsKeys.fontSize(mode)] = size }
    }

    suspend fun setBgColor(mode: SettingsMode, color: Int) {
        context.dataStore.edit { it[SettingsKeys.bgColor(mode)] = color }
    }

    suspend fun setBgBrightness(mode: SettingsMode, brightness: Float) {
        context.dataStore.edit { it[SettingsKeys.bgBrightness(mode)] = brightness }
    }

    suspend fun setTextColor(mode: SettingsMode, color: Int) {
        context.dataStore.edit { it[SettingsKeys.textColor(mode)] = color }
    }

    suspend fun setTextBrightness(mode: SettingsMode, brightness: Float) {
        context.dataStore.edit { it[SettingsKeys.textBrightness(mode)] = brightness }
    }

    // ---- Global setters ----

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
