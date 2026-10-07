package com.suhas.globaledgeai.notifications

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.suhas.globaledgeai.MainActivity
import com.suhas.globaledgeai.domain.model.Candidate
import com.suhas.globaledgeai.domain.model.GlobalLeadCandidate
import com.suhas.globaledgeai.domain.model.StrategySetup
import java.util.Locale
import kotlin.math.abs

object AppNotifier {
    private const val UC_CHANNEL = "actionable_uc_alerts"
    private const val GLOBAL_CHANNEL = "global_lead_entry_alerts"
    private const val STRATEGY_CHANNEL = "strategy_entry_alerts"
    private const val ORDER_CHANNEL = "prepared_order_tickets"
    private const val DEDUPE_MS = 20L * 60L * 1000L

    fun ensureChannel(context: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val manager = context.getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(NotificationChannel(UC_CHANNEL,"Buyable upper-circuit alerts",NotificationManager.IMPORTANCE_HIGH).apply {
                description="Alerts when the automatic scanner finds a near-upper-circuit stock with executable sellers still available."
                enableVibration(true)
            })
            manager.createNotificationChannel(NotificationChannel(GLOBAL_CHANNEL,"Global Lead entry alerts",NotificationManager.IMPORTANCE_HIGH).apply {
                description="9:15 AM Indian LONG/SHORT entry alerts confirmed by the Global Lead scanner."
                enableVibration(true)
            })
            manager.createNotificationChannel(NotificationChannel(STRATEGY_CHANNEL,"Trading strategy entry alerts",NotificationManager.IMPORTANCE_HIGH).apply {
                description="Alerts only when a new persistent LIVE intraday strategy call clears price, volume and liquidity gates."
                enableVibration(true)
            })
            manager.createNotificationChannel(NotificationChannel(ORDER_CHANNEL,"Prepared order tickets",NotificationManager.IMPORTANCE_HIGH).apply {
                description="Confirms that a model-card order ticket has been prepared for review."
                enableVibration(true)
            })
        }
    }

    private fun allowed(context: Context):Boolean = Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
        context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED

    private fun pending(context: Context, requestCode:Int):PendingIntent = PendingIntent.getActivity(
        context, requestCode, Intent(context,MainActivity::class.java).apply {
            flags=Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
    )

    private fun shouldNotify(context:Context,key:String,fingerprint:String):Boolean {
        val prefs=context.getSharedPreferences("global_edge_notifications",Context.MODE_PRIVATE)
        val now=System.currentTimeMillis()
        if(prefs.getString("${key}_fingerprint","")==fingerprint && now-prefs.getLong("${key}_at",0L)<DEDUPE_MS)return false
        prefs.edit().putString("${key}_fingerprint",fingerprint).putLong("${key}_at",now).apply()
        return true
    }

    fun notifyBuyableUc(context: Context, candidates: List<Candidate>) {
        if(candidates.isEmpty()||!allowed(context))return
        ensureChannel(context)
        val actionable=candidates.take(3)
        val fingerprint=actionable.joinToString("|"){"${it.symbol}:${it.score.toInt()}"}
        if(!shouldNotify(context,"uc",fingerprint))return
        val lines=actionable.map{c->
            val distance=if(c.upperCircuit>0.0)abs(c.upperCircuit-c.price)/c.upperCircuit*100.0 else 0.0
            "${c.symbol}: ${String.format(Locale.US,"%.2f",distance)}% from UC • score ${c.score.toInt()}"
        }
        val n=NotificationCompat.Builder(context,UC_CHANNEL)
            .setSmallIcon(android.R.drawable.stat_notify_more)
            .setContentTitle("Buyable upper-circuit setup")
            .setContentText(if(actionable.size==1)lines.first() else "${actionable.size} buyable near-UC setups found")
            .setStyle(NotificationCompat.BigTextStyle().bigText(lines.joinToString("\n")))
            .setPriority(NotificationCompat.PRIORITY_HIGH).setCategory(NotificationCompat.CATEGORY_RECOMMENDATION)
            .setAutoCancel(true).setContentIntent(pending(context,2201)).build()
        runCatching{NotificationManagerCompat.from(context).notify(2201,n)}
    }

    fun notifyThreePmUc(context:Context,candidates:List<Candidate>){
        if(candidates.isEmpty()||!allowed(context))return
        ensureChannel(context)
        val top=candidates.take(5)
        val fingerprint=top.joinToString("|"){"${it.symbol}:${it.score.toInt()}"}
        if(!shouldNotify(context,"uc3pm",fingerprint))return
        val lines=top.map{c->
            "LONG ${c.symbol} • score ${c.score.toInt()} • ₹${String.format(Locale.US,"%.2f",c.price)} • next-session UC prediction"
        }
        val n=NotificationCompat.Builder(context,UC_CHANNEL)
            .setSmallIcon(android.R.drawable.stat_notify_more)
            .setContentTitle("3 PM next-session Upper Circuit list")
            .setContentText(if(top.size==1)lines.first() else "${top.size} LONG candidates for the next trading day")
            .setStyle(NotificationCompat.BigTextStyle().bigText(lines.joinToString("\n")))
            .setPriority(NotificationCompat.PRIORITY_HIGH).setCategory(NotificationCompat.CATEGORY_RECOMMENDATION)
            .setAutoCancel(true).setContentIntent(pending(context,2251)).build()
        runCatching{NotificationManagerCompat.from(context).notify(2251,n)}
    }

    private data class GlobalAlertPlan(val trigger:Double,val stop:Double,val target1:Double,val target2:Double)

    private fun globalAlertPlan(c:GlobalLeadCandidate):GlobalAlertPlan{
        val short=c.direction.name.contains("SHORT")
        val base=c.indianPrice.coerceAtLeast(0.01)
        val trigger=if(short)base*0.999 else base*1.001
        val openDistancePct=if(c.indianOpen>0.0)kotlin.math.abs(c.indianOpen-trigger)/trigger*100.0 else 0.0
        val riskPct=(openDistancePct*0.25).takeIf{it.isFinite()&&it>0.0}?.coerceIn(0.35,1.00)?:0.50
        val targetPct=c.expectedTargetPct.takeIf{it.isFinite()&&it>0.0}?.coerceIn(0.35,3.00)?:0.50
        val target2Pct=(targetPct*1.75).coerceIn(targetPct+0.20,4.00)
        val stop=if(short)trigger*(1.0+riskPct/100.0) else trigger*(1.0-riskPct/100.0)
        val target1=if(short)trigger*(1.0-targetPct/100.0) else trigger*(1.0+targetPct/100.0)
        val target2=if(short)trigger*(1.0-target2Pct/100.0) else trigger*(1.0+target2Pct/100.0)
        return GlobalAlertPlan(trigger,stop,target1,target2)
    }

    fun notifyGlobalLead(context:Context,candidates:List<GlobalLeadCandidate>){
        if(candidates.isEmpty()||!allowed(context))return
        ensureChannel(context)
        val qualified=candidates.filter{c->c.action.name.contains("ENTER")}.take(5)
        if(qualified.isEmpty())return
        val fingerprint=qualified.joinToString("|"){"${it.indianSymbol}:${it.direction.name}:${it.action.name}"}
        if(!shouldNotify(context,"global",fingerprint))return
        val lines=qualified.map{c->
            val side=if(c.direction.name.contains("SHORT"))"SHORT" else "LONG"
            val plan=globalAlertPlan(c)
            "$side ${c.indianSymbol} ${if(side=="SHORT")"≤" else "≥"} ₹${String.format(Locale.US,"%.2f",plan.trigger)} • SL ₹${String.format(Locale.US,"%.2f",plan.stop)} • T1 ₹${String.format(Locale.US,"%.2f",plan.target1)} • T2 ₹${String.format(Locale.US,"%.2f",plan.target2)}"
        }
        val n=NotificationCompat.Builder(context,GLOBAL_CHANNEL)
            .setSmallIcon(android.R.drawable.stat_notify_more)
            .setContentTitle("Global Lead: confirmed entry trigger")
            .setContentText(lines.first())
            .setStyle(NotificationCompat.BigTextStyle().bigText(lines.joinToString(" • ")))
            .setPriority(NotificationCompat.PRIORITY_HIGH).setCategory(NotificationCompat.CATEGORY_RECOMMENDATION)
            .setAutoCancel(true).setContentIntent(pending(context,2301)).build()
        runCatching{NotificationManagerCompat.from(context).notify(2301,n)}
    }

    fun notifyStrategySetups(context:Context,setups:List<StrategySetup>){
        if(setups.isEmpty()||!allowed(context))return
        ensureChannel(context)
        val top=setups.filter{it.entryPrice.isFinite()&&it.entryPrice>0.0}.take(5)
        if(top.isEmpty())return
        val fingerprint=top.joinToString("|"){"${it.strategyId}:${it.symbol}:${it.direction.name}:${String.format(Locale.US,"%.2f",it.entryPrice)}"}
        if(!shouldNotify(context,"strategy",fingerprint))return
        val lines=top.map{s->
            val short=s.direction.name.contains("SHORT")
            val stop=if(short)s.entryPrice*(1.0+s.stopPct.coerceAtLeast(0.1)/100.0) else s.entryPrice*(1.0-s.stopPct.coerceAtLeast(0.1)/100.0)
            val target=if(short)s.entryPrice*(1.0-s.targetPct.coerceAtLeast(0.1)/100.0) else s.entryPrice*(1.0+s.targetPct.coerceAtLeast(0.1)/100.0)
            "${s.direction.name} ${s.symbol} • entry ₹${String.format(Locale.US,"%.2f",s.entryPrice)} • SL ₹${String.format(Locale.US,"%.2f",stop)} • target ₹${String.format(Locale.US,"%.2f",target)} • ${s.strategyName}"
        }
        val n=NotificationCompat.Builder(context,STRATEGY_CHANNEL)
            .setSmallIcon(android.R.drawable.stat_notify_more)
            .setContentTitle("New LIVE trading-strategy call")
            .setContentText(lines.first())
            .setStyle(NotificationCompat.BigTextStyle().bigText(lines.joinToString("\n")))
            .setPriority(NotificationCompat.PRIORITY_HIGH).setCategory(NotificationCompat.CATEGORY_RECOMMENDATION)
            .setAutoCancel(true).setContentIntent(pending(context,2401)).build()
        runCatching{NotificationManagerCompat.from(context).notify(2401,n)}
    }

    fun notifyPreparedOrder(context:Context,symbol:String,side:String,product:String,quantity:Int,entry:Double,stop:Double,target:Double){
        if(!allowed(context))return
        ensureChannel(context)
        val text=side+" "+symbol+" • "+product+" • qty "+quantity+" • entry ₹"+String.format(Locale.US,"%.2f",entry)+" • SL ₹"+String.format(Locale.US,"%.2f",stop)+" • target ₹"+String.format(Locale.US,"%.2f",target)
        val n=NotificationCompat.Builder(context,ORDER_CHANNEL)
            .setSmallIcon(android.R.drawable.stat_notify_more)
            .setContentTitle("Manual order submitted")
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text+"\nSubmitted to Groww after your explicit PLACE ORDER confirmation."))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_STATUS)
            .setAutoCancel(true)
            .setContentIntent(pending(context,2501))
            .build()
        runCatching{NotificationManagerCompat.from(context).notify((System.currentTimeMillis()%100000).toInt()+2500,n)}
    }


}
