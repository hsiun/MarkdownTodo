package com.hsiun.markdowntodo.ui.fragment

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.view.MotionEvent
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.RecyclerView
import com.hsiun.markdowntodo.R

class TodoItemTouchHelperCallback(
    private val context: Context,
    private val recyclerView: RecyclerView,
    private val onDeleteClicked: (Int) -> Unit
) : ItemTouchHelper.Callback() {

    private var currentSwipedViewHolder: RecyclerView.ViewHolder? = null
    private var swipeThresholdLimit = 0f
    private val deleteIcon = ContextCompat.getDrawable(context, R.drawable.ic_delete)?.apply {
        setTint(Color.WHITE)
    }
    private val paint = Paint().apply { color = Color.parseColor("#FF3B30") }
    private var isDeleteButtonClicked = false

    init {
        swipeThresholdLimit = 80 * context.resources.displayMetrics.density

        // 拦截 RecyclerView 触摸事件，用于处理点击 Canvas 画出来的按钮
        recyclerView.addOnItemTouchListener(object : RecyclerView.SimpleOnItemTouchListener() {
            override fun onInterceptTouchEvent(rv: RecyclerView, e: MotionEvent): Boolean {
                if (currentSwipedViewHolder != null) {
                    val itemView = currentSwipedViewHolder!!.itemView
                    val isClickOutsideItem = e.y < itemView.top || e.y > itemView.bottom

                    if (e.action == MotionEvent.ACTION_DOWN) {
                        // 检查是否点击了露出的红色区域（宽度即 swipeThresholdLimit）
                        val rightBoundary = itemView.right.toFloat()
                        val leftBoundary = rightBoundary - swipeThresholdLimit
                        
                        // 注意：此时 itemView.translationX 处于负值，意味着 itemView 的可见区域左移了
                        // 红色区域绘制在了原 itemView 占据的末端位置
                        isDeleteButtonClicked = e.x >= leftBoundary && e.x <= rightBoundary && !isClickOutsideItem
                        
                        if (!isDeleteButtonClicked) {
                            // 没点中垃圾桶，缩回卡片
                            recoverSwipedItem()
                        }
                    } else if (e.action == MotionEvent.ACTION_UP && isDeleteButtonClicked) {
                        isDeleteButtonClicked = false
                        val position = currentSwipedViewHolder!!.adapterPosition
                        recoverSwipedItem()
                        if (position != RecyclerView.NO_POSITION) {
                            onDeleteClicked(position)
                        }
                        return true // 拦截事件，避免列表响应点击
                    }
                }
                return false
            }
        })
    }

    private fun recoverSwipedItem() {
        currentSwipedViewHolder?.let {
            it.itemView.animate().translationX(0f).setDuration(200).start()
            currentSwipedViewHolder = null
        }
    }

    override fun getMovementFlags(
        recyclerView: RecyclerView,
        viewHolder: RecyclerView.ViewHolder
    ): Int {
        return makeMovementFlags(0, ItemTouchHelper.LEFT) // 只允许左滑
    }

    override fun onMove(
        recyclerView: RecyclerView,
        viewHolder: RecyclerView.ViewHolder,
        target: RecyclerView.ViewHolder
    ): Boolean = false

    // 这个参数决定了需要滑多远才能触发 onSwiped，设置大于 1 就会导致永远无法触发 onSwiped
    override fun getSwipeThreshold(viewHolder: RecyclerView.ViewHolder): Float {
        return 2.0f
    }

    // 设置逃逸速度极大，防止快速滑动直接触发 onSwiped
    override fun getSwipeEscapeVelocity(defaultValue: Float): Float {
        return Float.MAX_VALUE
    }

    override fun onSwiped(viewHolder: RecyclerView.ViewHolder, direction: Int) {
        // 由于上面我们拦截了触发阈值，这里永远不会被调用
    }

    override fun onChildDraw(
        c: Canvas,
        recyclerView: RecyclerView,
        viewHolder: RecyclerView.ViewHolder,
        dX: Float,
        dY: Float,
        actionState: Int,
        isCurrentlyActive: Boolean
    ) {
        if (actionState == ItemTouchHelper.ACTION_STATE_SWIPE) {
            val itemView = viewHolder.itemView

            // 如果当前有其他展开的 item 且不是正在滑动的这个，将其合拢
            if (currentSwipedViewHolder != null && currentSwipedViewHolder != viewHolder) {
                recoverSwipedItem()
            }

            // 限制最大滑动距离为 80dp (即 swipeThresholdLimit)
            var clampedDx = dX
            if (dX < -swipeThresholdLimit) {
                clampedDx = -swipeThresholdLimit
            }

            if (clampedDx < 0) {
                // 绘制底层红色背景：右侧留出来的空白区域
                val backgroundRect = RectF(
                    itemView.right + clampedDx,
                    itemView.top.toFloat(),
                    itemView.right.toFloat(),
                    itemView.bottom.toFloat()
                )
                c.drawRect(backgroundRect, paint)

                // 绘制居中的白色垃圾桶图标
                deleteIcon?.let {
                    val iconMargin = (swipeThresholdLimit - it.intrinsicWidth) / 2
                    val iconTop = itemView.top + (itemView.bottom - itemView.top - it.intrinsicHeight) / 2
                    val iconLeft = itemView.right - swipeThresholdLimit + iconMargin
                    val iconRight = iconLeft + it.intrinsicWidth
                    val iconBottom = iconTop + it.intrinsicHeight

                    it.setBounds(iconLeft.toInt(), iconTop.toInt(), iconRight.toInt(), iconBottom.toInt())
                    it.draw(c)
                }
            }

            // 拦截原生的位移操作，使用我们限制过的 clampedDx
            itemView.translationX = clampedDx

            // 判断滑动状态：如果是正在操作，或者已经停留在了拉开的状态
            if (isCurrentlyActive || clampedDx < 0) {
                currentSwipedViewHolder = viewHolder
            } else if (clampedDx == 0f) {
                currentSwipedViewHolder = null
            }
        } else {
            super.onChildDraw(c, recyclerView, viewHolder, dX, dY, actionState, isCurrentlyActive)
        }
    }

    override fun clearView(recyclerView: RecyclerView, viewHolder: RecyclerView.ViewHolder) {
        super.clearView(recyclerView, viewHolder)
        // 恢复原生位移的干扰
        viewHolder.itemView.translationX = 0f
    }
}
