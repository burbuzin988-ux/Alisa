package com.example.alisa

import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.media.AudioManager
import android.os.Build
import android.provider.AlarmClock
import android.provider.Settings
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Разбирает фразу и выполняет команду.
 * say() — куда выводить текст ответа, onHeard() — вызывается, когда Алиса услышала команду.
 */
class CommandHandler(
    private val ctx: Context,
    private val say: (String) -> Unit,
    private val onHeard: () -> Unit = {}
) {
    init {
        Speaker.init(ctx)
    }

    // Как человек говорит -> какие слова искать в названии приложения
    private val aliases: Map<String, List<String>> = mapOf(
        "телеграм" to listOf("telegram"),
        "телеграмм" to listOf("telegram"),
        "твич" to listOf("twitch"),
        "твитч" to listOf("twitch"),
        "ютуб" to listOf("youtube"),
        "ютьюб" to listOf("youtube"),
        "ватсап" to listOf("whatsapp"),
        "вотсап" to listOf("whatsapp"),
        "вацап" to listOf("whatsapp"),
        "вк" to listOf("vk", "вконтакте"),
        "вконтакте" to listOf("vk", "вконтакте"),
        "инстаграм" to listOf("instagram"),
        "тикток" to listOf("tiktok"),
        "дискорд" to listOf("discord"),
        "стим" to listOf("steam"),
        "спотифай" to listOf("spotify"),
        "нетфликс" to listOf("netflix"),
        "яндекс" to listOf("yandex", "яндекс"),
        "почта" to listOf("gmail", "почта", "mail"),
        "гмейл" to listOf("gmail"),
        "галерея" to listOf("gallery", "галерея", "photos", "фото"),
        "фото" to listOf("photos", "фото", "gallery", "галерея"),
        "калькулятор" to listOf("calculator", "калькулятор"),
        "часы" to listOf("clock", "часы"),
        "камера" to listOf("camera", "камера"),
        "камеру" to listOf("camera", "камера"),
        "настройки" to listOf("settings", "настройки"),
        "хром" to listOf("chrome"),
        "браузер" to listOf("chrome", "browser", "браузер"),
        "карты" to listOf("maps", "карты"),
        "плей маркет" to listOf("play store", "play маркет", "google play"),
        "гугл" to listOf("google"),
        "контакты" to listOf("contacts", "контакты"),
        "телефон" to listOf("phone", "телефон", "dialer"),
        "сообщения" to listOf("messages", "сообщения"),
        "музыка" to listOf("music", "музыка")
    )

    // Лишние слова, которые не входят в название приложения
    private val fillerWords = setOf("приложение", "приложуху", "программу", "пожалуйста", "мне")

    private val numberWords = mapOf(
        "один" to 1, "одну" to 1, "два" to 2, "две" to 2, "три" to 3, "четыре" to 4,
        "пять" to 5, "шесть" to 6, "семь" to 7, "восемь" to 8, "девять" to 9,
        "десять" to 10, "пятнадцать" to 15, "двадцать" to 20, "тридцать" to 30,
        "сорок" to 40, "пятьдесят" to 50, "шестьдесят" to 60
    )

    // После «напиши» эти слова значат просьбу к ИИ, а не имя контакта
    private val aiWords = setOf(
        "стих", "стихотворение", "историю", "рассказ", "письмо", "текст",
        "код", "сочинение", "песню", "сказку", "поздравление"
    )

    /** requireName = true: реагируем только если в фразе прозвучало «Алиса». */
    fun handle(raw: String, requireName: Boolean) {
        var text = raw.lowercase().trim()
        val name = Regex("алис\\S*[,.!?]?\\s*").find(text)
        if (name != null) {
            text = text.substring(name.range.last + 1).trim()
        } else if (requireName) {
            return
        }
        onHeard()
        if (text.isBlank()) {
            reply("Слушаю")
            return
        }
        say("Вы сказали: $text")

        val both = Regex("откр\\S*\\s+(.+?)\\s+и\\s+напиши\\s+(\\S+)\\s+(.+)").find(text)
        val onlyWrite = Regex("напиши\\s+(\\S+)\\s+(.+)").find(text)
            ?.takeIf { it.groupValues[1] !in aiWords }
        val onlyOpen = Regex("откр\\S*\\s+(.+)").find(text)

        when {
            text.contains("таймер") -> if (!timer(text)) ask(text)
            text.contains("будильник") || text.contains("разбуди") -> alarm(text)
            text.contains("фонар") -> torch(text)
            text.contains("громч") || text.contains("тише") || text.contains("громкост") -> volume(text)
            Regex("wi-?fi|вай-?фай|вайфай").containsMatchIn(text) -> wifi()
            text.contains("не беспокоить") -> doNotDisturb(text)
            text.contains("который час") || text.contains("сколько времени") -> tellTime()
            both != null -> {
                val (app, contact, message) = both.destructured
                if (openApp(app)) sendTelegram(contact, message)
            }
            onlyWrite != null -> {
                val (contact, message) = onlyWrite.destructured
                if (openApp("телеграм")) sendTelegram(contact, message)
            }
            onlyOpen != null -> openApp(onlyOpen.groupValues[1])
            else -> ask(text)
        }
    }

    private fun reply(text: String) {
        say(text)
        Speaker.speak(text)
    }

    private fun ask(question: String) {
        say("Думаю…")
        AiClient.ask(ctx, question) { answer -> reply(answer) }
    }

    private fun launch(intent: Intent): Boolean {
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        // Запускаем через службу специальных возможностей: из фона Android иначе может заблокировать запуск
        val c: Context = AlisaAccessibilityService.instance ?: ctx
        return try {
            c.startActivity(intent)
            true
        } catch (e: Exception) {
            reply("Не получилось это открыть")
            false
        }
    }

    // ---------- Таймер и будильник ----------

    private fun timer(text: String): Boolean {
        var seconds = 0
        if (text.contains("полчаса")) {
            seconds = 1800
        } else {
            val m = Regex("(?<![а-я])(?:(\\d+)\\s*|([а-я]+)\\s+)(сек\\S*|мин\\S*|час\\S*)")
                .find(text) ?: return false
            val n = m.groupValues[1].toIntOrNull() ?: numberWords[m.groupValues[2]] ?: 1
            val unit = m.groupValues[3]
            seconds = when {
                unit.startsWith("сек") -> n
                unit.startsWith("мин") -> n * 60
                else -> n * 3600
            }
        }
        if (seconds <= 0) return false
        val intent = Intent(AlarmClock.ACTION_SET_TIMER).apply {
            putExtra(AlarmClock.EXTRA_LENGTH, seconds)
            putExtra(AlarmClock.EXTRA_MESSAGE, "Алиса")
            putExtra(AlarmClock.EXTRA_SKIP_UI, true)
        }
        if (launch(intent)) reply("Таймер запущен")
        return true
    }

    private fun alarm(text: String) {
        if (text.contains("через")) {
            if (!timer(text)) reply("Не поняла, через сколько разбудить")
            return
        }
        val start = maxOf(text.indexOf("будильник"), text.indexOf("разбуди"))
        val m = Regex("(\\d{1,2})(?:[:.\\s](\\d{2}))?").find(text.substring(start))
        if (m == null) {
            reply("На какое время поставить будильник?")
            return
        }
        var hour = m.groupValues[1].toInt()
        val minute = m.groupValues[2].ifEmpty { "0" }.toInt()
        if ((text.contains("вечера") || text.contains("дня")) && hour in 1..11) hour += 12
        if (text.contains("ночи") && hour == 12) hour = 0
        if (hour !in 0..23 || minute !in 0..59) {
            reply("Не поняла время")
            return
        }
        val intent = Intent(AlarmClock.ACTION_SET_ALARM).apply {
            putExtra(AlarmClock.EXTRA_HOUR, hour)
            putExtra(AlarmClock.EXTRA_MINUTES, minute)
            putExtra(AlarmClock.EXTRA_MESSAGE, "Алиса")
            putExtra(AlarmClock.EXTRA_SKIP_UI, true)
        }
        if (launch(intent)) reply("Будильник на %02d:%02d".format(hour, minute))
    }

    private fun tellTime() {
        val t = SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date())
        reply("Сейчас $t")
    }

    // ---------- Управление телефоном ----------

    private fun torch(text: String) {
        val on = !(text.contains("выключ") || text.contains("отключ") || text.contains("погаси"))
        try {
            val cm = ctx.getSystemService(Context.CAMERA_SERVICE) as CameraManager
            val id = cm.cameraIdList.firstOrNull {
                cm.getCameraCharacteristics(it).get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true
            }
            if (id == null) {
                reply("На этом телефоне нет вспышки")
                return
            }
            cm.setTorchMode(id, on)
            reply(if (on) "Фонарик включён" else "Фонарик выключен")
        } catch (e: Exception) {
            reply("Не получилось управлять фонариком")
        }
    }

    private fun volume(text: String) {
        val am = ctx.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        val percent = Regex("громкост\\S*\\s+(?:на\\s+)?(\\d+)").find(text)
            ?.groupValues?.get(1)?.toIntOrNull()
        when {
            percent != null -> {
                val max = am.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
                am.setStreamVolume(
                    AudioManager.STREAM_MUSIC,
                    (max * percent / 100).coerceIn(0, max),
                    AudioManager.FLAG_SHOW_UI
                )
                reply("Громкость $percent процентов")
            }
            text.contains("громч") -> {
                repeat(3) {
                    am.adjustStreamVolume(
                        AudioManager.STREAM_MUSIC, AudioManager.ADJUST_RAISE, AudioManager.FLAG_SHOW_UI
                    )
                }
                reply("Сделала громче")
            }
            else -> {
                repeat(3) {
                    am.adjustStreamVolume(
                        AudioManager.STREAM_MUSIC, AudioManager.ADJUST_LOWER, AudioManager.FLAG_SHOW_UI
                    )
                }
                reply("Сделала тише")
            }
        }
    }

    private fun wifi() {
        reply("Android не разрешает приложениям переключать Wi-Fi самим. Открываю панель.")
        val action = if (Build.VERSION.SDK_INT >= 29) {
            Settings.Panel.ACTION_INTERNET_CONNECTIVITY
        } else {
            Settings.ACTION_WIFI_SETTINGS
        }
        launch(Intent(action))
    }

    private fun doNotDisturb(text: String) {
        val nm = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (!nm.isNotificationPolicyAccessGranted) {
            reply("Разрешите Алисе управлять режимом «Не беспокоить»")
            launch(Intent(Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS))
            return
        }
        val on = !(text.contains("выключ") || text.contains("отключ"))
        nm.setInterruptionFilter(
            if (on) NotificationManager.INTERRUPTION_FILTER_PRIORITY
            else NotificationManager.INTERRUPTION_FILTER_ALL
        )
        reply(if (on) "Режим «Не беспокоить» включён" else "Режим «Не беспокоить» выключен")
    }

    // ---------- Приложения и Telegram ----------

    private fun openApp(spoken: String): Boolean {
        val name = spoken.lowercase().split(" ")
            .filter { it.isNotBlank() && it !in fillerWords }
            .joinToString(" ")
        val candidates = (aliases[name] ?: emptyList()) + name
        val pm = ctx.packageManager
        val labels = pm.queryIntentActivities(
            Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER), 0
        ).map { it to it.loadLabel(pm).toString().lowercase() }
        // Сначала точное совпадение названия, потом частичное
        val match = labels.firstOrNull { (_, l) -> candidates.any { l == it } }
            ?: labels.firstOrNull { (_, l) -> candidates.any { l.contains(it) } }
        val intent = match?.let { pm.getLaunchIntentForPackage(it.first.activityInfo.packageName) }
        return if (intent != null) {
            if (launch(intent)) {
                reply("Открываю: $name")
                true
            } else {
                false
            }
        } else {
            reply("Не нашла приложение: $name")
            false
        }
    }

    private fun sendTelegram(spokenName: String, message: String) {
        val service = AlisaAccessibilityService.instance
        if (service == null) {
            reply("Включите службу специальных возможностей для Алисы")
            launch(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
            return
        }
        // «Ивану» -> «Иван»: отрезаем окончание, поиск Telegram найдёт по началу имени
        val contact = if (spokenName.length > 4) spokenName.dropLast(1) else spokenName
        service.startTelegramMessage(contact, message)
    }
}
