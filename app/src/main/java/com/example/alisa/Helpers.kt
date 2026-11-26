package com.example.alisa

import android.content.Context
import android.graphics.PixelFormat
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale
import kotlin.math.abs

/** Хранит ключ API на телефоне (в код и на GitHub он не попадает). */
object Prefs {
    private fun sp(ctx: Context) = ctx.getSharedPreferences("alisa", Context.MODE_PRIVATE)
    fun apiKey(ctx: Context): String = sp(ctx).getString("api_key", "") ?: ""
    fun setApiKey(ctx: Context, key: String) {
        sp(ctx).edit().putString("api_key", key.trim()).apply()
    }
}

/** Голос Алисы (синтез речи Android). */
object Speaker {
    private var tts: TextToSpeech? = null
    private var ready = false
    private val main = Handler(Looper.getMainLooper())
    @Volatile var isSpeaking = false
    @Volatile private var startedAt = 0L
    @Volatile private var lastEnd = 0L
    var onSpeakingChanged: ((Boolean) -> Unit)? = null
    var uiListener: ((Boolean) -> Unit)? = null

    /** Чтобы Алиса не слушала саму себя. */
    fun recentlySpoke(): Boolean {
        val now = System.currentTimeMillis()
        return (isSpeaking && now - startedAt < 30000) || now - lastEnd < 1500
    }

    fun init(ctx: Context) {
        if (tts != null) return
        tts = TextToSpeech(ctx.applicationContext) { status ->
            if (status == TextToSpeech.SUCCESS) {
                tts?.language = Locale("ru", "RU")
                ready = true
            }
        }
        tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) = change(true)
            override fun onDone(utteranceId: String?) = change(false)
            @Deprecated("Deprecated in Java")
            override fun onError(utteranceId: String?) = change(false)
        })
    }

    private fun change(speaking: Boolean) {
        isSpeaking = speaking
        if (speaking) startedAt = System.currentTimeMillis() else lastEnd = System.currentTimeMillis()
        main.post {
            onSpeakingChanged?.invoke(speaking)
            uiListener?.invoke(speaking)
        }
    }

    fun speak(text: String) {
        if (!ready) return
        isSpeaking = true
        startedAt = System.currentTimeMillis()
        tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, "alisa")
    }
}

/** Ответы на вопросы через Claude API. Нужен ключ, который вводится в приложении. */
object AiClient {
    fun ask(ctx: Context, question: String, onResult: (String) -> Unit) {
        val key = Prefs.apiKey(ctx)
        if (key.isBlank()) {
            onResult("Чтобы я отвечала на вопросы, добавьте ключ API в настройках приложения")
            return
        }
        Thread {
            val answer = try {
                request(key, question)
            } catch (e: Exception) {
                "Не получилось связаться с ИИ"
            }
            Handler(Looper.getMainLooper()).post { onResult(answer) }
        }.start()
    }

    private fun request(key: String, question: String): String {
        val body = JSONObject().apply {
            put("model", "claude-haiku-5-5")
            put("max_tokens", 300)
            put(
                "system",
                "Ты Алиса, голосовой ассистент в телефоне. Отвечай по-русски, коротко: " +
                    "одно-два предложения, без списков и без разметки."
            )
            put(
                "messages",
                JSONArray().put(JSONObject().put("role", "user").put("content", question))
            )
        }
        val conn = URL("https://api.anthropic.com/v1/messages").openConnection() as HttpURLConnection
        conn.requestMethod = "POST"
        conn.connectTimeout = 15000
        conn.readTimeout = 30000
        conn.setRequestProperty("content-type", "application/json")
        conn.setRequestProperty("x-api-key", key)
        conn.setRequestProperty("anthropic-version", "2023-06-01")
        conn.doOutput = true
        conn.outputStream.use { it.write(body.toString().toByteArray()) }

        val code = conn.responseCode
        val stream = (if (code in 200..299) conn.inputStream else conn.errorStream)
            ?: return "Нет ответа от ИИ"
        val text = stream.bufferedReader().use { it.readText() }
        if (code !in 200..299) {
            return when (code) {
                401 -> "Ключ не подходит. Проверьте его в настройках."
                429 -> "Слишком много запросов. Попробуйте позже."
                else -> "Ошибка ИИ, код $code"
            }
        }
        val content = JSONObject(text).getJSONArray("content")
        val sb = StringBuilder()
        for (i in 0 until content.length()) {
            val block = content.getJSONObject(i)
            if (block.optString("type") == "text") sb.append(block.optString("text"))
        }
        return sb.toString().ifBlank { "Не нашла, что ответить" }
    }
}

/** Маленькое круглое окошко с анимированной Алисой поверх всех приложений. Можно перетаскивать. */
class AvatarOverlay(private val ctx: Context, private val onTap: () -> Unit) {

    private val wm = ctx.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private var view: AlisaView? = null

    private fun dp(v: Int) = (v * ctx.resources.displayMetrics.density).toInt()

    private val params = WindowManager.LayoutParams(
        dp(110),
        dp(110),
        WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
        PixelFormat.TRANSLUCENT
    ).apply {
        gravity = Gravity.TOP or Gravity.START
        x = 24
        y = 300
    }

    fun show() {
        if (view != null || !Settings.canDrawOverlays(ctx)) return
        val v = AlisaView(ctx)
        v.circular = true
        v.setOnTouchListener(dragListener())
        try {
            wm.addView(v, params)
        } catch (e: Exception) {
            return
        }
        view = v
    }

    private fun dragListener() = object : View.OnTouchListener {
        var startX = 0
        var startY = 0
        var downX = 0f
        var downY = 0f
        var moved = false

        override fun onTouch(v: View, e: MotionEvent): Boolean {
            when (e.action) {
                MotionEvent.ACTION_DOWN -> {
                    startX = params.x
                    startY = params.y
                    downX = e.rawX
                    downY = e.rawY
                    moved = false
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = (e.rawX - downX).toInt()
                    val dy = (e.rawY - downY).toInt()
                    if (abs(dx) > 10 || abs(dy) > 10) moved = true
                    params.x = startX + dx
                    params.y = startY + dy
                    try {
                        wm.updateViewLayout(v, params)
                    } catch (ex: Exception) {
                    }
                }
                MotionEvent.ACTION_UP -> {
                    if (!moved) onTap()
                }
            }
            return true
        }
    }

    /** Когда Алиса говорит, она шевелит губами. */
    fun setSpeaking(speaking: Boolean) {
        view?.speaking = speaking
    }

    /** Короткий «кивок», когда Алиса услышала своё имя. */
    fun perk() {
        view?.perk()
    }

    fun hide() {
        view?.let {
            try {
                wm.removeView(it)
            } catch (e: Exception) {
            }
        }
        view = null
    }
}
