package com.suhas.globaledgeai.data.local

import android.content.Context
import com.suhas.globaledgeai.domain.model.*
import org.json.JSONArray
import org.json.JSONObject

class MultifyTradingStore(context:Context){
    private val prefs=context.getSharedPreferences("global_edge_multify_trading_v164",Context.MODE_PRIVATE)
    private val tradesKey="shadow_trades"
    private val decisionsKey="decisions"

    fun loadTrades(limit:Int=2500):List<MultifyShadowTrade>{
        val raw=prefs.getString(tradesKey,"[]")?:"[]"
        return runCatching{
            val a=JSONArray(raw);buildList{
                for(i in 0 until a.length())tradeFromJson(a.optJSONObject(i)?:continue)?.let(::add)
            }
        }.getOrDefault(emptyList()).sortedByDescending{it.openedAt}.take(limit)
    }

    @Synchronized fun saveTrades(rows:List<MultifyShadowTrade>){
        val a=JSONArray();rows.distinctBy{it.id}.sortedByDescending{it.openedAt}.take(2500).forEach{a.put(tradeToJson(it))}
        prefs.edit().putString(tradesKey,a.toString()).apply()
    }

    @Synchronized fun upsertTrade(row:MultifyShadowTrade){
        val all=loadTrades(2500).toMutableList();val i=all.indexOfFirst{it.id==row.id}
        if(i>=0)all[i]=row else all.add(row);saveTrades(all)
    }

    fun loadDecisions(limit:Int=1000):List<MultifyDecision>{
        val raw=prefs.getString(decisionsKey,"[]")?:"[]"
        return runCatching{
            val a=JSONArray(raw);buildList{
                for(i in 0 until a.length())decisionFromJson(a.optJSONObject(i)?:continue)?.let(::add)
            }
        }.getOrDefault(emptyList()).sortedByDescending{it.generatedAt}.take(limit)
    }

    @Synchronized fun appendDecision(row:MultifyDecision){
        val all=loadDecisions(1000).toMutableList();all.removeAll{it.eventId==row.eventId&&it.generatedAt==row.generatedAt};all.add(row)
        val a=JSONArray();all.sortedByDescending{it.generatedAt}.take(1000).forEach{a.put(decisionToJson(it))}
        prefs.edit().putString(decisionsKey,a.toString()).apply()
    }

    fun latestDecision():MultifyDecision?=loadDecisions(1).firstOrNull()

    fun clearOpenTradesAsClosed(now:Long,reason:String){
        val rows=loadTrades(2500).map{if(it.status==MultifyShadowStatus.OPEN)it.copy(status=MultifyShadowStatus.CLOSED,closedAt=now,exitPrice=it.lastPrice,closeReason=reason) else it}
        saveTrades(rows)
    }

    private fun tradeToJson(x:MultifyShadowTrade)=JSONObject().apply{
        put("id",x.id);put("sourceEventId",x.sourceEventId);put("symbol",x.symbol);put("side",x.side.name);put("strategyTag",x.strategyTag);put("contextKey",x.contextKey);put("wave",x.wave)
        put("openedAt",x.openedAt);put("entryPrice",x.entryPrice);put("quantity",x.quantity);put("allocatedCapital",x.allocatedCapital);put("score",x.score);put("status",x.status.name)
        put("closedAt",x.closedAt);put("exitPrice",x.exitPrice);put("grossPnl",x.grossPnl);put("estimatedCosts",x.estimatedCosts);put("netPnl",x.netPnl);put("closeReason",x.closeReason)
        put("mfePct",x.mfePct);put("maePct",x.maePct);put("peakPrice",x.peakPrice);put("troughPrice",x.troughPrice);put("lastPrice",x.lastPrice);put("lastUpdatedAt",x.lastUpdatedAt)
        put("liveEntryReference",x.liveEntryReference);put("liveExitReference",x.liveExitReference);put("liveProtectionReference",x.liveProtectionReference);put("liveProtectionId",x.liveProtectionId);put("liveFilledQuantity",x.liveFilledQuantity);put("liveAverageEntryPrice",x.liveAverageEntryPrice);put("liveExecutionNote",x.liveExecutionNote)
    }

    private fun tradeFromJson(o:JSONObject):MultifyShadowTrade?=runCatching{
        val entry=o.optDouble("entryPrice",0.0)
        MultifyShadowTrade(
            id=o.optString("id"),sourceEventId=o.optString("sourceEventId"),symbol=o.optString("symbol"),
            side=runCatching{MultifyShadowSide.valueOf(o.optString("side"))}.getOrDefault(MultifyShadowSide.LONG),
            strategyTag=o.optString("strategyTag"),contextKey=o.optString("contextKey"),wave=o.optInt("wave",1),openedAt=o.optLong("openedAt"),entryPrice=entry,
            quantity=o.optInt("quantity"),allocatedCapital=o.optDouble("allocatedCapital"),score=o.optDouble("score"),
            status=runCatching{MultifyShadowStatus.valueOf(o.optString("status"))}.getOrDefault(MultifyShadowStatus.OPEN),
            closedAt=o.optLong("closedAt"),exitPrice=o.optDouble("exitPrice"),grossPnl=o.optDouble("grossPnl"),estimatedCosts=o.optDouble("estimatedCosts"),netPnl=o.optDouble("netPnl"),closeReason=o.optString("closeReason"),
            mfePct=o.optDouble("mfePct"),maePct=o.optDouble("maePct"),peakPrice=o.optDouble("peakPrice",entry),troughPrice=o.optDouble("troughPrice",entry),lastPrice=o.optDouble("lastPrice",entry),lastUpdatedAt=o.optLong("lastUpdatedAt"),
            liveEntryReference=o.optString("liveEntryReference"),liveExitReference=o.optString("liveExitReference"),liveProtectionReference=o.optString("liveProtectionReference"),liveProtectionId=o.optString("liveProtectionId"),liveFilledQuantity=o.optInt("liveFilledQuantity"),liveAverageEntryPrice=o.optDouble("liveAverageEntryPrice"),liveExecutionNote=o.optString("liveExecutionNote")
        )
    }.getOrNull()

    private fun decisionToJson(x:MultifyDecision)=JSONObject().apply{
        put("eventId",x.eventId);put("symbol",x.symbol);put("eventType",x.eventType.name);put("tier",x.tier.name);put("direction",x.direction?.name?:"");put("score",x.score);put("price",x.price)
        put("strategyTag",x.strategyTag);put("contextKey",x.contextKey);put("reason",x.reason);put("generatedAt",x.generatedAt)
    }

    private fun decisionFromJson(o:JSONObject):MultifyDecision?=runCatching{
        MultifyDecision(
            eventId=o.optString("eventId"),symbol=o.optString("symbol"),eventType=runCatching{MultifyEventType.valueOf(o.optString("eventType"))}.getOrDefault(MultifyEventType.UNKNOWN),
            tier=runCatching{MultifyDecisionTier.valueOf(o.optString("tier"))}.getOrDefault(MultifyDecisionTier.WATCH),
            direction=o.optString("direction").takeIf{it.isNotBlank()}?.let{runCatching{MultifyShadowSide.valueOf(it)}.getOrNull()},
            score=o.optDouble("score"),price=o.optDouble("price"),strategyTag=o.optString("strategyTag"),contextKey=o.optString("contextKey"),reason=o.optString("reason"),generatedAt=o.optLong("generatedAt")
        )
    }.getOrNull()
}
