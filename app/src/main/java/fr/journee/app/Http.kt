package fr.journee.app

import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL

/**
 * Executes the HTTP requests pawnote asks for (JSON in, JSON out).
 * Plain HttpURLConnection: no cookie jar, no CORS, so pawnote sees exactly what Pronote sends.
 */
object Http {
    private val SKIP = setOf("content-length", "host", "connection", "accept-encoding")

    fun perform(requestJson: String): String {
        return try {
            execute(JSONObject(requestJson))
        } catch (e: Exception) {
            JSONObject().put("error", (e.javaClass.simpleName + ": " + (e.message ?: "")).take(300)).toString()
        }
    }

    private fun execute(req: JSONObject): String {
        val follow = req.optString("redirect", "follow") != "manual"
        var url = req.getString("url")
        var method = req.optString("method", "GET").uppercase()
        var body: String? = if (req.isNull("body")) null else req.optString("body")
        val headers = req.optJSONObject("headers") ?: JSONObject()

        var hops = 0
        while (hops <= 10) {
            val conn = URL(url).openConnection() as HttpURLConnection
            conn.instanceFollowRedirects = false
            conn.connectTimeout = 20_000
            conn.readTimeout = 45_000
            conn.requestMethod = method
            conn.useCaches = false
            val keys = headers.keys()
            while (keys.hasNext()) {
                val k = keys.next()
                if (k.lowercase() !in SKIP) conn.setRequestProperty(k, headers.optString(k))
            }
            try {
                val payload = body
                if (payload != null && method != "GET") {
                    val bytes = payload.toByteArray(Charsets.UTF_8)
                    conn.doOutput = true
                    conn.setFixedLengthStreamingMode(bytes.size)
                    conn.outputStream.use { it.write(bytes) }
                }
                val code = conn.responseCode
                val location = conn.getHeaderField("Location")
                if (follow && code in 300..399 && location != null) {
                    hops++
                    url = URL(URL(url), location).toString()
                    if (code == 301 || code == 302 || code == 303) {
                        method = "GET"
                        body = null
                    }
                    continue
                }
                val stream: InputStream? = if (code >= 400) conn.errorStream else conn.inputStream
                val text = readAll(stream)
                val outHeaders = JSONObject()
                for ((k, values) in conn.headerFields) {
                    if (k == null) continue
                    val key = k.lowercase()
                    val arr = outHeaders.optJSONArray(key) ?: JSONArray().also { outHeaders.put(key, it) }
                    for (v in values) arr.put(v)
                }
                return JSONObject()
                    .put("status", code)
                    .put("body", text)
                    .put("headers", outHeaders)
                    .toString()
            } finally {
                conn.disconnect()
            }
        }
        return JSONObject().put("error", "Too many redirects").toString()
    }

    private fun readAll(stream: InputStream?): String {
        if (stream == null) return ""
        stream.use { input ->
            val out = ByteArrayOutputStream()
            val buf = ByteArray(16 * 1024)
            while (true) {
                val n = input.read(buf)
                if (n < 0) break
                out.write(buf, 0, n)
            }
            return String(out.toByteArray(), Charsets.UTF_8)
        }
    }
}
