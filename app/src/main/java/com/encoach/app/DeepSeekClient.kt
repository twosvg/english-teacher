package com.encoach.app

import android.os.Handler
import android.os.Looper
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.IOException
import java.io.InputStream
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.SocketTimeoutException
import java.net.URL
import java.net.UnknownHostException
import java.util.concurrent.Executors
import javax.net.ssl.SSLException

/**
 * 极简的 OpenAI 兼容客户端：
 * HttpURLConnection 直连 /v1/chat/completions，stream=true，逐行解析 SSE。
 * 不用协程，不用第三方库；回调统一切回主线程。
 */
class DeepSeekClient {

    interface Listener {
        /** 收到一段增量文本（主线程回调） */
        fun onDelta(delta: String)

        /** 全部结束，full 是拼接后的完整文本（主线程回调） */
        fun onFinish(full: String)

        /** 出错，message 是可以直接显示给用户的中文提示（主线程回调） */
        fun onError(message: String)
    }

    private val mainHandler = Handler(Looper.getMainLooper())

    private val executor = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "encoach-network").apply { isDaemon = true }
    }

    /** 每次请求一个代号，旧请求的回调一律丢弃，避免「停止后还在刷字」。 */
    @Volatile
    private var generation = 0

    @Volatile
    private var activeConnection: HttpURLConnection? = null

    fun submit(
        apiKey: String,
        baseUrl: String,
        model: String,
        messages: JSONArray,
        listener: Listener
    ) {
        if (executor.isShutdown) return
        val myGeneration = nextGeneration()
        // 先把上一个还在等数据的连接掐掉，否则旧请求会把线程占住，新请求只能排队
        disconnectQuietly()
        try {
            executor.execute {
                try {
                    stream(myGeneration, apiKey, baseUrl, model, messages, listener)
                } catch (t: Throwable) {
                    if (myGeneration == generation) {
                        postError(myGeneration, listener, friendlyMessage(t))
                    }
                }
            }
        } catch (t: Throwable) {
            // 线程池已关闭，交给上层提示
            if (myGeneration == generation) {
                postError(myGeneration, listener, "客户端已关闭，请重试。")
            }
        }
    }

    fun cancel() {
        nextGeneration()
        disconnectQuietly()
    }

    fun close() {
        nextGeneration()
        disconnectQuietly()
        executor.shutdownNow()
    }

    // ---------------------------------------------------------------- 内部实现

    private fun nextGeneration(): Int {
        val next = generation + 1
        generation = next
        return next
    }

    private fun stream(
        myGeneration: Int,
        apiKey: String,
        baseUrl: String,
        model: String,
        messages: JSONArray,
        listener: Listener
    ) {
        val endpoint = endpointOf(baseUrl)
        if (myGeneration != generation) return
        val requestBody = JSONObject()
            .put("model", model)
            .put("messages", messages)
            .put("stream", true)
            .put("temperature", 0.4)
            .put("max_tokens", 2400)
            .toString()

        var opened: HttpURLConnection? = null
        try {
            val connection = URL(endpoint).openConnection() as HttpURLConnection
            opened = connection
            activeConnection = connection

            connection.requestMethod = "POST"
            connection.connectTimeout = 20_000
            connection.readTimeout = 180_000
            connection.doOutput = true
            connection.setRequestProperty("Content-Type", "application/json; charset=utf-8")
            connection.setRequestProperty("Accept", "text/event-stream")
            connection.setRequestProperty("Authorization", "Bearer " + apiKey)
            connection.setRequestProperty("User-Agent", "EnglishTeacher-Android/1.0")

            if (myGeneration != generation) return

            connection.outputStream.use { out ->
                out.write(requestBody.toByteArray(Charsets.UTF_8))
                out.flush()
            }

            val statusCode = connection.responseCode
            if (statusCode < 200 || statusCode > 299) {
                val errorBody = readAllSafely(connection.errorStream)
                postError(myGeneration, listener, httpErrorMessage(statusCode, errorBody))
                return
            }

            val full = StringBuilder()
            BufferedReader(InputStreamReader(connection.inputStream, Charsets.UTF_8)).use { reader ->
                while (true) {
                    if (myGeneration != generation) return
                    val line = reader.readLine() ?: break
                    if (line.isEmpty()) continue
                    if (!line.startsWith("data:")) continue
                    val payload = line.substring(5).trim()
                    if (payload.isEmpty()) continue
                    if (payload == "[DONE]") break
                    val delta = extractDelta(payload)
                    if (delta.isEmpty()) continue
                    full.append(delta)
                    postDelta(myGeneration, listener, delta)
                }
            }

            if (myGeneration != generation) return
            if (full.isEmpty()) {
                postError(
                    myGeneration,
                    listener,
                    "模型没有返回任何内容。请重试，或在「设置」里换一个模型。"
                )
            } else {
                val text = full.toString()
                mainHandler.post {
                    if (myGeneration == generation) listener.onFinish(text)
                }
            }
        } catch (t: Throwable) {
            if (myGeneration == generation) {
                postError(myGeneration, listener, friendlyMessage(t))
            }
        } finally {
            activeConnection = null
            try {
                opened?.disconnect()
            } catch (ignored: Throwable) {
                // 忽略
            }
        }
    }

    private fun postDelta(myGeneration: Int, listener: Listener, delta: String) {
        mainHandler.post {
            if (myGeneration == generation) listener.onDelta(delta)
        }
    }

    private fun postError(myGeneration: Int, listener: Listener, message: String) {
        mainHandler.post {
            if (myGeneration == generation) listener.onError(message)
        }
    }

    private fun disconnectQuietly() {
        val connection = activeConnection
        activeConnection = null
        try {
            connection?.disconnect()
        } catch (ignored: Throwable) {
            // 忽略
        }
    }

    /** 把用户填的 baseUrl 归一化成完整的 chat/completions 地址。 */
    private fun endpointOf(rawBaseUrl: String): String {
        var base = rawBaseUrl.trim()
        if (base.isEmpty()) base = Prefs.DEFAULT_BASE_URL
        if (!base.startsWith("http://", true) && !base.startsWith("https://", true)) {
            base = "https://" + base
        }
        base = base.trimEnd('/')
        return when {
            base.endsWith("/chat/completions") -> base
            base.endsWith("/v1") -> base + "/chat/completions"
            else -> base + "/v1/chat/completions"
        }
    }

    private fun extractDelta(payload: String): String {
        val root = try {
            JSONObject(payload)
        } catch (t: Throwable) {
            return ""
        }
        val choices = root.optJSONArray("choices") ?: return ""
        if (choices.length() == 0) return ""
        val choice = choices.optJSONObject(0) ?: return ""
        val delta = choice.optJSONObject("delta")
        if (delta != null) return delta.optString("content", "")
        // 少量兼容服务即使 stream=true 也会一次性返回 message
        val message = choice.optJSONObject("message")
        return message?.optString("content", "") ?: ""
    }

    private fun readAllSafely(stream: InputStream?): String {
        if (stream == null) return ""
        return try {
            stream.bufferedReader(Charsets.UTF_8).use { it.readText().take(600) }
        } catch (t: Throwable) {
            ""
        }
    }

    private fun httpErrorMessage(code: Int, rawBody: String): String {
        val detail = parseErrorDetail(rawBody)
        val hint = when (code) {
            401, 403 -> "API Key 无效，或这个 Key 没有调用权限。请到「设置」里检查。"
            402 -> "账户余额不足，请到服务商后台充值。"
            404 -> "接口地址不对（404）。请检查「设置」里的 baseUrl 和模型名。"
            429 -> "请求太频繁，被限流了。等几秒再试。"
            in 500..599 -> "服务端出错了（HTTP " + code + "），过一会儿再试。"
            else -> "请求失败（HTTP " + code + "）。"
        }
        return if (detail.isBlank()) hint else hint + "\n服务端说：" + detail
    }

    private fun parseErrorDetail(rawBody: String): String {
        if (rawBody.isBlank()) return ""
        val parsed = try {
            JSONObject(rawBody)
        } catch (t: Throwable) {
            null
        }
        if (parsed != null) {
            val nested = parsed.optJSONObject("error")
            val nestedMessage = nested?.optString("message", "").orEmpty()
            if (nestedMessage.isNotBlank()) return nestedMessage
            val flatMessage = parsed.optString("message", "")
            if (flatMessage.isNotBlank()) return flatMessage
        }
        return rawBody.replace("\n", " ").take(200)
    }

    private fun friendlyMessage(t: Throwable): String = when (t) {
        is UnknownHostException ->
            "连不上服务器：请检查网络，或确认「设置」里的 baseUrl 是否正确。"
        is SocketTimeoutException ->
            "请求超时了。网络可能不稳定，或服务商响应太慢，请重试。"
        is SSLException ->
            "HTTPS 连接失败：可能是证书问题，或中间有代理拦截。"
        is IOException ->
            "网络中断：" + (t.message ?: "读取数据时出错") + "。请检查网络后重试。"
        else ->
            "出错了：" + t.javaClass.simpleName + " " + (t.message ?: "")
    }
}
