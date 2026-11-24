package com.example.alisa

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.text.InputType
import android.view.Gravity
import android.widget.Button
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat

class MainActivity : AppCompatActivity() {

    private lateinit var status: TextView
    private lateinit var commands: CommandHandler
    private var recognizer: SpeechRecognizer? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        status = TextView(this).apply {
            text = "Привет! Я Алиса. Нажмите кнопку и скажите команду."
            textSize = 16f
            gravity = Gravity.CENTER
            setPadding(0, 24, 0, 24)
        }
        commands = CommandHandler(applicationContext, say = { status.text = it })

        val avatar = ImageView(this).apply {
            setImageResource(R.drawable.alisa_avatar)
            layoutParams = LinearLayout.LayoutParams(420, 420)
        }
        val title = TextView(this).apply {
            text = "Алиса"
            textSize = 28f
            gravity = Gravity.CENTER
        }
        val keyField = EditText(this).apply {
            hint = "Ключ API для ответов на вопросы (необязательно)"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            setSingleLine()
        }

        fun button(label: String, onClick: () -> Unit) = Button(this).apply {
            text = label
            setOnClickListener { onClick() }
        }

        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(48, 48, 48, 48)
            addView(avatar)
            addView(title)
            addView(status)
            addView(button("🎤 Сказать команду") { startListening() })
            addView(button("Включить «Эй, Алиса»") {
                ContextCompat.startForegroundService(
                    this@MainActivity, Intent(this@MainActivity, ListenService::class.java)
                )
                status.text = "Слушаю в фоне. Говорите: «Алиса, открой телеграм»"
            })
            addView(button("Выключить прослушивание") {
                stopService(Intent(this@MainActivity, ListenService::class.java))
                status.text = "Фоновое прослушивание выключено"
            })
            addView(button("Разрешить окошко Алисы поверх экрана") {
                startActivity(
                    Intent(
                        Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                        Uri.parse("package:$packageName")
                    )
                )
            })
            addView(button("Специальные возможности") {
                startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
            })
            addView(keyField)
            addView(button("Сохранить ключ") {
                Prefs.setApiKey(this@MainActivity, keyField.text.toString())
                keyField.setText("")
                status.text = "Ключ сохранён"
            })
        }
        setContentView(ScrollView(this).apply { addView(layout) })

        val perms = mutableListOf(Manifest.permission.RECORD_AUDIO)
        if (Build.VERSION.SDK_INT >= 33) perms.add(Manifest.permission.POST_NOTIFICATIONS)
        val missing = perms.filter {
            checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED
        }
        if (missing.isNotEmpty()) requestPermissions(missing.toTypedArray(), 1)
    }

    private fun startListening() {
        if (!SpeechRecognizer.isRecognitionAvailable(this)) {
            status.text = "Распознавание речи недоступно на этом телефоне"
            return
        }
        recognizer?.destroy()
        recognizer = SpeechRecognizer.createSpeechRecognizer(this).apply {
            setRecognitionListener(object : RecognitionListener {
                override fun onResults(results: Bundle?) {
                    val text = results
                        ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                        ?.firstOrNull()
                    if (text != null) commands.handle(text, requireName = false)
                    else status.text = "Не расслышала"
                }
                override fun onError(error: Int) { status.text = "Ошибка распознавания ($error)" }
                override fun onReadyForSpeech(params: Bundle?) { status.text = "Слушаю…" }
                override fun onBeginningOfSpeech() {}
                override fun onRmsChanged(rmsdB: Float) {}
                override fun onBufferReceived(buffer: ByteArray?) {}
                override fun onEndOfSpeech() {}
                override fun onPartialResults(partialResults: Bundle?) {}
                override fun onEvent(eventType: Int, params: Bundle?) {}
            })
        }
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, "ru-RU")
        }
        recognizer?.startListening(intent)
    }

    override fun onDestroy() {
        recognizer?.destroy()
        super.onDestroy()
    }
}
