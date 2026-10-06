package com.suhas.globaledgeai.data.local

import android.content.Context
import android.content.Intent
import android.net.Uri
import org.json.JSONArray
import org.json.JSONObject

class PersistentLearningVault(private val context:Context){
    private val control=context.getSharedPreferences(CONTROL_PREFS,Context.MODE_PRIVATE)

    fun configured():Boolean=configuredUri().isNotBlank()
    fun configuredUri():String=control.getString(KEY_URI,"").orEmpty()
    fun lastBackupAt():Long=control.getLong(KEY_LAST_BACKUP_AT,0L)
    fun lastRestoreAt():Long=control.getLong(KEY_LAST_RESTORE_AT,0L)

    fun configure(uri:Uri):Int{
        persistGrant(uri)
        control.edit().putString(KEY_URI,uri.toString()).apply()
        return backupNow()
    }

    fun restoreAndConfigure(uri:Uri):Int{
        persistGrant(uri)
        val raw=context.contentResolver.openInputStream(uri)?.bufferedReader(Charsets.UTF_8)?.use{it.readText()}
            ?:error("Unable to read selected learning vault")
        val root=JSONObject(raw)
        require(root.optInt("schemaVersion")==SCHEMA_VERSION){"Unsupported learning-vault schema"}
        val stores=root.optJSONObject("stores")?:error("Learning vault has no stores")
        var restored=0
        for(storeName in STORES){
            val payload=stores.optJSONObject(storeName)?:continue
            val prefs=context.getSharedPreferences(storeName,Context.MODE_PRIVATE)
            val editor=prefs.edit().clear()
            val keys=payload.keys()
            while(keys.hasNext()){
                val key=keys.next()
                if(excluded(storeName,key))continue
                val item=payload.optJSONObject(key)?:continue
                when(item.optString("type")){
                    "string"->editor.putString(key,item.optString("value"))
                    "int"->editor.putInt(key,item.optInt("value"))
                    "long"->editor.putLong(key,item.optLong("value"))
                    "float"->editor.putFloat(key,item.optDouble("value").toFloat())
                    "boolean"->editor.putBoolean(key,item.optBoolean("value"))
                    "string_set"->{
                        val a=item.optJSONArray("value")?:JSONArray()
                        val values=buildSet{for(i in 0 until a.length())add(a.optString(i))}
                        editor.putStringSet(key,values)
                    }
                    else->continue
                }
                restored++
            }
            editor.apply()
        }
        context.getSharedPreferences("global_edge_ai_prefs",Context.MODE_PRIVATE).edit()
            .putBoolean("multify_live_trading_enabled",false)
            .remove("multify_live_arm_date")
            .apply()
        context.getSharedPreferences("global_edge_multify_events",Context.MODE_PRIVATE).edit()
            .remove("trusted_multify_package_v168")
            .apply()
        control.edit().putString(KEY_URI,uri.toString())
            .putLong(KEY_LAST_RESTORE_AT,System.currentTimeMillis())
            .apply()
        return restored
    }

    fun backupIfDue(minIntervalMs:Long=2L*60_000L):Int{
        val now=System.currentTimeMillis()
        if(!configured()||now-lastBackupAt()<minIntervalMs)return 0
        return backupNow()
    }

    fun backupNow():Int{
        val uriText=configuredUri()
        if(uriText.isBlank())return 0
        val uri=Uri.parse(uriText)
        val stores=JSONObject()
        var count=0
        for(storeName in STORES){
            val prefs=context.getSharedPreferences(storeName,Context.MODE_PRIVATE)
            val payload=JSONObject()
            prefs.all.toSortedMap().forEach{(key,value)->
                if(excluded(storeName,key))return@forEach
                val item=JSONObject()
                when(value){
                    is String->{item.put("type","string");item.put("value",value)}
                    is Int->{item.put("type","int");item.put("value",value)}
                    is Long->{item.put("type","long");item.put("value",value)}
                    is Float->{item.put("type","float");item.put("value",value.toDouble())}
                    is Boolean->{item.put("type","boolean");item.put("value",value)}
                    is Set<*>->{
                        item.put("type","string_set")
                        val a=JSONArray()
                        value.filterIsInstance<String>().sorted().forEach{a.put(it)}
                        item.put("value",a)
                    }
                    else->return@forEach
                }
                payload.put(key,item)
                count++
            }
            stores.put(storeName,payload)
        }
        val root=JSONObject()
            .put("schemaVersion",SCHEMA_VERSION)
            .put("packageName",context.packageName)
            .put("generatedAt",System.currentTimeMillis())
            .put("containsCredentials",false)
            .put("containsLiveOrderArm",false)
            .put("stores",stores)
        context.contentResolver.openOutputStream(uri,"wt")?.bufferedWriter(Charsets.UTF_8)?.use{writer->
            writer.write(root.toString())
        }?:error("Unable to write selected learning vault")
        control.edit().putLong(KEY_LAST_BACKUP_AT,System.currentTimeMillis()).apply()
        return count
    }

    private fun persistGrant(uri:Uri){
        val flags=Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
        context.contentResolver.takePersistableUriPermission(uri,flags)
    }

    private fun excluded(store:String,key:String):Boolean{
        val k=key.lowercase()
        if(k.contains("access_token")||k.contains("password")||k.contains("credential")||k.contains("totp_secret"))return true
        if(store=="global_edge_ai_prefs"&&(key=="multify_live_trading_enabled"||key=="multify_live_arm_date"))return true
        if(store=="global_edge_multify_events"&&key=="trusted_multify_package_v168")return true
        return false
    }

    companion object{
        private const val SCHEMA_VERSION=1
        private const val CONTROL_PREFS="global_edge_learning_vault_control_v171"
        private const val KEY_URI="vault_uri"
        private const val KEY_LAST_BACKUP_AT="last_backup_at"
        private const val KEY_LAST_RESTORE_AT="last_restore_at"
        private val STORES=listOf(
            "global_edge_ai_prefs",
            "global_edge_multify_trading_v164",
            "global_edge_multify_events"
        )
    }
}
