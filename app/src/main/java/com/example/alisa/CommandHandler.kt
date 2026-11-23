package com.example.alisa

import android.content.Context
import android.content.Intent
import android.provider.Settings

/** Разбирает фразу и выполняет команду. say() — куда выводить ответ Алисы. */
class CommandHandler(private val ctx: Context, private val say: (String) -> Unit) {

    private val aliases = mapOf(
        "телеграм" to "telegram",
        "телеграмм" to "telegram",
        "ватсап" to "whatsapp",
        "ютуб" to "youtube",
        "камера" to "camera",
        "камеру" to "camera",
        "настройки" to "settings",
        "хром" to "chrome",
        "карты" to "maps"
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
        if (text.isBlank()) {
            say("Слушаю, говорите команду")
            return
        }
        say("Вы сказали: $text")

        val both = Regex("откр\\S*\\s+(.+?)\\s+и\\s+напиши\\s+(\\S+)\\s+(.+)").find(text)
        val onlyWrite = Regex("напиши\\s+(\\S+)\\s+(.+)").find(text)
        val onlyOpen = Regex("откр\\S*\\s+(.+)").find(text)

        when {
            both != null -> {
                val (app, contact, message) = both.destructured
                if (openApp(app)) sendTelegram(contact, message)
            }
            onlyWrite != null -> {
                val (contact, message) = onlyWrite.destructured
                if (openApp("телеграм")) sendTelegram(contact, message)
            }
            onlyOpen != null -> openApp(onlyOpen.groupValues[1])
            else -> say("Не поняла команду")
        }
    }

    private fun launch(intent: Intent) {
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        // Запускаем через службу специальных возможностей: из фона Android иначе может заблокировать запуск
        val c: Context = AlisaAccessibilityService.instance ?: ctx
        c.startActivity(intent)
    }

    private fun openApp(spoken: String): Boolean {
        val query = aliases[spoken.trim()] ?: spoken.trim()
        val pm = ctx.packageManager
        val launchers = pm.queryIntentActivities(
            Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER), 0
        )
        val match = launchers.firstOrNull {
            it.loadLabel(pm).toString().lowercase().contains(query)
        }
        val intent = match?.let { pm.getLaunchIntentForPackage(it.activityInfo.packageName) }
        return if (intent != null) {
            launch(intent)
            say("Открываю: $spoken")
            true
        } else {
            say("Не нашла приложение: $spoken")
            false
        }
    }

    private fun sendTelegram(spokenName: String, message: String) {
        val service = AlisaAccessibilityService.instance
        if (service == null) {
            say("Включите службу специальных возможностей для Алисы")
            launch(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
            return
        }
        // «Ивану» -> «Иван»: отрезаем окончание, поиск Telegram найдёт по началу имени
        val contact = if (spokenName.length > 4) spokenName.dropLast(1) else spokenName
        service.startTelegramMessage(contact, message)
    }
}
