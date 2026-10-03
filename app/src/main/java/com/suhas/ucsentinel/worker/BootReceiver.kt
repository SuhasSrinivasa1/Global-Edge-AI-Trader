package com.suhas.globaledgeai.worker

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.suhas.globaledgeai.diagnostics.DiagnosticLog

class BootReceiver: BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        DiagnosticLog.log(context,"BOOT","Received ${intent?.action.orEmpty()}; WorkManager schedules remain active. Foreground scanner will start from the next visible app activity.")
    }
}
