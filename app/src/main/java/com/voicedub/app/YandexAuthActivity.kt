package com.voicedub.app

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.view.WindowManager
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/** VOT account in app memory only. No token is sent to webpages or the voice server. */
object VotAccount {
    private var token = ""
    private var expires = 0L
    private var login = ""
    @Synchronized fun set(value: String, until: Long, username: String) {
        if (value.isBlank() || until <= System.currentTimeMillis()) { clear(); return }
        token = value; expires = until; login = username
    }
    @Synchronized fun username(): String {
        if (expires <= System.currentTimeMillis()) clear()
        return if (token.isEmpty()) "вход не выполнен" else login.ifBlank { "аккаунт подключён" }
    }
    @Synchronized fun clear() { token = ""; expires = 0L; login = "" }
}

/** Dedicated process and WebView storage, without browser probes, JS bridges or credential interception. */
class YandexAuthActivity : Activity() {
    companion object { private var storageInitialized = false }
    private lateinit var web: WebView
    private lateinit var message: TextView
    private lateinit var attempt: VotAuthAttempt
    private var exchanging = false
    @Volatile private var destroyed = false
    @Volatile private var activeConnection: HttpURLConnection? = null
    private val clientId = "1666fbe22f3749c581002a4f97b2592d" // Public VOT client, from user-supplied MIT script.
    private val redirect = "https://oauth.yandex.ru/verification_code"

    @Suppress("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        if (!storageInitialized) { WebView.setDataDirectorySuffix("yandex-auth"); storageInitialized = true }
        attempt = VotAuthAttempt.create()
        val layout = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        message = TextView(this).apply {
            text = "Вход через публичный OAuth-клиент VOT из присланного скрипта. Пароль вводится только на странице Яндекса. Токен останется в памяти VoiceDub; озвучка VOT пока не подключена."
            setPadding(18, 12, 18, 12)
        }
        layout.addView(message)
        layout.addView(Button(this).apply { text = "Отмена"; setOnClickListener { finish() } })
        web = WebView(this)
        web.settings.javaScriptEnabled = true
        web.settings.domStorageEnabled = true
        web.settings.allowFileAccess = false
        web.settings.allowContentAccess = false
        web.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
                if (request?.isForMainFrame != true) return false
                val url = request.url.toString()
                if (handleCallback(url)) return true
                val host = request.url.host.orEmpty()
                val allowed = listOf("yandex.ru", "yandex.com", "ya.ru", "yandex.net").any { host == it || host.endsWith(".$it") }
                if (request.url.scheme != "https" || !allowed) {
                    message.text = "Переход за пределы HTTPS-сайтов Яндекса остановлен"
                    return true
                }
                return false
            }
            override fun onPageStarted(view: WebView?, url: String?, favicon: android.graphics.Bitmap?) {
                if (url != null && handleCallback(url)) view?.stopLoading()
            }
        }
        layout.addView(web, LinearLayout.LayoutParams(-1, 0, 1f))
        setContentView(layout)
        val params = mapOf("client_id" to clientId, "response_type" to "code", "redirect_uri" to redirect,
            "state" to attempt.state, "code_challenge" to attempt.challenge, "code_challenge_method" to "S256", "force_confirm" to "1")
        web.loadUrl("https://oauth.yandex.ru/authorize?" + form(params))
    }

    private fun handleCallback(url: String): Boolean {
        val uri = try { java.net.URI(url) } catch (_: Exception) { return false }
        if (uri.host != "oauth.yandex.ru" || uri.path != "/verification_code") return false
        if (exchanging) return true
        val code = attempt.consumeCallback(url)
        if (code == null) { message.text = "Яндекс не подтвердил вход или проверка ответа не пройдена"; return true }
        exchanging = true
        web.visibility = android.view.View.GONE
        message.text = "Яндекс подтвердил код. Завершаю вход…"
        Thread({
            try {
                val result = request("https://oauth.yandex.ru/token", form(mapOf(
                    "grant_type" to "authorization_code", "code" to code, "client_id" to clientId,
                    "redirect_uri" to redirect, "code_verifier" to attempt.verifier)))
                val token = result.getString("access_token")
                val lifetime = result.getLong("expires_in").coerceAtMost(366L * 24 * 60 * 60)
                check(token.isNotBlank() && lifetime > 0)
                val user = request("https://login.yandex.ru/info?format=json", null, token)
                runOnUiThread {
                    if (!destroyed && !isFinishing) {
                        setResult(RESULT_OK, Intent().putExtra("token", token)
                            .putExtra("expires", System.currentTimeMillis() + lifetime * 1000)
                            .putExtra("username", user.optString("login").take(120)))
                        finish()
                    }
                }
            } catch (_: Exception) { runOnUiThread {
                if (!destroyed) message.text = "Не удалось завершить вход Яндекса. Закройте экран и повторите. API-ключ вводить не нужно."
            } }
        }, "voicedub-yandex-auth").start()
        return true
    }

    private fun form(values: Map<String, String>) = values.entries.joinToString("&") {
        URLEncoder.encode(it.key, "UTF-8") + "=" + URLEncoder.encode(it.value, "UTF-8")
    }

    private fun request(url: String, body: String?, token: String? = null): JSONObject {
        check(!destroyed)
        val conn = URL(url).openConnection() as HttpURLConnection
        activeConnection = conn
        try {
            conn.instanceFollowRedirects = false
            conn.connectTimeout = 10000; conn.readTimeout = 15000
            if (token != null) conn.setRequestProperty("Authorization", "OAuth $token")
            if (body != null) {
                conn.requestMethod = "POST"; conn.doOutput = true
                conn.setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
                conn.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            }
            check(conn.responseCode == 200)
            return JSONObject(conn.inputStream.use { String(it.readBytesBounded(65536), Charsets.UTF_8) })
        } finally { activeConnection = null; conn.disconnect() }
    }

    override fun onDestroy() {
        destroyed = true
        activeConnection?.disconnect()
        if (::web.isInitialized) { web.stopLoading(); web.destroy() }
        super.onDestroy()
    }
}
