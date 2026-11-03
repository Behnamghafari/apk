package com.behnamghafari.salonlink

import android.annotation.SuppressLint
import android.app.AlertDialog
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.os.Build
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat

class MainActivity : ComponentActivity() {
    private val preferences by lazy {
        getSharedPreferences("salon_link_settings", MODE_PRIVATE)
    }

    private lateinit var root: LinearLayout
    private var webView: WebView? = null
    private var addressInput: EditText? = null
    private var progressBar: ProgressBar? = null
    private var savedAddress: String? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        savedAddress = preferences.getString(KEY_ADDRESS, null)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        window.statusBarColor = Color.rgb(23, 36, 58)
        window.navigationBarColor = Color.rgb(23, 36, 58)

        root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.rgb(248, 247, 244))
        }
        ViewCompat.setOnApplyWindowInsetsListener(root) { view, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            view.setPadding(0, bars.top, 0, bars.bottom)
            insets
        }
        setContentView(root)

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                val browser = webView
                if (browser != null && browser.canGoBack()) {
                    browser.goBack()
                } else {
                    confirmExit()
                }
            }
        })

        if (savedAddress.isNullOrBlank()) {
            showAddressForm()
        } else {
            showWebsite(savedAddress!!)
        }
    }

    private fun showAddressForm() {
        webView?.stopLoading()
        webView?.destroy()
        webView = null
        root.removeAllViews()

        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(dp(28), dp(24), dp(28), dp(24))
        }
        root.addView(content, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.MATCH_PARENT
        ))

        val icon = TextView(this).apply {
            text = "✂"
            textSize = 54f
            gravity = Gravity.CENTER
            setTextColor(Color.rgb(217, 179, 108))
        }
        content.addView(icon, centeredParams())

        val title = TextView(this).apply {
            text = "سالن من"
            textSize = 27f
            gravity = Gravity.CENTER
            setTextColor(Color.rgb(23, 36, 58))
            typeface = android.graphics.Typeface.DEFAULT_BOLD
        }
        content.addView(title, centeredParams())

        val subtitle = TextView(this).apply {
            text = "آدرس سایت موردنظرت رو وارد کن"
            textSize = 15f
            gravity = Gravity.CENTER
            setTextColor(Color.rgb(103, 111, 124))
        }
        val subtitleParams = centeredParams().apply {
            topMargin = dp(10)
            bottomMargin = dp(24)
        }
        content.addView(subtitle, subtitleParams)

        val input = EditText(this).apply {
            hint = "https://example.com"
            textSize = 16f
            singleLine = true
            inputType = android.text.InputType.TYPE_CLASS_TEXT or
                android.text.InputType.TYPE_TEXT_VARIATION_URI
            setTextColor(Color.rgb(23, 36, 58))
            setHintTextColor(Color.rgb(145, 150, 160))
            setPadding(dp(16), dp(14), dp(16), dp(14))
            setBackgroundColor(Color.WHITE)
            setText(savedAddress.orEmpty())
        }
        addressInput = input
        content.addView(input, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, dp(54)
        ))

        val saveButton = Button(this).apply {
            text = "ذخیره و باز کردن سایت"
            textSize = 15f
            isAllCaps = false
            setTextColor(Color.WHITE)
            backgroundTintList = android.content.res.ColorStateList.valueOf(Color.rgb(23, 36, 58))
            setOnClickListener { saveAddressAndOpen() }
        }
        val buttonParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, dp(54)
        ).apply { topMargin = dp(16) }
        content.addView(saveButton, buttonParams)

        if (!savedAddress.isNullOrBlank()) {
            val cancel = Button(this).apply {
                text = "بازگشت به سایت"
                isAllCaps = false
                setOnClickListener { showWebsite(savedAddress!!) }
            }
            content.addView(cancel, centeredParams())
        }
    }

    private fun saveAddressAndOpen() {
        val raw = addressInput?.text?.toString()?.trim().orEmpty()
        if (raw.isBlank()) {
            addressInput?.error = "آدرس سایت را وارد کن"
            return
        }

        val normalized = if (raw.startsWith("http://", true) ||
            raw.startsWith("https://", true)) raw else "https://$raw"

        val uri = runCatching { Uri.parse(normalized) }.getOrNull()
        if (uri == null || uri.host.isNullOrBlank() ||
            (uri.scheme != "http" && uri.scheme != "https")) {
            addressInput?.error = "آدرس معتبر نیست"
            return
        }

        preferences.edit().putString(KEY_ADDRESS, normalized).apply()
        savedAddress = normalized
        showWebsite(normalized)
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun showWebsite(address: String) {
        root.removeAllViews()

        val toolbar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(14), dp(4), dp(8), dp(4))
            setBackgroundColor(Color.rgb(23, 36, 58))
        }

        val brand = TextView(this).apply {
            text = "✂  سالن من"
            textSize = 17f
            typeface = android.graphics.Typeface.DEFAULT_BOLD
            setTextColor(Color.WHITE)
        }
        toolbar.addView(brand, LinearLayout.LayoutParams(0, dp(48), 1f))

        val changeButton = Button(this).apply {
            text = "تغییر آدرس"
            textSize = 12f
            isAllCaps = false
            setTextColor(Color.WHITE)
            backgroundTintList = android.content.res.ColorStateList.valueOf(Color.rgb(63, 78, 101))
            setOnClickListener { showAddressForm() }
        }
        toolbar.addView(changeButton, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, dp(42)
        ))
        root.addView(toolbar)

        progressBar = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 100
            progress = 0
        }
        root.addView(progressBar, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, dp(3)
        ))

        val browser = WebView(this)
        webView = browser
        browser.setBackgroundColor(Color.WHITE)
        browser.settings.javaScriptEnabled = true
        browser.settings.domStorageEnabled = true
        browser.settings.javaScriptCanOpenWindowsAutomatically = false
        browser.settings.setSupportMultipleWindows(false)
        browser.settings.allowFileAccess = false
        browser.settings.allowContentAccess = false
        browser.settings.mixedContentMode = android.webkit.WebSettings.MIXED_CONTENT_NEVER_ALLOW
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            browser.settings.safeBrowsingEnabled = true
        }
        browser.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(
                view: WebView,
                request: WebResourceRequest
            ): Boolean {
                val scheme = request.url.scheme?.lowercase()
                return scheme != "http" && scheme != "https"
            }
        }
        browser.webChromeClient = object : WebChromeClient() {
            override fun onProgressChanged(view: WebView, newProgress: Int) {
                progressBar?.progress = newProgress
                progressBar?.visibility = if (newProgress >= 100) View.GONE else View.VISIBLE
            }
        }
        root.addView(browser, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f
        ))
        browser.loadUrl(address)
    }

    private fun confirmExit() {
        AlertDialog.Builder(this)
            .setTitle("خروج از برنامه")
            .setMessage("می‌خوای از برنامه خارج بشی؟")
            .setNegativeButton("نه", null)
            .setPositiveButton("خروج") { _, _ -> finish() }
            .show()
    }

    override fun onDestroy() {
        webView?.apply {
            stopLoading()
            loadUrl("about:blank")
            destroy()
        }
        webView = null
        super.onDestroy()
    }

    private fun centeredParams(): LinearLayout.LayoutParams =
        LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { gravity = Gravity.CENTER_HORIZONTAL }

    private fun dp(value: Int): Int =
        (value * resources.displayMetrics.density).toInt()

    companion object {
        private const val KEY_ADDRESS = "website_address"
    }
}
