package com.encoach.app

import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.os.Bundle
import android.os.SystemClock
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.Button
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast

/**
 * 选词入口：其他 App 选中文字后，从系统选择菜单点「英文老师」进到这里。
 * 对话框样式，半屏卡片，关闭即回到原 App。
 */
class ProcessTextActivity : Activity() {

    private lateinit var resultView: TextView
    private lateinit var statusView: TextView
    private lateinit var previewView: TextView
    private lateinit var scrollView: ScrollView
    private lateinit var tabWord: TextView
    private lateinit var tabSentence: TextView
    private lateinit var tabCheck: TextView
    private lateinit var client: DeepSeekClient

    private val buffer = StringBuilder()
    private var sourceText: String = ""
    private var mode: Mode = Mode.WORD
    private var firstDelta = true
    private var lastFlush = 0L

    private val listener = object : DeepSeekClient.Listener {
        override fun onDelta(delta: String) {
            buffer.append(delta)
            val now = SystemClock.uptimeMillis()
            if (firstDelta || now - lastFlush >= 60L) {
                firstDelta = false
                lastFlush = now
                resultView.text = render(buffer.toString())
                scrollView.post { scrollView.fullScroll(View.FOCUS_DOWN) }
            }
        }

        override fun onFinish(full: String) {
            buffer.setLength(0)
            buffer.append(full)
            resultView.text = render(full)
            statusView.text = getString(R.string.status_done)
        }

        override fun onError(message: String) {
            buffer.setLength(0)
            statusView.text = getString(R.string.status_error)
            resultView.text = message
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_process_text)
        client = DeepSeekClient()
        applyWindowStyle()

        resultView = findViewById(R.id.result)
        statusView = findViewById(R.id.status)
        previewView = findViewById(R.id.preview)
        scrollView = findViewById(R.id.resultScroll)
        tabWord = findViewById(R.id.tabWord)
        tabSentence = findViewById(R.id.tabSentence)
        tabCheck = findViewById(R.id.tabCheck)
        Typefaces.body(this)?.let { typeface -> resultView.typeface = typeface }

        resultView.setOnLongClickListener {
            copyAll()
            true
        }
        findViewById<Button>(R.id.btnRegenerate).setOnClickListener { generate() }
        findViewById<Button>(R.id.btnCopy).setOnClickListener { copyAll() }
        findViewById<Button>(R.id.btnClose).setOnClickListener { finish() }

        tabWord.setOnClickListener { switchMode(Mode.WORD) }
        tabSentence.setOnClickListener { switchMode(Mode.SENTENCE) }
        tabCheck.setOnClickListener { switchMode(Mode.AUTO) }

        loadSelection(intent)
    }

    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        if (intent != null) {
            setIntent(intent)
            client.cancel()
            loadSelection(intent)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        if (this::client.isInitialized) {
            client.close()
        }
    }

    // ---------------------------------------------------------------- 入口逻辑

    private fun loadSelection(intent: Intent?) {
        val raw = intent?.getCharSequenceExtra(Intent.EXTRA_PROCESS_TEXT)
        sourceText = raw?.toString()?.trim().orEmpty()
        previewView.text = getString(R.string.selection_prefix, sourceText)
        buffer.setLength(0)
        resultView.text = ""

        if (sourceText.isBlank()) {
            resultView.text = getString(R.string.err_no_text)
            statusView.text = ""
            return
        }

        // 含中文 → 功能 3（汉译英）；纯英文 → 功能 1（生词解析）
        val initial = if (containsChinese(sourceText)) Mode.AUTO else Mode.WORD
        switchMode(initial)
    }

    private fun switchMode(newMode: Mode) {
        mode = newMode
        tabWord.isSelected = newMode == Mode.WORD
        tabSentence.isSelected = newMode == Mode.SENTENCE
        tabCheck.isSelected = newMode == Mode.AUTO
        generate()
    }

    private fun generate() {
        if (sourceText.isBlank()) return
        if (!languageCompatible()) return

        val settings = Prefs.load(this)
        if (!settings.hasKey) {
            resultView.text = getString(R.string.err_no_key_card)
            statusView.text = ""
            return
        }

        buffer.setLength(0)
        firstDelta = true
        resultView.text = getString(R.string.loading)
        statusView.text = getString(R.string.status_generating)
        client.submit(
            settings.apiKey,
            settings.baseUrl,
            settings.model,
            Prompts.build(mode, sourceText),
            listener
        )
    }

    private fun languageCompatible(): Boolean {
        val english = containsLatin(sourceText)
        val chinese = containsChinese(sourceText)

        if (mode == Mode.WORD && !english) {
            resultView.text = getString(R.string.card_hint_chinese_word)
            statusView.text = ""
            return false
        }
        if (mode == Mode.SENTENCE && !english) {
            resultView.text = getString(R.string.card_hint_chinese_sentence)
            statusView.text = ""
            return false
        }
        if (mode == Mode.AUTO && !english && !chinese) {
            resultView.text = getString(R.string.err_no_text)
            statusView.text = ""
            return false
        }
        return true
    }

    // ---------------------------------------------------------------- 界面辅助

    private fun applyWindowStyle() {
        val dialogWindow = window ?: return
        dialogWindow.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
        dialogWindow.addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
        dialogWindow.setDimAmount(0.30f)
        dialogWindow.setGravity(Gravity.BOTTOM)
        dialogWindow.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)

        val metrics = resources.displayMetrics
        val height = (metrics.heightPixels * 0.64f).toInt()
            .coerceAtMost(metrics.heightPixels - 160)
            .coerceAtLeast(480)
        dialogWindow.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, height)
    }

    private fun copyAll() {
        val text = Markdown.toPlain(buffer.toString().ifEmpty { resultView.text.toString() })
        if (text.isBlank()) return
        val manager = getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager ?: return
        manager.setPrimaryClip(ClipData.newPlainText(getString(R.string.app_name), text))
        Toast.makeText(this, R.string.copied, Toast.LENGTH_SHORT).show()
    }

    private fun render(text: String): CharSequence =
        Markdown.render(text, getColor(R.color.accent), getColor(R.color.text_secondary))

    private fun containsChinese(text: String): Boolean = text.any { it in '\u4e00'..'\u9fff' }

    private fun containsLatin(text: String): Boolean =
        text.any { (it in 'a'..'z') || (it in 'A'..'Z') }
}
