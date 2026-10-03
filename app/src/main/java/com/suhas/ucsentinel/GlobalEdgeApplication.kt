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
        val scan=PeriodicWorkRequestBuilder<ScanWorker>(15,TimeUnit.MINUTES).setConstraints(network).build()
        wm.enqueueUniquePeriodicWork("global_edge_ai_periodic_scan",ExistingPeriodicWorkPolicy.UPDATE,scan)

        // Start one pass soon after the app process is created instead of waiting for the first periodic window.
        val immediate=OneTimeWorkRequestBuilder<ScanWorker>().setConstraints(network).build()
        wm.enqueueUniqueWork("global_edge_ai_startup_scan",ExistingWorkPolicy.REPLACE,immediate)

        val ist=ZoneId.of("Asia/Kolkata")
        val now=ZonedDateTime.now(ist)

        // WorkManager remains a 15-minute safety net. The persistent market foreground service
        // owns the one-minute heartbeat and continues when the activity is closed.
        listOf(LocalTime.of(9,15),LocalTime.of(15,25)).forEachIndexed{idx,t->
            var target=now.toLocalDate().atTime(t).atZone(ist)
            if(!target.isAfter(now))target=target.plusDays(1)
            val delay=Duration.between(now,target).toMillis().coerceAtLeast(0L)
            val wake=PeriodicWorkRequestBuilder<ScanWorker>(24,TimeUnit.HOURS)
                .setInitialDelay(delay,TimeUnit.MILLISECONDS).setConstraints(network).build()
            wm.enqueueUniquePeriodicWork("global_edge_ai_market_anchor_$idx",ExistingPeriodicWorkPolicy.UPDATE,wake)
        }

        wm.cancelUniqueWork("global_edge_ai_24h_learning")
        val learning=PeriodicWorkRequestBuilder<LearningWorker>(15,TimeUnit.MINUTES).setConstraints(network).build()
        wm.enqueueUniquePeriodicWork("global_edge_ai_15m_learning",ExistingPeriodicWorkPolicy.UPDATE,learning)

        // A heavier replay pass runs after the Indian session. It complements, rather than replaces,
        // the 15-minute learner: more Multify event replay, shadow resolution and next-session research.
        val nightlyTime=LocalTime.of(17,45)
        var nightlyTarget=now.toLocalDate().atTime(nightlyTime).atZone(ist)
        if(!nightlyTarget.isAfter(now))nightlyTarget=nightlyTarget.plusDays(1)
        val nightlyDelay=Duration.between(now,nightlyTarget).toMillis().coerceAtLeast(0L)
        val nightly=PeriodicWorkRequestBuilder<NightlyLearningWorker>(24,TimeUnit.HOURS)
            .setInitialDelay(nightlyDelay,TimeUnit.MILLISECONDS).setConstraints(network).build()
        wm.enqueueUniquePeriodicWork("global_edge_ai_nightly_deep_learning",ExistingPeriodicWorkPolicy.UPDATE,nightly)
    }
    override fun onTrimMemory(level:Int){super.onTrimMemory(level);if(level>=ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW)repository.trimMemory()}
}
