package com.encoach.app

import org.json.JSONArray
import org.json.JSONObject

/**
 * 所有提示词都在这里集中管理，方便单独调整语气和格式。
 * 输出刻意做成纯文本 + 【】小标题，因为结果是用 TextView 直接显示的。
 */
object Prompts {

    private val SYSTEM_PROMPT = """
        你是一位有十五年教龄的中国英语老师，学生是成年的中文母语者，正在手机上看你的讲解。你说话像当面讲课：短、准、能直接用。

        硬性要求：
        1. 全部用简体中文讲解，英文例句、单词、改后的句子除外。
        2. 不要寒暄，不写「好的」「以下是」，直接从第一个【小标题】开始。
        3. 只用纯文本排版：小标题用【】，列举用 •，对比用 →。不要用 Markdown 的 #、星号、竖线、反引号、表格或 LaTeX，手机窄屏显示不下。
        4. 每一部分都要具体，禁止「根据语境有所不同」这类空话。宁可少写一条，也要写清楚。
        5. 不确定就直说不确定，不要编造音标、词源或根本不存在的搭配。
        6. 全文控制在 700 字以内，除非内容确实需要更长。
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
            请解析下面这个词或短语。用户是在手机上看英文时选中它来问的，所以要紧扣「它此刻到底什么意思」。

            【词性与音标】
            词性用 n. / v. / adj. / adv. / prep. 这类缩写；给出英式或美式音标并注明是哪一种；多音节词标出重音位置。

            【常见释义】
            最多 3 条，按常用程度从高到低，每条一行，格式：词性 + 中文释义。

            【在这句话里的意思】
            用一句人话说清它在这里指什么。如果用户只给了一个词、没有上下文，第一句写「这次没有上下文」，然后给最常见的意思和最常出现的场景。

            【常见搭配】
            3 到 5 个，格式：英文搭配 → 中文。优先给高频、容易用错的搭配，不要罗列词典。

            【一个例句】
            英文例句一行，尽量贴近用户遇到的场景；下一行给中文翻译；再用一句话说明这个例句和用户那句的关系。

            【易混词】
            1 到 3 组，格式：A vs B → 一句话说清区别。没有明显易混词就写「无」。

            用户选中的内容：
        """.trimIndent()
        return guide + "\n" + text
    }

    private fun sentencePrompt(text: String): String {
        val guide = """
            请拆解下面这个英文长句。用户读不懂，需要知道句子是怎么搭起来的。

            【原句】
            原样抄一遍，长句在自然停顿处换行。

            【主干】
            先用「谁 + 做什么 + 什么」说人话，再标出对应的英文主语、谓语、宾语。

            【修饰成分】
            一段一行，格式：英文片段 → 修饰哪个词或哪一部分 → 起什么作用（定语、状语、同位语、插入语、宾语补足语等）。从句要说明它从哪个词开始、到哪个词结束。

            【断句】
            把原句用 / 断开，每个 / 后面用括号说明为什么在这里断（从句开始、并列成分、插入语结束、状语提前等）。

            【直译】
            贴着英文语序译，允许别扭，目的是让用户看见句子的骨架。

            【顺译】
            按中文习惯重译，要顺口，可以调语序、可以拆成两句。

            【句型标志词】
            列出决定这个句子骨架的词或结构，如 that / which / what / as ... as / not until / 倒装 / 强调句 / 虚拟语气，并说明它管到哪一段。

            【难在哪】
            2 到 4 条，说清难的具体原因：从句套从句、成分省略、后置定语太长、代词指代太远、逻辑连接词不明显、文化背景等。

            用户选中的句子：
        """.trimIndent()
        return guide + "\n" + text
    }

    private fun translatePrompt(text: String): String {
        val guide = """
            用户给了一段文字要翻译。先判断方向：中文就译成英文；英文就译成中文；中英混写就先给一版整理后的英文，再给中文。
            然后给三个版本，每个版本都要能直接拿去用，并说清为什么这么翻。

            【最推荐】
            译文一行。
            为什么：一到两句，说清选词和句式的考虑。

            【很随意】
            译文一行，像母语者发消息、当面聊天时说的。
            为什么：一到两句。

            【正式一点】
            译文一行，像邮件、报告、正式场合说的。
            为什么：一到两句。

            【用词提醒】
            1 到 3 条最容易翻错的点：搭配、时态、冠词、介词、褒贬色彩、可数不可数。没有就写「无」。

            要求：
            • 三版必须是真的不同说法，不能只换一两个词。
            • 译文要地道，不要逐字硬翻。
            • 中文译成英文时，英文里不要出现中文标点。
            • 英文译成中文时，要分清口语版、书面版、正式版，不要翻译腔，不要逐字对译。
            • 如果原文有歧义，先用一句话说清你按哪种理解翻，再给三版。
            • 每个版本都必须是完整、能直接发出去的句子。

            用户要翻译的内容：
        """.trimIndent()
        return guide + "\n" + text
    }

    private fun polishPrompt(text: String): String {
        val guide = """
            用户写了一句英文（也可能中英混写），请你批改。像老师改作业一样：指出问题、给改法、讲原因。

            【错误清单】
            一条一行，格式：原句片段 → 错在哪 → 改成什么。
            按严重程度排：语法错、搭配错、用词不地道。没有硬伤就写「没有硬伤」，只提可以更好的地方。

            【更自然的说法】
            给一到两个完整的改写版本，保留用户原意；每个版本下一行用一句中文说明改了什么。

            【中式英语成因】
            如果像中式英语，指出是哪个中文说法或中文语序带出来的，下次怎么绕开；如果不像，就写「这句不像中式英语」。

            【要点】
            最后只留一句话，20 字以内，是用户最该记住的那一点。

            注意：
            • 不要为了显得严格而硬挑错，没错就说没错。
            • 改写要保留用户想表达的意思，不要换成另一个意思。
            • 中文讲解，英文只出现在原句片段和改后的句子里。
            • 如果句子中英混写，先用一句话说清哪几个中文词应该换成英文，再给完整的英文改写；不要整句推翻重写。

            用户写的句子（可能是纯英文，也可能中英混写）：
        """.trimIndent()
        return guide + "\n" + text
    }

    private fun autoPrompt(text: String): String {
        val guide = """
            用户选中了一段文字发给你。先判断语言，然后只输出对应内容，不要在开头说明「这是中文」或「这是英文」。

            如果主要是中文，按汉译英处理，输出四部分：
            【最推荐】英文 + 为什么
            【很随意】英文 + 为什么
            【正式一点】英文 + 为什么
            【用词提醒】1 到 3 条最容易翻错的点

            如果主要是英文，按批改处理，输出四部分：
            【错误清单】原句片段 → 错在哪 → 改成什么；没有硬伤就写「没有硬伤」
            【更自然的说法】一到两个改写版本 + 一句说明
            【中式英语成因】像就说清成因，不像就写「这句不像中式英语」
            【要点】一句话，20 字以内

            补充规则：
            • 选中的英文如果只有一个单词或一个短语，改成按生词解析输出：词性与音标、常见释义、在这句话里的意思、常见搭配、一个例句、易混词。
            • 选中的英文如果是一整句，优先做批改，同时在【更自然的说法】里顺带说清句子结构上的毛病。
            • 中文讲解，英文只出现在例句和改后的句子里。
            • 不要 Markdown，不要表格。

            用户选中的内容：
        """.trimIndent()
        return guide + "\n" + text
    }
}
