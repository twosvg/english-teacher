package com.encoach.app

import android.app.Activity
import android.app.AlertDialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Bundle
import android.text.format.DateFormat
import android.view.View
import android.widget.ListView
import android.widget.ScrollView
import android.widget.SimpleAdapter
import android.widget.TextView
import android.widget.Toast

/**
 * 笔记本：列出保存过的解析结果，点开看全文，长按删除。
 */
class NotesActivity : Activity() {

    private lateinit var listView: ListView
    private lateinit var emptyView: TextView
    private var notes: MutableList<NotesStore.Note> = mutableListOf()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_notes)
        title = getString(R.string.notes_title)

        listView = findViewById(R.id.notesList)
        emptyView = findViewById(R.id.notesEmpty)

        listView.setOnItemClickListener { _, _, position, _ ->
            if (position in notes.indices) showNote(notes[position])
        }
        listView.setOnItemLongClickListener { _, _, position, _ ->
            if (position in notes.indices) confirmDelete(notes[position])
            true
        }

        refresh()
    }

    override fun onResume() {
        super.onResume()
        refresh()
    }

    private fun refresh() {
        notes = NotesStore.load(this)

        val rows = ArrayList<Map<String, String>>()
        for (note in notes) {
            val row = HashMap<String, String>()
            row["title"] = modeLabel(note.mode) + " · " + DateFormat.format("MM-dd HH:mm", note.time).toString()
            row["sub"] = oneLine(note.source)
            rows.add(row)
        }

        val adapter = SimpleAdapter(
            this,
            rows,
            android.R.layout.simple_list_item_2,
            arrayOf("title", "sub"),
            intArrayOf(android.R.id.text1, android.R.id.text2)
        )
        listView.adapter = adapter
        emptyView.visibility = if (notes.isEmpty()) View.VISIBLE else View.GONE
    }

    private fun showNote(note: NotesStore.Note) {
        val scroll = ScrollView(this)
        val text = TextView(this)
        text.text = note.content
        text.setTextIsSelectable(true)
        text.textSize = 15f
        text.setLineSpacing(dp(4).toFloat(), 1f)
        text.setPadding(dp(18), dp(14), dp(18), dp(14))
        scroll.addView(text)

        AlertDialog.Builder(this)
            .setTitle(R.string.notes_detail_title)
            .setView(scroll)
            .setPositiveButton(R.string.action_copy) { _, _ -> copyText(note.content) }
            .setNegativeButton(R.string.action_close, null)
            .show()
    }

    private fun confirmDelete(note: NotesStore.Note) {
        AlertDialog.Builder(this)
            .setTitle(R.string.notes_delete)
            .setMessage(R.string.notes_delete_confirm)
            .setPositiveButton(R.string.notes_delete) { _, _ ->
                NotesStore.delete(this, note.id)
                refresh()
            }
            .setNegativeButton(R.string.settings_cancel, null)
            .show()
    }

    private fun copyText(text: String) {
        val manager = getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager ?: return
        manager.setPrimaryClip(ClipData.newPlainText(getString(R.string.app_name), text))
        Toast.makeText(this, R.string.copied, Toast.LENGTH_SHORT).show()
    }

    private fun modeLabel(mode: String): String = when (mode) {
        "WORD" -> getString(R.string.btn_word)
        "SENTENCE" -> getString(R.string.btn_sentence)
        "TRANSLATE" -> getString(R.string.btn_translate)
        "POLISH" -> getString(R.string.btn_polish)
        else -> getString(R.string.btn_word)
    }

    private fun oneLine(text: String): String {
        val clean = text.replace("\n", " ").trim()
        return if (clean.length > 42) clean.substring(0, 42) + "…" else clean
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()
}
