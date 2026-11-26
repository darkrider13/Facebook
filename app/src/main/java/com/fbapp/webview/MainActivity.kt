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
import android.animation.ObjectAnimator
import android.animation.ValueAnimator
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.animation.LinearInterpolator
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
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
    private var lastNonMessengerUrl: String? = null
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
        // TEMPORARY: shows a small toast telling which path opened Messenger. Set to false once tested.
        const val DEBUG_MESSENGER = false

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

        // Catches taps on the Messenger tab / "Message" button the instant the finger lifts
        // (pointer, touch and click), BEFORE Facebook starts loading anything, and hands the
        // tap to Kotlin so Messenger opens straight away.
        const val MESSENGER_HOOK_JS = """
            (function() {
                if (window.__fbxMsgHook2) return;
                window.__fbxMsgHook2 = true;
                var sx = 0, sy = 0, hasStart = false, lastFire = 0;

                function isMsgHref(h) {
                    if (!h) return false;
                    return /^(https?:\/\/[^\/]*(facebook|messenger)\.com)?\/(messages|messenger)(\/|\?|#|$)/i.test(h) ||
                           /^https?:\/\/(m\.me|(www\.)?messenger\.com)\//i.test(h) ||
                           /^(fb-messenger|intent)/i.test(h);
                }

                function match(t) {
                    if (t.closest('input,textarea,[contenteditable="true"],[role="textbox"]')) return null;
                    var a = t.closest('a[href]');
                    var href = a ? (a.getAttribute('href') || '') : '';
                    if (isMsgHref(href)) return { href: href };
                    var el = t, hops = 0;
                    while (el && el !== document.body && hops < 8) {
                        var lab = ((el.getAttribute && el.getAttribute('aria-label')) || '').trim().toLowerCase();
                        if (lab === 'message' || lab === 'messenger' || lab === 'go to messenger' ||
                            lab.indexOf('messenger') === 0 || lab.indexOf('messages') === 0) return { href: '' };
                        var txt = (el.innerText || '').trim().toLowerCase();
                        if (txt === 'message' || txt === 'messenger') return { href: '' };
                        if (txt.length > 0) break;   // reached a bigger container with other text
                        el = el.parentElement; hops++;
                    }
                    return null;
                }

                // ---- numeric id of a profile (lets Messenger open the chat instantly) ----
                var RESERVED = { '': 1, 'home.php': 1, 'messages': 1, 'messenger': 1, 'watch': 1, 'reel': 1, 'reels': 1,
                    'groups': 1, 'marketplace': 1, 'stories': 1, 'events': 1, 'pages': 1, 'friends': 1, 'notifications': 1,
                    'bookmarks': 1, 'settings': 1, 'menu': 1, 'login': 1, 'login.php': 1, 'search': 1, 'hashtag': 1,
                    'gaming': 1, 'saved': 1, 'memories': 1, 'story.php': 1, 'photo': 1, 'photo.php': 1, 'permalink.php': 1,
                    'video': 1, 'videos': 1, 'posts': 1, 'share': 1, 'profile.php': 1, 'people': 1, 'help': 1, 'policies': 1,
                    'privacy': 1, 'ads': 1, 'business': 1, 'fundraisers': 1, 'jobs': 1, 'offers': 1, 'live': 1, 'composer': 1 };
                var ID_RX = /fb:\\?\/\\?\/(?:profile|user)\\?\/(\d{5,})/i;
                var LS_KEY = '__fbxIds';
                var idCache = {}, idPending = {}, idTries = {}, active = 0;

                function pageKey(u) {
                    try {
                        var p = new URL(u, location.href);
                        var path = p.pathname.replace(/\/+/g, '/').replace(/\/+$/, '').toLowerCase();
                        return path + (path === '/profile.php' ? '?id=' + p.searchParams.get('id') : '');
                    } catch (e) { return ''; }
                }
                function profileLike(u) {
                    try {
                        var p = new URL(u, location.href);
                        if (!/(^|\.)facebook\.com$/i.test(p.hostname)) return false;
                        var segs = p.pathname.split('/').filter(Boolean);
                        if (segs.length !== 1) return false;
                        var s = segs[0].toLowerCase();
                        return !RESERVED[s] && /^[a-z0-9.\-_]+$/i.test(s) && !/\.php$/.test(s);
                    } catch (e) { return false; }
                }
                function lsGet(k) {
                    try { return (JSON.parse(localStorage.getItem(LS_KEY) || '{}'))[k] || ''; } catch (e) { return ''; }
                }
                function lsPut(k, v) {
                    try {
                        var o = JSON.parse(localStorage.getItem(LS_KEY) || '{}');
                        o[k] = v;
                        var ks = Object.keys(o);
                        if (ks.length > 500) delete o[ks[0]];
                        localStorage.setItem(LS_KEY, JSON.stringify(o));
                    } catch (e) {}
                }
                function remember(key, id) { idCache[key] = id; lsPut(key, id); }

                function profileId() {
                    var key = pageKey(location.href);
                    if (idCache[key]) return idCache[key];
                    var saved = lsGet(key);
                    if (saved) { idCache[key] = saved; return saved; }
                    try {
                        // The page's own app-link tag is only right after a full load, so it is trusted only
                        // when the page's canonical address is this very address.
                        var m = document.querySelector('meta[property="al:android:url"],meta[name="al:android:url"]');
                        var r = ID_RX.exec((m && m.getAttribute('content')) || '');
                        if (!r) return '';
                        var can = document.querySelector('link[rel="canonical"]');
                        var og = document.querySelector('meta[property="og:url"]');
                        var ref = (can && can.href) || (og && og.getAttribute('content')) || '';
                        if (ref && pageKey(ref) === key) { remember(key, r[1]); return r[1]; }
                    } catch (e) {}
                    return '';
                }

                // Reads only the first part of the profile's html (the <head>, where the id lives) and stops.
                function fetchId(u) {
                    var key = pageKey(u);
                    if (!key || idCache[key] || idPending[key] || (idTries[key] || 0) >= 3 || active >= 2) return;
                    if (lsGet(key)) { idCache[key] = lsGet(key); return; }
                    idTries[key] = (idTries[key] || 0) + 1;
                    idPending[key] = true; active++;
                    function done() { idPending[key] = false; active--; }
                    fetch(new URL(u, location.href).href, { credentials: 'include' })
                        .then(function(res) {
                            if (!res.body || !res.body.getReader) return res.text();
                            var rd = res.body.getReader(), dec = new TextDecoder(), buf = '';
                            function pump() {
                                return rd.read().then(function(x) {
                                    if (x.done) return buf;
                                    buf += dec.decode(x.value, { stream: true });
                                    if (ID_RX.test(buf) || buf.indexOf('</head>') > -1 || buf.length > 300000) {
                                        try { rd.cancel(); } catch (e) {}
                                        return buf;
                                    }
                                    return pump();
                                });
                            }
                            return pump();
                        })
                        .then(function(t) {
                            var m = ID_RX.exec(t || '');
                            if (m) remember(key, m[1]);
                            done();
                        })
                        .catch(done);
                }

                // 1) the moment a profile link is tapped, start fetching its id (while the page is still opening)
                function onTapPrefetch(e) {
                    try {
                        var t = e.target;
                        if (!t || !t.closest) return;
                        var a = t.closest('a[href]');
                        if (!a) return;
                        var p = (e.changedTouches && e.changedTouches[0]) || e;
                        if (hasStart && (Math.abs(p.clientX - sx) > 12 || Math.abs(p.clientY - sy) > 12)) return;
                        var h = a.getAttribute('href') || '';
                        if (profileLike(h)) fetchId(h);
                    } catch (err) {}
                }
                // 2) safety net: whatever profile page is open now
                setInterval(function() {
                    try {
                        if (profileLike(location.href) && !profileId()) fetchId(location.href);
                    } catch (e) {}
                }, 400);

                function onStart(e) {
                    var p = (e.touches && e.touches[0]) || e;
                    sx = p.clientX; sy = p.clientY; hasStart = true;
                }

                function onEnd(e) {
                    var t = e.target;
                    if (!t || !t.closest) return;
                    var m = match(t);
                    if (!m) return;
                    if (e.type === 'pointerup' || e.type === 'touchend') {
                        var p = (e.changedTouches && e.changedTouches[0]) || e;
                        if (hasStart && (Math.abs(p.clientX - sx) > 12 || Math.abs(p.clientY - sy) > 12)) return; // scroll, not a tap
                    }
                    if (e.cancelable) e.preventDefault();
                    e.stopPropagation();
                    if (e.stopImmediatePropagation) e.stopImmediatePropagation();
                    var now = Date.now();
                    if (now - lastFire < 800) return;   // same tap already handled
                    lastFire = now;
                    var uid = m.href ? '' : profileId();
                    try { AndroidMessenger.open(m.href, location.href, uid); } catch (err) {}
                }

                window.addEventListener('pointerdown', onStart, true);
                window.addEventListener('touchstart', onStart, { capture: true, passive: true });
                window.addEventListener('pointerup', onEnd, true);
                window.addEventListener('touchend', onEnd, { capture: true, passive: false });
                window.addEventListener('click', onEnd, true);
                window.addEventListener('pointerup', onTapPrefetch, true);
                window.addEventListener('touchend', onTapPrefetch, { capture: true, passive: true });
            })();
        """

        // Hides Facebook's blue "Open app" bar (shown under reels / videos and on the home screen).
        // Runs at DOCUMENT START (before the page draws anything) and reacts to every DOM change
        // instantly (no timers), so the bar never gets a chance to be painted.
        const val HIDE_OPEN_APP_JS = """
            (function() {
                if (window.__fbxNoOpenApp2) return;
                window.__fbxNoOpenApp2 = true;
                var CSS = '[data-fbx-gone="1"]{display:none !important;visibility:hidden !important;}' +
                          '[aria-label="Open app"],[aria-label="Open in app"]{display:none !important;}';
                function ensureStyle() {
                    if (document.getElementById('__fbxGoneCss')) return;
                    var host = document.head || document.documentElement;
                    if (!host) return;
                    var st = document.createElement('style');
                    st.id = '__fbxGoneCss';
                    st.textContent = CSS;
                    host.appendChild(st);
                }
                function hideLeaf(leaf) {
                    if (leaf.closest && leaf.closest('[data-fbx-gone="1"]')) return;
                    var node = leaf;
                    while (node.parentElement && node.parentElement !== document.body &&
                           node.parentElement !== document.documentElement &&
                           (node.parentElement.textContent || '').trim() === 'Open app') {
                        node = node.parentElement;
                    }
                    node.setAttribute('data-fbx-gone', '1');
                }
                function scan(root) {
                    if (!root) return;
                    try {
                        var r = document.evaluate("descendant-or-self::*[normalize-space(text())='Open app']", root, null,
                                                  XPathResult.ORDERED_NODE_SNAPSHOT_TYPE, null);
                        for (var i = 0; i < r.snapshotLength; i++) hideLeaf(r.snapshotItem(i));
                    } catch (e) {}
                }
                function onMut(muts) {
                    ensureStyle();
                    for (var i = 0; i < muts.length; i++) {
                        var m = muts[i];
                        if (m.type === 'characterData') {
                            if ((m.target.nodeValue || '').indexOf('Open app') > -1) scan(m.target.parentElement);
                            continue;
                        }
                        for (var j = 0; j < m.addedNodes.length; j++) {
                            var n = m.addedNodes[j];
                            if (n.nodeType === 3) {
                                if ((n.nodeValue || '').indexOf('Open app') > -1) scan(n.parentElement);
                            } else if (n.nodeType === 1 && (n.textContent || '').indexOf('Open app') > -1) {
                                scan(n);
                            }
                        }
                    }
                }
                new MutationObserver(onMut).observe(document, { childList: true, subtree: true, characterData: true });
                function sweep() { ensureStyle(); scan(document.body || document.documentElement); }
                sweep();
                setInterval(sweep, 1500);
                document.addEventListener('visibilitychange', sweep, true);
                window.addEventListener('pageshow', sweep, true);
                window.addEventListener('focus', sweep, true);
            })();
        """

        // Tells the app when the page is really ready (feed drawn: posts / big images visible, or a login
        // form), so the native loading screen can be removed.
        const val FEED_READY_JS = """
            (function() {
                if (window.__fbxReadyWatch) return;
                window.__fbxReadyWatch = true;
                var t0 = Date.now(), fired = false;
                function bigImagesShown() {
                    var imgs = document.images, n = 0;
                    for (var i = 0; i < imgs.length; i++) {
                        var im = imgs[i];
                        if (!im.complete || im.naturalWidth < 120) continue;
                        var r = im.getBoundingClientRect();
                        if (r.width > 120 && r.height > 80 && r.bottom > 0 && r.top < window.innerHeight) n++;
                    }
                    return n >= 2;
                }
                function isReady() {
                    if (document.querySelector('input[type="password"]')) return true;   // login page
                    if (document.querySelector('[role="article"]')) return true;         // a post is drawn
                    return bigImagesShown();                                             // stories / photos drawn
                }
                var timer = setInterval(function() {
                    if (fired) return;
                    var ok = false;
                    try { ok = isReady(); } catch (e) {}
                    if (ok || Date.now() - t0 > 7000) {
                        fired = true;
                        clearInterval(timer);
                        // one more frame so the feed is really painted before the cover is lifted
                        requestAnimationFrame(function() {
                            setTimeout(function() { try { AndroidLoader.ready(); } catch (e) {} }, 120);
                        });
                    }
                }, 120);
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
        val root = FrameLayout(this)
        root.setBackgroundColor(Color.BLACK)
        root.addView(webView, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        setContentView(root)
        // Native loading screen (logo + 3 blinking dots) that stays until the home feed is really drawn.
        val loader = buildLoader()
        setBarColors(Color.BLACK)   // status + navigation bar match the black loading screen
        loaderView = loader
        root.addView(loader, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        // Safety net: never keep the cover up for more than 9 seconds.
        webView.postDelayed({ hideLoader() }, 9000)

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
                } else if (url != null) {
                    lastNonMessengerUrl = url
                }
            }

            override fun onPageCommitVisible(view: WebView, url: String?) {
                super.onPageCommitVisible(view, url)
                view.evaluateJavascript(NO_TAP_HIGHLIGHT_JS, null)
                view.evaluateJavascript(MESSENGER_HOOK_JS, null)
                view.evaluateJavascript(HIDE_OPEN_APP_JS, null)
                if (!loaderHidden) view.evaluateJavascript(FEED_READY_JS, null)
            }

            override fun onPageFinished(view: WebView, url: String?) {
                super.onPageFinished(view, url)
                // Commit the login/session cookie to disk so it survives a background process kill.
                CookieManager.getInstance().flush()
                view.evaluateJavascript(BAR_MODE_JS, null)
                view.evaluateJavascript(MESSENGER_HOOK_JS, null)
                view.evaluateJavascript(NO_TAP_HIGHLIGHT_JS, null)
                view.evaluateJavascript(HIDE_OPEN_APP_JS, null)
                if (!loaderHidden) {
                    view.evaluateJavascript(FEED_READY_JS, null)
                    // Any page other than the home feed (login, a shared link...) has no feed to wait for.
                    if (!isHomeUrl(url)) view.postDelayed({ hideLoader() }, 500)
                }
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
        webView.addJavascriptInterface(LoaderBridge(), "AndroidLoader")
        webView.addJavascriptInterface(MessengerBridge(), "AndroidMessenger")

        // Run the hide-"Open app" and Messenger scripts at DOCUMENT START: before the page draws or runs
        // anything. That is what stops the "Open app" bar flashing and makes the Message tap hook live
        // from the very first moment (including after the app is restored).
        try {
            if (WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)) {
                val origins = setOf("https://*.facebook.com")
                WebViewCompat.addDocumentStartJavaScript(webView, HIDE_OPEN_APP_JS, origins)
                WebViewCompat.addDocumentStartJavaScript(webView, MESSENGER_HOOK_JS, origins)
            }
        } catch (e: Exception) { /* the later evaluateJavascript injections still work as a fallback */ }

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
                    Toast.makeText(this@MainActivity, "Could not open the file chooser", Toast.LENGTH_SHORT).show()
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
                    "Turn on \"Open supported links\", then select all links under \"Add link\"",
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

    private var loaderView: FrameLayout? = null
    private var loaderHidden = false
    private val dotAnimators = mutableListOf<ObjectAnimator>()

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    /** True for the Facebook home feed (where we wait for posts to draw). */
    private fun isHomeUrl(url: String?): Boolean {
        val u = url?.let { Uri.parse(it) } ?: return false
        val host = u.host?.lowercase() ?: return false
        if (!(host == "facebook.com" || host.endsWith(".facebook.com"))) return false
        val path = (u.path ?: "").trimEnd('/')
        return path.isEmpty() || path == "/home.php"
    }

    /** Splash-style cover: Facebook logo with three softly blinking dots underneath. */
    private fun buildLoader(): FrameLayout {
        val overlay = FrameLayout(this)
        overlay.setBackgroundColor(Color.BLACK)
        overlay.isClickable = true      // swallow touches while the page is still loading
        overlay.isFocusable = true

        val logo = ImageView(this)
        logo.setImageResource(R.drawable.splash_logo)
        logo.scaleType = ImageView.ScaleType.FIT_CENTER
        overlay.addView(logo, FrameLayout.LayoutParams(dp(288), dp(288), Gravity.CENTER))

        val dots = LinearLayout(this)
        dots.orientation = LinearLayout.HORIZONTAL
        dots.gravity = Gravity.CENTER
        for (i in 0..2) {
            val dot = View(this)
            val shape = GradientDrawable()
            shape.shape = GradientDrawable.OVAL
            shape.setColor(Color.parseColor("#0866FF"))
            dot.background = shape
            dot.alpha = 0.2f
            val lp = LinearLayout.LayoutParams(dp(9), dp(9))
            lp.marginStart = dp(6)
            lp.marginEnd = dp(6)
            dots.addView(dot, lp)

            val anim = ObjectAnimator.ofFloat(dot, View.ALPHA, 0.2f, 1f, 0.2f)
            anim.duration = 1050
            anim.startDelay = i * 170L
            anim.repeatCount = ValueAnimator.INFINITE
            anim.interpolator = LinearInterpolator()
            dotAnimators.add(anim)
        }
        val dotsLp = FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER
        )
        overlay.addView(dots, dotsLp)
        dots.translationY = dp(78).toFloat()   // just below the logo
        dotAnimators.forEach { it.start() }
        return overlay
    }

    private fun setBarColors(color: Int) {
        window.statusBarColor = color
        window.navigationBarColor = color
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            window.navigationBarDividerColor = color
        }
    }

    /** Fades the loading cover out and removes it (safe to call many times). */
    private fun hideLoader() {
        if (loaderHidden) return
        loaderHidden = true
        val v = loaderView ?: return
        // Bars fade from the loader's black to the normal #121212 together with the cover.
        ValueAnimator.ofArgb(Color.BLACK, Color.parseColor("#121212")).apply {
            duration = 220
            addUpdateListener { setBarColors(it.animatedValue as Int) }
            start()
        }
        v.animate().alpha(0f).setDuration(220).withEndAction {
            dotAnimators.forEach { it.cancel() }
            dotAnimators.clear()
            (v.parent as? ViewGroup)?.removeView(v)
            loaderView = null
        }.start()
    }

    private inner class LoaderBridge {
        @JavascriptInterface
        fun ready() {
            runOnUiThread { hideLoader() }
        }
    }

    /** Called from the injected JS when the Messenger tab / Message button is tapped. */
    private inner class MessengerBridge {
        @JavascriptInterface
        fun open(href: String, pageUrl: String, uid: String) {
            runOnUiThread { openMessengerFor(href, pageUrl, uid) }
        }
    }

    private fun openMessengerFor(href: String?, pageUrl: String?, uid: String?) {
        msgSource = "tap"
        var target: String? = null
        if (!href.isNullOrEmpty()) target = messengerTargetFromUrl(Uri.parse(href))
        if (target == null && !pageUrl.isNullOrEmpty()) target = profileTargetFromUrl(Uri.parse(pageUrl))
        // A numeric id opens the chat instantly; a username has to be looked up by Messenger first (slow).
        if ((target == null || !target.all { it.isDigit() }) && !uid.isNullOrEmpty() && uid.all { it.isDigit() }) {
            target = uid
        }
        openMessengerChat(target)
    }

    /** Pulls the person (numeric id or username) out of a Messenger / "Message" link. */
    private fun messengerTargetFromUrl(uri: Uri): String? {
        val scheme = uri.scheme?.lowercase()
        if (scheme != null && (scheme.startsWith("fb-messenger") || scheme == "intent")) {
            return Regex("(\\d{6,})").find(uri.toString())?.value
        }
        for (key in listOf("ids", "id", "user", "user_id", "uid")) {
            val v = uri.getQueryParameter(key)?.substringBefore(',')
            if (v != null && v.matches(Regex("\\d+"))) return v
        }
        val host = uri.host?.lowercase()
        val segs = uri.pathSegments
        if (host == "m.me" || host?.endsWith(".m.me") == true) {
            return segs.firstOrNull { it != "j" && it != "ref" }
        }
        if (host == "messenger.com" || host?.endsWith(".messenger.com") == true) {
            val i = segs.indexOf("t")
            return if (i != -1) segs.getOrNull(i + 1) else null
        }
        val i = segs.indexOf("messages")
        if (i != -1) {
            val kind = segs.getOrNull(i + 1)
            if (kind == "t" || kind == "thread") {
                val id = segs.getOrNull(i + 2)
                if (id != null && !id.startsWith("cid.")) return id
            }
        }
        return null
    }

    /** Works out whose profile a Facebook page URL is (numeric id or username). */
    private fun profileTargetFromUrl(uri: Uri): String? {
        val host = uri.host?.lowercase() ?: return null
        if (!(host == "facebook.com" || host.endsWith(".facebook.com"))) return null
        val segs = uri.pathSegments
        if (segs.isEmpty()) return null

        val numeric = Regex("\\d{5,}")
        for (n in listOf("user", "profile")) {
            val i = segs.indexOf(n)
            if (i != -1) segs.getOrNull(i + 1)?.takeIf { numeric.matches(it) }?.let { return it }
        }
        if (segs[0] == "people") {
            segs.drop(1).firstOrNull { numeric.matches(it) }?.let { return it }
        }
        if (segs[0] == "profile.php") {
            return uri.getQueryParameter("id")?.takeIf { numeric.matches(it) }
        }
        val reserved = setOf(
            "home.php", "messages", "messenger", "watch", "reel", "reels", "groups", "marketplace",
            "stories", "events", "pages", "friends", "notifications", "bookmarks", "settings",
            "menu", "login", "login.php", "search", "hashtag", "gaming", "saved", "memories",
            "story.php", "photo", "photo.php", "permalink.php", "video", "videos", "posts", "share"
        )
        val first = segs[0]
        if (first.lowercase() !in reserved) return first
        return null
    }

    /** Opens Messenger (straight to the chat if we know the user); Play Store if not installed. */
    private fun openMessenger(uri: Uri) {
        msgSource = "url-change"
        var target = messengerTargetFromUrl(uri)
        if (target == null) {
            // A "Message" link with extra path/query (not the plain inbox) -> use the profile we came from.
            val path = uri.path?.trimEnd('/') ?: ""
            val isInbox = path == "/messages" || path == "/messenger" || path.isEmpty()
            if (!isInbox) lastNonMessengerUrl?.let { target = profileTargetFromUrl(Uri.parse(it)) }
        }
        openMessengerChat(target)
    }

    private var msgSource = "?"

    private fun openMessengerChat(target: String?) {
        if (DEBUG_MESSENGER) {
            val what = when {
                target == null -> "no id, opening inbox"
                target.all { it.isDigit() } -> "numeric ID (fast path)"
                else -> "username (slow path)"
            }
            Toast.makeText(this, "Messenger [" + msgSource + "]: " + what, Toast.LENGTH_SHORT).show()
        }
        if (target != null) {
            val isNumeric = target.all { it.isDigit() }
            val links = if (isNumeric) {
                listOf(
                    "fb-messenger://user-thread/$target",
                    "fb-messenger://user/$target",
                    "https://m.me/$target"
                )
            } else {
                listOf("https://m.me/$target")
            }
            for (link in links) {
                try {
                    startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(link)).setPackage(messengerPackage))
                    return
                } catch (e: Exception) { /* try next */ }
            }
        }
        // Just launch Messenger (inbox)
        try {
            val launch = packageManager.getLaunchIntentForPackage(messengerPackage)
            if (launch != null) {
                startActivity(launch)
                return
            }
        } catch (e: Exception) { /* try next */ }
        // Not installed -> Play Store
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
            Toast.makeText(this, "Could not open the link", Toast.LENGTH_SHORT).show()
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
        if (!loaderHidden) return
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
