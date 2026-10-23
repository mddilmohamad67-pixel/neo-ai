package com.neoai

import android.content.Context
import android.content.ClipData
import android.content.ClipboardManager

fun Context.copyToClipboard(text: String): Boolean {
    return runCatching {
        val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        cm.setPrimaryClip(ClipData.newPlainText("neo", text))
        true
    }.getOrDefault(false)
}
