package com.aline.jier.jier

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import java.util.Locale

/**
 * 页面识别的确认通知：识别到一笔就发一条高优先级通知，
 * 点开进入应用弹出核对框（应用在后台时无法直接在别人界面弹 Flutter 框，用通知代替）。
 */
object BillConfirmNotifier {
    const val EXTRA_BILL_ID = "bill_id"

    private const val CHANNEL_ID = "chaoxi_bill_confirm"
    private const val CHANNEL_NAME = "页面识别确认"
    private const val NOTIFICATION_BASE_ID = 8900

    fun notify(context: Context, draft: ScreenBillDraft) {
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        ensureChannel(manager)

        val open = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra(EXTRA_BILL_ID, draft.id)
        }
        val pending = PendingIntent.getActivity(
            context,
            draft.id.hashCode(),
            open,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        val amountText = String.format(Locale.US, "%.2f", draft.amount)
        val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(context, CHANNEL_ID)
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(context)
        }
        val notification = builder
            .setContentTitle("识别到一笔 ¥$amountText")
            .setContentText("${draft.sourceLabel} · 点开确认")
            .setSmallIcon(android.R.drawable.stat_notify_more)
            .setAutoCancel(true)
            .setDefaults(Notification.DEFAULT_ALL)
            .setContentIntent(pending)
            .build()
        manager.notify(notificationId(draft.id), notification)
    }

    fun cancel(context: Context, id: String) {
        if (id.isBlank()) return
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.cancel(notificationId(id))
    }

    private fun ensureChannel(manager: NotificationManager) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                CHANNEL_NAME,
                NotificationManager.IMPORTANCE_HIGH,
            )
            channel.description = "识别到账单页时提示你确认"
            manager.createNotificationChannel(channel)
        }
    }

    private fun notificationId(id: String): Int =
        NOTIFICATION_BASE_ID + (id.hashCode() and 0x7fffffff) % 1000
}
