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

        // Remembers each video's playback position (keyed by the video's own id, not the page)
        // and restores it when that video shows up again, e.g. after coming back from comments.
        const val VIDEO_RESUME_JS = """
            (function() {
                if (window.__fbVideoResume) return;
                window.__fbVideoResume = true;

                var PREFIX = '__vpos:';
                var MAX_AGE = 15 * 60 * 1000;
                var ID_PATTERNS = [
                    /[?&]v=(\d{6,})/,
                    /\/videos\/(?:[^\/?#]+\/)?(\d{6,})/,
                    /\/reel\/(\d{6,})/,
                    /[?&]video_id=(\d{6,})/
                ];

                // Drop expired entries from earlier visits.
                try {
                    for (var i = sessionStorage.length - 1; i >= 0; i--) {
                        var k = sessionStorage.key(i);
                        if (k && k.indexOf(PREFIX) === 0) {
                            var o = JSON.parse(sessionStorage.getItem(k) || 'null');
                            if (!o || (Date.now() - o.at) > MAX_AGE) sessionStorage.removeItem(k);
                        }
                    }
                } catch (e) {}

                // Finds this video's own id (not the page's), so every video in a feed gets its own slot.
                function findId(v) {
                    var el = v.parentElement, depth = 0;
                    while (el && el !== document.body && depth < 25) {
                        // Once the container holds more than one video it is no longer "this" video's post.
                        if (el.querySelectorAll('video').length > 1) break;
                        var store = el.getAttribute && el.getAttribute('data-store');
                        if (store) {
                            var m = /"videoID"\s*:\s*"?(\d+)/.exec(store);
                            if (m) return 'v' + m[1];
                        }
                        var links = el.querySelectorAll('a[href]');
                        for (var a = 0; a < links.length; a++) {
                            var h = links[a].getAttribute('href') || '';
                            for (var p = 0; p < ID_PATTERNS.length; p++) {
                                var mm = ID_PATTERNS[p].exec(h);
                                if (mm) return 'v' + mm[1];
                            }
                        }
                        el = el.parentElement;
                        depth++;
                    }
                    return null;
                }

                function indexOfVideo(v) {
                    return Array.prototype.indexOf.call(document.querySelectorAll('video'), v);
                }

                function hook(v) {
                    if (v.__fbResumeHooked) return;
                    v.__fbResumeHooked = true;

                    var id = null, strong = false;
                    function key() {
                        if (!strong) {
                            var found = findId(v);
                            if (found) { id = found; strong = true; }
                            else { id = 'p' + location.pathname + location.search + '#' + indexOfVideo(v); }
                        }
                        return PREFIX + id;
                    }

                    var restoring = true;   // no saving until we have decided whether to restore
                    var decided = false;
                    var target = null;
                    var attempts = 0;

                    function finish() { restoring = false; target = null; }

                    function decide() {
                        if (decided) return;
                        if (!v.duration || !isFinite(v.duration)) return;
                        decided = true;
                        var saved = null;
                        try { saved = JSON.parse(sessionStorage.getItem(key()) || 'null'); } catch (e) {}
                        if (saved && saved.t > 2 &&
                            (Date.now() - saved.at) < MAX_AGE &&
                            saved.t < v.duration - 1 &&
                            Math.abs((saved.d || 0) - v.duration) < 1.5) {   // same video? lengths must match
                            target = saved.t;
                            setTimeout(finish, 6000);   // safety net: stop fighting the player after 6s
                        } else {
                            finish();
                        }
                    }

                    function apply() {
                        decide();
                        if (target === null) return;
                        if (Math.abs(v.currentTime - target) > 1.5) {
                            if (attempts < 8) {
                                attempts++;
                                try { v.currentTime = target; } catch (e) {}
                            } else {
                                finish();
                            }
                        } else {
                            finish();
                        }
                    }

                    ['loadedmetadata', 'durationchange', 'canplay', 'playing', 'seeked'].forEach(function(ev) {
                        v.addEventListener(ev, apply);
                    });
                    if (v.readyState >= 1) apply();

                    v.addEventListener('timeupdate', function() {
                        if (restoring) return;
                        if (!v.duration || !isFinite(v.duration)) return;
                        try {
                            var t = v.currentTime;
                            if (t > v.duration - 1) {
                                sessionStorage.removeItem(key());
                            } else if (t > 1) {
                                sessionStorage.setItem(key(), JSON.stringify({ t: t, d: v.duration, at: Date.now() }));
                            }
                        } catch (e) {}
                    });

                    v.addEventListener('ended', function() {
                        try { sessionStorage.removeItem(key()); } catch (e) {}
                    });
                }

                setInterval(function() {
                    document.querySelectorAll('video').forEach(hook);
                }, 500);
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

        // Keep login sessions saved
        CookieManager.getInstance().setAcceptCookie(true)
        CookieManager.getInstance().setAcceptThirdPartyCookies(webView, true)

        webView.webViewClient = object : WebViewClient() {
            override fun onPageFinished(view: WebView, url: String?) {
                super.onPageFinished(view, url)
                // Commit the login/session cookie to disk so it survives a background process kill.
                CookieManager.getInstance().flush()
                view.evaluateJavascript(VIDEO_RESUME_JS, null)
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

    private fun enableDarkMode() {
        webView.setBackgroundColor(Color.parseColor("#121212"))
        if (Build.VERSION.SDK_INT >= 33) {
            webView.settings.isAlgorithmicDarkeningAllowed = true
        } else if (Build.VERSION.SDK_INT >= 29) {
            @Suppress("DEPRECATION")
            webView.settings.forceDark = WebSettings.FORCE_DARK_ON
        }
    }

    private fun applyStatusBarColor() {
        val color = Color.parseColor("#121212")

        window.statusBarColor = color
        window.navigationBarColor = color

        WindowCompat.getInsetsController(window, window.decorView).apply {
            isAppearanceLightStatusBars = false
            isAppearanceLightNavigationBars = false
        }
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
