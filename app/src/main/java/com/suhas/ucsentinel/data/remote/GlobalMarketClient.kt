package com.suhas.globaledgeai.data.remote

import android.content.Context
import com.suhas.globaledgeai.domain.model.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.time.Instant
import java.time.ZoneOffset
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

class GlobalMarketClient(private val context: Context) {
    companion object {
        const val REMOTE_MAPPING_URL = "https://raw.githubusercontent.com/SuhasSrinivasa1/AI-Trader/main/android-stable/global-lead-mappings-v2.json"
    }

    data class MappingBundle(val version:String,val mappings:List<GlobalCounterpart>,val source:String)

    private data class CacheEntry(val at:Long,val snapshot:GlobalQuoteSnapshot)
    private data class SeriesCacheEntry(val at:Long,val bars:List<GlobalBar>)
    private val cache=ConcurrentHashMap<String,CacheEntry>()
    private val seriesCache=ConcurrentHashMap<String,SeriesCacheEntry>()
    private val cacheTtlMs=12L*60*1000
    private val client=OkHttpClient.Builder()
        .connectTimeout(12,TimeUnit.SECONDS)
        .readTimeout(18,TimeUnit.SECONDS)
        .build()

    suspend fun loadMappings(preferRemote:Boolean=true):MappingBundle=withContext(Dispatchers.IO){
        if(preferRemote){
            val remote=runCatching{fetchText(REMOTE_MAPPING_URL)}.getOrNull()
            if(!remote.isNullOrBlank()) runCatching{return@withContext parseMappings(remote,"weekly remote map")}
        }
        val raw=context.assets.open("global_lead_mappings.json").bufferedReader().use{it.readText()}
        parseMappings(raw,"embedded fallback")
    }

    suspend fun snapshot(ticker:String,force:Boolean=false):GlobalQuoteSnapshot?{
        val now=System.currentTimeMillis();val old=cache[ticker]
        if(!force&&old!=null&&now-old.at<=cacheTtlMs)return old.snapshot
        delay(90)
        val s=withContext(Dispatchers.IO){fetchSnapshot(ticker)}
        if(s!=null)cache[ticker]=CacheEntry(System.currentTimeMillis(),s)
        return s
    }

    suspend fun intradaySeries(ticker:String,force:Boolean=false):List<GlobalBar>{
        val now=System.currentTimeMillis();val old=seriesCache[ticker]
        if(!force&&old!=null&&now-old.at<=5L*60*1000)return old.bars
        delay(90)
        val bars=withContext(Dispatchers.IO){fetchIntradaySeries(ticker)}
        if(bars.isNotEmpty())seriesCache[ticker]=SeriesCacheEntry(System.currentTimeMillis(),bars)
        return bars
    }

    fun clearCache(){cache.clear();seriesCache.clear()}

    private fun fetchText(url:String):String{
        val req=Request.Builder().url(url).header("User-Agent","UC-Sentinel/1.4.4 Android").get().build()
        client.newCall(req).execute().use{r->if(!r.isSuccessful)throw IOException("Global mapping HTTP ${r.code}");return r.body?.string().orEmpty()}
    }


    private fun fetchIntradaySeries(ticker:String):List<GlobalBar>{
        val url=HttpUrl.Builder().scheme("https").host("query1.finance.yahoo.com")
            .addPathSegment("v8").addPathSegment("finance").addPathSegment("chart").addPathSegment(ticker)
            .addQueryParameter("range","5d").addQueryParameter("interval","5m")
            .addQueryParameter("includePrePost","false").build()
        val req=Request.Builder().url(url).header("User-Agent","Mozilla/5.0 (Android 16; Mobile) Global-Edge-AI-Trader/1.2").get().build()
        client.newCall(req).execute().use{r->
            if(!r.isSuccessful)return emptyList()
            val root=runCatching{JSONObject(r.body?.string().orEmpty())}.getOrNull()?:return emptyList()
            val result=root.optJSONObject("chart")?.optJSONArray("result")?.optJSONObject(0)?:return emptyList()
            val ts=result.optJSONArray("timestamp")?:return emptyList()
            val q=result.optJSONObject("indicators")?.optJSONArray("quote")?.optJSONObject(0)?:return emptyList()
            val opens=q.optJSONArray("open")?:JSONArray();val highs=q.optJSONArray("high")?:JSONArray();val lows=q.optJSONArray("low")?:JSONArray();val closes=q.optJSONArray("close")?:JSONArray();val vols=q.optJSONArray("volume")?:JSONArray()
            return buildList{
                for(i in 0 until ts.length()){
                    val close=closes.optNullableDouble(i)?:continue;val open=opens.optNullableDouble(i)?:close;val high=highs.optNullableDouble(i)?:maxOf(open,close);val low=lows.optNullableDouble(i)?:minOf(open,close)
                    if(close<=0||open<=0)continue
                    add(GlobalBar(ts.optLong(i),open,high,low,close,vols.optLong(i,0L)))
                }
            }
        }
    }

    private fun fetchSnapshot(ticker:String):GlobalQuoteSnapshot?{
        val url=HttpUrl.Builder().scheme("https").host("query1.finance.yahoo.com")
            .addPathSegment("v8").addPathSegment("finance").addPathSegment("chart").addPathSegment(ticker)
            .addQueryParameter("range","1mo").addQueryParameter("interval","1d")
            .addQueryParameter("includePrePost","false").addQueryParameter("events","div,splits")
            .build()
        val req=Request.Builder().url(url).header("User-Agent","Mozilla/5.0 (Android 16; Mobile) UC-Sentinel/1.4.4").get().build()
        client.newCall(req).execute().use{r->
            if(!r.isSuccessful)return null
            val root=runCatching{JSONObject(r.body?.string().orEmpty())}.getOrNull()?:return null
            val result=root.optJSONObject("chart")?.optJSONArray("result")?.optJSONObject(0)?:return null
            val meta=result.optJSONObject("meta")?:JSONObject()
            val ts=result.optJSONArray("timestamp")?:return null
            val quote=result.optJSONObject("indicators")?.optJSONArray("quote")?.optJSONObject(0)?:return null
            val opens=quote.optJSONArray("open")?:JSONArray();val highs=quote.optJSONArray("high")?:JSONArray();val lows=quote.optJSONArray("low")?:JSONArray();val closes=quote.optJSONArray("close")?:JSONArray();val vols=quote.optJSONArray("volume")?:JSONArray()
            val valid=mutableListOf<Int>()
            for(i in 0 until ts.length()){
                val c=closes.optNullableDouble(i)?:continue
                if(c>0)valid+=i
            }
            if(valid.isEmpty())return null
            val idx=valid.last();val prevIdx=valid.dropLast(1).lastOrNull()
            val last=closes.optNullableDouble(idx)?:meta.optDouble("regularMarketPrice",0.0)
            val previous=prevIdx?.let{closes.optNullableDouble(it)}?:meta.optDouble("chartPreviousClose",meta.optDouble("previousClose",0.0))
            val open=opens.optNullableDouble(idx)?:last;val high=highs.optNullableDouble(idx)?:maxOf(open,last);val low=lows.optNullableDouble(idx)?:minOf(open,last)
            val volume=vols.optLong(idx,0L)
            val priorVolumes=valid.dropLast(1).takeLast(20).mapNotNull{i->vols.optLong(i,0L).takeIf{it>0}?.toDouble()}
            val avg=if(priorVolumes.isEmpty())volume.toDouble().coerceAtLeast(1.0) else priorVolumes.average()
            val sessionTs=ts.optLong(idx,0L)*1000L
            val marketTs=meta.optLong("regularMarketTime",0L).takeIf{it>0}?.times(1000L)?:sessionTs
            val date=if(sessionTs>0)Instant.ofEpochMilli(sessionTs).atZone(ZoneOffset.UTC).toLocalDate().toString() else ""
            if(last<=0||previous<=0)return null
            return GlobalQuoteSnapshot(ticker,last,previous,open,high,low,volume,avg,marketTs,sessionTs,date)
        }
    }

    private fun parseMappings(raw:String,source:String):MappingBundle{
        val root=JSONObject(raw);val version=root.optString("version","GLM-UNKNOWN");val arr=root.optJSONArray("mappings")?:JSONArray()
        val out=buildList{
            for(i in 0 until arr.length()){
                val j=arr.optJSONObject(i)?:continue
                val symbol=j.optString("indianSymbol");val ticker=j.optString("foreignTicker")
                if(symbol.isBlank()||ticker.isBlank())continue
                add(GlobalCounterpart(
                    indianSymbol=symbol,indianCompany=j.optString("indianCompany",symbol),foreignTicker=ticker,
                    foreignCompany=j.optString("foreignCompany",ticker),exchange=j.optString("exchange"),region=j.optString("region"),
                    benchmarkTicker=j.optString("benchmarkTicker","SPY"),mappingType=runCatching{GlobalMappingType.valueOf(j.optString("mappingType"))}.getOrDefault(GlobalMappingType.LISTED_GROUP_PARENT),
                    relationshipWeight=j.optDouble("relationshipWeight",0.75).coerceIn(0.3,1.0),officialSource=j.optString("officialSource"),notes=j.optString("notes")
                ))
            }
        }
        return MappingBundle(version,out,source)
    }

    private fun JSONArray.optNullableDouble(i:Int):Double?{
        if(i<0||i>=length()||isNull(i))return null
        val v=optDouble(i,Double.NaN);return v.takeIf{!it.isNaN()}
    }
}
