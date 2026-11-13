package com.raunakpatil.roniepocket.tools

import android.app.AlarmManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.media.RingtoneManager
import android.os.Build
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * Ronie's own timers (instead of handing them to the Clock app, which won't let other apps cancel a running
 * timer). Each timer is an exact alarm plus an ongoing notification with a live countdown and a Stop button;
 * when it ends, a loud notification rings with the phone's alarm sound until stopped. Kept in a file so
 * they survive the app closing and a reboot.
 */
object Timers {
    data class Timer(val id: Int, val label: String?, val endsAt: Long, val lengthMs: Long, val ringing: Boolean = false)

    private const val CH_RUNNING = "timers"
    private const val CH_RING = "timer_ring"
    const val ACTION_FIRE = "com.raunakpatil.roniepocket.TIMER_FIRE"
    const val ACTION_STOP = "com.raunakpatil.roniepocket.TIMER_STOP"

    private fun file(ctx: Context) = File(ctx.filesDir, "tools/timers.json")

    @Synchronized fun all(ctx: Context): List<Timer> = runCatching {
        val a = JSONArray(file(ctx).readText())
        (0 until a.length()).map { a.getJSONObject(it) }.map {
            Timer(it.getInt("id"), it.optString("label").ifEmpty { null }, it.getLong("endsAt"), it.getLong("lengthMs"), it.optBoolean("ringing"))
        }
    }.getOrDefault(emptyList())

    @Synchronized private fun save(ctx: Context, list: List<Timer>) {
        file(ctx).parentFile?.mkdirs()
        file(ctx).writeText(JSONArray(list.map {
            JSONObject().put("id", it.id).put("label", it.label ?: "").put("endsAt", it.endsAt).put("lengthMs", it.lengthMs).put("ringing", it.ringing)
        }).toString())
    }

    /** True when this phone lets Ronie set exact alarms (otherwise timers go to the Clock app). */
    fun canRingExactly(ctx: Context): Boolean =
        Build.VERSION.SDK_INT < 31 || ctx.getSystemService(AlarmManager::class.java).canScheduleExactAlarms()

    fun start(ctx: Context, lengthMs: Long, label: String?): Timer {
        val t = Timer((System.currentTimeMillis() and 0x3fffffff).toInt(), label, System.currentTimeMillis() + lengthMs, lengthMs)
        save(ctx, all(ctx) + t)
        schedule(ctx, t)
        showRunning(ctx, t)
        return t
    }

    /** Stops timers whose label matches [label] (all of them when null or nothing matches). Returns the stopped ones. */
    fun stop(ctx: Context, label: String? = null): List<Timer> {
        val list = all(ctx)
        val q = label?.lowercase()?.trim()?.takeIf { it.isNotEmpty() && it != "timer" && it != "all" }
        val matched = q?.let { w -> list.filter { it.label?.lowercase()?.contains(w) == true } }?.takeIf { it.isNotEmpty() } ?: list
        matched.forEach { cancelAlarm(ctx, it); nm(ctx).cancel(it.id) }
        save(ctx, list - matched.toSet())
        return matched
    }

    private fun stopOne(ctx: Context, id: Int) {
        all(ctx).firstOrNull { it.id == id }?.let { cancelAlarm(ctx, it) }
        nm(ctx).cancel(id)
        save(ctx, all(ctx).filter { it.id != id })
    }

    /** After a reboot: re-arm timers that haven't ended yet. */
    fun rescheduleAll(ctx: Context) {
        val now = System.currentTimeMillis()
        val keep = all(ctx).filter { it.endsAt > now }
        save(ctx, keep)
        keep.forEach { schedule(ctx, it); showRunning(ctx, it) }
    }

    private fun nm(ctx: Context) = ctx.getSystemService(NotificationManager::class.java)

    private fun pending(ctx: Context, t: Timer, action: String): PendingIntent = PendingIntent.getBroadcast(ctx,
        t.id + if (action == ACTION_STOP) 1 else 0,
        Intent(ctx, TimerReceiver::class.java).setAction(action).putExtra("id", t.id),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)

    private fun openApp(ctx: Context): PendingIntent = PendingIntent.getActivity(ctx, 0,
        ctx.packageManager.getLaunchIntentForPackage(ctx.packageName), PendingIntent.FLAG_IMMUTABLE)

    private fun schedule(ctx: Context, t: Timer) {
        val am = ctx.getSystemService(AlarmManager::class.java)
        // setAlarmClock: exact even in Doze, and Android shows the alarm icon.
        am.setAlarmClock(AlarmManager.AlarmClockInfo(t.endsAt, openApp(ctx)), pending(ctx, t, ACTION_FIRE))
    }

    private fun cancelAlarm(ctx: Context, t: Timer) {
        ctx.getSystemService(AlarmManager::class.java).cancel(pending(ctx, t, ACTION_FIRE))
    }

    private fun channels(ctx: Context) {
        val n = nm(ctx)
        n.createNotificationChannel(NotificationChannel(CH_RUNNING, "Timers (counting down)", NotificationManager.IMPORTANCE_LOW))
        n.createNotificationChannel(NotificationChannel(CH_RING, "Timers (ringing)", NotificationManager.IMPORTANCE_HIGH).apply {
            setSound(RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM),
                AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ALARM).setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build())
            enableVibration(true)
            vibrationPattern = longArrayOf(0, 600, 400, 600)
        })
    }

    private fun title(t: Timer) = (t.label?.replaceFirstChar { it.uppercase() } ?: "Timer") + " · " + Builtins.duration((t.lengthMs / 1000).toInt())

    /** The ongoing notification with Android's built-in live countdown. */
    private fun showRunning(ctx: Context, t: Timer) {
        channels(ctx)
        val n = Notification.Builder(ctx, CH_RUNNING)
            .setSmallIcon(android.R.drawable.ic_lock_idle_alarm)
            .setContentTitle(title(t))
            .setContentText("Ronie will ring when it's done")
            .setWhen(t.endsAt).setShowWhen(true).setUsesChronometer(true).setChronometerCountDown(true)
            .setOngoing(true).setOnlyAlertOnce(true)
            .setContentIntent(openApp(ctx))
            .addAction(Notification.Action.Builder(null, "Stop", pending(ctx, t, ACTION_STOP)).build())
            .build()
        runCatching { nm(ctx).notify(t.id, n) }
    }

    /** Time's up: ring (insistent alarm sound) until Stop. */
    fun fired(ctx: Context, id: Int) {
        val t = all(ctx).firstOrNull { it.id == id } ?: return
        save(ctx, all(ctx).map { if (it.id == id) it.copy(ringing = true) else it })
        channels(ctx)
        val n = Notification.Builder(ctx, CH_RING)
            .setSmallIcon(android.R.drawable.ic_lock_idle_alarm)
            .setContentTitle("Time's up")
            .setContentText(title(t))
            .setCategory(Notification.CATEGORY_ALARM)
            .setContentIntent(openApp(ctx))
            .setDeleteIntent(pending(ctx, t, ACTION_STOP))
            .addAction(Notification.Action.Builder(null, "Stop", pending(ctx, t, ACTION_STOP)).build())
            .build().apply { flags = flags or Notification.FLAG_INSISTENT }
        runCatching { nm(ctx).notify(t.id, n) }
    }

    fun stopFromNotification(ctx: Context, id: Int) = stopOne(ctx, id)
}

class TimerReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        val id = intent.getIntExtra("id", 0)
        when (intent.action) {
            Timers.ACTION_FIRE -> Timers.fired(ctx, id)
            Timers.ACTION_STOP -> Timers.stopFromNotification(ctx, id)
        }
    }
}
