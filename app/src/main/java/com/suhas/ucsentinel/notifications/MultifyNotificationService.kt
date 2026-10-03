package com.suhas.globaledgeai.notifications

import android.app.Notification
import android.content.Context
import android.provider.Settings
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.workDataOf
import com.suhas.globaledgeai.diagnostics.DiagnosticLog
import com.suhas.globaledgeai.GlobalEdgeApplication
import kotlinx.coroutines.*
import com.suhas.globaledgeai.domain.model.MultifyEventType
import com.suhas.globaledgeai.domain.model.MultifyInstrumentClass
import com.suhas.globaledgeai.worker.MultifySignalWorker
import org.json.JSONArray
import org.json.JSONObject
import java.security.MessageDigest
import java.util.Locale

data class MultifyEvent(
    val id:String,
    val capturedAt:Long,
    val packageName:String,
    val title:String,
    val text:String,
    val direction:String,
    val symbol:String,
    val signalPrice:Double,
    val eventType:MultifyEventType=MultifyEventType.UNKNOWN,
    val instrumentClass:MultifyInstrumentClass=MultifyInstrumentClass.UNKNOWN,
    val processedAt:Long=0L,
    val decisionTier:String="",
    val decisionDirection:String="",
    val decisionScore:Double=0.0,
    val decisionPrice:Double=0.0,
    val decisionStrategy:String="",
    val decisionReason:String="",
    val evaluatedAt:Long=0L,
    val return1mPct:Double=0.0,
    val return3mPct:Double=0.0,
    val return5mPct:Double=0.0,
    val return15mPct:Double=0.0,
    val mfePct:Double=0.0,
    val maePct:Double=0.0,
    val postExitFall5mPct:Double=0.0,
    val postExitFall15mPct:Double=0.0,
    val evaluation:String="PENDING"
)

object MultifyEventStore {
    private const val PREFS="global_edge_multify_events"
    private const val KEY="events_json"
    private const val TRUSTED_PACKAGE_KEY="trusted_multify_package"
    private const val MAX_EVENTS=1000

    @Synchronized fun trustedPackage(context:Context):String=context.getSharedPreferences(PREFS,Context.MODE_PRIVATE).getString(TRUSTED_PACKAGE_KEY,"").orEmpty()
    @Synchronized fun trustPackageIfUnset(context:Context,packageName:String):Boolean{
        if(!packageName.lowercase(Locale.ROOT).contains("multify"))return false
        val prefs=context.getSharedPreferences(PREFS,Context.MODE_PRIVATE);val current=prefs.getString(TRUSTED_PACKAGE_KEY,"").orEmpty()
        if(current.isBlank()){prefs.edit().putString(TRUSTED_PACKAGE_KEY,packageName).apply();return true}
        return current==packageName
    }

    fun listenerEnabled(context:Context):Boolean {
        val enabled=Settings.Secure.getString(context.contentResolver,"enabled_notification_listeners").orEmpty()
        return enabled.split(':').any{it.startsWith(context.packageName+"/") && it.contains("MultifyNotificationService")}
    }

    @Synchronized fun recent(context:Context,limit:Int=400):List<MultifyEvent> = load(context).sortedByDescending{it.capturedAt}.take(limit)
    @Synchronized fun find(context:Context,id:String):MultifyEvent?=load(context).firstOrNull{it.id==id}

    @Synchronized fun capture(context:Context,event:MultifyEvent):Boolean {
        val rows=load(context).toMutableList()
        if(rows.any{it.id==event.id})return false
        rows.add(event)
        save(context,rows.sortedByDescending{it.capturedAt}.take(MAX_EVENTS))
        return true
    }

    @Synchronized fun update(context:Context,event:MultifyEvent){
        val rows=load(context).toMutableList();val i=rows.indexOfFirst{it.id==event.id}
        if(i>=0){rows[i]=event;save(context,rows)}
    }

    private fun load(context:Context):List<MultifyEvent>{
        val raw=context.getSharedPreferences(PREFS,Context.MODE_PRIVATE).getString(KEY,"[]")?:"[]"
        return runCatching{
            val a=JSONArray(raw);buildList{
                for(i in 0 until a.length()){
                    val o=a.optJSONObject(i)?:continue
                    add(MultifyEvent(
                        id=o.optString("id"),capturedAt=o.optLong("capturedAt"),packageName=o.optString("packageName"),title=o.optString("title"),text=o.optString("text"),
                        direction=o.optString("direction"),symbol=o.optString("symbol"),signalPrice=o.optDouble("signalPrice",0.0),
                        eventType=runCatching{MultifyEventType.valueOf(o.optString("eventType"))}.getOrDefault(inferLegacyType(o.optString("direction"),o.optString("title")+" "+o.optString("text"))),
                        instrumentClass=runCatching{MultifyInstrumentClass.valueOf(o.optString("instrumentClass"))}.getOrDefault(MultifyInstrumentClass.UNKNOWN),
                        processedAt=o.optLong("processedAt"),decisionTier=o.optString("decisionTier"),decisionDirection=o.optString("decisionDirection"),decisionScore=o.optDouble("decisionScore"),decisionPrice=o.optDouble("decisionPrice"),decisionStrategy=o.optString("decisionStrategy"),decisionReason=o.optString("decisionReason"),
                        evaluatedAt=o.optLong("evaluatedAt"),return1mPct=o.optDouble("return1mPct"),return3mPct=o.optDouble("return3mPct"),return5mPct=o.optDouble("return5mPct"),return15mPct=o.optDouble("return15mPct",0.0),mfePct=o.optDouble("mfePct",0.0),maePct=o.optDouble("maePct",0.0),
                        postExitFall5mPct=o.optDouble("postExitFall5mPct"),postExitFall15mPct=o.optDouble("postExitFall15mPct"),evaluation=o.optString("evaluation","PENDING")
                    ))
                }
            }
        }.getOrDefault(emptyList())
    }

    private fun save(context:Context,rows:List<MultifyEvent>){
        val a=JSONArray();rows.take(MAX_EVENTS).forEach{e->
            a.put(JSONObject().apply{
                put("id",e.id);put("capturedAt",e.capturedAt);put("packageName",e.packageName);put("title",e.title);put("text",e.text);put("direction",e.direction);put("symbol",e.symbol);put("signalPrice",e.signalPrice)
                put("eventType",e.eventType.name);put("instrumentClass",e.instrumentClass.name);put("processedAt",e.processedAt);put("decisionTier",e.decisionTier);put("decisionDirection",e.decisionDirection);put("decisionScore",e.decisionScore);put("decisionPrice",e.decisionPrice);put("decisionStrategy",e.decisionStrategy);put("decisionReason",e.decisionReason)
                put("evaluatedAt",e.evaluatedAt);put("return1mPct",e.return1mPct);put("return3mPct",e.return3mPct);put("return5mPct",e.return5mPct);put("return15mPct",e.return15mPct);put("mfePct",e.mfePct);put("maePct",e.maePct);put("postExitFall5mPct",e.postExitFall5mPct);put("postExitFall15mPct",e.postExitFall15mPct);put("evaluation",e.evaluation)
            })
        }
        context.getSharedPreferences(PREFS,Context.MODE_PRIVATE).edit().putString(KEY,a.toString()).apply()
    }

    fun idFor(packageName:String,postedAt:Long,title:String,text:String):String{
        val bytes=MessageDigest.getInstance("SHA-256").digest("$packageName|$postedAt|$title|$text".toByteArray())
        return bytes.take(10).joinToString(""){"%02x".format(it)}
    }

    private fun inferLegacyType(direction:String,raw:String):MultifyEventType{
        if(EXIT_RE.containsMatchIn(raw))return MultifyEventType.EXIT
        return when(direction.uppercase(Locale.ROOT)){"BUY"->MultifyEventType.ENTRY_LONG;"SELL"->MultifyEventType.ENTRY_SHORT;else->MultifyEventType.UNKNOWN}
    }

    private val EXIT_RE=Regex("\\b(EXIT|CLOSE|CLOSED|BOOK\\s+PROFIT|BOOKED\\s+PROFIT|SQUARE\\s*OFF|TARGET\\s+HIT|STOP\\s*LOSS\\s+HIT|SL\\s+HIT)\\b",RegexOption.IGNORE_CASE)
}

class MultifyNotificationService:NotificationListenerService(){
    private val immediateScope=CoroutineScope(SupervisorJob()+Dispatchers.IO)

    override fun onDestroy(){immediateScope.cancel();super.onDestroy()}

    override fun onNotificationPosted(sbn:StatusBarNotification){
        val n=sbn.notification?:return
        val title=n.extras.getCharSequence(Notification.EXTRA_TITLE)?.toString().orEmpty().trim()
        val text=listOf(
            n.extras.getCharSequence(Notification.EXTRA_TEXT)?.toString().orEmpty(),
            n.extras.getCharSequence(Notification.EXTRA_BIG_TEXT)?.toString().orEmpty(),
            n.extras.getCharSequence(Notification.EXTRA_SUB_TEXT)?.toString().orEmpty()
        ).firstOrNull{it.isNotBlank()}.orEmpty().trim()
        val pkg=sbn.packageName.orEmpty()
        val combined="$title $text"
        // Trust the exact Multify package after the first package-namespace match.
        // Text that merely says "Multify" is never accepted as a trading trigger.
        if(!MultifyEventStore.trustPackageIfUnset(applicationContext,pkg))return
        if(pkg!=MultifyEventStore.trustedPackage(applicationContext))return
        if(combined.isBlank())return

        val upper=combined.uppercase(Locale.ROOT)
        val exit=Regex("\\b(EXIT|CLOSE|CLOSED|BOOK\\s+PROFIT|BOOKED\\s+PROFIT|SQUARE\\s*OFF|TARGET\\s+HIT|STOP\\s*LOSS\\s+HIT|SL\\s+HIT)\\b",RegexOption.IGNORE_CASE).containsMatchIn(combined)
        val direction=Regex("\\b(BUY|SELL)\\b",RegexOption.IGNORE_CASE).find(combined)?.groupValues?.getOrNull(1)?.uppercase(Locale.ROOT).orEmpty()
        val eventType=when{
            exit->MultifyEventType.EXIT
            direction=="BUY"->MultifyEventType.ENTRY_LONG
            direction=="SELL"->MultifyEventType.ENTRY_SHORT
            else->MultifyEventType.UNKNOWN
        }
        val symbol=parseSymbol(combined,direction)
        val instrumentClass=classifyInstrument(upper,symbol)
        val price=Regex("(?:@|\\bAT\\b|\\bPRICE\\b|\\bCMP\\b)\\s*[:=-]?\\s*₹?\\s*(\\d+(?:\\.\\d+)?)",RegexOption.IGNORE_CASE)
            .find(combined)?.groupValues?.getOrNull(1)?.toDoubleOrNull()?:0.0
        val event=MultifyEvent(
            id=MultifyEventStore.idFor(pkg,sbn.postTime,title,text),capturedAt=sbn.postTime,packageName=pkg,title=title.take(160),text=text.take(900),
            direction=if(eventType==MultifyEventType.EXIT)"EXIT" else direction,symbol=symbol,signalPrice=price,eventType=eventType,instrumentClass=instrumentClass
        )
        if(MultifyEventStore.capture(applicationContext,event)){
            DiagnosticLog.log(applicationContext,"MULTIFY","captured ${event.eventType} ${event.symbol.ifBlank{"unparsed"}} • class=${event.instrumentClass} • package=$pkg")
            if(event.instrumentClass==MultifyInstrumentClass.EQUITY && event.symbol.isNotBlank() && event.eventType!=MultifyEventType.UNKNOWN){
                // Immediate in-process fast path; WorkManager remains the durable fallback if Android kills this service.
                immediateScope.launch{
                    runCatching{(applicationContext as GlobalEdgeApplication).repository.processMultifyEvent(event.id)}
                        .onFailure{DiagnosticLog.log(applicationContext,"MULTIFY-FAST","direct notification analysis failed • fallback queued",it)}
                }
                val work=OneTimeWorkRequestBuilder<MultifySignalWorker>()
                    .setInputData(workDataOf("event_id" to event.id))
                    .setInitialDelay(10,java.util.concurrent.TimeUnit.SECONDS)
                    .setBackoffCriteria(androidx.work.BackoffPolicy.LINEAR,10,java.util.concurrent.TimeUnit.SECONDS)
                    .build()
                WorkManager.getInstance(applicationContext).enqueueUniqueWork("multify-event-${event.id}",ExistingWorkPolicy.KEEP,work)
            }
        }
    }

    private fun classifyInstrument(upper:String,symbol:String):MultifyInstrumentClass{
        val derivative=Regex("\\b(OPTION|OPTIONS|FUT|FUTURE|FUTURES|CE|PE|NIFTY|BANKNIFTY|FINNIFTY|MIDCPNIFTY|SENSEX|CRUDE|GOLD|SILVER|COMMODITY)\\b").containsMatchIn(upper) ||
            Regex("\\b\\d{4,6}\\s*(CE|PE)\\b").containsMatchIn(upper) || Regex("\\b[A-Z]{2,15}\\d{2,6}(CE|PE)\\b").containsMatchIn(upper)
        if(derivative)return MultifyInstrumentClass.DERIVATIVE_OR_NON_EQUITY
        if(symbol.isNotBlank())return MultifyInstrumentClass.EQUITY
        return MultifyInstrumentClass.UNKNOWN
    }

    private fun parseSymbol(raw:String,direction:String):String{
        val u=raw.uppercase(Locale.ROOT)
        val candidates=mutableListOf<String>()
        if(direction.isNotBlank()){
            Regex("\\b$direction\\b\\s*[:=-]?\\s*(?:NSE[:\\s-]*)?([A-Z][A-Z0-9&.-]{1,19})").find(u)?.groupValues?.getOrNull(1)?.let(candidates::add)
        }
        Regex("\\bNSE[:\\s-]+([A-Z][A-Z0-9&.-]{1,19})\\b").find(u)?.groupValues?.getOrNull(1)?.let(candidates::add)
        Regex("\\b(?:STOCK|SYMBOL|SCRIP)\\s*[:=-]\\s*([A-Z][A-Z0-9&.-]{1,19})\\b").find(u)?.groupValues?.getOrNull(1)?.let(candidates::add)
        Regex("\\b(?:EXIT|CLOSE|CLOSED|SQUARE\\s*OFF)\\b\\s*[:=-]?\\s*(?:NSE[:\\s-]*)?([A-Z][A-Z0-9&.-]{1,19})\\b").find(u)?.groupValues?.getOrNull(1)?.let(candidates::add)
        Regex("\\b([A-Z][A-Z0-9&.-]{1,19})\\b\\s*[:=-]?\\s*\\b(?:EXIT|CLOSE|CLOSED|SQUARE\\s*OFF)\\b").find(u)?.groupValues?.getOrNull(1)?.let(candidates::add)
        val blocked=setOf("BUY","SELL","EXIT","CLOSE","CLOSED","NSE","CASH","EQUITY","INTRADAY","TARGET","STOP","LOSS","PRICE","CALL","MULTIFY","CMP","ABOVE","BELOW","BOOK","PROFIT")
        return candidates.firstOrNull{it !in blocked && !it.endsWith("CE") && !it.endsWith("PE")}.orEmpty()
    }
}
