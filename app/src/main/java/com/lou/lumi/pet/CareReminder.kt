package com.lou.lumi.pet

import android.Manifest
import android.app.AlarmManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.lou.lumi.R
import com.lou.lumi.ui.MainActivity

/**
 * „Lumi hat Hunger“ – the one thing a virtual pet must do when you are not looking. When the app
 * goes to the background, [PetEngine.nextNeedAt] predicts when a need will run low and an inexact
 * local alarm is set for that moment. Nothing leaves the phone.
 */
object CareReminder {

    private const val CHANNEL = "care"
    private const val PREFS = "settings"
    const val KEY_ENABLED = "reminders"

    fun enabled(ctx: Context) = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY_ENABLED, true)

    fun schedule(ctx: Context, s: PetState) {
        cancel(ctx)
        if (!enabled(ctx)) return
        val (what, at) = PetEngine.nextNeedAt(s, System.currentTimeMillis()) ?: return
        val am = ctx.getSystemService(AlarmManager::class.java) ?: return
        val pi = pending(ctx, "${s.name} $what")
        // inexact on purpose: no exact-alarm permission, the system may batch it by a few minutes
        am.set(AlarmManager.RTC_WAKEUP, at, pi)
        Log.i("Lumi.CARE", "reminder „${s.name} $what“ in ${(at - System.currentTimeMillis()) / 60000} min")
    }

    fun cancel(ctx: Context) {
        val am = ctx.getSystemService(AlarmManager::class.java) ?: return
        am.cancel(pending(ctx, ""))
        ctx.getSystemService(NotificationManager::class.java)?.cancel(1)
    }

    private fun pending(ctx: Context, text: String): PendingIntent {
        val i = Intent(ctx, Receiver::class.java).putExtra("text", text)
        return PendingIntent.getBroadcast(ctx, 7, i, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
    }

    class Receiver : BroadcastReceiver() {
        override fun onReceive(ctx: Context, intent: Intent) {
            val text = intent.getStringExtra("text").orEmpty().ifBlank { "Lumi vermisst dich" }
            if (Build.VERSION.SDK_INT >= 33 &&
                ContextCompat.checkSelfPermission(ctx, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) return
            val nm = ctx.getSystemService(NotificationManager::class.java) ?: return
            nm.createNotificationChannel(NotificationChannel(CHANNEL, "Lumis Bedürfnisse", NotificationManager.IMPORTANCE_DEFAULT))
            val open = PendingIntent.getActivity(ctx, 0, Intent(ctx, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
            val n = NotificationCompat.Builder(ctx, CHANNEL)
                .setSmallIcon(R.drawable.ic_notify)
                .setContentTitle(text)
                .setContentText("Schau mal kurz vorbei.")
                .setContentIntent(open)
                .setAutoCancel(true)
                .build()
            nm.notify(1, n)
        }
    }
}
