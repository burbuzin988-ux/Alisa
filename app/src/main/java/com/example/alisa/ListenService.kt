package com.example.alisa

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.widget.Toast
import androidx.core.app.ServiceCompat

/** Постоянно слушает, реагирует на фразы со словом «Алиса» и показывает окошко с аватаром. */
class ListenService : Service() {

    private val handler = Handler(Looper.getMainLooper())
    private var recognizer: SpeechRecognizer? = null
    private lateinit var commands: CommandHandler
    private var overlay: AvatarOverlay? = null
    private var running = true

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        Speaker.init(this)

        overlay = AvatarOverlay(this) {
            startActivity(
                Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        }
        overlay?.show()
        Speaker.onSpeakingChanged = { speaking -> overlay?.setSpeaking(speaking) }

        commands = CommandHandler(
            this,
            say = { msg -> Toast.makeText(this, msg, Toast.LENGTH_SHORT).show() },
            onHeard = { overlay?.perk() }
        )

        val channelId = "alisa_listen"
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(channelId, "Алиса слушает", NotificationManager.IMPORTANCE_LOW)
        )
        val notification = Notification.Builder(this, channelId)
            .setContentTitle("Алиса слушает")
            .setContentText("Скажите: «Алиса, открой телеграм»")
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .build()
        ServiceCompat.startForeground(
            this, 1, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
        )
        listen()
    }

    private fun listen() {
        if (!running) return
        if (Speaker.recentlySpoke()) {
            restart()
            return
        }
        recognizer?.destroy()
        recognizer = SpeechRecognizer.createSpeechRecognizer(this).apply {
            setRecognitionListener(object : RecognitionListener {
                override fun onResults(results: Bundle?) {
                    val text = results
                        ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                        ?.firstOrNull()
                    if (text != null && !Speaker.recentlySpoke()) {
                        commands.handle(text, requireName = true)
                    }
                    restart()
                }
                override fun onError(error: Int) { restart() }
                override fun onReadyForSpeech(params: Bundle?) {}
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

    private fun restart() {
        handler.postDelayed({ listen() }, 600)
    }

    override fun onDestroy() {
        running = false
        handler.removeCallbacksAndMessages(null)
        recognizer?.destroy()
        Speaker.onSpeakingChanged = null
        overlay?.hide()
        super.onDestroy()
    }
}
