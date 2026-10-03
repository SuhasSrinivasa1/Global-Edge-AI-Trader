package com.suhas.globaledgeai.domain.engine

import com.suhas.globaledgeai.domain.model.*
import java.time.*
import kotlin.math.abs

class EvidenceFabricEngine {
    companion object { const val VERSION="EVIDENCE-FABRIC-2026.09.25" }

    fun sector(
        symbol:String,
        direction:TradeDirection,
        industryMap:Map<String,String>,
        ohlc:Map<String,Ohlc>,
        observedAt:Long=System.currentTimeMillis()
    ):SectorIntelligence?{
        val industry=industryMap[symbol]?:return null
        val peers=industryMap.filterValues{it==industry}.keys.mapNotNull{s->
            val x=ohlc[s]?:return@mapNotNull null
            if(x.open<=0.0||x.close<=0.0)return@mapNotNull null
            s to ((x.close/x.open-1.0)*100.0)
        }
        if(peers.size<4)return SectorIntelligence(symbol,industry,peers.size,50.0,0.0,0.0,0.0,false,"NSE Indices NIFTY 500",observedAt)
        val stock=peers.firstOrNull{it.first==symbol}?.second?:return null
        val other=peers.filterNot{it.first==symbol}.map{it.second}
        if(other.isEmpty())return null
        val mean=other.average()
        val confirming=if(direction==TradeDirection.LONG)other.count{it>0.0}else other.count{it<0.0}
        val breadth=confirming*100.0/other.size
        val rs=if(direction==TradeDirection.LONG)stock-mean else mean-stock
        val confirmed=breadth>=60.0&&rs>=-0.20
        return SectorIntelligence(symbol,industry,other.size,breadth,mean,stock,rs,confirmed,"NSE Indices NIFTY 500",observedAt)
    }

    fun sectorAdjustment(x:SectorIntelligence?):Double{
        if(x==null||x.peersObserved<4)return 0.0
        return when{
            x.peerConfirmed&&x.relativeStrengthPct>=0.75->3.0
            x.peerConfirmed&&x.relativeStrengthPct>=0.0->1.5
            x.directionalBreadthPct<35.0&&x.relativeStrengthPct<0.0->-3.0
            x.directionalBreadthPct<45.0->-1.5
            else->0.0
        }
    }

    fun seededMacroEvents():List<MacroEventRecord>{
        val z=ZoneId.of("Asia/Kolkata")
        fun event(id:String,title:String,start:LocalDate,end:LocalDate,risk:EventRiskLevel,source:String,url:String)=MacroEventRecord(
            id,title,start.atStartOfDay(z).toInstant().toEpochMilli(),
            end.atTime(23,59,59).atZone(z).toInstant().toEpochMilli(),risk,source,url,
            observedAt=LocalDate.of(2026,4,1).atStartOfDay(z).toInstant().toEpochMilli(),prospective=true
        )
        return listOf(
            event("RBI-MPC-2026-04","RBI MPC • Apr 6–8",LocalDate.of(2026,4,6),LocalDate.of(2026,4,8),EventRiskLevel.HIGH,"RBI FY27 MPC schedule","https://www.rbi.org.in/"),
            event("RBI-MPC-2026-06","RBI MPC • Jun 3–5",LocalDate.of(2026,6,3),LocalDate.of(2026,6,5),EventRiskLevel.HIGH,"RBI FY27 MPC schedule","https://www.rbi.org.in/"),
            event("RBI-MPC-2026-08","RBI MPC • Aug 3–5",LocalDate.of(2026,8,3),LocalDate.of(2026,8,5),EventRiskLevel.HIGH,"RBI FY27 MPC schedule","https://www.rbi.org.in/"),
            event("RBI-MPC-2026-10","RBI MPC • Oct 5–7",LocalDate.of(2026,10,5),LocalDate.of(2026,10,7),EventRiskLevel.HIGH,"RBI FY27 MPC schedule","https://www.rbi.org.in/"),
            event("RBI-MPC-2026-12","RBI MPC • Dec 2–4",LocalDate.of(2026,12,2),LocalDate.of(2026,12,4),EventRiskLevel.HIGH,"RBI FY27 MPC schedule","https://www.rbi.org.in/"),
            event("RBI-MPC-2027-02","RBI MPC • Feb 3–5",LocalDate.of(2027,2,3),LocalDate.of(2027,2,5),EventRiskLevel.HIGH,"RBI FY27 MPC schedule","https://www.rbi.org.in/")
        )
    }

    fun macroRisk(now:ZonedDateTime,events:List<MacroEventRecord>):Pair<EventRiskLevel?,String>{
        val ms=now.toInstant().toEpochMilli()
        val relevant=events.filter{ms in it.startAt..it.endAt}
        if(relevant.isEmpty())return null to ""
        val max=relevant.maxByOrNull{it.risk.ordinal}?:return null to ""
        // On the final day of an RBI meeting, the first 75 minutes of the regular session are a hard-risk window.
        val finalDay=Instant.ofEpochMilli(max.endAt).atZone(now.zone).toLocalDate()
        val highWindow=max.id.startsWith("RBI-MPC")&&now.toLocalDate()==finalDay&&now.toLocalTime()<=LocalTime.of(10,30)
        return (if(highWindow)EventRiskLevel.HIGH else EventRiskLevel.MEDIUM) to max.title
    }

    fun shouldHardWaitForMacro(now:ZonedDateTime,events:List<MacroEventRecord>):Boolean{
        val (risk,_)=macroRisk(now,events)
        return risk==EventRiskLevel.HIGH
    }

    fun companyEventRisk(symbol:String,nowMs:Long,events:List<MacroEventRecord>):MacroEventRecord?{
        val z=ZoneId.of("Asia/Kolkata")
        val today=Instant.ofEpochMilli(nowMs).atZone(z).toLocalDate()
        return events.filter{it.symbol.equals(symbol,true)&&it.prospective}.minByOrNull{
            abs(Duration.between(Instant.ofEpochMilli(nowMs),Instant.ofEpochMilli(it.startAt)).toHours())
        }?.takeIf{
            val d=Instant.ofEpochMilli(it.startAt).atZone(z).toLocalDate()
            abs(Duration.between(today.atStartOfDay(),d.atStartOfDay()).toDays())<=1
        }
    }
}
