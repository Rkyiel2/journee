package fr.journee.app

import android.annotation.SuppressLint
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.webkit.JavascriptInterface
import android.webkit.WebView
import android.webkit.WebViewClient
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger

/**
 * Runs pawnote (bundled in assets/engine.js) inside an invisible WebView.
 * HTTP goes through [Http] so there is no CORS problem.
 */
class Engine(context: Context) {
    private val ctx = context.applicationContext
    private val main = Handler(Looper.getMainLooper())
    private val io = Executors.newFixedThreadPool(4)
    private val calls = ConcurrentHashMap<String, CompletableDeferred<String>>()
    private val ids = AtomicInteger(0)
    private val ready = CompletableDeferred<Unit>()
    @Volatile private var web: WebView? = null
    @Volatile private var destroyed = false

    private inner class Bridge {
        @JavascriptInterface
        fun ready() {
            ready.complete(Unit)
        }

        @JavascriptInterface
        fun request(id: String, json: String) {
            if (destroyed) return
            runCatching {
                io.execute {
                    val response = Http.perform(json)
                    eval("PW.http(${JSONObject.quote(id)}, ${JSONObject.quote(response)})")
                }
            }
        }

        @JavascriptInterface
        fun result(id: String, json: String) {
            calls.remove(id)?.complete(json)
        }

        @JavascriptInterface
        fun saveRefresh(json: String) {
            runCatching { Store.saveRefresh(ctx, json) }
                .onFailure { Log.w(TAG, "saveRefresh failed", it) }
        }

        @JavascriptInterface
        fun log(message: String) {
            Log.d(TAG, message)
        }
    }

    private fun eval(js: String) {
        main.post {
            if (!destroyed) web?.evaluateJavascript(js, null)
        }
    }

    @SuppressLint("SetJavaScriptEnabled", "JavascriptInterface")
    private suspend fun start() {
        withContext(Dispatchers.Main) {
            if (web == null && !destroyed) {
                val view = WebView(ctx)
                view.settings.javaScriptEnabled = true
                view.settings.domStorageEnabled = true
                view.addJavascriptInterface(Bridge(), "Bridge")
                view.webViewClient = object : WebViewClient() {}
                view.loadUrl("file:///android_asset/engine.html")
                web = view
            }
        }
        withTimeout(20_000) { ready.await() }
    }

    /** Calls PW.<method>(args) in JS and returns its JSON result. */
    suspend fun call(method: String, args: JSONObject, timeoutMs: Long = 150_000): JSONObject {
        start()
        val id = ids.incrementAndGet().toString()
        val deferred = CompletableDeferred<String>()
        calls[id] = deferred
        eval("PW.call(${JSONObject.quote(id)}, ${JSONObject.quote(method)}, ${JSONObject.quote(args.toString())})")
        return try {
            JSONObject(withTimeout(timeoutMs) { deferred.await() })
        } finally {
            calls.remove(id)
        }
    }

    fun destroy() {
        destroyed = true
        io.shutdownNow()
        main.post {
            web?.destroy()
            web = null
        }
    }

    companion object {
        private const val TAG = "JourneeEngine"
    }
}
