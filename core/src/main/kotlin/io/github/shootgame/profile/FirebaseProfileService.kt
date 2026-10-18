package io.github.shootgame.profile

import com.badlogic.gdx.Gdx
import com.badlogic.gdx.utils.JsonReader
import io.github.shootgame.auth.FirebaseAuthService
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL
import java.nio.charset.StandardCharsets

data class PlayerProfile(
    var username: String = "PLAYER",
    var score: Long = 0L,
    var xp: Long = 0L,
    var kills: Int = 0,
    var deaths: Int = 0,
    var matchesPlayed: Int = 0,
    var matchesWon: Int = 0,
    var lastUpdated: Long = System.currentTimeMillis() / 1000L
) {
    val level: Int
        get() = (1 + (xp / 1000L)).toInt().coerceAtLeast(1)

    val currentLevelXp: Long
        get() = xp % 1000L

    val nextLevelXp: Long
        get() = 1000L

    val progressRatio: Float
        get() = (currentLevelXp.toFloat() / nextLevelXp.toFloat()).coerceIn(0f, 1f)

    val rankTitle: String
        get() = when {
            level >= 25 -> "ARENA OVERLORD"
            level >= 20 -> "CYBER ELITE"
            level >= 15 -> "VETERAN ASSASSIN"
            level >= 10 -> "TACTICAL STRIKER"
            level >= 5  -> "COMBAT OPERATIVE"
            else        -> "ROOKIE CADET"
        }

    val winRatePct: Int
        get() = if (matchesPlayed > 0) ((matchesWon.toFloat() / matchesPlayed) * 100).toInt() else 0

    val kdRatio: Float
        get() = if (deaths > 0) kills.toFloat() / deaths else kills.toFloat()
}

/**
 * Service managing Player Score and XP persistence with Firebase Realtime Database
 * and local preferences caching for instantaneous, seamless offline/online gameplay.
 */
object FirebaseProfileService {

    private const val FIREBASE_DB_URL =
        "https://game-server-e24db-default-rtdb.asia-southeast1.firebasedatabase.app"
    private const val FIREBASE_DB_SECRET =
        "wx5hqcgTGDCRc5uNTFS1gTvQG2oljH8IWcAReGqk"

    private const val PREFS_NAME = "shootgame_player_profile"

    @Volatile
    private var inMemoryProfile: PlayerProfile? = null

    /**
     * Synchronously returns cached profile for instantaneous UI rendering.
     */
    fun getCachedProfile(username: String = "PLAYER"): PlayerProfile {
        val mem = inMemoryProfile
        if (mem != null && mem.username.equals(username, ignoreCase = true)) {
            return mem
        }
        val loaded = loadFromPrefs(username)
        inMemoryProfile = loaded
        return loaded
    }

    /**
     * Fetches the latest Player Profile from Firebase Realtime Database in background thread,
     * updates the local cache, and invokes [onComplete] on LibGDX main thread.
     */
    fun fetchProfile(
        username: String,
        onComplete: (PlayerProfile) -> Unit
    ) {
        val cleanName = username.trim().ifEmpty { "PLAYER" }
        val userKey = FirebaseAuthService.normalizeUserKey(cleanName)

        // Read local cache immediately
        val cached = getCachedProfile(cleanName)

        Thread {
            val profile = try {
                val urlStr = "$FIREBASE_DB_URL/shootgame/users/$userKey/profile.json?auth=$FIREBASE_DB_SECRET"
                val body = httpRequest("GET", urlStr, null)

                if (body.isBlank() || body.trim() == "null") {
                    // Create default profile in Firebase
                    val initial = cached.copy(username = cleanName)
                    val json = toJson(initial)
                    httpRequest("PUT", urlStr, json)
                    saveToPrefs(initial)
                    inMemoryProfile = initial
                    initial
                } else {
                    val parsed = parseProfileJson(body, cleanName)
                    saveToPrefs(parsed)
                    inMemoryProfile = parsed
                    parsed
                }
            } catch (e: Exception) {
                Gdx.app.error("FirebaseProfile", "Error fetching profile for $cleanName: ${e.message}")
                cached
            }

            Gdx.app.postRunnable {
                onComplete(profile)
            }
        }.apply {
            isDaemon = true
            name = "Firebase-Profile-Fetch"
            start()
        }
    }

    /**
     * Adds match rewards (score, xp, kills, deaths, match result), immediately
     * updates local cache, and syncs asynchronously to Firebase Realtime Database.
     */
    fun addMatchResult(
        username: String,
        scoreDelta: Long,
        xpDelta: Long,
        killsDelta: Int,
        deathsDelta: Int,
        won: Boolean,
        onComplete: ((PlayerProfile) -> Unit)? = null
    ) {
        val cleanName = username.trim().ifEmpty { "PLAYER" }
        val userKey = FirebaseAuthService.normalizeUserKey(cleanName)

        // 1. Immediately apply to local cache
        val current = getCachedProfile(cleanName)
        current.score = (current.score + scoreDelta).coerceAtLeast(0L)
        current.xp = (current.xp + xpDelta).coerceAtLeast(0L)
        current.kills += killsDelta.coerceAtLeast(0)
        current.deaths += deathsDelta.coerceAtLeast(0)
        current.matchesPlayed++
        if (won) current.matchesWon++
        current.lastUpdated = System.currentTimeMillis() / 1000L

        saveToPrefs(current)
        inMemoryProfile = current

        // 2. Asynchronously sync to Firebase
        Thread {
            try {
                val urlStr = "$FIREBASE_DB_URL/shootgame/users/$userKey/profile.json?auth=$FIREBASE_DB_SECRET"
                val json = toJson(current)
                httpRequest("PUT", urlStr, json)
            } catch (e: Exception) {
                Gdx.app.error("FirebaseProfile", "Error syncing match result for $cleanName: ${e.message}")
            }

            if (onComplete != null) {
                Gdx.app.postRunnable {
                    onComplete(current)
                }
            }
        }.apply {
            isDaemon = true
            name = "Firebase-Profile-Sync"
            start()
        }
    }

    private fun toJson(p: PlayerProfile): String {
        return """{
            "username":"${escapeJson(p.username)}",
            "score":${p.score},
            "xp":${p.xp},
            "kills":${p.kills},
            "deaths":${p.deaths},
            "matches_played":${p.matchesPlayed},
            "matches_won":${p.matchesWon},
            "last_updated":${p.lastUpdated}
        }""".trimIndent()
    }

    private fun parseProfileJson(jsonStr: String, fallbackUsername: String): PlayerProfile {
        return try {
            val root = JsonReader().parse(jsonStr)
            PlayerProfile(
                username = root.getString("username", fallbackUsername),
                score = root.getLong("score", 0L),
                xp = root.getLong("xp", 0L),
                kills = root.getInt("kills", 0),
                deaths = root.getInt("deaths", 0),
                matchesPlayed = root.getInt("matches_played", 0),
                matchesWon = root.getInt("matches_won", 0),
                lastUpdated = root.getLong("last_updated", System.currentTimeMillis() / 1000L)
            )
        } catch (e: Exception) {
            PlayerProfile(username = fallbackUsername)
        }
    }

    private fun saveToPrefs(p: PlayerProfile) {
        try {
            val prefs = Gdx.app.getPreferences(PREFS_NAME)
            prefs.putString("username", p.username)
            prefs.putLong("score", p.score)
            prefs.putLong("xp", p.xp)
            prefs.putInteger("kills", p.kills)
            prefs.putInteger("deaths", p.deaths)
            prefs.putInteger("matches_played", p.matchesPlayed)
            prefs.putInteger("matches_won", p.matchesWon)
            prefs.putLong("last_updated", p.lastUpdated)
            prefs.flush()
        } catch (_: Exception) {
        }
    }

    private fun loadFromPrefs(username: String): PlayerProfile {
        return try {
            val prefs = Gdx.app.getPreferences(PREFS_NAME)
            val storedName = prefs.getString("username", username)
            PlayerProfile(
                username = storedName.ifEmpty { username },
                score = prefs.getLong("score", 0L),
                xp = prefs.getLong("xp", 0L),
                kills = prefs.getInteger("kills", 0),
                deaths = prefs.getInteger("deaths", 0),
                matchesPlayed = prefs.getInteger("matches_played", 0),
                matchesWon = prefs.getInteger("matches_won", 0),
                lastUpdated = prefs.getLong("last_updated", System.currentTimeMillis() / 1000L)
            )
        } catch (_: Exception) {
            PlayerProfile(username = username)
        }
    }

    private fun escapeJson(s: String): String {
        return s.replace("\\", "\\\\").replace("\"", "\\\"")
    }

    private fun httpRequest(method: String, urlStr: String, jsonBody: String?): String {
        val conn = (URL(urlStr).openConnection() as HttpURLConnection).apply {
            requestMethod = method
            connectTimeout = 6000
            readTimeout = 6000
            setRequestProperty("Accept", "application/json")
            if (jsonBody != null) {
                doOutput = true
                setRequestProperty("Content-Type", "application/json; charset=UTF-8")
            }
        }
        try {
            if (jsonBody != null) {
                OutputStreamWriter(conn.outputStream, StandardCharsets.UTF_8).use { writer ->
                    writer.write(jsonBody)
                    writer.flush()
                }
            }
            val code = conn.responseCode
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            if (stream == null) return ""
            return BufferedReader(InputStreamReader(stream, StandardCharsets.UTF_8)).use {
                it.readText()
            }
        } finally {
            conn.disconnect()
        }
    }
}
