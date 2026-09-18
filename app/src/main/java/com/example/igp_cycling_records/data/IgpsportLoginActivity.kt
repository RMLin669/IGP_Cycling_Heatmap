package com.example.igp_cycling_heatmap.data

import android.annotation.SuppressLint
import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.util.Log
import android.view.Gravity
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class IgpsportLoginActivity : ComponentActivity() {
    companion object {
        const val RESULT_TOKEN = "token"
        const val RESULT_USERNAME = "username"
        private const val TAG = "IgpsportLogin"
        private const val MOBILE_UA =
            "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 " +
                "(KHTML, like Gecko) Chrome/131.0.0.0 Mobile Safari/537.36"
    }

    private lateinit var webView: WebView
    private lateinit var statusText: TextView
    private var detected = false
    private var reloadCount = 0
    private var httpErrorCount = 0
    private lateinit var backCallback: OnBackPressedCallback

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(buildContentView())
        backCallback = object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (!detected) {
                    detected = true
                    setResult(android.app.Activity.RESULT_CANCELED)
                }
                finish()
            }
        }
        onBackPressedDispatcher.addCallback(this, backCallback)
        startLogin()
    }

    private fun buildContentView(): ViewGroup {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.WHITE)
        }
        ViewCompat.setOnApplyWindowInsetsListener(root) { view, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            view.setPadding(
                systemBars.left,
                systemBars.top,
                systemBars.right,
                systemBars.bottom,
            )
            insets
        }

        val titleBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(20, 18, 20, 12)
        }
        val close = TextView(this).apply {
            text = "关闭"
            textSize = 16f
            setTextColor(Color.parseColor("#1668DC"))
            setOnClickListener {
                detected = true
                setResult(android.app.Activity.RESULT_CANCELED)
                finish()
            }
        }
        statusText = TextView(this).apply {
            text = "正在打开 iGPSPORT 登录页"
            textSize = 14f
            setTextColor(Color.parseColor("#666666"))
            gravity = Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }
        val spacer = TextView(this).apply { text = "     " }
        titleBar.addView(close)
        titleBar.addView(statusText)
        titleBar.addView(spacer)
        root.addView(titleBar)

        val progress = ProgressBar(this).apply {
            isIndeterminate = true
            visibility = ProgressBar.GONE
        }
        root.addView(progress, LinearLayout.LayoutParams.MATCH_PARENT, 3)

        webView = WebView(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                0,
                1f,
            )
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.databaseEnabled = true
            settings.javaScriptCanOpenWindowsAutomatically = true
            settings.allowContentAccess = true
            settings.mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
            settings.cacheMode = WebSettings.LOAD_DEFAULT
            settings.userAgentString = MOBILE_UA
            webViewClient = object : WebViewClient() {
                override fun onPageFinished(view: WebView?, url: String?) {
                    super.onPageFinished(view, url)
                    if (!detected) {
                        statusText.text = "请登录 iGPSPORT，登录成功会自动返回"
                        view?.postDelayed({ detectLogin() }, 600)
                    }
                }

                override fun onReceivedHttpError(
                    view: WebView?,
                    request: WebResourceRequest?,
                    errorResponse: WebResourceResponse?,
                ) {
                    super.onReceivedHttpError(view, request, errorResponse)
                    val code = errorResponse?.statusCode ?: 0
                    if (!detected && httpErrorCount < 1 &&
                        code in listOf(403, 404, 500, 502, 503)
                    ) {
                        httpErrorCount++
                        Log.w(TAG, "iGPSPORT login HTTP $code, reload once")
                        view?.postDelayed({ if (!detected) view.reload() }, 500)
                    }
                }
            }
            webChromeClient = object : WebChromeClient() {
                override fun onProgressChanged(view: WebView?, newProgress: Int) {
                    progress.visibility =
                        if (newProgress < 100) ProgressBar.VISIBLE else ProgressBar.GONE
                }
            }
        }
        root.addView(webView)
        return root
    }

    private fun startLogin() {
        CookieManager.getInstance().setAcceptCookie(true)
        CookieManager.getInstance().acceptThirdPartyCookies(webView)
        webView.loadUrl(IgpsportApi.LOGIN_URL)
        webView.postDelayed({
            if (!detected && !isFinishing) detectLogin()
        }, 1500)
    }

    private fun detectLogin() {
        if (detected || isFinishing) return
        webView.evaluateJavascript(
            """
            (function() {
                try {
                    var raw = localStorage.getItem('persist:app-store');
                    if (!raw) return 'NO_PERSIST';
                    var obj = JSON.parse(raw);
                    var global = JSON.parse(obj.global || '{}');
                    return global.token || 'NO_TOKEN';
                } catch(e) { return 'ERR:' + e.message; }
            })()
            """.trimIndent(),
        ) { value ->
            if (detected || isFinishing) return@evaluateJavascript
            val raw = value
                ?.removeSurrounding("\"")
                ?.replace("\\u0022", "\"")
                ?.replace("\\/", "/")
                .orEmpty()
            if (!raw.startsWith("Bearer ") || raw.length <= 80) {
                webView.postDelayed({ detectLogin() }, 1000)
                return@evaluateJavascript
            }
            val token = raw.removePrefix("Bearer ")
            statusText.text = "正在验证登录状态"
            lifecycleScope.launch(Dispatchers.IO) {
                val username = try {
                    IgpsportApi().getUsername(token)
                } catch (_: Exception) {
                    null
                }
                runOnUiThread {
                    if (username != null) {
                        Log.i(TAG, "iGPSPORT token valid, user=$username")
                        detected = true
                        setResult(
                            android.app.Activity.RESULT_OK,
                            Intent()
                                .putExtra(RESULT_TOKEN, token)
                                .putExtra(RESULT_USERNAME, username),
                        )
                        finish()
                    } else if (reloadCount < 2) {
                        reloadCount++
                        webView.evaluateJavascript("localStorage.clear();") {
                            CookieManager.getInstance().removeAllCookies {
                                webView.postDelayed({ webView.reload() }, 300)
                            }
                        }
                    } else {
                        statusText.text = "登录态校验失败，请关闭后重试"
                    }
                }
            }
        }
    }

    override fun onDestroy() {
        webView.destroy()
        super.onDestroy()
    }
}
