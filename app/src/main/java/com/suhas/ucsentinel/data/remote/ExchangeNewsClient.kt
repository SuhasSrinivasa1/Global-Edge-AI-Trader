package com.suhas.globaledgeai.data.remote

import com.suhas.globaledgeai.domain.model.NewsItem
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.*
import org.json.JSONArray
import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlPullParserFactory
import java.io.StringReader
import java.util.concurrent.TimeUnit

class ExchangeNewsClient {
    private data class NewsCache(val at:Long,val items:List<NewsItem>)
    private var marketCache:NewsCache?=null
    private val queryCache=mutableMapOf<String,NewsCache>()
    private val cookieStore = mutableMapOf<String, MutableList<Cookie>>()

    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .cookieJar(object : CookieJar {
            override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) {
                cookieStore.getOrPut(url.host) { mutableListOf() }.apply {
                    removeAll { old -> cookies.any { it.name == old.name } }
                    addAll(cookies)
                }
            }

            override fun loadForRequest(url: HttpUrl): List<Cookie> =
                cookieStore[url.host].orEmpty()
        })
        .build()

    suspend fun latest(): List<NewsItem> = withContext(Dispatchers.IO) {
        val nse = runCatching { fetchNseAnnouncements() }.getOrDefault(emptyList())
        val bse = runCatching { fetchBseNotices() }.getOrDefault(emptyList())
        (nse + bse).take(80)
    }

    suspend fun marketContext(limit:Int=40):List<NewsItem> = withContext(Dispatchers.IO) {
        val now=System.currentTimeMillis();marketCache?.takeIf{now-it.at<15L*60*1000}?.let{return@withContext it.items.take(limit)}
        val exchange=runCatching{fetchNseAnnouncements()+fetchBseNotices()}.getOrDefault(emptyList())
        val macro=runCatching{fetchGoogleNews("India stock market Nifty Sensex RBI global markets geopolitical oil")}.getOrDefault(emptyList())
        val global=runCatching{fetchGoogleNews("global markets Federal Reserve geopolitics crude oil Asia markets")}.getOrDefault(emptyList())
        val items=(exchange+macro+global).distinctBy{it.title}.take(80);marketCache=NewsCache(now,items);items.take(limit)
    }

    suspend fun contextual(symbol:String,company:String,limit:Int=12):List<NewsItem> = withContext(Dispatchers.IO) {
        val key=(symbol+"|"+company).uppercase();val now=System.currentTimeMillis();queryCache[key]?.takeIf{now-it.at<30L*60*1000}?.let{return@withContext it.items.take(limit)}
        val local=runCatching{fetchNseAnnouncements()+fetchBseNotices()}.getOrDefault(emptyList()).filter{n->
            val t=(n.symbol+" "+n.title+" "+n.summary).uppercase();symbol.uppercase() in t || company.split(" ").filter{it.length>=4}.any{it.uppercase() in t}
        }
        val q=listOf(symbol,company).filter{it.isNotBlank()}.joinToString(" ")
        val web=if(q.isBlank())emptyList() else runCatching{fetchGoogleNews(q+" stock India")}.getOrDefault(emptyList())
        val items=(local+web).distinctBy{it.title}.take(30);queryCache[key]=NewsCache(now,items);items.take(limit)
    }

    private fun fetchNseAnnouncements(): List<NewsItem> {
        val headers = Headers.Builder()
            .add("User-Agent", "Mozilla/5.0 (Android 16; Mobile) AppleWebKit/537.36 Chrome/139 Safari/537.36")
            .add("Accept-Language", "en-US,en;q=0.9")
            .build()

        val bootstrap = Request.Builder()
            .url("https://www.nseindia.com/companies-listing/corporate-filings-announcements")
            .headers(headers)
            .get()
            .build()
        client.newCall(bootstrap).execute().close()

        val request = Request.Builder()
            .url("https://www.nseindia.com/api/corporate-announcements?index=equities")
            .headers(headers)
            .header("Accept", "application/json,text/plain,*/*")
            .header("Referer", "https://www.nseindia.com/companies-listing/corporate-filings-announcements")
            .get()
            .build()

        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) return emptyList()
            val raw = response.body?.string().orEmpty()
            val arr = runCatching { JSONArray(raw) }.getOrElse { JSONArray() }
            return buildList {
                for (i in 0 until minOf(arr.length(), 60)) {
                    val j = arr.optJSONObject(i) ?: continue
                    add(
                        NewsItem(
                            source = "NSE",
                            symbol = j.optString("symbol"),
                            title = j.optString("desc").ifBlank { "Corporate announcement" },
                            summary = j.optString("attchmntText"),
                            publishedAt = j.optString("an_dt"),
                            url = j.optString("attchmntFile")
                        )
                    )
                }
            }
        }
    }


    private fun fetchGoogleNews(query:String):List<NewsItem>{
        val url=HttpUrl.Builder().scheme("https").host("news.google.com").addPathSegments("rss/search")
            .addQueryParameter("q",query).addQueryParameter("hl","en-IN").addQueryParameter("gl","IN").addQueryParameter("ceid","IN:en").build()
        val request=Request.Builder().url(url).header("User-Agent","Mozilla/5.0 (Android 16; Mobile)").get().build()
        client.newCall(request).execute().use{response->
            if(!response.isSuccessful)return emptyList()
            return parseRss(response.body?.string().orEmpty(),"NEWS")
        }
    }

    private fun fetchBseNotices(): List<NewsItem> {
        val request = Request.Builder()
            .url("https://www.bseindia.com/data/xml/notices.xml")
            .header("User-Agent", "Mozilla/5.0 (Android 16; Mobile)")
            .get()
            .build()

        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) return emptyList()
            val xml = response.body?.string().orEmpty()
            return parseRss(xml,"BSE")
        }
    }

    private fun parseRss(xml: String, source:String): List<NewsItem> {
        val parser = XmlPullParserFactory.newInstance().newPullParser()
        parser.setInput(StringReader(xml))

        val out = mutableListOf<NewsItem>()
        var event = parser.eventType
        var inItem = false
        var title = ""
        var link = ""
        var description = ""
        var pubDate = ""
        var currentTag = ""

        while (event != XmlPullParser.END_DOCUMENT) {
            when (event) {
                XmlPullParser.START_TAG -> {
                    currentTag = parser.name.orEmpty()
                    if (currentTag.equals("item", true)) {
                        inItem = true
                        title = ""
                        link = ""
                        description = ""
                        pubDate = ""
                    }
                }
                XmlPullParser.TEXT -> if (inItem) {
                    val value = parser.text.orEmpty().trim()
                    if (value.isNotBlank()) {
                        when (currentTag.lowercase()) {
                            "title" -> title += value
                            "link" -> link += value
                            "description" -> description += value
                            "pubdate" -> pubDate += value
                        }
                    }
                }
                XmlPullParser.END_TAG -> {
                    if (parser.name.equals("item", true) && inItem) {
                        out += NewsItem(
                            source = source,
                            symbol = "",
                            title = title,
                            summary = description,
                            publishedAt = pubDate,
                            url = link
                        )
                        inItem = false
                    }
                    currentTag = ""
                }
            }
            event = parser.next()
        }
        return out.take(30)
    }
}
