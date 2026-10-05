package com.suhas.globaledgeai

import android.app.Application
import android.content.ComponentCallbacks2
import androidx.work.*
import com.suhas.globaledgeai.data.repository.GlobalEdgeAITraderRepository
import com.suhas.globaledgeai.notifications.AppNotifier
import com.suhas.globaledgeai.diagnostics.DiagnosticLog
import com.suhas.globaledgeai.worker.NightlyLearningWorker
import com.suhas.globaledgeai.worker.LearningWorker
import com.suhas.globaledgeai.worker.ScanWorker
import java.time.Duration
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime
import java.util.concurrent.TimeUnit

class GlobalEdgeApplication:Application(){
    lateinit var repository:GlobalEdgeAITraderRepository; private set
    override fun onCreate(){super.onCreate();AppNotifier.ensureChannel(this);repository=GlobalEdgeAITraderRepository(this);scheduleWorkers();DiagnosticLog.log(this,"APP","Process initialized; foreground scanner starts only from a visible activity") }
    private fun scheduleWorkers(){
        val wm=WorkManager.getInstance(this)
        val network=Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()
        val schedulerPrefs=getSharedPreferences("global_edge_scheduler",MODE_PRIVATE)
        val migrate=schedulerPrefs.getInt("version",0)!=170
        val periodicPolicy=if(migrate)ExistingPeriodicWorkPolicy.UPDATE else ExistingPeriodicWorkPolicy.KEEP

        val scan=PeriodicWorkRequestBuilder<ScanWorker>(15,TimeUnit.MINUTES).setConstraints(network).build()
        wm.enqueueUniquePeriodicWork("global_edge_ai_periodic_scan",periodicPolicy,scan)

        // v1.6.10: never enqueue a new immediate ScanWorker from Application.onCreate().
        // Funtouch may recreate the process often; the old startup worker created a feedback loop.
        wm.cancelUniqueWork("global_edge_ai_startup_scan")

        val ist=ZoneId.of("Asia/Kolkata")
        val now=ZonedDateTime.now(ist)
        if(migrate){
            wm.cancelUniqueWork("global_edge_ai_market_anchor_0")
            wm.cancelUniqueWork("global_edge_ai_market_anchor_1")
        }

        // Daily safety anchors. The foreground service remains the owner; these only recover stale service state.
        listOf(LocalTime.of(9,15),LocalTime.of(15,10),LocalTime.of(15,20),LocalTime.of(15,25)).forEach{t->
            var target=now.toLocalDate().atTime(t).atZone(ist)
            if(!target.isAfter(now))target=target.plusDays(1)
            val delay=Duration.between(now,target).toMillis().coerceAtLeast(0L)
            val wake=PeriodicWorkRequestBuilder<ScanWorker>(24,TimeUnit.HOURS)
                .setInitialDelay(delay,TimeUnit.MILLISECONDS).setConstraints(network).build()
            wm.enqueueUniquePeriodicWork("global_edge_ai_market_anchor_${t.hour}_${t.minute}",periodicPolicy,wake)
        }

        wm.cancelUniqueWork("global_edge_ai_24h_learning")
        val learning=PeriodicWorkRequestBuilder<LearningWorker>(15,TimeUnit.MINUTES).setConstraints(network).build()
        wm.enqueueUniquePeriodicWork("global_edge_ai_15m_learning",periodicPolicy,learning)

        val nightlyTime=LocalTime.of(17,45)
        var nightlyTarget=now.toLocalDate().atTime(nightlyTime).atZone(ist)
        if(!nightlyTarget.isAfter(now))nightlyTarget=nightlyTarget.plusDays(1)
        val nightlyDelay=Duration.between(now,nightlyTarget).toMillis().coerceAtLeast(0L)
        val nightly=PeriodicWorkRequestBuilder<NightlyLearningWorker>(24,TimeUnit.HOURS)
            .setInitialDelay(nightlyDelay,TimeUnit.MILLISECONDS).setConstraints(network).build()
        wm.enqueueUniquePeriodicWork("global_edge_ai_nightly_deep_learning",periodicPolicy,nightly)
        schedulerPrefs.edit().putInt("version",170).apply()
    }
    override fun onTrimMemory(level:Int){super.onTrimMemory(level);if(level>=ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW)repository.trimMemory()}
}
