package com.fuckets.etsviewer

import android.content.ComponentName
import android.content.Context
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.IBinder
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import rikka.shizuku.Shizuku
import java.io.BufferedReader
import java.io.InputStreamReader

/**
 * Shizuku 权限与文件访问封装。
 *
 * 有两条通道，自动择优：
 * 1. **UserService（优先）**：Shizuku 拉起一个以 shell(2000) 身份运行的进程，
 *    我们在其中用 Java File API / Runtime.exec 干活。这是官方现在推荐的唯一方式。
 * 2. **newProcess（兜底）**：旧版 Shizuku 支持 `Shizuku.newProcess()`；
 *    新服务端已把它禁用（调用会抛 SecurityException: Permission Denial），
 *    所以只在 UserService 不可用时才尝试。
 */
object ShizukuHelper {

    @Volatile
    private var userService: IUserFsService? = null

    private var connection: ServiceConnection? = null

    /** UserService 是否已就绪 */
    val userServiceReady: Boolean
        get() = userService != null

    val isAvailable: Boolean
        get() = try {
            Shizuku.pingBinder()
        } catch (e: Throwable) {
            AppLog.w("Shizuku", "pingBinder 异常", e)
            false
        }

    val hasPermission: Boolean
        get() = try {
            Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED
        } catch (e: Throwable) {
            AppLog.w("Shizuku", "checkSelfPermission 异常", e)
            false
        }

    /** Shizuku 服务端版本号，异常时返回描述而不是崩溃 */
    fun serverVersion(): String = try {
        val v = Shizuku.getVersion()
        "v$v"
    } catch (e: Throwable) {
        "未知(${e.message})"
    }

    /** 记录服务端能力，方便排查"为什么这条命令跑不通" */
    fun dumpServerInfo() {
        val info = try {
            "version=${Shizuku.getVersion()}, " +
                "patch=${Shizuku.getServerPatchVersion()}, " +
                "latestApi=${Shizuku.getLatestServiceVersion()}, " +
                "uid=${Shizuku.getUid()}, " +
                "selinux=${Shizuku.getSELinuxContext()}"
        } catch (e: Throwable) {
            "读取失败：${e.message}"
        }
        AppLog.i("Shizuku", "服务端信息：$info")
    }

    fun requestPermission(requestCode: Int = 100) {
        AppLog.i("Shizuku", "申请权限 requestCode=$requestCode")
        if (isAvailable && !hasPermission) {
            Shizuku.requestPermission(requestCode)
        }
    }

    /**
     * 绑定 UserService 并等待就绪。超时 / 服务端不支持时返回 false，调用方会回退到 newProcess。
     * 可以重复调用：已连接则直接返回 true。
     */
    suspend fun prepareUserService(context: Context, timeoutMs: Long = 8000L): Boolean {
        if (userService != null) return true
        if (!isAvailable || !hasPermission) {
            AppLog.w("Shizuku", "跳过绑定 UserService：服务不可用或权限未授予")
            return false
        }

        val deferred = CompletableDeferred<Boolean>()
        val className = UserFsService::class.java.name
        AppLog.i("Shizuku", "开始绑定 UserService：$className")

        try {
            withContext(Dispatchers.Main) {
                val args = Shizuku.UserServiceArgs(
                    ComponentName(context.packageName, className)
                )
                    .daemon(false)
                    .processNameSuffix("-fs")
                    .version(1)

                val conn = object : ServiceConnection {
                    override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
                        val binder = service
                        if (binder == null) {
                            AppLog.e("Shizuku", "UserService 连接但 binder 为空")
                            deferred.complete(false)
                            return
                        }
                        userService = IUserFsService.Stub.asInterface(binder)
                        AppLog.i("Shizuku", "UserService 已连接：$className")
                        deferred.complete(true)
                    }

                    override fun onServiceDisconnected(name: ComponentName?) {
                        userService = null
                        AppLog.w("Shizuku", "UserService 已断开")
                        deferred.complete(false)
                    }
                }
                connection = conn
                Shizuku.bindUserService(args, conn)
            }
        } catch (e: Throwable) {
            AppLog.e("Shizuku", "bindUserService 失败，将回退 newProcess", e)
            return false
        }

        val ok = withTimeoutOrNull(timeoutMs) { deferred.await() } ?: false
        if (!ok) {
            AppLog.w("Shizuku", "UserService 未就绪（超时 ${timeoutMs}ms），将回退 newProcess")
        }
        return ok
    }

    /** 解绑 UserService（退出时需要） */
    fun releaseUserService(context: Context) {
        val conn = connection ?: return
        try {
            val args = Shizuku.UserServiceArgs(
                ComponentName(context.packageName, UserFsService::class.java.name)
            ).daemon(false).processNameSuffix("-fs").version(1)
            Shizuku.unbindUserService(args, conn, true)
            AppLog.i("Shizuku", "UserService 已解绑")
        } catch (e: Throwable) {
            AppLog.w("Shizuku", "解绑 UserService 失败", e)
        }
        userService = null
        connection = null
    }

    // ---------------------------------------------------------------- 文件访问

    /** 一个资源文件夹的扫描结果：名称 / 修改时间秒 / content.json 内容（无或读取失败为 null） */
    data class FsEntry(val name: String, val tsSeconds: Long, val content: String?)

    /** 字段分隔符 SOH / 记录分隔符 STX。合法 JSON 必须转义控制字符，所以原文里不可能出现它们 */
    private const val FS = "\u0001"
    private const val RS = "\u0002"

    /** 每页条数：Binder 事务有 ~1MB 上限，按 50 条/页（通常 < 500KB）分页，防 TransactionTooLargeException */
    private const val PAGE_SIZE = 50

    /**
     * 批量扫描资源目录：把"列目录 + 逐文件读 content.json"的 N+1 次 IPC/进程调用合并掉。
     * UserService 通道一次分页循环；回退通道一条 shell 命令输出全部（控制字符分隔，单次进程）。
     * 两条通道都失败返回 null。
     */
    fun loadEntries(basePath: String): List<FsEntry>? {
        // 通道一：UserService 分页拉取（每页一次 IPC，而非每个文件夹一次）
        userService?.let { svc ->
            val all = ArrayList<FsEntry>()
            var offset = 0
            var failed = false
            try {
                while (true) {
                    val arr = svc.loadEntries(basePath, offset, PAGE_SIZE)
                    if (arr == null) {
                        AppLog.w("Shell", "批量扫描（UserService）第 ${offset / PAGE_SIZE + 1} 页失败：${svc.lastError()}")
                        failed = true
                        break
                    }
                    arr.mapNotNullTo(all) { parseEntry(it) }
                    if (arr.size < PAGE_SIZE) break
                    offset += arr.size
                }
            } catch (e: Throwable) {
                AppLog.e("Shell", "批量扫描（UserService）异常：$basePath", e)
                failed = true
            }
            if (!failed) {
                AppLog.d("Shell", "批量扫描（UserService）：$basePath → ${all.size} 项")
                return all
            }
        }

        // 通道二：一条 shell 命令完成全部读取（回退通道下每个 cat 原本都是一个独立进程，这里合并为一个）
        val cmd = "cd '$basePath' 2>/dev/null && for d in */; do [ -d \"\$d\" ] || continue; " +
            "d=\"\${d%/}\"; printf '%s\\001%s\\001' \"\$d\" \"\$(stat -c %Y \"\$d\")\"; " +
            "cat \"\$d/content.json\" 2>/dev/null; printf '\\002'; done"
        val out = exec(cmd) ?: run {
            AppLog.e("Shell", "批量扫描（shell）失败，path=$basePath")
            return null
        }
        val entries = out.split(RS).mapNotNull { parseEntry(it) }
        AppLog.d("Shell", "批量扫描（shell）：$basePath → ${entries.size} 项")
        return entries
    }

    /** 解析 "名称\u0001时间秒\u0001内容" 条目；内容空串视为 null（与"无 content.json"一致） */
    private fun parseEntry(raw: String): FsEntry? {
        if (raw.isBlank()) return null
        val parts = raw.split(FS, limit = 3)
        if (parts.size < 2) return null
        val name = parts[0].trim()
        if (name.isEmpty()) return null
        val ts = parts[1].trim().toLongOrNull() ?: 0L
        val content = parts.getOrNull(2)?.trim()?.ifEmpty { null }
        return FsEntry(name, ts, content)
    }

    /** 列出目录下子文件夹，返回 "名称|修改时间秒" 行；失败返回 null */
    fun listFolders(path: String): List<String>? {
        userService?.let { svc ->
            try {
                val arr = svc.listFolders(path)
                if (arr != null) {
                    AppLog.d("Shell", "列目录（UserService）：$path → ${arr.size} 项")
                    return arr.toList()
                }
                AppLog.w("Shell", "列目录（UserService）失败：${svc.lastError()}")
            } catch (e: Throwable) {
                AppLog.e("Shell", "列目录（UserService）异常：$path", e)
            }
        }
        return exec("cd '$path' && for d in */; do d=\"\${d%/}\"; echo \"\$(stat -c %Y \"\$d\")|\$d\"; done")
            ?.lines()?.filter { it.isNotBlank() }
    }

    /** 读取文件内容；失败返回 null */
    fun readFile(path: String): String? {
        userService?.let { svc ->
            try {
                val text = svc.readFile(path)
                if (text != null) return text
                AppLog.w("Shell", "读取（UserService）失败：${svc.lastError()}")
            } catch (e: Throwable) {
                AppLog.e("Shell", "读取（UserService）异常：$path", e)
            }
        }
        return exec("cat '$path'")
    }

    /** 递归删除；删除后用 exists 复核（UserService 通道下）或 ls 复核（命令通道下） */
    fun deleteRecursive(path: String): Boolean {
        userService?.let { svc ->
            try {
                if (svc.deleteRecursively(path) && !svc.exists(path)) return true
                AppLog.e("Shell", "删除（UserService）失败：${svc.lastError()}")
            } catch (e: Throwable) {
                AppLog.e("Shell", "删除（UserService）异常：$path", e)
            }
        }
        exec("rm -rf '$path'") ?: return false
        // 命令通道下再 ls 复核一次，避免"命令成功但没删掉"的假成功
        val still = exec("ls -d '$path' 2>/dev/null")
        return still.isNullOrBlank()
    }

    /** 以 shell 权限执行命令，返回 stdout 文本；失败返回 null */
    @Suppress("DEPRECATION") // newProcess 是故意保留的旧版 Shizuku 兜底通道
    fun exec(cmd: String): String? {
        if (!isAvailable || !hasPermission) {
            AppLog.w("Shizuku", "exec 跳过：服务不可用或权限未授予")
            return null
        }

        // 通道一：UserService（在 shell 进程里执行，不受 newProcess 限制）
        userService?.let { svc ->
            val t0 = System.currentTimeMillis()
            return try {
                AppLog.d("Shell", "执行（UserService）：${AppLog.truncate(cmd, 400)}")
                val out = svc.exec(cmd)
                val cost = System.currentTimeMillis() - t0
                when {
                    out == null -> {
                        AppLog.e("Shell", "失败（UserService）：${cost}ms，${svc.lastError()}")
                        null
                    }
                    out.isBlank() -> {
                        AppLog.w("Shell", "成功但 stdout 为空（UserService）：${cost}ms")
                        out
                    }
                    else -> {
                        AppLog.d("Shell", "成功（UserService）：${cost}ms，${out.length} 字符")
                        out
                    }
                }
            } catch (e: Throwable) {
                AppLog.e("Shell", "执行异常（UserService）：${AppLog.truncate(cmd, 200)}", e)
                null
            }
        }

        // 通道二：newProcess（旧版 Shizuku 可用，新版服务端会抛 SecurityException）
        val t0 = System.currentTimeMillis()
        return try {
            AppLog.d("Shell", "执行（newProcess）：${AppLog.truncate(cmd, 400)}")
            val process = Shizuku.newProcess(arrayOf("sh", "-c", cmd), null, null)
            val sb = StringBuilder()
            BufferedReader(InputStreamReader(process.inputStream)).use { r ->
                var line: String?
                while (r.readLine().also { line = it } != null) {
                    sb.append(line).append('\n')
                }
            }
            val err = StringBuilder()
            BufferedReader(InputStreamReader(process.errorStream)).use { r ->
                var line: String?
                while (r.readLine().also { line = it } != null) {
                    err.append(line).append('\n')
                }
            }
            val code = process.waitFor()
            process.destroy()
            val cost = System.currentTimeMillis() - t0
            if (code == 0) {
                AppLog.d("Shell", "成功（newProcess）：${cost}ms，stdout ${sb.length} 字符")
                if (sb.isBlank()) AppLog.w("Shell", "命令成功但 stdout 为空")
                sb.toString()
            } else {
                AppLog.e(
                    "Shell",
                    "失败（newProcess）：exit=$code，${cost}ms，stderr=${AppLog.truncate(err.toString(), 600)}"
                )
                null
            }
        } catch (e: Throwable) {
            AppLog.e("Shell", "执行异常（newProcess）：${AppLog.truncate(cmd, 200)}", e)
            null
        }
    }
}
