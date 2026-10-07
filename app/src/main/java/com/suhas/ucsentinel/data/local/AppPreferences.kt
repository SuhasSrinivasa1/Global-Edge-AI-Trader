package com.suhas.globaledgeai.data.local

import android.content.Context
import com.suhas.globaledgeai.domain.model.*
import com.suhas.globaledgeai.domain.engine.AccuracyLearningEngine
import org.json.JSONArray
import org.json.JSONObject
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.security.MessageDigest

class AppPreferences(private val context:Context){
    private val prefs=context.getSharedPreferences("global_edge_ai_prefs",Context.MODE_PRIVATE)
    private val ist=ZoneId.of("Asia/Kolkata")
    private fun finite(v:Double):Double=if(v.isFinite())v else 0.0
    private fun finiteOrNull(v:Double?):Any=v?.takeIf{it.isFinite()}?:JSONObject.NULL
    private fun storedFinite(j:JSONObject,name:String,default:Double=0.0):Double{
        val v=j.optDouble(name,default)
        return if(v.isFinite())v else default
    }

    init {
        // v1.4.2 reliability migration: existing installs used a 60-minute cadence.
        // Move them once to the requested frequent 15-minute cadence; users can change it later.
        if(!prefs.getBoolean("cadence_v142_migrated",false)){
            prefs.edit().putInt("pressure_scan_interval_minutes",15).putInt("global_lead_scan_interval_minutes",15)
                .putBoolean("cadence_v142_migrated",true).apply()
        }
        // v1.4.3: model thresholds become adaptive by default. Existing installs migrate once.
        if(!prefs.getBoolean("adaptive_ranges_v143_migrated",false)){
            prefs.edit().putBoolean("adaptive_ranges_enabled",true)
                .putBoolean("adaptive_ranges_v143_migrated",true).apply()
        }
        migrateTradeCallIntegrityV159()
    }

    fun loadSettings():AppSettings=AppSettings(
        minScore=prefs.getFloat("min_score",72f).toDouble(),demandMinScore=prefs.getFloat("pressure_prediction_min_score",70f).toDouble(),
        demandSpikeTargetPct=prefs.getFloat("pressure_spike_target_pct",3f).toDouble(),adaptiveRangesEnabled=prefs.getBoolean("adaptive_ranges_enabled",true),demandPredictionHorizonHours=prefs.getInt("pressure_horizon_hours",24),
        demandPressureMaxBuySellRatio=prefs.getFloat("pressure_max_current_ratio",8f).toDouble(),maxFinalCandidates=prefs.getInt("max_candidates",3),
        maxDemandCandidates=prefs.getInt("max_demand_candidates",5),maxQuotesPerScan=prefs.getInt("max_quotes",120),excludeFnoLinked=prefs.getBoolean("exclude_fno_linked",true),
        includeSmeSeries=prefs.getBoolean("include_sme",true),newListingDays=prefs.getInt("new_listing_days",45),freezeHour=prefs.getInt("freeze_hour",15),
        freezeMinute=prefs.getInt("freeze_minute",30),autoScanEnabled=prefs.getBoolean("auto_scan",true),pressureAutoScanEnabled=prefs.getBoolean("pressure_auto_scan",true),
        pressureScanIntervalMinutes=prefs.getInt("pressure_scan_interval_minutes",15),learningEnabled=prefs.getBoolean("learning_enabled",true),
        learningIntervalHours=prefs.getInt("learning_interval_hours",24),memoryRetentionDays=prefs.getInt("memory_retention_days",90),demoMode=prefs.getBoolean("demo_mode",false),
        globalLeadEnabled=prefs.getBoolean("global_lead_enabled",true),globalLeadScanIntervalMinutes=prefs.getInt("global_lead_scan_interval_minutes",15),
        globalMappingRefreshDays=prefs.getInt("global_mapping_refresh_days",7),globalTopCandidates=prefs.getInt("global_top_candidates",10),
        globalMinForeignGapPct=prefs.getFloat("global_min_foreign_gap_pct",0.75f).toDouble(),globalContinuationTargetPct=prefs.getFloat("global_continuation_target_pct",0.5f).toDouble(),
        strategyTournamentEnabled=prefs.getBoolean("strategy_tournament_enabled",true),strategyActiveCount=prefs.getInt("strategy_active_count",20),
        strategyTopCandidates=prefs.getInt("strategy_top_candidates",10),strategyMinChampionAccuracy=prefs.getFloat("strategy_min_champion_accuracy",60f).toDouble(),
        strategyMinChampionSamples=prefs.getInt("strategy_min_champion_samples",30),strategyCatalogRefreshDays=prefs.getInt("strategy_catalog_refresh_days",7),
        strategyHistorySymbolsPerPass=prefs.getInt("strategy_history_symbols_per_pass",80)
    )

    fun tradingStaticIp():String=prefs.getString("trading_static_ip","").orEmpty()
    fun saveTradingStaticIp(value:String){prefs.edit().putString("trading_static_ip",value.trim()).apply()}

    fun saveSettings(s:AppSettings){prefs.edit().putFloat("min_score",s.minScore.toFloat()).putFloat("pressure_prediction_min_score",s.demandMinScore.toFloat())
        .putFloat("pressure_spike_target_pct",s.demandSpikeTargetPct.toFloat()).putBoolean("adaptive_ranges_enabled",s.adaptiveRangesEnabled).putInt("pressure_horizon_hours",s.demandPredictionHorizonHours)
        .putFloat("pressure_max_current_ratio",s.demandPressureMaxBuySellRatio.toFloat()).putInt("max_candidates",s.maxFinalCandidates)
        .putInt("max_demand_candidates",s.maxDemandCandidates).putInt("max_quotes",s.maxQuotesPerScan).putBoolean("exclude_fno_linked",s.excludeFnoLinked)
        .putBoolean("include_sme",s.includeSmeSeries).putInt("new_listing_days",s.newListingDays).putInt("freeze_hour",s.freezeHour).putInt("freeze_minute",s.freezeMinute)
        .putBoolean("auto_scan",s.autoScanEnabled).putBoolean("pressure_auto_scan",s.pressureAutoScanEnabled).putInt("pressure_scan_interval_minutes",s.pressureScanIntervalMinutes)
        .putBoolean("learning_enabled",s.learningEnabled).putInt("learning_interval_hours",s.learningIntervalHours).putInt("memory_retention_days",s.memoryRetentionDays)
        .putBoolean("demo_mode",s.demoMode).putBoolean("global_lead_enabled",s.globalLeadEnabled).putInt("global_lead_scan_interval_minutes",s.globalLeadScanIntervalMinutes)
        .putInt("global_mapping_refresh_days",s.globalMappingRefreshDays).putInt("global_top_candidates",s.globalTopCandidates)
        .putFloat("global_min_foreign_gap_pct",s.globalMinForeignGapPct.toFloat()).putFloat("global_continuation_target_pct",s.globalContinuationTargetPct.toFloat())
        .putBoolean("strategy_tournament_enabled",s.strategyTournamentEnabled).putInt("strategy_active_count",s.strategyActiveCount)
        .putInt("strategy_top_candidates",s.strategyTopCandidates).putFloat("strategy_min_champion_accuracy",s.strategyMinChampionAccuracy.toFloat())
        .putInt("strategy_min_champion_samples",s.strategyMinChampionSamples).putInt("strategy_catalog_refresh_days",s.strategyCatalogRefreshDays)
        .putInt("strategy_history_symbols_per_pass",s.strategyHistorySymbolsPerPass).apply()}


    fun lastStrategyScanAt():Long=prefs.getLong("last_strategy_scan_at",0L)
    fun lastStrategyAttemptAt():Long=prefs.getLong("last_strategy_attempt_at",0L)
    fun lastStrategyErrorAt():Long=prefs.getLong("last_strategy_error_at",0L)
    fun lastStrategyError():String=prefs.getString("last_strategy_error","").orEmpty()
    fun markStrategyAttempt(v:Long=System.currentTimeMillis()){prefs.edit().putLong("last_strategy_attempt_at",v).apply()}
    fun markStrategyError(message:String,v:Long=System.currentTimeMillis()){
        prefs.edit().putLong("last_strategy_error_at",v).putString("last_strategy_error",message.take(240)).apply()
    }
    fun clearStrategyError(){prefs.edit().putLong("last_strategy_error_at",0L).remove("last_strategy_error").apply()}
    fun lastStrategyCatalogRefreshAt():Long=prefs.getLong("last_strategy_catalog_refresh_at",0L)
    fun strategyDeepScanCursor():Int=prefs.getInt("strategy_deep_scan_cursor_v157",0).coerceAtLeast(0)
    fun setStrategyDeepScanCursor(value:Int){prefs.edit().putInt("strategy_deep_scan_cursor_v157",value.coerceAtLeast(0)).apply()}
    fun setLastStrategyCatalogRefreshAt(v:Long){prefs.edit().putLong("last_strategy_catalog_refresh_at",v).apply()}
    fun strategyCatalogVersion():String=prefs.getString("strategy_catalog_version","embedded").orEmpty()
    fun savedStrategyCatalogJson():String?=prefs.getString("strategy_catalog_json",null)
    fun saveStrategyCatalog(version:String,raw:String){prefs.edit().putString("strategy_catalog_version",version).putString("strategy_catalog_json",raw).putLong("last_strategy_catalog_refresh_at",System.currentTimeMillis()).apply()}

    private fun strategySetupToJson(x:StrategySetup):JSONObject = JSONObject()
        .put("symbol",x.symbol).put("companyName",x.companyName).put("strategyId",x.strategyId).put("strategyName",x.strategyName)
        .put("direction",x.direction.name).put("score",finite(x.score)).put("entryPrice",finite(x.entryPrice))
        .put("targetPct",finite(x.targetPct)).put("stopPct",finite(x.stopPct)).put("evidence",x.evidence)
        .put("listingAgeDays",x.listingAgeDays?:-1).put("generatedAt",x.generatedAt)
        .put("researchSignature",x.researchSignature).put("handbookQualityPct",finite(x.handbookQualityPct))
        .put("handbookPattern",x.handbookPattern).put("handbookCombination",x.handbookCombination)
        .put("rawScore",finite(x.rawScore)).put("calibratedProbabilityPct",finite(x.calibratedProbabilityPct)).put("expectedR",finite(x.expectedR))
        .put("contextBand",x.contextBand).put("contextRegime",x.contextRegime)
        .put("componentStrategyIds",JSONArray().apply{x.componentStrategyIds.forEach{put(it)}}).put("strategyModelVersion",x.modelVersion)

    private fun strategySetupFromJson(x:JSONObject):StrategySetup = StrategySetup(
        x.optString("symbol"),x.optString("companyName"),x.optString("strategyId"),x.optString("strategyName"),
        runCatching{TradeDirection.valueOf(x.optString("direction"))}.getOrDefault(TradeDirection.LONG),
        x.optDouble("score"),x.optDouble("entryPrice"),x.optDouble("targetPct"),x.optDouble("stopPct"),x.optString("evidence"),
        x.optLong("listingAgeDays",-1).takeIf{it>=0},x.optLong("generatedAt"),
        x.optString("researchSignature"),x.optDouble("handbookQualityPct"),x.optString("handbookPattern"),x.optString("handbookCombination"),
        x.optDouble("rawScore",x.optDouble("score")),x.optDouble("calibratedProbabilityPct",0.0),x.optDouble("expectedR",0.0),
        x.optString("contextBand"),x.optString("contextRegime"),
        buildList{val a=x.optJSONArray("componentStrategyIds")?:JSONArray();for(i in 0 until a.length()){val v=a.optString(i);if(v.isNotBlank())add(v)}},
        x.optString("strategyModelVersion","LEGACY-V1")
    )

    private fun recommendationToJson(r:StrategyRecommendation):JSONObject = JSONObject()
        .put("id",r.id).put("setup",strategySetupToJson(r.setup)).put("openedAt",r.openedAt).put("lastSeenAt",r.lastSeenAt)
        .put("lastPrice",finite(r.lastPrice)).put("closedAt",r.closedAt).put("exitPrice",finite(r.exitPrice)).put("status",r.status.name)
        .put("returnPct",finite(r.returnPct)).put("closeReason",r.closeReason).put("tradedValue",finite(r.tradedValue))
        .put("volume",r.volume).put("spreadPct",finite(r.spreadPct))

    private fun recommendationFromJson(j:JSONObject):StrategyRecommendation? = runCatching{
        val setup=strategySetupFromJson(j.optJSONObject("setup")?:return@runCatching null)
        StrategyRecommendation(
            id=j.optString("id"),setup=setup,openedAt=j.optLong("openedAt"),lastSeenAt=j.optLong("lastSeenAt"),
            lastPrice=j.optDouble("lastPrice",setup.entryPrice),closedAt=j.optLong("closedAt"),exitPrice=j.optDouble("exitPrice"),
            status=runCatching{StrategyRecommendationStatus.valueOf(j.optString("status"))}.getOrDefault(StrategyRecommendationStatus.LIVE),
            returnPct=j.optDouble("returnPct"),closeReason=j.optString("closeReason"),tradedValue=j.optDouble("tradedValue"),
            volume=j.optLong("volume"),spreadPct=j.optDouble("spreadPct")
        )
    }.getOrNull()

    fun saveStrategySummary(summary:StrategyTournamentSummary){
        val setups=JSONArray();summary.topSetups.forEach{x->setups.put(strategySetupToJson(x))}
        val active=JSONArray();summary.activeStrategies.forEach{x->active.put(strategyDefToJson(x))}
        val perfs=JSONArray();summary.performances.forEach{x->perfs.put(JSONObject().put("strategyId",x.strategyId).put("name",x.name).put("observations",x.observations).put("wins",x.wins).put("accuracyPct",finite(x.accuracyPct)).put("avgReturnPct",finite(x.avgReturnPct)).put("expectancyPct",finite(x.expectancyPct)).put("maxDrawdownPct",finite(x.maxDrawdownPct)).put("confidenceFloorPct",finite(x.confidenceFloorPct)).put("status",x.status.name)
            .put("recentAccuracyPct",finite(x.recentAccuracyPct)).put("distinctSessions",x.distinctSessions).put("shadowAccuracyPct",finite(x.shadowAccuracyPct)).put("netExpectancyR",finite(x.netExpectancyR)).put("contextLabel",x.contextLabel))}
        val insights=JSONArray();summary.championInsights.forEach{insights.put(it)}
        val root=JSONObject().put("generatedAt",summary.generatedAt).put("universeCount",summary.universeCount).put("strategiesRun",summary.strategiesRun).put("symbolsEnriched",summary.symbolsEnriched).put("catalogVersion",summary.catalogVersion).put("message",summary.message).put("topSetups",setups).put("activeStrategies",active).put("performances",perfs)
            .put("championInsights",insights).put("rejectedJournalCount",summary.rejectedJournalCount).put("handbookVersion",summary.handbookVersion)
            .put("productionRosterDate",summary.productionRosterDate).put("productionContext",summary.productionContext).put("strategyV2EventCount",summary.strategyV2EventCount)
        // v1.1.0: the persistent recommendation ledger is the source of truth. Do not overwrite a same-day
        // strategy list on every scan; that was the reason notified ideas appeared to vanish.
        prefs.edit().putString("strategy_tournament_summary",root.toString()).putLong("last_strategy_scan_at",summary.generatedAt)
            .putLong("last_strategy_attempt_at",summary.generatedAt).putLong("last_strategy_error_at",0L).remove("last_strategy_error").apply()
    }
    fun loadStrategySummary():StrategyTournamentSummary?{
        val raw=prefs.getString("strategy_tournament_summary",null)?:return null
        return runCatching{
            val j=JSONObject(raw);val setups=jsonToSetups(j.optJSONArray("topSetups")?:JSONArray());val defs=jsonToDefs(j.optJSONArray("activeStrategies")?:JSONArray())
            val pfs=j.optJSONArray("performances")?:JSONArray()
            val perfs=buildList{for(i in 0 until pfs.length()){val x=pfs.optJSONObject(i)?:continue;add(StrategyPerformance(x.optString("strategyId"),x.optString("name"),x.optInt("observations"),x.optInt("wins"),x.optDouble("accuracyPct"),x.optDouble("avgReturnPct"),x.optDouble("expectancyPct"),x.optDouble("maxDrawdownPct"),x.optDouble("confidenceFloorPct"),runCatching{StrategyStatus.valueOf(x.optString("status"))}.getOrDefault(StrategyStatus.CHALLENGER),
                x.optDouble("recentAccuracyPct"),x.optInt("distinctSessions"),x.optDouble("shadowAccuracyPct"),x.optDouble("netExpectancyR"),x.optString("contextLabel")))}}
            val ia=j.optJSONArray("championInsights")?:JSONArray();val insights=buildList{for(i in 0 until ia.length()){val v=ia.optString(i);if(v.isNotBlank())add(v)}}
            StrategyTournamentSummary(j.optLong("generatedAt"),j.optInt("universeCount"),j.optInt("strategiesRun"),j.optInt("symbolsEnriched"),setups,defs,perfs,j.optString("catalogVersion"),j.optString("message"),insights,j.optInt("rejectedJournalCount"),j.optString("handbookVersion"),
                j.optString("productionRosterDate"),j.optString("productionContext"),j.optInt("strategyV2EventCount"))
        }.getOrNull()
    }

    fun loadStrategyLive():List<StrategyRecommendation>{
        val a=runCatching{JSONArray(prefs.getString("strategy_live_ledger","[]")?:"[]")}.getOrElse{JSONArray()}
        return buildList{for(i in 0 until a.length()){recommendationFromJson(a.optJSONObject(i)?:continue)?.let(::add)}}
            .filter{it.status==StrategyRecommendationStatus.LIVE}.sortedByDescending{it.openedAt}
    }
    fun loadStrategyClosed(limit:Int=250):List<StrategyRecommendation>{
        val a=runCatching{JSONArray(prefs.getString("strategy_closed_ledger","[]")?:"[]")}.getOrElse{JSONArray()}
        return buildList{for(i in 0 until a.length()){recommendationFromJson(a.optJSONObject(i)?:continue)?.let(::add)}}
            .filter{it.status!=StrategyRecommendationStatus.LIVE}.sortedByDescending{it.closedAt}.take(limit)
    }
    fun saveStrategyLedger(live:List<StrategyRecommendation>,closed:List<StrategyRecommendation>){
        val la=JSONArray();live.sortedByDescending{it.openedAt}.take(100).forEach{la.put(recommendationToJson(it))}
        val ca=JSONArray();closed.sortedByDescending{it.closedAt}.take(1500).forEach{ca.put(recommendationToJson(it))}
        prefs.edit().putString("strategy_live_ledger",la.toString()).putString("strategy_closed_ledger",ca.toString()).apply()
    }

    private fun tradeCallToJson(r:TradeCallRecord):JSONObject = JSONObject()
        .put("id",r.id).put("engine",r.engine.name).put("bucket",r.bucket.name).put("symbol",r.symbol).put("companyName",r.companyName)
        .put("direction",r.direction.name).put("score",finite(r.score)).put("entryPrice",finite(r.entryPrice)).put("stopPrice",finite(r.stopPrice))
        .put("targetPrice",finite(r.targetPrice)).put("target2Price",finiteOrNull(r.target2Price)).put("openedAt",r.openedAt)
        .put("targetSessionDate",r.targetSessionDate).put("sourceLabel",r.sourceLabel).put("detail",r.detail).put("learningKey",r.learningKey)
        .put("outcome",r.outcome.name).put("closedAt",r.closedAt).put("exitPrice",finite(r.exitPrice)).put("returnPct",finite(r.returnPct)).put("closeReason",r.closeReason)

    private fun tradeCallFromJson(j:JSONObject):TradeCallRecord? = runCatching{
        val target2=if(j.has("target2Price")&&!j.isNull("target2Price"))j.optDouble("target2Price") else null
        TradeCallRecord(
            id=j.optString("id"),
            engine=runCatching{TradeCallEngine.valueOf(j.optString("engine"))}.getOrDefault(TradeCallEngine.UPPER_CIRCUIT),
            bucket=runCatching{TradeCallBucket.valueOf(j.optString("bucket"))}.getOrDefault(TradeCallBucket.LIVE),
            symbol=j.optString("symbol"),companyName=j.optString("companyName"),
            direction=runCatching{TradeDirection.valueOf(j.optString("direction"))}.getOrDefault(TradeDirection.LONG),
            score=j.optDouble("score"),entryPrice=j.optDouble("entryPrice"),stopPrice=j.optDouble("stopPrice"),targetPrice=j.optDouble("targetPrice"),target2Price=target2,
            openedAt=j.optLong("openedAt"),targetSessionDate=j.optString("targetSessionDate"),sourceLabel=j.optString("sourceLabel"),detail=j.optString("detail"),learningKey=j.optString("learningKey"),
            outcome=runCatching{TradeCallOutcome.valueOf(j.optString("outcome"))}.getOrDefault(TradeCallOutcome.OPEN),closedAt=j.optLong("closedAt"),exitPrice=j.optDouble("exitPrice"),
            returnPct=j.optDouble("returnPct"),closeReason=j.optString("closeReason")
        )
    }.getOrNull()

    fun loadTradeCalls(limit:Int=1500):List<TradeCallRecord>{
        val a=runCatching{JSONArray(prefs.getString("trade_call_ledger_v118","[]")?:"[]")}.getOrElse{JSONArray()}
        return buildList{for(i in 0 until a.length()){tradeCallFromJson(a.optJSONObject(i)?:continue)?.let(::add)}}
            .sortedByDescending{it.openedAt}.take(limit)
    }
    fun saveTradeCalls(records:List<TradeCallRecord>){
        val a=JSONArray();records.distinctBy{it.id}.sortedByDescending{it.openedAt}.take(1500).forEach{a.put(tradeCallToJson(it))}
        prefs.edit().putString("trade_call_ledger_v118",a.toString()).apply()
    }

    private fun isScoredTradeOutcome(outcome:TradeCallOutcome):Boolean=
        outcome==TradeCallOutcome.WIN||outcome==TradeCallOutcome.LOSS

    private fun rebuildGlobalLearningFromValidCalls(records:List<TradeCallRecord>){
        val root=JSONObject()
        records.filter{
            it.engine==TradeCallEngine.GLOBAL&&it.bucket==TradeCallBucket.LIVE&&isScoredTradeOutcome(it.outcome)&&
                it.learningKey.isNotBlank()&&it.returnPct.isFinite()
        }.sortedBy{it.closedAt}.forEach{call->
            val x=root.optJSONObject(call.learningKey)?:JSONObject()
            val n=x.optInt("observations").coerceAtLeast(0)
            val wins=x.optInt("wins").coerceAtLeast(0)
            val sum=x.optDouble("sumReturn",0.0).takeIf{it.isFinite()}?:0.0
            x.put("observations",n+1)
                .put("wins",wins+(if(call.outcome==TradeCallOutcome.WIN)1 else 0))
                .put("sumReturn",sum+call.returnPct)
                .put("lastAt",call.closedAt.takeIf{it>0L}?:call.openedAt)
            root.put(call.learningKey,x)
        }
        prefs.edit().putString("global_learning_stats",root.toString()).apply()
    }

    private fun migrateTradeCallIntegrityV159(){
        if(prefs.getBoolean("trade_call_integrity_v159",false))return
        val all=loadTradeCalls(1500).sortedBy{it.openedAt}
        val seenGlobalSession=mutableSetOf<String>()
        var invalidated=0
        val cleaned=all.map{call->
            val opened=runCatching{Instant.ofEpochMilli(call.openedAt).atZone(ist)}.getOrNull()
            var reason:String?=null
            if(opened!=null&&call.bucket==TradeCallBucket.LIVE&&(call.engine==TradeCallEngine.UPPER_CIRCUIT||call.engine==TradeCallEngine.PRESSURE)){
                val t=opened.toLocalTime()
                if(t<LocalTime.of(9,15)||t>LocalTime.of(15,30))reason="Invalidated by v1.5.9 • LIVE call was created outside NSE 09:15–15:30 IST"
            }
            if(opened!=null&&call.engine==TradeCallEngine.GLOBAL&&call.bucket==TradeCallBucket.LIVE){
                val key="${opened.toLocalDate()}|${call.symbol}|${call.direction.name}"
                if(!seenGlobalSession.add(key))reason="Invalidated by v1.5.9 • duplicate Global LIVE call for the same symbol/direction/session"
            }
            if(reason!=null&&call.outcome!=TradeCallOutcome.INVALID){
                invalidated++
                call.copy(outcome=TradeCallOutcome.INVALID,closedAt=call.closedAt.takeIf{it>0L}?:System.currentTimeMillis(),closeReason=reason)
            }else call
        }
        if(invalidated>0)saveTradeCalls(cleaned)
        rebuildGlobalLearningFromValidCalls(cleaned)
        prefs.edit().putBoolean("trade_call_integrity_v159",true).putInt("trade_call_integrity_v159_invalidated",invalidated).apply()
    }

    fun enforceGlobalLiveOnlyFlow():Int{
        if(prefs.getBoolean("global_live_only_flow_v154",false))return 0
        val all=loadTradeCalls(1500)
        val researchOnly=all.filter{it.engine==TradeCallEngine.GLOBAL&&it.bucket!=TradeCallBucket.LIVE}

        if(researchOnly.isNotEmpty()){
            val archive=runCatching{JSONArray(prefs.getString("global_next_research_archive_v154","[]")?:"[]")}.getOrElse{JSONArray()}
            val known=mutableSetOf<String>()
            for(i in 0 until archive.length())archive.optJSONObject(i)?.optString("id")?.takeIf{it.isNotBlank()}?.let(known::add)
            researchOnly.filterNot{it.id in known}.forEach{archive.put(tradeCallToJson(it))}
            saveTradeCalls(all.filterNot{it.engine==TradeCallEngine.GLOBAL&&it.bucket!=TradeCallBucket.LIVE})
            prefs.edit().putString("global_next_research_archive_v154",archive.toString()).apply()
        }

        // Rebuild Global calibration from confirmed LIVE-origin outcomes only.
        val root=JSONObject()
        all.filter{
            it.engine==TradeCallEngine.GLOBAL&&it.bucket==TradeCallBucket.LIVE&&
                isScoredTradeOutcome(it.outcome)&&it.learningKey.isNotBlank()&&it.returnPct.isFinite()
        }.sortedBy{it.closedAt}.forEach{call->
            val x=root.optJSONObject(call.learningKey)?:JSONObject()
            val n=x.optInt("observations").coerceAtLeast(0)
            val wins=x.optInt("wins").coerceAtLeast(0)
            val sum=x.optDouble("sumReturn",0.0).takeIf{it.isFinite()}?:0.0
            x.put("observations",n+1)
                .put("wins",wins+(if(call.outcome==TradeCallOutcome.WIN)1 else 0))
                .put("sumReturn",sum+call.returnPct)
                .put("lastAt",call.closedAt.takeIf{it>0L}?:call.openedAt)
            root.put(call.learningKey,x)
        }
        prefs.edit()
            .putString("global_learning_stats",root.toString())
            .putBoolean("global_live_only_flow_v154",true)
            .apply()
        return researchOnly.size
    }

    private fun rejectedShadowToJson(r:RejectedCandidateRecord)=JSONObject()
        .put("id",r.id).put("engineLabel",r.engineLabel).put("symbol",r.symbol).put("direction",r.direction.name)
        .put("score",finite(r.score)).put("entryPrice",finite(r.entryPrice)).put("stopPrice",finite(r.stopPrice)).put("targetPrice",finite(r.targetPrice))
        .put("capturedAt",r.capturedAt).put("targetSessionDate",r.targetSessionDate).put("reason",r.reason).put("outcome",r.outcome.name)
        .put("closedAt",r.closedAt).put("exitPrice",finite(r.exitPrice)).put("returnPct",finite(r.returnPct))

    private fun rejectedShadowFromJson(j:JSONObject):RejectedCandidateRecord?=runCatching{
        RejectedCandidateRecord(j.optString("id"),j.optString("engineLabel"),j.optString("symbol"),
            runCatching{TradeDirection.valueOf(j.optString("direction"))}.getOrDefault(TradeDirection.LONG),j.optDouble("score"),j.optDouble("entryPrice"),
            j.optDouble("stopPrice"),j.optDouble("targetPrice"),j.optLong("capturedAt"),j.optString("targetSessionDate"),j.optString("reason"),
            runCatching{RejectedShadowOutcome.valueOf(j.optString("outcome"))}.getOrDefault(RejectedShadowOutcome.PENDING),j.optLong("closedAt"),j.optDouble("exitPrice"),j.optDouble("returnPct"))
    }.getOrNull()

    fun loadRejectedShadows(limit:Int=2500):List<RejectedCandidateRecord>{
        val a=runCatching{JSONArray(prefs.getString("rejected_shadow_v121","[]")?:"[]")}.getOrElse{JSONArray()}
        return buildList{for(i in 0 until a.length())rejectedShadowFromJson(a.optJSONObject(i)?:continue)?.let(::add)}.sortedByDescending{it.capturedAt}.take(limit)
    }
    fun saveRejectedShadows(records:List<RejectedCandidateRecord>){
        val a=JSONArray();records.distinctBy{it.id}.sortedByDescending{it.capturedAt}.take(2500).forEach{a.put(rejectedShadowToJson(it))}
        prefs.edit().putString("rejected_shadow_v121",a.toString()).apply()
    }
    fun appendRejectedShadow(record:RejectedCandidateRecord){
        val all=loadRejectedShadows(2500).toMutableList()
        val duplicate=all.any{it.outcome==RejectedShadowOutcome.PENDING&&it.engineLabel==record.engineLabel&&it.symbol==record.symbol&&it.direction==record.direction&&it.targetSessionDate==record.targetSessionDate&&kotlin.math.abs(it.score-record.score)<3.0&&record.capturedAt-it.capturedAt<60L*60_000L}
        if(!duplicate){all+=record;saveRejectedShadows(all)}
    }

    private fun accuracyObservations(engineLabel:String):List<AccuracyLearningEngine.Observation>{
        val normalized=engineLabel.uppercase()
        if(normalized=="STRATEGY")return loadStrategyClosed(1000).filter{it.status==StrategyRecommendationStatus.WIN||it.status==StrategyRecommendationStatus.LOSS}.map{
            AccuracyLearningEngine.Observation(it.setup.score,it.status==StrategyRecommendationStatus.WIN,it.closedAt,
                java.time.Instant.ofEpochMilli(it.openedAt).atZone(ist).toLocalDate().toString())
        }
        val engine=runCatching{TradeCallEngine.valueOf(normalized)}.getOrNull()?:return emptyList()
        return loadTradeCalls(1500).filter{it.engine==engine&&isScoredTradeOutcome(it.outcome)}.map{
            AccuracyLearningEngine.Observation(it.score,it.outcome==TradeCallOutcome.WIN,it.closedAt,it.targetSessionDate)
        }
    }
    fun confidenceCalibration(engineLabel:String,rawScore:Double):ConfidenceCalibration=
        AccuracyLearningEngine().calibrate(engineLabel,rawScore,accuracyObservations(engineLabel))
    fun walkForwardValidation(engineLabel:String):WalkForwardValidation=
        AccuracyLearningEngine().walkForward(engineLabel,accuracyObservations(engineLabel))
    fun calibrationThresholdAdjustment(engineLabel:String):Double=
        AccuracyLearningEngine().thresholdAdjustment(engineLabel,accuracyObservations(engineLabel))

    private fun shadowToJson(x:ShadowStrategyResult)=JSONObject().put("strategyId",x.strategyId).put("strategyName",x.strategyName)
        .put("traded",x.traded).put("outcome",x.outcome.name).put("returnPct",finite(x.returnPct)).put("evidence",x.evidence)
    private fun shadowFromJson(j:JSONObject)=ShadowStrategyResult(j.optString("strategyId"),j.optString("strategyName"),j.optBoolean("traded"),runCatching{ShadowOutcome.valueOf(j.optString("outcome"))}.getOrDefault(ShadowOutcome.NO_TRADE),j.optDouble("returnPct"),j.optString("evidence"))
    private fun autopsyToJson(a:TradeAutopsyRecord):JSONObject{
        val ev=JSONArray();a.evidence.forEach{ev.put(it)};val news=JSONArray();a.newsEvidence.forEach{news.put(it)};val sh=JSONArray();a.shadowResults.forEach{sh.put(shadowToJson(it))}
        return JSONObject().put("id",a.id).put("sourceId",a.sourceId).put("engineLabel",a.engineLabel).put("symbol",a.symbol).put("originalOutcome",a.originalOutcome)
            .put("originalReturnPct",finite(a.originalReturnPct)).put("openedAt",a.openedAt).put("closedAt",a.closedAt).put("generatedAt",a.generatedAt)
            .put("regime",a.regime.name).put("regimeConfidencePct",finite(a.regimeConfidencePct)).put("dominantCause",a.dominantCause.name).put("causeConfidencePct",finite(a.causeConfidencePct))
            .put("evidence",ev).put("newsEvidence",news).put("shadowResults",sh).put("recommendedRule",a.recommendedRule).put("sessionDate",a.sessionDate)
    }
    private fun autopsyFromJson(j:JSONObject):TradeAutopsyRecord?=runCatching{
        fun strings(a:JSONArray)=buildList{for(i in 0 until a.length()){val v=a.optString(i);if(v.isNotBlank())add(v)}}
        val sh=j.optJSONArray("shadowResults")?:JSONArray();val shadows=buildList{for(i in 0 until sh.length())add(shadowFromJson(sh.optJSONObject(i)?:continue))}
        TradeAutopsyRecord(j.optString("id"),j.optString("sourceId"),j.optString("engineLabel"),j.optString("symbol"),j.optString("originalOutcome"),j.optDouble("originalReturnPct"),
            j.optLong("openedAt"),j.optLong("closedAt"),j.optLong("generatedAt"),runCatching{MarketRegime.valueOf(j.optString("regime"))}.getOrDefault(MarketRegime.MIXED),j.optDouble("regimeConfidencePct"),
            runCatching{AutopsyCause.valueOf(j.optString("dominantCause"))}.getOrDefault(AutopsyCause.NO_DOMINANT_CAUSE),j.optDouble("causeConfidencePct"),strings(j.optJSONArray("evidence")?:JSONArray()),
            strings(j.optJSONArray("newsEvidence")?:JSONArray()),shadows,j.optString("recommendedRule"),j.optString("sessionDate"))
    }.getOrNull()
    fun loadAutopsies(limit:Int=800):List<TradeAutopsyRecord>{
        val a=runCatching{JSONArray(prefs.getString("trade_autopsies_v120","[]")?:"[]")}.getOrElse{JSONArray()}
        return buildList{for(i in 0 until a.length())autopsyFromJson(a.optJSONObject(i)?:continue)?.let(::add)}.sortedByDescending{it.generatedAt}.take(limit)
    }
    fun hasAutopsy(sourceId:String):Boolean=loadAutopsies(1000).any{it.sourceId==sourceId}
    fun saveAutopsy(record:TradeAutopsyRecord){
        val all=loadAutopsies(1000).filterNot{it.sourceId==record.sourceId}.toMutableList();all.add(record)
        val a=JSONArray();all.sortedByDescending{it.generatedAt}.take(800).forEach{a.put(autopsyToJson(it))}
        prefs.edit().putString("trade_autopsies_v120",a.toString()).apply();updateShadowLearning(record)
    }
    private fun updateShadowLearning(record:TradeAutopsyRecord){
        val root=runCatching{JSONObject(prefs.getString("shadow_strategy_stats_v120","{}")?:"{}")}.getOrElse{JSONObject()}
        for(x in record.shadowResults){
            val key="${record.engineLabel}|${record.regime.name}|${x.strategyId}"
            val j=root.optJSONObject(key)?:JSONObject()
            val safeReturn=finite(x.returnPct)
            j.put("strategyName",x.strategyName).put("observations",j.optInt("observations").coerceAtLeast(0)+1)
                .put("tradedWins",j.optInt("tradedWins").coerceAtLeast(0)+(if(x.outcome==ShadowOutcome.WIN)1 else 0))
                .put("tradedLosses",j.optInt("tradedLosses").coerceAtLeast(0)+(if(x.outcome==ShadowOutcome.LOSS)1 else 0))
                .put("avoidedLosses",j.optInt("avoidedLosses").coerceAtLeast(0)+(if(x.outcome==ShadowOutcome.AVOIDED_LOSS)1 else 0))
                .put("missedWins",j.optInt("missedWins").coerceAtLeast(0)+(if(x.outcome==ShadowOutcome.MISSED_WIN)1 else 0))
                .put("sumReturn",finite(storedFinite(j,"sumReturn")+safeReturn)).put("lastAt",record.generatedAt)
            val days=j.optString("days").split(',').filter{it.isNotBlank()}.toMutableSet()
            days+=record.sessionDate
            j.put("days",days.sorted().takeLast(30).joinToString(","))
            root.put(key,j)
        }
        prefs.edit().putString("shadow_strategy_stats_v120",root.toString()).apply()
    }

    fun shadowLearningSummary(limit:Int=20):List<String>{
        data class Row(val date:String,val outcome:ShadowOutcome)
        val groups=linkedMapOf<String,MutableList<Row>>()
        loadAutopsies(800).sortedBy{it.closedAt}.forEach{a->a.shadowResults.forEach{x->
            val key="${a.engineLabel}|${a.regime.name}|${x.strategyId}";groups.getOrPut(key){mutableListOf()}+=Row(a.sessionDate,x.outcome)
        }}
        fun utility(rows:List<Row>):Double{if(rows.isEmpty())return 0.0;val good=rows.count{it.outcome==ShadowOutcome.WIN||it.outcome==ShadowOutcome.AVOIDED_LOSS};val bad=rows.count{it.outcome==ShadowOutcome.LOSS||it.outcome==ShadowOutcome.MISSED_WIN};return (good-bad).toDouble()/rows.size}
        return groups.mapNotNull{(k,rows)->
            val days=rows.map{it.date}.filter{it.isNotBlank()}.distinct();val n=rows.size;if(n==0)return@mapNotNull null
            val split=(days.size*0.70).toInt().coerceIn(1,maxOf(1,days.size-1));val trainDays=days.take(split).toSet();val valDays=days.drop(split).toSet()
            val train=rows.filter{it.date in trainDays};val validation=rows.filter{it.date in valDays};val tu=utility(train);val vu=utility(validation)
            val good=rows.count{it.outcome==ShadowOutcome.WIN||it.outcome==ShadowOutcome.AVOIDED_LOSS};val bad=rows.count{it.outcome==ShadowOutcome.LOSS||it.outcome==ShadowOutcome.MISSED_WIN}
            val walkForwardPass=n>=12&&days.size>=4&&validation.size>=4&&tu>=0.15&&vu>=0.15&&kotlin.math.abs(tu-vu)<=0.35
            n to "$k • n=$n days=${days.size} • useful=$good harmful=$bad • train=${"%+.2f".format(tu)} validation=${"%+.2f".format(vu)} • ${if(walkForwardPass)"PROMOTION ELIGIBLE" else "SHADOW / WALK-FORWARD HOLD"}"
        }.sortedByDescending{it.first}.take(limit).map{it.second}
    }
    fun autopsyThresholdAdjustment(engineLabel:String):Double{
        val cutoff=System.currentTimeMillis()-30L*24*60*60*1000;val rows=loadAutopsies(800).filter{it.engineLabel==engineLabel&&it.generatedAt>=cutoff};if(rows.size<8||rows.map{it.sessionDate}.distinct().size<3)return 0.0
        val wins=rows.count{it.originalOutcome.equals("WIN",true)};val winRate=wins.toDouble()/rows.size
        val systematic=rows.count{it.dominantCause in setOf(AutopsyCause.VOLUME_FAILURE,AutopsyCause.MOMENTUM_FAILURE,AutopsyCause.ENTRY_TIMING,AutopsyCause.INDEX_DIVERGENCE,AutopsyCause.MARKET_REVERSAL)}
        return when{winRate<0.45&&systematic>=rows.size/2->1.0;winRate>=0.75&&rows.size>=12->-0.5;else->0.0}
    }

    // Legacy v1.0.x next-session setup memory is retained only so older installs can finish learning old records.
    fun pendingStrategyDates():List<LocalDate>{val p="strategy_pending_";return prefs.all.keys.asSequence().filter{it.startsWith(p)}.mapNotNull{runCatching{LocalDate.parse(it.removePrefix(p))}.getOrNull()}.sorted().toList()}
    fun pendingStrategySetups(date:String):List<StrategySetup> =jsonToSetups(runCatching{JSONArray(prefs.getString("strategy_pending_$date","[]"))}.getOrElse{JSONArray()})
    fun strategyDateEvaluated(date:String)=prefs.getBoolean("strategy_evaluated_$date",false)
    fun markStrategyDateEvaluated(date:String){prefs.edit().putBoolean("strategy_evaluated_$date",true).apply()}
    fun strategyV2Migrated():Boolean=prefs.getBoolean("strategy_v2_room_migrated_170",false)
    fun markStrategyV2Migrated(){prefs.edit().putBoolean("strategy_v2_room_migrated_170",true).apply()}
    fun legacyStrategyPriors():List<StrategyLegacyPrior>{
        val root=runCatching{JSONObject(prefs.getString("strategy_stats","{}")?:"{}")}.getOrElse{JSONObject()}
        val out=mutableListOf<StrategyLegacyPrior>();val keys=root.keys()
        while(keys.hasNext()){
            val id=keys.next();val x=root.optJSONObject(id)?:continue
            val n=x.optInt("observations").coerceAtLeast(0);if(n<=0)continue
            val w=x.optInt("wins").coerceIn(0,n)
            val avg=storedFinite(x,"sumReturn")/n
            out+=StrategyLegacyPrior(id,x.optString("name",id),n,w,finite(avg))
        }
        return out
    }

    fun updateStrategyResult(strategyId:String,name:String,returnPct:Double,win:Boolean){
        val safeReturn=finite(returnPct)
        val root=runCatching{JSONObject(prefs.getString("strategy_stats","{}")?:"{}")}.getOrElse{JSONObject()}
        val x=root.optJSONObject(strategyId)?:JSONObject()
        val n=x.optInt("observations").coerceAtLeast(0)+1
        val wins=x.optInt("wins").coerceIn(0,n-1)+(if(win)1 else 0)
        val sum=finite(storedFinite(x,"sumReturn")+safeReturn)
        val equity=finite(storedFinite(x,"equity")+safeReturn)
        val peak=maxOf(storedFinite(x,"peak"),equity)
        val dd=minOf(storedFinite(x,"maxDrawdown"),equity-peak)
        x.put("name",name).put("observations",n).put("wins",wins)
            .put("sumReturn",finite(sum)).put("equity",finite(equity))
            .put("peak",finite(peak)).put("maxDrawdown",finite(dd))
        root.put(strategyId,x)
        prefs.edit().putString("strategy_stats",root.toString()).apply()
    }

    fun strategyPerformances(defs:List<TradingStrategyDefinition>,settings:AppSettings):List<StrategyPerformance>{
        val legacy=runCatching{JSONObject(prefs.getString("strategy_stats","{}")?:"{}")}.getOrElse{JSONObject()}
        val durable=loadStrategyClosed(2000).filter{it.status==StrategyRecommendationStatus.WIN||it.status==StrategyRecommendationStatus.LOSS}.sortedBy{it.closedAt}
        val shadowAll=loadChallengerShadows(4000).filter{it.outcome==ChallengerShadowOutcome.WIN||it.outcome==ChallengerShadowOutcome.LOSS}.sortedBy{it.resolvedAt}
        fun winRate(rows:List<StrategyRecommendation>)=if(rows.isEmpty())0.0 else rows.count{it.status==StrategyRecommendationStatus.WIN}*100.0/rows.size
        fun avgReturn(rows:List<StrategyRecommendation>)=if(rows.isEmpty())0.0 else rows.map{finite(it.returnPct)}.average()
        fun maxDrawdown(rows:List<StrategyRecommendation>):Double{
            var equity=0.0;var peak=0.0;var trough=0.0
            rows.forEach{equity+=finite(it.returnPct);peak=maxOf(peak,equity);trough=minOf(trough,equity-peak)}
            return kotlin.math.abs(trough)
        }
        return defs.map{d->
            val rows=durable.filter{it.setup.strategyId==d.id}
            val shadow=shadowAll.filter{it.strategyId==d.id}
            val shadowN=shadow.size;val shadowWins=shadow.count{it.outcome==ChallengerShadowOutcome.WIN}
            val shadowAcc=if(shadowN==0)0.0 else shadowWins*100.0/shadowN
            val shadowAvg=if(shadowN==0)0.0 else shadow.map{finite(it.returnPct)}.average()
            val shadowDays=shadow.map{it.scheduledSessionDate}.filter{it.isNotBlank()}.distinct().size
            val shadowHoldoutSize=if(shadowN<4)0 else kotlin.math.ceil(shadowN*0.25).toInt().coerceAtLeast(3).coerceAtMost(shadowN)
            val shadowHoldout=if(shadowHoldoutSize>0)shadow.takeLast(shadowHoldoutSize) else emptyList()
            val shadowHoldoutAcc=if(shadowHoldout.isEmpty())0.0 else shadowHoldout.count{it.outcome==ChallengerShadowOutcome.WIN}*100.0/shadowHoldout.size
            val shadowHoldoutAvg=if(shadowHoldout.isEmpty())0.0 else shadowHoldout.map{finite(it.returnPct)}.average()
            val shadowAdjusted=shadowAcc-multipleTestingPenaltyPct(shadowN)
            val shadowQualified=shadowN>=12&&shadowDays>=4&&shadowAvg>0.0&&shadowAcc>=55.0&&shadowHoldout.size>=3&&shadowHoldoutAcc>=50.0&&shadowHoldoutAvg>0.0&&shadowAdjusted>=48.0

            val x=legacy.optJSONObject(d.id)?:JSONObject()
            val liveN=if(rows.isNotEmpty())rows.size else x.optInt("observations").coerceAtLeast(0)
            val liveW=if(rows.isNotEmpty())rows.count{it.status==StrategyRecommendationStatus.WIN} else x.optInt("wins").coerceIn(0,liveN)
            val liveAcc=if(liveN==0)0.0 else liveW*100.0/liveN
            val liveAvg=if(rows.isNotEmpty())avgReturn(rows) else if(liveN==0)0.0 else finite(storedFinite(x,"sumReturn")/liveN)
            val floor=wilsonLower(liveW,liveN)*100.0
            val maxDd=if(rows.isNotEmpty())maxDrawdown(rows) else kotlin.math.abs(storedFinite(x,"maxDrawdown"))
            val holdoutSize=if(rows.isEmpty())0 else kotlin.math.ceil(rows.size*0.20).toInt().coerceAtLeast(4).coerceAtMost(rows.size)
            val holdout=if(holdoutSize>0)rows.takeLast(holdoutSize) else emptyList()
            val holdoutAcc=winRate(holdout);val holdoutAvg=avgReturn(holdout)
            val wfStable=rows.size>=18&&strategyWalkForwardStable(rows)
            val adjustedAcc=(liveAcc-multipleTestingPenaltyPct(liveN)).coerceAtLeast(0.0)
            val recent=rows.takeLast(minOf(10,rows.size));val baseline=rows.dropLast(recent.size).takeLast(20)
            val recentAcc=winRate(recent);val recentAvg=avgReturn(recent);val baseAcc=winRate(baseline)
            val decayed=recent.size>=8&&((recentAvg<=0.0&&recentAcc<40.0)||(baseline.size>=8&&baseAcc-recentAcc>=25.0&&recentAvg<0.0))
            val status=when{
                decayed->StrategyStatus.SUSPENDED
                liveN>=settings.strategyMinChampionSamples.coerceAtLeast(30)&&liveAcc>=settings.strategyMinChampionAccuracy&&liveAvg>0.0&&floor>=50.0&&
                    holdout.size>=6&&holdoutAcc>=50.0&&holdoutAvg>0.0&&wfStable&&adjustedAcc>=55.0->StrategyStatus.CHAMPION
                liveN>=18&&!wfStable->StrategyStatus.PROBATION
                liveN>=10&&liveAvg<=0.0->StrategyStatus.PROBATION
                liveN>=5->StrategyStatus.ACTIVE
                shadowN>=12&&!shadowQualified->StrategyStatus.PROBATION
                shadowQualified->StrategyStatus.ACTIVE
                else->StrategyStatus.CHALLENGER
            }
            val totalN=liveN+shadowN
            val totalW=liveW+shadowWins
            val totalAcc=if(totalN==0)0.0 else totalW*100.0/totalN
            val totalAvg=if(totalN==0)0.0 else ((liveAvg*liveN)+(shadowAvg*shadowN))/totalN
            StrategyPerformance(d.id,d.name,totalN,totalW,finite(totalAcc),finite(totalAvg),finite(totalAvg),finite(maxDd),finite(floor),status)
        }.sortedWith(compareBy<StrategyPerformance>{it.status.ordinal}.thenByDescending{it.expectancyPct}.thenByDescending{it.accuracyPct})
    }

    private fun strategyWalkForwardStable(rows:List<StrategyRecommendation>):Boolean{
        val sorted=rows.sortedBy{it.closedAt}
        if(sorted.size<18)return false
        val firstTrain=(sorted.size*0.50).toInt().coerceAtLeast(8)
        val remaining=sorted.size-firstTrain
        val fold=(remaining/3).coerceAtLeast(3)
        var start=firstTrain;var stableFolds=0;var totalFolds=0
        while(start<sorted.size){
            val validation=sorted.subList(start,minOf(sorted.size,start+fold))
            if(validation.size<3)break
            val train=sorted.subList(0,start)
            val trainWin=train.count{it.status==StrategyRecommendationStatus.WIN}*100.0/train.size
            val valWin=validation.count{it.status==StrategyRecommendationStatus.WIN}*100.0/validation.size
            val valAvg=validation.map{finite(it.returnPct)}.average()
            totalFolds++
            if(valAvg>0.0&&valWin>=40.0&&kotlin.math.abs(trainWin-valWin)<=25.0)stableFolds++
            start+=fold
        }
        return totalFolds>=2&&stableFolds==totalFolds
    }

    private fun multipleTestingPenaltyPct(n:Int):Double{
        if(n<=0)return 100.0
        val variants=500.0
        return (kotlin.math.sqrt(2.0*kotlin.math.ln(variants)/n.toDouble())*10.0).coerceIn(2.0,18.0)
    }

    fun championResearchInsights(defs:List<TradingStrategyDefinition>,limit:Int=6):List<String>{
        val closed=loadStrategyClosed(2000).filter{it.status==StrategyRecommendationStatus.WIN||it.status==StrategyRecommendationStatus.LOSS}.sortedBy{it.closedAt}
        fun winRate(rows:List<StrategyRecommendation>)=if(rows.isEmpty())0.0 else rows.count{it.status==StrategyRecommendationStatus.WIN}*100.0/rows.size
        fun avg(rows:List<StrategyRecommendation>)=if(rows.isEmpty())0.0 else rows.map{finite(it.returnPct)}.average()
        data class Row(val text:String,val rank:Double)
        val rows=closed.filter{it.setup.researchSignature.isNotBlank()}.groupBy{it.setup.researchSignature}.mapNotNull{(sig,x)->
            if(x.size<5)return@mapNotNull null
            val hs=kotlin.math.ceil(x.size*0.25).toInt().coerceAtLeast(2).coerceAtMost(x.size);val h=x.takeLast(hs)
            val wr=winRate(x);val hw=winRate(h);val av=avg(x);val hav=avg(h);val penalty=multipleTestingPenaltyPct(x.size);val adj=wr-penalty
            val recent=x.takeLast(minOf(8,x.size));val decayed=recent.size>=6&&avg(recent)<=0.0&&winRate(recent)<40.0
            val label=if(decayed)"DECAY" else if(adj>=55.0&&hav>0.0&&hw>=50.0)"CHAMPION" else "WATCH"
            val display=sig.split("|").drop(1).joinToString(" • ").replace("_"," ").take(78)
            Row(label+" • "+display+" • n="+x.size+" • holdout "+"%.0f".format(hw)+"% • avg "+"%+.2f".format(av)+"% • MT-adj "+"%.1f".format(adj),adj+hav*10.0)
        }.sortedByDescending{it.rank}.take(limit).map{it.text}.toMutableList()

        val resolved=loadRejectedShadows(2500).filter{it.outcome!=RejectedShadowOutcome.PENDING}
        if(resolved.isNotEmpty()){
            val avoided=resolved.count{it.outcome==RejectedShadowOutcome.WOULD_LOSE};val missed=resolved.count{it.outcome==RejectedShadowOutcome.WOULD_WIN}
            rows.add(0,"REJECT JOURNAL • resolved "+resolved.size+" • avoided losses "+avoided+" • missed wins "+missed)
            val bestGate=resolved.groupBy{it.reason.substringBefore(" ").take(42)}.filterValues{it.size>=3}.maxByOrNull{(_,g)->g.count{it.outcome==RejectedShadowOutcome.WOULD_LOSE}-g.count{it.outcome==RejectedShadowOutcome.WOULD_WIN}}
            if(bestGate!=null){
                val g=bestGate.value
                rows.add(1,"GATE LEARNING • "+bestGate.key+" • "+g.count{it.outcome==RejectedShadowOutcome.WOULD_LOSE}+" losses avoided / "+g.count{it.outcome==RejectedShadowOutcome.WOULD_WIN}+" winners missed")
            }
        }
        return rows.take(limit+2)
    }

    private fun wilsonLower(wins:Int,n:Int):Double{if(n<=0)return 0.0;val z=1.96;val p=wins.toDouble()/n;val den=1+z*z/n;val center=p+z*z/(2*n);val margin=z*kotlin.math.sqrt((p*(1-p)+z*z/(4*n))/n);return ((center-margin)/den).coerceIn(0.0,1.0)}
    private fun strategyDefToJson(x:TradingStrategyDefinition)=JSONObject().put("id",x.id).put("name",x.name).put("kind",x.kind).put("family",x.family).put("description",x.description).put("source",x.source).put("priority",x.priority)
    private fun jsonToDefs(a:JSONArray):List<TradingStrategyDefinition> =buildList{for(i in 0 until a.length()){val x=a.optJSONObject(i)?:continue;add(TradingStrategyDefinition(x.optString("id"),x.optString("name"),x.optString("kind"),x.optString("family"),x.optString("description"),x.optString("source"),x.optInt("priority")))}}
    private fun jsonToSetups(a:JSONArray):List<StrategySetup> =buildList{for(i in 0 until a.length()){val x=a.optJSONObject(i)?:continue;add(strategySetupFromJson(x))}}


    fun saveGlobalLeadSummary(summary:GlobalLeadSummary){
        val a=JSONArray();summary.candidates.forEach{c->
            val r=JSONArray();c.reasons.forEach{r.put(it)}
            a.put(JSONObject().put("rank",c.rank).put("indianSymbol",c.indianSymbol).put("indianCompany",c.indianCompany)
                .put("foreignTicker",c.foreignTicker).put("foreignCompany",c.foreignCompany).put("exchange",c.exchange).put("region",c.region)
                .put("mappingType",c.mappingType.name).put("relationshipWeight",finite(c.relationshipWeight)).put("score",finite(c.score)).put("confidence",c.confidence.name)
                .put("action",c.action.name).put("direction",c.direction.name).put("foreignGapPct",finite(c.foreignGapPct)).put("foreignDayPct",finite(c.foreignDayPct)).put("foreignFromOpenPct",finite(c.foreignFromOpenPct))
                .put("foreignExcessPct",finite(c.foreignExcessPct)).put("foreignVolumeRatio",finite(c.foreignVolumeRatio)).put("foreignCloseLocation",finite(c.foreignCloseLocation))
                .put("foreignSignalAt",c.foreignSignalAt).put("indianPrice",finite(c.indianPrice)).put("indianOpen",finite(c.indianOpen)).put("indianFromOpenPct",finite(c.indianFromOpenPct))
                .put("indianDayPct",finite(c.indianDayPct)).put("indianBuySellRatio",finite(c.indianBuySellRatio)).put("freshnessPct",finite(c.freshnessPct))
                .put("pressureConfirmationScore",finite(c.pressureConfirmationScore)).put("expectedTargetPct",finite(c.expectedTargetPct)).put("officialSource",c.officialSource)
                .put("generatedAt",c.generatedAt).put("reasons",r))
        }
        val root=JSONObject().put("generatedAt",summary.generatedAt).put("mappingVersion",summary.mappingVersion).put("mappingsScanned",summary.mappingsScanned)
            .put("foreignQuotesLoaded",summary.foreignQuotesLoaded).put("message",summary.message).put("nextDecisionDeadline",summary.nextDecisionDeadline).put("candidates",a)
        val dropped=JSONArray();summary.droppedSincePrevious.forEach{dropped.put(it)};root.put("droppedSincePrevious",dropped)
        prefs.edit().putString("global_lead_summary",root.toString()).putLong("last_global_lead_scan_at",summary.generatedAt).apply()
    }

    fun loadGlobalLeadSummary():GlobalLeadSummary?{
        val raw=prefs.getString("global_lead_summary",null)?:return null
        return runCatching{
            val root=JSONObject(raw);val a=root.optJSONArray("candidates")?:JSONArray();val cs=buildList{for(i in 0 until a.length()){
                val j=a.optJSONObject(i)?:continue;val ra=j.optJSONArray("reasons")?:JSONArray();val reasons=buildList{for(k in 0 until ra.length()){val v=ra.optString(k);if(v.isNotBlank())add(v)}}
                add(GlobalLeadCandidate(j.optInt("rank"),runCatching{GlobalLeadDirection.valueOf(j.optString("direction","LONG"))}.getOrDefault(GlobalLeadDirection.LONG),j.optString("indianSymbol"),j.optString("indianCompany"),j.optString("foreignTicker"),j.optString("foreignCompany"),
                    j.optString("exchange"),j.optString("region"),runCatching{GlobalMappingType.valueOf(j.optString("mappingType"))}.getOrDefault(GlobalMappingType.LISTED_GROUP_PARENT),
                    j.optDouble("relationshipWeight"),j.optDouble("score"),runCatching{ConfidenceBand.valueOf(j.optString("confidence"))}.getOrDefault(ConfidenceBand.LOW),
                    runCatching{GlobalLeadAction.valueOf(j.optString("action"))}.getOrDefault(GlobalLeadAction.OBSERVE),j.optDouble("foreignGapPct"),j.optDouble("foreignDayPct"),
                    j.optDouble("foreignFromOpenPct"),j.optDouble("foreignExcessPct"),j.optDouble("foreignVolumeRatio"),j.optDouble("foreignCloseLocation"),j.optLong("foreignSignalAt"),
                    j.optDouble("indianPrice"),j.optDouble("indianOpen"),j.optDouble("indianFromOpenPct"),j.optDouble("indianDayPct"),j.optDouble("indianBuySellRatio"),
                    j.optDouble("freshnessPct"),j.optDouble("pressureConfirmationScore"),j.optDouble("expectedTargetPct"),reasons,j.optString("officialSource"),j.optLong("generatedAt")))
            }}
            val da=root.optJSONArray("droppedSincePrevious")?:JSONArray();val dropped=buildList{for(i in 0 until da.length()){val v=da.optString(i);if(v.isNotBlank())add(v)}}
            GlobalLeadSummary(root.optLong("generatedAt"),root.optString("mappingVersion"),root.optInt("mappingsScanned"),root.optInt("foreignQuotesLoaded"),cs,root.optString("message"),root.optString("nextDecisionDeadline","15:00 IST"),dropped)
        }.getOrNull()
    }

    private fun globalClosedToJson(r:GlobalLeadClosedRecord):JSONObject{
        val c=r.candidate;val reasons=JSONArray();c.reasons.forEach{reasons.put(it)}
        return JSONObject().put("closedAt",r.closedAt).put("closeReason",r.reason)
            .put("rank",c.rank).put("direction",c.direction.name).put("indianSymbol",c.indianSymbol).put("indianCompany",c.indianCompany)
            .put("foreignTicker",c.foreignTicker).put("foreignCompany",c.foreignCompany).put("exchange",c.exchange).put("region",c.region)
            .put("mappingType",c.mappingType.name).put("relationshipWeight",finite(c.relationshipWeight)).put("score",finite(c.score)).put("confidence",c.confidence.name)
            .put("action",c.action.name).put("foreignGapPct",finite(c.foreignGapPct)).put("foreignDayPct",finite(c.foreignDayPct)).put("foreignFromOpenPct",finite(c.foreignFromOpenPct))
            .put("foreignExcessPct",finite(c.foreignExcessPct)).put("foreignVolumeRatio",finite(c.foreignVolumeRatio)).put("foreignCloseLocation",finite(c.foreignCloseLocation))
            .put("foreignSignalAt",c.foreignSignalAt).put("indianPrice",finite(c.indianPrice)).put("indianOpen",finite(c.indianOpen)).put("indianFromOpenPct",finite(c.indianFromOpenPct))
            .put("indianDayPct",finite(c.indianDayPct)).put("indianBuySellRatio",finite(c.indianBuySellRatio)).put("freshnessPct",finite(c.freshnessPct))
            .put("pressureConfirmationScore",finite(c.pressureConfirmationScore)).put("expectedTargetPct",finite(c.expectedTargetPct)).put("officialSource",c.officialSource)
            .put("generatedAt",c.generatedAt).put("reasons",reasons)
    }
    private fun globalClosedFromJson(j:JSONObject):GlobalLeadClosedRecord?=runCatching{
        val ra=j.optJSONArray("reasons")?:JSONArray();val reasons=buildList{for(k in 0 until ra.length()){val v=ra.optString(k);if(v.isNotBlank())add(v)}}
        val c=GlobalLeadCandidate(j.optInt("rank"),runCatching{GlobalLeadDirection.valueOf(j.optString("direction","LONG"))}.getOrDefault(GlobalLeadDirection.LONG),j.optString("indianSymbol"),j.optString("indianCompany"),j.optString("foreignTicker"),j.optString("foreignCompany"),
            j.optString("exchange"),j.optString("region"),runCatching{GlobalMappingType.valueOf(j.optString("mappingType"))}.getOrDefault(GlobalMappingType.LISTED_GROUP_PARENT),
            j.optDouble("relationshipWeight"),j.optDouble("score"),runCatching{ConfidenceBand.valueOf(j.optString("confidence"))}.getOrDefault(ConfidenceBand.LOW),
            runCatching{GlobalLeadAction.valueOf(j.optString("action"))}.getOrDefault(GlobalLeadAction.OBSERVE),j.optDouble("foreignGapPct"),j.optDouble("foreignDayPct"),
            j.optDouble("foreignFromOpenPct"),j.optDouble("foreignExcessPct"),j.optDouble("foreignVolumeRatio"),j.optDouble("foreignCloseLocation"),j.optLong("foreignSignalAt"),
            j.optDouble("indianPrice"),j.optDouble("indianOpen"),j.optDouble("indianFromOpenPct"),j.optDouble("indianDayPct"),j.optDouble("indianBuySellRatio"),
            j.optDouble("freshnessPct"),j.optDouble("pressureConfirmationScore"),j.optDouble("expectedTargetPct"),reasons,j.optString("officialSource"),j.optLong("generatedAt"))
        GlobalLeadClosedRecord(c,j.optLong("closedAt"),j.optString("closeReason","Signal closed"))
    }.getOrNull()
    fun loadGlobalLeadClosed(limit:Int=250):List<GlobalLeadClosedRecord>{
        val a=runCatching{JSONArray(prefs.getString("global_lead_closed_ledger","[]")?:"[]")}.getOrElse{JSONArray()}
        return buildList{for(i in 0 until a.length()){globalClosedFromJson(a.optJSONObject(i)?:continue)?.let(::add)}}.sortedByDescending{it.closedAt}.take(limit)
    }
    fun appendGlobalLeadClosed(records:List<GlobalLeadClosedRecord>){
        if(records.isEmpty())return
        val merged=(loadGlobalLeadClosed(500)+records).distinctBy{"${it.candidate.direction}|${it.candidate.indianSymbol}|${it.candidate.generatedAt}"}.sortedByDescending{it.closedAt}.take(500)
        val a=JSONArray();merged.forEach{a.put(globalClosedToJson(it))};prefs.edit().putString("global_lead_closed_ledger",a.toString()).apply()
    }

    fun lastGlobalLeadScanAt():Long=prefs.getLong("last_global_lead_scan_at",0L)
    fun setLastGlobalLeadScanAt(v:Long){prefs.edit().putLong("last_global_lead_scan_at",v).apply()}
    fun lastGlobalMappingRefreshAt():Long=prefs.getLong("last_global_mapping_refresh_at",0L)
    fun setLastGlobalMappingRefreshAt(v:Long){prefs.edit().putLong("last_global_mapping_refresh_at",v).apply()}
    fun globalMappingVersion():String=prefs.getString("global_mapping_version","embedded").orEmpty()
    fun setGlobalMappingVersion(v:String){prefs.edit().putString("global_mapping_version",v).apply()}

    fun saveLastScan(summary:ScanSummary){prefs.edit().putString("last_scan_${summary.section.name}",scanToJson(summary).toString()).putLong("last_market_data_success_at",summary.completedAt).apply()}
    fun loadLastScan(section:ScannerSection):ScanSummary?{val raw=prefs.getString("last_scan_${section.name}",null)?:return null;return runCatching{scanFromJson(JSONObject(raw),section)}.getOrNull()}
    fun lastMarketDataSuccessAt():Long=prefs.getLong("last_market_data_success_at",0L)

    fun saveNewListings(items:List<ListedSecurity>){val arr=JSONArray();items.forEach{n->arr.put(JSONObject().put("symbol",n.symbol).put("companyName",n.companyName).put("series",n.series).put("listingDateIso",n.listingDateIso).put("isin",n.isin).put("daysListed",n.daysListed))};prefs.edit().putString("new_listings_cache",arr.toString()).apply()}
    fun loadNewListings():List<ListedSecurity>{val arr=runCatching{JSONArray(prefs.getString("new_listings_cache","[]")?:"[]")}.getOrElse{JSONArray()};return buildList{for(i in 0 until arr.length()){val j=arr.optJSONObject(i)?:continue;add(ListedSecurity(j.optString("symbol"),j.optString("companyName"),j.optString("series"),j.optString("listingDateIso"),j.optString("isin"),j.optLong("daysListed")))}}}

    fun saveListingFeedHealth(h:FeedHealth){prefs.edit().putString("listing_feed_health",JSONObject().put("state",h.state.name).put("itemCount",h.itemCount).put("lastAttemptAt",h.lastAttemptAt).put("lastSuccessAt",h.lastSuccessAt).put("message",h.message).toString()).apply()}
    fun listingFeedHealth():FeedHealth{val raw=prefs.getString("listing_feed_health",null)?:return FeedHealth();return runCatching{val j=JSONObject(raw);FeedHealth(runCatching{FeedHealthState.valueOf(j.optString("state"))}.getOrDefault(FeedHealthState.NEVER_LOADED),j.optInt("itemCount"),j.optLong("lastAttemptAt"),j.optLong("lastSuccessAt"),j.optString("message"))}.getOrDefault(FeedHealth())}

    fun saveThreePmPrep(dateKey:String,candidates:List<Candidate>,at:Long=System.currentTimeMillis()){
        val a=JSONArray();candidates.take(40).forEach{a.put(candidateToJson(it,false))}
        prefs.edit().putString("uc_three_pm_prep_v170_"+dateKey,a.toString()).putLong("uc_three_pm_prep_at_v170",at).putString("uc_three_pm_prep_date_v170",dateKey).apply()
    }
    fun threePmPrep(dateKey:String):List<Candidate>{
        val raw=prefs.getString("uc_three_pm_prep_v170_"+dateKey,null)?:return emptyList()
        return candidatesFromArray(runCatching{JSONArray(raw)}.getOrElse{JSONArray()},ScannerSection.UC_CONTINUATION,false)
    }
    fun lastThreePmPrepAt():Long=prefs.getLong("uc_three_pm_prep_at_v170",0L)
    fun threePmPrepDate():String=prefs.getString("uc_three_pm_prep_date_v170","").orEmpty()

    fun saveFreezeRecord(dateKey:String,section:ScannerSection,candidates:List<Candidate>,outcome:FreezeOutcome,sourceScanAt:Long,message:String,frozenAt:Long=System.currentTimeMillis()){
        val arr=JSONArray();candidates.forEach{arr.put(candidateToJson(it,true))};val meta=JSONObject().put("dateIso",dateKey).put("section",section.name).put("outcome",outcome.name).put("frozenAt",frozenAt).put("sourceScanAt",sourceScanAt).put("message",message)
        prefs.edit().putString("frozen_${section.name}_$dateKey",arr.toString()).putString("freeze_meta_${section.name}_$dateKey",meta.toString()).apply()
    }
    fun saveFrozenCandidates(dateKey:String,section:ScannerSection,payload:JSONArray){prefs.edit().putString("frozen_${section.name}_$dateKey",payload.toString()).apply()}
    fun getFrozenCandidates(dateKey:String,section:ScannerSection):JSONArray?{val raw=prefs.getString("frozen_${section.name}_$dateKey",null)?:return null;return runCatching{JSONArray(raw)}.getOrNull()}
    fun hasFreezeRecord(dateKey:String,section:ScannerSection):Boolean=prefs.contains("freeze_meta_${section.name}_$dateKey")||((getFrozenCandidates(dateKey,section)?.length()?:0)>0)
    fun freezeRecord(dateKey:String,section:ScannerSection):FreezeRecord{
        val candidates=candidatesFromArray(getFrozenCandidates(dateKey,section)?:JSONArray(),section,true);val raw=prefs.getString("freeze_meta_${section.name}_$dateKey",null)
        if(raw==null)return if(candidates.isNotEmpty())FreezeRecord(section,dateKey,true,FreezeOutcome.PICKS,0,0,candidates,"Legacy frozen picks") else FreezeRecord(section,dateKey,false)
        return runCatching{val j=JSONObject(raw);FreezeRecord(section,dateKey,true,runCatching{FreezeOutcome.valueOf(j.optString("outcome"))}.getOrDefault(if(candidates.isEmpty())FreezeOutcome.NO_SIGNAL else FreezeOutcome.PICKS),j.optLong("frozenAt"),j.optLong("sourceScanAt"),candidates,j.optString("message"))}.getOrElse{FreezeRecord(section,dateKey,candidates.isNotEmpty(),if(candidates.isNotEmpty())FreezeOutcome.PICKS else FreezeOutcome.NO_DATA,candidates=candidates)}
    }
    fun freezeHistory(section:ScannerSection,limit:Int=20):List<FreezeRecord>{val mp="freeze_meta_${section.name}_";val lp="frozen_${section.name}_";val dates=prefs.all.keys.asSequence().filter{it.startsWith(mp)||it.startsWith(lp)}.mapNotNull{key->val p=if(key.startsWith(mp))mp else lp;runCatching{LocalDate.parse(key.removePrefix(p))}.getOrNull()}.toSet().sortedDescending().take(limit);return dates.map{freezeRecord(it.toString(),section)}.filter{it.recorded}}
    fun pendingFrozenDates(section:ScannerSection):List<LocalDate>{val p="frozen_${section.name}_";return prefs.all.keys.asSequence().filter{it.startsWith(p)}.mapNotNull{runCatching{LocalDate.parse(it.removePrefix(p))}.getOrNull()}.sorted().toList()}

    fun evaluatedKey(dateKey:String,section:ScannerSection,modelVersion:String)="evaluated_${section.name}_${sanitize(modelVersion)}_$dateKey"
    fun isOutcomeEvaluated(dateKey:String,section:ScannerSection,modelVersion:String)=prefs.getBoolean(evaluatedKey(dateKey,section,modelVersion),false)
    fun markOutcomeEvaluated(dateKey:String,section:ScannerSection,modelVersion:String){prefs.edit().putBoolean(evaluatedKey(dateKey,section,modelVersion),true).apply()}

    fun updateLearning(section:ScannerSection,modelVersion:String,signalIds:List<String>,win:Boolean,targetMovePct:Double?=null,evaluatedAt:Long=System.currentTimeMillis()){
        val stats=loadSignalStats().toMutableMap();signalIds.distinct().forEach{id->val k=statKey(section,modelVersion,id);val cur=stats[k]?:(0 to 0);stats[k]=(cur.first+1) to (cur.second+if(win)1 else 0)};val obj=JSONObject();stats.forEach{(k,v)->obj.put(k,JSONObject().put("observations",v.first).put("wins",v.second))}
        val outcomes=outcomes();outcomes.put(JSONObject().put("section",section.name).put("modelVersion",modelVersion).put("win",win).put("targetMovePct",finiteOrNull(targetMovePct)).put("at",evaluatedAt));while(outcomes.length()>1500)outcomes.remove(0);prefs.edit().putString("signal_stats",obj.toString()).putString("outcomes",outcomes.toString()).apply()
    }
    fun adaptivePrecisionMap(section:ScannerSection,modelVersion:String):Map<String,Double> = loadSignalStats().mapNotNull{(k,pair)->val p=parseStatKey(k)?:return@mapNotNull null;if(p.first!=section||p.second!=modelVersion||pair.first<5)null else p.third to pair.second.toDouble()/pair.first*100}.toMap()
    fun signalMetrics():List<StrategyMetric> = loadSignalStats().mapNotNull{(k,pair)->val p=parseStatKey(k)?:return@mapNotNull null;StrategyMetric(p.first,p.third,p.third,pair.first,pair.second,if(pair.first==0)0.0 else pair.second.toDouble()/pair.first*100,p.second)}.sortedWith(compareByDescending<StrategyMetric>{it.observations>=5}.thenByDescending{it.precision})
    fun sectionAccuracy(section:ScannerSection,modelVersion:String):SectionAccuracy{val now=System.currentTimeMillis();val cutoff=now-24L*60*60*1000;val rs=buildList{val a=outcomes();for(i in 0 until a.length()){val j=a.optJSONObject(i)?:continue;if(j.optString("section")==section.name&&j.optString("modelVersion")==modelVersion)add(j)}};val hits=rs.count{it.optBoolean("win")};val recent=rs.filter{it.optLong("at")>=cutoff};val rh=recent.count{it.optBoolean("win")};return SectionAccuracy(section,rs.size,hits,if(rs.isEmpty())0.0 else hits*100.0/rs.size,recent.size,rh,if(recent.isEmpty())0.0 else rh*100.0/recent.size,modelVersion)}

    fun pruneMemory(retentionDays:Int){
        val safeDays=retentionDays.coerceAtLeast(30)
        val cutoff=System.currentTimeMillis()-safeDays.toLong()*24*60*60*1000
        val kept=JSONArray();val a=outcomes();for(i in 0 until a.length()){val j=a.optJSONObject(i)?:continue;if(j.optLong("at")>=cutoff)kept.put(j)}
        val closed=loadStrategyClosed(500).filter{it.closedAt==0L||it.closedAt>=cutoff}
        val closedJson=JSONArray();closed.forEach{closedJson.put(recommendationToJson(it))}
        val trade=loadTradeCalls(1500).filter{it.outcome==TradeCallOutcome.OPEN||it.closedAt>=cutoff||it.openedAt>=cutoff}
        val tradeJson=JSONArray();trade.forEach{tradeJson.put(tradeCallToJson(it))}
        val aut=loadAutopsies(800).filter{it.generatedAt>=cutoff||it.closedAt>=cutoff};val autJson=JSONArray();aut.forEach{autJson.put(autopsyToJson(it))}
        val e=prefs.edit().putString("outcomes",kept.toString()).putString("strategy_closed_ledger",closedJson.toString()).putString("trade_call_ledger_v118",tradeJson.toString()).putString("trade_autopsies_v120",autJson.toString())
        prefs.all.keys.filter{it.startsWith("frozen_")||it.startsWith("freeze_meta_")||it.startsWith("evaluated_")||it.startsWith("strategy_pending_")||it.startsWith("strategy_evaluated_")}.forEach{k->
            val d=runCatching{LocalDate.parse(k.takeLast(10))}.getOrNull()?:return@forEach
            if(d.atStartOfDay(ist).toInstant().toEpochMilli()<cutoff)e.remove(k)
        }
        e.apply()
    }
    fun scannerHeartbeatAt():Long=prefs.getLong("scanner_service_heartbeat_at_v170",0L)
    fun scannerHeartbeatStatus():String=prefs.getString("scanner_service_heartbeat_status_v170","").orEmpty()
    fun setScannerHeartbeat(at:Long,status:String){prefs.edit().putLong("scanner_service_heartbeat_at_v170",at).putString("scanner_service_heartbeat_status_v170",status.take(160)).apply()}

    fun lastPressureScanAt():Long=prefs.getLong("last_pressure_scan_at",0L)
    fun setLastPressureScanAt(v:Long){prefs.edit().putLong("last_pressure_scan_at",v).apply()}
    fun lastNearCloseAutoScanAt():Long=prefs.getLong("last_near_close_auto_scan_at",0L)
    fun setLastNearCloseAutoScanAt(v:Long){prefs.edit().putLong("last_near_close_auto_scan_at",v).apply()}
    fun lastLearningAt():Long=prefs.getLong("last_learning_at",0L)
    fun setLastLearningAt(v:Long){prefs.edit().putLong("last_learning_at",v).apply()}
    fun lastAutonomousLearningAt():Long=prefs.getLong("last_autonomous_learning_at",0L)
    fun setLastAutonomousLearningAt(v:Long){prefs.edit().putLong("last_autonomous_learning_at",v).apply()}
    fun lastAutoTuneDate():String=prefs.getString("last_auto_tune_date","").orEmpty()
    fun setLastAutoTuneDate(v:String){prefs.edit().putString("last_auto_tune_date",v).apply()}

    private fun globalLearningRoot():JSONObject=runCatching{JSONObject(prefs.getString("global_learning_stats","{}")?:"{}")}.getOrElse{JSONObject()}
    fun updateGlobalLearning(key:String,returnPct:Double,win:Boolean){
        if(key.isBlank()||!returnPct.isFinite())return
        val root=globalLearningRoot()
        val x=root.optJSONObject(key)?:JSONObject()
        val sum=finite(storedFinite(x,"sumReturn")+returnPct)
        x.put("observations",x.optInt("observations").coerceAtLeast(0)+1)
            .put("wins",x.optInt("wins").coerceAtLeast(0)+(if(win)1 else 0))
            .put("sumReturn",sum)
            .put("lastAt",System.currentTimeMillis())
        root.put(key,x)
        prefs.edit().putString("global_learning_stats",root.toString()).apply()
    }

    fun globalScoreAdjustment(key:String):Double{
        val x=globalLearningRoot().optJSONObject(key)?:return 0.0
        val n=x.optInt("observations")
        if(n<4)return 0.0
        val wins=x.optInt("wins").coerceIn(0,n)
        val winRate=wins.toDouble()/n
        val avg=finite(storedFinite(x,"sumReturn")/n)
        return (((winRate-0.5)*10.0)+(avg*1.5)).takeIf{it.isFinite()}?.coerceIn(-6.0,6.0)?:0.0
    }

    fun globalLearningSummary(limit:Int=12):List<String>{
        val root=globalLearningRoot();val rows=mutableListOf<String>();val keys=root.keys()
        while(keys.hasNext()){val k=keys.next();val x=root.optJSONObject(k)?:continue;val n=x.optInt("observations");if(n<=0)continue;val w=x.optInt("wins");val avg=x.optDouble("sumReturn")/n;rows+="$k • n=$n • win=${"%.1f".format(w*100.0/n)}% • avg=${"%.2f".format(avg)}% • adj=${"%+.1f".format(globalScoreAdjustment(k))}"}
        return rows.sortedByDescending{Regex("n=(\\d+)").find(it)?.groupValues?.getOrNull(1)?.toIntOrNull()?:0}.take(limit)
    }


    // ---------- v1.5 point-in-time evidence / events / Challenger / broker / audit ledgers ----------

    private fun evidenceToJson(x:PointInTimeEvidence)=JSONObject()
        .put("id",x.id).put("symbol",x.symbol).put("kind",x.kind.name).put("metric",x.metric).put("value",x.value)
        .put("source",x.source).put("sourceUrl",x.sourceUrl).put("observedAt",x.observedAt).put("effectiveAt",x.effectiveAt)
        .put("publishedAt",x.publishedAt).put("revisionId",x.revisionId).put("notes",x.notes)
    private fun evidenceFromJson(j:JSONObject)=PointInTimeEvidence(
        j.optString("id"),j.optString("symbol"),runCatching{EvidenceKind.valueOf(j.optString("kind"))}.getOrDefault(EvidenceKind.COMPANY_EVENT),
        j.optString("metric"),j.optString("value"),j.optString("source"),j.optString("sourceUrl"),j.optLong("observedAt"),
        j.optLong("effectiveAt"),j.optLong("publishedAt"),j.optString("revisionId"),j.optString("notes")
    )
    fun loadPointInTimeEvidence(limit:Int=5000):List<PointInTimeEvidence>{
        val a=runCatching{JSONArray(prefs.getString("pit_evidence_v150","[]")?:"[]")}.getOrElse{JSONArray()}
        return buildList{for(i in 0 until a.length())runCatching{add(evidenceFromJson(a.getJSONObject(i)))}}
            .sortedByDescending{it.observedAt}.take(limit)
    }
    fun appendPointInTimeEvidence(x:PointInTimeEvidence){
        val all=loadPointInTimeEvidence(5000).filterNot{it.id==x.id}.toMutableList();all+=x
        val a=JSONArray();all.sortedByDescending{it.observedAt}.take(5000).forEach{a.put(evidenceToJson(it))}
        prefs.edit().putString("pit_evidence_v150",a.toString()).apply()
    }
    fun pointInTimeEvidenceAsOf(symbol:String,asOf:Long):List<PointInTimeEvidence> =
        loadPointInTimeEvidence(5000).filter{it.symbol.equals(symbol,true)&&it.observedAt<=asOf&&it.effectiveAt<=asOf}

    private fun macroToJson(x:MacroEventRecord)=JSONObject().put("id",x.id).put("title",x.title).put("startAt",x.startAt).put("endAt",x.endAt)
        .put("risk",x.risk.name).put("source",x.source).put("sourceUrl",x.sourceUrl).put("symbol",x.symbol).put("observedAt",x.observedAt).put("prospective",x.prospective)
    private fun macroFromJson(j:JSONObject)=MacroEventRecord(j.optString("id"),j.optString("title"),j.optLong("startAt"),j.optLong("endAt"),
        runCatching{EventRiskLevel.valueOf(j.optString("risk"))}.getOrDefault(EventRiskLevel.MEDIUM),j.optString("source"),j.optString("sourceUrl"),
        j.optString("symbol"),j.optLong("observedAt"),j.optBoolean("prospective",true))
    fun loadMacroEvents(limit:Int=1000):List<MacroEventRecord>{
        val a=runCatching{JSONArray(prefs.getString("macro_events_v150","[]")?:"[]")}.getOrElse{JSONArray()}
        return buildList{for(i in 0 until a.length())runCatching{add(macroFromJson(a.getJSONObject(i)))}}
            .sortedBy{it.startAt}.take(limit)
    }
    fun mergeMacroEvents(items:List<MacroEventRecord>){
        val all=(loadMacroEvents(1000)+items).associateBy{it.id}.values.sortedBy{it.startAt}.takeLast(1000)
        val a=JSONArray();all.forEach{a.put(macroToJson(it))}
        prefs.edit().putString("macro_events_v150",a.toString()).apply()
    }

    private fun challengerToJson(x:ChallengerShadowRecord)=JSONObject().put("id",x.id).put("strategyId",x.strategyId).put("strategyName",x.strategyName)
        .put("symbol",x.symbol).put("direction",x.direction.name).put("score",finite(x.score)).put("entryPrice",finite(x.entryPrice))
        .put("openedAt",x.openedAt).put("resolveAt",x.resolveAt).put("scheduledSessionDate",x.scheduledSessionDate)
        .put("researchSignature",x.researchSignature).put("evidence",x.evidence).put("outcome",x.outcome.name).put("resolvedAt",x.resolvedAt)
        .put("horizonPrice",finite(x.horizonPrice)).put("returnPct",finite(x.returnPct)).put("note",x.note)
        .put("targetPct",finite(x.targetPct)).put("stopPct",finite(x.stopPct)).put("sessionBand",x.sessionBand).put("regime",x.regime)
        .put("componentStrategyIds",JSONArray().apply{x.componentStrategyIds.forEach{put(it)}}).put("modelVersion",x.modelVersion)
    private fun challengerFromJson(j:JSONObject)=ChallengerShadowRecord(j.optString("id"),j.optString("strategyId"),j.optString("strategyName"),j.optString("symbol"),
        runCatching{TradeDirection.valueOf(j.optString("direction"))}.getOrDefault(TradeDirection.LONG),j.optDouble("score"),j.optDouble("entryPrice"),
        j.optLong("openedAt"),j.optLong("resolveAt"),j.optString("scheduledSessionDate"),j.optString("researchSignature"),j.optString("evidence"),
        runCatching{ChallengerShadowOutcome.valueOf(j.optString("outcome"))}.getOrDefault(ChallengerShadowOutcome.PENDING),
        j.optLong("resolvedAt"),j.optDouble("horizonPrice"),j.optDouble("returnPct"),j.optString("note"),
        j.optDouble("targetPct"),j.optDouble("stopPct"),j.optString("sessionBand"),j.optString("regime"),
        buildList{val a=j.optJSONArray("componentStrategyIds")?:JSONArray();for(i in 0 until a.length()){val v=a.optString(i);if(v.isNotBlank())add(v)}},
        j.optString("modelVersion","LEGACY-V1"))
    fun loadChallengerShadows(limit:Int=4000):List<ChallengerShadowRecord>{
        val a=runCatching{JSONArray(prefs.getString("challenger_shadow_v150","[]")?:"[]")}.getOrElse{JSONArray()}
        return buildList{for(i in 0 until a.length())runCatching{add(challengerFromJson(a.getJSONObject(i)))}}
            .sortedByDescending{it.openedAt}.take(limit)
    }
    fun saveChallengerShadows(records:List<ChallengerShadowRecord>){
        val a=JSONArray();records.distinctBy{it.id}.sortedByDescending{it.openedAt}.take(4000).forEach{a.put(challengerToJson(it))}
        prefs.edit().putString("challenger_shadow_v150",a.toString()).apply()
    }
    fun appendChallengerShadow(x:ChallengerShadowRecord):Boolean{
        val all=loadChallengerShadows(4000).toMutableList()
        val dup=all.any{it.outcome==ChallengerShadowOutcome.PENDING&&it.strategyId==x.strategyId&&it.symbol==x.symbol&&it.direction==x.direction&&kotlin.math.abs(it.openedAt-x.openedAt)<30L*60_000L}
        if(dup)return false
        all+=x;saveChallengerShadows(all);return true
    }

    private fun fillToJson(x:BrokerFillRecord)=JSONObject().put("growwTradeId",x.growwTradeId).put("exchangeTradeId",x.exchangeTradeId).put("exchangeOrderId",x.exchangeOrderId)
        .put("quantity",x.quantity).put("price",finite(x.price)).put("tradeStatus",x.tradeStatus).put("tradeDateTime",x.tradeDateTime).put("remark",x.remark)
    private fun fillFromJson(j:JSONObject)=BrokerFillRecord(j.optString("growwTradeId"),j.optString("exchangeTradeId"),j.optString("exchangeOrderId"),j.optInt("quantity"),
        j.optDouble("price"),j.optString("tradeStatus"),j.optString("tradeDateTime"),j.optString("remark"))
    private fun brokerToJson(x:BrokerOrderRecord):JSONObject{
        val f=JSONArray();x.fills.forEach{f.put(fillToJson(it))}
        return JSONObject().put("growwOrderId",x.growwOrderId).put("referenceId",x.referenceId).put("symbol",x.symbol).put("side",x.side).put("product",x.product)
            .put("requestedQuantity",x.requestedQuantity).put("submittedAt",x.submittedAt).put("signalEntryPrice",finite(x.signalEntryPrice)).put("status",x.status)
            .put("remark",x.remark).put("filledQuantity",x.filledQuantity).put("remainingQuantity",x.remainingQuantity).put("averageFillPrice",finite(x.averageFillPrice))
            .put("lastReconciledAt",x.lastReconciledAt).put("fills",f).put("reconciliationError",x.reconciliationError)
    }
    private fun brokerFromJson(j:JSONObject):BrokerOrderRecord{
        val a=j.optJSONArray("fills")?:JSONArray();val fills=buildList{for(i in 0 until a.length())runCatching{add(fillFromJson(a.getJSONObject(i)))}}
        return BrokerOrderRecord(j.optString("growwOrderId"),j.optString("referenceId"),j.optString("symbol"),j.optString("side"),j.optString("product"),
            j.optInt("requestedQuantity"),j.optLong("submittedAt"),j.optDouble("signalEntryPrice"),j.optString("status","SUBMITTED"),j.optString("remark"),
            j.optInt("filledQuantity"),j.optInt("remainingQuantity"),j.optDouble("averageFillPrice"),j.optLong("lastReconciledAt"),fills,j.optString("reconciliationError"))
    }
    fun loadBrokerOrders(limit:Int=500):List<BrokerOrderRecord>{
        val a=runCatching{JSONArray(prefs.getString("broker_orders_v150","[]")?:"[]")}.getOrElse{JSONArray()}
        return buildList{for(i in 0 until a.length())runCatching{add(brokerFromJson(a.getJSONObject(i)))}}
            .sortedByDescending{it.submittedAt}.take(limit)
    }
    fun saveBrokerOrders(records:List<BrokerOrderRecord>){
        val a=JSONArray();records.distinctBy{it.growwOrderId}.sortedByDescending{it.submittedAt}.take(500).forEach{a.put(brokerToJson(it))}
        prefs.edit().putString("broker_orders_v150",a.toString()).apply()
    }
    fun upsertBrokerOrder(x:BrokerOrderRecord){
        val all=loadBrokerOrders(500).filterNot{it.growwOrderId==x.growwOrderId}.toMutableList();all+=x;saveBrokerOrders(all)
    }

    private fun decisionToJson(x:DecisionSnapshot)=JSONObject().put("id",x.id).put("hash",x.hash).put("symbol",x.symbol).put("direction",x.direction.name)
        .put("strategyId",x.strategyId).put("decision",x.decision).put("reason",x.reason).put("score",finite(x.score)).put("decisionAt",x.decisionAt)
        .put("calendarVersion",x.calendarVersion).put("handbookVersion",x.handbookVersion).put("researchSignature",x.researchSignature)
        .put("evidence",x.evidence).put("pointInTimeEvidenceCount",x.pointInTimeEvidenceCount).put("sectorIndustry",x.sectorIndustry).put("macroRisk",x.macroRisk)
    private fun decisionFromJson(j:JSONObject)=DecisionSnapshot(j.optString("id"),j.optString("hash"),j.optString("symbol"),
        runCatching{TradeDirection.valueOf(j.optString("direction"))}.getOrDefault(TradeDirection.LONG),j.optString("strategyId"),j.optString("decision"),
        j.optString("reason"),j.optDouble("score"),j.optLong("decisionAt"),j.optString("calendarVersion"),j.optString("handbookVersion"),j.optString("researchSignature"),
        j.optString("evidence"),j.optInt("pointInTimeEvidenceCount"),j.optString("sectorIndustry"),j.optString("macroRisk"))
    fun loadDecisionSnapshots(limit:Int=3000):List<DecisionSnapshot>{
        val a=runCatching{JSONArray(prefs.getString("decision_snapshots_v150","[]")?:"[]")}.getOrElse{JSONArray()}
        return buildList{for(i in 0 until a.length())runCatching{add(decisionFromJson(a.getJSONObject(i)))}}
            .sortedByDescending{it.decisionAt}.take(limit)
    }
    fun appendDecisionSnapshot(setup:StrategySetup,decision:String,reason:String,calendarVersion:String,handbookVersion:String,sectorIndustry:String="",macroRisk:String="NONE",at:Long=System.currentTimeMillis()):DecisionSnapshot{
        val evidenceCount=pointInTimeEvidenceAsOf(setup.symbol,at).size
        val raw=listOf(setup.symbol,setup.direction.name,setup.strategyId,decision,reason,setup.score.toString(),at.toString(),calendarVersion,handbookVersion,setup.researchSignature,setup.evidence,evidenceCount.toString(),sectorIndustry,macroRisk).joinToString("|")
        val hash=MessageDigest.getInstance("SHA-256").digest(raw.toByteArray(Charsets.UTF_8)).joinToString(""){"%02x".format(it)}
        val x=DecisionSnapshot("DEC-"+hash.take(20),hash,setup.symbol,setup.direction,setup.strategyId,decision,reason,setup.score,at,calendarVersion,handbookVersion,setup.researchSignature,setup.evidence,evidenceCount,sectorIndustry,macroRisk)
        val all=loadDecisionSnapshots(3000).filterNot{it.id==x.id}.toMutableList();all+=x
        val a=JSONArray();all.sortedByDescending{it.decisionAt}.take(3000).forEach{a.put(decisionToJson(it))}
        prefs.edit().putString("decision_snapshots_v150",a.toString()).apply()
        return x
    }

    fun sectorMapVersion():String=prefs.getString("sector_map_version_v150","").orEmpty()
    fun setSectorMapVersion(v:String){prefs.edit().putString("sector_map_version_v150",v).apply()}

    private fun scanToJson(s:ScanSummary):JSONObject{val a=JSONArray();s.candidates.forEach{a.put(candidateToJson(it,true))};return JSONObject().put("section",s.section.name).put("startedAt",s.startedAt).put("completedAt",s.completedAt).put("universeCount",s.universeCount).put("preliminaryCount",s.preliminaryCount).put("quotedCount",s.quotedCount).put("newListingsScanned",s.newListingsScanned).put("message",s.message).put("candidates",a)}
    private fun scanFromJson(j:JSONObject,fallback:ScannerSection):ScanSummary{val section=runCatching{ScannerSection.valueOf(j.optString("section"))}.getOrDefault(fallback);return ScanSummary(section,j.optLong("startedAt"),j.optLong("completedAt"),j.optInt("universeCount"),j.optInt("preliminaryCount"),j.optInt("quotedCount"),candidatesFromArray(j.optJSONArray("candidates")?:JSONArray(),section,false),j.optInt("newListingsScanned"),j.optString("message"))}
    private fun candidateToJson(c:Candidate,includeSignals:Boolean):JSONObject{
        val ids=JSONArray();if(includeSignals)c.signals.filter{it.passed}.forEach{ids.put(it.id)}
        val strategies=JSONArray();c.activeStrategies.forEach{strategies.put(it)}
        return JSONObject().put("symbol",c.symbol).put("companyName",c.companyName).put("kind",c.kind.name).put("section",c.section.name)
            .put("price",finite(c.price)).put("upperCircuit",finite(c.upperCircuit)).put("dayChangePercent",finite(c.dayChangePercent)).put("score",finite(c.score))
            .put("confidence",c.confidence.name).put("passedSignals",c.passedSignals).put("totalSignals",c.totalSignals)
            .put("buySellRatio",finite(c.buySellRatio)).put("volumeRatio",finite(c.volumeRatio)).put("consecutiveCircuitLikeDays",c.consecutiveCircuitLikeDays)
            .put("listingAgeDays",c.listingAgeDays?:-1).put("generatedAt",c.generatedAt).put("predictionPhase",c.predictionPhase?.name?:"")
            .put("modelVersion",c.modelVersion).put("predictionHorizonHours",c.predictionHorizonHours).put("targetMovePct",finiteOrNull(c.targetMovePct))
            .put("setupScore",finiteOrNull(c.setupScore)).put("accelerationScore",finiteOrNull(c.accelerationScore)).put("microstructureScore",finiteOrNull(c.microstructureScore))
            .put("riskPenalty",finiteOrNull(c.riskPenalty)).put("signalIds",ids).put("activeStrategies",strategies)
    }
    private fun candidatesFromArray(a:JSONArray,section:ScannerSection,frozen:Boolean):List<Candidate> = buildList{for(i in 0 until a.length()){val j=a.optJSONObject(i)?:continue;val ids=j.optJSONArray("signalIds")?:JSONArray();val signals=buildList{for(k in 0 until ids.length()){val id=ids.optString(k);if(id.isNotBlank())add(SignalResult(id,id,"Persisted",true,1.0,"Persisted passed signal"))}};val sa=j.optJSONArray("activeStrategies")?:JSONArray();val strategies=buildList{for(k in 0 until sa.length()){val v=sa.optString(k);if(v.isNotBlank())add(v)}};add(Candidate(j.optString("symbol"),j.optString("companyName"),runCatching{CandidateKind.valueOf(j.optString("kind"))}.getOrElse{if(j.optLong("listingAgeDays",-1) in 0..45)CandidateKind.POST_LISTING else CandidateKind.SEASONED},section,j.optDouble("price"),j.optDouble("upperCircuit"),j.optDouble("dayChangePercent"),j.optDouble("score"),runCatching{ConfidenceBand.valueOf(j.optString("confidence"))}.getOrDefault(ConfidenceBand.MEDIUM),j.optInt("passedSignals"),j.optInt("totalSignals"),j.optDouble("buySellRatio"),j.optDouble("volumeRatio"),j.optInt("consecutiveCircuitLikeDays"),signals,strategies,j.optLong("listingAgeDays",-1).takeIf{it>=0},j.optLong("generatedAt",System.currentTimeMillis()),frozen,runCatching{PredictionPhase.valueOf(j.optString("predictionPhase"))}.getOrNull(),j.optString("modelVersion"),j.optInt("predictionHorizonHours",24),j.optNullableDouble("targetMovePct"),j.optNullableDouble("setupScore"),j.optNullableDouble("accelerationScore"),j.optNullableDouble("microstructureScore"),j.optNullableDouble("riskPenalty")))}}
    private fun statKey(section:ScannerSection,modelVersion:String,id:String)="${section.name}|${sanitize(modelVersion)}|$id"
    private fun parseStatKey(k:String):Triple<ScannerSection,String,String>?{val p=k.split("|",limit=3);if(p.size!=3)return null;val s=runCatching{ScannerSection.valueOf(p[0])}.getOrNull()?:return null;return Triple(s,p[1],p[2])}
    private fun sanitize(v:String)=v.replace(Regex("[^A-Za-z0-9_.-]"),"_")
    private fun loadSignalStats():Map<String,Pair<Int,Int>>{val o=runCatching{JSONObject(prefs.getString("signal_stats","{}")?:"{}")}.getOrElse{JSONObject()};val out=linkedMapOf<String,Pair<Int,Int>>();val ks=o.keys();while(ks.hasNext()){val k=ks.next();val j=o.optJSONObject(k)?:continue;out[k]=j.optInt("observations") to j.optInt("wins")};return out}
    private fun outcomes():JSONArray=runCatching{JSONArray(prefs.getString("outcomes","[]")?:"[]")}.getOrElse{JSONArray()}
    private fun JSONObject.optNullableDouble(name:String):Double?{if(!has(name)||isNull(name))return null;val v=optDouble(name,Double.NaN);return v.takeIf{it.isFinite()}}
}
