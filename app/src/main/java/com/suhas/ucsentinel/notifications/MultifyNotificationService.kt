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
    val evaluation:String="PENDING",
    val listenerReceivedAt:Long=0L,
    val parsedAt:Long=0L,
    val quoteReceivedAt:Long=0L,
    val decisionCompletedAt:Long=0L,
    val orderSubmittedAt:Long=0L,
    val brokerAcknowledgedAt:Long=0L,
    val firstFillAt:Long=0L,
    val fullFillAt:Long=0L
)

object MultifyTrustPolicy {
    fun isResearchCandidatePackage(packageName:String):Boolean =
        packageName.isNotBlank() && packageName.lowercase(Locale.ROOT).contains("multify")
    fun canUseForLive(trustedPackage:String,packageName:String):Boolean =
        trustedPackage.isNotBlank() && trustedPackage==packageName
}

data class MultifyParsedNotification(
    val eventType:MultifyEventType,
    val direction:String,
    val symbol:String,
    val price:Double,
    val instrumentClass:MultifyInstrumentClass,
    val noise:Boolean,
    val raw:String
)

object MultifyNotificationParser {
    private val EXIT_RE=Regex("\\b(EXIT|CLOSE|CLOSED|BOOK\\s+PROFIT|BOOKED\\s+PROFIT|SQUARE\\s*OFF|TARGET\\s+HIT|STOP\\s*LOSS\\s+HIT|SL\\s+HIT)\\b",RegexOption.IGNORE_CASE)
    private val LONG_RE=Regex("\\b(BUY|LONG|ENTRY\\s+LONG|GO\\s+LONG|BOUGHT)\\b",RegexOption.IGNORE_CASE)
    private val SHORT_RE=Regex("\\b(SELL|SHORT|ENTRY\\s+SHORT|GO\\s+SHORT|SOLD)\\b",RegexOption.IGNORE_CASE)
    private val STATUS_PHRASES=listOf(
        "signal capture active","listening for paid multify","notification access","alerts enabled",
        "shadow + app-owned live monitors ready","monitoring notifications","service running","capture service"
    )
    private val BLOCKED=setOf(
        "BUY","SELL","LONG","SHORT","ENTRY","EXIT","CLOSE","CLOSED","NSE","BSE","CASH","EQUITY","INTRADAY","TARGET","STOP","LOSS",
        "PRICE","CALL","MULTIFY","CMP","ABOVE","BELOW","BOOK","PROFIT","SIGNAL","CAPTURE","ACTIVE","LISTENING","PAID","ALERT","ALERTS",
        "APP","OWNED","LIVE","MONITORS","READY","SERVICE","RUNNING","TRADER","TRADE"
    )

    fun parse(title:String,parts:List<String>,ongoing:Boolean=false):MultifyParsedNotification{
        val raw=(listOf(title)+parts).map{it.trim()}.filter{it.isNotBlank()}.distinct().joinToString(" • ")
        if(raw.isBlank())return MultifyParsedNotification(MultifyEventType.UNKNOWN,"","",0.0,MultifyInstrumentClass.UNKNOWN,true,raw)
        val upper=raw.uppercase(Locale.ROOT)
        val exit=EXIT_RE.containsMatchIn(raw)
        val longHit=LONG_RE.containsMatchIn(raw)
        val shortHit=SHORT_RE.containsMatchIn(raw)
        val eventType=when{
            exit->MultifyEventType.EXIT
            longHit&&!shortHit->MultifyEventType.ENTRY_LONG
            shortHit&&!longHit->MultifyEventType.ENTRY_SHORT
            else->MultifyEventType.UNKNOWN
        }
        val direction=when(eventType){
            MultifyEventType.ENTRY_LONG->"BUY"
            MultifyEventType.ENTRY_SHORT->"SELL"
            MultifyEventType.EXIT->"EXIT"
            else->""
        }
        val symbol=parseSymbol(upper,direction)
        val price=parsePrice(raw)
        val clazz=classifyInstrument(upper,symbol)
        val lower=raw.lowercase(Locale.ROOT)
        val explicitStatus=STATUS_PHRASES.any{it in lower}
        val actionable=eventType!=MultifyEventType.UNKNOWN&&symbol.isNotBlank()&&clazz==MultifyInstrumentClass.EQUITY
        val noise=(explicitStatus&&!actionable)||(ongoing&&!actionable)
        return MultifyParsedNotification(eventType,direction,symbol,price,clazz,noise,raw)
    }

    private fun parsePrice(raw:String):Double{
        val patterns=listOf(
            Regex("(?:@|\\bAT\\b|\\bPRICE\\b|\\bCMP\\b|\\bLTP\\b|\\bENTRY\\b)\\s*[:=\\-]?\\s*₹?\\s*(\\d+(?:\\.\\d+)?)",RegexOption.IGNORE_CASE),
            Regex("₹\\s*(\\d+(?:\\.\\d+)?)")
        )
        return patterns.asSequence().mapNotNull{it.find(raw)?.groupValues?.getOrNull(1)?.toDoubleOrNull()}.firstOrNull()?:0.0
    }

    private fun parseSymbol(upper:String,direction:String):String{
        val candidates=mutableListOf<String>()
        fun add(re:Regex){re.find(upper)?.groupValues?.getOrNull(1)?.let(candidates::add)}
        if(direction=="BUY")add(Regex("\\b(?:BUY|LONG|ENTRY\\s+LONG|GO\\s+LONG|BOUGHT)\\b\\s*[:=\\-]?\\s*(?:NSE[:\\s-]*)?([A-Z][A-Z0-9&.\\-]{1,19})"))
        if(direction=="SELL")add(Regex("\\b(?:SELL|SHORT|ENTRY\\s+SHORT|GO\\s+SHORT|SOLD)\\b\\s*[:=\\-]?\\s*(?:NSE[:\\s-]*)?([A-Z][A-Z0-9&.\\-]{1,19})"))
        add(Regex("\\bNSE[:\\s-]+([A-Z][A-Z0-9&.\\-]{1,19})\\b"))
        add(Regex("\\b(?:STOCK|SYMBOL|SCRIP|TICKER)\\s*[:=\\-]\\s*([A-Z][A-Z0-9&.\\-]{1,19})\\b"))
        add(Regex("[#$]([A-Z][A-Z0-9&.\\-]{1,19})\\b"))
        add(Regex("\\b([A-Z][A-Z0-9&.\\-]{1,19})\\b\\s*(?:@|\\bAT\\b|\\bCMP\\b|₹)"))
        if(direction=="EXIT")add(Regex("\\b(?:EXIT|CLOSE|CLOSED|SQUARE\\s*OFF)\\b\\s*[:=\\-]?\\s*(?:NSE[:\\s-]*)?([A-Z][A-Z0-9&.\\-]{1,19})\\b"))
        add(Regex("\\b([A-Z][A-Z0-9&.\\-]{1,19})\\b\\s*[:=\\-]?\\s*\\b(?:BUY|SELL|LONG|SHORT|EXIT|CLOSE)\\b"))
        return candidates.map{it.trim('.', '-', ' ')}.firstOrNull{
            it.length in 2..20&&it !in BLOCKED&&!it.endsWith("CE")&&!it.endsWith("PE")&&!it.all(Char::isDigit)
        }.orEmpty()
    }

    private fun classifyInstrument(upper:String,symbol:String):MultifyInstrumentClass{
        val derivative=Regex("\\b(OPTION|OPTIONS|FUT|FUTURE|FUTURES|CE|PE|NIFTY|BANKNIFTY|FINNIFTY|MIDCPNIFTY|SENSEX|CRUDE|GOLD|SILVER|COMMODITY)\\b").containsMatchIn(upper) ||
            Regex("\\b\\d{4,6}\\s*(CE|PE)\\b").containsMatchIn(upper)||Regex("\\b[A-Z]{2,15}\\d{2,6}(CE|PE)\\b").containsMatchIn(upper)
        if(derivative)return MultifyInstrumentClass.DERIVATIVE_OR_NON_EQUITY
        return if(symbol.isNotBlank())MultifyInstrumentClass.EQUITY else MultifyInstrumentClass.UNKNOWN
    }
}

object MultifyEventStore {
    private const val PREFS="global_edge_multify_events"
    private const val KEY="events_json"
    private const val TRUSTED_PACKAGE_KEY="trusted_multify_package_v168"
    private const val CANDIDATE_PACKAGE_KEY="candidate_multify_package"
    private const val MAX_EVENTS=1000

    @Synchronized fun trustedPackage(context:Context):String=context.getSharedPreferences(PREFS,Context.MODE_PRIVATE).getString(TRUSTED_PACKAGE_KEY,"").orEmpty()
    @Synchronized fun candidatePackage(context:Context):String=context.getSharedPreferences(PREFS,Context.MODE_PRIVATE).getString(CANDIDATE_PACKAGE_KEY,"").orEmpty()
    @Synchronized fun observeCandidatePackage(context:Context,packageName:String):Boolean{
        if(!MultifyTrustPolicy.isResearchCandidatePackage(packageName))return false
        val prefs=context.getSharedPreferences(PREFS,Context.MODE_PRIVATE)
        if(prefs.getString(CANDIDATE_PACKAGE_KEY,"").orEmpty()!=packageName)prefs.edit().putString(CANDIDATE_PACKAGE_KEY,packageName).apply()
        return true
    }
    @Synchronized fun trustCandidatePackage(context:Context):Boolean{
        val prefs=context.getSharedPreferences(PREFS,Context.MODE_PRIVATE);val candidate=prefs.getString(CANDIDATE_PACKAGE_KEY,"").orEmpty()
        if(!MultifyTrustPolicy.isResearchCandidatePackage(candidate))return false
        prefs.edit().putString(TRUSTED_PACKAGE_KEY,candidate).apply();return true
    }
    @Synchronized fun clearTrustedPackage(context:Context){context.getSharedPreferences(PREFS,Context.MODE_PRIVATE).edit().remove(TRUSTED_PACKAGE_KEY).apply()}
    @Synchronized fun sourceTrustedForLive(context:Context,packageName:String):Boolean=MultifyTrustPolicy.canUseForLive(trustedPackage(context),packageName)

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
                        postExitFall5mPct=o.optDouble("postExitFall5mPct"),postExitFall15mPct=o.optDouble("postExitFall15mPct"),evaluation=o.optString("evaluation","PENDING"),
                        listenerReceivedAt=o.optLong("listenerReceivedAt"),parsedAt=o.optLong("parsedAt"),quoteReceivedAt=o.optLong("quoteReceivedAt"),decisionCompletedAt=o.optLong("decisionCompletedAt"),
                        orderSubmittedAt=o.optLong("orderSubmittedAt"),brokerAcknowledgedAt=o.optLong("brokerAcknowledgedAt"),firstFillAt=o.optLong("firstFillAt"),fullFillAt=o.optLong("fullFillAt")
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
                put("listenerReceivedAt",e.listenerReceivedAt);put("parsedAt",e.parsedAt);put("quoteReceivedAt",e.quoteReceivedAt);put("decisionCompletedAt",e.decisionCompletedAt)
                put("orderSubmittedAt",e.orderSubmittedAt);put("brokerAcknowledgedAt",e.brokerAcknowledgedAt);put("firstFillAt",e.firstFillAt);put("fullFillAt",e.fullFillAt)
            })
        }
        context.getSharedPreferences(PREFS,Context.MODE_PRIVATE).edit().putString(KEY,a.toString()).apply()
    }


    @Synchronized fun markLatency(
        context:Context,id:String,listenerReceivedAt:Long?=null,parsedAt:Long?=null,quoteReceivedAt:Long?=null,decisionCompletedAt:Long?=null,
        orderSubmittedAt:Long?=null,brokerAcknowledgedAt:Long?=null,firstFillAt:Long?=null,fullFillAt:Long?=null
    ){
        val e=find(context,id)?:return
        update(context,e.copy(
            listenerReceivedAt=listenerReceivedAt?:e.listenerReceivedAt,parsedAt=parsedAt?:e.parsedAt,quoteReceivedAt=quoteReceivedAt?:e.quoteReceivedAt,decisionCompletedAt=decisionCompletedAt?:e.decisionCompletedAt,
            orderSubmittedAt=orderSubmittedAt?:e.orderSubmittedAt,brokerAcknowledgedAt=brokerAcknowledgedAt?:e.brokerAcknowledgedAt,firstFillAt=firstFillAt?:e.firstFillAt,fullFillAt=fullFillAt?:e.fullFillAt
        ))
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
        val listenerReceivedAt=System.currentTimeMillis()
        val n=sbn.notification?:return
        val title=n.extras.getCharSequence(Notification.EXTRA_TITLE)?.toString().orEmpty().trim()
        val parts=mutableListOf<String>()
        listOf(Notification.EXTRA_TEXT,Notification.EXTRA_BIG_TEXT,Notification.EXTRA_SUB_TEXT,Notification.EXTRA_SUMMARY_TEXT,Notification.EXTRA_INFO_TEXT).forEach{k->
            n.extras.getCharSequence(k)?.toString()?.takeIf{it.isNotBlank()}?.let(parts::add)
        }
        n.extras.getCharSequenceArray(Notification.EXTRA_TEXT_LINES)?.forEach{it?.toString()?.takeIf{v->v.isNotBlank()}?.let(parts::add)}
        n.tickerText?.toString()?.takeIf{it.isNotBlank()}?.let(parts::add)
        n.actions?.mapNotNull{it.title?.toString()}?.filter{it.isNotBlank()}?.let(parts::addAll)
        val pkg=sbn.packageName.orEmpty()
        if(!MultifyEventStore.observeCandidatePackage(applicationContext,pkg))return
        val parsed=MultifyNotificationParser.parse(title,parts,sbn.isOngoing||(n.flags and Notification.FLAG_ONGOING_EVENT)!=0)
        if(parsed.raw.isBlank())return
        if(parsed.noise){
            DiagnosticLog.log(applicationContext,"MULTIFY-PARSER","ignored non-signal/status notification • package="+pkg+" • "+parsed.raw.take(180))
            return
        }
        val text=parts.distinct().joinToString(" • ")
        val event=MultifyEvent(
            id=MultifyEventStore.idFor(pkg,sbn.postTime,title,text),capturedAt=sbn.postTime,packageName=pkg,title=title.take(160),text=text.take(900),
            direction=parsed.direction,symbol=parsed.symbol,signalPrice=parsed.price,eventType=parsed.eventType,instrumentClass=parsed.instrumentClass,
            listenerReceivedAt=listenerReceivedAt,parsedAt=System.currentTimeMillis()
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


}
