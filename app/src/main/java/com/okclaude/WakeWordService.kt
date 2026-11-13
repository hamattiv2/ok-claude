package com.okclaude

import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import org.json.JSONObject
import org.vosk.Model
import org.vosk.Recognizer
import org.vosk.android.RecognitionListener
import org.vosk.android.SpeechService
import org.vosk.android.StorageService

/**
 * 端末内（Vosk）で常に「OK クロード」を待ち受けるサービス。
 * 音声はどこにも送らない。
 *
 * Claudeアプリが音声モードでマイクを使っている間は、Androidの仕組みで
 * こちらには無音が届くだけなので、止めずに動かし続ける。
 */
class WakeWordService : Service(), RecognitionListener {

    companion object {
        const val ACTION_STOP = "com.okclaude.STOP"
        @Volatile var instance: WakeWordService? = null
        /** 画面に「聞こえた言葉」を出すためのコールバック */
        var onHeard: ((String) -> Unit)? = null
        var lastError: String = ""
    }

    private val handler = Handler(Looper.getMainLooper())
    private var model: Model? = null
    private var speech: SpeechService? = null
    private var armed = true
    private var lastTrigger = 0L

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        instance = this
        Notifier.ensure(this)
        val stop = PendingIntent.getService(
            this, 1, Intent(this, WakeWordService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE
        )
        val n = Notification.Builder(this, Notifier.CH_SERVICE)
            .setSmallIcon(R.drawable.ic_mic)
            .setContentTitle("「OK クロード」を待ち受け中")
            .setContentIntent(Notifier.openApp(this))
            .addAction(Notification.Action.Builder(null, "停止", stop).build())
            .setOngoing(true)
            .build()
        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(Notifier.ID_SERVICE, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
        } else {
            startForeground(Notifier.ID_SERVICE, n)
        }

        StorageService.unpack(this, "model-ja", "model",
            { m -> model = m; startListening() },
            { e -> lastError = "音声モデルの読み込みに失敗: ${e.message}"; Notifier.alert(this, lastError) })
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopSelf()
            return START_NOT_STICKY
        }
        return START_STICKY
    }

    private fun startListening() {
        val m = model ?: return
        try {
            speech?.shutdown()
            val rec = Recognizer(m, 16000f)
            speech = SpeechService(rec, 16000f).also { it.startListening(this) }
            lastError = ""
        } catch (e: Exception) {
            lastError = "マイクを開けません: ${e.message}"
            handler.postDelayed({ startListening() }, 3000)
        }
    }

    private fun handle(text: String) {
        if (text.isBlank()) return
        onHeard?.invoke(text)
        if (!armed) return
        val now = System.currentTimeMillis()
        if (now - lastTrigger < 4000) return
        if (WakeMatcher.matches(text, Prefs(this).phrases)) {
            armed = false
            lastTrigger = now
            buzz(this, 80)
            val tap = ClaudeTapService.instance
            if (tap != null) {
                tap.summon()
            } else {
                Notifier.alert(this, "ユーザー補助で「OK クロード」がオフになっています。アプリを開いてオンにしてください。")
            }
        }
    }

    override fun onPartialResult(hypothesis: String?) {
        handle(JSONObject(hypothesis ?: return).optString("partial"))
    }

    override fun onResult(hypothesis: String?) {
        handle(JSONObject(hypothesis ?: return).optString("text"))
        armed = true // 一区切り話し終わったら次を受け付ける
    }

    override fun onFinalResult(hypothesis: String?) {
        armed = true
    }

    override fun onError(exception: Exception?) {
        lastError = "エラー: ${exception?.message}"
        handler.postDelayed({ startListening() }, 2000)
    }

    override fun onTimeout() {
        handler.postDelayed({ startListening() }, 500)
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        try {
            speech?.stop()
            speech?.shutdown()
        } catch (_: Exception) {
        }
        speech = null
        model?.close()
        model = null
        instance = null
        super.onDestroy()
    }
}
