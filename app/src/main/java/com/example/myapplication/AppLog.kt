package com.example.myapplication

import android.content.Context
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object AppLog {
    private const val FILE_NAME = "app_logs.txt"
    private const val MAX_FILE_BYTES = 1024 * 1024 // 1MB
    private val timeFmt = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US)

    @Volatile
    private var appContext: Context? = null

    fun init(context: Context) {
        appContext = context.applicationContext
    }

    fun d(tag: String, message: String) {
        append("D", tag, message, null)
    }

    fun e(tag: String, message: String, throwable: Throwable? = null) {
        append("E", tag, message, throwable)
    }

    fun readAll(): String {
        val ctx = appContext ?: return "日志系统未初始化"
        val file = File(ctx.filesDir, FILE_NAME)
        if (!file.exists()) return "暂无日志"
        return runCatching { file.readText() }.getOrElse { "日志读取失败: ${it.message}" }
    }

    fun clear() {
        val ctx = appContext ?: return
        val file = File(ctx.filesDir, FILE_NAME)
        runCatching { if (file.exists()) file.delete() }
    }

    private fun append(level: String, tag: String, message: String, throwable: Throwable?) {
        val ctx = appContext ?: return
        val file = File(ctx.filesDir, FILE_NAME)
        val now = timeFmt.format(Date())
        val sb = StringBuilder()
        sb.append(now)
            .append(" [").append(level).append("]")
            .append(" [").append(tag).append("] ")
            .append(message)
            .append('\n')
        if (throwable != null) {
            sb.append(throwable.stackTraceToString()).append('\n')
        }
        synchronized(this) {
            runCatching {
                if (file.exists() && file.length() > MAX_FILE_BYTES) {
                    // simple rotate: keep tail half
                    val text = file.readText()
                    val keepFrom = (text.length / 2).coerceAtLeast(0)
                    file.writeText(text.substring(keepFrom))
                }
                file.appendText(sb.toString())
            }
        }
    }
}

