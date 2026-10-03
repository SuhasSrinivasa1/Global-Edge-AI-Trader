package com.suhas.globaledgeai.data.local

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import com.suhas.globaledgeai.domain.model.AuthMode
import com.suhas.globaledgeai.domain.model.Credentials
import java.security.KeyStore

class SecureCredentialStore(context: Context) {
    companion object {
        private const val TAG="GlobalEdge/SecureStore"
        // v1.5.2 intentionally uses a new encrypted store and a new Android Keystore alias.
        // This bypasses any corrupted legacy Tink keyset/master-key pair without touching
        // strategy history, learning ledgers, settings or other app preferences.
        private const val PREF_FILE_V2="global_edge_ai_secure_v152"
        private const val MASTER_KEY_ALIAS_V2="_global_edge_master_key_v152_"
    }

    private val appContext=context.applicationContext

    private fun createV2():SharedPreferences{
        val masterKey=MasterKey.Builder(appContext, MASTER_KEY_ALIAS_V2)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()
        return EncryptedSharedPreferences.create(
            appContext,
            PREF_FILE_V2,
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
        )
    }

    private fun resetV2AndCreate():SharedPreferences{
        Log.w(TAG,"Resetting v1.5.2 encrypted credential slot; non-secret app data is preserved.")
        runCatching{appContext.deleteSharedPreferences(PREF_FILE_V2)}
        runCatching{
            val ks=KeyStore.getInstance("AndroidKeyStore")
            ks.load(null)
            if(ks.containsAlias(MASTER_KEY_ALIAS_V2))ks.deleteEntry(MASTER_KEY_ALIAS_V2)
        }.onFailure{Log.e(TAG,"Unable to reset v1.5.2 Android Keystore alias",it)}
        return createV2()
    }

    private val prefs:SharedPreferences=try{
        createV2()
    }catch(first:Throwable){
        Log.e(TAG,"Primary v1.5.2 credential slot failed; rebuilding only the secure credential slot.",first)
        resetV2AndCreate()
    }

    init{
        Log.i(TAG,"Secure credential store v1.5.2 initialized. Legacy encrypted credentials are intentionally not opened.")
    }

    fun saveCredentials(credentials: Credentials) {
        prefs.edit()
            .putString("auth_mode", credentials.mode.name)
            .putString("api_key", credentials.apiKeyOrTotpToken)
            .putString("secret", credentials.secret)
            .apply()
    }

    fun loadCredentials(): Credentials {
        val mode=runCatching{
            AuthMode.valueOf(prefs.getString("auth_mode",AuthMode.TOTP.name)?:AuthMode.TOTP.name)
        }.getOrDefault(AuthMode.TOTP)
        return Credentials(
            mode=mode,
            apiKeyOrTotpToken=prefs.getString("api_key","")?:"",
            secret=prefs.getString("secret","")?:""
        )
    }

    fun saveAccessToken(token:String,expiry:String){
        prefs.edit()
            .putString("access_token",token)
            .putString("access_token_expiry",expiry)
            .putLong("access_token_saved_at",System.currentTimeMillis())
            .apply()
    }

    fun accessToken():String=prefs.getString("access_token","")?:""
    fun accessTokenExpiry():String=prefs.getString("access_token_expiry","")?:""
    fun accessTokenSavedAt():Long=prefs.getLong("access_token_saved_at",0L)

    fun clearAccessToken(){
        prefs.edit().remove("access_token").remove("access_token_expiry").remove("access_token_saved_at").apply()
    }
}
