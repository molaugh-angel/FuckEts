package com.fuckets.etsviewer

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.content.res.ColorStateList
import android.graphics.Typeface
import android.text.SpannableString
import android.text.style.StyleSpan
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.animation.AccelerateDecelerateInterpolator
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.RecyclerView
import com.fuckets.etsviewer.databinding.ItemGroupBinding
import com.fuckets.etsviewer.databinding.ItemPartBinding

/** 每种 Part 对应的配色：背景（浅）、强调条、文字（深） */
private val PART_COLORS = mapOf(
    PartType.A to Triple(R.color.part_a_bg, R.color.part_a_accent, R.color.part_a_text),
    PartType.B to Triple(R.color.part_b_bg, R.color.part_b_accent, R.color.part_b_text),
    PartType.C to Triple(R.color.part_c_bg, R.color.part_c_accent, R.color.part_c_text)
)

private val QUESTION_TITLE_REGEX = Regex("第\\s*\\d+\\s*题")

/** 展开状态：默认全部折叠，重新提交数据时清空 */
private object ExpandState {
    private val expanded = mutableSetOf<Int>()

    fun isExpanded(index: Int) = expanded.contains(index)

    fun set(index: Int, value: Boolean) {
        if (value) expanded.add(index) else expanded.remove(index)
    }

    fun clear() = expanded.clear()
}

class GroupAdapter : RecyclerView.Adapter<GroupAdapter.VH>() {

    private val groups = mutableListOf<PartGroup>()

    /** 长按组标题 → 请求删除整组（回调由 Activity 弹确认框并执行） */
    var onRequestDeleteGroup: ((index: Int, group: PartGroup) -> Unit)? = null

    /** 长按单个 Part 卡片 → 请求删除该 Part */
    var onRequestDeletePart: ((part: Part) -> Unit)? = null

    fun submit(list: List<PartGroup>) {
        groups.clear()
        groups.addAll(list)
        ExpandState.clear()   // 新数据进来时恢复"全部折叠"
        notifyDataSetChanged()
    }

    /** 展开/折叠全部，供外部（如菜单）调用 */
    fun expandAll(expand: Boolean) {
        for (i in groups.indices) ExpandState.set(i + 1, expand)
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val b = ItemGroupBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return VH(b)
    }

    override fun getItemCount() = groups.size

    override fun onBindViewHolder(holder: VH, position: Int) {
        val index = position + 1
        holder.bind(index, groups[position], onRequestDeleteGroup, onRequestDeletePart)
    }

    class VH(private val binding: ItemGroupBinding) :
        RecyclerView.ViewHolder(binding.root) {

        /** 进行中的展开/折叠高度动画；复用 ViewHolder 或再次点击时先取消 */
        private var heightAnimator: ValueAnimator? = null

        fun bind(
            index: Int,
            group: PartGroup,
            onLongPressGroup: ((Int, PartGroup) -> Unit)?,
            onLongPressPart: ((Part) -> Unit)?
        ) {
            AppLog.d("Adapter", "绑定第 $index 组：${group.parts.size} 个 Part -> ${group.parts.map { it.type }}")
            val ctx = binding.root.context

            // 整组卡片：中性玻璃面板
            binding.root.background = LiquidGlass.surface(ctx, radiusTopDp = 28f, radiusBottomDp = 28f)

            binding.groupTitle.text = "第 $index 组 · ${group.parts.size} 项"

            // 组序号做成一颗带色玻璃珠（颜色按组序在 蓝/紫/绿 之间轮转）
            val badge = PART_COLORS[PartType.values()[(index - 1) % PartType.values().size]]!!
            val badgeAccent = ContextCompat.getColor(ctx, badge.second)
            binding.groupBadge.text = index.toString()
            binding.groupBadge.setTextColor(ContextCompat.getColor(ctx, badge.third))
            binding.groupBadge.background = LiquidGlass.surface(
                ctx,
                radiusTopDp = 17f,
                radiusBottomDp = 17f,
                tintColor = badgeAccent,
                tintAlpha = if (LiquidGlass.isNight(ctx)) 0.50f else 0.34f,
                fillAlpha = if (LiquidGlass.isNight(ctx)) 0.14f else 0.62f
            )

            // 长按组标题：删除整组
            val groupCb = onLongPressGroup
            if (groupCb != null) {
                binding.groupHeader.setOnLongClickListener {
                    AppLog.d("Adapter", "长按第 $index 组标题 → 请求删除整组")
                    it.performHapticFeedback(android.view.HapticFeedbackConstants.LONG_PRESS)
                    groupCb(index, group)
                    true
                }
            } else {
                binding.groupHeader.setOnLongClickListener(null)
            }

            // Part 视图惰性构建：折叠的组不 inflate 子视图。
            // Part 内容是大文本 + 可选中 TextView，布局很贵，默认全折叠时全量构建会明显拖慢首屏与滚动。
            binding.partContainer.removeAllViews()
            var partsBuilt = false
            fun buildParts() {
                if (partsBuilt) return
                partsBuilt = true
                val inflater = LayoutInflater.from(binding.root.context)
                for (part in group.parts) {
                    val pb = ItemPartBinding.inflate(inflater, binding.partContainer, true)
                    pb.partTitle.text = part.title
                    pb.partFolder.text = part.folderName
                    pb.partContent.text = renderContent(part)
                    pb.partContent.setTextIsSelectable(true)
                    applyPartColors(pb, part.type)

                    // 长按卡片（标题 / 文件夹名 / 空白区域）→ 删除该 Part
                    // 注意：长按正文会被系统文字选择接管，这是 Android 默认行为
                    val partCb = onLongPressPart
                    if (partCb != null) {
                        val l = View.OnLongClickListener { v ->
                            AppLog.d("Adapter", "长按 Part ${part.type}（${part.folderName}）→ 请求删除")
                            v.performHapticFeedback(android.view.HapticFeedbackConstants.LONG_PRESS)
                            partCb(part)
                            true
                        }
                        pb.root.setOnLongClickListener(l)
                        pb.partTitle.setOnLongClickListener(l)
                        pb.partFolder.setOnLongClickListener(l)
                    } else {
                        pb.root.setOnLongClickListener(null)
                    }
                }
            }

            // 默认折叠；展开状态记录在 adapter 里，滚动回收后仍保持。
            // 回收复用时先停掉残留动画，并复位高度/透明度，防止"半展开"状态串到别的组
            cancelHeightAnimation()
            binding.partContainer.layoutParams.height = ViewGroup.LayoutParams.WRAP_CONTENT
            binding.partContainer.alpha = 1f
            val expanded = isExpanded(index)
            if (expanded) buildParts()   // 展开态的组在绑定时才构建（如滚动回收后回来）
            binding.partContainer.visibility = if (expanded) View.VISIBLE else View.GONE
            binding.groupArrow.rotation = if (expanded) 180f else 0f
            binding.groupHeader.setOnClickListener {
                if (heightAnimator != null) return@setOnClickListener   // 动画途中不响应连点
                val nowExpanded = isExpanded(index)
                if (!nowExpanded) buildParts()   // 首次展开时才真正构建内容
                setExpanded(index, !nowExpanded)
                animateExpanded(!nowExpanded)
                AppLog.d("Adapter", "第 $index 组 ${if (nowExpanded) "折叠" else "展开"}")
            }
        }

        private fun isExpanded(index: Int) = ExpandState.isExpanded(index)

        private fun setExpanded(index: Int, value: Boolean) = ExpandState.set(index, value)

        /**
         * 展开/折叠动画：容器高度 0 ↔ 内容高度（220ms，先快后慢），
         * 同步淡入淡出与箭头旋转，结束后恢复 WRAP_CONTENT 不影响后续布局。
         */
        private fun animateExpanded(expanding: Boolean) {
            cancelHeightAnimation()
            val container = binding.partContainer
            val card = binding.root

            // 量出内容完整高度（容器在卡片内垂直排布，宽度 = 卡片宽 - 卡片内边距）
            val widthSpec = View.MeasureSpec.makeMeasureSpec(
                (card.width - card.paddingLeft - card.paddingRight).coerceAtLeast(0),
                View.MeasureSpec.EXACTLY
            )
            container.measure(widthSpec, View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED))
            val contentHeight = container.measuredHeight

            if (contentHeight <= 0) {   // 内容为空（异常情况）：直接切换，不做动画
                container.visibility = if (expanding) View.VISIBLE else View.GONE
                return
            }

            val from = if (expanding) 0 else container.height
            val to = if (expanding) contentHeight else 0
            if (expanding) {
                container.layoutParams.height = 0
                container.alpha = 0.25f
                container.visibility = View.VISIBLE
            }

            val anim = ValueAnimator.ofInt(from, to).apply {
                duration = 220L
                interpolator = AccelerateDecelerateInterpolator()
                addUpdateListener { va ->
                    val h = va.animatedValue as Int
                    container.layoutParams.height = h
                    container.requestLayout()
                    // 按"已展开比例"同步透明度：展开 0.25→1，折叠 1→0.25
                    container.alpha = 0.25f + 0.75f * (h.toFloat() / contentHeight)
                }
                addListener(object : AnimatorListenerAdapter() {
                    override fun onAnimationEnd(animation: Animator) {
                        container.layoutParams.height = ViewGroup.LayoutParams.WRAP_CONTENT
                        container.alpha = 1f
                        if (!expanding) container.visibility = View.GONE
                        heightAnimator = null
                    }
                })
            }
            heightAnimator = anim
            anim.start()

            binding.groupArrow.animate()
                .rotation(if (expanding) 180f else 0f)
                .setDuration(180L)
                .start()
        }

        private fun cancelHeightAnimation() {
            heightAnimator?.cancel()
            heightAnimator = null
        }

        /** 按 Part 类型着色：Chip 与内容块都做成"带色玻璃"，用颜色区分 A / B / C */
        private fun applyPartColors(pb: ItemPartBinding, type: PartType) {
            val ctx = pb.root.context
            val (_, accentRes, textRes) = PART_COLORS[type] ?: return
            val accent = ContextCompat.getColor(ctx, accentRes)
            val night = LiquidGlass.isNight(ctx)

            val (chipFill, chipStroke) = LiquidGlass.chipColors(ctx, accent)
            pb.partTitle.chipBackgroundColor = ColorStateList.valueOf(chipFill)
            pb.partTitle.chipStrokeColor = ColorStateList.valueOf(chipStroke)
            pb.partTitle.setTextColor(ContextCompat.getColor(ctx, textRes))

            // 内容块：带色玻璃（半透明底 + 顶部高光 + 彩色亮边）
            pb.partContentBox.background = LiquidGlass.surface(
                ctx,
                radiusTopDp = 20f,
                radiusBottomDp = 20f,
                tintColor = accent,
                tintAlpha = if (night) 0.24f else 0.14f,
                fillAlpha = if (night) 0.09f else 0.46f,
                strokeAlpha = if (night) 0.16f else 0.50f
            )
        }

        /** Part B 的"第 N 题"小标题加粗，答案带序号，纯文本更易读 */
        private fun renderContent(part: Part): CharSequence {
            val text = part.displayText.ifBlank { "（无内容）" }
            if (part.type != PartType.B) return text

            val spannable = SpannableString(text)
            QUESTION_TITLE_REGEX.findAll(text).forEach { m ->
                spannable.setSpan(
                    StyleSpan(Typeface.BOLD),
                    m.range.first,
                    m.range.last + 1,
                    SpannableString.SPAN_EXCLUSIVE_EXCLUSIVE
                )
            }
            return spannable
        }
    }
}
