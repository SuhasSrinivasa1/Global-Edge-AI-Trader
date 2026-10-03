package com.suhas.globaledgeai.data.remote

import android.content.Context
import com.suhas.globaledgeai.domain.model.TradingStrategyDefinition
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.util.concurrent.TimeUnit

class StrategyCatalogClient(private val context:Context){
    data class Bundle(val version:String,val strategies:List<TradingStrategyDefinition>)
    private val http=OkHttpClient.Builder().connectTimeout(15,TimeUnit.SECONDS).readTimeout(20,TimeUnit.SECONDS).build()
    private val remoteUrl="https://raw.githubusercontent.com/SuhasSrinivasa1/AI-Trader/main/android-stable/strategy-catalog-v1.json"
    fun embedded():Bundle=parse(context.assets.open("trading_strategy_catalog.json").bufferedReader().use{it.readText()})
    fun remote():Bundle{
        val req=Request.Builder().url(remoteUrl).header("User-Agent","Global-Edge-AI-Trader/1.0").build()
        http.newCall(req).execute().use{r->require(r.isSuccessful){"Strategy catalogue HTTP ${r.code}"};return parse(r.body?.string().orEmpty())}
    }
    private fun parse(raw:String):Bundle{
        val j=JSONObject(raw);val a=j.optJSONArray("strategies")?:org.json.JSONArray()
        val list=buildList{for(i in 0 until a.length()){val x=a.optJSONObject(i)?:continue;add(TradingStrategyDefinition(x.optString("id"),x.optString("name"),x.optString("kind"),x.optString("family"),x.optString("description"),x.optString("source"),x.optInt("priority")))}}
        return Bundle(j.optString("version","UNKNOWN"),list.sortedByDescending{it.priority})
    }
}
