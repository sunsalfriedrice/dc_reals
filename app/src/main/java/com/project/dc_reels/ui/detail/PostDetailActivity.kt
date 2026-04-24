package com.project.dc_reels.ui.detail

import android.annotation.SuppressLint
import android.os.Bundle
import android.view.View
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.ProgressBar
import androidx.appcompat.app.AppCompatActivity
import com.project.dc_reels.R

class PostDetailActivity : AppCompatActivity() {

    private lateinit var webView: WebView
    private lateinit var loading: ProgressBar

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
        webView.webChromeClient = WebChromeClient()
        webView.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
                return false
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
    }
}

