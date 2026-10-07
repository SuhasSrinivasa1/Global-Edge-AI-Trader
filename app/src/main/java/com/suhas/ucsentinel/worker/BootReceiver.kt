package com.suhas.globaledgeai.worker

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.suhas.globaledgeai.diagnostics.DiagnosticLog

class BootReceiver: BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        NearCloseAlarmScheduler.schedule(context)
        DiagnosticLog.log(context,"BOOT","Received ${intent?.action.orEmpty()}; WorkManager remains armed and near-close UC wake alarms were rescheduled.")
    }
}
