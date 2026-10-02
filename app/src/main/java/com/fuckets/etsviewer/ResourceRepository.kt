package com.fuckets.etsviewer

import androidx.core.text.HtmlCompat
import org.json.JSONArray
import org.json.JSONObject

/** 一个资源文件夹 */
data class ResourceFolder(
    val name: String,      // 文件夹名
    val timestamp: Long,   // 排序用时间（文件夹修改时间或名称解析出的时间）
    val contentJson: String? // content.json 内容，无则为 null
)

enum class PartType { A, B, C }

/** 一组（最多 3 个文件夹，按 Part A / B / C 排序） */
data class PartGroup(
    val parts: List<Part>
)

data class Part(
    val type: PartType,
    val folderName: String,
    val title: String,          // 例如 "Part A (collector.read)"
    val displayText: String     // 要展示的 value 内容（已去掉 HTML 标签）
)

/** 每道题最多保留的答案条数（Part B） */
const val MAX_ANSWERS_PER_QUESTION = 3

/** Part C 每组作答只保留 1 条（多条答案内容高度重复，只看最好的一条） */
const val MAX_ANSWERS_PART_C = 1

/** 删除结果：成功删除的数量 + 删除失败的文件夹名 */
data class DeleteResult(val deleted: Int, val failed: List<String>)

// ---- 预编译正则：cleanText / 解析在加载时会被调用几百次，Regex 构造即编译，提升为常量避免重复编译 ----
private val BR_TAG_REGEX = Regex("(?i)</?br\\s*/?>")
private val TRAILING_SPACES_REGEX = Regex("[ \\t]+\\n")
private val MULTI_NEWLINE_REGEX = Regex("\\n{3,}")
private val NAME_TS_REGEX = Regex("(\\d{10,13})")
private val KEYPOINT_NUM_REGEX = Regex("^\\d+\\s*[.、)]\\s*.*")

/**
 * 去掉 HTML 标签与实体，并规范换行/空白，提升可读性。
 * <p>、<br> 等块级标签会转成真实换行，<b>/<i> 等样式标签会被丢弃，只保留纯文本。
 */
fun cleanText(raw: String): String {
    if (raw.isBlank()) return ""
    // 先把各种换行标签统一成真实换行（数据里常见 </br>，先把关，避免依赖 Html 解析器的容错）
    val pre = raw.replace(BR_TAG_REGEX, "\n")
    val cleaned = HtmlCompat.fromHtml(pre, HtmlCompat.FROM_HTML_MODE_COMPACT)
        .toString()
        .replace('\u00A0', ' ')                 // &nbsp; → 普通空格
        .replace(TRAILING_SPACES_REGEX, "\n")   // 去掉行尾多余空格
        .replace(MULTI_NEWLINE_REGEX, "\n\n")   // 连续空行压缩为一个
        .trim()
    if (raw.length != cleaned.length) {
        AppLog.d("Text", "剥离 HTML：${raw.length} → ${cleaned.length} 字符，原文=${AppLog.truncate(raw, 120)}")
    }
    return cleaned
}

object ResourceRepository {

    const val BASE_PATH =
        "/storage/emulated/0/Android/data/com.ets100.secondary/files/Download/ETS_SECONDARY/resource"

    /** 排除的文件夹名 */
    private val EXCLUDED = setOf("common")

    /**
     * 加载全部文件夹 → 排除 common → 按时间排序 → 剔除无 content.json 的 →
     * 以 A→B→C 顺序为准分组（顺序回退即另起新组，组内按 A、B、C 排列）→ 解析
     */
    fun load(): List<PartGroup> {
        val all = listFolders()
        AppLog.i("Repo", "扫描到文件夹 ${all.size} 个")
        val noJson = all.count { it.contentJson == null }
        if (noJson > 0) AppLog.w("Repo", "其中 $noJson 个缺少 content.json，已剔除")

        val folders = all
            .filter { it.contentJson != null }          // 无 content.json 的剔除（之后分组即为"重新分组"）
            .sortedBy { it.timestamp }                   // 按时间排序
        AppLog.d("Repo", "排序后: ${folders.map { "${it.name}(${it.timestamp})" }}")

        // 分组：先按时间排好序（上面已 sortedBy timestamp），再以 A→B→C 的顺序为准——
        // 一个 Part 只有当它的类型"排在当前组最后一个类型之后"时才能加入当前组，
        // 一旦顺序回退（如 C 后面来了 A，或同类型重复），说明新的一组开始了，封箱另起。
        // 这样单个异常文件夹（重拍/漏拍）只影响自己所在的边界，不会连锁错位。
        val groups = mutableListOf<PartGroup>()
        var current = mutableListOf<Part>()
        fun closeGroup(reason: String) {
            if (current.isEmpty()) return
            groups.add(PartGroup(current.sortedBy { it.type }))
            AppLog.d("Repo", "第 ${groups.size} 组封箱（$reason）：${current.map { it.type }}")
            current = mutableListOf()
        }
        for (folder in folders) {
            val part = parsePart(folder) ?: continue
            val last = current.lastOrNull()
            if (last != null && part.type <= last.type) {
                closeGroup("顺序回退 ${last.type} → ${part.type}，以 A→B→C 为准另起新组")
            }
            current.add(part)
        }
        closeGroup("收尾")

        // 缺类型的组只是数据不完整，属正常情况，但记录日志方便排查
        groups.forEachIndexed { gi, g ->
            val missing = PartType.values().filter { t -> g.parts.none { it.type == t } }
            if (missing.isNotEmpty()) {
                AppLog.w("Repo", "第 ${gi + 1} 组缺少 ${missing.joinToString("/")}（数据本身不完整，仅提示）")
            }
        }
        AppLog.i("Repo", "分组完成：${folders.size} 个文件夹 → ${groups.size} 组")
        return groups
    }

    /**
     * 物理删除资源文件夹（不可恢复）。
     * 走 ShizukuHelper.deleteRecursive()：UserService 通道下用 File API 删除并复核，
     * 命令通道下用 rm -rf 再 ls 复核；非法文件夹名直接拒绝，防误删。
     */
    fun deleteFolders(names: List<String>): DeleteResult {
        var deleted = 0
        val failed = mutableListOf<String>()
        if (names.isEmpty()) return DeleteResult(0, failed)

        for (name in names) {
            if (!isSafeFolderName(name)) {
                AppLog.e("Repo", "拒绝删除非法文件夹名：$name")
                failed += name
                continue
            }
            val path = "$BASE_PATH/$name"
            if (!ShizukuHelper.deleteRecursive(path)) {
                AppLog.e("Repo", "删除失败：$path")
                failed += name
                continue
            }
            deleted++
            AppLog.i("Repo", "已删除：$path")
        }
        AppLog.i("Repo", "删除完成：成功 $deleted，失败 ${failed.size} $failed")
        return DeleteResult(deleted, failed)
    }

    /** 只接受干净的文件夹名：非空、不含路径分隔符/引号、不是 . 或 .. */
    private fun isSafeFolderName(name: String): Boolean =
        name.isNotBlank() &&
            name != "." && name != ".." &&
            !name.contains("..") &&
            name.none { it == '/' || it == '\'' || it == '"' || it == '\n' || it == '\r' }

    /** 列出 resource 目录下所有文件夹（排除 common），附带时间与 content.json 内容（批量扫描，一次通道调用） */
    private fun listFolders(): List<ResourceFolder> {
        val entries = ShizukuHelper.loadEntries(BASE_PATH)
        if (entries == null) {
            AppLog.e("Repo", "批量扫描失败（两条 Shizuku 通道均不可用），path=$BASE_PATH")
            return emptyList()
        }
        AppLog.d("Repo", "批量扫描返回 ${entries.size} 项")

        return entries.mapNotNull { e ->
            if (e.name in EXCLUDED) return@mapNotNull null
            if (e.content.isNullOrBlank()) {
                AppLog.w("Repo", "[${e.name}] content.json 为空或读取失败")
            }
            // 优先用文件夹名里的时间戳（连续数字串），否则用修改时间
            val nameTs = NAME_TS_REGEX.find(e.name)?.value?.toLongOrNull()
            val sortTs = when {
                nameTs == null -> e.tsSeconds
                nameTs > 9_999_999_999L -> nameTs / 1000 // 毫秒转秒
                else -> nameTs
            }
            ResourceFolder(e.name, sortTs, e.content)
        }
    }

    /** 解析 content.json，按 structure_type 分类并提取正文（纯文本） */
    private fun parsePart(folder: ResourceFolder): Part? {
        val json = folder.contentJson ?: return null
        val root = try {
            JSONObject(json)
        } catch (e: Exception) {
            AppLog.e("Repo", "[${folder.name}] content.json 解析失败，前 300 字符=${AppLog.truncate(json, 300)}", e)
            return null
        }
        val type = root.optString("structure_type")
        val info = root.optJSONObject("info")
        AppLog.d("Repo", "[${folder.name}] structure_type=$type，info 字段=${info?.keys()?.asSequence()?.toList()}")

        val parsed: Pair<PartType, String> = when (type) {
            "collector.read" -> {
                // Part A：info.value（纯文本材料）
                val value = cleanText(info?.optString("value").orEmpty())
                AppLog.d("Repo", "[${folder.name}] Part A 文本 ${value.length} 字符")
                PartType.A to value
            }
            "collector.3q5a" -> {
                // Part B：info 下所有 std[]，每个数组算一道题，每题只保留前 3 条
                val questions = mutableListOf<List<String>>()
                collectStdQuestions(info, questions)
                AppLog.d(
                    "Repo",
                    "[${folder.name}] Part B 解析出 ${questions.size} 题，答案数=${questions.map { it.size }}（每题上限 $MAX_ANSWERS_PER_QUESTION）"
                )
                PartType.B to formatQuestions(questions)
            }
            "collector.picture" -> {
                // Part C：新结构 info.std[]（多条作答，含 value/ai/audio）
                //          兼容老结构 info.stu.value（单条文本）
                //          只取正文，info.keypoint（要点）不展示
                val blocks = mutableListOf<String>()

                val stu = cleanText(info?.optJSONObject("stu")?.optString("value").orEmpty())
                if (stu.isNotEmpty()) blocks += stu

                val questions = mutableListOf<List<String>>()
                collectStdQuestions(info, questions, MAX_ANSWERS_PART_C)
                if (questions.isNotEmpty()) {
                    AppLog.d(
                        "Repo",
                        "[${folder.name}] Part C 解析出 ${questions.size} 组作答，答案数=${questions.map { it.size }}（每组上限 $MAX_ANSWERS_PART_C）"
                    )
                    blocks += formatQuestions(questions)
                }
                AppLog.d("Repo", "[${folder.name}] Part C 文本 ${blocks.sumOf { it.length }} 字符（${blocks.size} 段）")
                PartType.C to blocks.joinToString("\n\n")
            }
            else -> {
                AppLog.w("Repo", "[${folder.name}] 未知 structure_type=$type，跳过")
                return null // 其他类型不展示
            }
        }

        // 要点（keypoint）追加在正文后面；Part C 只需正文，要点一律不展示
        val keypoint = if (parsed.first == PartType.C) {
            if (info?.optString("keypoint").isNullOrBlank().not()) {
                AppLog.d("Repo", "[${folder.name}] Part C 存在 keypoint，按要求不展示")
            }
            ""
        } else {
            formatKeypoint(info?.optString("keypoint").orEmpty())
        }
        val text = when {
            parsed.second.isBlank() -> keypoint
            keypoint.isEmpty() -> parsed.second
            else -> parsed.second + "\n\n" + keypoint
        }
        if (text.isBlank()) AppLog.w("Repo", "[${folder.name}] $type 解析结果为空，请检查字段结构")

        val title = when (parsed.first) {
            PartType.A -> "Part A · collector.read"
            PartType.B -> "Part B · collector.3q5a"
            PartType.C -> "Part C · collector.picture"
        }
        return Part(parsed.first, folder.name, title, text)
    }

    /**
     * 把若干道题的答案拼成易读文本（只有一组时不加"第 N 题"标题；
     * 只有一组且只有一条答案时连"1."编号也省掉，直接输出正文）：
     * 第 1 题
     *   1. …
     *   2. …
     *   3. …
     */
    private fun formatQuestions(questions: List<List<String>>): String = buildString {
        val withTitle = questions.size > 1
        val single = !withTitle && questions.firstOrNull()?.size == 1   // 单条答案：不加编号
        questions.forEachIndexed { qi, answers ->
            if (qi > 0) append("\n\n")
            if (withTitle) append("第 ${qi + 1} 题")
            answers.forEachIndexed { ai, answer ->
                if (withTitle) append("\n  ") else if (ai > 0) append("\n")
                if (!single) append("${ai + 1}. ")
                append(answer)
            }
        }
    }.trim()

    /**
     * 把 keypoint（用 </br> 或换行分隔的要点）整理成带编号的"要点"段落。
     * 原文已有编号（1. / 1、/ 1) ）时保留原编号，否则按顺序补编号。
     */
    private fun formatKeypoint(raw: String): String {
        val cleaned = cleanText(raw)
        if (cleaned.isEmpty()) return ""
        val lines = cleaned.lineSequence()
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .toList()
        if (lines.isEmpty()) return ""
        return buildString {
            append("要点")
            lines.forEachIndexed { i, line ->
                append("\n  ")
                append(if (line.matches(KEYPOINT_NUM_REGEX)) line else "${i + 1}. $line")
            }
        }
    }

    /** 递归找出所有名为 "std" 的数组，每个数组视为一道题，只取前 limit 条 */
    private fun collectStdQuestions(
        node: Any?,
        out: MutableList<List<String>>,
        limit: Int = MAX_ANSWERS_PER_QUESTION
    ) {
        when (node) {
            is JSONObject -> {
                val keys = node.keys()
                while (keys.hasNext()) {
                    val key = keys.next()
                    val child = node.opt(key)
                    if (key == "std" && child is JSONArray) {
                        val take = minOf(limit, child.length())
                        val answers = mutableListOf<String>()
                        for (i in 0 until take) {
                            val raw = when (val item = child.opt(i)) {
                                is JSONObject -> item.optString("value").ifBlank { item.optString("ai") }
                                is String -> item
                                else -> ""
                            }
                            val cleaned = cleanText(raw)
                            if (cleaned.isNotEmpty()) answers.add(cleaned)
                        }
                        if (answers.isNotEmpty()) out.add(answers)
                    } else {
                        collectStdQuestions(child, out, limit)
                    }
                }
            }
            is JSONArray -> {
                for (i in 0 until node.length()) collectStdQuestions(node.opt(i), out, limit)
            }
        }
    }
}
