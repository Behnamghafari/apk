package ir.behnamghafari.apixnative.data.network

import android.content.Context
import ir.behnamghafari.apixnative.data.security.SecretVault
import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.HttpUrl
import org.json.JSONArray
import org.json.JSONObject

/** Persistent, encrypted OkHttp cookie jar shared by all ApiX requests. */
class PersistentCookieJar(context: Context) : CookieJar {
    private val preferences = context.applicationContext.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
    private val vault = SecretVault()
    private val lock = Any()

    override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) {
        synchronized(lock) {
            val now = System.currentTimeMillis()
            val current = readCookies().toMutableList()
            cookies.forEach { incoming ->
                current.removeAll {
                    it.name == incoming.name && it.domain == incoming.domain && it.path == incoming.path
                }
                if (incoming.expiresAt > now) current += incoming
            }
            writeCookies(current)
        }
    }

    override fun loadForRequest(url: HttpUrl): List<Cookie> = synchronized(lock) {
        val now = System.currentTimeMillis()
        val all = readCookies()
        val valid = all.filter { it.expiresAt > now }
        if (valid.size != all.size) writeCookies(valid)
        valid.filter { it.matches(url) }
    }

    private fun readCookies(): List<Cookie> {
        val encrypted = preferences.getString(KEY_COOKIES, null) ?: return emptyList()
        if (encrypted.isBlank()) return emptyList()
        val source = runCatching { vault.reveal(encrypted) }.getOrNull() ?: return emptyList()
        return runCatching {
            val array = JSONArray(source)
            buildList {
                for (i in 0 until array.length()) {
                    val item = array.optJSONObject(i) ?: continue
                    val domain = item.optString("domain")
                    val builder = Cookie.Builder()
                        .name(item.optString("name"))
                        .value(item.optString("value"))
                        .path(item.optString("path", "/"))
                    if (item.optBoolean("hostOnly")) builder.hostOnlyDomain(domain) else builder.domain(domain)
                    if (item.optBoolean("secure")) builder.secure()
                    if (item.optBoolean("httpOnly")) builder.httpOnly()
                    if (item.has("expiresAt")) builder.expiresAt(item.optLong("expiresAt"))
                    runCatching { add(builder.build()) }
                }
            }
        }.getOrDefault(emptyList())
    }

    private fun writeCookies(cookies: List<Cookie>) {
        val array = JSONArray()
        cookies.forEach { cookie ->
            array.put(
                JSONObject()
                    .put("name", cookie.name)
                    .put("value", cookie.value)
                    .put("domain", cookie.domain)
                    .put("path", cookie.path)
                    .put("expiresAt", cookie.expiresAt)
                    .put("hostOnly", cookie.hostOnly)
                    .put("secure", cookie.secure)
                    .put("httpOnly", cookie.httpOnly)
            )
        }
        preferences.edit().putString(KEY_COOKIES, vault.protect(array.toString())).apply()
    }

    companion object {
        private const val PREFERENCES = "apix_cookie_jar"
        private const val KEY_COOKIES = "encrypted_cookies_v1"
    }
}
