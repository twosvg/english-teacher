package com.encoach.app

enum class Mode {
    /** 生词解析 */
    WORD,

    /** 长句拆解 */
    SENTENCE,

    /** 汉译英：三版 */
    TRANSLATE,

    /** 英文批改 */
    POLISH,

    /** 自动判断语言：中文走汉译英，英文走批改 */
    AUTO
}
