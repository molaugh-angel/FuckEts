package com.fuckets.etsviewer

import android.content.Context
import android.content.pm.ApplicationInfo
import android.os.Build
import android.util.Log
import java.io.File
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * 轻量日志：同时输出到 Logcat 和本地文件（filesDir/logs/ets-viewer.log）。
 *
 * - Logcat：过滤 `ETSViewer` 即可看到全部日志
 * - 文件：App 内「运行日志」页面可查看 / 复制 / 分享，崩溃时也能保留现场
 */
object AppLog {

    private const val TAG = "ETSViewer"
    /** 文件超过该大小后自动裁剪，只保留最近 MAX_LINES 行 */
    private const val MAX_FILE_BYTES = 2L * 1024 * 1024
    private const val MAX_LINES = 3000
    /** 内存中保留的最近日志条数，崩溃前来不及落盘时兜底 */
    private const val MEMORY_MAX = 800

    private val stampFmt = DateTimeFormatter.ofPattern("MM-dd HH:mm:ss.SSS")
    private val executor = Executors.newSingleThreadExecutor { r ->
        Thread(r, "ets-log").apply { isDaemon = true }
    }
    private val memory = ArrayDeque<String>()

    private var file: File? = null

    /**
     * 是否记录 DEBUG 级日志。默认跟随构建类型：debug 包开、release 包关
     * （release 下每次加载会产生几百条 D 级日志，纯浪费字符串拼接与文件 I/O）。
     * 排障时可在代码里手动改回 true。
     */
    @Volatile
    var verbose = true

    fun init(context: Context) {
        val dir = File(context.filesDir, "logs").apply { if (!exists()) mkdirs() }
        file = File(dir, "ets-viewer.log")
        verbose = (context.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0
        i("AppLog", "===== 会话开始 =====")
        i("AppLog", "设备=${Build.MANUFACTURER} ${Build.MODEL}, Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})")
        i("AppLog", "日志文件=${file?.absolutePath}，DEBUG 日志=${if (verbose) "开" else "关（release 构建）"}")
    }

    fun d(tag: String, msg: String, t: Throwable? = null) {
        if (verbose) write('D', tag, msg, t)
    }

    fun i(tag: String, msg: String, t: Throwable? = null) = write('I', tag, msg, t)
    fun w(tag: String, msg: String, t: Throwable? = null) = write('W', tag, msg, t)
    fun e(tag: String, msg: String, t: Throwable? = null) = write('E', tag, msg, t)

    /** 长文本按上限截断，避免一条日志刷屏 */
    fun truncate(text: String?, limit: Int = 500): String {
        if (text == null) return "<null>"
        val oneLine = text.replace('\n', '⏎')
        return if (oneLine.length <= limit) oneLine else oneLine.substring(0, limit) + "…(共${text.length}字符)"
    }

    private fun write(level: Char, tag: String, msg: String, t: Throwable?) {
        val fullTag = "$TAG/$tag"
        when (level) {
            'D' -> Log.d(fullTag, msg, t)
            'I' -> Log.i(fullTag, msg, t)
            'W' -> Log.w(fullTag, msg, t)
            else -> Log.e(fullTag, msg, t)
        }

        val line = buildString {
            append(LocalDateTime.now().format(stampFmt))
            append(' ').append(level).append('/').append(tag).append(": ").append(msg)
            if (t != null) append('\n').append(Log.getStackTraceString(t))
        }
        synchronized(memory) {
            memory.addLast(line)
            while (memory.size > MEMORY_MAX) memory.removeFirst()
        }
        executor.execute { appendFile(line) }
    }

    private fun appendFile(line: String) {
        val f = file ?: return
        try {
            if (f.length() > MAX_FILE_BYTES) trim(f)
            f.appendText(line + "\n", Charsets.UTF_8)
        } catch (e: Throwable) {
            Log.w(TAG, "写日志文件失败", e)
        }
    }

    private fun trim(f: File) {
        try {
            val keep = f.readLines().takeLast(MAX_LINES)
            f.writeText("…（旧日志已自动裁剪）…\n" + keep.joinToString("\n", postfix = "\n"))
        } catch (e: Throwable) {
            Log.w(TAG, "裁剪日志文件失败", e)
        }
    }

    /** 等待已排队的日志落盘（崩溃前调用） */
    fun flush() {
        val latch = CountDownLatch(1)
        executor.execute { latch.countDown() }
        try {
            latch.await(1, TimeUnit.SECONDS)
        } catch (_: InterruptedException) {
        }
    }

    /** 全部日志（内存 + 文件），供日志页展示 */
    fun readAll(): String {
        val mem: List<String> = synchronized(memory) { memory.toList() }
        val f = file
        val disk = if (f != null && f.exists()) {
            try {
                f.readText()
            } catch (e: Throwable) {
                "读取日志文件失败：$e"
            }
        } else {
            "<日志文件尚未创建>"
        }
        return buildString {
            append("设备：").append(Build.MODEL).append(" / Android ")
            append(Build.VERSION.RELEASE).append(" / API ").append(Build.VERSION.SDK_INT).append('\n')
            append("路径：").append(f?.absolutePath).append('\n')
            append("———— 最近日志（内存 ").append(mem.size).append(" 条）————\n")
            mem.forEach { append(it).append('\n') }
            append("\n———— 完整日志文件 ————\n")
            append(disk)
        }
    }

    fun clear() {
        synchronized(memory) { memory.clear() }
        executor.execute {
            try {
                file?.writeText("")
            } catch (e: Throwable) {
                Log.w(TAG, "清空日志失败", e)
            }
        }
    }

    fun filePath(): String? = file?.absolutePath
}
