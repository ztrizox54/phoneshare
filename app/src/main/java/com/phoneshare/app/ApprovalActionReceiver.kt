package com.phoneshare.app

import android.app.NotificationManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

class ApprovalActionReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        val id = intent.getStringExtra("id") ?: return
        val approve = intent.action == ACTION_APPROVE
        WebServerService.instance?.decideApproval(id, approve)
        val nm = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.cancel(notifIdFor(id))
    }

    companion object {
        const val ACTION_APPROVE = "com.phoneshare.app.NOTIF_APPROVE"
        const val ACTION_DENY    = "com.phoneshare.app.NOTIF_DENY"
        fun notifIdFor(approvalId: String): Int = ("approval:$approvalId").hashCode()
    }
}
