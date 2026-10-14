package io.github.shootgame.auth

import com.badlogic.gdx.Gdx
import com.badlogic.gdx.utils.JsonReader
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * Handles User Registration & Login with Username + Password stored in Firebase Realtime Database,
 * generates signed HS256 JWT tokens + Session tokens, and persists them in the local SQLite database
 * via [SqliteSessionStore].
 */
object FirebaseAuthService {

    private const val FIREBASE_DB_URL =
        "https://game-server-e24db-default-rtdb.asia-southeast1.firebasedatabase.app"
    private const val FIREBASE_DB_SECRET =
        "wx5hqcgTGDCRc5uNTFS1gTvQG2oljH8IWcAReGqk"
    private const val JWT_SIGNING_SECRET =
        "shootgame_jwt_secret_key_2026_firebase_auth"

    private const val SESSION_TTL_SECONDS = 7L * 24L * 3600L // 7 days

    private val secureRandom = SecureRandom()

    fun normalizeUserKey(username: String): String {
        return username.trim().lowercase().replace(Regex("[^a-z0-9_-]"), "_")
    }

    fun hashPassword(usernameKey: String, password: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val input = "shootgame_v1:$usernameKey:$password"
        val bytes = digest.digest(input.toByteArray(StandardCharsets.UTF_8))
        return bytes.joinToString("") { "%02x".format(it) }
    }

    fun generateSessionToken(): String {
        val bytes = ByteArray(16)
        secureRandom.nextBytes(bytes)
        return bytes.joinToString("") { "%02x".format(it) }
    }

    /**
     * Generates a standard RFC 7519 HS256 JWT token (`header.payload.signature`).
     */
    fun generateJwtToken(
        username: String,
        sessionToken: String,
        issuedAt: Long,
        expiresAt: Long
    ): String {
        val headerJson = """{"alg":"HS256","typ":"JWT"}"""
        val safeSub = escapeJson(username)
        val payloadJson =
            """{"sub":"$safeSub","jti":"$sessionToken","iat":$issuedAt,"exp":$expiresAt}"""

        val headerB64 = base64UrlEncode(headerJson.toByteArray(StandardCharsets.UTF_8))
        val payloadB64 = base64UrlEncode(payloadJson.toByteArray(StandardCharsets.UTF_8))
        val signingInput = "$headerB64.$payloadB64"

        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(JWT_SIGNING_SECRET.toByteArray(StandardCharsets.UTF_8), "HmacSHA256"))
        val sigBytes = mac.doFinal(signingInput.toByteArray(StandardCharsets.UTF_8))
        val sigB64 = base64UrlEncode(sigBytes)

        return "$signingInput.$sigB64"
    }

    private fun base64UrlEncode(bytes: ByteArray): String {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
    }

    private fun escapeJson(s: String): String {
        return s.replace("\\", "\\\\").replace("\"", "\\\"")
    }

    /**
     * Authenticates an existing user with [username] and [password] against Firebase Realtime Database,
     * issues a fresh JWT token + session token, updates Firebase, and stores the session in SQLite.
     */
    fun login(
        username: String,
        password: String,
        onComplete: (Result<AuthSessionRecord>) -> Unit
    ) {
        val cleanUser = username.trim()
        if (cleanUser.length < 3) {
            onComplete(Result.failure(IllegalArgumentException("Username must be at least 3 characters.")))
            return
        }
        if (password.length < 4) {
            onComplete(Result.failure(IllegalArgumentException("Password must be at least 4 characters.")))
            return
        }

        val userKey = normalizeUserKey(cleanUser)
        val expectedHash = hashPassword(userKey, password)

        Thread {
            val result = try {
                val userUrl = "$FIREBASE_DB_URL/shootgame/users/$userKey.json?auth=$FIREBASE_DB_SECRET"
                val existingBody = httpRequest("GET", userUrl, null)

                if (existingBody.isBlank() || existingBody.trim() == "null") {
                    Result.failure(IllegalStateException("Account '$cleanUser' not found. Click REGISTER to create it."))
                } else {
                    val json = JsonReader().parse(existingBody)
                    val storedHash = json.getString("password_hash", "")
                    val canonicalUsername = json.getString("username", cleanUser)
                    val createdAtUser = json.getLong("created_at", System.currentTimeMillis() / 1000L)

                    if (storedHash != expectedHash) {
                        Result.failure(IllegalArgumentException("Invalid password for '$canonicalUsername'."))
                    } else {
                        val now = System.currentTimeMillis() / 1000L
                        val exp = now + SESSION_TTL_SECONDS
                        val sessionToken = generateSessionToken()
                        val jwtToken = generateJwtToken(canonicalUsername, sessionToken, now, exp)

                        val updatedUserJson = """{
                            "username":"${escapeJson(canonicalUsername)}",
                            "password_hash":"$expectedHash",
                            "jwt_token":"$jwtToken",
                            "session_token":"$sessionToken",
                            "created_at":$createdAtUser,
                            "last_login":$now
                        }""".trimIndent()
                        httpRequest("PUT", userUrl, updatedUserJson)

                        val sessionUrl = "$FIREBASE_DB_URL/shootgame/sessions/$userKey.json?auth=$FIREBASE_DB_SECRET"
                        val sessionJson = """{
                            "username":"${escapeJson(canonicalUsername)}",
                            "jwt_token":"$jwtToken",
                            "session_token":"$sessionToken",
                            "created_at":$now,
                            "expires_at":$exp
                        }""".trimIndent()
                        httpRequest("PUT", sessionUrl, sessionJson)

                        val record = AuthSessionRecord(
                            username = canonicalUsername,
                            jwtToken = jwtToken,
                            sessionToken = sessionToken,
                            createdAt = now,
                            expiresAt = exp
                        )
                        SqliteSessionStore.saveSession(record)
                        Result.success(record)
                    }
                }
            } catch (e: Exception) {
                Result.failure(IllegalStateException("Firebase connection error: ${e.message}"))
            }

            Gdx.app.postRunnable {
                onComplete(result)
            }
        }.apply {
            isDaemon = true
            name = "Firebase-Login-Thread"
            start()
        }
    }

    /**
     * Registers a new user with [username] and [password] inside Firebase Realtime Database,
     * issues a signed JWT token + session token, and persists them inside the local SQLite database.
     */
    fun register(
        username: String,
        password: String,
        onComplete: (Result<AuthSessionRecord>) -> Unit
    ) {
        val cleanUser = username.trim()
        if (cleanUser.length < 3) {
            onComplete(Result.failure(IllegalArgumentException("Username must be at least 3 characters.")))
            return
        }
        if (password.length < 4) {
            onComplete(Result.failure(IllegalArgumentException("Password must be at least 4 characters.")))
            return
        }

        val userKey = normalizeUserKey(cleanUser)
        val passwordHash = hashPassword(userKey, password)

        Thread {
            val result = try {
                val userUrl = "$FIREBASE_DB_URL/shootgame/users/$userKey.json?auth=$FIREBASE_DB_SECRET"
                val existingBody = httpRequest("GET", userUrl, null)

                if (existingBody.isNotBlank() && existingBody.trim() != "null") {
                    val json = JsonReader().parse(existingBody)
                    val storedHash = json.getString("password_hash", "")
                    if (storedHash.isNotEmpty() && storedHash != passwordHash) {
                        Result.failure(IllegalStateException("Username '$cleanUser' is already taken!"))
                    } else {
                        // Same user & password -> issue new JWT & session
                        val now = System.currentTimeMillis() / 1000L
                        val exp = now + SESSION_TTL_SECONDS
                        val sessionToken = generateSessionToken()
                        val jwtToken = generateJwtToken(cleanUser, sessionToken, now, exp)
                        val record = AuthSessionRecord(cleanUser, jwtToken, sessionToken, now, exp)
                        SqliteSessionStore.saveSession(record)
                        Result.success(record)
                    }
                } else {
                    val now = System.currentTimeMillis() / 1000L
                    val exp = now + SESSION_TTL_SECONDS
                    val sessionToken = generateSessionToken()
                    val jwtToken = generateJwtToken(cleanUser, sessionToken, now, exp)

                    val newUserJson = """{
                        "username":"${escapeJson(cleanUser)}",
                        "password_hash":"$passwordHash",
                        "jwt_token":"$jwtToken",
                        "session_token":"$sessionToken",
                        "created_at":$now,
                        "last_login":$now
                    }""".trimIndent()
                    httpRequest("PUT", userUrl, newUserJson)

                    val sessionUrl = "$FIREBASE_DB_URL/shootgame/sessions/$userKey.json?auth=$FIREBASE_DB_SECRET"
                    val sessionJson = """{
                        "username":"${escapeJson(cleanUser)}",
                        "jwt_token":"$jwtToken",
                        "session_token":"$sessionToken",
                        "created_at":$now,
                        "expires_at":$exp
                    }""".trimIndent()
                    httpRequest("PUT", sessionUrl, sessionJson)

                    val record = AuthSessionRecord(
                        username = cleanUser,
                        jwtToken = jwtToken,
                        sessionToken = sessionToken,
                        createdAt = now,
                        expiresAt = exp
                    )
                    SqliteSessionStore.saveSession(record)
                    Result.success(record)
                }
            } catch (e: Exception) {
                Result.failure(IllegalStateException("Firebase registration error: ${e.message}"))
            }

            Gdx.app.postRunnable {
                onComplete(result)
            }
        }.apply {
            isDaemon = true
            name = "Firebase-Register-Thread"
            start()
        }
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
