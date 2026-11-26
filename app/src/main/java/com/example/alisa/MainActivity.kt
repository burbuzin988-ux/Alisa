package com.example.alisa

import android.Manifest
import android.app.ActivityManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.view.MotionEvent
import androidx.activity.result.contract.ActivityResultContracts
import java.io.File
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.text.InputType
import android.view.Gravity
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.SwitchCompat
import androidx.core.content.ContextCompat

class MainActivity : AppCompatActivity() {

    private lateinit var alisa: AlisaView
    private lateinit var bubble: TextView
    private lateinit var commands: CommandHandler
    private var recognizer: SpeechRecognizer? = null
    private val lines = mutableListOf<String>()

    private val pickPhoto = registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) savePhoto(uri)
    }

    /** Копирует выбранную картинку в память приложения (на GitHub она не попадает). */
    private fun savePhoto(uri: Uri) {
        try {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
            var sample = 1
            while (bounds.outWidth / sample > 1800 || bounds.outHeight / sample > 1800) sample *= 2
            val opts = BitmapFactory.Options().apply { inSampleSize = sample }
            val bmp = contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, opts) }
            if (bmp == null) {
                log("Не получилось открыть картинку")
                return
            }
            File(filesDir, "alisa_photo.jpg").outputStream().use {
                bmp.compress(Bitmap.CompressFormat.JPEG, 92, it)
            }
            alisa.reloadPhoto()
            log("Картинка сохранена. Нажми на лицо, чтобы круглое окошко показывало его")
        } catch (e: Exception) {
            log("Не получилось сохранить картинку")
        }
    }

    private fun removePhoto() {
        File(filesDir, "alisa_photo.jpg").delete()
        alisa.reloadPhoto()
        log("Вернулась нарисованная Алиса")
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private fun round(color: Int, radiusDp: Int) = GradientDrawable().apply {
        setColor(color)
        cornerRadius = dp(radiusDp).toFloat()
    }

    /** Показывает последние реплики в «облачке» под Алисой. */
    private fun log(msg: String) {
        lines.add(msg)
        while (lines.size > 4) lines.removeAt(0)
        bubble.text = lines.joinToString("\n")
    }

    @Suppress("DEPRECATION")
    private fun serviceRunning(): Boolean {
        val am = getSystemService(ACTIVITY_SERVICE) as ActivityManager
        return am.getRunningServices(100).any { it.service.className == ListenService::class.java.name }
    }

    private fun bigButton(label: String, onClick: () -> Unit) = Button(this).apply {
        text = label
        isAllCaps = false
        textSize = 18f
        setTextColor(Color.WHITE)
        stateListAnimator = null
        background = GradientDrawable(
            GradientDrawable.Orientation.LEFT_RIGHT,
            intArrayOf(Color.parseColor("#8E36B0"), Color.parseColor("#C0408F"))
        ).apply { cornerRadius = dp(28).toFloat() }
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(56)).apply {
            topMargin = dp(14)
        }
        setOnClickListener { onClick() }
    }

    private fun smallButton(label: String, onClick: () -> Unit) = Button(this).apply {
        text = label
        isAllCaps = false
        textSize = 15f
        setTextColor(Color.parseColor("#E6DAFF"))
        stateListAnimator = null
        background = GradientDrawable().apply {
            setColor(Color.TRANSPARENT)
            setStroke(dp(1), Color.parseColor("#8A6FD0"))
            cornerRadius = dp(22).toFloat()
        }
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(46)).apply {
            topMargin = dp(10)
        }
        setOnClickListener { onClick() }
    }

    private fun label(text: String, sizeSp: Float, color: String, bold: Boolean = false) = TextView(this).apply {
        this.text = text
        textSize = sizeSp
        setTextColor(Color.parseColor(color))
        if (bold) setTypeface(typeface, android.graphics.Typeface.BOLD)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = Color.parseColor("#1B0F3D")
        window.navigationBarColor = Color.parseColor("#1B0F3D")

        alisa = AlisaView(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { gravity = Gravity.CENTER_HORIZONTAL }
            setOnTouchListener { _, e ->
                if (e.action == MotionEvent.ACTION_UP && setFocusFromTouch(e.x, e.y)) {
                    log("Круглое окошко будет показывать это место (выключи и включи фоновый режим)")
                }
                true
            }
        }
        commands = CommandHandler(
            applicationContext,
            say = { log(it) },
            onHeard = { alisa.perk() }
        )

        val title = label("Алиса", 28f, "#FFFFFF", true).apply {
            gravity = Gravity.CENTER
            setPadding(0, dp(12), 0, 0)
        }
        val subtitle = label("твой голосовой помощник", 14f, "#CDB8F5").apply { gravity = Gravity.CENTER }

        bubble = label("Привет! Нажми кнопку или скажи «Алиса…»", 16f, "#FFFFFF").apply {
            setPadding(dp(16), dp(14), dp(16), dp(14))
            background = round(Color.parseColor("#33FFFFFF"), 18)
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                topMargin = dp(16)
            }
        }

        val listenSwitch = SwitchCompat(this).apply {
            isChecked = serviceRunning()
            setOnCheckedChangeListener { _, on ->
                val svc = Intent(this@MainActivity, ListenService::class.java)
                if (on) {
                    ContextCompat.startForegroundService(this@MainActivity, svc)
                    log(
                        if (Settings.canDrawOverlays(this@MainActivity)) "Слушаю в фоне. Скажи: «Алиса, открой твич»"
                        else "Слушаю в фоне. Чтобы появилось окошко Алисы, разреши его ниже"
                    )
                } else {
                    stopService(svc)
                    log("Фоновое прослушивание выключено")
                }
            }
        }
        val switchRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(16), dp(12), dp(16), dp(12))
            background = round(Color.parseColor("#2E1B5C"), 18)
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                topMargin = dp(16)
            }
            addView(label("Слушать «Алиса…» в фоне", 16f, "#FFFFFF").apply {
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            })
            addView(listenSwitch)
        }

        val keyField = EditText(this).apply {
            hint = "Ключ API для ответов на вопросы"
            setHintTextColor(Color.parseColor("#8A7BB8"))
            setTextColor(Color.WHITE)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            setSingleLine()
        }

        val settingsCard = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(14), dp(16), dp(16))
            background = round(Color.parseColor("#2E1B5C"), 18)
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                topMargin = dp(16)
            }
            addView(label("Настройки", 16f, "#FFFFFF", true))
            addView(smallButton("Окошко Алисы поверх экрана") {
                startActivity(
                    Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName"))
                )
            })
            addView(smallButton("Выбрать картинку Алисы") { pickPhoto.launch("image/*") })
            addView(smallButton("Вернуть нарисованную Алису") { removePhoto() })
            addView(smallButton("Специальные возможности (для Telegram)") {
                startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
            })
            addView(keyField)
            addView(smallButton("Сохранить ключ") {
                Prefs.setApiKey(this@MainActivity, keyField.text.toString())
                keyField.setText("")
                log("Ключ сохранён")
            })
        }

        val hint = label(
            "Примеры: «Алиса, открой твич», «поставь таймер на 10 минут», «включи фонарик», «который час», " +
                "или просто задай любой вопрос. Нажми на лицо на картинке, чтобы выбрать, что видно в круглом окошке.",
            13f, "#A99AD6"
        ).apply { setPadding(dp(4), dp(16), dp(4), dp(8)) }

        val column = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(24), dp(20), dp(24))
            addView(alisa)
            addView(title)
            addView(subtitle)
            addView(bubble)
            addView(bigButton("🎤  Сказать команду") { startListening() })
            addView(switchRow)
            addView(settingsCard)
            addView(hint)
        }
        setContentView(ScrollView(this).apply {
            isFillViewport = true
            background = GradientDrawable(
                GradientDrawable.Orientation.TOP_BOTTOM,
                intArrayOf(Color.parseColor("#1B0F3D"), Color.parseColor("#3A1B72"))
            )
            addView(column)
        })

        val perms = mutableListOf(Manifest.permission.RECORD_AUDIO)
        if (Build.VERSION.SDK_INT >= 33) perms.add(Manifest.permission.POST_NOTIFICATIONS)
        val missing = perms.filter {
            checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED
        }
        if (missing.isNotEmpty()) requestPermissions(missing.toTypedArray(), 1)
    }

    override fun onResume() {
        super.onResume()
        Speaker.uiListener = { speaking -> alisa.speaking = speaking }
    }

    override fun onPause() {
        Speaker.uiListener = null
        super.onPause()
    }

    private fun startListening() {
        if (!SpeechRecognizer.isRecognitionAvailable(this)) {
            log("Распознавание речи недоступно на этом телефоне")
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
                    else log("Не расслышала")
                }
                override fun onError(error: Int) { log("Ошибка распознавания ($error)") }
                override fun onReadyForSpeech(params: Bundle?) { bubble.text = "Слушаю…" }
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
