package com.suhas.globaledgeai.data.local

import android.content.Context
import androidx.room.*
import com.suhas.globaledgeai.domain.model.*

@Entity(
    tableName="strategy_learning_events",
    indices=[
        Index(value=["strategyId","direction","sessionBand","regime","source"]),
        Index(value=["sessionDate"]),
        Index(value=["closedAt"]),
        Index(value=["outcome"])
    ]
)
data class StrategyLearningEventEntity(
    @PrimaryKey val eventId:String,
    val strategyId:String,
    val strategyName:String,
    val symbol:String,
    val direction:String,
    val source:String,
    val sessionBand:String,
    val regime:String,
    val rawScore:Double,
    val calibratedProbabilityPct:Double,
    val entryPrice:Double,
    val targetPct:Double,
    val stopPct:Double,
    val openedAt:Long,
    val closedAt:Long,
    val sessionDate:String,
    val outcome:String,
    val returnPct:Double,
    val rMultiple:Double,
    val researchSignature:String,
    val componentStrategyIds:String,
    val clusterKey:String,
    val sampleWeight:Double,
    val legacy:Boolean,
    val modelVersion:String="STRAT-GOV-V2-1.7.0"
){
    fun toDomain()=StrategyLearningObservation(
        eventId=eventId,strategyId=strategyId,strategyName=strategyName,symbol=symbol,
        direction=runCatching{TradeDirection.valueOf(direction)}.getOrDefault(TradeDirection.LONG),
        source=runCatching{StrategyLearningSource.valueOf(source)}.getOrDefault(StrategyLearningSource.LEGACY_LIVE),
        sessionBand=sessionBand,
        regime=runCatching{MarketRegime.valueOf(regime)}.getOrDefault(MarketRegime.MIXED),
        rawScore=rawScore,calibratedProbabilityPct=calibratedProbabilityPct,entryPrice=entryPrice,targetPct=targetPct,stopPct=stopPct,
        openedAt=openedAt,closedAt=closedAt,sessionDate=sessionDate,
        outcome=runCatching{StrategyLearningOutcome.valueOf(outcome)}.getOrDefault(StrategyLearningOutcome.INVALID),
        returnPct=returnPct,rMultiple=rMultiple,researchSignature=researchSignature,
        componentStrategyIds=componentStrategyIds.split(',').filter{it.isNotBlank()},clusterKey=clusterKey,
        sampleWeight=sampleWeight,legacy=legacy
    )

    companion object{
        fun from(x:StrategyLearningObservation)=StrategyLearningEventEntity(
            x.eventId,x.strategyId,x.strategyName,x.symbol,x.direction.name,x.source.name,x.sessionBand,x.regime.name,
            x.rawScore,x.calibratedProbabilityPct,x.entryPrice,x.targetPct,x.stopPct,x.openedAt,x.closedAt,x.sessionDate,x.outcome.name,
            x.returnPct,x.rMultiple,x.researchSignature,x.componentStrategyIds.joinToString(","),x.clusterKey,x.sampleWeight,x.legacy
        )
    }
}

@Entity(tableName="strategy_legacy_priors")
data class StrategyLegacyPriorEntity(
    @PrimaryKey val strategyId:String,
    val strategyName:String,
    val observations:Int,
    val wins:Int,
    val avgReturnPct:Double,
    val importedAt:Long
){
    fun toDomain()=StrategyLegacyPrior(strategyId,strategyName,observations,wins,avgReturnPct)
}

@Entity(
    tableName="strategy_daily_roster",
    primaryKeys=["sessionDate","strategyId","direction","sessionBand","regime"],
    indices=[Index(value=["sessionDate"]),Index(value=["strategyId"])]
)
data class StrategyRosterEntity(
    val sessionDate:String,
    val strategyId:String,
    val strategyName:String,
    val direction:String,
    val sessionBand:String,
    val regime:String,
    val status:String,
    val samples:Int,
    val wins:Int,
    val distinctSessions:Int,
    val hitRatePct:Double,
    val recentHitRatePct:Double,
    val expectancyR:Double,
    val confidenceFloorPct:Double,
    val shadowSamples:Int,
    val shadowHitRatePct:Double,
    val calibratedBasePct:Double,
    val frozenAt:Long,
    val note:String
){
    fun toDomain()=StrategyRosterDecision(
        sessionDate,strategyId,strategyName,runCatching{TradeDirection.valueOf(direction)}.getOrDefault(TradeDirection.LONG),
        sessionBand,runCatching{MarketRegime.valueOf(regime)}.getOrDefault(MarketRegime.MIXED),
        runCatching{StrategyStatus.valueOf(status)}.getOrDefault(StrategyStatus.CHALLENGER),
        samples,wins,distinctSessions,hitRatePct,recentHitRatePct,expectancyR,confidenceFloorPct,
        shadowSamples,shadowHitRatePct,calibratedBasePct,frozenAt,note
    )
    companion object{
        fun from(x:StrategyRosterDecision)=StrategyRosterEntity(
            x.sessionDate,x.strategyId,x.strategyName,x.direction.name,x.sessionBand,x.regime.name,x.status.name,
            x.samples,x.wins,x.distinctSessions,x.hitRatePct,x.recentHitRatePct,x.expectancyR,x.confidenceFloorPct,
            x.shadowSamples,x.shadowHitRatePct,x.calibratedBasePct,x.frozenAt,x.note
        )
    }
}

@Dao
interface StrategyLearningDao{
    @Insert(onConflict=OnConflictStrategy.IGNORE)
    suspend fun insertEvents(rows:List<StrategyLearningEventEntity>):List<Long>

    @Insert(onConflict=OnConflictStrategy.REPLACE)
    suspend fun upsertEvent(row:StrategyLearningEventEntity)

    @Query("SELECT * FROM strategy_learning_events ORDER BY CASE WHEN closedAt=0 THEN openedAt ELSE closedAt END DESC LIMIT :limit")
    suspend fun recentEvents(limit:Int=20000):List<StrategyLearningEventEntity>

    @Query("SELECT * FROM strategy_learning_events WHERE outcome='PENDING' AND source='SHADOW_V2' ORDER BY openedAt ASC LIMIT :limit")
    suspend fun pendingShadows(limit:Int=250):List<StrategyLearningEventEntity>

    @Query("SELECT COUNT(*) FROM strategy_learning_events")
    suspend fun eventCount():Int

    @Query("SELECT COUNT(*) FROM strategy_learning_events WHERE eventId=:eventId")
    suspend fun eventExists(eventId:String):Int

    @Insert(onConflict=OnConflictStrategy.REPLACE)
    suspend fun upsertPriors(rows:List<StrategyLegacyPriorEntity>)

    @Query("SELECT * FROM strategy_legacy_priors")
    suspend fun allPriors():List<StrategyLegacyPriorEntity>

    @Insert(onConflict=OnConflictStrategy.REPLACE)
    suspend fun upsertRoster(rows:List<StrategyRosterEntity>)

    @Query("SELECT * FROM strategy_daily_roster WHERE sessionDate=:sessionDate")
    suspend fun rosterForDate(sessionDate:String):List<StrategyRosterEntity>

    @Query("SELECT * FROM strategy_daily_roster ORDER BY sessionDate DESC")
    suspend fun allRosters():List<StrategyRosterEntity>

    @Query("DELETE FROM strategy_daily_roster WHERE sessionDate=:sessionDate")
    suspend fun deleteRosterForDate(sessionDate:String)
}

@Database(
    entities=[StrategyLearningEventEntity::class,StrategyLegacyPriorEntity::class,StrategyRosterEntity::class],
    version=1,
    exportSchema=false
)
abstract class StrategyLearningDatabase:RoomDatabase(){
    abstract fun dao():StrategyLearningDao
    companion object{
        @Volatile private var INSTANCE:StrategyLearningDatabase?=null
        fun get(context:Context):StrategyLearningDatabase=INSTANCE?:synchronized(this){
            INSTANCE?:Room.databaseBuilder(
                context.applicationContext,
                StrategyLearningDatabase::class.java,
                "global-edge-strategy-learning-v170.db"
            ).build().also{INSTANCE=it}
        }
    }
}
