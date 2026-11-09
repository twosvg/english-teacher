package com.encoach.app

import android.app.AlertDialog
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.content.res.Configuration
import android.graphics.PixelFormat
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import android.view.Gravity
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.EditText
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import kotlin.math.abs

/**
 * 悬浮球 + 悬浮面板。
 *
 * 用 TYPE_APPLICATION_OVERLAY 直接画在别的 App 上面，不切换应用：
 * 在 X 里选词复制 → 点悬浮球 → 面板从底部滑出 → 自动读剪贴板并解析。
 * 面板里可以切讲解/拆句/翻译/批改、继续追问、保存到笔记本。
 */
class FloatingService : Service() {

    companion object {
        const val ACTION_SHOW = "com.encoach.app.action.SHOW_FLOATING"
        const val ACTION_HIDE = "com.encoach.app.action.HIDE_FLOATING"
        private const val CHANNEL_ID = "encoach_floating"
        private const val NOTIFICATION_ID = 20241109

        @Volatile
        var isRunning: Boolean = false
            private set

        fun start(context: Context) {
            val intent = Intent(context, FloatingService::class.java).setAction(ACTION_SHOW)
            context.startForegroundService(intent)
        }

        fun stop(context: Context) {
            if (!isRunning) return
            val intent = Intent(context, FloatingService::class.java).setAction(ACTION_HIDE)
            context.startService(intent)
        }
    }

    private lateinit var windowManager: WindowManager
    private val main = Handler(Looper.getMainLooper())

    private var bubble: View? = null
    private var panel: View? = null

    private lateinit var client: DeepSeekClient
    private var session: CoachSession? = null

    private var resultView: TextView? = null
    private var sourceView: EditText? = null
    private var followView: EditText? = null
    private var scroll: ScrollView? = null

    private val transcript = StringBuilder()
    private var lastFlush = 0L
    private var currentMode = Mode.WORD
    private var currentText = ""

    private val sessionListener = object : CoachSession.Listener {
        override fun onAnswerStart() {
            // 清屏动作由调用方在 begin/regenerate 里做
        }

        override fun onDelta(delta: String) {
            transcript.append(delta)
            val now = SystemClock.uptimeMillis()
            if (now - lastFlush >= 60L) {
                lastFlush = now
                setResult(transcript.toString())
            }
        }

        override fun onAnswerDone(full: String) {
            setResult(transcript.toString())
        }

        override fun onAnswerError(message: String) {
            transcript.append("\n").append(message)
            setResult(transcript.toString())
        }
    }

    override fun onCreate() {
        super.onCreate()
        windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        client = DeepSeekClient()
        startAsForeground()
        isRunning = true
        if (Settings.canDrawOverlays(this)) {
            addBubble()
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_HIDE -> {
                hidePanel()
                removeBubble()
                stopForeground(true)
                stopSelf()
                return START_NOT_STICKY
            }

            else -> {
                if (!Settings.canDrawOverlays(this)) {
                    toast(getString(R.string.overlay_no_permission))
                    stopForeground(true)
                    stopSelf()
                    return START_NOT_STICKY
                }
                addBubble()
            }
        }
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        super.onDestroy()
        hidePanel()
        removeBubble()
        if (this::client.isInitialized) {
            client.close()
        }
        isRunning = false
    }

    // ---------------------------------------------------------------- 通知

    private fun startAsForeground() {
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val channel = NotificationChannel(
            CHANNEL_ID,
            getString(R.string.floating_channel),
            NotificationManager.IMPORTANCE_LOW
        )
        channel.description = getString(R.string.floating_channel_desc)
        manager.createNotificationChannel(channel)

        val openIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val closeIntent = PendingIntent.getService(
            this,
            1,
            Intent(this, FloatingService::class.java).setAction(ACTION_HIDE),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notification = Notification.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.floating_notification_title))
            .setContentText(getString(R.string.floating_notification_text))
            .setSmallIcon(R.drawable.ic_stat_teacher)
            .setOngoing(true)
            .setContentIntent(openIntent)
            .addAction(
                android.R.drawable.ic_menu_close_clear_cancel,
                getString(R.string.floating_notification_close),
                closeIntent
            )
            .build()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    // ---------------------------------------------------------------- 悬浮球

    private fun addBubble() {
        if (bubble != null) return
        val view = LayoutInflater.from(this).inflate(R.layout.view_overlay_bubble, null)
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        )
        params.gravity = Gravity.TOP or Gravity.START
        params.x = 18
        params.y = resources.displayMetrics.heightPixels / 3

        var downX = 0f
        var downY = 0f
        var startX = 0
        var startY = 0
        var dragged = false
        var downTime = 0L

        view.setOnTouchListener { v, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downX = event.rawX
                    downY = event.rawY
                    startX = params.x
                    startY = params.y
                    dragged = false
                    downTime = event.eventTime
                    true
                }

                MotionEvent.ACTION_MOVE -> {
                    val dx = event.rawX - downX
                    val dy = event.rawY - downY
                    if (abs(dx) > 16f || abs(dy) > 16f) dragged = true
                    if (dragged) {
                        params.x = startX + dx.toInt()
                        params.y = startY + dy.toInt()
                        try {
                            windowManager.updateViewLayout(v, params)
                        } catch (t: Throwable) {
                            // 忽略
                        }
                    }
                    true
                }

                MotionEvent.ACTION_UP -> {
                    if (!dragged) {
                        if (event.eventTime - downTime >= 500L) {
                            confirmClose()
                        } else {
                            showPanel()
                        }
                    }
                    true
                }

                else -> false
            }
        }

        try {
            windowManager.addView(view, params)
            bubble = view
        } catch (t: Throwable) {
            toast(getString(R.string.overlay_no_permission))
        }
    }

    private fun removeBubble() {
        val view = bubble
        bubble = null
        if (view != null) {
            try {
                windowManager.removeView(view)
            } catch (t: Throwable) {
                // 忽略
            }
        }
    }

    /** 长按小球：确认后关掉悬浮窗。 */
    private fun confirmClose() {
        val night = (resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) ==
            Configuration.UI_MODE_NIGHT_YES
        val themeRes = if (night) {
            android.R.style.Theme_Material_Dialog_Alert
        } else {
            android.R.style.Theme_Material_Light_Dialog_Alert
        }
        try {
            val dialog = AlertDialog.Builder(this, themeRes)
                .setTitle(R.string.bubble_close_title)
                .setMessage(R.string.bubble_close_message)
                .setPositiveButton(R.string.bubble_close_ok) { _, _ -> stopFloating() }
                .setNegativeButton(R.string.settings_cancel, null)
                .create()
            dialog.window?.setType(WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY)
            dialog.show()
        } catch (t: Throwable) {
            stopFloating()
        }
    }

    private fun stopFloating() {
        hidePanel()
        removeBubble()
        stopForeground(true)
        stopSelf()
    }

    /** 系统切深色/浅色时重建界面，否则颜色会停在旧主题。 */
    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        val hadPanel = panel != null
        val keptText = sourceView?.text?.toString().orEmpty()
        val keptResult = transcript.toString()

        if (hadPanel) hidePanel()
        removeBubble()
        if (Settings.canDrawOverlays(this)) addBubble()

        if (hadPanel && keptText.isNotBlank()) {
            main.postDelayed({
                showPanel(false)
                sourceView?.setText(keptText)
                transcript.setLength(0)
                transcript.append(keptResult)
                setResult(keptResult)
            }, 150)
        }
    }

    // ---------------------------------------------------------------- 悬浮面板

    private fun showPanel(autoRead: Boolean = true) {
        if (panel != null) return
        val view = LayoutInflater.from(this).inflate(R.layout.view_overlay_panel, null)
        val height = (resources.displayMetrics.heightPixels * 0.72f).toInt()
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            height,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
            PixelFormat.TRANSLUCENT
        )
        params.gravity = Gravity.BOTTOM
        params.softInputMode = WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE

        resultView = view.findViewById(R.id.overlayResult)
        sourceView = view.findViewById(R.id.overlaySource)
        followView = view.findViewById(R.id.overlayFollowUp)
        scroll = view.findViewById(R.id.overlayScroll)
        Typefaces.body(this)?.let { typeface -> resultView?.typeface = typeface }

        view.findViewById<View>(R.id.overlayClose).setOnClickListener { hidePanel() }
        view.findViewById<View>(R.id.overlayCloseBottom).setOnClickListener { hidePanel() }
        view.findViewById<View>(R.id.overlayReadClipboard).setOnClickListener { pullClipboard(true) }
        view.findViewById<View>(R.id.overlayRegenerate).setOnClickListener { regenerate() }
        view.findViewById<View>(R.id.overlayCopy).setOnClickListener { copyResult() }
        view.findViewById<View>(R.id.overlaySave).setOnClickListener { saveNote() }
        view.findViewById<View>(R.id.overlaySend).setOnClickListener { sendFollowUp() }

        view.findViewById<TextView>(R.id.chipWord).setOnClickListener { analyze(Mode.WORD) }
        view.findViewById<TextView>(R.id.chipSentence).setOnClickListener { analyze(Mode.SENTENCE) }
        view.findViewById<TextView>(R.id.chipTranslate).setOnClickListener { analyze(Mode.TRANSLATE) }
        view.findViewById<TextView>(R.id.chipPolish).setOnClickListener { analyze(Mode.POLISH) }

        try {
            windowManager.addView(view, params)
            panel = view
        } catch (t: Throwable) {
            panel = null
            toast(getString(R.string.overlay_no_permission))
            return
        }

        if (autoRead) {
            main.postDelayed({ pullClipboard(false) }, 350)
        }
    }

    private fun hidePanel() {
        session?.cancel()
        session = null
        val view = panel
        panel = null
        if (view != null) {
            try {
                windowManager.removeView(view)
            } catch (t: Throwable) {
                // 忽略
            }
        }
        resultView = null
        sourceView = null
        followView = null
        scroll = null
        transcript.setLength(0)
    }

    // ---------------------------------------------------------------- 交互逻辑

    private fun pullClipboard(force: Boolean) {
        val field = sourceView ?: return
        if (!force && field.text.isNotBlank()) return
        val text = readClipboard()
        if (text.isEmpty()) {
            if (force) {
                toast(getString(R.string.overlay_clipboard_empty))
            } else {
                setResult(getString(R.string.overlay_empty_hint))
            }
            return
        }
        field.setText(text)
        clearHighlight()
        setResult(getString(R.string.overlay_pick_hint))
    }

    /** 只填字不解析，所以一个栏目都不选中，等用户自己点。 */
    private fun clearHighlight() {
        val view = panel ?: return
        view.findViewById<TextView>(R.id.chipWord).isSelected = false
        view.findViewById<TextView>(R.id.chipSentence).isSelected = false
        view.findViewById<TextView>(R.id.chipTranslate).isSelected = false
        view.findViewById<TextView>(R.id.chipPolish).isSelected = false
    }

    private fun analyze(mode: Mode) {
        val field = sourceView ?: return
        val text = field.text.toString().trim()
        if (text.isEmpty()) {
            toast(getString(R.string.err_empty_input))
            return
        }
        val settings = Prefs.load(this)
        if (!settings.hasKey) {
            setResult(getString(R.string.err_no_key_card))
            return
        }
        currentMode = mode
        currentText = text
        transcript.setLength(0)
        lastFlush = 0L
        setResult("")
        highlight(mode)

        session?.cancel()
        session = CoachSession(client, settings, sessionListener)
        session?.begin(mode, text)
    }

    private fun highlight(mode: Mode) {
        val view = panel ?: return
        view.findViewById<TextView>(R.id.chipWord).isSelected = mode == Mode.WORD
        view.findViewById<TextView>(R.id.chipSentence).isSelected = mode == Mode.SENTENCE
        view.findViewById<TextView>(R.id.chipTranslate).isSelected = mode == Mode.TRANSLATE
        view.findViewById<TextView>(R.id.chipPolish).isSelected = mode == Mode.POLISH
    }

    private fun sendFollowUp() {
        val field = followView ?: return
        val question = field.text.toString().trim()
        if (question.isEmpty()) return
        val active = session
        if (active == null) {
            toast(getString(R.string.overlay_need_first))
            return
        }
        field.setText("")
        transcript.append("\n\n【追问】").append(question).append("\n")
        lastFlush = 0L
        setResult(transcript.toString())
        active.followUp(question)
    }

    private fun regenerate() {
        val active = session ?: return
        transcript.setLength(0)
        lastFlush = 0L
        setResult("")
        active.regenerate()
    }

    private fun copyResult() {
        val raw = transcript.toString().ifEmpty { resultView?.text?.toString().orEmpty() }
        val text = Markdown.toPlain(raw)
        if (text.isBlank()) {
            toast(getString(R.string.err_nothing_to_copy))
            return
        }
        val manager = getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager ?: return
        manager.setPrimaryClip(ClipData.newPlainText(getString(R.string.app_name), text))
        toast(getString(R.string.copied))
    }

    private fun saveNote() {
        val text = transcript.toString().ifEmpty { resultView?.text?.toString().orEmpty() }
        if (text.isBlank()) {
            toast(getString(R.string.err_nothing_to_copy))
            return
        }
        NotesStore.add(this, currentMode.name, currentText, text)
        toast(getString(R.string.notes_saved))
    }

    private fun setResult(text: String) {
        val view = resultView ?: return
        if (text.isEmpty()) {
            view.text = ""
        } else {
            view.text = Markdown.render(
                text,
                getColor(R.color.accent),
                getColor(R.color.text_secondary)
            )
        }
        scroll?.post { scroll?.fullScroll(View.FOCUS_DOWN) }
    }

    private fun readClipboard(): String {
        val manager = getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager ?: return ""
        val clip = manager.primaryClip ?: return ""
        if (clip.itemCount == 0) return ""
        return clip.getItemAt(0).coerceToText(this)?.toString()?.trim().orEmpty()
    }

    private fun toast(text: String) {
        Toast.makeText(this, text, Toast.LENGTH_SHORT).show()
    }
}
