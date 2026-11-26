package com.fbapp.webview

import android.Manifest
import android.annotation.SuppressLint
import android.app.DownloadManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.database.Cursor
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.Settings
import android.util.Base64
import android.webkit.JavascriptInterface
import android.webkit.URLUtil
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.webkit.CookieManager
import android.widget.Toast
import java.io.File
import java.io.FileOutputStream
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.getSystemService
import androidx.core.view.WindowCompat
import androidx.webkit.WebSettingsCompat
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature

class MainActivity : AppCompatActivity() {

    private lateinit var webView: WebView
    private val fileChooserRequestCode = 200
    private var filePathCallback: ValueCallback<Array<Uri>>? = null

    /**
     * Notifies the user once a DownloadManager-enqueued file has actually finished
     * writing to disk (enqueue() only means "queued", not "done"), showing the real
     * saved file name so it's clear whether it succeeded or failed.
     */
    private val downloadCompleteReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val id = intent.getLongExtra(DownloadManager.EXTRA_DOWNLOAD_ID, -1L)
            if (id == -1L) return

            val downloadManager = getSystemService<DownloadManager>() ?: return
            val query = DownloadManager.Query().setFilterById(id)
            val cursor: Cursor = downloadManager.query(query)

            cursor.use {
                if (it.moveToFirst()) {
                    val statusIdx = it.getColumnIndex(DownloadManager.COLUMN_STATUS)
                    val uriIdx = it.getColumnIndex(DownloadManager.COLUMN_LOCAL_URI)
                    val status = if (statusIdx != -1) it.getInt(statusIdx) else -1
                    val localUri = if (uriIdx != -1) it.getString(uriIdx) else null

                    if (status == DownloadManager.STATUS_SUCCESSFUL) {
                        val savedName = localUri?.let { uriStr -> Uri.parse(uriStr).lastPathSegment } ?: "file"
                        Toast.makeText(context, "Downloaded", Toast.LENGTH_SHORT).show()
                    } else {
                        Toast.makeText(context, "Download failed", Toast.LENGTH_LONG).show()
                    }
                }
            }
        }
    }

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { /* if denied, the relevant action (upload/download) just won't work until retried */ }

    /**
     * DownloadManager can only fetch http/https URLs, but some pages generate
     * blob: URLs for exports/media. This bridge lets injected JS hand the blob's
     * base64 content straight to Kotlin so it can be written to Downloads directly.
     */
    private inner class BlobDownloader {
        @JavascriptInterface
        fun saveBase64File(base64Data: String, fileName: String) {
            try {
                val commaIndex = base64Data.indexOf(",")
                val pureBase64 = if (commaIndex != -1) base64Data.substring(commaIndex + 1) else base64Data
                val bytes = Base64.decode(pureBase64, Base64.DEFAULT)

                val downloadsDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
                val outFile = File(downloadsDir, fileName)

                FileOutputStream(outFile).use { it.write(bytes) }

                runOnUiThread {
                    Toast.makeText(this@MainActivity, "Downloaded", Toast.LENGTH_SHORT).show()
                }
            } catch (e: Exception) {
                runOnUiThread {
                    Toast.makeText(this@MainActivity, "Download failed: ${e.message}", Toast.LENGTH_SHORT).show()
                }
            }
        }

        @JavascriptInterface
        fun reportError(message: String) {
            runOnUiThread {
                Toast.makeText(this@MainActivity, "Save error: $message", Toast.LENGTH_LONG).show()
            }
        }
    }

    private companion object {
        // Presses Escape, and if a menu is still open taps the empty area outside it.
        const val DISMISS_MENU_JS = """
            (function() {
                function esc(type) {
                    return new KeyboardEvent(type, {
                        key: 'Escape', code: 'Escape', keyCode: 27, which: 27,
                        bubbles: true, cancelable: true
                    });
                }
                [document.activeElement, document.body, document].forEach(function(t) {
                    if (t) { t.dispatchEvent(esc('keydown')); t.dispatchEvent(esc('keyup')); }
                });
                setTimeout(function() {
                    if (document.querySelector('[role="menu"]')) {
                        var el = document.elementFromPoint(6, Math.round(window.innerHeight * 0.3));
                        if (el && !el.closest('[role="menu"]')) { el.click(); }
                    }
                }, 120);
            })();
        """

        // Removes the blue tap/selection flash that WebView draws when something is tapped
        // (most visible on the full-screen photo viewer).
        const val NO_TAP_HIGHLIGHT_JS = """
            (function() {
                var nonText = 'a,a *,button,button *,[role="button"],[role="button"] *,[role="link"],[role="link"] *,' +
                              '[role="menuitem"],[role="menuitem"] *,[role="tab"],[role="tab"] *,[role="img"],[role="img"] *,' +
                              '[role="dialog"] [role="presentation"],[tabindex],img,video,canvas,svg,svg *,i';
                var css = '*{-webkit-tap-highlight-color:transparent !important;}' +
                          '*:focus,*:focus-visible{outline:none !important;}' +
                          nonText + '{-webkit-user-select:none !important;user-select:none !important;' +
                          '-webkit-tap-highlight-color:transparent !important;}' +
                          nonText.split(',').map(function(x){return x+'::selection';}).join(',') +
                          '{background:transparent !important;}';
                function add() {
                    if (document.getElementById('__noTapHl')) return;
                    var st = document.createElement('style');
                    st.id = '__noTapHl';
                    st.textContent = css;
                    (document.head || document.documentElement).appendChild(st);
                }
                add();
                if (!window.__noTapHlObs) {
                    window.__noTapHlObs = new MutationObserver(add);
                    window.__noTapHlObs.observe(document.documentElement, { childList: true, subtree: false });
                }
            })();
        """

        // Tells the app whether a big (reel / watch style) video fills the screen.
        const val BAR_MODE_JS = """
            (function() {
                if (window.__fbBarMode) return;
                window.__fbBarMode = true;
                var last = null;
                function bigVideoShown() {
                    var vids = document.querySelectorAll('video');
                    for (var i = 0; i < vids.length; i++) {
                        var r = vids[i].getBoundingClientRect();
                        var visibleH = Math.min(r.bottom, window.innerHeight) - Math.max(r.top, 0);
                        if (visibleH > window.innerHeight * 0.75 && r.width > window.innerWidth * 0.5) return true;
                    }
                    return false;
                }
                function check() {
                    var on = bigVideoShown();
                    if (on !== last) {
                        last = on;
                        try { AndroidBars.setVideoMode(on); } catch (e) {}
                    }
                }
                setInterval(check, 400);
                check();
            })();
        """
    }

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        applyStatusBarColor()
        requestNeededPermissions()

        registerDownloadCompleteReceiver()

        webView = WebView(this)
        setContentView(webView)

        val settings: WebSettings = webView.settings
        settings.javaScriptEnabled = true
        settings.domStorageEnabled = true
        settings.databaseEnabled = true
        settings.loadWithOverviewMode = true
        settings.useWideViewPort = true
        settings.setSupportZoom(true)
        settings.builtInZoomControls = true
        settings.displayZoomControls = false
        settings.cacheMode = WebSettings.LOAD_DEFAULT
        settings.javaScriptCanOpenWindowsAutomatically = true
        settings.setSupportMultipleWindows(true)

        // Force dark mode for the page content
        enableDarkMode()
        warnIfWebViewOutdated()

        // Keep login sessions saved
        CookieManager.getInstance().setAcceptCookie(true)
        CookieManager.getInstance().setAcceptThirdPartyCookies(webView, true)

        webView.webViewClient = object : WebViewClient() {
            // m.facebook.com changes pages without a real load (pushState), so
            // shouldOverrideUrlLoading doesn't always fire. Catch the Messages page here.
            override fun doUpdateVisitedHistory(view: WebView, url: String?, isReload: Boolean) {
                super.doUpdateVisitedHistory(view, url, isReload)
                if (url != null && isMessengerUrl(Uri.parse(url))) {
                    handleExternalLink(url)
                    view.post { if (view.canGoBack()) view.goBack() }
                }
            }

            override fun onPageCommitVisible(view: WebView, url: String?) {
                super.onPageCommitVisible(view, url)
                view.evaluateJavascript(NO_TAP_HIGHLIGHT_JS, null)
            }

            override fun onPageFinished(view: WebView, url: String?) {
                super.onPageFinished(view, url)
                // Commit the login/session cookie to disk so it survives a background process kill.
                CookieManager.getInstance().flush()
                view.evaluateJavascript(BAR_MODE_JS, null)
                view.evaluateJavascript(NO_TAP_HIGHLIGHT_JS, null)
            }

            // Non-Facebook links open in the phone's browser, Facebook links stay in the app.
            override fun shouldOverrideUrlLoading(
                view: WebView,
                request: android.webkit.WebResourceRequest
            ): Boolean {
                return handleExternalLink(request.url.toString())
            }
        }

        webView.addJavascriptInterface(BlobDownloader(), "AndroidDownloader")
        webView.addJavascriptInterface(BarBridge(), "AndroidBars")

        webView.webChromeClient = object : WebChromeClient() {
            override fun onShowFileChooser(
                webView: WebView?,
                callback: ValueCallback<Array<Uri>>?,
                fileChooserParams: FileChooserParams?
            ): Boolean {
                filePathCallback?.onReceiveValue(null)
                filePathCallback = callback

                // Always offer both images and videos, regardless of what the page asked for.
                val intent = Intent(Intent.ACTION_GET_CONTENT).apply {
                    type = "*/*"
                    putExtra(Intent.EXTRA_MIME_TYPES, arrayOf("image/*", "video/*"))
                    addCategory(Intent.CATEGORY_OPENABLE)
                    if (fileChooserParams?.mode == FileChooserParams.MODE_OPEN_MULTIPLE) {
                        putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true)
                    }
                }

                try {
                    startActivityForResult(
                        Intent.createChooser(intent, "Select Photo or Video"),
                        fileChooserRequestCode
                    )
                } catch (e: Exception) {
                    filePathCallback = null
                    Toast.makeText(this@MainActivity, "File chooser open kora jai ni", Toast.LENGTH_SHORT).show()
                    return false
                }
                return true
            }

            // Some "Save" flows open a popup (window.open / target="_blank") instead of
            // navigating the main WebView. Without this, WebView silently blocks the popup
            // and nothing happens when the button is tapped.
            override fun onCreateWindow(
                view: WebView?,
                isDialog: Boolean,
                isUserGesture: Boolean,
                resultMsg: android.os.Message?
            ): Boolean {
                val popup = WebView(this@MainActivity)
                popup.settings.javaScriptEnabled = true
                popup.settings.domStorageEnabled = true
                popup.addJavascriptInterface(BlobDownloader(), "AndroidDownloader")

                // Reuse the same download handling for anything the popup tries to save.
                popup.setDownloadListener { url, _, contentDisposition, mimeType, _ ->
                    handleDownload(popup, url, contentDisposition, mimeType)
                }

                // The popup never needs to be shown on screen; it only exists to let the
                // page's JS run (fetch/blob/save logic) and hand the result back to us.
                popup.webViewClient = object : WebViewClient() {
                    private var handled = false

                    private fun interceptExternal(view: WebView, url: String?): Boolean {
                        if (url == null || handled) return false
                        if (handleExternalLink(url)) {
                            handled = true
                            view.stopLoading()
                            view.post { view.destroy() }
                            return true
                        }
                        return false
                    }

                    override fun shouldOverrideUrlLoading(
                        view: WebView,
                        request: android.webkit.WebResourceRequest
                    ): Boolean = interceptExternal(view, request.url.toString())

                    override fun onPageStarted(view: WebView, url: String?, favicon: android.graphics.Bitmap?) {
                        super.onPageStarted(view, url, favicon)
                        interceptExternal(view, url)
                    }
                }

                val transport = resultMsg?.obj as? WebView.WebViewTransport
                transport?.webView = popup
                resultMsg?.sendToTarget()
                return true
            }

            // Logs JS errors to Logcat for debugging, without spamming the user with
            // Toasts — most console errors on facebook.com are noise from FB's own
            // scripts (storage access, permissions policy, etc.) and aren't real
            // app problems.
            override fun onConsoleMessage(consoleMessage: android.webkit.ConsoleMessage?): Boolean {
                consoleMessage?.let {
                    if (it.messageLevel() == android.webkit.ConsoleMessage.MessageLevel.ERROR) {
                        android.util.Log.e(
                            "WebViewJS",
                            "${it.message()} (${it.sourceId()}:${it.lineNumber()})"
                        )
                    }
                }
                return true
            }
        }

        // Handles files that the page pushes out for download (media, documents, etc.)
        webView.setDownloadListener { url, _, contentDisposition, mimeType, _ ->
            handleDownload(webView, url, contentDisposition, mimeType)
        }

        if (savedInstanceState != null) {
            webView.restoreState(savedInstanceState)
        } else {
            webView.loadUrl(startUrlFrom(intent))
        }

        promptOpenByDefaultOnce()
    }

    /**
     * Android 12+ does not let an app open facebook.com links by itself; the user has to
     * switch on "Open supported links" and tick the links once. On first launch we take
     * them straight to that settings screen.
     */
    private fun promptOpenByDefaultOnce() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return
        val prefs = getSharedPreferences("app_prefs", Context.MODE_PRIVATE)
        if (prefs.getBoolean("open_by_default_prompted", false)) return
        prefs.edit().putBoolean("open_by_default_prompted", true).apply()

        webView.postDelayed({
            try {
                Toast.makeText(
                    this,
                    "Open supported links chalu kore Add link e sob link tick daw",
                    Toast.LENGTH_LONG
                ).show()
                startActivity(
                    Intent(
                        Settings.ACTION_APP_OPEN_BY_DEFAULT_SETTINGS,
                        Uri.parse("package:$packageName")
                    )
                )
            } catch (e: Exception) {
                try {
                    startActivity(
                        Intent(
                            Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                            Uri.parse("package:$packageName")
                        )
                    )
                } catch (_: Exception) { }
            }
        }, 2500)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        linkFrom(intent)?.let { webView.loadUrl(it) }
    }

    /** Link coming from another app: a tapped link (VIEW) or a shared one (SEND). */
    private fun linkFrom(intent: Intent?): String? {
        var link: Uri? = null

        if (intent?.action == Intent.ACTION_VIEW) {
            link = intent.data
        } else if (intent?.action == Intent.ACTION_SEND) {
            val text = intent.getStringExtra(Intent.EXTRA_TEXT)
            val found = text?.let { Regex("https?://\\S+").find(it)?.value }
            link = found?.let { Uri.parse(it) }
        }

        if (link == null || (link.scheme != "http" && link.scheme != "https")) return null

        val host = link.host?.lowercase()
        return if (host == "www.facebook.com" || host == "facebook.com" || host == "web.facebook.com") {
            link.buildUpon().scheme("https").authority("m.facebook.com").build().toString()
        } else {
            link.buildUpon().scheme("https").build().toString()
        }
    }

    private fun startUrlFrom(intent: Intent?): String =
        linkFrom(intent) ?: "https://m.facebook.com"

    private fun isInternalHost(host: String?): Boolean {
        if (host == null) return false
        val h = host.lowercase()
        val domains = listOf(
            "facebook.com", "fb.com", "fb.me", "fb.watch",
            "fbcdn.net", "fbsbx.com", "facebook.net", "messenger.com", "m.me"
        )
        return domains.any { h == it || h.endsWith(".$it") }
    }

    /** Returns true if the link was handed to the phone's browser/app (so the WebView must not load it). */
    private fun handleExternalLink(url: String): Boolean {
        val uri = Uri.parse(url)
        val scheme = uri.scheme?.lowercase() ?: return false
        if (scheme in listOf("about", "blob", "data", "javascript", "file")) return false

        // Message button / "Go to Messenger" -> open the Messenger app (or Play Store)
        if (isMessengerUrl(uri)) {
            openMessenger(uri)
            return true
        }

        if (scheme == "http" || scheme == "https") {
            val host = uri.host?.lowercase()
            // Facebook wraps outgoing links as l.facebook.com/l.php?u=<real link>
            if ((host == "l.facebook.com" || host == "lm.facebook.com" || host == "l.messenger.com") &&
                uri.path?.startsWith("/l.php") == true
            ) {
                val target = uri.getQueryParameter("u")
                if (!target.isNullOrEmpty()) {
                    openInBrowser(target)
                    return true
                }
                return false
            }
            if (isInternalHost(host)) return false
        }

        openInBrowser(url)
        return true
    }

    private val messengerPackage = "com.facebook.orca"

    /** True for any link that means "go to Messenger". */
    private fun isMessengerUrl(uri: Uri): Boolean {
        val scheme = uri.scheme?.lowercase() ?: return false
        if (scheme.startsWith("fb-messenger")) return true
        if (scheme == "intent") {
            val s = uri.toString()
            return s.contains(messengerPackage) || s.contains("fb-messenger")
        }
        if (scheme != "http" && scheme != "https") return false
        val host = uri.host?.lowercase() ?: return false
        val path = uri.path ?: ""
        if (path.startsWith("/l.php")) return false // outgoing-link wrapper, handled elsewhere
        if (host == "m.me" || host.endsWith(".m.me")) return true
        if (host == "messenger.com" || host.endsWith(".messenger.com")) return true
        if (host == "facebook.com" || host.endsWith(".facebook.com")) {
            return path == "/messages" || path.startsWith("/messages/") || path.startsWith("/messenger")
        }
        return false
    }

    /** Opens Messenger (straight to the chat if we know the user id); Play Store if not installed. */
    private fun openMessenger(uri: Uri) {
        val path = uri.path ?: ""
        val threadId = Regex("/messages/(?:t|thread)/(\\d+)").find(path)?.groupValues?.get(1)
        val host = uri.host?.lowercase()

        val deepLink: Uri? = when {
            host == "m.me" -> uri
            threadId != null -> Uri.parse("https://m.me/$threadId")
            else -> null
        }

        // 1) Open the specific chat in Messenger
        if (deepLink != null) {
            try {
                startActivity(Intent(Intent.ACTION_VIEW, deepLink).setPackage(messengerPackage))
                return
            } catch (e: Exception) { /* try next */ }
        }
        // 2) Just launch Messenger
        try {
            val launch = packageManager.getLaunchIntentForPackage(messengerPackage)
            if (launch != null) {
                startActivity(launch)
                return
            }
        } catch (e: Exception) { /* try next */ }
        // 3) Not installed -> Play Store
        openMessengerInPlayStore()
    }

    private fun openMessengerInPlayStore() {
        try {
            startActivity(
                Intent(Intent.ACTION_VIEW, Uri.parse("market://details?id=$messengerPackage"))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        } catch (e: Exception) {
            openInBrowser("https://play.google.com/store/apps/details?id=$messengerPackage")
        }
    }

    private fun openInBrowser(url: String) {
        try {
            startActivity(
                Intent(Intent.ACTION_VIEW, Uri.parse(url)).addCategory(Intent.CATEGORY_BROWSABLE)
            )
        } catch (e: Exception) {
            Toast.makeText(this, "Link open kora jai ni", Toast.LENGTH_SHORT).show()
        }
    }

    /** Closes the open Facebook popup menu (Save / report...) in the main WebView. */
    private fun dismissFacebookMenu() {
        webView.post {
            webView.evaluateJavascript(DISMISS_MENU_JS, null)
        }
    }

    /** Shared download logic used by both the main WebView and any popup WebView. */
    private fun handleDownload(source: WebView, url: String, contentDisposition: String?, mimeType: String?) {
        val fileName = URLUtil.guessFileName(url, contentDisposition, mimeType)

        // Close Facebook's "Save / I don't want to see this" menu right away.
        dismissFacebookMenu()

        if (url.startsWith("blob:")) {
            val isImage = mimeType?.startsWith("image") == true ||
                fileName.substringAfterLast('.', "").lowercase() in
                    listOf("jpg", "jpeg", "png", "webp", "gif")

            val js = if (isImage) {
                // Some pages (e.g. Facebook) set a CSP that blocks XHR/fetch to blob: URLs
                // (connect-src). Loading it into an <img> and reading it back via <canvas>
                // is governed by img-src instead, which is usually permitted, so this
                // sidesteps the CSP block.
                """
                (function() {
                    try {
                        var img = new Image();
                        img.onload = function() {
                            try {
                                var canvas = document.createElement('canvas');
                                canvas.width = img.naturalWidth;
                                canvas.height = img.naturalHeight;
                                var ctx = canvas.getContext('2d');
                                ctx.drawImage(img, 0, 0);
                                var dataUrl = canvas.toDataURL('image/jpeg', 0.95);
                                AndroidDownloader.saveBase64File(dataUrl, '$fileName');
                            } catch (e) {
                                AndroidDownloader.reportError('canvas: ' + e.message);
                            }
                        };
                        img.onerror = function() {
                            AndroidDownloader.reportError('image load failed for blob');
                        };
                        img.src = '$url';
                    } catch (e) {
                        AndroidDownloader.reportError('img setup: ' + e.message);
                    }
                })();
                """.trimIndent()
            } else {
                // Non-image blobs (video, documents) - try the direct XHR read.
                // This can still fail under a strict CSP; reportError will surface why.
                """
                (function() {
                    try {
                        var xhr = new XMLHttpRequest();
                        xhr.open('GET', '$url', true);
                        xhr.responseType = 'blob';
                        xhr.onload = function() {
                            var reader = new FileReader();
                            reader.onloadend = function() {
                                AndroidDownloader.saveBase64File(reader.result, '$fileName');
                            };
                            reader.readAsDataURL(xhr.response);
                        };
                        xhr.onerror = function() {
                            AndroidDownloader.reportError('XHR failed to read blob (possible CSP block)');
                        };
                        xhr.send();
                    } catch (e) {
                        AndroidDownloader.reportError('xhr setup: ' + e.message);
                    }
                })();
                """.trimIndent()
            }
            source.evaluateJavascript(js, null)
            return
        }

        try {
            val cookie = CookieManager.getInstance().getCookie(url) ?: ""

            val request = DownloadManager.Request(Uri.parse(url)).apply {
                addRequestHeader("cookie", cookie)
                setMimeType(mimeType)
                setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
                setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, fileName)
            }

            getSystemService<DownloadManager>()?.enqueue(request)
        } catch (e: Exception) {
            Toast.makeText(this, "Download failed: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }

    private fun registerDownloadCompleteReceiver() {
        val filter = IntentFilter(DownloadManager.ACTION_DOWNLOAD_COMPLETE)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(downloadCompleteReceiver, filter, Context.RECEIVER_EXPORTED)
        } else {
            registerReceiver(downloadCompleteReceiver, filter)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        try {
            unregisterReceiver(downloadCompleteReceiver)
        } catch (e: IllegalArgumentException) {
            // Receiver was already unregistered or never registered; safe to ignore.
        }
    }

    /** Asks for the storage/media/notification permissions needed for upload & download. */
    private fun requestNeededPermissions() {
        val needed = mutableListOf<String>()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            needed += Manifest.permission.READ_MEDIA_IMAGES
            needed += Manifest.permission.READ_MEDIA_VIDEO
            needed += Manifest.permission.READ_MEDIA_AUDIO
        } else {
            needed += Manifest.permission.READ_EXTERNAL_STORAGE
            if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.P) {
                needed += Manifest.permission.WRITE_EXTERNAL_STORAGE
            }
        }

        val notGranted = needed.filter {
            checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED
        }

        if (notGranted.isNotEmpty()) {
            permissionLauncher.launch(notGranted.toTypedArray())
        }
    }

    @Suppress("DEPRECATION")
    private fun enableDarkMode() {
        webView.setBackgroundColor(Color.parseColor("#121212"))
        // Uses the WebView's own dark-mode support, so it works on every Android version
        // (including Android 9, where the platform forceDark setting doesn't exist).
        try {
            when {
                WebViewFeature.isFeatureSupported(WebViewFeature.ALGORITHMIC_DARKENING) ->
                    WebSettingsCompat.setAlgorithmicDarkeningAllowed(webView.settings, true)
                WebViewFeature.isFeatureSupported(WebViewFeature.FORCE_DARK) ->
                    WebSettingsCompat.setForceDark(webView.settings, WebSettingsCompat.FORCE_DARK_ON)
            }
        } catch (e: Exception) { /* page just stays in its own theme */ }
    }

    /** Old phones (e.g. Android 9) often have an outdated WebView, which Facebook's site needs to be recent. */
    private fun warnIfWebViewOutdated() {
        try {
            val major = WebViewCompat.getCurrentWebViewPackage(this)
                ?.versionName?.substringBefore('.')?.toIntOrNull() ?: return
            if (major < 100) {
                Toast.makeText(
                    this,
                    "Please update \"Android System WebView\" from Play Store for the best Facebook experience",
                    Toast.LENGTH_LONG
                ).show()
            }
        } catch (e: Exception) { }
    }

    private fun applyStatusBarColor() {
        val color = Color.parseColor("#121212")

        window.statusBarColor = color
        window.navigationBarColor = color
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            window.navigationBarDividerColor = color
        }

        WindowCompat.getInsetsController(window, window.decorView).apply {
            isAppearanceLightStatusBars = false
            isAppearanceLightNavigationBars = false
        }
    }

    /** Lets the page tell us when a big video is on screen, so the bars can be recoloured. */
    private inner class BarBridge {
        @JavascriptInterface
        fun setVideoMode(on: Boolean) {
            runOnUiThread { applyBarColors(on) }
        }
    }

    // Normal pages: both bars #121212. Big video on screen: status bar matches Facebook's
    // dark top bar (#242527) and the navigation bar is pure black.
    private fun applyBarColors(videoMode: Boolean) {
        val base = Color.parseColor("#121212")
        window.statusBarColor = if (videoMode) Color.parseColor("#242527") else base
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            window.isNavigationBarContrastEnforced = false
        }
        window.navigationBarColor = if (videoMode) Color.BLACK else base
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        if (requestCode == fileChooserRequestCode) {
            var results: Array<Uri>? = null
            if (resultCode == RESULT_OK && data != null) {
                val dataUri = data.data
                val clipData = data.clipData
                if (clipData != null) {
                    results = Array(clipData.itemCount) { i -> clipData.getItemAt(i).uri }
                } else if (dataUri != null) {
                    results = arrayOf(dataUri)
                }
            }
            filePathCallback?.onReceiveValue(results)
            filePathCallback = null
            return
        }
        super.onActivityResult(requestCode, resultCode, data)
    }

    override fun onPause() {
        super.onPause()
        // Facebook uses this login session; commit it before we background for the file picker.
        CookieManager.getInstance().flush()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        webView.saveState(outState)
    }

    override fun onBackPressed() {
        if (webView.canGoBack()) {
            webView.goBack()
        } else {
            super.onBackPressed()
        }
    }
}
