package com.githubclient.app.util

import android.content.Context
import android.net.Uri
import android.util.Log
import com.google.gson.Gson
import com.google.gson.JsonObject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File

/**
 * 插件数据模型。
 *
 * 插件格式（JSON），仅支持翻译插件：
 * {
 *   "type": "translation",
 *   "name": "英译中增强版",
 *   "author": "用户名",
 *   "version": "1.0",
 *   "enabled": true,
 *   "dictionary": {
 *     "Commit": "提交",
 *     "Push": "推送",
 *     "Pull": "拉取",
 *     "Merge": "合并",
 *     "Build": "构建",
 *     "Deploy": "部署"
 *   }
 * }
 *
 * 用户制作插件后上传，词典会自动合并到内置词典中，
 * 翻译插件开启后所有界面英文会自动翻译为中文。
 */
data class Plugin(
    val id: String = "",            // 文件名（不含.json）
    val type: String = "",          // 固定为 "translation"
    val name: String = "",
    val author: String = "",
    val version: String = "1.0",
    val enabled: Boolean = true,
    // 翻译插件的词典
    val dictionary: Map<String, String> = emptyMap(),
)

/**
 * 插件管理器。
 * 用户可以制作翻译插件 JSON 文件并上传到 APP 中使用。
 * 上传后词典会自动合并到内置词典，立即生效。
 */
object PluginManager {

    private const val TAG = "PluginManager"
    private const val PLUGIN_DIR = "plugins"
    private val gson = Gson()

    private var pluginDir: File? = null

    private val _plugins = MutableStateFlow<List<Plugin>>(emptyList())
    val plugins: StateFlow<List<Plugin>> = _plugins.asStateFlow()

    fun init(context: Context) {
        pluginDir = File(context.filesDir, PLUGIN_DIR).apply { mkdirs() }
        loadPlugins()
    }

    // ===== 加载所有已安装的插件 =====
    private fun loadPlugins() {
        val dir = pluginDir ?: return
        val list = mutableListOf<Plugin>()

        dir.listFiles { f -> f.isFile && f.extension == "json" }?.forEach { file ->
            try {
                val json = file.readText()
                val obj = gson.fromJson(json, JsonObject::class.java) ?: return@forEach

                val type = obj.get("type")?.asString ?: return@forEach
                val name = obj.get("name")?.asString ?: file.nameWithoutExtension
                val author = obj.get("author")?.asString ?: "unknown"
                val version = obj.get("version")?.asString ?: "1.0"
                val enabled = if (obj.has("enabled")) obj.get("enabled").asBoolean else true

                // 仅支持翻译插件
                if (type != "translation") return@forEach

                val dictJson = obj.getAsJsonObject("dictionary")
                val dict = mutableMapOf<String, String>()
                dictJson?.entrySet()?.forEach { (key, value) ->
                    dict[key] = value.asString
                }

                list.add(Plugin(
                    id = file.nameWithoutExtension,
                    type = type,
                    name = name,
                    author = author,
                    version = version,
                    enabled = enabled,
                    dictionary = dict,
                ))
            } catch (e: Exception) {
                Log.w(TAG, "加载插件 ${file.name} 失败: ${e.message}")
            }
        }

        _plugins.value = list
        Log.d(TAG, "已加载 ${list.size} 个翻译插件")

        // 通知翻译插件重新加载合并词典
        TranslationPlugin.reloadDictionary()
    }

    // ===== 从 Uri 安装插件 =====
    fun installFromUri(context: Context, uri: Uri): Boolean {
        return try {
            val json = context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
                ?.let { String(it) } ?: return false

            val obj = gson.fromJson(json, JsonObject::class.java) ?: return false
            val type = obj.get("type")?.asString ?: return false
            val name = obj.get("name")?.asString ?: return false

            // 仅支持翻译插件
            if (type != "translation") return false

            // 验证词典是否有效
            val dictJson = obj.getAsJsonObject("dictionary")
            if (dictJson == null || dictJson.size() == 0) return false

            // 生成文件名
            val fileName = "${type}_${name.replace(Regex("[^a-zA-Z0-9]"), "_")}.json"
            val file = File(pluginDir, fileName)
            file.writeText(json)

            loadPlugins()
            Log.d(TAG, "插件安装成功: $name (词典 ${dictJson.size()} 条)")
            true
        } catch (e: Exception) {
            Log.e(TAG, "安装插件失败: ${e.message}")
            false
        }
    }

    // ===== 卸载插件 =====
    fun uninstall(pluginId: String) {
        val file = File(pluginDir, "$pluginId.json")
        if (file.exists()) file.delete()
        loadPlugins()
    }

    // ===== 启用/禁用 =====
    fun toggleEnabled(pluginId: String) {
        val dir = pluginDir ?: return
        val file = File(dir, "$pluginId.json")
        if (!file.exists()) return

        try {
            val json = file.readText()
            val obj = gson.fromJson(json, JsonObject::class.java) ?: return

            val currentlyEnabled = if (obj.has("enabled")) obj.get("enabled").asBoolean else true
            obj.addProperty("enabled", !currentlyEnabled)

            file.writeText(gson.toJson(obj))
            loadPlugins()
        } catch (e: Exception) {
            Log.e(TAG, "切换插件状态失败: ${e.message}")
        }
    }

    // ===== 获取翻译词典（合并所有启用的翻译插件） =====
    fun getTranslationDictionary(): Map<String, String> {
        val result = mutableMapOf<String, String>()
        _plugins.value
            .filter { it.type == "translation" && it.enabled }
            .forEach { plugin -> result.putAll(plugin.dictionary) }
        return result
    }

    // ===== 统计 =====
    fun getTranslationPluginCount(): Int =
        _plugins.value.count { it.type == "translation" && it.enabled }
}
