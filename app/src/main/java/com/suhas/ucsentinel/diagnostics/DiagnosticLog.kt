package com.suhas.globaledgeai.diagnostics

import android.content.Context
import java.io.File
import java.io.OutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter

object DiagnosticLog {
    private const val MAX_BYTES = 5_000_000L
    private const val MAX_ARCHIVES = 6
    private val fmt = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS")
    private fun file(context: Context): File = File(context.filesDir, "global-edge-diagnostics.log")
    private fun archive(context:Context,index:Int):File = File(context.filesDir,"global-edge-diagnostics.$index.log")

    @Synchronized
    fun log(context: Context, tag: String, message: String, error: Throwable? = null) {
        runCatching {
            val f = file(context)
            if (f.exists() && f.length() > MAX_BYTES) {
                archive(context,MAX_ARCHIVES).delete()
                for(i in MAX_ARCHIVES-1 downTo 1){
                    val src=archive(context,i);if(src.exists())src.renameTo(archive(context,i+1))
                }
                f.renameTo(archive(context,1))
            }
            val stamp = ZonedDateTime.now(ZoneId.of("Asia/Kolkata")).format(fmt)
            val safe = message.replace('\n', ' ').replace('\r', ' ').take(1200)
            val err = error?.let { " | ${it::class.java.simpleName}: ${it.message.orEmpty().replace('\n',' ').take(800)}" }.orEmpty()
            f.appendText("$stamp [$tag] $safe$err\n")
        }
    }

    fun snapshot(context: Context): String {
        val header = "Global Edge AI Trader diagnostics\nGenerated: ${ZonedDateTime.now(ZoneId.of("Asia/Kolkata"))}\n\n"
        return header + rawBody(context)
    }

    fun weeklySnapshot(context: Context, learningReport: String): String {
        val now=ZonedDateTime.now(ZoneId.of("Asia/Kolkata"))
        return buildString {
            append("Global Edge AI Trader WEEKLY LEARNING LOG\n")
            append("Generated: $now\n")
            append("Contains autonomous 15-minute audit snapshots and engine diagnostics.\n\n")
            append(learningReport)
            append("\n\n--- DIAGNOSTIC / LEARNING TIMELINE ---\n")
            append(rawBody(context))
        }
    }

    private fun rawBody(context: Context): String {
        val legacy = File(context.filesDir, "global-edge-diagnostics.previous.log")
        val current = file(context)
        return buildString {
            if(legacy.exists())append(legacy.readText().takeLast(1_000_000))
            for(i in MAX_ARCHIVES downTo 1){val f=archive(context,i);if(f.exists())append(f.readText())}
            if(current.exists())append(current.readText())
        }.takeLast(35_000_000)
    }
    fun writeEndOfDayBundle(
        context: Context,
        output: OutputStream,
        appVersion: String,
        stateReport: String,
        learningReport: String
    ) {
        val now=ZonedDateTime.now(ZoneId.of("Asia/Kolkata"))
        ZipOutputStream(output.buffered()).use { zip ->
            fun add(name:String,text:String){
                zip.putNextEntry(ZipEntry(name))
                zip.write(text.toByteArray(Charsets.UTF_8))
                zip.closeEntry()
            }
            add("README.txt", buildString {
                appendLine("Global Edge AI Trader end-of-day diagnostic bundle")
                appendLine("Version: "+appVersion)
                appendLine("Generated IST: "+now)
                appendLine("Purpose: reproduce scanner/scheduler/learning behaviour for debugging.")
                appendLine("SECURITY: Groww credentials, TOTP secret and access token are intentionally NOT exported.")
                appendLine()
                appendLine("state-report.txt = persisted engine/scheduler/call/learning state")
                appendLine("learning-report.txt = accuracy/calibration/walk-forward/autopsy/shadow summary")
                appendLine("global-edge-diagnostics.log = current runtime timeline")
                appendLine("global-edge-diagnostics.previous.log = previous rotated runtime timeline when present")
            })
            add("state-report.txt",stateReport)
            add("learning-report.txt",learningReport)
            val old=File(context.filesDir,"global-edge-diagnostics.previous.log")
            val current=file(context)
            if(old.exists()) add("global-edge-diagnostics.previous.log",old.readText())
            for(i in MAX_ARCHIVES downTo 1){val f=archive(context,i);if(f.exists())add("global-edge-diagnostics.$i.log",f.readText())}
            if(current.exists()) add("global-edge-diagnostics.log",current.readText())
        }
    }

    fun writeCentralizedBundle(
        context: Context,
        output: OutputStream,
        appVersion: String,
        stateReport: String,
        learningReport: String
    ) {
        val now=ZonedDateTime.now(ZoneId.of("Asia/Kolkata"))
        ZipOutputStream(output.buffered()).use { zip ->
            fun add(name:String,text:String){
                zip.putNextEntry(ZipEntry(name))
                zip.write(text.toByteArray(Charsets.UTF_8))
                zip.closeEntry()
            }
            add("README.txt", buildString {
                appendLine("Global Edge AI Trader centralized log export")
                appendLine("Version: "+appVersion)
                appendLine("Generated IST: "+now)
                appendLine("Use this same ZIP for daily, weekly or monthly review; retained logs/history determine the available lookback.")
                appendLine("SECURITY: Groww credentials, TOTP secret and access token are intentionally NOT exported.")
                appendLine()
                appendLine("state-report.txt = persisted scheduler/scanner/call/strategy/global/broker state")
                appendLine("learning-report.txt = accuracy/calibration/walk-forward/autopsy/shadow learning")
                appendLine("global-edge-diagnostics.log = current runtime timeline")
                appendLine("global-edge-diagnostics.1..6.log = retained rotated timelines for longer weekly/monthly review")
            })
            add("state-report.txt",stateReport)
            add("learning-report.txt",learningReport)
            val old=File(context.filesDir,"global-edge-diagnostics.previous.log")
            val current=file(context)
            if(old.exists())add("global-edge-diagnostics.previous.log",old.readText())
            for(i in MAX_ARCHIVES downTo 1){val f=archive(context,i);if(f.exists())add("global-edge-diagnostics.$i.log",f.readText())}
            if(current.exists())add("global-edge-diagnostics.log",current.readText())
        }
    }

}
