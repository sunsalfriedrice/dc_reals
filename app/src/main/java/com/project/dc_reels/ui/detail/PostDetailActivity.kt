package com.project.dc_reels.ui.detail

import android.annotation.SuppressLint
import android.os.Bundle
import android.view.View
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.ProgressBar
import androidx.appcompat.app.AppCompatActivity
import com.project.dc_reels.R

class PostDetailActivity : AppCompatActivity() {

    private lateinit var webView: WebView
    private lateinit var loading: ProgressBar
    private var initialPostUrl: String = ""

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_post_detail)

        loading = findViewById(R.id.detailLoading)
        webView = findViewById(R.id.postDetailWebView)

        title = intent.getStringExtra(EXTRA_POST_TITLE).orEmpty().ifBlank {
            getString(R.string.post_detail)
        }

        webView.settings.javaScriptEnabled = true
        webView.settings.domStorageEnabled = true
        webView.settings.loadsImagesAutomatically = true
        webView.settings.mediaPlaybackRequiresUserGesture = false
        webView.settings.mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
        webView.settings.userAgentString = USER_AGENT
        webView.webChromeClient = WebChromeClient()
        webView.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
                val targetUrl = request?.url?.toString().orEmpty()
                if (request?.isForMainFrame == true) {
                    if (!isSamePage(initialPostUrl, targetUrl) || isImageUrl(targetUrl)) {
                        loading.visibility = View.GONE
                        return true
                    }
                }
                return false
            }

            override fun onPageStarted(view: WebView?, url: String?, favicon: android.graphics.Bitmap?) {
                if (isSamePage(initialPostUrl, url.orEmpty())) {
                    loading.visibility = View.VISIBLE
                }
            }

            override fun onPageFinished(view: WebView?, url: String?) {
                loading.visibility = View.GONE
            }
        }

        val url = intent.getStringExtra(EXTRA_POST_URL).orEmpty()
        if (url.isBlank()) {
            loading.visibility = View.GONE
            finish()
            return
        }

        initialPostUrl = url
        loading.visibility = View.VISIBLE
        webView.loadUrl(url)
    }

    override fun onDestroy() {
        webView.destroy()
        super.onDestroy()
    }

    companion object {
        const val EXTRA_POST_URL = "extra_post_url"
        const val EXTRA_POST_TITLE = "extra_post_title"
        private const val USER_AGENT =
            "Mozilla/5.0 (Linux; Android 14; Pixel 7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/123.0.0.0 Mobile Safari/537.36"
    }

    private fun isSamePage(baseUrl: String, targetUrl: String): Boolean {
        if (baseUrl.isBlank() || targetUrl.isBlank()) return false
        val base = baseUrl.substringBefore('#')
        val target = targetUrl.substringBefore('#')
        return base == target
    }

    private fun isImageUrl(rawUrl: String): Boolean {
        val normalized = rawUrl.substringBefore('?').substringBefore('#').lowercase()
        return normalized.endsWith(".jpg") ||
            normalized.endsWith(".jpeg") ||
            normalized.endsWith(".png") ||
            normalized.endsWith(".gif") ||
            normalized.endsWith(".webp") ||
            normalized.endsWith(".bmp") ||
            normalized.endsWith(".svg")
    }
}
