package com.example.alisa

import android.accessibilityservice.AccessibilityService
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.Toast

/**
 * Управляет интерфейсом Telegram: поиск контакта -> открыть чат -> ввести текст -> «Отправить».
 * Подписи кнопок могут отличаться в разных версиях Telegram — поправьте списки ниже при необходимости.
 */
class AlisaAccessibilityService : AccessibilityService() {

    companion object {
        var instance: AlisaAccessibilityService? = null
    }

    private val handler = Handler(Looper.getMainLooper())
    private var contact = ""
    private var message = ""
    private var step = 0
    private var attempts = 0

    private val searchDescs = listOf("search", "поиск")
    private val sendDescs = listOf("send", "отправить")

    override fun onServiceConnected() { instance = this }
    override fun onAccessibilityEvent(event: AccessibilityEvent?) {}
    override fun onInterrupt() {}

    override fun onDestroy() {
        instance = null
        super.onDestroy()
    }

    fun startTelegramMessage(contact: String, message: String) {
        this.contact = contact
        this.message = message
        step = 0
        attempts = 0
        handler.removeCallbacksAndMessages(null)
        handler.postDelayed({ tick() }, 2000)
    }

    private fun tick() {
        if (attempts++ > 20) {
            toast("Не получилось: остановилась на шаге $step")
            return
        }
        val root = rootInActiveWindow
        if (root == null || root.packageName?.startsWith("org.telegram") != true) {
            retry(); return
        }
        when (step) {
            0 -> { // лупа
                val n = find(root) { descMatches(it, searchDescs) }
                if (n != null && click(n)) next() else retry()
            }
            1 -> { // имя в поиск
                val f = find(root) { it.isEditable }
                if (f != null && setText(f, contact)) next() else retry()
            }
            2 -> { // первый найденный чат
                val r = find(root) {
                    !it.isEditable && it.text?.toString()?.lowercase()?.contains(contact.lowercase()) == true
                }
                if (r != null && click(r)) next() else retry()
            }
            3 -> { // текст сообщения
                val f = find(root) { it.isEditable }
                if (f != null && setText(f, message)) next() else retry()
            }
            4 -> { // отправить
                val s = find(root) { descMatches(it, sendDescs) }
                if (s != null && click(s)) toast("Отправлено") else retry()
            }
        }
    }

    private fun next() {
        step++
        attempts = 0
        handler.postDelayed({ tick() }, 1000)
    }

    private fun retry() { handler.postDelayed({ tick() }, 700) }

    private fun descMatches(n: AccessibilityNodeInfo, words: List<String>): Boolean {
        val d = n.contentDescription?.toString()?.lowercase() ?: return false
        return words.any { d == it || d.startsWith(it) }
    }

    private fun find(node: AccessibilityNodeInfo?, match: (AccessibilityNodeInfo) -> Boolean): AccessibilityNodeInfo? {
        if (node == null) return null
        if (match(node)) return node
        for (i in 0 until node.childCount) {
            find(node.getChild(i), match)?.let { return it }
        }
        return null
    }

    private fun click(node: AccessibilityNodeInfo): Boolean {
        var n: AccessibilityNodeInfo? = node
        while (n != null) {
            if (n.isClickable) return n.performAction(AccessibilityNodeInfo.ACTION_CLICK)
            n = n.parent
        }
        return false
    }

    private fun setText(node: AccessibilityNodeInfo, text: String): Boolean {
        val args = Bundle().apply {
            putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text)
        }
        return node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
    }

    private fun toast(msg: String) {
        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
    }
}
