package com.encoach.app

import android.content.Context
import android.graphics.Typeface

/**
 * 内置字体加载器：霞鹜文楷（LXGW WenKai）。
 * 字体文件由 CI 下载到 assets/fonts/；本地没下载时返回 null，调用方保持系统字体。
 */
object Typefaces {

    private var loaded = false
    private var cached: Typeface? = null

    fun body(context: Context): Typeface? {
        if (!loaded) {
            loaded = true
            cached = try {
                Typeface.createFromAsset(context.assets, "fonts/LXGWWenKai-Regular.ttf")
            } catch (t: Throwable) {
                null
            }
        }
        return cached
    }
}
