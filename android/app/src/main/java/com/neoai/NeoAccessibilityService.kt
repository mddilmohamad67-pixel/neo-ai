package com.neoai

import android.accessibilityservice.AccessibilityService
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo

/**
 * User-authorized UI assistance. It only reacts when the user turns automation
 * ON inside the app, never installs permissions, never hides activity, and only
 * operates on the screen the user is already looking at.
 */
class NeoAccessibilityService : AccessibilityService() {

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        enabled = true
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) = Unit

    override fun onInterrupt() = Unit

    override fun onDestroy() {
        enabled = false
        instance = null
        sendHandler.removeCallbacksAndMessages(null)
        super.onDestroy()
    }

    // ------------------------------------------------ WhatsApp send assist

    private val sendHandler = Handler(Looper.getMainLooper())
    @Volatile private var sendDeadline = 0L
    private var sendAttempts = 0

    /**
     * After wa.me opens the chat (message already prefilled), poll for
     * WhatsApp's send button for up to ~20 s and tap it once.
     * Only runs while the user has automation ON and accessibility enabled.
     */
    fun watchWhatsAppSend() {
        if (!userEnabledAutomation) return
        sendDeadline = SystemClock.uptimeMillis() + 20_000
        sendAttempts = 0
        sendHandler.removeCallbacksAndMessages(null)
        attemptSend(600)
    }

    private fun attemptSend(delay: Long) {
        sendHandler.postDelayed({
            if (!userEnabledAutomation || SystemClock.uptimeMillis() > sendDeadline) return@postDelayed
            val root = rootInActiveWindow
            if (root != null && root.packageName?.toString()?.startsWith("com.whatsapp") == true) {
                if (clickSend(root)) return@postDelayed
            }
            sendAttempts++
            if (sendAttempts < 20) attemptSend(700)
        }, delay)
    }

    private fun clickSend(root: AccessibilityNodeInfo): Boolean {
        for (node in root.findAccessibilityNodeInfosByViewId("com.whatsapp:id/send")) {
            if (clickNode(node)) return true
        }
        val fallback = findSendNode(root) ?: return false
        return clickNode(fallback)
    }

    private fun findSendNode(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        if (node.isVisibleToUser) {
            val id = node.viewIdResourceName.orEmpty().lowercase()
            val desc = node.contentDescription?.toString()?.lowercase().orEmpty()
            if (id.endsWith("/send") || id.contains("send_button") ||
                desc == "send" || desc.contains("bhej")
            ) {
                return node
            }
        }
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            val hit = findSendNode(child)
            if (hit != null) return hit
        }
        return null
    }

    private fun clickNode(start: AccessibilityNodeInfo): Boolean {
        var current: AccessibilityNodeInfo? = start
        var depth = 0
        while (depth < 5) {
            val node = current ?: return false
            if (node.isClickable) {
                return try {
                    node.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                } catch (_: Exception) {
                    false
                }
            }
            current = node.parent
            depth++
        }
        return false
    }

    fun global(action: Int): Boolean = runCatching { performGlobalAction(action) }.getOrDefault(false)

    fun clickText(text: String): Boolean {
        if (!userEnabledAutomation || text.isBlank()) return false
        val root = rootInActiveWindow ?: return false
        val target = find(root, text) ?: return false
        var current: AccessibilityNodeInfo? = target
        var guard = 0
        while (guard < 8) {
            val node = current ?: break
            if (node.isClickable) {
                return try {
                    node.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                } catch (_: Exception) {
                    false
                }
            }
            current = node.parent
            guard++
        }
        return runCatching { target.performAction(AccessibilityNodeInfo.ACTION_CLICK) }.getOrDefault(false)
    }

    fun typeText(text: String): Boolean {
        if (!userEnabledAutomation || text.isBlank()) return false
        val root = rootInActiveWindow ?: return false
        val focused = findFocusedEditable(root) ?: return false
        val args = Bundle().apply {
            putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text)
        }
        return runCatching {
            focused.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
        }.getOrDefault(false)
    }

    fun scroll(forward: Boolean): Boolean {
        if (!userEnabledAutomation) return false
        val root = rootInActiveWindow ?: return false
        val scrollable = findScrollable(root) ?: return false
        val action = if (forward) AccessibilityNodeInfo.ACTION_SCROLL_FORWARD
        else AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD
        return runCatching { scrollable.performAction(action) }.getOrDefault(false)
    }

    private fun find(node: AccessibilityNodeInfo, text: String): AccessibilityNodeInfo? {
        val haystack = listOfNotNull(node.text?.toString(), node.contentDescription?.toString())
        val wanted = text.lowercase()
        if (haystack.any { it.lowercase().contains(wanted) }) return node
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            val hit = find(child, text)
            if (hit != null) return hit
        }
        return null
    }

    private fun findFocusedEditable(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        if (node.isFocused && node.isEditable) return node
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            val hit = findFocusedEditable(child)
            if (hit != null) return hit
        }
        return null
    }

    private fun findScrollable(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        if (node.isScrollable) return node
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            val hit = findScrollable(child)
            if (hit != null) return hit
        }
        return null
    }

    companion object {
        @Volatile var instance: NeoAccessibilityService? = null
            private set

        @Volatile var enabled: Boolean = false
            private set

        @Volatile var userEnabledAutomation: Boolean = false

        fun scheduleWhatsAppSend() {
            instance?.watchWhatsAppSend()
        }
    }
}
