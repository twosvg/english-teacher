package com.encoach.app

import org.json.JSONArray
import org.json.JSONObject

/**
 * 所有提示词集中在里，方便单独调语气。
 * 输出用 Markdown（App 内有简易渲染器），默认短，追问才展开。
 */
object Prompts {

    private val SYSTEM_PROMPT = """
        你是一位有十五年教龄的中国英语老师，学生是成年人在刷手机时顺手查一下，不是在上一整节课。

        硬性要求：
        1. 全部用简体中文讲解，英文例句、单词、改后的句子除外。
        2. 不要寒暄，不写「好的」「以下是」，直接从第一个小标题开始。
        3. 排版用 Markdown：小标题用 ##，强调用 **，列举用 -。不要表格、代码块、图片、LaTeX。
        4. 默认要短：能一句话说清就一句话。只有「句式」可以写到 8 到 12 行。
        5. 用户追问时再展开，追问不受长度限制。
        6. 不确定就直说不确定，不要编造音标、词源或不存在的搭配。
    """.trimIndent()

    fun build(mode: Mode, text: String): JSONArray {
        val messages = JSONArray()
        messages.put(systemMessage())
        messages.put(userMessage(mode, text))
        return messages
    }

    fun systemMessage(): JSONObject =
        JSONObject().put("role", "system").put("content", SYSTEM_PROMPT)

    fun userMessage(mode: Mode, text: String): JSONObject =
        JSONObject().put("role", "user").put("content", userPrompt(mode, text))

    fun plainUserMessage(text: String): JSONObject =
        JSONObject().put("role", "user").put("content", text)

    private fun userPrompt(mode: Mode, text: String): String = when (mode) {
        Mode.WORD -> wordPrompt(text)
        Mode.SENTENCE -> sentencePrompt(text)
        Mode.TRANSLATE -> translatePrompt(text)
        Mode.POLISH -> polishPrompt(text)
        Mode.AUTO -> autoPrompt(text)
    }

    private fun wordPrompt(text: String): String {
        val guide = """
            解析下面这个词或短语。用户在刷手机时顺手查，**总共不超过 6 行**。

            ## 词性与音标
            一行：词性缩写 + 音标（英式或美式注明）。

            ## 释义
            最多 2 条，每条一行。

            ## 这句里
            一句话说清它在这里指什么；没上下文就写「这次没有上下文」，再给最常用义。

            ## 搭配
            3 个，格式：英文搭配 → 中文。

            ## 例句
            英文一行，中文一行。

            ## 易混
            1 组，格式：A vs B → 一句话。没有就写「无」。

            用户选中的内容：
        """.trimIndent()
        return guide + "\n" + text
    }

    private fun sentencePrompt(text: String): String {
        val guide = """
            拆解下面这个英文长句。用户读不懂，需要看清句子怎么搭起来的。

            这一项**可以详细**，总量 8 到 12 行，但不要写成讲课。

            ## 主干
            用「谁 + 做什么 + 什么」说人话，再标出英文主语、谓语、宾语。

            ## 修饰成分
            一段一行，格式：英文片段 → 修饰哪个词或哪部分 → 起什么作用（定语、状语、同位语、插入语、宾语补足语）。
            从句要说明从哪个词开始、到哪个词结束。**有几个列几个，不要合并**。

            ## 断句
            原句用 / 断开，每个 / 后面用括号说明为什么断（从句开始、并列、插入语结束、状语提前）。

            ## 直译
            贴着英文语序译，允许别扭，让用户看见骨架。

            ## 顺译
            按中文习惯重译，可调语序、可拆成两句。

            ## 标志词
            决定骨架的词或结构（that / which / as ... as / not until / 倒装 / 强调句），说明它管到哪一段。

            ## 难在哪
            2 条，说清具体原因：从句套从句、省略、后置定语太长、代词指代远。

            用户选中的句子：
        """.trimIndent()
        return guide + "\n" + text
    }

    private fun translatePrompt(text: String): String {
        val guide = """
            翻译下面的内容。中文就译成英文，英文就译成中文，中英混写先给整理后的英文再给中文。

            ## 最推荐
            译文一行。
            为什么：一句，说清选词和句式。

            ## 很随意
            译文一行，像发消息聊天。
            为什么：一句。

            ## 正式一点
            译文一行，像邮件报告。
            为什么：一句。

            ## 提醒
            1 条最容易翻错的点，没有就写「无」。

            要求：三版必须是真正不同的说法；英文里不要出现中文标点；英文译中文时不要翻译腔。

            用户要翻译的内容：
        """.trimIndent()
        return guide + "\n" + text
    }

    private fun polishPrompt(text: String): String {
        val guide = """
            批改下面的句子（可能是纯英文，也可能中英混写）。像改作业：指出问题、给改法、讲原因。

            ## 错误
            最多 3 条，格式：原句片段 → 错在哪 → 改成什么。没有硬伤就写「没有硬伤」。

            ## 更自然
            一个改写版本，保留原意；下一行用一句中文说明改了什么。

            ## 中式的成因
            像中式英语就说清是哪个中文说法带出来的；不像就写「这句不像中式英语」。

            ## 要点
            一句话，20 字以内。

            要求：中英混写时，先点出哪几个中文词该换成英文，再给完整英文；不要整句推翻重写。

            用户写的句子：
        """.trimIndent()
        return guide + "\n" + text
    }

    private fun autoPrompt(text: String): String {
        val guide = """
            用户选中了一段文字。先判断语言，只输出对应内容，不要说明「这是中文/英文」。

            如果主要是中文，按翻译处理：
            ## 最推荐 / ## 很随意 / ## 正式一点
            各给一行英文 + 一句为什么。
            最后加 ## 提醒，1 条。

            如果主要是英文，按批改处理：
            ## 错误（最多 3 条，没有就写「没有硬伤」）
            ## 更自然（一个改写版本）
            ## 中式的成因（或「这句不像中式英语」）
            ## 要点（一句话，20 字以内）

            补充：
            - 选中的英文如果只有一个单词，改成按生词解析，总共不超过 6 行。
            - 选中的英文如果是一整句，优先批改。
            - 排版用 Markdown，不要表格、代码块。

            用户选中的内容：
        """.trimIndent()
        return guide + "\n" + text
    }
}
