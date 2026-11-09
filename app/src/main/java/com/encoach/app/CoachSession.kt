package com.encoach.app

import org.json.JSONArray
import org.json.JSONObject

/**
 * 一次完整的“对话会话语义”：
 * 保存 system + 多轮 user/assistant 消息，支持追问和重新生成。
 * 悬浮窗的追问功能靠它把上下文一起发给模型。
 */
class CoachSession(
    private val client: DeepSeekClient,
    private val settings: Prefs.Settings,
    private val listener: Listener
) {

    interface Listener {
        /** 每次开始新的回答时回调（首次解析、追问、重新生成都会触发） */
        fun onAnswerStart()

        fun onDelta(delta: String)

        fun onAnswerDone(full: String)

        fun onAnswerError(message: String)
    }

    private val messages = JSONArray()

    @Volatile
    private var streaming = false

    fun begin(mode: Mode, text: String) {
        messages.put(Prompts.systemMessage())
        messages.put(Prompts.userMessage(mode, text))
        send()
    }

    fun followUp(question: String) {
        if (messages.length() == 0) return
        messages.put(Prompts.plainUserMessage(question))
        send()
    }

    fun regenerate() {
        if (messages.length() < 2) return
        if (streaming) {
            client.cancel()
            streaming = false
        }
        while (messages.length() > 2 && roleOf(messages.length() - 1) == "assistant") {
            messages.remove(messages.length() - 1)
        }
        send()
    }

    fun cancel() {
        streaming = false
        client.cancel()
    }

    private fun roleOf(index: Int): String =
        messages.optJSONObject(index)?.optString("role", "") ?: ""

    private fun send() {
        // 传副本，避免网络线程序列化时主线程又改了数组
        val payload = JSONArray(messages.toString())
        streaming = true
        listener.onAnswerStart()
        client.submit(
            settings.apiKey,
            settings.baseUrl,
            settings.model,
            payload,
            object : DeepSeekClient.Listener {
                override fun onDelta(delta: String) {
                    listener.onDelta(delta)
                }

                override fun onFinish(full: String) {
                    streaming = false
                    messages.put(JSONObject().put("role", "assistant").put("content", full))
                    listener.onAnswerDone(full)
                }

                override fun onError(message: String) {
                    streaming = false
                    listener.onAnswerError(message)
                }
            }
        )
    }
}
