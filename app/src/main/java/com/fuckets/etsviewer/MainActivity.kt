package com.fuckets.etsviewer

import android.animation.Animator
import android.content.Intent
import android.os.Bundle
import android.view.Menu
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.fuckets.etsviewer.databinding.ActivityMainBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import rikka.shizuku.Shizuku

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private val adapter = GroupAdapter()
    private val blobAnimators = mutableListOf<Animator>()

    private val permListener = Shizuku.OnRequestPermissionResultListener { requestCode, grantResult ->
        AppLog.i("Main", "权限回调 requestCode=$requestCode, grantResult=$grantResult")
        if (grantResult == android.content.pm.PackageManager.PERMISSION_GRANTED) {
            loadData()
        } else {
            showStatus("Shizuku 权限被拒绝，无法读取受限目录")
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        AppLog.i("Main", "onCreate")
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        setSupportActionBar(binding.toolbar)

        // ---- 液态玻璃：背景光斑 + 玻璃面板 ----
        val blobLayer: ViewGroup = findViewById(R.id.blobLayer)
        blobAnimators.addAll(LiquidGlass.applyBackdrop(blobLayer))
        binding.toolbar.background = LiquidGlass.surface(this, radiusTopDp = 0f, radiusBottomDp = 26f)
        binding.statusText.background = LiquidGlass.surface(this, radiusTopDp = 24f, radiusBottomDp = 24f)
        binding.swipeRefresh.setColorSchemeResources(
            R.color.part_a_accent, R.color.part_b_accent, R.color.part_c_accent
        )

        binding.recyclerView.layoutManager = LinearLayoutManager(this)
        binding.recyclerView.adapter = adapter
        binding.swipeRefresh.setOnRefreshListener {
            AppLog.i("Main", "用户下拉刷新")
            ensurePermissionAndLoad()
        }

        // 长按删除：整组 / 单个 Part
        adapter.onRequestDeleteGroup = { index, group ->
            val names = group.parts.map { it.folderName }.distinct()
            confirmDelete(
                "删除第 $index 组？",
                "将永久删除该组的 ${names.size} 个资源文件夹：\n" +
                    names.joinToString("\n") { "· $it" }
            ) { doDelete(names) }
        }
        adapter.onRequestDeletePart = { part ->
            confirmDelete(
                "删除 ${part.title}？",
                "将永久删除资源文件夹：\n· ${part.folderName}"
            ) { doDelete(listOf(part.folderName)) }
        }

        Shizuku.addRequestPermissionResultListener(permListener)
        ensurePermissionAndLoad()
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.menu_main, menu)
        // 顶栏只有一个"更多"按钮（actionLayout），点击弹出液态玻璃菜单
        menu.findItem(R.id.action_more)?.actionView?.setOnClickListener { anchor ->
            showGlassMenu(anchor)
        }
        return true
    }

    /** 顶栏"更多"菜单：展开/折叠为一组，日志/关于为一组，中间分隔线 */
    private fun showGlassMenu(anchor: View) {
        AppLog.d("Main", "打开玻璃菜单")
        GlassMenuPopup.show(
            anchor = anchor,
            items = listOf(
                GlassMenuPopup.Item(R.drawable.ic_unfold_more, "展开全部") {
                    AppLog.i("Main", "菜单：展开全部")
                    adapter.expandAll(true)
                },
                GlassMenuPopup.Item(R.drawable.ic_unfold_less, "折叠全部") {
                    AppLog.i("Main", "菜单：折叠全部")
                    adapter.expandAll(false)
                },
                GlassMenuPopup.Item(R.drawable.ic_log, "运行日志") {
                    AppLog.i("Main", "打开日志页")
                    startActivity(Intent(this, LogActivity::class.java))
                },
                GlassMenuPopup.Item(R.drawable.ic_about, "关于") {
                    AppLog.i("Main", "打开关于页")
                    startActivity(Intent(this, AboutActivity::class.java))
                }
            ),
            dividerAfter = setOf(1)   // "折叠全部"后面分组
        )
    }

    override fun onPause() {
        // 退后台暂停背景光斑动画，省电省 GPU
        blobAnimators.forEach { it.pause() }
        super.onPause()
    }

    override fun onResume() {
        super.onResume()
        blobAnimators.forEach { it.resume() }
    }

    override fun onDestroy() {
        blobAnimators.forEach { it.cancel() }
        blobAnimators.clear()
        ShizukuHelper.releaseUserService(this)
        Shizuku.removeRequestPermissionResultListener(permListener)
        AppLog.i("Main", "onDestroy")
        super.onDestroy()
    }

    private fun ensurePermissionAndLoad() {
        val available = ShizukuHelper.isAvailable
        val granted = ShizukuHelper.hasPermission
        AppLog.i("Main", "检查 Shizuku: available=$available, granted=$granted, version=${ShizukuHelper.serverVersion()}")
        ShizukuHelper.dumpServerInfo()
        when {
            !available ->
                showStatus("Shizuku 未运行。请先启动 Shizuku 服务后下拉刷新重试。")
            !granted -> {
                showStatus("正在申请 Shizuku 权限…")
                ShizukuHelper.requestPermission()
            }
            else -> loadData()
        }
    }

    private fun loadData() {
        binding.swipeRefresh.isRefreshing = true
        AppLog.i("Main", "开始加载数据 path=${ResourceRepository.BASE_PATH}")
        val start = System.currentTimeMillis()
        lifecycleScope.launch {
            // 新版 Shizuku 已禁用 Shizuku.newProcess()，先把 UserService 通道建起来
            val viaService = ShizukuHelper.prepareUserService(this@MainActivity)
            AppLog.i("Main", "Shizuku 通道：${if (viaService) "UserService" else "newProcess（回退）"}")
            val groups = withContext(Dispatchers.IO) { ResourceRepository.load() }
            val cost = System.currentTimeMillis() - start
            binding.swipeRefresh.isRefreshing = false
            if (groups.isEmpty()) {
                AppLog.w("Main", "加载完成但无数据（耗时 ${cost}ms，通道=${if (viaService) "UserService" else "newProcess"}）")
                showStatus(
                    if (viaService) "没有找到可展示的数据（目录为空或所有文件夹都缺少 content.json）"
                    else "读取失败：Shizuku 命令通道不可用。请确认 Shizuku 已运行并已授权，下拉刷新重试；仍失败请查看运行日志。"
                )
            } else {
                AppLog.i("Main", "加载完成：${groups.size} 组，耗时 ${cost}ms")
                binding.statusText.visibility = View.GONE
                adapter.submit(groups)
            }
        }
    }

    /** 删除前二次确认，避免误触 */
    private fun confirmDelete(title: String, detail: String, onConfirm: () -> Unit) {
        MaterialAlertDialogBuilder(this)
            .setTitle(title)
            .setMessage("$detail\n\n此操作不可恢复，确认删除？")
            .setNegativeButton("取消", null)
            .setPositiveButton("删除") { _, _ ->
                AppLog.i("Main", "用户确认删除：$title")
                onConfirm()
            }
            .show()
    }

    /** 在 IO 线程执行删除，完成后提示结果并重新加载列表 */
    private fun doDelete(names: List<String>) {
        if (!ShizukuHelper.isAvailable || !ShizukuHelper.hasPermission) {
            Toast.makeText(this, "Shizuku 未就绪，无法删除", Toast.LENGTH_LONG).show()
            AppLog.w("Main", "删除取消：Shizuku 不可用或无权限")
            return
        }
        binding.swipeRefresh.isRefreshing = true
        AppLog.i("Main", "开始删除 ${names.size} 个文件夹：$names")
        val start = System.currentTimeMillis()
        lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) { ResourceRepository.deleteFolders(names) }
            val cost = System.currentTimeMillis() - start
            binding.swipeRefresh.isRefreshing = false
            val msg = if (result.failed.isEmpty()) {
                "已删除 ${result.deleted} 个文件夹（${cost}ms）"
            } else {
                "删除 ${result.deleted} 个成功，${result.failed.size} 个失败：${result.failed.joinToString()}"
            }
            AppLog.i("Main", "删除结果：$msg")
            Toast.makeText(this@MainActivity, msg, Toast.LENGTH_LONG).show()
            loadData()
        }
    }

    private fun showStatus(msg: String) {
        AppLog.w("Main", "状态提示：$msg")
        binding.swipeRefresh.isRefreshing = false
        binding.statusText.visibility = View.VISIBLE
        binding.statusText.text = msg
        adapter.submit(emptyList())
    }
}
