package com.litebrowser

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.app.DownloadManager
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.Message
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.webkit.*
import android.widget.*
import org.json.JSONArray
import org.json.JSONObject

class MainActivity : Activity() {
    private class Tab(val web: WebView, val incognito: Boolean) {
        var title = "New tab"; var pending: String? = null; var desktop = false
    }

    private val HOME = "https://www.google.com"
    private val DESKTOP_UA = "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36"
    private val tabs = ArrayList<Tab>()
    private var cur = 0
    private lateinit var box: FrameLayout
    private lateinit var url: EditText
    private lateinit var bar: ProgressBar
    private lateinit var tabBtn: TextView
    private lateinit var store: Store
    private var customView: View? = null
    private var customCb: WebChromeClient.CustomViewCallback? = null
    private var fileCb: ValueCallback<Array<Uri>>? = null
    private var pendingWeb: PermissionRequest? = null
    private var pendingGeo: Pair<String, GeolocationPermissions.Callback>? = null

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()
    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_SHORT).show()
    private fun has(p: String) = checkSelfPermission(p) == PackageManager.PERMISSION_GRANTED
    private fun isCur(t: Tab) = tabs.getOrNull(cur) === t
    private fun mobileUa() = WebSettings.getDefaultUserAgent(this).replace("; wv", "").replace(Regex("Version/\\d+\\.\\d+ "), "")

    override fun onCreate(b: android.os.Bundle?) {
        super.onCreate(b)
        store = Store(this)
        CookieManager.getInstance().setAcceptCookie(true)

        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setBackgroundColor(Color.WHITE) }
        val top = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL; setPadding(dp(8), dp(6), dp(8), dp(6)) }
        url = EditText(this).apply {
            hint = "Search or type URL"; isSingleLine = true; textSize = 15f
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
            imeOptions = EditorInfo.IME_ACTION_GO
            setSelectAllOnFocus(true)
            setPadding(dp(16), 0, dp(16), 0)
            background = GradientDrawable().apply { cornerRadius = dp(21).toFloat(); setColor(0xFFEEF1F6.toInt()) }
            setOnEditorActionListener { _, a, _ ->
                if (a == EditorInfo.IME_ACTION_GO) { load(text.toString()); hideKb(); true } else false
            }
        }
        top.addView(url, LinearLayout.LayoutParams(0, dp(42), 1f))
        bar = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply { max = 100; visibility = View.GONE }
        box = FrameLayout(this)
        val bottom = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
        fun btn(l: String, f: () -> Unit) = TextView(this).apply {
            text = l; textSize = 22f; gravity = Gravity.CENTER; setTextColor(0xFF333333.toInt())
            setOnClickListener { f() }
        }
        tabBtn = btn("1") { showTabs() }
        listOf(
            btn("◀") { tabs[cur].web.let { if (it.canGoBack()) it.goBack() } },
            btn("▶") { tabs[cur].web.let { if (it.canGoForward()) it.goForward() } },
            btn("⌂") { load(HOME) },
            btn("⟳") { tabs[cur].web.reload() },
            tabBtn,
            btn("⋮") { showMenu() }
        ).forEach { bottom.addView(it, LinearLayout.LayoutParams(0, dp(48), 1f)) }
        col.addView(top)
        col.addView(bar, LinearLayout.LayoutParams(-1, dp(3)))
        col.addView(box, LinearLayout.LayoutParams(-1, 0, 1f))
        col.addView(bottom)
        setContentView(col)

        if (Build.VERSION.SDK_INT >= 33 && !has(Manifest.permission.POST_NOTIFICATIONS) && !store.flag("asked")) {
            store.setFlag("asked"); requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 12)
        }
        val d = intent?.data?.toString()
        val restored = restore()
        if (d != null) newTab(d) else if (!restored) newTab(HOME)
    }

    override fun onNewIntent(i: Intent?) {
        super.onNewIntent(i); i?.data?.let { newTab(it.toString()) }
    }

    // ---------- tabs ----------
    private fun newTab(u: String?, incog: Boolean = false, show: Boolean = true): Tab {
        val t = Tab(WebView(this), incog)
        setup(t); tabs.add(t)
        if (show) select(tabs.size - 1)
        if (u != null) t.web.loadUrl(u)
        return t
    }

    private fun select(i: Int) {
        cur = i; val t = tabs[i]
        box.removeAllViews(); (t.web.parent as? ViewGroup)?.removeView(t.web)
        box.addView(t.web, FrameLayout.LayoutParams(-1, -1))
        val u = t.pending; t.pending = null
        if (u != null) t.web.loadUrl(u)
        url.setText(u ?: t.web.url ?: ""); bar.visibility = View.GONE
        tabBtn.text = "${tabs.size}"; t.web.requestFocus()
    }

    private fun closeTab(i: Int) {
        val t = tabs.removeAt(i)
        (t.web.parent as? ViewGroup)?.removeView(t.web); t.web.destroy()
        if (tabs.isEmpty()) { newTab(HOME); return }
        cur = if (i < cur) cur - 1 else cur
        select(cur.coerceIn(0, tabs.size - 1))
    }

    private fun saveSession() {
        val keep = tabs.filter { !it.incognito }; if (keep.isEmpty()) return
        val o = JSONObject()
        o.put("cur", keep.indexOf(tabs.getOrNull(cur)).coerceAtLeast(0))
        o.put("urls", JSONArray(keep.map { it.pending ?: it.web.url ?: HOME }))
        store.session = o.toString()
    }

    private fun restore(): Boolean = try {
        val o = JSONObject(store.session); val a = o.getJSONArray("urls")
        if (a.length() == 0) false else {
            for (i in 0 until a.length()) newTab(null, false, false).pending = a.getString(i)
            select(o.getInt("cur").coerceIn(0, tabs.size - 1)); true
        }
    } catch (e: Exception) { false }

    // ---------- webview ----------
    private fun setup(t: Tab) {
        val w = t.web; val s = w.settings
        s.javaScriptEnabled = true; s.domStorageEnabled = true
        s.mediaPlaybackRequiresUserGesture = false
        s.setSupportZoom(true); s.builtInZoomControls = true; s.displayZoomControls = false
        s.useWideViewPort = true; s.loadWithOverviewMode = true
        s.setSupportMultipleWindows(true); s.javaScriptCanOpenWindowsAutomatically = true
        s.mixedContentMode = WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE
        s.allowFileAccess = false; s.allowContentAccess = false
        s.setGeolocationEnabled(true)
        s.userAgentString = mobileUa()
        if (t.incognito) s.cacheMode = WebSettings.LOAD_NO_CACHE
        CookieManager.getInstance().setAcceptThirdPartyCookies(w, true)

        w.webViewClient = object : WebViewClient() {
            override fun onPageStarted(v: WebView, u: String?, f: android.graphics.Bitmap?) {
                if (isCur(t) && !url.hasFocus()) url.setText(u)
            }
            override fun onPageFinished(v: WebView, u: String?) {
                if (!t.incognito && u != null && u.startsWith("http")) store.add("history", v.title ?: u, u, 500)
                if (isCur(t) && !url.hasFocus()) url.setText(u)
            }
            override fun shouldOverrideUrlLoading(v: WebView, r: WebResourceRequest): Boolean {
                val sc = r.url.scheme ?: ""
                if (sc in listOf("http", "https", "file", "about", "data", "javascript", "blob")) return false
                try {
                    val i = if (sc == "intent") Intent.parseUri(r.url.toString(), Intent.URI_INTENT_SCHEME) else Intent(Intent.ACTION_VIEW, r.url)
                    i.addCategory(Intent.CATEGORY_BROWSABLE); i.component = null; i.selector = null
                    startActivity(i)
                } catch (e: Exception) { toast("No app can open this link") }
                return true
            }
            override fun onReceivedError(v: WebView, r: WebResourceRequest, e: WebResourceError) {
                if (r.isForMainFrame) v.loadDataWithBaseURL(r.url.toString(),
                    "<html><head><meta name='viewport' content='width=device-width'></head><body style='font-family:sans-serif;padding:32px;color:#444'><h2>Can't open this page</h2><p>${e.description}</p></body></html>",
                    "text/html", "utf-8", r.url.toString())
            }
        }
        w.webChromeClient = object : WebChromeClient() {
            override fun onProgressChanged(v: WebView, p: Int) {
                if (isCur(t)) { bar.progress = p; bar.visibility = if (p < 100) View.VISIBLE else View.GONE }
            }
            override fun onReceivedTitle(v: WebView, s: String?) { t.title = s ?: t.title }
            override fun onCreateWindow(v: WebView, d: Boolean, g: Boolean, m: Message): Boolean {
                val n = newTab(null, t.incognito)
                (m.obj as WebView.WebViewTransport).webView = n.web; m.sendToTarget(); return true
            }
            override fun onShowCustomView(v: View, cb: CustomViewCallback) {
                if (customView != null) { cb.onCustomViewHidden(); return }
                customView = v; customCb = cb; v.setBackgroundColor(Color.BLACK)
                (window.decorView as FrameLayout).addView(v, FrameLayout.LayoutParams(-1, -1))
                @Suppress("DEPRECATION")
                window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_FULLSCREEN or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
            }
            override fun onHideCustomView() { hideCustom() }
            override fun onPermissionRequest(r: PermissionRequest) { runOnUiThread { askWeb(r) } }
            override fun onGeolocationPermissionsShowPrompt(o: String, cb: GeolocationPermissions.Callback) {
                runOnUiThread {
                    AlertDialog.Builder(this@MainActivity).setMessage("${Uri.parse(o).host ?: o} wants to know your location.")
                        .setPositiveButton("Allow") { _, _ ->
                            if (has(Manifest.permission.ACCESS_FINE_LOCATION) || has(Manifest.permission.ACCESS_COARSE_LOCATION)) cb.invoke(o, true, false)
                            else { pendingGeo = o to cb; requestPermissions(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION), 11) }
                        }
                        .setNegativeButton("Block") { _, _ -> cb.invoke(o, false, false) }
                        .setOnCancelListener { cb.invoke(o, false, false) }.show()
                }
            }
            override fun onShowFileChooser(v: WebView, cb: ValueCallback<Array<Uri>>, p: FileChooserParams): Boolean {
                fileCb?.onReceiveValue(null); fileCb = cb
                return try { startActivityForResult(p.createIntent(), 20); true } catch (e: Exception) { fileCb = null; false }
            }
        }
        w.setDownloadListener { u, ua, cd, mime, _ -> download(u, ua, cd, mime) }
        w.setOnLongClickListener {
            val h = w.hitTestResult; val ex = h.extra ?: return@setOnLongClickListener false
            when (h.type) {
                WebView.HitTestResult.SRC_ANCHOR_TYPE, WebView.HitTestResult.SRC_IMAGE_ANCHOR_TYPE -> { linkMenu(ex, t.incognito); true }
                WebView.HitTestResult.IMAGE_TYPE -> { imgMenu(ex); true }
                else -> false
            }
        }
    }

    private fun hideCustom() {
        val v = customView ?: return
        (window.decorView as FrameLayout).removeView(v)
        @Suppress("DEPRECATION")
        window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_VISIBLE
        customCb?.onCustomViewHidden(); customView = null; customCb = null
    }

    private fun download(u: String, ua: String?, cd: String?, mime: String?) {
        try {
            val name = URLUtil.guessFileName(u, cd, mime)
            val r = DownloadManager.Request(Uri.parse(u))
            if (mime != null) r.setMimeType(mime)
            CookieManager.getInstance().getCookie(u)?.let { r.addRequestHeader("cookie", it) }
            if (ua != null) r.addRequestHeader("User-Agent", ua)
            r.setTitle(name)
            r.setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
            r.setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, name)
            (getSystemService(DOWNLOAD_SERVICE) as DownloadManager).enqueue(r)
            toast("Downloading $name")
        } catch (e: Exception) { toast("Download failed") }
    }

    // ---------- permissions ----------
    private fun askWeb(r: PermissionRequest) {
        val need = ArrayList<String>(); val names = ArrayList<String>()
        for (x in r.resources) when (x) {
            PermissionRequest.RESOURCE_VIDEO_CAPTURE -> { need.add(Manifest.permission.CAMERA); names.add("camera") }
            PermissionRequest.RESOURCE_AUDIO_CAPTURE -> { need.add(Manifest.permission.RECORD_AUDIO); names.add("microphone") }
        }
        if (names.isEmpty()) { grantWeb(r); return }
        AlertDialog.Builder(this).setMessage("${r.origin.host} wants to use your ${names.joinToString(" & ")}.")
            .setPositiveButton("Allow") { _, _ ->
                val miss = need.filter { !has(it) }
                if (miss.isEmpty()) grantWeb(r) else { pendingWeb = r; requestPermissions(miss.toTypedArray(), 10) }
            }
            .setNegativeButton("Block") { _, _ -> r.deny() }
            .setOnCancelListener { r.deny() }.show()
    }

    private fun grantWeb(r: PermissionRequest) {
        val g = r.resources.filter {
            when (it) {
                PermissionRequest.RESOURCE_VIDEO_CAPTURE -> has(Manifest.permission.CAMERA)
                PermissionRequest.RESOURCE_AUDIO_CAPTURE -> has(Manifest.permission.RECORD_AUDIO)
                PermissionRequest.RESOURCE_PROTECTED_MEDIA_ID -> true
                else -> false
            }
        }
        if (g.isEmpty()) r.deny() else r.grant(g.toTypedArray())
    }

    override fun onRequestPermissionsResult(code: Int, perms: Array<out String>, res: IntArray) {
        super.onRequestPermissionsResult(code, perms, res)
        if (code == 10) { pendingWeb?.let { grantWeb(it) }; pendingWeb = null }
        if (code == 11) {
            pendingGeo?.let { (o, cb) -> cb.invoke(o, has(Manifest.permission.ACCESS_FINE_LOCATION) || has(Manifest.permission.ACCESS_COARSE_LOCATION), false) }
            pendingGeo = null
        }
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(req: Int, res: Int, data: Intent?) {
        if (req == 20) { fileCb?.onReceiveValue(WebChromeClient.FileChooserParams.parseResult(res, data)); fileCb = null }
        else super.onActivityResult(req, res, data)
    }

    // ---------- UI helpers ----------
    private fun toUrl(s: String): String {
        val t = s.trim()
        if (t.matches(Regex("^[a-zA-Z][a-zA-Z0-9+.-]*:.*")) && (t.contains("://") || t.startsWith("about:") || t.startsWith("data:"))) return t
        if (t.contains(" ") || !t.contains(".") && t != "localhost" && !t.startsWith("localhost:"))
            return "https://www.google.com/search?q=" + Uri.encode(t)
        return "https://$t"
    }
    private fun load(s: String) { tabs[cur].web.loadUrl(toUrl(s)) }
    private fun hideKb() {
        (getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager).hideSoftInputFromWindow(url.windowToken, 0); url.clearFocus()
    }
    private fun copy(s: String) {
        (getSystemService(CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(ClipData.newPlainText("url", s)); toast("Copied")
    }
    private fun share(s: String) {
        startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, s), "Share"))
    }

    private fun linkMenu(l: String, incog: Boolean) {
        AlertDialog.Builder(this).setTitle(l.take(80)).setItems(arrayOf("Open in new tab", "Open in incognito tab", "Copy link", "Share link")) { _, i ->
            when (i) { 0 -> newTab(l, incog); 1 -> newTab(l, true); 2 -> copy(l); 3 -> share(l) }
        }.show()
    }
    private fun imgMenu(l: String) {
        AlertDialog.Builder(this).setItems(arrayOf("Open image in new tab", "Download image", "Copy image URL")) { _, i ->
            when (i) { 0 -> newTab(l); 1 -> download(l, mobileUa(), null, null); 2 -> copy(l) }
        }.show()
    }

    private fun showTabs() {
        val names = tabs.map { (if (it.incognito) "🕶 " else "") + (it.pending?.let { p -> Uri.parse(p).host } ?: it.title).take(50) }.toTypedArray()
        val d = AlertDialog.Builder(this).setTitle("Tabs (long-press to close)")
            .setItems(names) { _, i -> select(i) }
            .setPositiveButton("New tab") { _, _ -> newTab(HOME) }
            .setNeutralButton("Incognito") { _, _ -> newTab(HOME, true) }
            .setNegativeButton("Close", null).create()
        d.show()
        d.listView.setOnItemLongClickListener { _, _, i, _ -> closeTab(i); d.dismiss(); true }
    }

    private fun showList(title: String, key: String) {
        val l = store.get(key); if (l.isEmpty()) { toast("Empty"); return }
        val d = AlertDialog.Builder(this).setTitle("$title (long-press to remove)")
            .setItems(l.map { it.first.take(60) + "\n" + it.second.take(70) }.toTypedArray()) { _, i -> load(l[i].second) }
            .setNeutralButton("Clear all") { _, _ -> store.clear(key) }
            .setNegativeButton("Close", null).create()
        d.show()
        d.listView.setOnItemLongClickListener { _, _, i, _ -> store.remove(key, l[i].second); toast("Removed"); d.dismiss(); true }
    }

    private fun find() {
        val w = tabs[cur].web; val e = EditText(this).apply { hint = "Find in page"; isSingleLine = true }
        e.addTextChangedListener(object : TextWatcher {
            override fun afterTextChanged(s: Editable?) { w.findAllAsync(s.toString()) }
            override fun beforeTextChanged(a: CharSequence?, b: Int, c: Int, d: Int) {}
            override fun onTextChanged(a: CharSequence?, b: Int, c: Int, d: Int) {}
        })
        val d = AlertDialog.Builder(this).setView(e).setPositiveButton("Next", null)
            .setNegativeButton("Close") { _, _ -> w.clearMatches() }.setOnCancelListener { w.clearMatches() }.create()
        d.setOnShowListener { d.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener { w.findNext(true) } }
        d.show()
    }

    private fun toggleDesktop() {
        val t = tabs[cur]; t.desktop = !t.desktop
        t.web.settings.userAgentString = if (t.desktop) DESKTOP_UA else mobileUa()
        t.web.reload()
    }

    private fun clearData() {
        AlertDialog.Builder(this).setMessage("Clear cookies, cache, site data and history?")
            .setPositiveButton("Clear") { _, _ ->
                CookieManager.getInstance().removeAllCookies(null); WebStorage.getInstance().deleteAllData()
                tabs.forEach { it.web.clearCache(true); it.web.clearHistory() }
                store.clear("history"); toast("Cleared")
            }.setNegativeButton("Cancel", null).show()
    }

    private fun showMenu() {
        val t = tabs[cur]; val u = t.web.url ?: ""
        val bm = store.has("bookmarks", u)
        val items = arrayOf(
            "New tab", "New incognito tab",
            if (bm) "Remove bookmark" else "Add bookmark",
            "Bookmarks", "History", "Downloads", "Share", "Find in page",
            "Desktop site: " + if (t.desktop) "ON" else "OFF", "Clear browsing data"
        )
        AlertDialog.Builder(this).setItems(items) { _, i ->
            when (i) {
                0 -> newTab(HOME); 1 -> newTab(HOME, true)
                2 -> if (u.startsWith("http")) { if (bm) store.remove("bookmarks", u) else store.add("bookmarks", t.web.title ?: u, u, 2000); toast(if (bm) "Removed" else "Bookmarked") }
                3 -> showList("Bookmarks", "bookmarks"); 4 -> showList("History", "history")
                5 -> startActivity(Intent(DownloadManager.ACTION_VIEW_DOWNLOADS))
                6 -> if (u.isNotEmpty()) share(u)
                7 -> find(); 8 -> toggleDesktop(); 9 -> clearData()
            }
        }.show()
    }

    // ---------- lifecycle ----------
    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        if (customView != null) { hideCustom(); return }
        val w = tabs[cur].web
        if (w.canGoBack()) w.goBack()
        else if (tabs.size > 1) closeTab(cur)
        else @Suppress("DEPRECATION") super.onBackPressed()
    }
    override fun onPause() { super.onPause(); saveSession(); CookieManager.getInstance().flush() }
    override fun onDestroy() { tabs.forEach { it.web.destroy() }; super.onDestroy() }
}
