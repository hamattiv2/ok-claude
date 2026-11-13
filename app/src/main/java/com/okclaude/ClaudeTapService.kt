package com.okclaude

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.app.KeyguardManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Path
import android.graphics.Rect
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo

/**
 * ユーザー補助サービス。
 * ・Claudeアプリを開いて、登録した「波形ボタン」をタップする
 * ・学習モードでは、Claudeアプリ内で押したボタンを記録する
 * 対象はClaudeアプリだけ（accessibility_config.xml で限定）。
 */
class ClaudeTapService : AccessibilityService() {

    companion object {
        @Volatile var instance: ClaudeTapService? = null
        private val DEFAULT_MATCH = Regex("(?i)(voice mode|start voice|音声モード|音声会話|ボイスモード)")
        private val EXCLUDE = Regex("(?i)(dictat|音声入力|ディクテーション)")
    }

    private val handler = Handler(Looper.getMainLooper())
    private var deadline = 0L
    private var pendingAfterUnlock = false

    private val unlockReceiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context?, i: Intent?) {
            if (pendingAfterUnlock) {
                pendingAfterUnlock = false
                openAndTap()
            }
        }
    }

    override fun onServiceConnected() {
        instance = this
        registerReceiver(unlockReceiver, IntentFilter(Intent.ACTION_USER_PRESENT))
    }

    override fun onDestroy() {
        instance = null
        try { unregisterReceiver(unlockReceiver) } catch (_: Exception) {}
        super.onDestroy()
    }

    override fun onInterrupt() {}

    /** 「OK クロード」で呼ばれる */
    fun summon() {
        wakeScreen()
        val km = getSystemService(KeyguardManager::class.java)
        if (km.isKeyguardLocked) {
            pendingAfterUnlock = true
            Notifier.alert(this, "ロックを解除するとClaudeが開きます")
            return
        }
        openAndTap()
    }

    /** 学習モード用：Claudeを開くだけ */
    fun openClaude(): Boolean {
        val intent = packageManager.getLaunchIntentForPackage(CLAUDE_PKG) ?: return false
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED)
        startActivity(intent)
        return true
    }

    private fun openAndTap() {
        if (!openClaude()) {
            Notifier.alert(this, "Claudeアプリが見つかりません")
            return
        }
        deadline = System.currentTimeMillis() + 8000
        handler.removeCallbacks(tapLoop)
        handler.postDelayed(tapLoop, 700)
    }

    private val tapLoop = object : Runnable {
        override fun run() {
            if (tryTap()) return
            if (System.currentTimeMillis() < deadline) {
                handler.postDelayed(this, 400)
            } else {
                Notifier.alert(
                    this@ClaudeTapService,
                    "波形ボタンが見つかりませんでした。アプリの「波形ボタンを登録」からもう一度登録してください。"
                )
            }
        }
    }

    private fun claudeRoots(): List<AccessibilityNodeInfo> {
        val list = mutableListOf<AccessibilityNodeInfo>()
        try {
            windows?.forEach { w -> w.root?.let { if (it.packageName == CLAUDE_PKG) list.add(it) } }
        } catch (_: Exception) {}
        rootInActiveWindow?.let { if (it.packageName == CLAUDE_PKG) list.add(it) }
        return list
    }

    private fun tryTap(): Boolean {
        val prefs = Prefs(this)
        for (root in claudeRoots()) {
            val node = find(root) { n -> isTarget(n, prefs) } ?: continue
            click(node)
            return true
        }
        return false
    }

    private fun isTarget(n: AccessibilityNodeInfo, p: Prefs): Boolean {
        if (!n.isVisibleToUser) return false
        val desc = n.contentDescription?.toString().orEmpty()
        val text = n.text?.toString().orEmpty()
        val id = n.viewIdResourceName.orEmpty()
        if (p.hasTarget) {
            return when {
                p.targetDesc.isNotEmpty() -> desc == p.targetDesc
                p.targetText.isNotEmpty() -> text == p.targetText
                else -> id == p.targetId
            }
        }
        val label = "$desc $text"
        return DEFAULT_MATCH.containsMatchIn(label) && !EXCLUDE.containsMatchIn(label)
    }

    private fun find(root: AccessibilityNodeInfo, pred: (AccessibilityNodeInfo) -> Boolean): AccessibilityNodeInfo? {
        val queue = ArrayDeque<AccessibilityNodeInfo>()
        queue.add(root)
        var guard = 0
        while (queue.isNotEmpty() && guard++ < 3000) {
            val n = queue.removeFirst()
            if (pred(n)) return n
            for (i in 0 until n.childCount) n.getChild(i)?.let { queue.add(it) }
        }
        return null
    }

    private fun click(node: AccessibilityNodeInfo) {
        var n: AccessibilityNodeInfo? = node
        while (n != null && !n.isClickable) n = n.parent
        if (n != null && n.performAction(AccessibilityNodeInfo.ACTION_CLICK)) return
        // クリックできない部品なら、その場所を指でタップする
        val r = Rect()
        node.getBoundsInScreen(r)
        val path = Path().apply { moveTo(r.exactCenterX(), r.exactCenterY()) }
        dispatchGesture(
            GestureDescription.Builder()
                .addStroke(GestureDescription.StrokeDescription(path, 0, 60))
                .build(), null, null
        )
    }

    override fun onAccessibilityEvent(e: AccessibilityEvent?) {
        if (e == null) return
        val prefs = Prefs(this)
        if (prefs.learnMode &&
            e.eventType == AccessibilityEvent.TYPE_VIEW_CLICKED &&
            e.packageName == CLAUDE_PKG
        ) {
            val src = e.source ?: return
            learn(src, prefs)
        }
    }

    private fun learn(src: AccessibilityNodeInfo, p: Prefs) {
        var desc = src.contentDescription?.toString().orEmpty()
        var text = src.text?.toString().orEmpty()
        if (desc.isEmpty() && text.isEmpty()) {
            // アイコンボタンは子要素に説明が付いていることが多い
            find(src) { !it.contentDescription.isNullOrEmpty() }?.let { desc = it.contentDescription.toString() }
            if (desc.isEmpty()) find(src) { !it.text.isNullOrEmpty() }?.let { text = it.text.toString() }
        }
        val id = src.viewIdResourceName.orEmpty()
        if (desc.isEmpty() && text.isEmpty() && id.isEmpty()) {
            Notifier.alert(this, "このボタンには名前が無く登録できませんでした。もう一度押してみてください。")
            return
        }
        p.clearTarget()
        p.targetDesc = desc
        p.targetText = if (desc.isEmpty()) text else ""
        p.targetId = if (desc.isEmpty() && text.isEmpty()) id else ""
        p.learnMode = false
        buzz(this, 150)
        Notifier.alert(this, "登録しました：「${p.targetLabel}」")
    }

    @Suppress("DEPRECATION")
    private fun wakeScreen() {
        val pm = getSystemService(PowerManager::class.java)
        if (pm.isInteractive) return
        pm.newWakeLock(
            PowerManager.SCREEN_BRIGHT_WAKE_LOCK or PowerManager.ACQUIRE_CAUSES_WAKEUP,
            "okclaude:wake"
        ).acquire(10_000)
    }
}
