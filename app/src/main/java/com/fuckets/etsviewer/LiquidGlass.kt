package com.fuckets.etsviewer

import android.animation.Animator
import android.animation.ObjectAnimator
import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.content.Context
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.RenderEffect
import android.graphics.Shader
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.LayerDrawable
import android.os.Build
import android.view.View
import android.view.ViewGroup
import android.view.animation.AccelerateDecelerateInterpolator
import androidx.core.graphics.ColorUtils

/**
 * 液态玻璃（Liquid Glass）观感的绘制与背景工具。
 *
 * 玻璃质感由三层叠加而来：
 * 1. 半透明底色（浅色模式偏白、深色模式极薄）→ 能看到背后的光斑；
 * 2. 顶部镜面高光（白 → 透明的竖向渐变）→ 模拟玻璃折射；
 * 3. 1dp 亮色描边 → 玻璃边缘的"折射亮边"。
 *
 * 真正的背景模糊在 Android 12（API 31）以上用 RenderEffect 施加到光斑层，
 * 低版本由径向渐变自身足够柔和，视觉上基本一致。
 */
object LiquidGlass {

    /**
     * 背景光斑的真实模糊半径（仅 Android 12+ 生效）。
     * 低端机若觉得滑动发涩，把它改成 0f 即可关掉模糊（观感几乎不变，光斑本身就是柔和渐变）。
     */
    const val BACKDROP_BLUR_PX = 46f

    fun isNight(context: Context): Boolean =
        (context.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) ==
            Configuration.UI_MODE_NIGHT_YES

    private fun dp(context: Context, value: Float): Float =
        value * context.resources.displayMetrics.density

    private fun white(alpha: Float): Int =
        ColorUtils.setAlphaComponent(Color.WHITE, (alpha.coerceIn(0f, 1f) * 255).toInt())

    /**
     * 生成一块玻璃面板背景。
     *
     * @param tintColor  混入的主题色（Part A/B/C 的强调色），传 0 表示中性玻璃
     * @param tintAlpha  主题色混入比例；描边会按更高比例混色，让玻璃边缘带上颜色
     */
    fun surface(
        context: Context,
        radiusTopDp: Float = 26f,
        radiusBottomDp: Float = 26f,
        tintColor: Int = Color.TRANSPARENT,
        tintAlpha: Float = 0f,
        fillAlpha: Float = -1f,
        strokeAlpha: Float = -1f,
        sheenAlpha: Float = -1f
    ): Drawable {
        val night = isNight(context)
        val fill = if (fillAlpha >= 0f) fillAlpha else if (night) 0.10f else 0.55f
        val stroke = if (strokeAlpha >= 0f) strokeAlpha else if (night) 0.20f else 0.60f
        val sheen = if (sheenAlpha >= 0f) sheenAlpha else if (night) 0.12f else 0.30f
        val hasTint = tintAlpha > 0f && tintColor != Color.TRANSPARENT

        val rt = dp(context, radiusTopDp)
        val rb = dp(context, radiusBottomDp)
        val radii = floatArrayOf(rt, rt, rt, rt, rb, rb, rb, rb)

        // ① 底色：白色半透明，按需混入主题色
        var fillColor = white(fill)
        if (hasTint) fillColor = ColorUtils.blendARGB(fillColor, tintColor, tintAlpha)

        var strokeColor = white(stroke)
        if (hasTint) strokeColor = ColorUtils.blendARGB(strokeColor, tintColor, 0.45f)

        val base = GradientDrawable().apply {
            setColor(fillColor)
            cornerRadii = radii
            setStroke(dp(context, 1f).toInt().coerceAtLeast(1), strokeColor)
        }

        // ② 顶部镜面高光
        val sheenLayer = GradientDrawable(
            GradientDrawable.Orientation.TOP_BOTTOM,
            intArrayOf(white(sheen), white(0f))
        ).apply { cornerRadii = radii }

        return LayerDrawable(arrayOf(base, sheenLayer))
    }

    /** 给 Chip 一块玻璃底（半透明 + 彩色描边），替换原来的纯色块 */
    fun chipColors(context: Context, accentColor: Int): Pair<Int, Int> {
        val night = isNight(context)
        val fill = ColorUtils.blendARGB(
            white(if (night) 0.14f else 0.72f),
            accentColor,
            if (night) 0.30f else 0.20f
        )
        val stroke = ColorUtils.setAlphaComponent(accentColor, if (night) 160 else 170)
        return fill to stroke
    }

    /**
     * 启动背景光斑：缓慢漂移 + 缩放（只用 GPU 变换，不触发重绘），
     * Android 12+ 再叠一层真实模糊，让"玻璃背后的世界"是糊的。
     *
     * @return 动画句柄，Activity 销毁时 cancel 掉
     */
    @SuppressLint("NewApi")
    fun applyBackdrop(container: ViewGroup, blurRadius: Float = BACKDROP_BLUR_PX): List<Animator> {
        if (blurRadius > 0f && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            try {
                container.setRenderEffect(
                    RenderEffect.createBlurEffect(blurRadius, blurRadius, Shader.TileMode.CLAMP)
                )
                AppLog.d("Glass", "已启用 RenderEffect 背景模糊（${blurRadius}px）")
            } catch (t: Throwable) {
                AppLog.w("Glass", "RenderEffect 不可用，退化为纯渐变背景", t)
            }
        } else {
            AppLog.d("Glass", "API ${Build.VERSION.SDK_INT} < 31，跳过背景模糊（渐变本身已足够柔和）")
        }

        val animators = mutableListOf<Animator>()
        val density = container.resources.displayMetrics.density
        for (i in 0 until container.childCount) {
            val child = container.getChildAt(i)
            val amp = (64 + i * 28) * density
            val duration = 9_000L + i * 2_300L
            val anims = listOf(
                ObjectAnimator.ofFloat(child, View.TRANSLATION_X, -amp, amp),
                ObjectAnimator.ofFloat(child, View.TRANSLATION_Y, amp * 0.55f, -amp * 0.55f),
                ObjectAnimator.ofFloat(child, View.SCALE_X, 1f, 1.18f),
                ObjectAnimator.ofFloat(child, View.SCALE_Y, 1f, 1.18f)
            )
            for (a in anims) {
                a.duration = duration
                a.repeatCount = ValueAnimator.INFINITE
                a.repeatMode = ValueAnimator.REVERSE
                a.interpolator = AccelerateDecelerateInterpolator()
                a.start()
                animators.add(a)
            }
        }
        AppLog.d("Glass", "背景光斑动画启动：${container.childCount} 个光斑")
        return animators
    }
}
