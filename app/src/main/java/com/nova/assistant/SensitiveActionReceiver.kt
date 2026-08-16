package com.nova.assistant

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat

/**
 * A background Service cannot show a normal AlertDialog the way an Activity
 * can (that would need the heavy "draw over other apps" permission, which
 * this project deliberately does not request). So when a sensitive action is
 * triggered by the background wake-word service, approval happens through a
 * notification with real Approve/Deny action buttons instead — same rule as
 * PermissionGate ("nothing sensitive runs without an explicit tap"), just a
 * different, backgroundable UI for it.
 */
class SensitiveActionReceiver : BroadcastReceiver() {

    companion object {
        const val CHANNEL_ID = "nova_approval_channel"
        const val ACTION_APPROVE = "com.nova.assistant.action.APPROVE"
        const val ACTION_DENY = "com.nova.assistant.action.DENY"
        const val EXTRA_REQUEST_ID = "request_id"

        // Pending decisions, held only for the lifetime of this process — a
        // pending approval only makes sense while the app that requested it
        // is still running.
        private val pending = mutableMapOf<Int, (Boolean) -> Unit>()
        private var nextId = 1000

        fun requestApproval(context: Context, actionLabel: String, onDecision: (Boolean) -> Unit) {
            ensureChannel(context)
            val id = nextId++
            pending[id] = onDecision

            val flags = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            val approvePending = PendingIntent.getBroadcast(
                context, id * 2,
                Intent(context, SensitiveActionReceiver::class.java).setAction(ACTION_APPROVE).putExtra(EXTRA_REQUEST_ID, id),
                flags
            )
            val denyPending = PendingIntent.getBroadcast(
                context, id * 2 + 1,
                Intent(context, SensitiveActionReceiver::class.java).setAction(ACTION_DENY).putExtra(EXTRA_REQUEST_ID, id),
                flags
            )

            val notification = NotificationCompat.Builder(context, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_launcher_foreground)
                .setContentTitle("Nova needs your approval")
                .setContentText("Nova wants to $actionLabel. Allow this?")
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setAutoCancel(true)
                .addAction(0, "Approve", approvePending)
                .addAction(0, "Deny", denyPending)
                .build()

            NotificationManagerCompat.from(context).notify(id, notification)
        }

        private fun ensureChannel(context: Context) {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
            val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            if (nm.getNotificationChannel(CHANNEL_ID) == null) {
                nm.createNotificationChannel(
                    NotificationChannel(CHANNEL_ID, "Nova approval requests", NotificationManager.IMPORTANCE_HIGH)
                )
            }
        }
    }

    override fun onReceive(context: Context, intent: Intent) {
        val id = intent.getIntExtra(EXTRA_REQUEST_ID, -1)
        val callback = pending.remove(id) ?: return
        val approved = intent.action == ACTION_APPROVE
        callback(approved)
        NotificationManagerCompat.from(context).cancel(id)
    }
}
