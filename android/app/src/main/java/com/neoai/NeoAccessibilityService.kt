package com.neoai

import android.accessibilityservice.AccessibilityService
import android.os.Bundle
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
        super.onDestroy()
    }

    fun global(action: Int): Boolean = runCatching { performGlobalAction(action) }.getOrDefault(false)

    fun clickText(text: String): Boolean {
        if (!userEnabledAutomation || text.isBlank()) return false
        val root = rootInActiveWindow ?: return false
        val target = find(root, text) ?: return false
        var node: AccessibilityNodeInfo? = target
        var guard = 0
        while (node != null && guard < 8) {
            if (node.isClickable) return runCatching {
                node.performAction(AccessibilityNodeInfo.ACTION_CLICK)
            }.getOrDefault(false)
            node = node.parent
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
    }
}
