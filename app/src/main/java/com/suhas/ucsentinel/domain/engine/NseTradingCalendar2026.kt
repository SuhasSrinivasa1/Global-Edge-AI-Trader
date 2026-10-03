package com.suhas.globaledgeai.domain.engine

import java.time.*

/**
 * Exact NSE Capital Market regular-session calendar for calendar year 2026.
 * Source: NSE/CMTR/71775 dated 12-Dec-2025.
 *
 * For years other than 2026 we conservatively apply weekday logic only; no future holiday
 * is invented. All 2026 publication, execution and Challenger horizon calculations use this object.
 */
object NseTradingCalendar2026 {
    const val VERSION="NSE-CMTR-71775-2026"
    val zone:ZoneId=ZoneId.of("Asia/Kolkata")
    val open:LocalTime=LocalTime.of(9,15)
    val close:LocalTime=LocalTime.of(15,30)

    val holidays:Set<LocalDate> = setOf(
        LocalDate.of(2026,1,26),
        LocalDate.of(2026,3,3),
        LocalDate.of(2026,3,26),
        LocalDate.of(2026,3,31),
        LocalDate.of(2026,4,3),
        LocalDate.of(2026,4,14),
        LocalDate.of(2026,5,1),
        LocalDate.of(2026,5,28),
        LocalDate.of(2026,6,26),
        LocalDate.of(2026,9,14),
        LocalDate.of(2026,10,2),
        LocalDate.of(2026,10,20),
        LocalDate.of(2026,11,10),
        LocalDate.of(2026,11,24),
        LocalDate.of(2026,12,25)
    )

    fun isTradingDate(date:LocalDate):Boolean =
        date.dayOfWeek !in setOf(DayOfWeek.SATURDAY,DayOfWeek.SUNDAY) &&
            (date.year!=2026 || date !in holidays)

    fun phase(now:ZonedDateTime=ZonedDateTime.now(zone)):MarketPhaseInfo {
        val d=now.toLocalDate();val t=now.toLocalTime()
        if(!isTradingDate(d))return MarketPhaseInfo(false,false,"Market closed • NSE holiday/weekend",d)
        return when{
            t<open->MarketPhaseInfo(true,false,"Market closed • pre-open",d)
            t<=close->MarketPhaseInfo(true,true,"NSE regular session open",d)
            else->MarketPhaseInfo(true,false,"Market closed • regular session ended",d)
        }
    }

    fun nextTradingDate(from:LocalDate,includeCurrent:Boolean=false):LocalDate{
        var d=if(includeCurrent)from else from.plusDays(1)
        while(!isTradingDate(d))d=d.plusDays(1)
        return d
    }

    fun previousTradingDate(from:LocalDate,includeCurrent:Boolean=false):LocalDate{
        var d=if(includeCurrent)from else from.minusDays(1)
        while(!isTradingDate(d))d=d.minusDays(1)
        return d
    }

    fun remainingSessionsInWeek(date:LocalDate):Int{
        val end=date.plusDays((DayOfWeek.FRIDAY.value-date.dayOfWeek.value).toLong().coerceAtLeast(0))
        var d=date;var n=0
        while(!d.isAfter(end)){if(isTradingDate(d))n++;d=d.plusDays(1)}
        return n
    }

    fun remainingSessionsInMonth(date:LocalDate):Int{
        val end=date.withDayOfMonth(date.lengthOfMonth())
        var d=date;var n=0
        while(!d.isAfter(end)){if(isTradingDate(d))n++;d=d.plusDays(1)}
        return n
    }

    /**
     * Add live-market minutes while respecting 09:15–15:30 and the exact 2026 NSE calendar.
     * This is used for Challenger scheduled-horizon evaluation so a restart cannot shift the target.
     */
    fun addTradingMinutes(startEpochMs:Long,minutes:Int):Long{
        var remaining=minutes.coerceAtLeast(0)
        var z=Instant.ofEpochMilli(startEpochMs).atZone(zone)
        var d=z.toLocalDate();var t=z.toLocalTime()
        if(!isTradingDate(d)){
            d=nextTradingDate(d,includeCurrent=false);t=open
        }else when{
            t<open->t=open
            t>=close->{d=nextTradingDate(d,includeCurrent=false);t=open}
        }
        while(remaining>0){
            val available=Duration.between(t,close).toMinutes().toInt().coerceAtLeast(0)
            if(remaining<=available){
                t=t.plusMinutes(remaining.toLong());remaining=0
            }else{
                remaining-=available
                d=nextTradingDate(d,includeCurrent=false);t=open
            }
        }
        return ZonedDateTime.of(d,t,zone).toInstant().toEpochMilli()
    }

    data class MarketPhaseInfo(val tradingDate:Boolean,val open:Boolean,val label:String,val sessionDate:LocalDate)
}
