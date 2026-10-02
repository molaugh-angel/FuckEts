package com.fuckets.etsviewer

import android.animation.Animator
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.fuckets.etsviewer.databinding.ActivityLogBinding

/** 查看 / 复制 / 分享 / 清空本地日志文件 */
class LogActivity : AppCompatActivity() {

    private lateinit var binding: ActivityLogBinding
    private val blobAnimators = mutableListOf<Animator>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityLogBinding.inflate(layoutInflater)
        setContentView(binding.root)
        setSupportActionBar(binding.toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)

        // 液态玻璃：背景光斑 + 玻璃面板（与主界面一致）
        val blobLayer: ViewGroup = findViewById(R.id.blobLayerLog)
        blobAnimators.addAll(LiquidGlass.applyBackdrop(blobLayer))
        binding.scrollView.background =
            LiquidGlass.surface(this, radiusTopDp = 22f, radiusBottomDp = 22f)

        binding.btnRefresh.setOnClickListener { reload() }
        binding.btnCopy.setOnClickListener {
            val text = currentText()
            val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            cm.setPrimaryClip(ClipData.newPlainText("ETS日志", text))
            AppLog.i("LogActivity", "日志已复制到剪贴板（${text.length} 字符）")
            toast("已复制 ${text.length} 字符")
        }
        binding.btnShare.setOnClickListener {
            val text = currentText()
            val send = Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"
                putExtra(Intent.EXTRA_SUBJECT, "ETS资源查看器-运行日志")
                putExtra(Intent.EXTRA_TEXT, text.takeLast(100_000))
            }
            startActivity(Intent.createChooser(send, "导出日志"))
        }
        binding.btnClear.setOnClickListener {
            AppLog.clear()
            binding.logText.text = ""
            AppLog.i("LogActivity", "日志已清空")
            toast("已清空")
        }

        reload()
    }

    private fun reload() {
        binding.logText.text = AppLog.readAll()
        binding.scrollView.post { binding.scrollView.fullScroll(View.FOCUS_DOWN) }
        AppLog.d("LogActivity", "日志页刷新")
    }

    private fun currentText(): String = binding.logText.text?.toString().orEmpty()

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()

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

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        if (item.itemId == android.R.id.home) {
            finish()
            return true
        }
        return super.onOptionsItemSelected(item)
    }
}
