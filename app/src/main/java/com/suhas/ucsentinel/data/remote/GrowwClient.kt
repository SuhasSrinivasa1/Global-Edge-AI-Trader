package com.suhas.globaledgeai.data.remote

import com.suhas.globaledgeai.domain.model.*
import com.suhas.globaledgeai.domain.model.Credentials as AppCredentials
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import okhttp3.*
import okhttp3.logging.HttpLoggingInterceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.security.MessageDigest
import java.time.LocalTime
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.concurrent.TimeUnit
import java.util.concurrent.ConcurrentHashMap
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import kotlin.math.pow
import kotlin.math.min

class GrowwClient {

    private fun finiteNumber(value:Double,fallback:Double=0.0):Double =
        if(value.isFinite()) value else fallback

    private fun jsonDouble(obj:JSONObject,name:String,fallback:Double=0.0):Double =
        finiteNumber(obj.optDouble(name,fallback),fallback)

    companion object {
        const val API_BASE = "https://api.groww.in"
        const val INSTRUMENT_URL = "https://growwapi-assets.groww.in/instruments/instrument.csv"

        internal fun parseHistoricalEpochSeconds(raw:Any?):Long{
            fun normalize(v:Long):Long=if(v>10_000_000_000L)v/1000L else v
            return when(raw){
                is Number->normalize(raw.toLong())
                is String->{
                    val s=raw.trim()
                    s.toLongOrNull()?.let(::normalize)
                        ?:runCatching{LocalDateTime.parse(s,DateTimeFormatter.ISO_LOCAL_DATE_TIME).atZone(ZoneId.of("Asia/Kolkata")).toEpochSecond()}
                            .recoverCatching{LocalDateTime.parse(s,DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")).atZone(ZoneId.of("Asia/Kolkata")).toEpochSecond()}
                            .getOrDefault(0L)
                }
                else->0L
            }
        }
    }

    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(20, TimeUnit.SECONDS)
        .addInterceptor(
            HttpLoggingInterceptor().apply {
                level = HttpLoggingInterceptor.Level.BASIC
            }
        )
        .build()

    // Shared Live Data budget for Quote/LTP/OHLC. 210 ms ~= 4.8/sec ~= 285/minute.
    // GrowwApiHealth retains automatic cooldown after a real 429, so healthy sessions are not over-throttled.
    // Full-universe OHLC uses batches of 50; ~4,000 symbols therefore need ~80 calls.
    private val liveLimiter = ApiRateLimiter(210L)
    private val historicalLimiter = ApiRateLimiter(300L)
    private val orderLimiter = ApiRateLimiter(300L)
    private val nonTradingLimiter = ApiRateLimiter(150L)
    private val authLimiter = ApiRateLimiter(2_200L)

    fun apiHealthSnapshot():GrowwApiHealthSnapshot=GrowwApiHealth.snapshot()

    private data class OhlcCacheEntry(val atMs: Long, val value: Ohlc)
    private val ohlcCache = ConcurrentHashMap<String, OhlcCacheEntry>()
    // A synchronized market pass happens every 5 minutes. Keep OHLC just under that so the
    // next cycle must refresh, while all engines inside one cycle reuse the same snapshot.
    private val ohlcCacheTtlMs = 4 * 60 * 1000L + 30_000L

    private data class QuoteCacheEntry(val atMs: Long, val value: Quote)
    private val quoteCache = ConcurrentHashMap<String, QuoteCacheEntry>()
    private val quoteCacheTtlMs = 2 * 60 * 1000L
    private val closedMarketLiveCacheTtlMs = 60 * 60 * 1000L

    private fun indianMarketHot():Boolean{
        val t=ZonedDateTime.now(ZoneId.of("Asia/Kolkata")).toLocalTime()
        return t>=LocalTime.of(9,0)&&t<=LocalTime.of(15,30)
    }

    private data class CandleCacheEntry(val atMs: Long, val value: List<Candle>)
    private val candleCache = ConcurrentHashMap<String, CandleCacheEntry>()
    private val intradayCandleCacheTtlMs = 4 * 60 * 1000L + 30_000L
    private val dailyCandleCacheTtlMs = 6 * 60 * 60 * 1000L

    suspend fun authenticate(credentials: AppCredentials): Pair<String, String> = withContext(Dispatchers.IO) {
        require(credentials.apiKeyOrTotpToken.isNotBlank()) { "API/TOTP token is empty" }
        require(credentials.secret.isNotBlank()) { "Secret is empty" }

        val body = when (credentials.mode) {
            AuthMode.TOTP -> JSONObject()
                .put("key_type", "totp")
                .put("totp", Totp.generate(credentials.secret))
            AuthMode.APPROVAL -> {
                val timestamp = (System.currentTimeMillis() / 1000L).toString()
                val checksum = sha256(credentials.secret + timestamp)
                JSONObject()
                    .put("key_type", "approval")
                    .put("checksum", checksum)
                    .put("timestamp", timestamp)
            }
        }

        val request = Request.Builder()
            .url("$API_BASE/v1/token/api/access")
            .header("Authorization", "Bearer ${credentials.apiKeyOrTotpToken}")
            .header("Content-Type", "application/json")
            .post(body.toString().toRequestBody("application/json".toMediaType()))
            .build()

        val raw = executeWithRetry(request, authLimiter, "Authentication", maxAttempts = 3, bucket = GrowwApiHealth.Bucket.AUTHENTICATION)
        val json = JSONObject(raw)
        val token = json.optString("token")
            .ifBlank { json.optJSONObject("payload")?.optString("token").orEmpty() }
        val expiry = json.optString("expiry")
            .ifBlank { json.optJSONObject("payload")?.optString("expiry").orEmpty() }

        require(token.isNotBlank()) { "Groww response did not contain an access token" }
        token to expiry
    }

    suspend fun downloadInstrumentCsv(): String = withContext(Dispatchers.IO) {
        val request = Request.Builder().url(INSTRUMENT_URL).get().build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                throw IOException("Instrument download failed: HTTP ${response.code}")
            }
            response.body?.string() ?: throw IOException("Empty instrument CSV")
        }
    }

    suspend fun placeMarketOrder(
        accessToken:String,
        tradingSymbol:String,
        quantity:Int,
        product:String,
        transactionType:String,
        orderReferenceId:String
    ):BrokerOrderPlacement=withContext(Dispatchers.IO){
        val symbol=tradingSymbol.trim().uppercase();val prod=product.trim().uppercase();val side=transactionType.trim().uppercase();val referenceId=orderReferenceId.trim()
        require(symbol.matches(Regex("""^[A-Z0-9&._-]{1,40}$"""))){"Invalid trading symbol"};require(quantity>0){"Quantity must be greater than zero"}
        require(prod in setOf("CNC","MIS")){"Unsupported product: "+prod};require(side in setOf("BUY","SELL")){"Unsupported transaction type: "+side}
        require(validOrderReference(referenceId)){"Invalid order reference id: Groww requires 8-20 alphanumeric characters with at most two hyphens"}
        require((side=="BUY"&&prod=="CNC")||(side=="SELL"&&prod=="MIS")){"Manual mapping rejected: LONG must be BUY/CNC and SHORT must be SELL/MIS"}
        val body=JSONObject().put("trading_symbol",symbol).put("quantity",quantity).put("validity","DAY").put("exchange","NSE").put("segment","CASH")
            .put("product",prod).put("order_type","MARKET").put("transaction_type",side).put("order_reference_id",referenceId)
        val request=Request.Builder().url(API_BASE+"/v1/order/create").header("Authorization","Bearer "+accessToken).header("Accept","application/json")
            .header("Content-Type","application/json").header("X-API-VERSION","1.0").post(body.toString().toRequestBody("application/json".toMediaType())).build()
        orderLimiter.awaitPermit()
        GrowwApiHealth.recordRequest(GrowwApiHealth.Bucket.ORDERS)
        val raw=client.newCall(request).execute().use{response->
            val text=response.body?.string().orEmpty()
            if(response.code==429)GrowwApiHealth.recordRateLimit(GrowwApiHealth.Bucket.ORDERS)
            if(!response.isSuccessful)throw IOException("Groww order failed ("+response.code+"): "+safeMessage(text))
            text
        }
        val json=runCatching{JSONObject(raw)}.getOrElse{throw IOException("Groww returned an unreadable order response")}
        if(!json.optString("status").equals("SUCCESS",true))throw IOException("Groww order rejected: "+safeMessage(raw))
        val x=json.optJSONObject("payload")?:JSONObject()
        BrokerOrderPlacement(x.optString("groww_order_id"),x.optString("order_reference_id").ifBlank{referenceId},x.optString("order_status").ifBlank{"ACCEPTED"},x.optString("remark"))
    }

    suspend fun placeIntradayMarketOrder(
        accessToken:String,
        tradingSymbol:String,
        quantity:Int,
        transactionType:String,
        orderReferenceId:String
    ):BrokerOrderPlacement=withContext(Dispatchers.IO){
        val symbol=tradingSymbol.trim().uppercase();val side=transactionType.trim().uppercase();val referenceId=orderReferenceId.trim()
        require(symbol.matches(Regex("""^[A-Z0-9&._-]{1,40}$"""))){"Invalid trading symbol"};require(quantity>0){"Quantity must be greater than zero"}
        require(side in setOf("BUY","SELL")){"Unsupported transaction type: $side"};require(validOrderReference(referenceId)){"Invalid order reference id: Groww requires 8-20 alphanumeric characters with at most two hyphens"}
        val body=JSONObject().put("trading_symbol",symbol).put("quantity",quantity).put("validity","DAY").put("exchange","NSE").put("segment","CASH")
            .put("product","MIS").put("order_type","MARKET").put("transaction_type",side).put("order_reference_id",referenceId)
        val request=Request.Builder().url(API_BASE+"/v1/order/create").header("Authorization","Bearer "+accessToken).header("Accept","application/json")
            .header("Content-Type","application/json").header("X-API-VERSION","1.0").post(body.toString().toRequestBody("application/json".toMediaType())).build()
        orderLimiter.awaitPermit();GrowwApiHealth.recordRequest(GrowwApiHealth.Bucket.ORDERS)
        val raw=client.newCall(request).execute().use{response->
            val text=response.body?.string().orEmpty();if(response.code==429)GrowwApiHealth.recordRateLimit(GrowwApiHealth.Bucket.ORDERS)
            if(!response.isSuccessful)throw IOException("Groww intraday order failed ("+response.code+"): "+safeMessage(text));text
        }
        val json=runCatching{JSONObject(raw)}.getOrElse{throw IOException("Groww returned an unreadable intraday order response")}
        if(!json.optString("status").equals("SUCCESS",true))throw IOException("Groww intraday order rejected: "+safeMessage(raw))
        val x=json.optJSONObject("payload")?:JSONObject()
        BrokerOrderPlacement(x.optString("groww_order_id"),x.optString("order_reference_id").ifBlank{referenceId},x.optString("order_status").ifBlank{"ACCEPTED"},x.optString("remark"))
    }

    suspend fun getOrderStatusByReference(accessToken:String,referenceId:String):BrokerOrderPlacement=withContext(Dispatchers.IO){
        val url=HttpUrl.Builder().scheme("https").host("api.groww.in").addPathSegments("v1/order/status/reference").addPathSegment(referenceId).addQueryParameter("segment","CASH").build()
        val raw=executeWithRetry(authedGet(url,accessToken),nonTradingLimiter,"Order status by reference",maxAttempts=3,bucket=GrowwApiHealth.Bucket.NON_TRADING);val j=JSONObject(raw)
        require(j.optString("status").equals("SUCCESS",true)){"Groww reference status failed"};val x=j.optJSONObject("payload")?:JSONObject()
        BrokerOrderPlacement(x.optString("groww_order_id"),x.optString("order_reference_id").ifBlank{referenceId},x.optString("order_status"),x.optString("remark"))
    }

    suspend fun getOrderDetail(accessToken:String,growwOrderId:String):BrokerOrderDetail=withContext(Dispatchers.IO){
        val url=HttpUrl.Builder().scheme("https").host("api.groww.in").addPathSegments("v1/order/detail").addPathSegment(growwOrderId).addQueryParameter("segment","CASH").build()
        val raw=executeWithRetry(authedGet(url,accessToken),nonTradingLimiter,"Order detail",maxAttempts=3,bucket=GrowwApiHealth.Bucket.NON_TRADING);val j=JSONObject(raw)
        require(j.optString("status").equals("SUCCESS",true)){"Groww order detail failed"};val x=j.optJSONObject("payload")?:JSONObject()
        BrokerOrderDetail(x.optString("groww_order_id").ifBlank{growwOrderId},x.optString("order_status"),x.optString("remark"),x.optInt("quantity"),x.optInt("filled_quantity"),x.optInt("remaining_quantity"),jsonDouble(x,"average_fill_price"),x.optString("order_reference_id"))
    }

    suspend fun getOrderTrades(accessToken:String,growwOrderId:String):List<BrokerFillRecord> = withContext(Dispatchers.IO){
        val url=HttpUrl.Builder().scheme("https").host("api.groww.in").addPathSegments("v1/order/trades").addPathSegment(growwOrderId).addQueryParameter("segment","CASH").addQueryParameter("page","0").addQueryParameter("page_size","50").build()
        val raw=executeWithRetry(authedGet(url,accessToken),nonTradingLimiter,"Order trades",maxAttempts=3,bucket=GrowwApiHealth.Bucket.NON_TRADING);val j=JSONObject(raw)
        require(j.optString("status").equals("SUCCESS",true)){"Groww order trades failed"};val a=j.optJSONObject("payload")?.optJSONArray("trade_list")?:JSONArray()
        buildList{for(i in 0 until a.length()){val x=a.optJSONObject(i)?:continue;add(BrokerFillRecord(x.optString("groww_trade_id"),x.optString("exchange_trade_id"),x.optString("exchange_order_id"),x.optInt("quantity").coerceAtLeast(0),jsonDouble(x,"price"),x.optString("trade_status"),x.optString("trade_date_time"),x.optString("remark")))}}
    }

    suspend fun getOhlcBatch(accessToken: String, symbols: List<String>): Map<String, Ohlc> =
        withContext(Dispatchers.IO) {
            if (symbols.isEmpty()) return@withContext emptyMap()
            require(symbols.size <= 50) { "Groww OHLC accepts a maximum of 50 symbols per call" }

            val now = System.currentTimeMillis()
            val result = linkedMapOf<String, Ohlc>()
            val missing = mutableListOf<String>()
            symbols.forEach { symbol ->
                val cached = ohlcCache[symbol]
                val ttl=if(indianMarketHot())ohlcCacheTtlMs else closedMarketLiveCacheTtlMs
                if (cached != null && now - cached.atMs <= ttl) {
                    result[symbol] = cached.value
                } else {
                    missing += symbol
                }
            }

            if (missing.isNotEmpty()) {
                val joined = missing.joinToString(",") { "NSE_$it" }
                val url = HttpUrl.Builder()
                    .scheme("https")
                    .host("api.groww.in")
                    .addPathSegments("v1/live-data/ohlc")
                    .addQueryParameter("segment", "CASH")
                    .addQueryParameter("exchange_symbols", joined)
                    .build()

                val request = authedGet(url, accessToken)
                val raw = executeWithRetry(request, liveLimiter, "OHLC")
                val fetched = parseOhlcPayload(JSONObject(raw).optJSONObject("payload") ?: JSONObject())
                val at = System.currentTimeMillis()
                fetched.forEach { (symbol, value) ->
                    ohlcCache[symbol] = OhlcCacheEntry(at, value)
                    result[symbol] = value
                }
            }

            result
        }

    suspend fun prefetchOhlcSnapshot(
        accessToken: String,
        symbols: List<String>,
        progress: suspend (String) -> Unit = {}
    ) {
        val unique = symbols.distinct()
        unique.chunked(50).forEachIndexed { index, batch ->
            getOhlcBatch(accessToken, batch)
            progress("Market snapshot ${((index + 1) * 50).coerceAtMost(unique.size)}/${unique.size}")
        }
    }

    suspend fun getQuote(accessToken: String, symbol: String, fresh:Boolean=false): Quote =
        withContext(Dispatchers.IO) {
            val now = System.currentTimeMillis()
            val ttl=if(indianMarketHot())quoteCacheTtlMs else closedMarketLiveCacheTtlMs
            if(!fresh) quoteCache[symbol]?.takeIf { now - it.atMs <= ttl }?.let { return@withContext it.value }
            val url = HttpUrl.Builder()
                .scheme("https")
                .host("api.groww.in")
                .addPathSegments("v1/live-data/quote")
                .addQueryParameter("exchange", "NSE")
                .addQueryParameter("segment", "CASH")
                .addQueryParameter("trading_symbol", symbol)
                .build()

            val request = authedGet(url, accessToken)
            val raw = executeWithRetry(request, liveLimiter, "Quote for $symbol")
            val payload = JSONObject(raw).optJSONObject("payload")
                ?: throw IOException("Missing quote payload for $symbol")
            parseQuote(symbol, payload).also { quoteCache[symbol] = QuoteCacheEntry(System.currentTimeMillis(), it) }
        }

    suspend fun getPositions(accessToken:String,segment:String="CASH"):List<BrokerPosition> = withContext(Dispatchers.IO){
        val seg=segment.trim().uppercase();require(seg in setOf("CASH","FNO","COMMODITY")){"Unsupported position segment"}
        val url=HttpUrl.Builder().scheme("https").host("api.groww.in").addPathSegments("v1/positions/user").addQueryParameter("segment",seg).build()
        val raw=executeWithRetry(authedGet(url,accessToken),nonTradingLimiter,"Positions",bucket=GrowwApiHealth.Bucket.NON_TRADING)
        val arr=JSONObject(raw).optJSONObject("payload")?.optJSONArray("positions")?:JSONArray()
        buildList{for(i in 0 until arr.length()){val o=arr.optJSONObject(i)?:continue;add(BrokerPosition(
            tradingSymbol=o.optString("trading_symbol").uppercase(),exchange=o.optString("exchange").uppercase(),product=o.optString("product").uppercase(),
            quantity=o.optInt("quantity"),netPrice=jsonDouble(o,"net_price"),realisedPnl=jsonDouble(o,"realised_pnl")
        ))}}
    }

    suspend fun getPositionBySymbol(accessToken:String,symbol:String):BrokerPosition? = getPositions(accessToken,"CASH").firstOrNull{it.tradingSymbol.equals(symbol,true)}

    suspend fun getMarginSnapshot(accessToken:String):BrokerMarginSnapshot = withContext(Dispatchers.IO){
        val url=HttpUrl.Builder().scheme("https").host("api.groww.in").addPathSegments("v1/margins/detail/user").build()
        val raw=executeWithRetry(authedGet(url,accessToken),nonTradingLimiter,"Available margin",maxAttempts=3,bucket=GrowwApiHealth.Bucket.NON_TRADING)
        val j=JSONObject(raw);require(j.optString("status").equals("SUCCESS",true)){"Groww margin snapshot failed"};val p=j.optJSONObject("payload")?:JSONObject();val e=p.optJSONObject("equity_margin_details")?:JSONObject()
        BrokerMarginSnapshot(jsonDouble(p,"clear_cash"),jsonDouble(e,"mis_balance_available"),jsonDouble(e,"net_equity_margin_used"))
    }

    suspend fun getIntradayOrderMargin(accessToken:String,symbol:String,quantity:Int,side:String,referencePrice:Double):BrokerOrderMargin = withContext(Dispatchers.IO){
        require(quantity>0){"Margin quantity must be positive"}
        val url=HttpUrl.Builder().scheme("https").host("api.groww.in").addPathSegments("v1/margins/detail/orders").addQueryParameter("segment","CASH").build()
        val body=JSONArray().put(JSONObject().put("trading_symbol",symbol.uppercase()).put("transaction_type",side.uppercase()).put("quantity",quantity).put("price",referencePrice).put("order_type","MARKET").put("product","MIS").put("exchange","NSE"))
        val req=Request.Builder().url(url).header("Authorization","Bearer $accessToken").header("Accept","application/json").header("Content-Type","application/json").header("X-API-VERSION","1.0").post(body.toString().toRequestBody("application/json".toMediaType())).build()
        val raw=executeWithRetry(req,nonTradingLimiter,"Order margin",maxAttempts=3,bucket=GrowwApiHealth.Bucket.NON_TRADING);val j=JSONObject(raw);require(j.optString("status").equals("SUCCESS",true)){"Groww order margin failed"};val p=j.optJSONObject("payload")?:JSONObject()
        BrokerOrderMargin(jsonDouble(p,"total_requirement"),jsonDouble(p,"cash_mis_margin_required"),jsonDouble(p,"brokerage_and_charges"))
    }

    suspend fun cancelOrder(accessToken:String,growwOrderId:String):BrokerOrderPlacement=withContext(Dispatchers.IO){
        val body=JSONObject().put("segment","CASH").put("groww_order_id",growwOrderId)
        val req=Request.Builder().url("$API_BASE/v1/order/cancel").header("Authorization","Bearer $accessToken").header("Accept","application/json").header("Content-Type","application/json").header("X-API-VERSION","1.0").post(body.toString().toRequestBody("application/json".toMediaType())).build()
        orderLimiter.awaitPermit();GrowwApiHealth.recordRequest(GrowwApiHealth.Bucket.ORDERS)
        val raw=client.newCall(req).execute().use{r->val t=r.body?.string().orEmpty();if(r.code==429)GrowwApiHealth.recordRateLimit(GrowwApiHealth.Bucket.ORDERS);if(!r.isSuccessful)throw IOException("Groww cancel failed (${r.code}): ${safeMessage(t)}");t}
        val j=JSONObject(raw);require(j.optString("status").equals("SUCCESS",true)){"Groww cancel rejected"};val x=j.optJSONObject("payload")?:JSONObject();BrokerOrderPlacement(x.optString("groww_order_id").ifBlank{growwOrderId},"",x.optString("order_status"),"")
    }

    suspend fun createCashMisOco(
        accessToken:String,tradingSymbol:String,quantity:Int,netPositionQuantity:Int,transactionType:String,
        targetTrigger:Double,stopTrigger:Double,referenceId:String
    ):BrokerSmartOrderPlacement=withContext(Dispatchers.IO){
        val symbol=tradingSymbol.trim().uppercase();val side=transactionType.trim().uppercase();val ref=referenceId.trim()
        require(quantity>0&&quantity<=kotlin.math.abs(netPositionQuantity)){"OCO quantity must be within actual broker position"}
        require(side in setOf("BUY","SELL")){"Unsupported OCO transaction side"};require(validOrderReference(ref)){"Invalid OCO reference id"}
        require(targetTrigger>0.0&&stopTrigger>0.0){"Invalid OCO target/stop"}
        val body=JSONObject().put("reference_id",ref).put("smart_order_type","OCO").put("segment","CASH").put("trading_symbol",symbol)
            .put("quantity",quantity).put("net_position_quantity",netPositionQuantity).put("transaction_type",side)
            .put("target",JSONObject().put("trigger_price","%.2f".format(java.util.Locale.US,targetTrigger)).put("order_type","LIMIT").put("price","%.2f".format(java.util.Locale.US,targetTrigger)))
            .put("stop_loss",JSONObject().put("trigger_price","%.2f".format(java.util.Locale.US,stopTrigger)).put("order_type","SL_M").put("price",JSONObject.NULL))
            .put("product_type","MIS").put("exchange","NSE").put("duration","DAY")
        val req=Request.Builder().url(API_BASE+"/v1/order-advance/create").header("Authorization","Bearer "+accessToken).header("Accept","application/json")
            .header("Content-Type","application/json").header("X-API-VERSION","1.0").post(body.toString().toRequestBody("application/json".toMediaType())).build()
        orderLimiter.awaitPermit();GrowwApiHealth.recordRequest(GrowwApiHealth.Bucket.ORDERS)
        val raw=client.newCall(req).execute().use{r->val text=r.body?.string().orEmpty();if(r.code==429)GrowwApiHealth.recordRateLimit(GrowwApiHealth.Bucket.ORDERS);if(!r.isSuccessful)throw IOException("Groww OCO failed (${r.code}): "+safeMessage(text));text}
        val json=JSONObject(raw);if(!json.optString("status").equals("SUCCESS",true))throw IOException("Groww OCO rejected: "+safeMessage(raw));val x=json.optJSONObject("payload")?:JSONObject()
        BrokerSmartOrderPlacement(x.optString("smart_order_id"),ref,x.optString("status").ifBlank{"ACTIVE"})
    }

    suspend fun cancelCashOco(accessToken:String,smartOrderId:String){withContext(Dispatchers.IO){
        if(smartOrderId.isBlank())return@withContext
        val req=Request.Builder().url(API_BASE+"/v1/order-advance/cancel/CASH/OCO/"+smartOrderId).header("Authorization","Bearer "+accessToken).header("Accept","application/json").header("X-API-VERSION","1.0").post(ByteArray(0).toRequestBody(null)).build()
        orderLimiter.awaitPermit();GrowwApiHealth.recordRequest(GrowwApiHealth.Bucket.ORDERS);client.newCall(req).execute().use{r->val text=r.body?.string().orEmpty();if(r.code==429)GrowwApiHealth.recordRateLimit(GrowwApiHealth.Bucket.ORDERS);if(!r.isSuccessful&&r.code !in listOf(404,409))throw IOException("Groww OCO cancel failed (${r.code}): "+safeMessage(text))}
    }}

    suspend fun getHistoricalCandles(
        accessToken: String,
        symbol: String,
        startTime: String,
        endTime: String,
        interval: String
    ): List<Candle> = withContext(Dispatchers.IO) {
        val cacheKey = "$symbol|$startTime|$endTime|$interval"
        val now = System.currentTimeMillis()
        val nowIst=ZonedDateTime.now(ZoneId.of("Asia/Kolkata")).toLocalTime()
        val marketOpen=nowIst>=LocalTime.of(9,15)&&nowIst<=LocalTime.of(15,30)
        val ttl = when {
            interval.equals("1day", true) -> dailyCandleCacheTtlMs
            !marketOpen -> 6 * 60 * 60 * 1000L
            else -> intradayCandleCacheTtlMs
        }
        candleCache[cacheKey]?.takeIf { now - it.atMs <= ttl }?.let { return@withContext it.value }
        if (candleCache.size > 1500) candleCache.clear()
        val url = HttpUrl.Builder()
            .scheme("https")
            .host("api.groww.in")
            .addPathSegments("v1/historical/candles")
            .addQueryParameter("exchange", "NSE")
            .addQueryParameter("segment", "CASH")
            .addQueryParameter("groww_symbol", "NSE-$symbol")
            .addQueryParameter("start_time", startTime)
            .addQueryParameter("end_time", endTime)
            .addQueryParameter("candle_interval", interval)
            .build()

        val request = authedGet(url, accessToken)
        val raw = executeWithRetry(request, historicalLimiter, "Historical candles for $symbol", bucket = GrowwApiHealth.Bucket.HISTORICAL)
        val payload = JSONObject(raw).optJSONObject("payload") ?: JSONObject()
        val candles = payload.optJSONArray("candles") ?: JSONArray()
        buildList {
            for (i in 0 until candles.length()) {
                val row = candles.optJSONArray(i) ?: continue
                if (row.length() < 6) continue
                val epochSeconds=parseHistoricalEpochSeconds(row.opt(0))
                val open=finiteNumber(row.optDouble(1))
                val high=finiteNumber(row.optDouble(2))
                val low=finiteNumber(row.optDouble(3))
                val close=finiteNumber(row.optDouble(4))
                if(epochSeconds<=0L||open<=0.0||high<=0.0||low<=0.0||close<=0.0)continue
                add(
                    Candle(
                        epochSeconds = epochSeconds,
                        open = open,
                        high = high,
                        low = low,
                        close = close,
                        volume = row.optLong(5).coerceAtLeast(0L)
                    )
                )
            }
        }.also { candleCache[cacheKey] = CandleCacheEntry(System.currentTimeMillis(), it) }
    }

    private data class HttpResult(
        val code: Int,
        val successful: Boolean,
        val body: String,
        val retryAfterSeconds: Long?
    )

    private suspend fun executeWithRetry(
        request: Request,
        limiter: ApiRateLimiter,
        operation: String,
        maxAttempts: Int = 5,
        bucket: GrowwApiHealth.Bucket = GrowwApiHealth.Bucket.LIVE_DATA
    ): String {
        var lastCode = 0
        var lastBody = ""
        repeat(maxAttempts) { attempt ->
            if(bucket==GrowwApiHealth.Bucket.LIVE_DATA)GrowwApiHealth.awaitLiveReserve()
            limiter.awaitPermit()
            GrowwApiHealth.recordRequest(bucket)
            val result = try {
                withContext(Dispatchers.IO) {
                    client.newCall(request).execute().use { response ->
                        HttpResult(
                            code = response.code,
                            successful = response.isSuccessful,
                            body = response.body?.string().orEmpty(),
                            retryAfterSeconds = response.header("Retry-After")?.toLongOrNull()
                        )
                    }
                }
            } catch(io:IOException) {
                if(attempt==maxAttempts-1){
                    val dns=io.message.orEmpty().contains("Unable to resolve host",true)||io.message.orEmpty().contains("hostname",true)
                    if(dns) throw IOException("Groww network/DNS lookup failed after automatic retries. The official API host is api.groww.in; Global Edge AI Trader will retry again in the background. If this persists, check Private DNS, VPN, Wi-Fi/mobile-data DNS, or network filtering.",io)
                    throw io
                }
                delay(min(15_000L,1_500L*(attempt+1)*(attempt+1)))
                return@repeat
            }
            lastCode = result.code
            lastBody = result.body
            if (result.successful) return result.body

            if (result.code == 429) {
                GrowwApiHealth.recordRateLimit(bucket)
                val serverMs = result.retryAfterSeconds?.times(1000L)
                val exponentialMs = min(30_000L, 2_000L * (1L shl attempt.coerceAtMost(4)))
                delay((serverMs ?: exponentialMs).coerceAtLeast(1_500L))
            } else {
                throw IOException("$operation failed (${result.code}): ${safeMessage(result.body)}")
            }
        }

        if (lastCode == 429) {
            throw IOException(
                "Groww live-data rate limit is still active. Global Edge AI Trader already slowed down and retried automatically. " +
                    "Please wait about one minute before the next manual scan."
            )
        }
        throw IOException("$operation failed ($lastCode): ${safeMessage(lastBody)}")
    }

    private fun authedGet(url: HttpUrl, token: String): Request =
        Request.Builder()
            .url(url)
            .header("Authorization", "Bearer $token")
            .header("Accept", "application/json")
            .header("X-API-VERSION", "1.0")
            .get()
            .build()

    private fun parseOhlcPayload(payload: JSONObject): Map<String, Ohlc> {
        val result = linkedMapOf<String, Ohlc>()
        val keys = payload.keys()
        while (keys.hasNext()) {
            val key = keys.next()
            val value = payload.opt(key)
            val obj = when (value) {
                is JSONObject -> value
                is String -> parseLooseOhlc(value)
                else -> null
            } ?: continue

            val symbol = key.substringAfter("NSE_")
            result[symbol] = Ohlc(
                open = jsonDouble(obj,"open"),
                high = jsonDouble(obj,"high"),
                low = jsonDouble(obj,"low"),
                close = jsonDouble(obj,"close")
            )
        }
        return result
    }

    private fun parseLooseOhlc(raw: String): JSONObject? {
        val clean = raw.trim().removePrefix("{").removeSuffix("}")
        if (clean.isBlank()) return null
        val obj = JSONObject()
        clean.split(",").forEach { token ->
            val parts = token.split(":", limit = 2)
            if (parts.size == 2) {
                val parsed=parts[1].trim().toDoubleOrNull();obj.put(parts[0].trim().trim('"'),parsed?.takeIf{it.isFinite()}?:0.0)
            }
        }
        return obj
    }

    private fun validOrderReference(value:String):Boolean{
        if(value.length !in 8..20)return false
        if(!value.matches(Regex("^[A-Za-z0-9-]+$")))return false
        return value.count{it=='-'}<=2
    }

    private fun parseQuote(symbol: String, p: JSONObject): Quote {
        val o = when (val raw = p.opt("ohlc")) {
            is JSONObject -> raw
            is String -> parseLooseOhlc(raw) ?: JSONObject()
            else -> JSONObject()
        }

        val depth = p.optJSONObject("depth") ?: JSONObject()

        fun levels(name: String): List<DepthLevel> {
            val arr = depth.optJSONArray(name) ?: JSONArray()
            return buildList {
                for (i in 0 until arr.length()) {
                    val x = arr.optJSONObject(i) ?: continue
                    val price=jsonDouble(x,"price");if(price>0.0)add(DepthLevel(price,x.optLong("quantity").coerceAtLeast(0L)))
                }
            }
        }

        return Quote(
            symbol = symbol,
            lastPrice = jsonDouble(p,"last_price"),
            previousClose = jsonDouble(o,"close"),
            dayChangePercent = jsonDouble(p,"day_change_perc"),
            upperCircuit = jsonDouble(p,"upper_circuit_limit"),
            lowerCircuit = jsonDouble(p,"lower_circuit_limit"),
            volume = p.optLong("volume"),
            totalBuyQuantity = p.optLong("total_buy_quantity"),
            totalSellQuantity = p.optLong("total_sell_quantity"),
            bidPrice = jsonDouble(p,"bid_price"),
            bidQuantity = p.optLong("bid_quantity"),
            offerPrice = jsonDouble(p,"offer_price"),
            offerQuantity = p.optLong("offer_quantity"),
            marketCap = jsonDouble(p,"market_cap"),
            week52High = jsonDouble(p,"week_52_high"),
            week52Low = jsonDouble(p,"week_52_low"),
            ohlc = Ohlc(
                open = jsonDouble(o,"open"),
                high = jsonDouble(o,"high"),
                low = jsonDouble(o,"low"),
                close = jsonDouble(o,"close")
            ),
            buyDepth = levels("buy"),
            sellDepth = levels("sell"),
            lastTradeTime = p.optLong("last_trade_time")
        )
    }

    private fun sha256(input: String): String {
        val bytes = MessageDigest.getInstance("SHA-256").digest(input.toByteArray())
        return bytes.joinToString("") { "%02x".format(it) }
    }

    private fun safeMessage(raw: String): String =
        runCatching {
            val j = JSONObject(raw)
            j.optString("message")
                .ifBlank { j.optString("error") }
                .ifBlank { raw.take(220) }
        }.getOrElse { raw.take(220) }
}

object Totp {
    fun generate(base32Secret: String, timeMillis: Long = System.currentTimeMillis()): String {
        val key = decodeBase32(base32Secret.replace(" ", "").uppercase())
        val counter = timeMillis / 1000L / 30L
        val data = ByteArray(8)
        var value = counter
        for (i in 7 downTo 0) {
            data[i] = (value and 0xff).toByte()
            value = value shr 8
        }
        val mac = Mac.getInstance("HmacSHA1")
        mac.init(SecretKeySpec(key, "HmacSHA1"))
        val hash = mac.doFinal(data)
        val offset = hash.last().toInt() and 0x0f
        val binary = ((hash[offset].toInt() and 0x7f) shl 24) or
                ((hash[offset + 1].toInt() and 0xff) shl 16) or
                ((hash[offset + 2].toInt() and 0xff) shl 8) or
                (hash[offset + 3].toInt() and 0xff)
        val otp = binary % 1_000_000
        return otp.toString().padStart(6, '0')
    }

    private fun decodeBase32(input: String): ByteArray {
        val alphabet = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567"
        var buffer = 0
        var bitsLeft = 0
        val out = ArrayList<Byte>()
        input.trimEnd('=').forEach { ch ->
            val value = alphabet.indexOf(ch)
            if (value < 0) return@forEach
            buffer = (buffer shl 5) or value
            bitsLeft += 5
            if (bitsLeft >= 8) {
                out.add(((buffer shr (bitsLeft - 8)) and 0xff).toByte())
                bitsLeft -= 8
            }
        }
        return out.toByteArray()
    }
}
