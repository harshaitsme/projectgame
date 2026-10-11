package io.github.shootgame.auth

import com.badlogic.gdx.Gdx
import java.io.File
import java.sql.DriverManager

data class AuthSessionRecord(
    val username: String,
    val jwtToken: String,
    val sessionToken: String,
    val createdAt: Long,
    val expiresAt: Long
) {
    val isExpired: Boolean
        get() = (System.currentTimeMillis() / 1000L) >= expiresAt

    val shortJwtPreview: String
        get() = if (jwtToken.length > 24) {
            "${jwtToken.take(12)}...${jwtToken.takeLast(8)}"
        } else {
            jwtToken
        }
}

interface SqliteBackend {
    fun initTable()
    fun saveSession(record: AuthSessionRecord)
    fun loadActiveSession(): AuthSessionRecord?
    fun clearSessions()
}

/**
 * Client-side SQLite database store for JWT tokens and user login sessions.
 * Uses Android's built-in SQLiteDatabase on Android (via [AndroidSqliteBackend] or reflection)
 * and JDBC SQLite (`jdbc:sqlite:shootgame_session.db`) on Desktop/JVM, with a local preferences
 * fallback if no JDBC driver is present on the classpath.
 */
object SqliteSessionStore {

    private const val DB_FILE_NAME = "shootgame_session.db"

    const val SQL_CREATE_TABLE = """
        CREATE TABLE IF NOT EXISTS user_sessions (
            id INTEGER PRIMARY KEY AUTOINCREMENT,
            username TEXT UNIQUE NOT NULL,
            jwt_token TEXT NOT NULL,
            session_token TEXT NOT NULL,
            created_at INTEGER NOT NULL,
            expires_at INTEGER NOT NULL
        );
    """

    @Volatile
    var customBackend: SqliteBackend? = null

    private val activeBackend: SqliteBackend by lazy {
        val custom = customBackend
        if (custom != null) {
            custom.initTable()
            custom
        } else {
            val backend = createDefaultBackend()
            backend.initTable()
            backend
        }
    }

    fun saveSession(record: AuthSessionRecord) {
        try {
            activeBackend.saveSession(record)
        } catch (e: Exception) {
            println("SQLite saveSession error: ${e.message}")
        }
        saveFallbackPrefs(record)
    }

    fun loadActiveSession(): AuthSessionRecord? {
        try {
            val rec = activeBackend.loadActiveSession()
            if (rec != null) {
                if (rec.isExpired) {
                    clearSession()
                    return null
                }
                return rec
            }
        } catch (e: Exception) {
            println("SQLite loadActiveSession error: ${e.message}")
        }
        return loadFallbackPrefs()
    }

    fun clearSession() {
        try {
            activeBackend.clearSessions()
        } catch (e: Exception) {
            println("SQLite clearSession error: ${e.message}")
        }
        clearFallbackPrefs()
    }

    private fun createDefaultBackend(): SqliteBackend {
        // 1. Try Android native android.database.sqlite.SQLiteDatabase via reflection if on Android
        try {
            val sqliteClass = Class.forName("android.database.sqlite.SQLiteDatabase")
            val dbFile = Gdx.files.local(DB_FILE_NAME).file()
            dbFile.parentFile?.mkdirs()
            return ReflectiveAndroidSqliteBackend(sqliteClass, dbFile)
        } catch (_: Throwable) {
        }

        // 2. Try JDBC SQLite (org.sqlite.JDBC) on Desktop / JVM
        try {
            Class.forName("org.sqlite.JDBC")
            val dbFile = Gdx.files.local(DB_FILE_NAME).file()
            dbFile.parentFile?.mkdirs()
            return JdbcSqliteBackend(dbFile)
        } catch (_: Throwable) {
        }

        // 3. Fallback file-backed SQLite-compatible store
        return PrefsBackedSqliteFallback()
    }

    private fun saveFallbackPrefs(record: AuthSessionRecord) {
        try {
            val prefs = Gdx.app.getPreferences("shootgame_sqlite_mirror")
            prefs.putString("username", record.username)
            prefs.putString("jwt_token", record.jwtToken)
            prefs.putString("session_token", record.sessionToken)
            prefs.putLong("created_at", record.createdAt)
            prefs.putLong("expires_at", record.expiresAt)
            prefs.flush()
        } catch (_: Exception) {
        }
    }

    private fun loadFallbackPrefs(): AuthSessionRecord? {
        return try {
            val prefs = Gdx.app.getPreferences("shootgame_sqlite_mirror")
            val username = prefs.getString("username", "")
            val jwt = prefs.getString("jwt_token", "")
            val session = prefs.getString("session_token", "")
            val created = prefs.getLong("created_at", 0L)
            val expires = prefs.getLong("expires_at", 0L)
            if (username.isNotBlank() && jwt.isNotBlank() && session.isNotBlank()) {
                val rec = AuthSessionRecord(username, jwt, session, created, expires)
                if (rec.isExpired) {
                    clearFallbackPrefs()
                    null
                } else {
                    rec
                }
            } else {
                null
            }
        } catch (_: Exception) {
            null
        }
    }

    private fun clearFallbackPrefs() {
        try {
            val prefs = Gdx.app.getPreferences("shootgame_sqlite_mirror")
            prefs.clear()
            prefs.flush()
        } catch (_: Exception) {
        }
    }
}

/**
 * Standard JDBC SQLite backend (`jdbc:sqlite:...`).
 */
private class JdbcSqliteBackend(private val dbFile: File) : SqliteBackend {
    private val jdbcUrl = "jdbc:sqlite:${dbFile.absolutePath}"

    override fun initTable() {
        DriverManager.getConnection(jdbcUrl).use { conn ->
            conn.createStatement().use { stmt ->
                stmt.execute(SqliteSessionStore.SQL_CREATE_TABLE)
            }
        }
    }

    override fun saveSession(record: AuthSessionRecord) {
        DriverManager.getConnection(jdbcUrl).use { conn ->
            conn.createStatement().use { stmt ->
                stmt.execute("DELETE FROM user_sessions;")
            }
            conn.prepareStatement(
                "INSERT OR REPLACE INTO user_sessions (username, jwt_token, session_token, created_at, expires_at) VALUES (?, ?, ?, ?, ?);"
            ).use { ps ->
                ps.setString(1, record.username)
                ps.setString(2, record.jwtToken)
                ps.setString(3, record.sessionToken)
                ps.setLong(4, record.createdAt)
                ps.setLong(5, record.expiresAt)
                ps.executeUpdate()
            }
        }
    }

    override fun loadActiveSession(): AuthSessionRecord? {
        DriverManager.getConnection(jdbcUrl).use { conn ->
            conn.createStatement().use { stmt ->
                stmt.executeQuery(
                    "SELECT username, jwt_token, session_token, created_at, expires_at FROM user_sessions ORDER BY created_at DESC LIMIT 1;"
                ).use { rs ->
                    if (rs.next()) {
                        return AuthSessionRecord(
                            username = rs.getString(1),
                            jwtToken = rs.getString(2),
                            sessionToken = rs.getString(3),
                            createdAt = rs.getLong(4),
                            expiresAt = rs.getLong(5)
                        )
                    }
                }
            }
        }
        return null
    }

    override fun clearSessions() {
        DriverManager.getConnection(jdbcUrl).use { conn ->
            conn.createStatement().use { stmt ->
                stmt.execute("DELETE FROM user_sessions;")
            }
        }
    }
}

/**
 * Android SQLiteDatabase reflection backend (used if customBackend was not explicitly injected).
 */
private class ReflectiveAndroidSqliteBackend(
    private val sqliteClass: Class<*>,
    private val dbFile: File
) : SqliteBackend {

    private fun openDb(): Any {
        val openMethod = sqliteClass.getMethod(
            "openOrCreateDatabase",
            File::class.java,
            Class.forName("android.database.sqlite.SQLiteDatabase\$CursorFactory")
        )
        return openMethod.invoke(null, dbFile, null)!!
    }

    private fun closeDb(db: Any) {
        try {
            sqliteClass.getMethod("close").invoke(db)
        } catch (_: Throwable) {
        }
    }

    override fun initTable() {
        val db = openDb()
        try {
            val execSql = sqliteClass.getMethod("execSQL", String::class.java)
            execSql.invoke(db, SqliteSessionStore.SQL_CREATE_TABLE)
        } finally {
            closeDb(db)
        }
    }

    override fun saveSession(record: AuthSessionRecord) {
        val db = openDb()
        try {
            val execSql = sqliteClass.getMethod("execSQL", String::class.java)
            execSql.invoke(db, "DELETE FROM user_sessions;")
            val execWithArgs = sqliteClass.getMethod(
                "execSQL",
                String::class.java,
                Array<Any>::class.java
            )
            execWithArgs.invoke(
                db,
                "INSERT OR REPLACE INTO user_sessions (username, jwt_token, session_token, created_at, expires_at) VALUES (?, ?, ?, ?, ?);",
                arrayOf<Any>(
                    record.username,
                    record.jwtToken,
                    record.sessionToken,
                    record.createdAt,
                    record.expiresAt
                )
            )
        } finally {
            closeDb(db)
        }
    }

    override fun loadActiveSession(): AuthSessionRecord? {
        val db = openDb()
        try {
            val rawQuery = sqliteClass.getMethod(
                "rawQuery",
                String::class.java,
                Array<String>::class.java
            )
            val cursor = rawQuery.invoke(
                db,
                "SELECT username, jwt_token, session_token, created_at, expires_at FROM user_sessions ORDER BY created_at DESC LIMIT 1;",
                null
            ) ?: return null
            val cursorClass = cursor.javaClass
            try {
                val moveToFirst = cursorClass.getMethod("moveToFirst").invoke(cursor) as Boolean
                if (!moveToFirst) return null
                val getString = cursorClass.getMethod("getString", Int::class.javaPrimitiveType)
                val getLong = cursorClass.getMethod("getLong", Int::class.javaPrimitiveType)
                return AuthSessionRecord(
                    username = getString.invoke(cursor, 0) as String,
                    jwtToken = getString.invoke(cursor, 1) as String,
                    sessionToken = getString.invoke(cursor, 2) as String,
                    createdAt = getLong.invoke(cursor, 3) as Long,
                    expiresAt = getLong.invoke(cursor, 4) as Long
                )
            } finally {
                try {
                    cursorClass.getMethod("close").invoke(cursor)
                } catch (_: Throwable) {
                }
            }
        } finally {
            closeDb(db)
        }
    }

    override fun clearSessions() {
        val db = openDb()
        try {
            val execSql = sqliteClass.getMethod("execSQL", String::class.java)
            execSql.invoke(db, "DELETE FROM user_sessions;")
        } finally {
            closeDb(db)
        }
    }
}

private class PrefsBackedSqliteFallback : SqliteBackend {
    override fun initTable() {}
    override fun saveSession(record: AuthSessionRecord) {}
    override fun loadActiveSession(): AuthSessionRecord? = null
    override fun clearSessions() {}
}
