package com.hvkeyn.ceditneuro.access

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.graphics.Rect
import android.view.Display
import android.os.Bundle
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import com.hvkeyn.ceditneuro.tools.MobileNode
import com.hvkeyn.ceditneuro.tools.MobileSnapshot
import kotlinx.coroutines.delay

/**
 * Optional, user-enabled accessibility bridge for mobile control.
 * It is deliberately separate from Shizuku/root: the person enables it in Android
 * Settings, and it only reads the active window while the agent asks for a snapshot.
 */
class MobileAccessibilityService : AccessibilityService() {

    private val nodes = LinkedHashMap<String, AccessibilityNodeInfo>()

    override fun onServiceConnected() {
        instance = this
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) = Unit

    override fun onInterrupt() = Unit

    override fun onUnbind(intent: Intent?): Boolean {
        if (instance === this) instance = null
        recycle()
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        if (instance === this) instance = null
        recycle()
        super.onDestroy()
    }

    private fun recycle() {
        nodes.values.forEach { runCatching { it.recycle() } }
        nodes.clear()
    }

    fun snapshot(displayId: Int = Display.DEFAULT_DISPLAY): MobileSnapshot? {
        val roots = if (displayId == Display.DEFAULT_DISPLAY) {
            listOfNotNull(rootInActiveWindow)
        } else {
            windowsOnAllDisplays.get(displayId)?.mapNotNull { runCatching { it.root }.getOrNull() }.orEmpty()
        }
        if (roots.isEmpty()) return null
        val root = roots.first()
        recycle()
        val id = "a" + Integer.toHexString(System.identityHashCode(root)) +
            System.currentTimeMillis().toString(36).takeLast(4)
        val result = ArrayList<MobileNode>()
        val metrics = resources.displayMetrics
        fun visit(node: AccessibilityNodeInfo?, depth: Int) {
            if (node == null || depth > 30 || result.size >= 140) return
            val rect = Rect()
            node.getBoundsInScreen(rect)
            val simple = node.className?.toString()?.substringAfterLast('.') ?: "View"
            val text = node.text?.toString().orEmpty().trim()
            val desc = node.contentDescription?.toString().orEmpty().trim()
            val clickable = node.isClickable
            val longClickable = node.isLongClickable
            val editable = node.isEditable
            val scrollable = node.isScrollable
            val checkable = node.isCheckable
            val interactive = clickable || longClickable || editable || scrollable || checkable
            if (node.isVisibleToUser && rect.width() >= 2 && rect.height() >= 2 &&
                (interactive || text.isNotBlank() || desc.isNotBlank()) &&
                !(node.packageName?.toString() == "com.android.systemui" && !interactive)
            ) {
                val ref = "@$id:e${result.size}"
                val password = node.isPassword
                val item = MobileNode(
                    ref = ref,
                    role = simple,
                    text = if (password) "" else text,
                    desc = if (password) "" else desc,
                    id = node.viewIdResourceName?.substringAfter('/').orEmpty(),
                    x1 = rect.left,
                    y1 = rect.top,
                    x2 = rect.right,
                    y2 = rect.bottom,
                    clickable = clickable,
                    longClickable = longClickable,
                    editable = editable,
                    scrollable = scrollable,
                    checkable = checkable,
                    checked = node.isChecked,
                    enabled = node.isEnabled,
                    password = password,
                )
                result += item
                nodes[ref] = node
            }
            for (index in 0 until node.childCount) {
                visit(node.getChild(index), depth + 1)
            }
        }
        roots.forEach { visit(it, 0) }
        val pkg = root.packageName?.toString().orEmpty()
        if (result.isEmpty()) return null
        return MobileSnapshot(
            id = id,
            width = metrics.widthPixels,
            height = metrics.heightPixels,
            packageName = pkg,
            nodes = result,
        )
    }

    suspend fun act(ref: String, action: String, text: String): String? {
        val node = nodes[ref] ?: return "STALE_REF: take a new snapshot."
        if (!node.isEnabled) return "That element is disabled."
        val performed = when (action) {
            "tap", "check", "uncheck" -> node.performAction(AccessibilityNodeInfo.ACTION_CLICK)
            "long_click" -> node.performAction(AccessibilityNodeInfo.ACTION_LONG_CLICK)
            "scroll_forward" -> node.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD)
            "scroll_backward" -> node.performAction(AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD)
            "set_text", "clear_text" -> {
                if (!node.isEditable) return "That element is not editable."
                val value = if (action == "clear_text") "" else text
                val bundle = Bundle().apply {
                    putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, value)
                }
                if (!node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, bundle)) {
                    node.performAction(AccessibilityNodeInfo.ACTION_FOCUS)
                    node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, bundle)
                } else true
            }
            else -> false
        }
        if (!performed) return "The accessibility action was rejected by the app."
        delay(350)
        return null
    }

    suspend fun global(action: String): String? {
        val id = when (action) {
            "back" -> GLOBAL_ACTION_BACK
            "home" -> GLOBAL_ACTION_HOME
            "recents" -> GLOBAL_ACTION_RECENTS
            else -> return "Unknown global action."
        }
        if (!performGlobalAction(id)) return "Android rejected the global action."
        delay(350)
        return null
    }

    fun launch(packageName: String): String? {
        val intent = packageManager.getLaunchIntentForPackage(packageName)
            ?: return "No launchable activity for $packageName."
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        runCatching { startActivity(intent) }.onFailure { return it.message ?: "Launch failed." }
        return null
    }

    companion object {
        @Volatile
        private var instance: MobileAccessibilityService? = null

        val connected: Boolean get() = instance != null

        fun snapshotNow(displayId: Int = Display.DEFAULT_DISPLAY): MobileSnapshot? = instance?.snapshot(displayId)

        suspend fun actNow(ref: String, action: String, text: String): String? =
            instance?.act(ref, action, text) ?: "Accessibility control is not enabled."

        suspend fun globalNow(action: String): String? =
            instance?.global(action) ?: "Accessibility control is not enabled."

        fun launchNow(packageName: String): String? =
            instance?.launch(packageName) ?: "Accessibility control is not enabled."
    }
}
