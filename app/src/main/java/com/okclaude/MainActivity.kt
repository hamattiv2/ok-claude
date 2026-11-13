package com.okclaude

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.provider.Settings
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast

class MainActivity : Activity() {

    private lateinit var status: TextView
    private lateinit var target: TextView
    private lateinit var heard: TextView
    private lateinit var toggle: Button
    private lateinit var phrases: EditText
    private val handler = Handler(Looper.getMainLooper())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val d = resources.displayMetrics.density
        val pad = (16 * d).toInt()
        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad, pad, pad * 3)
        }

        fun label(t: String, size: Float = 15f, bold: Boolean = false) = TextView(this).apply {
            text = t
            textSize = size
            if (bold) setTypeface(typeface, android.graphics.Typeface.BOLD)
            setPadding(0, pad / 2, 0, pad / 4)
            col.addView(this)
        }

        fun button(t: String, f: () -> Unit) = Button(this).apply {
            text = t
            isAllCaps = false
            setOnClickListener { f() }
            col.addView(this)
        }

        label("OK クロード", 24f, true)
        status = label("", 13f)

        label("① 準備", 17f, true)
        button("マイクと通知を許可する") { askPermissions() }
        button("ユーザー補助で「OK クロード」をオンにする") {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        }
        label("※ オンにできない（グレーになる）時：スマホの設定 → アプリ → OK クロード → 右上の︙ →「制限付き設定を許可」を押してから、もう一度。", 12f)
        button("電池の最適化から外す（待ち受けが止まらないように）") { askIgnoreBattery() }

        label("② 波形ボタンを登録", 17f, true)
        label("下のボタンを押すとClaudeが開きます。いつも手で押している「波形アイコン」を1回タップしてください。ブルッと震えたら登録完了です。", 12f)
        button("登録を開始") { startLearning() }
        target = label("", 13f)
        button("登録をリセット（自動で探す）") { Prefs(this).clearTarget(); refresh() }

        label("③ 待ち受け", 17f, true)
        toggle = button("") { toggleService() }
        button("テスト（今すぐClaudeを呼ぶ）") {
            val s = ClaudeTapService.instance
            if (s == null) toast("先にユーザー補助をオンにしてください") else s.summon()
        }
        label("聞こえた言葉（反応しない時の確認用）", 12f)
        heard = label("—", 16f)

        label("反応する言葉（カンマ区切り）", 12f)
        phrases = EditText(this).apply {
            setText(Prefs(this@MainActivity).phrases)
            textSize = 14f
            col.addView(this)
        }
        button("言葉を保存") {
            Prefs(this).phrases = phrases.text.toString()
            toast("保存しました")
        }
        button("言葉を初期値に戻す") {
            Prefs(this).phrases = Prefs.DEFAULT_PHRASES
            phrases.setText(Prefs.DEFAULT_PHRASES)
        }

        setContentView(ScrollView(this).apply { addView(col) })
    }

    override fun onResume() {
        super.onResume()
        WakeWordService.onHeard = { t -> runOnUiThread { heard.text = t } }
        refresh()
    }

    override fun onPause() {
        WakeWordService.onHeard = null
        super.onPause()
    }

    private fun refresh() {
        val mic = checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
        val a11y = ClaudeTapService.instance != null
        val claude = packageManager.getLaunchIntentForPackage(CLAUDE_PKG) != null
        val running = WakeWordService.instance != null
        fun mark(b: Boolean) = if (b) "✅" else "❌"
        status.text = buildString {
            append("${mark(mic)} マイクの許可\n")
            append("${mark(a11y)} ユーザー補助\n")
            append("${mark(claude)} Claudeアプリ\n")
            append("${mark(running)} 待ち受け")
            if (WakeWordService.lastError.isNotEmpty()) append("\n⚠ ${WakeWordService.lastError}")
        }
        val p = Prefs(this)
        target.text = if (p.hasTarget) "登録中のボタン：「${p.targetLabel}」" else "登録なし（「音声モード」らしいボタンを自動で探します）"
        toggle.text = if (running) "待ち受けを止める" else "待ち受けを始める"
    }

    private fun askPermissions() {
        val list = mutableListOf(Manifest.permission.RECORD_AUDIO)
        if (Build.VERSION.SDK_INT >= 33) list.add(Manifest.permission.POST_NOTIFICATIONS)
        requestPermissions(list.toTypedArray(), 1)
    }

    override fun onRequestPermissionsResult(code: Int, perms: Array<out String>, results: IntArray) {
        super.onRequestPermissionsResult(code, perms, results)
        refresh()
    }

    private fun askIgnoreBattery() {
        val pm = getSystemService(PowerManager::class.java)
        if (pm.isIgnoringBatteryOptimizations(packageName)) {
            toast("設定済みです")
            return
        }
        startActivity(
            Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:$packageName"))
        )
    }

    private fun startLearning() {
        val s = ClaudeTapService.instance
        if (s == null) {
            toast("先にユーザー補助をオンにしてください")
            return
        }
        Prefs(this).learnMode = true
        if (!s.openClaude()) toast("Claudeアプリが見つかりません")
    }

    private fun toggleService() {
        val intent = Intent(this, WakeWordService::class.java)
        if (WakeWordService.instance != null) {
            startService(intent.setAction(WakeWordService.ACTION_STOP))
        } else {
            if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
                askPermissions()
                return
            }
            startForegroundService(intent)
        }
        handler.postDelayed({ refresh() }, 800)
    }

    private fun toast(t: String) = Toast.makeText(this, t, Toast.LENGTH_SHORT).show()
}
