package com.suhas.globaledgeai.data.remote

import android.content.Context
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * Prospective NIFTY 500 company→industry mapping.
 * The mapping is cached with the exact retrieval time; historical decisions keep their own
 * DecisionSnapshot and are never recomputed with a later industry file.
 */
class NiftyIndustryClient(private val context:Context){
    data class Bundle(val fetchedAt:Long,val source:String,val bySymbol:Map<String,String>)
    private val client=OkHttpClient.Builder().connectTimeout(15,TimeUnit.SECONDS).readTimeout(20,TimeUnit.SECONDS).build()
    private val url="https://www.niftyindices.com/IndexConstituent/ind_nifty500list.csv"
    private val cache=File(context.filesDir,"nifty500-industry-point-in-time.csv")
    private val meta=File(context.filesDir,"nifty500-industry-point-in-time.meta")
    private val ttl=7L*24*60*60*1000

    fun load(force:Boolean=false):Bundle{
        val now=System.currentTimeMillis()
        val cachedAt=meta.takeIf{it.exists()}?.readText()?.trim()?.toLongOrNull()?:0L
        if(!force&&cache.exists()&&cache.length()>100&&now-cachedAt<ttl){
            return Bundle(cachedAt,"NSE Indices cached",parse(cache.readText()))
        }
        val remote=runCatching{
            val request=Request.Builder().url(url).header("User-Agent","Global-Edge-AI-Trader/1.5").get().build()
            client.newCall(request).execute().use{r->
                require(r.isSuccessful){"NIFTY 500 industry HTTP ${r.code}"}
                r.body?.string().orEmpty().also{require(it.length>100){"NIFTY 500 industry file empty"}}
            }
        }.getOrNull()
        if(remote!=null){
            cache.writeText(remote);meta.writeText(now.toString())
            return Bundle(now,"NSE Indices NIFTY 500",parse(remote))
        }
        if(cache.exists()&&cache.length()>100)return Bundle(cachedAt,"NSE Indices cached",parse(cache.readText()))
        return Bundle(0L,"Unavailable",emptyMap())
    }

    private fun parse(raw:String):Map<String,String>{
        val lines=raw.lineSequence().filter{it.isNotBlank()}.toList()
        if(lines.isEmpty())return emptyMap()
        val header=csv(lines.first()).map{it.trim().lowercase()}
        val symbolIndex=header.indexOfFirst{it=="symbol"}
        val industryIndex=header.indexOfFirst{it=="industry"}
        if(symbolIndex<0||industryIndex<0)return emptyMap()
        return buildMap{
            lines.drop(1).forEach{line->
                val row=csv(line)
                if(row.size>maxOf(symbolIndex,industryIndex)){
                    val symbol=row[symbolIndex].trim().uppercase()
                    val industry=row[industryIndex].trim()
                    if(symbol.isNotBlank()&&industry.isNotBlank())put(symbol,industry)
                }
            }
        }
    }

    private fun csv(line:String):List<String>{
        val out=mutableListOf<String>();val b=StringBuilder();var quoted=false;var i=0
        while(i<line.length){
            val ch=line[i]
            when{
                ch=='"'&&quoted&&i+1<line.length&&line[i+1]=='"'->{b.append('"');i++}
                ch=='"'->quoted=!quoted
                ch==','&&!quoted->{out+=b.toString();b.setLength(0)}
                else->b.append(ch)
            }
            i++
        }
        out+=b.toString()
        return out
    }
}
