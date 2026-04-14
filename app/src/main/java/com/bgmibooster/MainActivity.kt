package com.bgmibooster

import android.annotation.SuppressLint
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.WindowManager
import android.webkit.*
import android.widget.FrameLayout
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat

class MainActivity : AppCompatActivity() {
    private lateinit var webView: WebView
    private lateinit var bridge: AndroidBridge
    private val autoBoostHandler = Handler(Looper.getMainLooper())
    private var lastForegroundPkg = ""

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        val ctrl = WindowInsetsControllerCompat(window, window.decorView)
        ctrl.hide(WindowInsetsCompat.Type.statusBars() or WindowInsetsCompat.Type.navigationBars())
        ctrl.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        webView = WebView(this).apply {
            layoutParams = FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT)
        }
        setContentView(webView)

        webView.settings.apply {
            javaScriptEnabled = true; domStorageEnabled = true; databaseEnabled = true
            allowFileAccess = true; allowContentAccess = true
            @Suppress("DEPRECATION") allowFileAccessFromFileURLs = true
            @Suppress("DEPRECATION") allowUniversalAccessFromFileURLs = true
            mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
            cacheMode = WebSettings.LOAD_DEFAULT; setSupportZoom(false)
            builtInZoomControls = false; displayZoomControls = false
            useWideViewPort = true; loadWithOverviewMode = true
            mediaPlaybackRequiresUserGesture = false
        }
        webView.setBackgroundColor(0xFF07090E.toInt())
        bridge = AndroidBridge(this)
        webView.addJavascriptInterface(bridge, "Android")
        webView.webChromeClient = object : WebChromeClient() {
            override fun onConsoleMessage(msg: ConsoleMessage) = true
        }
        webView.webViewClient = object : WebViewClient() {
            override fun onReceivedError(view: WebView?, request: WebResourceRequest?, error: WebResourceError?) {}
        }
        webView.loadUrl("file:///android_asset/index.html")
        startAutoBoostDetection()
    }

    // Poll foreground app every 5s — notify JS if BGMI detected
    private fun startAutoBoostDetection() {
        val BGMI_PKGS = listOf("com.pubg.imobile", "com.pubg.krmobile", "com.tencent.ig")
        val runnable = object : Runnable {
            override fun run() {
                try {
                    if (bridge.hasUsageAccess()) {
                        val json = bridge.getForegroundApp()
                        val pkg = Regex("\"pkg\":\"([^\"]+)\"").find(json)?.groupValues?.get(1) ?: ""
                        if (pkg != lastForegroundPkg) {
                            lastForegroundPkg = pkg
                            if (BGMI_PKGS.contains(pkg)) {
                                webView.post { webView.evaluateJavascript("if(typeof onBGMIDetected==='function')onBGMIDetected('$pkg');", null) }
                            }
                        }
                    }
                } catch (_: Exception) {}
                autoBoostHandler.postDelayed(this, 5000)
            }
        }
        autoBoostHandler.postDelayed(runnable, 5000)
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() { if (webView.canGoBack()) webView.goBack() else super.onBackPressed() }
    override fun onResume() { super.onResume(); webView.onResume() }
    override fun onPause() { super.onPause(); webView.onPause() }
    override fun onDestroy() { autoBoostHandler.removeCallbacksAndMessages(null); webView.destroy(); super.onDestroy() }
}
