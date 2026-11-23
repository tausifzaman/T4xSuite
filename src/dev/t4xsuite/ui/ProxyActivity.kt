package dev.t4xsuite.ui

import android.app.Activity
import android.app.AlertDialog
import android.content.ActivityNotFoundException
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.webkit.CookieManager
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceError
import android.webkit.WebSettings
import android.webkit.WebStorage
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.HorizontalScrollView
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import android.text.Editable
import android.text.TextWatcher
import dev.t4xsuite.proxy.ExchangeState
import dev.t4xsuite.proxy.InterceptedRequest
import dev.t4xsuite.proxy.InterceptedResponse
import dev.t4xsuite.proxy.LocalProxy
import dev.t4xsuite.proxy.ProxyExchange
import dev.t4xsuite.proxy.AndroidCaVault
import dev.t4xsuite.proxy.HttpMessageEditor
import dev.t4xsuite.proxy.RepeaterClient
import androidx.webkit.ProxyConfig
import androidx.webkit.ProxyController
import androidx.webkit.WebViewFeature
import com.google.gson.JsonParser
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.util.concurrent.Executor


private const val MATCH = ViewGroup.LayoutParams.MATCH_PARENT
private const val CONTENT = ViewGroup.LayoutParams.WRAP_CONTENT

/** The browser and proxy are opt-in and share one process-local session. */
class ProxyActivity : Activity(), LocalProxy.Listener {
    private val main = android.os.Handler(android.os.Looper.getMainLooper())
    private val proxy by lazy { LocalProxy(this) }
    private val caVault by lazy { AndroidCaVault(this) }
    private var route: Route = Route.BROWSER
    private var web: WebView? = null
    private var aboutWeb: WebView? = null
    private var address: EditText? = null
    private var contentFrame: FrameLayout? = null
    private var root: LinearLayout? = null
    private var browserStatus: TextView? = null
    private var browserSecurity: TextView? = null
    private var browserProxyStatus: TextView? = null
    private var browserProgress: ProgressBar? = null
    private class BrowserDataReset(val setControlsEnabled: (Boolean) -> Unit) {
        var waitingForBlank = true
    }
    private var browserDataReset: BrowserDataReset? = null
    private var pausedRows: LinearLayout? = null
    private var queueLabel: TextView? = null
    private val pauseCards = linkedMapOf<String, LinearLayout>()
    private var proxyCard: LinearLayout? = null
    private var statusLabel: TextView? = null
    private var startButton: TextView? = null
    private var interceptButton: TextView? = null
    private var responseInterceptButton: TextView? = null
    private var tlsButton: TextView? = null
    private var pendingLabel: TextView? = null
    private var statusDot: View? = null
    private var caState: TextView? = null
    private var caExportButton: TextView? = null
    private var historyRows: LinearLayout? = null
    private var historyCount: TextView? = null
    private var historyEmpty: LinearLayout? = null
    private val historyCards = linkedMapOf<Long, HistoryCard>()
    private var browserButton: TextView? = null
    private var proxyTab: TextView? = null
    private var historyButton: TextView? = null
    private var repeaterButton: TextView? = null
    private var aboutButton: TextView? = null
    private var repeaterEditor: EditText? = null
    private var repeaterResult: TextView? = null
    private var sendButton: TextView? = null
    private var repeaterState: TextView? = null
    private var repeaterTarget: TextView? = null
    private var repeaterMethod: TextView? = null
    private var repeaterResponseStatus: TextView? = null
    private var lastRunning: Boolean? = null
    private var lastPausedCount = -1
    private var hasCa = false
    private var caInstalled = false
    private var checkingUpdate = false
    private val liquid by lazy { LiquidUi(this) }
    private var repeaterCall: RepeaterClient.Call? = null
    private var proxyReady = false
    private var routingGeneration = 0
    private var pendingUrl: String? = null

    override fun onCreate(state: Bundle?) {
        super.onCreate(state)
        refreshCaState()
        build()
        render()
    }

    override fun onResume() {
        super.onResume()
        runCatching { web?.onResume() }
        runCatching { aboutWeb?.onResume() }
        refreshCaState()
        render()
    }

    private fun refreshCaState() {
        val identity = if (caVault.exists()) runCatching { caVault.load() }.getOrNull() else null
        hasCa = identity != null
        caInstalled = identity != null && caVault.isCaInstalledInUserStore(identity.certificate)
    }


    override fun onPause() {
        runCatching { aboutWeb?.onPause() }
        runCatching { web?.onPause() }
        super.onPause()
    }

    override fun onBackPressed() {
        if (route != Route.BROWSER) select(Route.BROWSER)
        else if (browserDataReset != null) return
        else if (web?.canGoBack() == true) web?.goBack()
        else super.onBackPressed()
    }

    override fun onDestroy() {
        browserDataReset?.let { main.removeCallbacksAndMessages(it) }
        browserDataReset = null
        browserStatus?.let { main.removeCallbacksAndMessages(it) }
        repeaterCall?.cancel()
        repeaterCall = null
        proxy.stop()
        clearWebProxy()
        web?.apply { stopLoading(); loadUrl("about:blank"); destroy() }
        web = null
        aboutWeb?.apply { stopLoading(); destroy() }
        aboutWeb = null
        super.onDestroy()
    }

    override fun changed() = main.post { if (!isFinishing && !isDestroyed) render() }.let { }
    override fun waiting(request: InterceptedRequest) = main.post {
        if (!isFinishing && !isDestroyed && request.isPending) select(Route.PROXY)
    }.let { }
    override fun waiting(response: InterceptedResponse) = main.post {
        if (!isFinishing && !isDestroyed && response.isPending) select(Route.PROXY)
    }.let { }

    private fun build() {
        val p = colors()
        if (android.os.Build.VERSION.SDK_INT >= 30) window.setDecorFitsSystemWindows(false)
        window.statusBarColor = p.background
        window.navigationBarColor = p.background
        window.decorView.systemUiVisibility = 0 // OBSIDIAN uses light system-bar icons in both modes.

        val page = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(liquid.dim("screen_padding"), dp(8), liquid.dim("screen_padding"), dp(8))
        }
        val wallpaper = liquid.wallpaper()
        val wallpaperHost = FrameLayout(this).apply {
            background = wallpaper
            addView(page, FrameLayout.LayoutParams(MATCH, MATCH))
        }
        root = page
        setContentView(wallpaperHost)
        val top = LinearLayout(this).apply {
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(10), dp(6), dp(10), dp(6))
            background = liquid.glass()
        }
        val markId = resources.getIdentifier("ic_t4xsuite", "drawable", packageName)
        top.addView(ImageView(this).apply {
            setImageResource(markId)
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }, LinearLayout.LayoutParams(dp(32), dp(32)))
        top.addView(TextView(this).apply {
            text = "T4X SUITE"
            textSize = 17f
            letterSpacing = .06f
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            setTextColor(p.foreground)
            setPadding(dp(10), 0, 0, 0)
        }, LinearLayout.LayoutParams(CONTENT, CONTENT))
        top.addView(chip("모", p.gold, p.panel), LinearLayout.LayoutParams(CONTENT, CONTENT).apply { marginStart = dp(8) })
        page.addView(top, layout())

        val overlay = FrameLayout(this).apply { clipChildren = false }
        val contentFrame = FrameLayout(this)
        this.contentFrame = contentFrame
        page.addView(overlay, LinearLayout.LayoutParams(MATCH, 0, 1f).apply { topMargin = dp(8) })
        overlay.addView(contentFrame, FrameLayout.LayoutParams(MATCH, MATCH))
        val tabs = liquid.dock(wallpaperHost, wallpaper)
        val tabItems = liquid.dockItems(tabs)
        browserButton = nav("Browser", "browser") { select(Route.BROWSER) }
        proxyTab = nav("Proxy", "proxy") { select(Route.PROXY) }
        historyButton = nav("History", "history") { select(Route.HISTORY) }
        repeaterButton = nav("Repeater", "repeater") { select(Route.REPEATER) }
        aboutButton = nav("About", "about") { select(Route.ABOUT) }
        listOf(browserButton!!, proxyTab!!, historyButton!!, repeaterButton!!, aboutButton!!).forEach { item ->
            tabItems.addView(item, LinearLayout.LayoutParams(0, CONTENT, 1f))
        }
        buildProxyControls()

        val browser = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val addressRow = LinearLayout(this).apply {
            gravity = Gravity.CENTER_VERTICAL
            background = liquid.glass(liquid.dim("address_radius").toFloat())
            setPadding(dp(4), dp(4), dp(4), dp(4))
        }
        address = EditText(this).apply {
            hint = "Enter authorized URL"
            contentDescription = "Browser address, HTTP or HTTPS"
            textSize = 14f
            setSingleLine(true)
            setTextColor(p.foreground)
            setHintTextColor(p.secondary)
            imeOptions = EditorInfo.IME_ACTION_GO
            inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_URI
            setPadding(dp(12), 0, dp(12), 0)
            background = null
            importantForAutofill = View.IMPORTANT_FOR_AUTOFILL_NO
            isSaveEnabled = false
            setOnEditorActionListener { _, action, _ ->
                if (action != EditorInfo.IME_ACTION_GO && action != EditorInfo.IME_NULL) false
                else { navigate(); true }
            }
        }
        addressRow.addView(address, LinearLayout.LayoutParams(0, CONTENT, 1f).apply { address?.minHeight = liquid.dim("touch_target") })
        val go = liquid.addressGo { navigate() }
        addressRow.addView(go, LinearLayout.LayoutParams(CONTENT, MATCH))
        browser.addView(addressRow)
        fun textWidth(label: TextView, value: CharSequence = label.text): Int =
            kotlin.math.ceil(label.paint.measureText(value.toString()).toDouble()).toInt() +
                label.compoundPaddingLeft + label.compoundPaddingRight
        val browserLabels = object : LinearLayout(this) {
            override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
                // Use the actual remaining width, including cutouts and the chip's scaled text.
                val needed = (0 until childCount).sumOf { index -> textWidth(getChildAt(index) as TextView) } + dp(12)
                val stacked = needed > View.MeasureSpec.getSize(widthMeasureSpec) || resources.configuration.fontScale > 1.25f
                val next = if (stacked) VERTICAL else HORIZONTAL
                if (orientation != next) {
                    orientation = next
                    for (index in 0 until childCount) {
                        getChildAt(index).layoutParams = LinearLayout.LayoutParams(if (stacked) MATCH else 0, CONTENT,
                            if (stacked) 0f else 1f)
                    }
                }
                browserProxyStatus?.gravity = if (stacked) Gravity.START else Gravity.END
                super.onMeasure(widthMeasureSpec, heightMeasureSpec)
            }
        }.apply { gravity = Gravity.CENTER_VERTICAL }
        browserSecurity = text("Local page", 12, p.secondary).apply { setPadding(dp(2), 0, 0, 0) }
        browserLabels.addView(browserSecurity, LinearLayout.LayoutParams(0, CONTENT, 1f))
        browserProxyStatus = text("Proxy stopped", 12, p.accent).apply { gravity = Gravity.END }
        browserLabels.addView(browserProxyStatus, LinearLayout.LayoutParams(0, CONTENT, 1f))
        val modeChip = liquid.browserModeChip()
        val clearDataChip = liquid.browserClearDataChip { }
        val browserActions = liquid.row().apply {
            addView(modeChip, LinearLayout.LayoutParams(CONTENT, CONTENT))
            addView(clearDataChip, LinearLayout.LayoutParams(CONTENT, CONTENT).apply { marginStart = dp(6) })
        }
        val browserMeta = object : LinearLayout(this) {
            override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
                val available = View.MeasureSpec.getSize(widthMeasureSpec)
                val chipWidth = maxOf(modeChip.minimumWidth, textWidth(modeChip, "Mobile"), textWidth(modeChip, "Desktop")) +
                    maxOf(clearDataChip.minimumWidth, textWidth(clearDataChip)) + dp(6)
                val stacked = available < chipWidth + dp(128) || resources.configuration.fontScale > 1.25f
                val next = if (stacked) VERTICAL else HORIZONTAL
                if (orientation != next) orientation = next
                (browserLabels.layoutParams as LinearLayout.LayoutParams).apply {
                    width = if (stacked) MATCH else 0
                    weight = if (stacked) 0f else 1f
                }
                (browserActions.layoutParams as LinearLayout.LayoutParams).apply {
                    width = if (chipWidth > available) MATCH else CONTENT
                    marginStart = if (stacked) 0 else dp(8)
                    topMargin = if (stacked) dp(2) else 0
                }
                for (chip in listOf(modeChip, clearDataChip)) {
                    (chip.layoutParams as LinearLayout.LayoutParams).apply {
                        width = if (chipWidth > available) 0 else CONTENT
                        weight = if (chipWidth > available) 1f else 0f
                    }
                }
                super.onMeasure(widthMeasureSpec, heightMeasureSpec)
            }
        }.apply {
            gravity = Gravity.CENTER_VERTICAL
            addView(browserLabels, LinearLayout.LayoutParams(0, CONTENT, 1f))
            addView(browserActions, LinearLayout.LayoutParams(CONTENT, CONTENT))
        }
        browser.addView(browserMeta, layout(top = 6))
        browserProgress = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 100
            progressTintList = android.content.res.ColorStateList.valueOf(p.accent)
            progressBackgroundTintList = android.content.res.ColorStateList.valueOf(p.stroke)
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }
        browser.addView(browserProgress, LinearLayout.LayoutParams(MATCH, dp(2)).apply { topMargin = dp(4) })
        val webView = WebView(this)
        web = webView
        // Only the local welcome is transparent; external loads get the stable dark backing before load.
        webView.setBackgroundColor(Color.TRANSPARENT)
        webView.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            cacheMode = WebSettings.LOAD_NO_CACHE
            loadWithOverviewMode = true
            useWideViewPort = true
            setSupportZoom(true)
            builtInZoomControls = true
            displayZoomControls = false
            allowFileAccess = false
            allowContentAccess = false
            setSupportMultipleWindows(false)
            javaScriptCanOpenWindowsAutomatically = false
            mixedContentMode = android.webkit.WebSettings.MIXED_CONTENT_NEVER_ALLOW
            userAgentString = "$userAgentString T4X-SUITE/0.5"
        }
        webView.webChromeClient = object : WebChromeClient() {
            override fun onProgressChanged(view: WebView?, progress: Int) {
                if (browserDataReset != null) return
                browserProgress?.apply {
                    setProgress(progress, LiquidMotion.enabled(this))
                    alpha = if (progress < 100) 1f else 0f
                }
            }
        }
        webView.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest): Boolean =
                browserDataReset != null || request.url.scheme !in setOf("https", "http")

            override fun onPageStarted(view: WebView?, url: String?, favicon: android.graphics.Bitmap?) {
                setBrowserBackground(view, url)
                if (browserDataReset != null) return
                browserStatus?.setTextColor(p.secondary)
                browserStatus?.visibility = View.GONE
                browserSecurity?.text = when {
                    url?.startsWith("https://") == true -> "HTTPS address"
                    url?.startsWith("http://") == true -> "HTTP · unencrypted"
                    else -> "Local page"
                }
                browserSecurity?.contentDescription = "${browserSecurity?.text}. WebView certificate verification remains enabled; the address alone is not proof of a trusted connection."
                if (url?.startsWith("https://") == true) showTlsNoticeOnce()
                if (url?.startsWith("https://") == true || url?.startsWith("http://") == true) address?.setText(url.take(1500))
            }

            override fun onPageFinished(view: WebView?, url: String?) {
                if (view != null && view === webView && url == "about:blank" && view.url == url) {
                    browserDataReset?.let { reset -> clearAfterBlank(view, reset) }
                }
            }

            override fun onReceivedError(view: WebView?, request: WebResourceRequest, error: WebResourceError) {
                if (browserDataReset != null) return
                if (request.isForMainFrame) {
                    browserStatus?.setTextColor(p.error)
                    browserStatus?.text = "Page could not load: ${error.description}. Check the address, proxy and CA settings."
                    browserStatus?.visibility = View.VISIBLE
                }
            }

            override fun onReceivedSslError(view: WebView?, handler: android.webkit.SslErrorHandler, error: android.net.http.SslError) {
                handler.cancel()
                if (browserDataReset != null) return
                browserSecurity?.text = "TLS rejected"
                browserSecurity?.contentDescription = "TLS certificate rejected. Connection not trusted."
                browserStatus?.setTextColor(p.error)
                browserStatus?.text = "TLS certificate rejected. Install your exported CA for inspection, or check the site's certificate."
                browserStatus?.visibility = View.VISIBLE
            }
        }
        browserStatus = text("", 12, p.secondary).apply {
            visibility = View.GONE // Errors and explicit Clear Data feedback only.
            background = liquid.shape(p.background, 12)
            setPadding(dp(8), dp(6), dp(8), dp(6))
            accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
        }
        browser.addView(browserStatus, layout())
        val browserViewport = liquid.browserViewport(webView)
        // Activity-local only: restore this WebView's exact Mobile defaults, not stored preferences.
        val mobileUserAgent = webView.settings.userAgentString
        val mobileWideViewport = webView.settings.useWideViewPort
        val mobileOverview = webView.settings.loadWithOverviewMode
        val chromeVersion = Regex("""Chrome/[\d.]+""").find(mobileUserAgent)?.value
        val desktopUserAgent = chromeVersion?.let {
            "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) $it Safari/537.36"
        }
        var desktopMode = false
        fun describeBrowserMode() {
            val mode = if (desktopMode) "Desktop" else "Mobile"
            modeChip.text = mode
            modeChip.isSelected = desktopMode
            modeChip.contentDescription = if (desktopUserAgent == null) "Browser mode: Mobile. Desktop unavailable: WebView Chrome version not reported."
                else "Browser mode: $mode. Switch to ${if (desktopMode) "Mobile" else "Desktop"}."
            if (android.os.Build.VERSION.SDK_INT >= 30) modeChip.stateDescription = mode
        }
        modeChip.isEnabled = desktopUserAgent != null
        describeBrowserMode()
        modeChip.setOnClickListener {
            if (desktopUserAgent != null) {
                val activeUrl = webView.url?.takeIf { it.startsWith("https://") || it.startsWith("http://") }
                // Stop before changing UA: changing it mid-load can otherwise initiate a second load.
                webView.stopLoading()
                desktopMode = !desktopMode
                webView.settings.apply {
                    useWideViewPort = if (desktopMode) true else mobileWideViewport
                    loadWithOverviewMode = if (desktopMode) true else mobileOverview
                    userAgentString = if (desktopMode) desktopUserAgent else mobileUserAgent
                }
                describeBrowserMode()
                // Standard MATCH_PARENT viewport: exactly one explicit reload, directly on tap.
                if (activeUrl != null && !isFinishing && !isDestroyed) webView.reload()
            }
        }
        clearDataChip.setOnClickListener {
            confirmBrowserDataReset(webView) { enabled ->
                go.isEnabled = enabled
                clearDataChip.isEnabled = enabled
                modeChip.isEnabled = enabled && desktopUserAgent != null
            }
        }
        browser.addView(browserViewport, LinearLayout.LayoutParams(MATCH, 0, 1f).apply { topMargin = dp(7) })
        contentFrame.addView(browser, FrameLayout.LayoutParams(MATCH, MATCH))
        LiquidMotion.watch(browser)
        webView.loadDataWithBaseURL("about:blank", """
            <!doctype html><html><head><meta name="viewport" content="width=device-width,initial-scale=1">
            <meta charset="utf-8"><style>html,body{background:transparent!important}body{font:15px sans-serif;margin:0;padding:28px 20px;color:${liquid.css(p.foreground)}}h1{font-size:28px;line-height:1.2;letter-spacing:-.6px;max-width:340px}p{line-height:1.7;color:${liquid.css(p.secondary)}}small{color:${liquid.css(p.accent)};letter-spacing:2px}#js{border-top:1px solid ${liquid.css(p.elevated)};padding-top:20px;color:${liquid.css(p.accent)}}</style></head>
            <body><small>T4X OBSIDIAN / BROWSER</small><h1>Your testing browser is ready.</h1>
            <p>Enter an HTTP or HTTPS address above and tap Go. Only browse systems you own or are authorized to test.</p>
            <p>Proxy controls and paused requests are in the Proxy tab. History requests can be sent to Repeater.</p>
            <p id="js">JavaScript check…</p><noscript>JavaScript is disabled.</noscript>
            <script>document.getElementById('js').textContent='JavaScript is enabled ✓';</script></body></html>
        """.trimIndent(), "text/html", "UTF-8", null)

        val proxyPage = buildProxyPage()
        contentFrame.addView(proxyPage, FrameLayout.LayoutParams(MATCH, MATCH))

        val history = buildHistoryPage()
        contentFrame.addView(history, FrameLayout.LayoutParams(MATCH, MATCH))
        val repeaterPage = buildRepeaterPage()
        contentFrame.addView(repeaterPage, FrameLayout.LayoutParams(MATCH, MATCH))
        val aboutPage = buildAboutPage()
        contentFrame.addView(aboutPage, FrameLayout.LayoutParams(MATCH, MATCH))
        browser.tag = Route.BROWSER
        proxyPage.tag = Route.PROXY
        history.tag = Route.HISTORY
        repeaterPage.tag = Route.REPEATER
        aboutPage.tag = Route.ABOUT
        overlay.addView(tabs, FrameLayout.LayoutParams(MATCH, CONTENT, Gravity.BOTTOM).apply { bottomMargin = dp(8) })
        fun updateDockClearance() {
            val clearance = if (tabs.visibility == View.VISIBLE) tabs.height + dp(8) else 0
            listOf(proxyPage, history, repeaterPage).forEach { scrollPage ->
                val bottom = dp(12) + clearance
                if (scrollPage.paddingBottom != bottom) scrollPage.setPadding(0, 0, 0, bottom)
            }
            browserViewport.setDockClearance(clearance + if (clearance > 0) dp(12) else 0)
            aboutPage.setDockClearance(clearance + if (clearance > 0) dp(12) else 0)
        }
        // Use actual wrap height (including wrapped labels/large fonts), not a guessed dock size.
        tabs.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> updateDockClearance() }
        page.setOnApplyWindowInsetsListener { view, insets ->
            if (android.os.Build.VERSION.SDK_INT >= 30) {
                val safe = insets.getInsets(android.view.WindowInsets.Type.systemBars() or android.view.WindowInsets.Type.displayCutout() or android.view.WindowInsets.Type.ime())
                val pad = liquid.dim("screen_padding")
                view.setPadding(pad + safe.left, dp(8) + safe.top, pad + safe.right, dp(8) + safe.bottom)
                val editing = insets.isVisible(android.view.WindowInsets.Type.ime())
                top.visibility = if (editing) View.GONE else View.VISIBLE
                tabs.visibility = if (editing) View.GONE else View.VISIBLE
                updateDockClearance()
            }
            insets
        }
        page.requestApplyInsets()
        select(Route.BROWSER)
    }

    private fun setBrowserBackground(view: WebView?, url: String?) {
        val external = url?.startsWith("https://", ignoreCase = true) == true ||
            url?.startsWith("http://", ignoreCase = true) == true
        view?.setBackgroundColor(if (external) colors().background else Color.TRANSPARENT)
    }

    private fun buildAboutPage(): LiquidUi.DockBrowserViewport {
        val assetUrl = "file:///android_asset/about.html"
        val allowedAssets = setOf(assetUrl, "file:///android_asset/pic.jpg", "file:///android_asset/ic_t4xsuite.svg")
        val view = WebView(this).apply {
            setBackgroundColor(colors().background)
            contentDescription = "About T4X-SUITE, version 0.5.0 and developer information"
            settings.apply {
                javaScriptEnabled = false
                domStorageEnabled = false
                allowFileAccess = true // Only this isolated, static asset view; Browser stays false.
                allowContentAccess = false
                @Suppress("DEPRECATION")
                setAllowFileAccessFromFileURLs(false)
                @Suppress("DEPRECATION")
                setAllowUniversalAccessFromFileURLs(false)
                blockNetworkLoads = true
                loadsImagesAutomatically = true
                blockNetworkImage = false // Local artwork is enabled; network loads remain blocked above.
                setSupportMultipleWindows(false)
                javaScriptCanOpenWindowsAutomatically = false
                mixedContentMode = android.webkit.WebSettings.MIXED_CONTENT_NEVER_ALLOW
            }
            webViewClient = object : WebViewClient() {
                override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest): Boolean {
                    val uri = request.url
                    if (request.isForMainFrame && request.hasGesture() && uri.scheme in setOf("http", "https")) {
                        if (uri.toString().equals("https://github.com/tausifzaman/T4xSuite/releases/latest", ignoreCase = true)) {
                            checkForAppUpdate()
                        } else {
                            openExternalUrl(uri)
                        }
                    }
                    return true // No navigation, external or otherwise, is loaded inside About.
                }
                override fun shouldInterceptRequest(view: WebView?, request: WebResourceRequest): android.webkit.WebResourceResponse? =
                    if (request.url.toString() in allowedAssets) null
                    else android.webkit.WebResourceResponse("text/plain", "UTF-8", java.io.ByteArrayInputStream(byteArrayOf()))
            }
        }
        aboutWeb = view
        view.loadUrl(assetUrl)
        return liquid.browserViewport(view)
    }

    private fun openExternalUrl(uri: android.net.Uri) {
        try {
            startActivity(Intent(Intent.ACTION_VIEW, uri))
        } catch (_: ActivityNotFoundException) {
            Toast.makeText(this, "No browser available to open this link.", Toast.LENGTH_SHORT).show()
        }
    }

    private fun isNewerVersion(remoteTag: String, current: String): Boolean {
        val remoteParts = remoteTag.trim().removePrefix("v").removePrefix("V")
            .substringBefore('-').split('.').map { it.toIntOrNull() ?: 0 }
        val currentParts = current.trim().removePrefix("v").removePrefix("V")
            .substringBefore('-').split('.').map { it.toIntOrNull() ?: 0 }
        for (i in 0 until maxOf(remoteParts.size, currentParts.size)) {
            val r = remoteParts.getOrElse(i) { 0 }
            val c = currentParts.getOrElse(i) { 0 }
            if (r != c) return r > c
        }
        return false
    }

    private fun checkForAppUpdate() {
        if (checkingUpdate || isFinishing || isDestroyed) return
        checkingUpdate = true
        Toast.makeText(this, "Checking for updates…", Toast.LENGTH_SHORT).show()
        val currentVersion = "0.5.0"
        val releasesUrl = "https://github.com/tausifzaman/T4xSuite/releases/latest"
        val repoUrl = "https://github.com/tausifzaman/T4xSuite"
        Thread {
            var title = "Update check failed"
            var message = "Could not reach GitHub. Check your internet connection and try again."
            var actionLabel = "Open GitHub"
            var actionUrl = repoUrl
            try {
                val conn = (URL("https://api.github.com/repos/tausifzaman/T4xSuite/releases/latest").openConnection() as HttpURLConnection).apply {
                    requestMethod = "GET"
                    connectTimeout = 8_000
                    readTimeout = 8_000
                    setRequestProperty("User-Agent", "T4xSuite-Android")
                    setRequestProperty("Accept", "application/vnd.github+json")
                }
                try {
                    val code = conn.responseCode
                    when (code) {
                        200 -> {
                            val body = conn.inputStream.bufferedReader(StandardCharsets.UTF_8).use { it.readText() }
                            val json = JsonParser.parseString(body).asJsonObject
                            val tag = json.get("tag_name")?.asString?.trim().orEmpty()
                            val htmlUrl = json.get("html_url")?.asString?.trim().takeUnless { it.isNullOrEmpty() } ?: releasesUrl
                            val notes = json.get("body")?.asString?.trim().orEmpty()
                                .let { if (it.length > 500) it.take(500) + "…" else it }
                            if (tag.isNotEmpty() && isNewerVersion(tag, currentVersion)) {
                                title = "Update available ($tag)"
                                message = if (notes.isNotEmpty()) notes else "A newer version of T4xSuite is available on GitHub."
                                actionLabel = "View & Update"
                                actionUrl = htmlUrl
                            } else {
                                title = "Up to date"
                                message = "T4xSuite v$currentVersion is up to date."
                                actionLabel = ""
                                actionUrl = ""
                            }
                        }
                        404 -> {
                            title = "No release published"
                            message = "No published release found yet."
                            actionLabel = "Open GitHub"
                            actionUrl = repoUrl
                        }
                        else -> {
                            title = "Update check failed"
                            message = "GitHub returned HTTP $code. Try again later."
                            actionLabel = "Open GitHub"
                            actionUrl = repoUrl
                        }
                    }
                } finally {
                    conn.disconnect()
                }
            } catch (_: Exception) {
                // Keep default offline/network error message.
            }
            main.post {
                checkingUpdate = false
                if (isFinishing || isDestroyed) return@post
                val dialog = AlertDialog.Builder(this).setTitle(title).setMessage(message)
                if (actionLabel.isNotEmpty() && actionUrl.isNotEmpty()) {
                    dialog.setNegativeButton("Close", null)
                    dialog.setPositiveButton(actionLabel) { _, _ ->
                        openExternalUrl(android.net.Uri.parse(actionUrl))
                    }
                } else {
                    dialog.setPositiveButton("OK", null)
                }
                dialog.show()
            }
        }.apply { isDaemon = true; start() }
    }


    private fun confirmBrowserDataReset(webView: WebView, setControlsEnabled: (Boolean) -> Unit) {
        if (web !== webView || browserDataReset != null || isFinishing || isDestroyed) return
        AlertDialog.Builder(this).setTitle("Clear browser data?")
            .setMessage("Clear this app’s cache, cookies, site storage and Browser back history for all sites? You may be signed out. Proxy History and certificates are not affected.")
            .setNegativeButton("Cancel", null)
            .setPositiveButton("Clear Data") { _, _ ->
                val reset = BrowserDataReset(setControlsEnabled)
                browserDataReset = reset
                pendingUrl = null // Do not resume an old destination when proxy routing becomes ready.
                setControlsEnabled(false)
                address?.text?.clear()
                browserSecurity?.text = "Local page"
                browserSecurity?.contentDescription = "Local page. No network request."
                browserProgress?.alpha = 0f
                showBrowserDataStatus("Clearing browser data…", colors().secondary)
                main.postDelayed({ finishBrowserDataReset(reset, success = false) }, reset, 10_000L)
                try {
                    webView.stopLoading()
                    webView.loadUrl("about:blank")
                } catch (_: Exception) {
                    finishBrowserDataReset(reset, success = false)
                }
            }.show()
    }

    private fun clearAfterBlank(webView: WebView, reset: BrowserDataReset) {
        if (!reset.waitingForBlank) return
        reset.waitingForBlank = false
        // The old page has been replaced: do not let its scripts repopulate storage,
        // and clear back entries after the new document is in history.
        try {
            webView.clearCache(true)
            webView.clearFormData()
            webView.clearHistory()
            WebStorage.getInstance().deleteAllData()
            val cookies = CookieManager.getInstance()
            // Removal is async; flush after its callback, even when no cookies existed.
            cookies.removeAllCookies {
                Thread {
                    val flushed = runCatching { cookies.flush() }.isSuccess // Android may do disk I/O here.
                    main.post { finishBrowserDataReset(reset, success = flushed) }
                }.apply { isDaemon = true; start() }
            }
        } catch (_: Exception) {
            finishBrowserDataReset(reset, success = false)
        }
    }

    private fun finishBrowserDataReset(reset: BrowserDataReset, success: Boolean) {
        if (browserDataReset !== reset || isFinishing || isDestroyed) return
        main.removeCallbacksAndMessages(reset)
        browserDataReset = null
        reset.setControlsEnabled(true)
        if (success) showBrowserDataStatus("Browser cache, cookies & storage cleared", colors().success, briefly = true)
        else showBrowserDataStatus("Could not confirm browser data was cleared. Try again.", colors().error)
    }

    private fun showBrowserDataStatus(message: String, color: Int, briefly: Boolean = false) {
        val status = browserStatus ?: return
        main.removeCallbacksAndMessages(status)
        status.setTextColor(color)
        status.text = message
        status.visibility = View.VISIBLE
        if (briefly) {
            val accessibility = getSystemService(android.view.accessibility.AccessibilityManager::class.java)
            val timeout = accessibility.getRecommendedTimeoutMillis(5_000,
                android.view.accessibility.AccessibilityManager.FLAG_CONTENT_TEXT)
            main.postDelayed({ if (status.text == message) status.visibility = View.GONE }, status, timeout.toLong())
        }
    }

    private fun section(title: String, description: String) = liquid.section(title, description)

    private fun scroll(content: LinearLayout) = ScrollView(this).apply {
        isFillViewport = true
        clipToPadding = false
        setPadding(0, 0, 0, dp(12))
        addView(content, FrameLayout.LayoutParams(MATCH, CONTENT))
        LiquidMotion.watch(this)
    }

    /** Wrap-height controls, stacked on narrow/large-text displays rather than clipped. */
    private fun actions(vararg buttons: TextView) = LinearLayout(this).apply {
        val stacked = resources.configuration.screenWidthDp < 360 || resources.configuration.fontScale > 1.25f
        orientation = if (stacked) LinearLayout.VERTICAL else LinearLayout.HORIZONTAL
        buttons.forEachIndexed { index, button ->
            addView(button, if (stacked) layout(if (index == 0) 0 else 8)
                else liquid.touch().apply { if (index > 0) marginStart = dp(8) })
        }
    }

    private fun buildProxyControls() {
        val p = colors()
        proxyCard = liquid.panel().apply {
            val state = liquid.row()
            statusDot = View(this@ProxyActivity).apply {
                background = round(p.muted, 5)
                importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
                LiquidMotion.watch(this)
            }
            state.addView(statusDot, LinearLayout.LayoutParams(dp(8), dp(8)).apply { marginEnd = dp(8) })
            statusLabel = text("Proxy stopped", 18, p.foreground).apply {
                typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
                accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
                LiquidMotion.watch(this)
            }
            state.addView(statusLabel, LinearLayout.LayoutParams(0, CONTENT, 1f))
            addView(state)
            addView(text("Loopback only · this browser only", 12, p.secondary), layout(top = 6))
            pendingLabel = chip("0 PAUSED", p.secondary, p.selected)
            addView(pendingLabel, layout(top = 8))
            startButton = actionButton("Start proxy", primary = true) { toggleProxy() }
            addView(startButton, layout(top = 12))
        }
        interceptButton = actionButton("Request interception · Off") { proxy.setIntercept(!proxy.interceptHttp) }
        responseInterceptButton = actionButton("Response interception · Off") { proxy.setInterceptResponses(!proxy.interceptResponses) }
        tlsButton = actionButton("HTTPS inspection · Off") { toggleTlsInspection() }
    }

    private fun buildProxyPage(): ScrollView {
        val p = colors()
        val content = liquid.column()
        content.addView(liquid.heading("Proxy", "Traffic control / local workspace"))
        content.addView(proxyCard, layout(top = 16))
        val queue = liquid.row()
        queue.addView(text("Intercept queue", 18, p.foreground).apply { isAccessibilityHeading = true }, LinearLayout.LayoutParams(0, CONTENT, 1f))
        content.addView(queue, layout(top = 20))
        queueLabel = text("No paused traffic", 12, p.secondary).apply { accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE }
        content.addView(queueLabel, layout(top = 4))
        pausedRows = liquid.column()
        content.addView(pausedRows, layout())
        val intercept = section("Interception", "Pause HTTP and inspected HTTPS inline. Forward sends the original unless edited. Edits recalculate Content-Length. Turning a control off releases its originals; decisions expire after 120 seconds.")
        intercept.addView(interceptButton, layout(top = 12))
        intercept.addView(responseInterceptButton, layout(top = 8))
        intercept.addView(tlsButton, layout(top = 8))
        intercept.addView(text("HTTPS inspection requires a manually installed CA. All three controls default to off.", 12, p.secondary), layout(top = 8))
        content.addView(intercept, layout(top = 16))
        val certificates = section("Certificate / HTTPS lab", "Generate or export the public CA, then install it yourself in Android Settings → Security → Install CA certificate (wording varies). Export is not proof of installation. User CA trust may be restricted; pinning is never bypassed.")
        caState = text("", 12, p.gold)
        certificates.addView(caState, layout(top = 12))
        caExportButton = actionButton(if (hasCa) "Export CA certificate" else "Generate CA") { manageCa() }.apply {
            liquid.icon(this, "export")
        }
        certificates.addView(caExportButton, layout(top = 12))
        certificates.addView(actionButton("Delete CA", danger = true) { deleteCa() }, layout(top = 8))
        content.addView(certificates, layout(top = 12))
        content.addView(section("Session boundaries", "The listener binds only to 127.0.0.1 and starts only on your action. History keeps up to 500 entries in memory. Screenshots are allowed; review URLs, headers and responses for secrets before sharing."), layout(top = 12))
        return scroll(content)
    }

    private fun buildHistoryPage(): ScrollView {
        val content = liquid.column()
        content.addView(liquid.heading("History", "Captured traffic / newest first"))
        historyCount = text("0 / 500 exchanges · memory only", 12, colors().secondary)
        content.addView(historyCount, layout(top = 8))
        content.addView(actionButton("Clear in-memory history") { proxy.clearHistory() }, layout(top = 12))
        historyEmpty = section("No traffic yet", "Start the proxy, then browse an authorized site. Default HTTPS entries contain encrypted CONNECT tunnels, not decrypted requests. Direct Repeater sends do not appear here.")
        content.addView(historyEmpty, layout(top = 16))
        historyRows = liquid.column()
        content.addView(historyRows, layout(top = 4))
        return scroll(content)
    }

    private fun buildRepeaterPage(): ScrollView {
        val p = colors()
        val content = liquid.column()
     //   content.addView(liquid.heading("Repeater", "Direct HTTP(S) / independent of proxy"))
       // content.addView(text("", 14, p.secondary), layout(top = 8))
        val requestPanel = liquid.panel()
        val stackHeader = resources.configuration.fontScale > 1.25f || resources.configuration.screenWidthDp < 360
        val requestHeader = if (stackHeader) liquid.column() else liquid.row()
        requestHeader.addView(text("Request", 18, p.foreground).apply { isAccessibilityHeading = true },
            if (stackHeader) layout() else LinearLayout.LayoutParams(0, CONTENT, 1f))
        repeaterState = chip("READY", p.accent, p.selected).apply { accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE }
        requestHeader.addView(repeaterState)
        requestPanel.addView(requestHeader)
        repeaterMethod = chip("RAW", p.cyan, p.selected)
        requestPanel.addView(repeaterMethod, LinearLayout.LayoutParams(CONTENT, CONTENT).apply { topMargin = dp(12) })
        repeaterTarget = text("No target entered", 13, p.secondary).apply { setTextIsSelectable(true) }
        requestPanel.addView(repeaterTarget, layout(top = 6))
        repeaterEditor = EditText(this).apply {
            inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_FLAG_MULTI_LINE or android.text.InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
            liquid.editor(this)
            minLines = 8; maxLines = 18
            contentDescription = "Raw HTTP request editor. Text is unchanged until you edit it."
            hint = "GET http://example.test/path HTTP/1.1\r\nHost: example.test\r\n\r\n"
            addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                    // Read only: decoration never writes text/spans, selection or request bytes.
                    val lineEnd = if (s == null) -1 else android.text.TextUtils.indexOf(s, '\n')
                    val firstLine = s?.subSequence(0, if (lineEnd >= 0) lineEnd else s.length)?.toString()?.trimEnd('\r').orEmpty()
                    val parts = firstLine.split(' ', limit = 3)
                    repeaterMethod?.text = parts.firstOrNull()?.takeIf { it.isNotBlank() }?.take(16) ?: "RAW"
                    repeaterMethod?.setTextColor(liquid.methodColor(parts.firstOrNull().orEmpty()))
                    repeaterTarget?.text = parts.getOrNull(1)?.takeIf { it.isNotBlank() } ?: "No target entered"
                }
                override fun afterTextChanged(s: Editable?) = Unit
            })
        }
        requestPanel.addView(repeaterEditor, layout(top = 12))
        sendButton = actionButton("Send request", primary = true) { sendRepeaterRequest() }
        requestPanel.addView(sendButton, layout(top = 12))
        content.addView(requestPanel, layout(top = 16))
        val responsePanel = liquid.panel()
        responsePanel.addView(text("Response", 18, p.foreground).apply { isAccessibilityHeading = true })
        repeaterResponseStatus = text("No response yet", 13, p.secondary)
        responsePanel.addView(repeaterResponseStatus, layout(top = 8))
        repeaterResult = text("Response status, headers and body appear here. Send a request to begin.", 13, p.foreground).apply {
            typeface = Typeface.MONOSPACE
            setTextIsSelectable(true)
            setHorizontallyScrolling(true)
            setPadding(dp(12), dp(12), dp(12), dp(12))
            background = round(p.editor, 16)
        }
        responsePanel.addView(HorizontalScrollView(this).apply {
            isFillViewport = true
            addView(repeaterResult, FrameLayout.LayoutParams(CONTENT, CONTENT))
        }, layout(top = 12))
        responsePanel.addView(text("2 MiB capture · 64 KiB preview · raw text only\nScreenshots may expose cookies, tokens and response data.", 12, p.secondary), layout(top = 12))
        content.addView(responsePanel, layout(top = 12))
        return scroll(content)
    }

    private fun select(next: Route) {
        val changed = route != next
        route = next
        val frame = contentFrame ?: return
        for (i in 0 until frame.childCount) {
            val item = frame.getChildAt(i)
            item.visibility = if (item.tag == next) View.VISIBLE else View.GONE
            // Do not alpha-composite live Chromium surfaces during tab transitions.
            if (changed && item.tag == next && next != Route.BROWSER && next != Route.ABOUT) LiquidMotion.enter(item, LiquidMotion.TAB)
        }
        val nav = listOf(Route.BROWSER to browserButton, Route.PROXY to proxyTab, Route.HISTORY to historyButton, Route.REPEATER to repeaterButton, Route.ABOUT to aboutButton)
        nav.forEach { (key, view) ->
            view?.apply {
                val selected = key == next
                isSelected = selected
                setTextColor(if (selected) colors().accent else colors().secondary)
                compoundDrawables[1]?.setTint(if (selected) colors().accent else colors().secondary)
                liquid.selectDockItem(this, selected, animate = changed)
            }
        }
        render()
    }

    private fun toggleProxy() {
        if (proxy.running) {
            proxy.stop()
            clearWebProxy()
            Toast.makeText(this, "Proxy stopped. Pending requests released.", Toast.LENGTH_SHORT).show()
        } else {
            try {
                proxy.start()
                installWebProxy(proxy.port)
            } catch (_: Exception) {
                proxy.stop()
                pendingUrl = null
                proxyReady = false
                Toast.makeText(this, "Couldn't start the local proxy.", Toast.LENGTH_LONG).show()
                render()
            }
        }
        render()
    }

    private fun installWebProxy(port: Int) {
        proxyReady = false
        val generation = ++routingGeneration
        if (!WebViewFeature.isFeatureSupported(WebViewFeature.PROXY_OVERRIDE)) {
            proxy.stop()
            clearWebProxy()
            Toast.makeText(this, "This Android System WebView cannot be routed through the local proxy.", Toast.LENGTH_LONG).show()
            render()
            return
        }
        val config = ProxyConfig.Builder().addProxyRule("127.0.0.1:$port").build()
        ProxyController.getInstance().setProxyOverride(config, Executor { it.run() }) {
            main.post {
                if (isDestroyed || generation != routingGeneration || !proxy.running || proxy.port != port) return@post
                proxyReady = true
                Toast.makeText(this, "In-app browser routed through the local proxy.", Toast.LENGTH_SHORT).show()
                pendingUrl?.let { url -> pendingUrl = null; loadBrowserUrl(url) }
                render()
            }
        }
    }

    private fun clearWebProxy() {
        routingGeneration++
        proxyReady = false
        pendingUrl = null
        if (android.os.Build.VERSION.SDK_INT >= 29) {
            runCatching {
                ProxyController.getInstance().clearProxyOverride(Executor { it.run() }) { }
            }
        }
    }

    private fun resolveBrowserInput(input: String): String? {
        if (input.isBlank()) return null
        val hasWhitespace = input.any { it.isWhitespace() }
        if (!hasWhitespace && (input.startsWith("http://", ignoreCase = true) || input.startsWith("https://", ignoreCase = true))) {
            val uri = runCatching { android.net.Uri.parse(input) }.getOrNull()
            return if (uri != null && uri.scheme in setOf("https", "http") && !uri.host.isNullOrBlank() && uri.userInfo == null) input else null
        }
        if (!hasWhitespace) {
            val authority = input.substringBefore('/').substringBefore('?').substringBefore('#')
            if (authority.contains('@')) return null
            val host = authority.substringBefore(':').lowercase(java.util.Locale.ROOT)
            val portPart = authority.substringAfter(':', "")
            val validPort = portPart.isEmpty() || portPart.toIntOrNull()?.let { it in 1..65535 } == true
            val isLoopback = host == "localhost" || host == "127.0.0.1" || host.startsWith("127.")
            val isIpv4 = Regex("""^(\d{1,3}\.){3}\d{1,3}$""").matches(host) &&
                host.split('.').all { it.toIntOrNull()?.let { n -> n in 0..255 } == true }
            val isDomain = host.contains('.') && !host.startsWith('.') && !host.endsWith('.') &&
                !host.contains("..") && host.split('.').all { it.isNotEmpty() }
            if (validPort && (isLoopback || isIpv4 || isDomain)) {
                val scheme = if (isLoopback) "http" else "https"
                val candidate = "$scheme://$input"
                val uri = runCatching { android.net.Uri.parse(candidate) }.getOrNull()
                if (uri != null && uri.scheme in setOf("https", "http") && !uri.host.isNullOrBlank() && uri.userInfo == null) {
                    return candidate
                }
            }
        }
        return "https://www.google.com/search?q=${URLEncoder.encode(input, "UTF-8")}"
    }

    private fun navigate() {
        if (browserDataReset != null) return
        val raw = address?.text?.toString()?.trim().orEmpty()
        val normalized = resolveBrowserInput(raw)
        if (normalized == null) {
            Toast.makeText(this, "Enter a valid HTTP or HTTPS address.", Toast.LENGTH_LONG).show()
            return
        }
        if (!proxy.running) {
            AlertDialog.Builder(this).setTitle("Start your local proxy?")
                    .setMessage("Your in-app browser will use a loopback-only proxy. HTTPS inspection is disabled unless you generate, install, and explicitly enable a CA.")
                .setNegativeButton("Not now", null).setPositiveButton("Start & continue") { _, _ ->
                    pendingUrl = normalized
                    toggleProxy()
                }.show()
            return
        }
        if (!proxyReady) {
            pendingUrl = normalized
            browserStatus?.text = "Waiting for the local proxy route…"
            return
        }
        loadBrowserUrl(normalized)
    }


    private fun loadBrowserUrl(url: String) {
        val keyboard = getSystemService(INPUT_METHOD_SERVICE) as android.view.inputmethod.InputMethodManager
        address?.let { keyboard.hideSoftInputFromWindow(it.windowToken, 0); it.clearFocus() }
        select(Route.BROWSER)
        web?.requestFocus()
        setBrowserBackground(web, url)
        web?.loadUrl(url)
    }

    /** Stable cards preserve drafts and cursor positions while unrelated requests complete. */
    private fun renderPaused() {
        val rows = pausedRows ?: return
        val requests = proxy.pending()
        val responses = proxy.pendingResponses()
        val active = requests.map { "request:${it.exchange.id}" }.toSet() + responses.map { "response:${it.exchange.id}" }
        pauseCards.keys.toList().filter { it !in active }.forEach { key -> rows.removeView(pauseCards.remove(key)) }
        requests.forEach { request ->
            val key = "request:${request.exchange.id}"
            if (key !in pauseCards) {
                val card = interceptionCard(request.exchange, request.exchange.request, false) { edited ->
                    if (edited == null) request.continueOriginal()
                    else request.continueWith(HttpMessageEditor.request(edited, request.exchange.request))
                }
                pauseCards[key] = card
                rows.addView(card, layout(top = 12))
                LiquidMotion.enter(card)
            }
        }
        responses.forEach { response ->
            val key = "response:${response.exchange.id}"
            if (key !in pauseCards) {
                val raw = response.exchange.responseHeaders + response.exchange.responseBody.toString(Charsets.ISO_8859_1)
                val card = interceptionCard(response.exchange, raw, true) { edited ->
                    if (edited == null) response.continueOriginal()
                    else response.continueWith(HttpMessageEditor.response(edited))
                }
                pauseCards[key] = card
                rows.addView(card, layout(top = 12))
                LiquidMotion.enter(card)
            }
        }
        queueLabel?.text = if (active.isEmpty()) "No paused traffic · Enable Intercept below" else
            "${requests.size} request(s) · ${responses.size} response(s) paused"
    }

    private fun interceptionCard(exchange: ProxyExchange, raw: String, response: Boolean, forward: (String?) -> Boolean): LinearLayout {
        val card = liquid.panel()
        LiquidMotion.watch(card)
        val summary = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
        summary.addView(chip(exchange.method, liquid.methodColor(exchange.method), colors().selected))
        summary.addView(text(if (response) "RESPONSE · PENDING" else "REQUEST · PENDING", 12, colors().gold).apply {
            setPadding(dp(8), 0, 0, 0)
        }, LinearLayout.LayoutParams(0, CONTENT, 1f))
        card.addView(summary, layout())
        card.addView(text(exchange.target, 13, colors().foreground).apply {
            setTextIsSelectable(true)
        }, layout(top = 8))
        val editor = EditText(this).apply {
            inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_FLAG_MULTI_LINE or android.text.InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
            liquid.editor(this)
            setText(raw)
            minLines = 6; maxLines = 12
            contentDescription = if (response) "Paused raw response editor" else "Paused raw request editor"
            LiquidMotion.watch(this)
            visibility = View.GONE
        }
        val initialEditorText = editor.text.toString()
        card.addView(editor, layout(top = 8))
        val error = text("", 12, colors().error).apply { visibility = View.GONE; accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE }
        card.addView(error, layout(top = 5))
        val edit = actionButton("Edit") {
            LiquidMotion.expand(editor, editor.visibility != View.VISIBLE)
        }
        val forwardButton = actionButton("Forward", primary = true) {
            try {
                val edited = editor.text.toString().takeIf { it != initialEditorText }
                if (forward(edited)) render()
                else {
                    error.text = "The message expired, was already forwarded, or contains invalid framing. Keep a valid HTTP/1.1 start line and headers."
                    error.visibility = View.VISIBLE
                    renderPaused()
                }
            } catch (problem: IllegalArgumentException) {
                error.text = problem.message ?: "Invalid message. Check the request line and headers."
                error.visibility = View.VISIBLE
            }
        }
        card.addView(actions(edit, forwardButton), layout(top = 12))
        return card
    }

    private fun sendRepeaterRequest() {
        repeaterCall?.let { call ->
            call.cancel()
            setRepeaterState("CANCEL REQUESTED", colors().gold)
            sendButton?.text = "Cancelling…"
            sendButton?.isEnabled = false
            return
        }
        val raw = try { HttpMessageEditor.request(repeaterEditor?.text?.toString().orEmpty()) }
        catch (error: IllegalArgumentException) {
            repeaterResult?.text = error.message
            setRepeaterState("FAILURE", colors().error)
            repeaterResponseStatus?.text = "Request validation failed · nothing sent"
            return
        }
        val call = RepeaterClient.newCall(raw)
        repeaterCall = call
        setRepeaterState("SENDING", colors().cyan)
        repeaterResponseStatus?.text = "Waiting for the target"
        sendButton?.text = "Cancel request"
        repeaterResult?.text = "Sending directly to target… Proxy and interception are not used."
        Thread {
            val response = runCatching { call.execute() }
            val result = if (call.isCancelled) "Request cancelled. You can send again."
                else response.getOrElse { "Request failed: ${it.message ?: "network error"}. Check the target and network; HTTPS certificate checks remain enabled. You can edit and send again." }
            main.post {
                if (!isDestroyed && repeaterCall === call) {
                    repeaterCall = null
                    repeaterResult?.text = result.take(64 * 1024) + if (result.length > 64 * 1024) "\n[Display truncated at 64 KiB]" else ""
                    val headline = if (response.isSuccess) responseHeadline(result) else ""
                    val httpError = headline.split(' ').getOrNull(1)?.toIntOrNull()?.let { it >= 400 } == true
                    val state = when {
                        call.isCancelled -> "CANCELLED" to colors().gold
                        response.isFailure || httpError -> "FAILURE" to colors().error
                        else -> "SUCCESS" to colors().success
                    }
                    setRepeaterState(state.first, state.second)
                    repeaterResponseStatus?.apply {
                        text = if (call.isCancelled) "Cancelled · ready for another send"
                            else if (response.isFailure) "Network or TLS failure · see details below"
                            else headline + if (result.contains("[Response capture truncated")) " · capture truncated" else ""
                        setTextColor(state.second)
                    }
                    sendButton?.text = "Send request"
                    sendButton?.isEnabled = true
                }
            }
        }.apply { isDaemon = true; start() }
    }

    private fun setRepeaterState(value: String, color: Int) {
        repeaterState?.apply { text = value; setTextColor(color); LiquidMotion.enter(this, LiquidMotion.STATUS) }
        repeaterResponseStatus?.setTextColor(color)
        sendButton?.let { liquid.styleButton(it, primary = repeaterCall == null, danger = repeaterCall != null) }
    }

    /** Read response-head metadata only, never interpret a status-like line in a body. */
    private fun responseHeadline(raw: String): String {
        var offset = 0
        repeat(9) {
            val end = raw.indexOf("\r\n", offset)
            if (end < 0) return "Response received"
            val status = raw.substring(offset, end)
            val code = status.split(' ').getOrNull(1)?.toIntOrNull() ?: return "Response received"
            if (code !in 100..199 || code == 101) return status
            val headEnd = raw.indexOf("\r\n\r\n", offset)
            if (headEnd < 0) return status
            offset = headEnd + 4
        }
        return "Response received"
    }

    private fun sendToRepeater(exchange: ProxyExchange) {
        if (exchange.method == "CONNECT" || exchange.request.isBlank()) {
            Toast.makeText(this, "Encrypted tunnels have no captured request. Enable CA-backed HTTPS inspection to capture replayable HTTPS requests.", Toast.LENGTH_LONG).show()
            return
        }
        repeaterEditor?.setText(exchange.request)
        select(Route.REPEATER)
    }

    private fun showResponseInBrowser(exchange: ProxyExchange) {
        if (browserDataReset != null) return
        val body = exchange.responseBody.toString(StandardCharsets.UTF_8)
        val safe = body.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
            .replace("\"", "&quot;").replace("'", "&#39;")
        val document = "<!doctype html><meta name='viewport' content='width=device-width'><meta charset='utf-8'><pre style='white-space:pre-wrap;word-break:break-word;font:14px monospace;color:${liquid.css(colors().foreground)}'>${safe}</pre>"
        select(Route.BROWSER)
        web?.loadDataWithBaseURL("about:blank", document, "text/html", "UTF-8", null)
        address?.setText("T4X-SUITE response preview · ${safeEndpoint(exchange.target)}")
    }

    private fun manageCa() {
        try {
            if (!caVault.exists()) caVault.create()
            caVault.exportCertificate()
            refreshCaState()
            AlertDialog.Builder(this).setTitle("CA certificate exported")
                .setMessage("Saved to Download/T4X-SUITE/T4X-SUITE-CA.crt. To install it, open Android Settings → Security & privacy → More security settings → Encryption & credentials → Install a certificate → CA certificate, then select the downloaded file. Menu names vary by device. Enable HTTPS inspection here only after installation.")
                .setPositiveButton("Got it", null).show()
            render()
        } catch (_: Exception) {
            Toast.makeText(this, "Could not export the CA certificate. Check available storage and try again.", Toast.LENGTH_LONG).show()
        }
    }

    private fun deleteCa() {
        AlertDialog.Builder(this).setTitle("Delete T4X-SUITE CA?")
            .setMessage("This removes T4X-SUITE's encrypted signing key. Android's installed user certificate and the exported file must be removed separately in Settings and Downloads.")
            .setNegativeButton("Cancel", null).setPositiveButton("Delete") { _, _ ->
                proxy.setTlsInspection(false)
                runCatching { caVault.delete() }
                refreshCaState()
                render()
            }.show()
    }


    private fun toggleTlsInspection() {
        if (proxy.tlsInspection) {
            proxy.setTlsInspection(false)
            return
        }
        try {
            if (!caVault.exists()) {
                Toast.makeText(this, "Generate and install the CA in the Proxy tab first.", Toast.LENGTH_LONG).show()
                return
            }
            AlertDialog.Builder(this).setTitle("Enable HTTPS inspection?")
                .setMessage("This decrypts HTTPS only inside T4X-SUITE's browser, after the CA is installed. Sites with pinning or unsupported TLS may fail. Continue only for authorized targets.")
                .setNegativeButton("Cancel", null).setPositiveButton("Enable") { _, _ ->
                    try { proxy.setTlsInspection(true, caVault.load()) }
                    catch (_: Exception) { Toast.makeText(this, "HTTPS inspection could not be enabled.", Toast.LENGTH_LONG).show() }
                    render()
                }.show()
        } catch (_: Exception) {
            Toast.makeText(this, "Generate and install the CA in the Proxy tab first.", Toast.LENGTH_LONG).show()
        }
    }

    private var shownTlsNotice = false
    private fun showTlsNoticeOnce() {
        if (shownTlsNotice) return
        shownTlsNotice = true
        main.post {
            Toast.makeText(this, if (proxy.tlsInspection) "HTTPS inspection is enabled for this browser. Certificate pinning and unsupported TLS may still fail." else "HTTPS is encrypted. T4X-SUITE records only the CONNECT host until you explicitly enable CA-backed inspection.", Toast.LENGTH_LONG).show()
        }
    }

    private fun render() {
        if (root == null) return
        val running = proxy.running
        statusLabel?.text = if (running) "Proxy active\n127.0.0.1:${proxy.port}" else "Proxy stopped"
        statusLabel?.setTextColor(if (running) colors().success else colors().secondary)
        if (lastRunning != running) {
            statusDot?.apply {
                background = round(if (running) colors().success else colors().muted, 5)
                LiquidMotion.enter(this, LiquidMotion.STATUS)
            }
            statusLabel?.let { LiquidMotion.enter(it, LiquidMotion.STATUS) }
            lastRunning = running
        }
        browserProxyStatus?.text = when {
            proxyReady -> "Proxy active"
            running -> "Routing browser…"
            else -> "Proxy stopped"
        }
        browserProxyStatus?.setTextColor(if (proxyReady) colors().success else if (running) colors().gold else colors().secondary)
        startButton?.text = if (running) "Stop proxy" else "Start proxy"
        startButton?.let { liquid.styleButton(it, primary = !running, danger = running) }
        bindToggle(interceptButton, "Request interception", proxy.interceptHttp)
        bindToggle(responseInterceptButton, "Response interception", proxy.interceptResponses)
        bindToggle(tlsButton, "HTTPS inspection", proxy.tlsInspection)
        val paused = proxy.pending().size + proxy.pendingResponses().size
        if (lastPausedCount != paused) {
            pendingLabel?.text = "$paused PAUSED"
            pendingLabel?.let { LiquidMotion.enter(it, LiquidMotion.STATUS) }
            proxyTab?.contentDescription = "Proxy, $paused paused messages"
            // Keep label width stable when traffic arrives; badge/counter carries the count.
            lastPausedCount = paused
        }
                caState?.apply {
            text = when {
                hasCa && caInstalled -> "CA stored & installed in Android"
                hasCa -> "CA stored locally · Not installed in Android Settings"
                else -> "No local CA · HTTPS inspection unavailable"
            }
            setTextColor(if (hasCa && caInstalled) colors().success else colors().gold)
        }
        caExportButton?.text = if (hasCa) "Export CA certificate" else "Generate CA"

        renderPaused()
        if (route == Route.HISTORY) renderHistory()
    }

    private fun bindToggle(button: TextView?, title: String, enabled: Boolean) {
        button?.apply {
            text = "$title · ${if (enabled) "On" else "Off"}"
            isSelected = enabled
            setTextColor(if (enabled) colors().accent else colors().foreground)
            background = liquid.buttonBackground(selected = enabled)
            contentDescription = "$title ${if (enabled) "on" else "off"}. Tap to change."
        }
    }

    private fun renderHistory() {
        val rows = historyRows ?: return
        val history = proxy.history()
        val ids = history.map { it.id }.toSet()
        historyCards.keys.toList().filter { it !in ids }.forEach { id -> rows.removeView(historyCards.remove(id)?.card) }
        historyCount?.text = "${history.size} / 500 exchanges · memory only"
        historyEmpty?.visibility = if (history.isEmpty()) View.VISIBLE else View.GONE
        (historyEmpty?.getChildAt(0) as? TextView)?.text = if (proxy.running) "Listening locally" else "No traffic yet"
        history.forEachIndexed { index, exchange ->
            val holder = historyCards.getOrPut(exchange.id) { HistoryCard(exchange) }
            if (holder.card.parent == null) rows.addView(holder.card, index, layout(top = 12))
            holder.bind()
        }
    }

    /** Stable timeline rows preserve expanded state and scroll positions on live updates. */
    private inner class HistoryCard(private val exchange: ProxyExchange) {
        val card = liquid.panel()
        private val method = chip(exchange.method, liquid.methodColor(exchange.method), colors().selected)
        private val status = chip("—", colors().secondary, colors().selected)
        private val host = text("", 16, colors().foreground).apply { setTextIsSelectable(true) }
        private val path = text("", 13, colors().secondary).apply { setTextIsSelectable(true) }
        private val meta = text("", 12, colors().secondary)
        private val details = text("", 12, colors().secondary).apply {
            typeface = Typeface.MONOSPACE
            setTextIsSelectable(true)
        }
        private val detailPanel = liquid.column().apply { visibility = View.GONE; LiquidMotion.watch(this) }
        private val preview = actionButton("View response") { showResponseInBrowser(exchange) }
        private var expanded = false
        private var signature: List<Any>? = null

        init {
            val badges = liquid.row()
            badges.addView(method, LinearLayout.LayoutParams(0, CONTENT, 1f))
            badges.addView(status, LinearLayout.LayoutParams(CONTENT, CONTENT).apply { marginStart = dp(8) })
            card.addView(badges)
            card.addView(host, layout(top = 10))
            card.addView(path, layout(top = 3))
            card.addView(meta, layout(top = 8))
            val expand = actionButton("Details +") { }
            expand.setOnClickListener {
                expanded = !expanded
                expand.text = if (expanded) "Hide details −" else "Details +"
                LiquidMotion.expand(detailPanel, expanded)
            }
            detailPanel.addView(details, layout())
            if (exchange.request.isNotBlank() && exchange.method != "CONNECT") {
                card.addView(actions(expand, actionButton("Send to Repeater") { sendToRepeater(exchange) }), layout(top = 10))
                detailPanel.addView(preview, layout(top = 8))
            } else if (exchange.method == "CONNECT") {
                card.addView(expand, layout(top = 10))
                card.addView(text("Encrypted tunnel · contents hidden. Enable CA-backed inspection to capture replayable HTTPS requests.", 12, colors().secondary), layout(top = 8))
            } else {
                card.addView(expand, layout(top = 10))
            }
            card.addView(detailPanel, layout(top = 8))
        }

        fun bind() {
            val current = listOf(exchange.method, exchange.target, exchange.state, exchange.status,
                exchange.elapsedMs, exchange.responseHeaders, exchange.responseBody.size, exchange.request.length)
            if (signature == current) return
            signature = current
            method.text = exchange.method
            method.setTextColor(liquid.methodColor(exchange.method))
            status.text = exchange.status.takeIf { it > 0 }?.toString() ?: "—"
            status.contentDescription = if (exchange.status > 0) "HTTP status ${exchange.status}" else "HTTP status not available"
            method.contentDescription = "HTTP method ${exchange.method}"
            status.setTextColor(if (exchange.status >= 400 || exchange.state == ExchangeState.FAILED) colors().error else colors().accent)
            val uri = runCatching { android.net.Uri.parse(exchange.target) }.getOrNull()
            host.text = if (exchange.method == "CONNECT") exchange.target else uri?.encodedAuthority ?: exchange.target
            path.text = if (exchange.method == "CONNECT") "CONNECT / encrypted HTTPS" else uri?.encodedPath.orEmpty().ifEmpty { "/" } + if (uri?.query != null) "?…" else ""
            val protocol = if (!exchange.isHttps) "HTTP" else if (exchange.method == "CONNECT") "TLS tunnel" else "HTTPS inspected"
            val timing = if (exchange.elapsedMs > 0) " · ${exchange.elapsedMs} ms" else ""
            meta.text = "${exchange.time} · $protocol\n${exchange.state.name.lowercase(java.util.Locale.ROOT)}$timing"
            val redacted = exchange.responseHeaders.lineSequence().take(9).joinToString("\n") { line ->
                if (line.substringBefore(':').trim().lowercase(java.util.Locale.ROOT) in setOf("set-cookie", "www-authenticate")) "${line.substringBefore(':')}: [hidden]" else line
            }
            details.text = buildString {
                append("Exchange #${exchange.id}\n${exchange.target}\n")
                if (exchange.request.isNotEmpty()) append("Retained request: ${exchange.request.toByteArray(Charsets.ISO_8859_1).size} B\n")
                if (exchange.responseHeaders.isNotEmpty()) append("Retained body: ${exchange.responseBody.size} B (not total wire size)\n${redacted.take(480)}")
                else append("No response headers retained.")
                if (exchange.state == ExchangeState.FAILED) append("\nExchange failed; no additional error detail retained.")
            }
            preview.visibility = if (exchange.responseBody.isEmpty()) View.GONE else View.VISIBLE
        }
    }

    private fun safeEndpoint(value: String): String = runCatching {
        val uri = android.net.Uri.parse(value)
        val path = uri.path?.take(120).orEmpty()
        "${uri.host.orEmpty()}$path${if (uri.query != null) "?…" else ""}"
    }.getOrDefault("[invalid target]")

    private fun colors() = liquid.colors

    private fun text(value: String, size: Int, color: Int) = liquid.text(value, size, color)

    private fun chip(value: String, foreground: Int, background: Int) = text(value, 12, foreground).apply {
        typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        letterSpacing = .06f
        setPadding(dp(9), dp(5), dp(9), dp(5))
        this.background = round(background, 10)
        LiquidMotion.watch(this)
    }

    private fun nav(value: String, icon: String, click: () -> Unit) = text(value, 12, colors().secondary).apply {
        gravity = Gravity.CENTER
        typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        minHeight = liquid.dim("touch_target")
        setPadding(dp(2), dp(8), dp(2), dp(8))
        val drawable = getDrawable(resources.getIdentifier("ic_liquid_$icon", "drawable", packageName))!!.mutate()
        drawable.setBounds(0, 0, dp(22), dp(22))
        setCompoundDrawables(null, drawable, null, null)
        compoundDrawablePadding = dp(4)
        liquid.dockItem(this)
        isFocusable = true
        contentDescription = "$value destination"
        LiquidMotion.press(this)
        setOnClickListener { click() }
    }

    private fun actionButton(value: String, primary: Boolean = false, danger: Boolean = false, click: () -> Unit) = liquid.button(value, primary, danger, click)

    private fun layout(top: Int = 0) = LinearLayout.LayoutParams(MATCH, CONTENT).apply { topMargin = dp(top) }
    private fun dp(value: Int) = (value * resources.displayMetrics.density + .5f).toInt()

    private fun round(color: Int, radius: Int, stroke: Int? = null) = liquid.shape(color, radius, stroke)

    private enum class Route { BROWSER, PROXY, HISTORY, REPEATER, ABOUT }

}
