package com.technewz.app.data

import android.content.Context
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import java.util.Locale

private val Context.dataStore by preferencesDataStore(name = "settings")

@Serializable
data class Profile(
    val fullName: String = "",
    val email: String = "",
    val phone: String = "",
    val location: String = "",
    val headline: String = "",
    val linkedin: String = "",
    val github: String = "",
    val portfolio: String = "",
    val summary: String = "",
    val skills: List<String> = emptyList(),
    val education: List<String> = emptyList(),
    val experience: List<String> = emptyList(),
    val projects: List<String> = emptyList(),
    val resumeFileName: String = "",
) {
    val firstName: String get() = fullName.trim().substringBefore(' ')
    val isEmpty: Boolean get() = fullName.isBlank() && skills.isEmpty()
}

enum class ThemeMode { SYSTEM, LIGHT, DARK }

data class AppSettings(
    val geminiKey: String = "",
    val geminiModel: String = DEFAULT_MODEL,
    val adzunaAppId: String = "",
    val adzunaAppKey: String = "",
    val openAlexKey: String = "",
    val city: String = "",
    val countryCode: String = Locale.getDefault().country.lowercase(),
    val keywords: List<String> = emptyList(),
    val alertsEnabled: Boolean = true,
    /** Rewrite summaries with Gemini (adds "why it matters"). Off = on-device model only, no API. */
    val aiSummaries: Boolean = false,
    val backgroundRefresh: Boolean = true,
    val theme: ThemeMode = ThemeMode.SYSTEM,
    val onboarded: Boolean = false,
    val greenhouseBoards: String = DEFAULT_GREENHOUSE,
    val leverBoards: String = DEFAULT_LEVER,
    val ashbyBoards: String = DEFAULT_ASHBY,
    val lastNewsRefresh: Long = 0,
    val lastJobsRefresh: Long = 0,
    val lastRadarRefresh: Long = 0,
    val radarVersion: Int = 0,
    val batteryPromptDismissed: Boolean = false,
    val lastError: String = "",
) {
    val hasAi: Boolean get() = geminiKey.isNotBlank()

    companion object {
        const val DEFAULT_MODEL = "gemini-2.5-flash-lite"
        const val DEFAULT_GREENHOUSE =
            "anthropic, databricks, stripe, figma, airbnb, discord, cloudflare, scaleai, gitlab, mongodb, dropbox, pinterest"
        const val DEFAULT_LEVER = "palantir, spotify"
        const val DEFAULT_ASHBY = "openai, notion, linear, ramp"
    }
}

class SettingsRepository(private val context: Context) {
    private object K {
        val geminiKey = stringPreferencesKey("gemini_key")
        val geminiModel = stringPreferencesKey("gemini_model")
        val adzunaId = stringPreferencesKey("adzuna_id")
        val adzunaKey = stringPreferencesKey("adzuna_key")
        val openAlexKey = stringPreferencesKey("openalex_key")
        val city = stringPreferencesKey("city")
        val country = stringPreferencesKey("country")
        val keywords = stringPreferencesKey("keywords")
        val alerts = booleanPreferencesKey("alerts")
        val aiSummaries = booleanPreferencesKey("ai_summaries")
        val bgRefresh = booleanPreferencesKey("bg_refresh")
        val theme = stringPreferencesKey("theme")
        val onboarded = booleanPreferencesKey("onboarded")
        val greenhouse = stringPreferencesKey("greenhouse")
        val lever = stringPreferencesKey("lever")
        val ashby = stringPreferencesKey("ashby")
        val lastNews = longPreferencesKey("last_news")
        val lastJobs = longPreferencesKey("last_jobs")
        val lastRadar = longPreferencesKey("last_radar")
        val radarVersion = androidx.datastore.preferences.core.intPreferencesKey("radar_version")
        val batteryPrompt = booleanPreferencesKey("battery_prompt_dismissed")
        val lastError = stringPreferencesKey("last_error")
        val profile = stringPreferencesKey("profile")
    }

    val settings: Flow<AppSettings> = context.dataStore.data.map { it.toSettings() }

    val profile: Flow<Profile> = context.dataStore.data.map { prefs ->
        prefs[K.profile]?.let { runCatching { AppJson.decodeFromString<Profile>(it) }.getOrNull() } ?: Profile()
    }

    suspend fun current(): AppSettings = settings.first()
    suspend fun currentProfile(): Profile = profile.first()

    private fun Preferences.toSettings(): AppSettings {
        val d = AppSettings()
        return AppSettings(
            geminiKey = this[K.geminiKey] ?: "",
            geminiModel = this[K.geminiModel]?.takeIf { it.isNotBlank() } ?: d.geminiModel,
            adzunaAppId = this[K.adzunaId] ?: "",
            adzunaAppKey = this[K.adzunaKey] ?: "",
            openAlexKey = this[K.openAlexKey] ?: "",
            city = this[K.city] ?: "",
            countryCode = this[K.country] ?: d.countryCode,
            keywords = (this[K.keywords] ?: "").split("|").map { it.trim() }.filter { it.isNotEmpty() },
            alertsEnabled = this[K.alerts] ?: true,
            aiSummaries = this[K.aiSummaries] ?: false,
            backgroundRefresh = this[K.bgRefresh] ?: true,
            theme = runCatching { ThemeMode.valueOf(this[K.theme] ?: "SYSTEM") }.getOrDefault(ThemeMode.SYSTEM),
            onboarded = this[K.onboarded] ?: false,
            greenhouseBoards = this[K.greenhouse] ?: d.greenhouseBoards,
            leverBoards = this[K.lever] ?: d.leverBoards,
            ashbyBoards = this[K.ashby] ?: d.ashbyBoards,
            lastNewsRefresh = this[K.lastNews] ?: 0,
            lastJobsRefresh = this[K.lastJobs] ?: 0,
            lastRadarRefresh = this[K.lastRadar] ?: 0,
            radarVersion = this[K.radarVersion] ?: 0,
            batteryPromptDismissed = this[K.batteryPrompt] ?: false,
            lastError = this[K.lastError] ?: "",
        )
    }

    suspend fun update(transform: (AppSettings) -> AppSettings) {
        context.dataStore.edit { prefs ->
            val s = transform(prefs.toSettings())
            prefs[K.geminiKey] = s.geminiKey.trim()
            prefs[K.geminiModel] = s.geminiModel.trim()
            prefs[K.adzunaId] = s.adzunaAppId.trim()
            prefs[K.adzunaKey] = s.adzunaAppKey.trim()
            prefs[K.openAlexKey] = s.openAlexKey.trim()
            prefs[K.city] = s.city.trim()
            prefs[K.country] = s.countryCode.trim().lowercase()
            prefs[K.keywords] = s.keywords.joinToString("|")
            prefs[K.alerts] = s.alertsEnabled
            prefs[K.aiSummaries] = s.aiSummaries
            prefs[K.bgRefresh] = s.backgroundRefresh
            prefs[K.theme] = s.theme.name
            prefs[K.onboarded] = s.onboarded
            prefs[K.greenhouse] = s.greenhouseBoards
            prefs[K.lever] = s.leverBoards
            prefs[K.ashby] = s.ashbyBoards
            prefs[K.lastNews] = s.lastNewsRefresh
            prefs[K.lastJobs] = s.lastJobsRefresh
            prefs[K.lastRadar] = s.lastRadarRefresh
            prefs[K.radarVersion] = s.radarVersion
            prefs[K.batteryPrompt] = s.batteryPromptDismissed
            prefs[K.lastError] = s.lastError
        }
    }

    suspend fun saveProfile(profile: Profile) {
        context.dataStore.edit { it[K.profile] = AppJson.encodeToString(profile) }
    }
}

val AppJson = kotlinx.serialization.json.Json {
    ignoreUnknownKeys = true
    isLenient = true
    coerceInputValues = true
    explicitNulls = false
}
