package com.fuckets.etsviewer

import android.animation.Animator
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.view.MenuItem
import android.view.ViewGroup
import androidx.appcompat.app.AppCompatActivity
import com.fuckets.etsviewer.databinding.ActivityAboutBinding
import java.util.Calendar

/** 关于页：应用标志、版本、开发者、运行环境与技术栈 */
class AboutActivity : AppCompatActivity() {

    private lateinit var binding: ActivityAboutBinding
    private val blobAnimators = mutableListOf<Animator>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityAboutBinding.inflate(layoutInflater)
        setContentView(binding.root)
        setSupportActionBar(binding.toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)

        // 液态玻璃：光斑动画 + 三张玻璃卡片（与主界面一致）
        val blobLayer: ViewGroup = findViewById(R.id.blobLayerAbout)
        blobAnimators.addAll(LiquidGlass.applyBackdrop(blobLayer))
        val glass = { r: Float -> LiquidGlass.surface(this, radiusTopDp = r, radiusBottomDp = r) }
        binding.cardInfo.background = glass(22f)
        binding.cardIntro.background = glass(22f)
        binding.cardTech.background = glass(22f)

        fillVersion()
        fillDevice()
        fillTech()

        binding.btnLog.setOnClickListener {
            AppLog.i("About", "从关于页打开日志")
            startActivity(Intent(this, LogActivity::class.java))
        }

        AppLog.i("About", "关于页打开：${binding.tvVersion.text}")
    }

    /** 版本号走 PackageManager 读取，不依赖 BuildConfig（AGP 8 起默认不生成） */
    private fun fillVersion() {
        val version = try {
            @Suppress("DEPRECATION")
            val pi = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                packageManager.getPackageInfo(packageName, PackageManager.PackageInfoFlags.of(0))
            } else {
                packageManager.getPackageInfo(packageName, 0)
            }
            val name = pi.versionName ?: "—"
            val code = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                pi.longVersionCode
            } else {
                @Suppress("DEPRECATION") pi.versionCode.toLong()
            }
            "$name（构建号 $code）"
        } catch (e: Exception) {
            AppLog.w("About", "读取版本信息失败", e)
            "—"
        }
        binding.tvVersion.text = getString(R.string.about_version_fmt, version)
        binding.tvDeveloper.text = getString(R.string.about_developer_name)
        binding.tvPackage.text = packageName

        val shizuku = when {
            !ShizukuHelper.isAvailable -> "未运行"
            !ShizukuHelper.hasPermission -> "已运行 · 未授权"
            else -> "已授权 · ${ShizukuHelper.serverVersion()}"
        }
        binding.tvShizuku.text = shizuku

        val year = Calendar.getInstance().get(Calendar.YEAR)
        binding.tvCopyright.text = "© $year ${getString(R.string.app_name)}"
    }

    private fun fillDevice() {
        binding.tvDevice.text = "${Build.MANUFACTURER} ${Build.MODEL}".replace("_", " ")
        binding.tvSystem.text = "Android ${Build.VERSION.RELEASE}（API ${Build.VERSION.SDK_INT}）"
    }

    private fun fillTech() {
        binding.tvTech.text = listOf(
            "Kotlin · Android SDK 34 · minSdk 26",
            "Material Components 1.12.0（Material 3 主题）",
            "Shizuku API 13.0.0（shell 权限执行）",
            "AndroidX：AppCompat / RecyclerView / SwipeRefreshLayout / CoordinatorLayout / Core-KTX",
            "液态玻璃：RenderEffect 背景模糊 + LayerDrawable 分层高光（自绘，无三方库）",
            "org.json 解析 content.json"
        ).joinToString("\n")
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        if (item.itemId == android.R.id.home) {
            finish()
            return true
        }
        return super.onOptionsItemSelected(item)
    }

    override fun onPause() {
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
        super.onDestroy()
    }
}
