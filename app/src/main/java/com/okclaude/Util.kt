package com.okclaude

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import java.text.Normalizer

const val CLAUDE_PKG = "com.anthropic.claude"

/** 設定の保存 */
class Prefs(ctx: Context) {
    private val p = ctx.getSharedPreferences("okclaude", Context.MODE_PRIVATE)

    var learnMode: Boolean
        get() = p.getBoolean("learn", false)
        set(v) = p.edit().putBoolean("learn", v).apply()

    var targetDesc: String
        get() = p.getString("t_desc", "") ?: ""
        set(v) = p.edit().putString("t_desc", v).apply()

    var targetText: String
        get() = p.getString("t_text", "") ?: ""
        set(v) = p.edit().putString("t_text", v).apply()

    var targetId: String
        get() = p.getString("t_id", "") ?: ""
        set(v) = p.edit().putString("t_id", v).apply()

    var phrases: String
        get() = p.getString("phrases", DEFAULT_PHRASES) ?: DEFAULT_PHRASES
        set(v) = p.edit().putString("phrases", v).apply()

    val hasTarget get() = targetDesc.isNotEmpty() || targetText.isNotEmpty() || targetId.isNotEmpty()

    val targetLabel: String
        get() = when {
            targetDesc.isNotEmpty() -> targetDesc
            targetText.isNotEmpty() -> targetText
            targetId.isNotEmpty() -> targetId
            else -> ""
        }

    fun clearTarget() {
        p.edit().remove("t_desc").remove("t_text").remove("t_id").apply()
    }

    companion object {
        const val DEFAULT_PHRASES = "おーけーくろーど,おっけーくろーど,okくろーど,おーけいくろーど,おけくろーど"
    }
}

/** 「OK クロード」の判定 */
object WakeMatcher {
    fun normalize(s: String): String {
        val n = Normalizer.normalize(s, Normalizer.Form.NFKC).lowercase()
        val sb = StringBuilder()
        for (c in n) {
            when {
                c in 'ァ'..'ヶ' -> sb.append(c - 0x60) // カタカナ → ひらがな
                c.isWhitespace() || c in "、。,.!?！？・" -> {}
                else -> sb.append(c)
            }
        }
        return sb.toString()
    }

    fun matches(text: String, phrases: String): Boolean {
        val t = normalize(text)
        if (t.isEmpty()) return false
        return phrases.split(',', '、', '\n')
            .map { normalize(it) }
            .filter { it.isNotEmpty() }
            .any { t.contains(it) }
    }
}

object Notifier {
    const val CH_SERVICE = "service"
    const val CH_ALERT = "alert"
    const val ID_SERVICE = 1
    const val ID_ALERT = 2

    fun ensure(ctx: Context) {
        val nm = ctx.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CH_SERVICE, "待ち受け中", NotificationManager.IMPORTANCE_MIN)
        )
        nm.createNotificationChannel(
            NotificationChannel(CH_ALERT, "お知らせ", NotificationManager.IMPORTANCE_HIGH)
        )
    }

    fun openApp(ctx: Context): PendingIntent = PendingIntent.getActivity(
        ctx, 0, Intent(ctx, MainActivity::class.java),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
    )

    fun alert(ctx: Context, text: String) {
        ensure(ctx)
        val n = Notification.Builder(ctx, CH_ALERT)
            .setSmallIcon(R.drawable.ic_mic)
            .setContentTitle("OK クロード")
            .setContentText(text)
            .setStyle(Notification.BigTextStyle().bigText(text))
            .setContentIntent(openApp(ctx))
            .setAutoCancel(true)
            .build()
        try {
            ctx.getSystemService(NotificationManager::class.java).notify(ID_ALERT, n)
        } catch (_: SecurityException) {
        }
    }
}

fun buzz(ctx: Context, ms: Long = 60) {
    val v: Vibrator = if (Build.VERSION.SDK_INT >= 31) {
        ctx.getSystemService(VibratorManager::class.java).defaultVibrator
    } else {
        @Suppress("DEPRECATION")
        ctx.getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
    }
    v.vibrate(VibrationEffect.createOneShot(ms, VibrationEffect.DEFAULT_AMPLITUDE))
}
