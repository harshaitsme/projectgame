package io.github.shootgame.android

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import io.github.shootgame.auth.AuthSessionRecord
import io.github.shootgame.auth.SqliteBackend
import io.github.shootgame.auth.SqliteSessionStore

class AndroidSqliteBackend(context: Context) :
    SQLiteOpenHelper(context, DATABASE_NAME, null, DATABASE_VERSION),
    SqliteBackend {

    companion object {
        private const val DATABASE_NAME = "shootgame_session.db"
        private const val DATABASE_VERSION = 1
        private const val TABLE_NAME = "user_sessions"
    }

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(SqliteSessionStore.SQL_CREATE_TABLE)
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        db.execSQL("DROP TABLE IF EXISTS $TABLE_NAME;")
        onCreate(db)
    }

    override fun initTable() {
        // No-op: onCreate handles this automatically
    }

    override fun saveSession(record: AuthSessionRecord) {
        val values = ContentValues().apply {
            put("username", record.username)
            put("jwt_token", record.jwtToken)
            put("session_token", record.sessionToken)
            put("created_at", record.createdAt)
            put("expires_at", record.expiresAt)
        }
        
        // insertWithOnConflict is already atomic, so explicit transaction is redundant
        writableDatabase.insertWithOnConflict(TABLE_NAME, null, values, SQLiteDatabase.CONFLICT_REPLACE)
    }

    override fun loadActiveSession(): AuthSessionRecord? {
        val cursor = readableDatabase.query(
            TABLE_NAME,
            arrayOf("username", "jwt_token", "session_token", "created_at", "expires_at"),
            null, null, null, null,
            "created_at DESC",
            "1"
        )
        
        return cursor.use { c ->
            if (c.moveToFirst()) {
                AuthSessionRecord(
                    username = c.getString(0),
                    jwtToken = c.getString(1),
                    sessionToken = c.getString(2),
                    createdAt = c.getLong(3),
                    expiresAt = c.getLong(4)
                )
            } else {
                null
            }
        }
    }

    override fun clearSessions() {
        writableDatabase.delete(TABLE_NAME, null, null)
    }
}
