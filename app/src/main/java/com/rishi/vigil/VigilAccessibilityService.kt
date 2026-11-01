package com.rishi.vigil

import android.accessibilityservice.AccessibilityService
import android.annotation.SuppressLint
import android.content.Intent
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo

// Reads the text of the app you were using when you opened Vigil (screen awareness).
// It does nothing in the background: no events are processed or stored.
class VigilAccessibilityService : AccessibilityService() {

    override fun onServiceConnected() { instance = this }

    override fun onUnbind(intent: Intent?): Boolean {
        instance = null
        return super.onUnbind(intent)
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {}   // not used
    override fun onInterrupt() {}

    // Called by AssistActivity: the snapshot taken when Vigil opened (if recent), else a live read
    fun screenText(): Pair<String, String>? {
        val recent = System.currentTimeMillis() - ScreenContext.capturedAt < 120_000
        val snap = ScreenContext.text
        if (recent && snap != null) return ScreenContext.app to snap
        return capture()
    }

    // Live read of the top app window that isn't Vigil → (package name, visible text)
    fun capture(): Pair<String, String>? {
        val window = windows
            .filter { it.type == AccessibilityWindowInfo.TYPE_APPLICATION }
            .sortedByDescending { it.layer }                                    // top-most first
            .firstOrNull { it.root?.packageName?.toString() != packageName }    // skip Vigil itself
        val root = window?.root
            ?: rootInActiveWindow?.takeIf { it.packageName?.toString() != packageName }
            ?: return null
        val lines = linkedSetOf<String>()                                       // set → no repeated lines
        collect(root, lines)
        return root.packageName.toString() to lines.joinToString("\n").take(4000)
    }

    // Walk the screen's view tree, collecting visible text and skipping password fields
    private fun collect(node: AccessibilityNodeInfo?, out: MutableSet<String>) {
        if (node == null) return
        if (!node.isPassword) {
            (node.text ?: node.contentDescription)?.toString()?.trim()
                ?.takeIf { it.isNotEmpty() }?.let { out += it }
        }
        for (i in 0 until node.childCount) collect(node.getChild(i), out)
    }

    companion object {
        @SuppressLint("StaticFieldLeak")
        @Volatile var instance: VigilAccessibilityService? = null
    }
}