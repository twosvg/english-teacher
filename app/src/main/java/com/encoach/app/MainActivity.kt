package com.encoach.app

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import android.provider.Settings
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.view.inputmethod.InputMethodManager
import android.widget.Button
import android.widget.EditText
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast

class MainActivity : Activity() {

    private lateinit var inputView: EditText
    private lateinit var resultView: TextView
    private lateinit var statusView: TextView
    private lateinit var scrollView: ScrollView
    private lateinit var stopButton: Button
    private lateinit var client: DeepSeekClient

    private val buffer = StringBuilder()
    private var lastFlush = 0L
    private var pendingFloatingStart = false
    private val requestNotificationCode = 1001

    private val listener = object : DeepSeekClient.Listener {
        override fun onDelta(delta: String) {
            buffer.append(delta)
            // 每 60ms 刷新一次界面，避免每个 token 都重排一次 TextView
            val now = SystemClock.uptimeMillis()
            if (now - lastFlush >= 60L) {
                lastFlush = now
                resultView.text = buffer.toString()
                scrollView.post { scrollView.fullScroll(View.FOCUS_DOWN) }
            }
        }

        override fun onFinish(full: String) {
            buffer.setLength(0)
            buffer.append(full)
            resultView.text = full
            setRunning(false)
            statusView.text = getString(R.string.status_done)
        }

        override fun onError(message: String) {
            buffer.setLength(0)
            setRunning(false)
            statusView.text = getString(R.string.status_error)
            resultView.text = message
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        title = getString(R.string.app_name)

        inputView = findViewById(R.id.input)
        resultView = findViewById(R.id.result)
        statusView = findViewById(R.id.status)
        scrollView = findViewById(R.id.resultScroll)
        stopButton = findViewById(R.id.btnStop)
        client = DeepSeekClient()

        findViewById<Button>(R.id.btnWord).setOnClickListener { start(Mode.WORD) }
        findViewById<Button>(R.id.btnSentence).setOnClickListener { start(Mode.SENTENCE) }
        findViewById<Button>(R.id.btnTranslate).setOnClickListener { start(Mode.TRANSLATE) }
        findViewById<Button>(R.id.btnPolish).setOnClickListener { start(Mode.POLISH) }
        findViewById<Button>(R.id.btnFloating).setOnClickListener { toggleFloating() }
        findViewById<Button>(R.id.btnNotes).setOnClickListener {
            startActivity(Intent(this, NotesActivity::class.java))
        }

        stopButton.setOnClickListener {
            client.cancel()
            setRunning(false)
            statusView.text = getString(R.string.status_stopped)
        }

        resultView.setOnLongClickListener {
            copyResult()
            true
        }
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.main_menu, menu)
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        if (item.itemId == R.id.action_settings) {
            startActivity(Intent(this, SettingsActivity::class.java))
            return true
        }
        return super.onOptionsItemSelected(item)
    }

    override fun onDestroy() {
        super.onDestroy()
        if (this::client.isInitialized) {
            client.close()
        }
    }

    override fun onResume() {
        super.onResume()
        if (pendingFloatingStart && Settings.canDrawOverlays(this)) {
            pendingFloatingStart = false
            askNotificationPermissionIfNeeded()
            FloatingService.start(this)
            toast(R.string.floating_started)
        }
    }

    private fun toggleFloating() {
        if (FloatingService.isRunning) {
            FloatingService.stop(this)
            toast(R.string.floating_stopped)
            return
        }
        if (!Settings.canDrawOverlays(this)) {
            AlertDialog.Builder(this)
                .setTitle(R.string.overlay_permission_title)
                .setMessage(R.string.overlay_permission_message)
                .setPositiveButton(R.string.overlay_permission_open) { _, _ ->
                    pendingFloatingStart = true
                    try {
                        startActivity(
                            Intent(
                                Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                                Uri.parse("package:" + packageName)
                            )
                        )
                    } catch (t: Throwable) {
                        startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION))
                    }
                }
                .setNegativeButton(R.string.settings_cancel, null)
                .show()
            return
        }
        askNotificationPermissionIfNeeded()
        FloatingService.start(this)
        toast(R.string.floating_started)
    }

    private fun askNotificationPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
        if (checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED) return
        requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), requestNotificationCode)
    }

    private fun start(mode: Mode) {
        val text = inputView.text.toString().trim()
        if (text.isEmpty()) {
            toast(R.string.err_empty_input)
            return
        }
        if (!checkLanguage(mode, text)) return

        val settings = Prefs.load(this)
        if (!settings.hasKey) {
            toast(R.string.err_no_key)
            startActivity(Intent(this, SettingsActivity::class.java))
            return
        }

        hideKeyboard()
        buffer.setLength(0)
        resultView.text = ""
        setRunning(true)
        client.submit(
            settings.apiKey,
            settings.baseUrl,
            settings.model,
            Prompts.build(mode, text),
            listener
        )
    }

    private fun setRunning(running: Boolean) {
        stopButton.visibility = if (running) View.VISIBLE else View.GONE
        statusView.text = getString(if (running) R.string.status_generating else R.string.status_ready)
    }

    private fun checkLanguage(mode: Mode, text: String): Boolean {
        val hasChinese = text.any { it in '\u4e00'..'\u9fff' }
        val hasLatin = text.any { (it in 'a'..'z') || (it in 'A'..'Z') }
        return when (mode) {
            Mode.TRANSLATE -> {
                if (hasChinese || hasLatin) {
                    true
                } else {
                    toast(R.string.err_need_chinese)
                    false
                }
            }

            Mode.WORD, Mode.SENTENCE, Mode.POLISH -> {
                if (hasLatin) {
                    true
                } else {
                    toast(R.string.err_need_english)
                    false
                }
            }

            Mode.AUTO -> true
        }
    }

    private fun copyResult() {
        val text = buffer.toString().ifEmpty { resultView.text.toString() }
        if (text.isBlank()) {
            toast(R.string.err_nothing_to_copy)
            return
        }
        val manager = getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
        if (manager == null) {
            toast(R.string.err_nothing_to_copy)
            return
        }
        manager.setPrimaryClip(ClipData.newPlainText(getString(R.string.app_name), text))
        toast(R.string.copied)
    }

    private fun hideKeyboard() {
        val manager = getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager ?: return
        manager.hideSoftInputFromWindow(inputView.windowToken, 0)
    }

    private fun toast(resId: Int) {
        Toast.makeText(this, resId, Toast.LENGTH_SHORT).show()
    }
}
