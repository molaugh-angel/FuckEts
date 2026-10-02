package com.fuckets.etsviewer

import java.io.File
import java.util.concurrent.TimeUnit

/**
 * 运行在 Shizuku 拉起的 shell 进程中的文件服务（Shizuku UserService）。
 *
 * 注意：
 * 1. 这里**不要**使用 [AppLog] —— 它依赖 App 进程的 Context 与初始化，在 shell 进程里会崩。
 *    失败原因统一存进 [lastError]，由 App 进程取回后写日志。
 * 2. 类必须 public、有无参构造，Shizuku 服务端通过反射实例化它。
 * 3. 优先用 Java File API（最快最稳），exec 作为兜底通道。
 */
class UserFsService : IUserFsService.Stub() {

    companion object {
        /** 字段分隔符（SOH）。JSON 规范要求控制字符必须转义，所以原始 JSON 里不可能出现它 */
        private const val FS = "\u0001"
    }

    @Volatile
    private var lastErr: String? = null

    private fun fail(msg: String, e: Throwable? = null): Nothing? {
        lastErr = if (e == null) msg else "$msg：${e.javaClass.simpleName} ${e.message}"
        return null
    }

    /**
     * 批量扫描：一次调用读出 basePath 下所有子文件夹的名称、修改时间、content.json 内容，
     * 避免客户端逐文件夹 IPC（N 个文件夹 = N+1 次 Binder 往返）。
     * 分页是防 Binder 事务 ~1MB 上限：按名称稳定排序后切片，保证多页之间不漏不重。
     */
    override fun loadEntries(basePath: String, offset: Int, limit: Int): Array<String>? {
        val dir = File(basePath)
        if (!dir.isDirectory) return fail("批量扫描失败：不是目录或不存在 $basePath")
        val children = dir.listFiles() ?: return fail("批量扫描失败：无法读取 $basePath")
        val dirs = children.filter { it.isDirectory }.sortedBy { it.name }
        val from = offset.coerceIn(0, dirs.size)
        val to = (from + limit).coerceAtMost(dirs.size)

        val out = ArrayList<String>(to - from)
        for (f in dirs.subList(from, to)) {
            // 单个文件读取失败不拖垮整批：给空串，客户端按"缺 content.json"处理
            val content = try {
                val cj = File(f, "content.json")
                if (cj.isFile) cj.readText() else ""
            } catch (e: Throwable) {
                ""
            }
            out += f.name + FS + (f.lastModified() / 1000) + FS + content
        }
        return out.toTypedArray().also { lastErr = null }
    }

    override fun listFolders(path: String): Array<String>? {
        val dir = File(path)
        if (!dir.isDirectory) return fail("列目录失败：不是目录或不存在 $path")
        val children = dir.listFiles() ?: return fail("列目录失败：无法读取 $path")
        val out = mutableListOf<String>()
        for (f in children) {
            if (!f.isDirectory) continue
            out += "${f.name}|${f.lastModified() / 1000}"
        }
        return out.toTypedArray().also { lastErr = null }
    }

    override fun readFile(path: String): String? = try {
        File(path).readText().also { lastErr = null }
    } catch (e: Throwable) {
        fail("读取失败 $path", e)
    }

    override fun exec(cmd: String): String? {
        return try {
            // stderr 合并进 stdout，单线程读完再 waitFor，避免管道写满导致死锁
            val p = ProcessBuilder("sh", "-c", cmd)
                .redirectErrorStream(true)
                .start()
            val out = p.inputStream.bufferedReader().use { it.readText() }
            val done = p.waitFor(20, TimeUnit.SECONDS)
            if (!done) {
                p.destroyForcibly()
                return fail("执行超时：$cmd")
            }
            if (p.exitValue() == 0) {
                lastErr = null
                out
            } else {
                fail("exit=${p.exitValue()}：$out")
            }
        } catch (e: Throwable) {
            fail("执行异常：$cmd", e)
        }
    }

    override fun deleteRecursively(path: String): Boolean = try {
        val ok = File(path).deleteRecursively()
        lastErr = if (ok) null else "删除失败：$path"
        ok
    } catch (e: Throwable) {
        fail("删除异常 $path", e)
        false
    }

    override fun exists(path: String): Boolean = try {
        File(path).exists().also { lastErr = null }
    } catch (e: Throwable) {
        fail("exists 异常 $path", e)
        false
    }

    override fun lastError(): String? = lastErr

    override fun destroy() {
        lastErr = null
    }
}
