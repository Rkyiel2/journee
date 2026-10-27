package fr.journee.app

import android.annotation.SuppressLint
import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.InputType
import android.view.View
import android.webkit.CookieManager
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.webkit.ScriptHandler
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import fr.journee.app.widget.DayWidget
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.util.Locale

/**
 * Connection screen.
 *
 * Main path (works with MonLycée / any ENT): the user signs in inside this WebView, opens Pronote,
 * and we ask Pronote for a *mobile app token* the same way the official app does
 * (infoMobileApp.json → cookies → mobile.eleve.html → hookAccesDepuisAppli → window.loginState).
 * The ENT password never touches the app; only the Pronote token is stored.
 *
 * Fallback: the QR code from Pronote web (Mes données > Mes informations > code QR),
 * read straight from the screen or from an image.
 */
class LoginActivity : Activity() {

    private enum class Phase { PORTAL, INFO, MOBILE, LOGGING_IN, DONE }

    private val scope: CoroutineScope = MainScope()
    private val handler = Handler(Looper.getMainLooper())
    private lateinit var web: WebView
    private lateinit var status: TextView
    private lateinit var progress: ProgressBar
    private var engine: Engine? = null
    private var phase = Phase.PORTAL
    private var base: String? = null
    private var deviceUUID = ""
    private var hookHandler: ScriptHandler? = null
    private var mobileStartedAt = 0L
    private var polling = false

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_login)
        deviceUUID = Store.deviceUUID(this)
        web = findViewById(R.id.web)
        status = findViewById(R.id.status)
        progress = findViewById(R.id.progress)
        findViewById<Button>(R.id.btn_close).setOnClickListener { finish() }
        findViewById<Button>(R.id.btn_qr_screen).setOnClickListener { readQrFromScreen() }
        findViewById<Button>(R.id.btn_qr_image).setOnClickListener { pickQrImage() }
        findViewById<Button>(R.id.btn_pronote_web).setOnClickListener { openPronoteWeb() }

        val cookies = CookieManager.getInstance()
        cookies.setAcceptCookie(true)
        cookies.setAcceptThirdPartyCookies(web, true)

        web.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            javaScriptCanOpenWindowsAutomatically = true
            setSupportMultipleWindows(false)
            loadWithOverviewMode = true
            useWideViewPort = true
            builtInZoomControls = true
            displayZoomControls = false
            // Some SSO pages refuse embedded browsers: drop the "; wv" marker.
            userAgentString = userAgentString.replace("; wv", "")
        }
        web.webChromeClient = object : WebChromeClient() {
            override fun onProgressChanged(view: WebView, newProgress: Int) {
                progress.progress = newProgress
                progress.visibility = if (newProgress in 1..99) View.VISIBLE else View.INVISIBLE
            }
        }
        web.webViewClient = client

        val startUrl = intent.getStringExtra(EXTRA_URL)?.let { normalizeBase(it) }
        when {
            intent.getStringExtra(EXTRA_MODE) == MODE_QR_IMAGE -> {
                setStatus(getString(R.string.login_qr_image_hint))
                phase = Phase.DONE
                web.loadUrl(startUrl?.let { it + "eleve.html" } ?: PORTAL_URL)
                pickQrImage()
            }
            startUrl != null -> startMobileFlow(startUrl)
            else -> {
                setStatus(getString(R.string.login_step_portal))
                web.loadUrl(PORTAL_URL)
            }
        }
    }

    override fun onDestroy() {
        polling = false
        handler.removeCallbacksAndMessages(null)
        scope.cancel()
        engine?.destroy()
        web.destroy()
        super.onDestroy()
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        if (web.canGoBack() && phase == Phase.PORTAL) web.goBack() else super.onBackPressed()
    }

    private fun setStatus(text: String) {
        status.text = text
    }

    // ---------------------------------------------------------------- WebView flow

    private val client = object : WebViewClient() {
        override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
            val url = request.url.toString()
            if (phase == Phase.PORTAL) {
                pronoteBase(url)?.let {
                    startMobileFlow(it)
                    return true
                }
            }
            return false
        }

        override fun onPageStarted(view: WebView, url: String, favicon: Bitmap?) {
            if (phase == Phase.PORTAL) {
                pronoteBase(url)?.let {
                    view.stopLoading()
                    startMobileFlow(it)
                }
            } else if (phase == Phase.MOBILE && isOnPronote(url)) {
                view.evaluateJavascript(hookScript(), null)
            }
        }

        override fun onPageFinished(view: WebView, url: String) {
            when (phase) {
                Phase.INFO -> if (url.contains("infoMobileApp.json")) readInfoMobile()
                Phase.MOBILE -> if (isOnPronote(url)) {
                    view.evaluateJavascript(hookScript(), null)
                    startPolling()
                }
                else -> Unit
            }
        }
    }

    /** Returns "https://host/pronote/" if [url] is a Pronote page. */
    private fun pronoteBase(url: String): String? {
        val uri = runCatching { Uri.parse(url) }.getOrNull() ?: return null
        val scheme = uri.scheme ?: return null
        if (scheme != "https" && scheme != "http") return null
        val host = uri.host ?: return null
        val path = uri.path ?: return null
        val idx = path.lowercase(Locale.ROOT).indexOf("/pronote")
        if (idx < 0) return null
        // Ignore the "service=" URLs of the ENT's own CAS pages: only real Pronote hosts count.
        if (host.contains("monlycee") || host.contains("iledefrance")) return null
        val end = path.indexOf('/', idx + 1).let { if (it < 0) path.length else it }
        val prefix = path.substring(0, end)
        val port = if (uri.port > 0) ":${uri.port}" else ""
        return "$scheme://$host$port$prefix/"
    }

    private fun isOnPronote(url: String): Boolean {
        val b = base ?: return false
        return url.startsWith(b) || (pronoteBase(url) == b)
    }

    private fun normalizeBase(input: String): String? {
        var s = input.trim()
        if (s.isEmpty()) return null
        if (!s.startsWith("http")) s = "https://$s"
        pronoteBase(s)?.let { return it }
        return if (s.endsWith("/")) s else "$s/"
    }

    private fun startMobileFlow(pronote: String) {
        base = pronote
        Store.setUrlHint(this, pronote)
        phase = Phase.INFO
        setStatus(getString(R.string.login_step_token))
        web.loadUrl(pronote + "infoMobileApp.json?id=0D264427-EEFC-4810-A9E9-346942A862A4")
    }

    private fun readInfoMobile() {
        web.evaluateJavascript("(function(){return document.body ? document.body.innerText : ''})()") { raw ->
            val text = runCatching { org.json.JSONTokener(raw).nextValue() as? String }.getOrNull() ?: ""
            val jeton = runCatching { JSONObject(text).optJSONObject("CAS")?.optString("jetonCAS") }.getOrNull()
            val b = base ?: return@evaluateJavascript
            val cookies = CookieManager.getInstance()
            if (!jeton.isNullOrBlank() && jeton != "null") {
                cookies.setCookie(b, "validationAppliMobile=$jeton; path=/")
                cookies.setCookie(b, "uuidAppliMobile=$deviceUUID; path=/")
                cookies.setCookie(b, "ielang=1036; path=/")
            }
            cookies.flush()
            phase = Phase.MOBILE
            mobileStartedAt = System.currentTimeMillis()
            installDocumentStartHook(b)
            web.loadUrl(b + "mobile.eleve.html?fd=1")
        }
    }

    private fun hookScript(): String =
        "(function(){try{window.hookAccesDepuisAppli=function(){this.passerEnModeValidationAppliMobile('', '$deviceUUID');};}catch(e){}})();"

    private fun installDocumentStartHook(b: String) {
        if (!WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)) return
        val uri = Uri.parse(b)
        val origin = uri.scheme + "://" + uri.host + (if (uri.port > 0) ":${uri.port}" else "")
        runCatching {
            hookHandler?.remove()
            hookHandler = WebViewCompat.addDocumentStartJavaScript(web, hookScript(), setOf(origin))
        }
    }

    private fun startPolling() {
        if (polling) return
        polling = true
        handler.post(pollTask)
    }

    private val pollTask = object : Runnable {
        override fun run() {
            if (!polling || phase != Phase.MOBILE) return
            val js = hookScript() + "(function(){var s=window.loginState;return s?JSON.stringify(s):'';})()"
            web.evaluateJavascript(js) { raw ->
                val text = runCatching { org.json.JSONTokener(raw).nextValue() as? String }.getOrNull().orEmpty()
                val state = if (text.isNotBlank()) runCatching { JSONObject(text) }.getOrNull() else null
                val login = state?.optString("login").orEmpty()
                val mdp = state?.optString("mdp").orEmpty()
                if (login.isNotBlank() && mdp.isNotBlank() && phase == Phase.MOBILE) {
                    polling = false
                    onMobileToken(login, mdp)
                } else {
                    if (System.currentTimeMillis() - mobileStartedAt > 45_000) {
                        setStatus(getString(R.string.login_slow_hint))
                    }
                    handler.postDelayed(this, 1000)
                }
            }
        }
    }

    private fun onMobileToken(login: String, token: String) {
        val b = base ?: return
        phase = Phase.LOGGING_IN
        hookHandler?.let { runCatching { it.remove() } }
        setStatus(getString(R.string.login_step_data))
        val args = JSONObject()
            .put("url", b)
            .put("username", login)
            .put("token", token)
            .put("kind", 6)
            .put("deviceUUID", deviceUUID)
        runEngine("loginToken", args)
    }

    private fun openPronoteWeb() {
        val b = base ?: Store.urlHint(this)
        if (b == null) {
            Toast.makeText(this, R.string.login_need_pronote_first, Toast.LENGTH_LONG).show()
            return
        }
        polling = false
        // Plain browsing from here on: no automatic interception.
        phase = Phase.DONE
        setStatus(getString(R.string.login_qr_screen_hint))
        web.loadUrl(b + "eleve.html?fd=1")
    }

    // ---------------------------------------------------------------- engine + results

    private fun engine(): Engine = engine ?: Engine(this).also { engine = it }

    private fun runEngine(method: String, args: JSONObject) {
        progress.visibility = View.VISIBLE
        progress.isIndeterminate = true
        scope.launch {
            val res = try {
                engine().call(method, args)
            } catch (e: Exception) {
                JSONObject().put("ok", false).put(
                    "error",
                    JSONObject().put("type", "network").put("message", "Pas de réponse (${e.javaClass.simpleName})"),
                )
            }
            progress.isIndeterminate = false
            progress.visibility = View.INVISIBLE
            handleResult(res)
        }
    }

    private fun handleResult(res: JSONObject) {
        if (res.optBoolean("ok")) {
            Sync.applyResult(this, res)
            phase = Phase.DONE
            DayWidget.updateAll(this)
            Toast.makeText(this, R.string.login_ok, Toast.LENGTH_SHORT).show()
            setResult(RESULT_OK)
            finish()
            return
        }
        res.optJSONObject("security")?.let { askSecurity(it); return }
        val err = res.optJSONObject("error") ?: JSONObject()
        val msg = err.optString("message").ifBlank { getString(R.string.login_failed) }
        AlertDialog.Builder(this)
            .setTitle(R.string.login_failed)
            .setMessage(msg + "\n\n" + getString(R.string.login_failed_hint))
            .setPositiveButton(R.string.retry) { _, _ ->
                val b = base
                if (b != null) startMobileFlow(b) else {
                    phase = Phase.PORTAL
                    web.loadUrl(PORTAL_URL)
                }
            }
            .setNegativeButton(R.string.use_qr) { _, _ -> openPronoteWeb() }
            .show()
    }

    private fun askSecurity(sec: JSONObject) {
        if (sec.optBoolean("customPassword")) {
            AlertDialog.Builder(this)
                .setTitle(R.string.security_title)
                .setMessage(R.string.security_password)
                .setPositiveButton(android.R.string.ok, null)
                .show()
            return
        }
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            val pad = (20 * resources.displayMetrics.density).toInt()
            setPadding(pad, pad / 2, pad, 0)
        }
        val pin = EditText(this).apply {
            hint = getString(if (sec.optBoolean("newPin")) R.string.security_new_pin else R.string.security_pin)
            inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_VARIATION_PASSWORD
        }
        val device = EditText(this).apply {
            hint = getString(R.string.security_device)
            setText(android.os.Build.MODEL ?: "Android")
        }
        if (sec.optBoolean("needPin")) box.addView(pin)
        box.addView(device)
        AlertDialog.Builder(this)
            .setTitle(R.string.security_title)
            .setMessage(R.string.security_message)
            .setView(box)
            .setCancelable(false)
            .setPositiveButton(R.string.validate) { _, _ ->
                val args = JSONObject()
                    .put("pin", pin.text.toString().trim())
                    .put("deviceName", device.text.toString().trim())
                setStatus(getString(R.string.login_step_data))
                runEngine("security", args)
            }
            .setNegativeButton(android.R.string.cancel) { _, _ -> finish() }
            .show()
    }

    // ---------------------------------------------------------------- QR code fallback

    private fun readQrFromScreen() {
        if (web.width == 0 || web.height == 0) return
        val bmp = Bitmap.createBitmap(web.width, web.height, Bitmap.Config.ARGB_8888)
        web.draw(Canvas(bmp))
        scope.launch {
            val text = withContext(Dispatchers.Default) { Qr.decode(bmp) }
            if (text == null) {
                Toast.makeText(this@LoginActivity, R.string.qr_not_found, Toast.LENGTH_LONG).show()
            } else {
                askQrPin(text)
            }
        }
    }

    @Suppress("DEPRECATION")
    private fun pickQrImage() {
        val intent = Intent(Intent.ACTION_GET_CONTENT).setType("image/*").addCategory(Intent.CATEGORY_OPENABLE)
        startActivityForResult(Intent.createChooser(intent, getString(R.string.qr_pick)), REQ_IMAGE)
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != REQ_IMAGE || resultCode != RESULT_OK) return
        val uri = data?.data ?: return
        scope.launch {
            val text = withContext(Dispatchers.IO) {
                runCatching { loadBitmap(uri)?.let { Qr.decode(it) } }.getOrNull()
            }
            if (text == null) {
                Toast.makeText(this@LoginActivity, R.string.qr_not_found, Toast.LENGTH_LONG).show()
            } else {
                askQrPin(text)
            }
        }
    }

    private fun loadBitmap(uri: Uri): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
        var sample = 1
        while (bounds.outWidth / sample > 2400 || bounds.outHeight / sample > 2400) sample *= 2
        val opts = BitmapFactory.Options().apply { inSampleSize = sample }
        return contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, opts) }
    }

    private fun askQrPin(qrText: String) {
        val looksPronote = runCatching { JSONObject(qrText).has("jeton") }.getOrDefault(false)
        if (!looksPronote) {
            Toast.makeText(this, R.string.qr_not_pronote, Toast.LENGTH_LONG).show()
            return
        }
        val pin = EditText(this).apply {
            hint = "0000"
            inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_VARIATION_PASSWORD
        }
        val box = LinearLayout(this).apply {
            val pad = (20 * resources.displayMetrics.density).toInt()
            setPadding(pad, pad / 2, pad, 0)
            addView(pin, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
        }
        AlertDialog.Builder(this)
            .setTitle(R.string.qr_pin_title)
            .setMessage(R.string.qr_pin_message)
            .setView(box)
            .setPositiveButton(R.string.validate) { _, _ ->
                polling = false
                phase = Phase.LOGGING_IN
                setStatus(getString(R.string.login_step_data))
                runEngine(
                    "loginQr",
                    JSONObject().put("qr", qrText).put("pin", pin.text.toString().trim()).put("deviceUUID", deviceUUID),
                )
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    companion object {
        const val EXTRA_URL = "url"
        const val EXTRA_MODE = "mode"
        const val MODE_QR_IMAGE = "qr_image"
        const val PORTAL_URL = "https://monlycee.net/"
        private const val REQ_IMAGE = 42
    }
}
