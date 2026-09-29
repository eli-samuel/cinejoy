package com.cinejoytv.app

import android.app.Activity
import android.app.AlertDialog
import android.content.SharedPreferences
import android.graphics.Bitmap
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.os.Message
import android.os.SystemClock
import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.webkit.CookieManager
import android.webkit.PermissionRequest
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import android.widget.Toast
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import kotlin.concurrent.thread
import kotlin.math.min
import kotlin.math.sign

class MainActivity : Activity() {

    private lateinit var root: FrameLayout
    private lateinit var webView: WebView
    private lateinit var cursor: CursorView
    private lateinit var prefs: SharedPreferences
    private lateinit var blocker: AdBlocker
    private lateinit var injectJs: String
    private var documentStartScript = false

    private var customView: View? = null
    private var customCallback: WebChromeClient.CustomViewCallback? = null

    private var cursorEnabled = true
    private var touchDownTime = 0L
    private var lastBackPress = 0L
    private var lastToast = 0L

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        prefs = getSharedPreferences("cinejoy", MODE_PRIVATE)
        cursorEnabled = prefs.getBoolean("cursor", true)
        blocker = AdBlocker(this)
        blocker.enabled = prefs.getBoolean("adblock", true)
        blocker.init()
        injectJs = assets.open("inject.js").bufferedReader().use { it.readText() }

        root = FrameLayout(this).apply { setBackgroundColor(Color.BLACK) }
        webView = WebView(this)
        cursor = CursorView(this)
        root.addView(webView, MATCH)
        root.addView(cursor, MATCH)
        setContentView(root)
        updateCursorVisibility()

        setupWebView()
        if (savedInstanceState == null || webView.restoreState(savedInstanceState) == null) {
            webView.loadUrl(startUrl())
        }
        webView.requestFocus()
    }

    private fun setupWebView() {
        with(webView.settings) {
            javaScriptEnabled = true
            domStorageEnabled = true
            @Suppress("DEPRECATION")
            databaseEnabled = true
            mediaPlaybackRequiresUserGesture = false
            loadWithOverviewMode = true
            useWideViewPort = true
            setSupportMultipleWindows(true)
            javaScriptCanOpenWindowsAutomatically = false
            mixedContentMode = WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE
            builtInZoomControls = false
            displayZoomControls = false
            cacheMode = WebSettings.LOAD_DEFAULT
            userAgentString = userAgent()
        }
        CookieManager.getInstance().apply {
            setAcceptCookie(true)
            setAcceptThirdPartyCookies(webView, true)
        }
        webView.setBackgroundColor(Color.BLACK)
        webView.isFocusable = true
        webView.isFocusableInTouchMode = true

        if (WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)) {
            WebViewCompat.addDocumentStartJavaScript(webView, injectJs, setOf("*"))
            documentStartScript = true
        }

        webView.webViewClient = object : WebViewClient() {
            override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? =
                if (!request.isForMainFrame && blocker.shouldBlock(request.url)) blocker.emptyResponse() else null

            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean =
                request.isForMainFrame && blockNavigation(request.url)

            @Deprecated("Used on API < 24")
            override fun shouldOverrideUrlLoading(view: WebView, url: String): Boolean =
                blockNavigation(Uri.parse(url))

            override fun onPageStarted(view: WebView, url: String?, favicon: Bitmap?) {
                if (!documentStartScript) view.evaluateJavascript(injectJs, null)
            }

            override fun onPageFinished(view: WebView, url: String?) {
                if (!documentStartScript) view.evaluateJavascript(injectJs, null)
                CookieManager.getInstance().flush()
                saveLastUrl()
            }
        }

        webView.webChromeClient = object : WebChromeClient() {
            override fun onShowCustomView(view: View, callback: CustomViewCallback) {
                if (customView != null) { callback.onCustomViewHidden(); return }
                customView = view
                customCallback = callback
                view.setBackgroundColor(Color.BLACK)
                root.addView(view, MATCH)
                cursor.visibility = View.GONE
                enterImmersive()
            }

            override fun onHideCustomView() = hideCustomView()

            // Popups: only user-initiated windows pointing at CineJoy itself are allowed,
            // and they open in the main WebView instead of a new window.
            override fun onCreateWindow(view: WebView, isDialog: Boolean, isUserGesture: Boolean, resultMsg: Message): Boolean {
                if (!isUserGesture) { notifyBlocked("popup"); return false }
                val probe = WebView(this@MainActivity)
                probe.webViewClient = object : WebViewClient() {
                    override fun shouldOverrideUrlLoading(v: WebView, request: WebResourceRequest) =
                        handlePopup(v, request.url)

                    @Deprecated("Used on API < 24")
                    override fun shouldOverrideUrlLoading(v: WebView, url: String) = handlePopup(v, Uri.parse(url))
                }
                (resultMsg.obj as WebView.WebViewTransport).webView = probe
                resultMsg.sendToTarget()
                return true
            }

            override fun onPermissionRequest(request: PermissionRequest) {
                // Allow DRM-protected playback; deny camera/mic.
                if (PermissionRequest.RESOURCE_PROTECTED_MEDIA_ID in request.resources) {
                    request.grant(arrayOf(PermissionRequest.RESOURCE_PROTECTED_MEDIA_ID))
                } else {
                    request.deny()
                }
            }

            // Avoids the grey "play" placeholder some WebViews draw before a video starts.
            override fun getDefaultVideoPoster(): Bitmap = Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888)
        }
    }

    private fun handlePopup(probe: WebView, uri: Uri): Boolean {
        val host = uri.host?.lowercase()
        if (host != null && AdBlocker.isFirstParty(host)) webView.loadUrl(uri.toString()) else notifyBlocked("popup")
        probe.post { probe.destroy() }
        return true
    }

    /** Returns true to cancel a top-level navigation (ad redirects, app-store / intent links). */
    private fun blockNavigation(uri: Uri): Boolean {
        val scheme = uri.scheme?.lowercase()
        if (scheme == "about" || scheme == "data" || scheme == "blob") return false
        if (scheme != "http" && scheme != "https") { notifyBlocked("redirect"); return true }
        val host = uri.host?.lowercase() ?: return true
        if (AdBlocker.isFirstParty(host)) return false
        if (!blocker.enabled) return false
        if (blocker.isBlockedHost(host) || NAV_ALLOWLIST.none { AdBlocker.matches(host, setOf(it)) }) {
            notifyBlocked("redirect")
            return true
        }
        return false
    }

    private fun notifyBlocked(what: String) {
        val now = SystemClock.uptimeMillis()
        if (now - lastToast < 3000) return
        lastToast = now
        runOnUiThread { Toast.makeText(this, "Blocked $what", Toast.LENGTH_SHORT).show() }
    }

    // ---------------------------------------------------------------- fullscreen

    private fun hideCustomView() {
        val view = customView ?: return
        root.removeView(view)
        customView = null
        customCallback?.onCustomViewHidden()
        customCallback = null
        updateCursorVisibility()
        webView.requestFocus()
    }

    private fun enterImmersive() {
        @Suppress("DEPRECATION")
        window.decorView.systemUiVisibility = (View.SYSTEM_UI_FLAG_FULLSCREEN
            or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
            or View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
            or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
            or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION)
    }

    // ---------------------------------------------------------------- remote control

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        val down = event.action == KeyEvent.ACTION_DOWN
        val first = down && event.repeatCount == 0

        when (event.keyCode) {
            KeyEvent.KEYCODE_MENU -> { if (first) showMenu(); return true }
            KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE -> { if (first) media("toggle"); return true }
            KeyEvent.KEYCODE_MEDIA_PLAY -> { if (first) media("play"); return true }
            KeyEvent.KEYCODE_MEDIA_PAUSE -> { if (first) media("pause"); return true }
            KeyEvent.KEYCODE_MEDIA_REWIND -> { if (down) media("seek", -10); return true }
            KeyEvent.KEYCODE_MEDIA_FAST_FORWARD -> { if (down) media("seek", 10); return true }
        }

        if (customView != null) {
            // Fullscreen video: centre = play/pause, left/right = seek.
            when (event.keyCode) {
                KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER -> {
                    if (first) media("toggle"); return true
                }
                KeyEvent.KEYCODE_DPAD_LEFT -> { if (down) media("seek", -10); return true }
                KeyEvent.KEYCODE_DPAD_RIGHT -> { if (down) media("seek", 10); return true }
            }
            return super.dispatchKeyEvent(event)
        }

        if (cursorEnabled) {
            when (event.keyCode) {
                KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT,
                KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN -> {
                    if (down) moveCursor(event)
                    return true
                }
                KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER -> {
                    if (first) sendTouch(MotionEvent.ACTION_DOWN)
                    else if (event.action == KeyEvent.ACTION_UP) sendTouch(MotionEvent.ACTION_UP)
                    return true
                }
            }
        }
        return super.dispatchKeyEvent(event)
    }

    private fun moveCursor(event: KeyEvent) {
        val step = resources.displayMetrics.density * 10 * (1 + min(event.repeatCount, 16) * 0.4f)
        var dx = 0f
        var dy = 0f
        when (event.keyCode) {
            KeyEvent.KEYCODE_DPAD_LEFT -> dx = -step
            KeyEvent.KEYCODE_DPAD_RIGHT -> dx = step
            KeyEvent.KEYCODE_DPAD_UP -> dy = -step
            KeyEvent.KEYCODE_DPAD_DOWN -> dy = step
        }
        val (ox, oy) = cursor.moveBy(dx, dy)
        if (ox != 0f || oy != 0f) {
            val fx = cursor.cx / cursor.width
            val fy = cursor.cy / cursor.height
            webView.evaluateJavascript(
                "window.__cjtvScroll&&__cjtvScroll($fx,$fy,${ox.sign * 0.2f},${oy.sign * 0.2f})", null
            )
        }
    }

    private fun sendTouch(action: Int) {
        val now = SystemClock.uptimeMillis()
        if (action == MotionEvent.ACTION_DOWN) touchDownTime = now
        val ev = MotionEvent.obtain(touchDownTime, now, action, cursor.cx, cursor.cy, 0)
        ev.source = InputDevice.SOURCE_TOUCHSCREEN
        webView.dispatchTouchEvent(ev)
        ev.recycle()
        cursor.wake()
    }

    private fun media(cmd: String, value: Int = 0) {
        webView.evaluateJavascript("window.__cjtvBroadcast&&__cjtvBroadcast({cjtv:'$cmd',v:$value})", null)
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        when {
            customView != null -> {
                webView.evaluateJavascript("document.exitFullscreen&&document.exitFullscreen()", null)
                hideCustomView()
            }
            webView.canGoBack() -> webView.goBack()
            SystemClock.uptimeMillis() - lastBackPress < 2000 -> finish()
            else -> {
                lastBackPress = SystemClock.uptimeMillis()
                Toast.makeText(this, "Press back again to exit", Toast.LENGTH_SHORT).show()
            }
        }
    }

    // ---------------------------------------------------------------- menu

    private fun showMenu() {
        val onOff = { b: Boolean -> if (b) "On" else "Off" }
        val desktop = prefs.getBoolean("desktop", false)
        val items = arrayOf(
            "Home",
            "Reload",
            "Pointer: ${onOff(cursorEnabled)}",
            "Ad blocker: ${onOff(blocker.enabled)} (${blocker.ruleCount} domains)",
            "Desktop site: ${onOff(desktop)}",
            "Update filter lists",
            "Clear cache (keeps login)",
            "Exit",
        )
        AlertDialog.Builder(this)
            .setTitle(R.string.app_name)
            .setItems(items) { _, which ->
                when (which) {
                    0 -> webView.loadUrl(HOME)
                    1 -> webView.reload()
                    2 -> {
                        cursorEnabled = !cursorEnabled
                        prefs.edit().putBoolean("cursor", cursorEnabled).apply()
                        updateCursorVisibility()
                    }
                    3 -> {
                        blocker.enabled = !blocker.enabled
                        prefs.edit().putBoolean("adblock", blocker.enabled).apply()
                        webView.reload()
                    }
                    4 -> {
                        prefs.edit().putBoolean("desktop", !desktop).apply()
                        webView.settings.userAgentString = userAgent()
                        webView.reload()
                    }
                    5 -> {
                        Toast.makeText(this, "Updating filter lists…", Toast.LENGTH_SHORT).show()
                        thread {
                            val ok = blocker.update()
                            runOnUiThread {
                                val msg = if (ok) "Filters updated: ${blocker.ruleCount} domains" else "Update failed"
                                Toast.makeText(this, msg, Toast.LENGTH_LONG).show()
                            }
                        }
                    }
                    6 -> { webView.clearCache(true); webView.reload() }
                    7 -> finish()
                }
            }
            .show()
    }

    private fun updateCursorVisibility() {
        cursor.visibility = if (cursorEnabled && customView == null) View.VISIBLE else View.GONE
        if (cursorEnabled) cursor.wake()
    }

    private fun userAgent(): String =
        if (prefs.getBoolean("desktop", false)) DESKTOP_UA
        // Drop the WebView markers so the site serves its normal Chrome experience.
        else WebSettings.getDefaultUserAgent(this).replace("; wv", "").replace(Regex("Version/\\S+ "), "")

    // ---------------------------------------------------------------- state

    private fun startUrl(): String {
        val last = prefs.getString("lastUrl", null) ?: return HOME
        val host = Uri.parse(last).host?.lowercase() ?: return HOME
        return if (AdBlocker.isFirstParty(host)) last else HOME
    }

    private fun saveLastUrl() {
        val url = webView.url ?: return
        val host = Uri.parse(url).host?.lowercase() ?: return
        if (AdBlocker.isFirstParty(host)) prefs.edit().putString("lastUrl", url).apply()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        webView.saveState(outState)
    }

    override fun onResume() {
        super.onResume()
        webView.onResume()
    }

    override fun onPause() {
        saveLastUrl()
        CookieManager.getInstance().flush()
        webView.onPause()
        super.onPause()
    }

    override fun onDestroy() {
        root.removeAllViews()
        webView.destroy()
        super.onDestroy()
    }

    companion object {
        const val HOME = "https://cinejoy.pk/"
        private val MATCH = FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        private const val DESKTOP_UA =
            "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0.0.0 Safari/537.36"

        // Off-site top-level navigations allowed besides CineJoy itself (sign-in providers).
        private val NAV_ALLOWLIST = listOf("google.com", "facebook.com", "apple.com")
    }
}
