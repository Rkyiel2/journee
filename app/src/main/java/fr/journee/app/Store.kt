package fr.journee.app

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONObject
import java.util.UUID

/** Everything the app persists, in private app storage. */
object Store {
    private const val FILE = "journee"

    const val K_URL = "url"
    const val K_USERNAME = "username"
    const val K_TOKEN = "token"
    const val K_NAV = "navigator"
    const val K_KIND = "kind"
    const val K_DEVICE = "device_uuid"
    const val K_SNAPSHOT = "snapshot"
    const val K_LAST_SYNC = "last_sync"
    const val K_ERROR = "last_error"
    const val K_NEEDS_LOGIN = "needs_login"
    const val K_SYNCING = "syncing"
    const val K_URL_HINT = "url_hint"

    fun prefs(ctx: Context): SharedPreferences =
        ctx.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    /** Stable per-install identifier that Pronote ties the mobile token to. */
    fun deviceUUID(ctx: Context): String {
        val p = prefs(ctx)
        p.getString(K_DEVICE, null)?.let { return it }
        val id = UUID.randomUUID().toString().uppercase()
        p.edit().putString(K_DEVICE, id).commit()
        return id
    }

    data class Creds(
        val url: String,
        val username: String,
        val token: String,
        val navigator: String?,
        val kind: Int,
    )

    fun creds(ctx: Context): Creds? {
        val p = prefs(ctx)
        val url = p.getString(K_URL, null) ?: return null
        val user = p.getString(K_USERNAME, null) ?: return null
        val token = p.getString(K_TOKEN, null) ?: return null
        return Creds(url, user, token, p.getString(K_NAV, null), p.getInt(K_KIND, 6))
    }

    fun hasAccount(ctx: Context) = creds(ctx) != null

    /** Called from JS right after each successful login: the old token is now dead. */
    fun saveRefresh(ctx: Context, json: String) {
        val o = JSONObject(json)
        val e = prefs(ctx).edit()
        o.optString("url").takeIf { it.isNotBlank() }?.let { e.putString(K_URL, it) }
        o.optString("username").takeIf { it.isNotBlank() }?.let { e.putString(K_USERNAME, it) }
        o.optString("token").takeIf { it.isNotBlank() }?.let { e.putString(K_TOKEN, it) }
        val nav = o.optString("navigatorIdentifier")
        if (nav.isNotBlank() && nav != "null") e.putString(K_NAV, nav)
        if (o.has("kind")) e.putInt(K_KIND, o.optInt("kind", 6))
        e.putBoolean(K_NEEDS_LOGIN, false)
        e.commit()
    }

    fun saveSnapshot(ctx: Context, snapshot: JSONObject) {
        prefs(ctx).edit()
            .putString(K_SNAPSHOT, snapshot.toString())
            .putLong(K_LAST_SYNC, System.currentTimeMillis())
            .remove(K_ERROR)
            .putBoolean(K_NEEDS_LOGIN, false)
            .commit()
    }

    fun snapshot(ctx: Context): Snapshot? =
        prefs(ctx).getString(K_SNAPSHOT, null)?.let { runCatching { Snapshot.parse(JSONObject(it)) }.getOrNull() }

    fun lastSync(ctx: Context): Long = prefs(ctx).getLong(K_LAST_SYNC, 0L)

    fun error(ctx: Context): String? = prefs(ctx).getString(K_ERROR, null)

    fun needsLogin(ctx: Context): Boolean = prefs(ctx).getBoolean(K_NEEDS_LOGIN, false)

    fun setError(ctx: Context, message: String?, needsLogin: Boolean = false) {
        prefs(ctx).edit()
            .putString(K_ERROR, message)
            .putBoolean(K_NEEDS_LOGIN, needsLogin)
            .commit()
    }

    fun setSyncing(ctx: Context, syncing: Boolean) {
        prefs(ctx).edit().putBoolean(K_SYNCING, syncing).commit()
    }

    fun syncing(ctx: Context) = prefs(ctx).getBoolean(K_SYNCING, false)

    fun urlHint(ctx: Context): String? = prefs(ctx).getString(K_URL_HINT, null) ?: prefs(ctx).getString(K_URL, null)

    fun setUrlHint(ctx: Context, url: String?) {
        prefs(ctx).edit().putString(K_URL_HINT, url).apply()
    }

    fun logout(ctx: Context) {
        val device = deviceUUID(ctx)
        val hint = urlHint(ctx)
        prefs(ctx).edit().clear().putString(K_DEVICE, device).putString(K_URL_HINT, hint).commit()
    }
}
