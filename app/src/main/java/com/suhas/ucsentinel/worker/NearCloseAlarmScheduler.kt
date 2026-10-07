package com.suhas.globaledgeai.worker

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.workDataOf
import com.suhas.globaledgeai.diagnostics.DiagnosticLog
import com.suhas.globaledgeai.domain.engine.NseTradingCalendar2026
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime

object NearCloseAlarmScheduler {
    private val ist=ZoneId.of("Asia/Kolkata")
    private val slots=listOf(
        "PREP" to LocalTime.of(14,47),
        "PRIMARY" to LocalTime.of(15,10),
        "BACKUP" to LocalTime.of(15,24)
    )

    fun schedule(context:Context){
        val alarm=context.getSystemService(AlarmManager::class.java)?:return
        val now=ZonedDateTime.now(ist)
        slots.forEachIndexed{index,(label,time)->
            var date=now.toLocalDate()
            if(!NseTradingCalendar2026.isTradingDate(date)||!date.atTime(time).atZone(ist).isAfter(now)){
                date=NseTradingCalendar2026.nextTradingDate(date)
            }
            val at=date.atTime(time).atZone(ist).toInstant().toEpochMilli()
            val intent=Intent(context,NearCloseAlarmReceiver::class.java).putExtra("slot",label)
            val pi=PendingIntent.getBroadcast(context,2810+index,intent,PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
            alarm.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP,at,pi)
        }
    }
}

class NearCloseAlarmReceiver:BroadcastReceiver(){
    override fun onReceive(context:Context,intent:Intent?){
        val slot=intent?.getStringExtra("slot").orEmpty().ifBlank{"UNKNOWN"}
        DiagnosticLog.log(context,"UC-3PM-WAKE","near-close alarm fired • slot=$slot")
        val req=OneTimeWorkRequestBuilder<ScanWorker>()
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .setInputData(workDataOf("force_market_pass" to true))
            .build()
        val date=LocalDate.now(ZoneId.of("Asia/Kolkata"))
        WorkManager.getInstance(context).enqueueUniqueWork(
            "global_edge_near_close_alarm_${date}_$slot",
            ExistingWorkPolicy.REPLACE,
            req
        )
        NearCloseAlarmScheduler.schedule(context)
    }
}
