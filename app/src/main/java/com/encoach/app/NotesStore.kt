package com.encoach.app

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * 笔记本：直接把笔记存成应用私有目录下的 JSON 文件，不依赖任何数据库。
 * 结构简单、可读，坏了删掉文件即可重来。
 */
object NotesStore {

    private const val FILE_NAME = "notes.json"

    data class Note(
        val id: Long,
        val time: Long,
        val mode: String,
        val source: String,
        val content: String
    )

    private fun file(context: Context): File = File(context.filesDir, FILE_NAME)

    fun load(context: Context): MutableList<Note> {
        val target = file(context)
        if (!target.exists()) return mutableListOf()
        return try {
            val array = JSONArray(target.readText())
            val result = mutableListOf<Note>()
            for (i in 0 until array.length()) {
                val item = array.optJSONObject(i) ?: continue
                result.add(
                    Note(
                        id = item.optLong("id"),
                        time = item.optLong("time"),
                        mode = item.optString("mode", ""),
                        source = item.optString("source", ""),
                        content = item.optString("content", "")
                    )
                )
            }
            result
        } catch (t: Throwable) {
            mutableListOf()
        }
    }

    fun add(context: Context, mode: String, source: String, content: String) {
        val list = load(context)
        val note = Note(
            id = System.currentTimeMillis(),
            time = System.currentTimeMillis(),
            mode = mode,
            source = source,
            content = content
        )
        list.add(0, note)
        save(context, list)
    }

    fun delete(context: Context, id: Long) {
        val list = load(context)
        list.removeAll { it.id == id }
        save(context, list)
    }

    private fun save(context: Context, list: MutableList<Note>) {
        val array = JSONArray()
        for (note in list) {
            array.put(
                JSONObject()
                    .put("id", note.id)
                    .put("time", note.time)
                    .put("mode", note.mode)
                    .put("source", note.source)
                    .put("content", note.content)
            )
        }
        try {
            file(context).writeText(array.toString())
        } catch (t: Throwable) {
            // 存储失败就算了，不打断使用
        }
    }
}
