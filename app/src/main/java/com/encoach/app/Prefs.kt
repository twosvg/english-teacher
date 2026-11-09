package com.encoach.app

import android.content.Context

/**
 * 本机设置。
 *
 * API Key 只保存在应用私有的 SharedPreferences 里（/data/data/com.encoach.app/...），
 * 应用已关闭 allowBackup，所以不会随系统备份上传到云端。
 */
object Prefs {

    private const val FILE_NAME = "encoach_settings"
    private const val KEY_API_KEY = "api_key"
    private const val KEY_BASE_URL = "base_url"
    private const val KEY_MODEL = "model"

    const val DEFAULT_BASE_URL = "https://api.deepseek.com"
    const val DEFAULT_MODEL = "deepseek-chat"

    data class Settings(
        val apiKey: String,
        val baseUrl: String,
        val model: String
    ) {
        val hasKey: Boolean
            get() = apiKey.isNotBlank()
    }

    fun load(context: Context): Settings {
        val sp = context.applicationContext
            .getSharedPreferences(FILE_NAME, Context.MODE_PRIVATE)
        val apiKey = sp.getString(KEY_API_KEY, "").orEmpty().trim()
        val baseUrl = sp.getString(KEY_BASE_URL, DEFAULT_BASE_URL).orEmpty().trim()
        val model = sp.getString(KEY_MODEL, DEFAULT_MODEL).orEmpty().trim()
        return Settings(
            apiKey = apiKey,
            baseUrl = if (baseUrl.isEmpty()) DEFAULT_BASE_URL else baseUrl,
            model = if (model.isEmpty()) DEFAULT_MODEL else model
        )
    }

    fun save(context: Context, apiKey: String, baseUrl: String, model: String) {
        context.applicationContext
            .getSharedPreferences(FILE_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_API_KEY, apiKey.trim())
            .putString(KEY_BASE_URL, if (baseUrl.isBlank()) DEFAULT_BASE_URL else baseUrl.trim())
            .putString(KEY_MODEL, if (model.isBlank()) DEFAULT_MODEL else model.trim())
            .apply()
    }
}
