private fun encode(input: String): String {
        return java.util.Base64.getEncoder().encodeToString(input.toByteArray())
    }

    private fun decode(input: String): String {
        return String(java.util.Base64.getDecoder().decode(input))
    }

    private fun saveFallbackPrefs(record: AuthSessionRecord) {
        try {
            val prefs = Gdx.app.getPreferences("shootgame_sqlite_mirror")
            prefs.putString("username", encode(record.username))
            prefs.putString("jwt_token", encode(record.jwtToken))
            prefs.putString("session_token", encode(record.sessionToken))
            prefs.putLong("created_at", record.createdAt)
            prefs.putLong("expires_at", record.expiresAt)
            prefs.flush()
        } catch (_: Exception) {
        }
    }

    private fun loadFallbackPrefs(): AuthSessionRecord? {
        return try {
            val prefs = Gdx.app.getPreferences("shootgame_sqlite_mirror")
            val encUsername = prefs.getString("username", "")
            val encJwt = prefs.getString("jwt_token", "")
            val encSession = prefs.getString("session_token", "")
            val created = prefs.getLong("created_at", 0L)
            val expires = prefs.getLong("expires_at", 0L)

            if (encUsername.isNotBlank() && encJwt.isNotBlank() && encSession.isNotBlank()) {
                val username = decode(encUsername)
                val jwt = decode(encJwt)
                val session = decode(encSession)

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
