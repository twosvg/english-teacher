package com.encoach.app

import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.TextPaint
import android.text.style.BackgroundColorSpan
import android.text.style.ForegroundColorSpan
import android.text.style.MetricAffectingSpan
import android.text.style.RelativeSizeSpan
import android.text.style.StrikethroughSpan
import android.text.style.TypefaceSpan

/**
 * 极简 Markdown 渲染器：把模型输出的 #、**、列表、引用、分隔线变成真正的排版。
 * 不用任何第三方库，纯 Span 拼出来；字体（楷体）由 TextView 的 typeface 决定。
 */
object Markdown {

    private const val SPAN = Spanned.SPAN_EXCLUSIVE_EXCLUSIVE

    private val HEADING = Regex("^\\s*(#{1,6})\\s+(.*)$")
    private val BULLET = Regex("^\\s*[-*•]\\s+(.*)$")
    private val NUMBER = Regex("^\\s*(\\d{1,2})[.)、]\\s+(.*)$")
    private val QUOTE = Regex("^\\s*>\\s?(.*)$")
    private val RULE = Regex("^\\s*(-{3,}|\\*{3,}|_{3,})\\s*$")

    private val INLINE = Regex(
        "(\\*\\*[^*]+\\*\\*|__[^_]+__|`[^`]+`|~~[^~]+~~|\\*[^*\\n]+\\*|\\[[^\\]]+\\]\\([^)]*\\))"
    )

    /** 把 Markdown 文本渲染成带样式的 CharSequence。 */
    fun render(source: String, accent: Int, secondary: Int): CharSequence {
        val out = SpannableStringBuilder()
        val normalized = source.replace("\r\n", "\n").replace('\r', '\n')
        val lines = normalized.split('\n')

        for ((index, raw) in lines.withIndex()) {
            val line = raw.trimEnd()
            if (line.isBlank()) {
                if (out.isNotEmpty() && out[out.length - 1] != '\n') out.append('\n')
                continue
            }

            val heading = HEADING.find(line)
            val bullet = BULLET.find(line)
            val number = NUMBER.find(line)
            val quote = QUOTE.find(line)

            when {
                RULE.matches(line) -> out.append("────────")

                heading != null -> {
                    val start = out.length
                    out.append(heading.groupValues[2].trim())
                    out.setSpan(RelativeSizeSpan(1.14f), start, out.length, SPAN)
                    out.setSpan(FakeBold(), start, out.length, SPAN)
                    out.setSpan(ForegroundColorSpan(accent), start, out.length, SPAN)
                }

                bullet != null -> {
                    out.append("• ")
                    inline(out, bullet.groupValues[1], accent)
                }

                number != null -> {
                    out.append(number.groupValues[1]).append("  ")
                    inline(out, number.groupValues[2], accent)
                }

                quote != null -> {
                    out.append("  ")
                    val start = out.length
                    inline(out, quote.groupValues[1], accent)
                    out.setSpan(ForegroundColorSpan(secondary), start, out.length, SPAN)
                }

                else -> inline(out, line, accent)
            }

            if (index != lines.lastIndex) out.append('\n')
        }
        return out
    }

    /** 复制到剪贴板时用：去掉所有标记，留干净文字。 */
    fun toPlain(source: String): String {
        var text = source.replace("\r\n", "\n")
        text = text.replace(Regex("(?m)^\\s*#{1,6}\\s+")) { "" }
        text = text.replace(Regex("\\*\\*([^*]+)\\*\\*")) { it.groupValues[1] }
        text = text.replace(Regex("__([^_]+)__")) { it.groupValues[1] }
        text = text.replace(Regex("~~([^~]+)~~")) { it.groupValues[1] }
        text = text.replace(Regex("`([^`]+)`")) { it.groupValues[1] }
        text = text.replace(Regex("\\[([^\\]]+)]\\([^)]*\\)")) { it.groupValues[1] }
        text = text.replace(Regex("(?m)^\\s*[-*•]\\s+")) { "• " }
        text = text.replace(Regex("\\*([^*\\n]+)\\*")) { it.groupValues[1] }
        return text
    }

    private fun inline(out: SpannableStringBuilder, text: String, accent: Int) {
        var last = 0
        for (match in INLINE.findAll(text)) {
            if (match.range.first > last) {
                out.append(text.substring(last, match.range.first))
            }
            val token = match.value
            val start = out.length
            when {
                token.length > 4 && token.startsWith("**") && token.endsWith("**") -> {
                    out.append(token.substring(2, token.length - 2))
                    out.setSpan(FakeBold(), start, out.length, SPAN)
                }

                token.length > 4 && token.startsWith("__") && token.endsWith("__") -> {
                    out.append(token.substring(2, token.length - 2))
                    out.setSpan(FakeBold(), start, out.length, SPAN)
                }

                token.length > 4 && token.startsWith("~~") && token.endsWith("~~") -> {
                    out.append(token.substring(2, token.length - 2))
                    out.setSpan(StrikethroughSpan(), start, out.length, SPAN)
                }

                token.length > 2 && token.startsWith("`") && token.endsWith("`") -> {
                    out.append(token.substring(1, token.length - 1))
                    out.setSpan(TypefaceSpan("monospace"), start, out.length, SPAN)
                    out.setSpan(BackgroundColorSpan(0x1A000000), start, out.length, SPAN)
                }

                token.startsWith("[") -> {
                    val close = token.indexOf(']')
                    if (close > 1) {
                        out.append(token.substring(1, close))
                        out.setSpan(ForegroundColorSpan(accent), start, out.length, SPAN)
                    } else {
                        out.append(token)
                    }
                }

                token.length > 2 && token.startsWith("*") && token.endsWith("*") -> {
                    out.append(token.substring(1, token.length - 1))
                    out.setSpan(Slant(), start, out.length, SPAN)
                }

                else -> out.append(token)
            }
            last = match.range.last + 1
        }
        if (last < text.length) out.append(text.substring(last))
}

    /** 楷体没有粗体，用画笔级伪粗体，避免 StyleSpan 把字族换回系统字体。 */
    private class FakeBold : MetricAffectingSpan() {
        override fun updateMeasureState(paint: TextPaint) {
            paint.isFakeBoldText = true
        }

        override fun updateDrawState(paint: TextPaint) {
            paint.isFakeBoldText = true
        }
    }

    /** 同理，斜体用倾斜画笔代替 Italic 字体。 */
    private class Slant : MetricAffectingSpan() {
        override fun updateMeasureState(paint: TextPaint) {
            paint.textSkewX = -0.22f
        }

        override fun updateDrawState(paint: TextPaint) {
            paint.textSkewX = -0.22f
        }
    }
}

