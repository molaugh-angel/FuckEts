package com.fuckets.etsviewer

import android.graphics.Outline
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.LayerDrawable
import android.graphics.Color
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.ViewOutlineProvider
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.PopupWindow
import android.widget.TextView
import androidx.annotation.DrawableRes
import androidx.core.graphics.ColorUtils
import com.google.android.material.color.MaterialColors

/**
 * 液态玻璃风格的溢出菜单（替换系统默认的黑/白直直角弹窗）。
 *
 * 与整 App 同一套观感：半透明玻璃面板 + 顶部高光 + 亮色描边 + 圆角阴影，
 * 行间带图标，支持在指定行后插入分组分隔线。点击外部或返回键自动消失。
 *
 * 用法：
 * ```kotlin
 * GlassMenuPopup.show(
 *     anchor = moreButton,
 *     items = listOf(
 *         GlassMenuPopup.Item(R.drawable.ic_unfold_more, "展开全部") { ... },
 *         GlassMenuPopup.Item(R.drawable.ic_unfold_less, "折叠全部") { ... },
 *         GlassMenuPopup.Item(R.drawable.ic_log, "运行日志") { ... },
 *         GlassMenuPopup.Item(R.drawable.ic_about, "关于") { ... },
 *     ),
 *     dividerAfter = setOf(1)   // 第 2 项后面画一条分隔线
 * )
 * ```
 */
object GlassMenuPopup {

    /** 一个菜单项：图标、标题、点击回调（回调触发时弹窗先关闭） */
    data class Item(
        @DrawableRes val iconRes: Int,
        val title: String,
        val onClick: () -> Unit
    )

    /**
     * 在 [anchor] 下方（右对齐）弹出玻璃菜单。
     *
     * @param dividerAfter 需要在其后插入分隔线的下标集合，例如 setOf(1) 表示第 2 项之后分组
     */
    fun show(anchor: View, items: List<Item>, dividerAfter: Set<Int> = emptySet()) {
        val ctx = anchor.context
        val inflater = LayoutInflater.from(ctx)
        val content = inflater.inflate(R.layout.popup_menu_glass, null) as LinearLayout
        val density = ctx.resources.displayMetrics.density
        val night = LiquidGlass.isNight(ctx)

        // 菜单底色：接近不透明的 colorSurface（白天近白、夜间深色），
        // 下层的光斑/卡片不会透上来干扰文字；只保留一圈玻璃亮边 + 轻微顶部高光。
        val radiusPx = 20f * density
        val surfaceColor = MaterialColors.getColor(
            ctx, com.google.android.material.R.attr.colorSurface,
            if (night) Color.parseColor("#1B1E24") else Color.WHITE
        )
        val fill = ColorUtils.setAlphaComponent(surfaceColor, if (night) 242 else 247)
        val strokeColor = if (night) {
            ColorUtils.setAlphaComponent(Color.WHITE, 40)
        } else {
            MaterialColors.getColor(
                ctx, com.google.android.material.R.attr.colorOutlineVariant, Color.LTGRAY
            )
        }
        val base = GradientDrawable().apply {
            setColor(fill)
            cornerRadius = radiusPx
            setStroke((1f * density).toInt().coerceAtLeast(1), strokeColor)
        }
        val sheen = GradientDrawable(
            GradientDrawable.Orientation.TOP_BOTTOM,
            intArrayOf(
                ColorUtils.setAlphaComponent(Color.WHITE, if (night) 14 else 42),
                Color.TRANSPARENT
            )
        ).apply { cornerRadius = radiusPx }
        content.background = LayerDrawable(arrayOf(base, sheen))

        val popup = PopupWindow(
            content,
            ViewGroup.LayoutParams.WRAP_CONTENT,
            ViewGroup.LayoutParams.WRAP_CONTENT,
            true
        )

        val dividerColor = MaterialColors.getColor(
            ctx, com.google.android.material.R.attr.colorOutlineVariant, Color.LTGRAY
        )

        items.forEachIndexed { index, item ->
            val row = inflater.inflate(R.layout.popup_menu_item, content, false)
            row.findViewById<ImageView>(R.id.menuIcon).setImageResource(item.iconRes)
            row.findViewById<TextView>(R.id.menuText).text = item.title
            row.setOnClickListener {
                popup.dismiss()
                item.onClick()
            }
            content.addView(row)

            if (index in dividerAfter && index < items.lastIndex) {
                val divider = View(ctx).apply {
                    setBackgroundColor(dividerColor)
                }
                val lp = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    (1 * density).toInt().coerceAtLeast(1)
                ).apply {
                    marginStart = (16 * density).toInt()
                    marginEnd = (16 * density).toInt()
                    topMargin = (6 * density).toInt()
                    bottomMargin = (6 * density).toInt()
                }
                content.addView(divider, lp)
            }
        }

        // 圆角阴影：LayerDrawable 自身不提供 outline，手动给一个圆角矩形轮廓，
        // 这样 elevation 投影是圆角的，同时把绘制裁剪到圆角内
        content.outlineProvider = object : ViewOutlineProvider() {
            override fun getOutline(view: View, outline: Outline) {
                outline.setRoundRect(0, 0, view.width, view.height, radiusPx)
            }
        }
        content.clipToOutline = true

        popup.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT)) // 点外部可关闭
        popup.elevation = 16f * density
        popup.animationStyle = R.style.GlassMenuPopupAnimation
        popup.showAsDropDown(anchor, 0, (4 * density).toInt(), Gravity.END)
    }
}
