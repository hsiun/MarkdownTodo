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

    private var openedViewHolder: RecyclerView.ViewHolder? = null
    private var swipeThresholdLimit = 0f
    private val deleteIcon = ContextCompat.getDrawable(context, R.drawable.ic_delete)?.apply {
        setTint(Color.WHITE)
    }
    private val paint = Paint().apply { color = Color.parseColor("#FF3B30") }
    private var isDeleteButtonClicked = false
    private var isDragging = false

    init {
        swipeThresholdLimit = 80 * context.resources.displayMetrics.density

        // 拦截 RecyclerView 触摸事件，用于处理点击、保持展开等逻辑
        recyclerView.addOnItemTouchListener(object : RecyclerView.SimpleOnItemTouchListener() {
            override fun onInterceptTouchEvent(rv: RecyclerView, e: MotionEvent): Boolean {
                if (e.action == MotionEvent.ACTION_DOWN) {
                    if (openedViewHolder != null) {
                        val itemView = openedViewHolder!!.itemView
                        // 红色删除区域的范围计算
                        val rect = RectF(
                            itemView.right - swipeThresholdLimit,
                            itemView.top.toFloat(),
                            itemView.right.toFloat(),
                            itemView.bottom.toFloat()
                        )
                        
                        if (rect.contains(e.x, e.y)) {
                            // 点到了删除按钮
                            isDeleteButtonClicked = true
                            return true // 拦截触摸事件
                        } else {
                            // 点到了其他地方，将其自动收回
                            val oldView = openedViewHolder!!.itemView
                            openedViewHolder = null
                            oldView.animate().translationX(0f).setDuration(200).start()
                            return true // 拦截本次触摸，防止误触进入详情
                        }
                    }
                } else if (e.action == MotionEvent.ACTION_UP) {
                    if (isDeleteButtonClicked) {
                        isDeleteButtonClicked = false
                        val pos = openedViewHolder?.adapterPosition ?: RecyclerView.NO_POSITION
                        val oldView = openedViewHolder?.itemView
                        openedViewHolder = null
                        oldView?.animate()?.translationX(0f)?.setDuration(200)?.start()
                        
                        if (pos != RecyclerView.NO_POSITION) {
                            onDeleteClicked(pos)
                        }
                        return true
                    }
                }
                return false
            }

            override fun onTouchEvent(rv: RecyclerView, e: MotionEvent) {
                if (e.action == MotionEvent.ACTION_UP && isDeleteButtonClicked) {
                    isDeleteButtonClicked = false
                    val pos = openedViewHolder?.adapterPosition ?: RecyclerView.NO_POSITION
                    val oldView = openedViewHolder?.itemView
                    openedViewHolder = null
                    oldView?.animate()?.translationX(0f)?.setDuration(200)?.start()
                    
                    if (pos != RecyclerView.NO_POSITION) {
                        onDeleteClicked(pos)
                    }
                }
            }
        })
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

    // 这个参数决定了需要滑多远才能触发真正的 onSwiped（我们设得很高，永远不触发）
    override fun getSwipeThreshold(viewHolder: RecyclerView.ViewHolder): Float {
        return 2.0f
    }

    override fun getSwipeEscapeVelocity(defaultValue: Float): Float {
        return Float.MAX_VALUE
    }

    override fun onSwiped(viewHolder: RecyclerView.ViewHolder, direction: Int) {}

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

            // 核心逻辑：覆盖 ItemTouchHelper 自带的位移恢复动画
            if (isCurrentlyActive) {
                isDragging = true
                var newDx = dX
                // 如果当前拖动的是已经展开的项，需要加上偏移量
                if (viewHolder == openedViewHolder) {
                    newDx = dX - swipeThresholdLimit
                }
                
                // 限制最多只能向左滑出删除按钮的宽度，且不能向右滑出屏幕
                if (newDx < -swipeThresholdLimit) newDx = -swipeThresholdLimit
                if (newDx > 0f) newDx = 0f
                
                itemView.translationX = newDx
            } else {
                if (isDragging) {
                    isDragging = false
                    // 手指刚刚松开，判断释放时的位置决定是合上还是展开
                    val targetX = if (itemView.translationX <= -swipeThresholdLimit / 2) {
                        openedViewHolder = viewHolder
                        -swipeThresholdLimit
                    } else {
                        if (openedViewHolder == viewHolder) openedViewHolder = null
                        0f
                    }
                    // 启动我们自己的动画
                    itemView.animate().translationX(targetX).setDuration(200).start()
                }
                // 注意：在手指释放期间，直接忽略 ItemTouchHelper 传进来的 dX
                // 而是让上面我们自己的 itemView.animate() 来接管位移！
            }

            // 绘制底层的红色背景和垃圾桶图标，跟随当前 itemView 实际的 translationX
            val currentTx = itemView.translationX
            if (currentTx < 0) {
                val backgroundRect = RectF(
                    itemView.right + currentTx,
                    itemView.top.toFloat(),
                    itemView.right.toFloat(),
                    itemView.bottom.toFloat()
                )
                c.drawRect(backgroundRect, paint)

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
        } else {
            super.onChildDraw(c, recyclerView, viewHolder, dX, dY, actionState, isCurrentlyActive)
        }
    }

    override fun clearView(recyclerView: RecyclerView, viewHolder: RecyclerView.ViewHolder) {
        super.clearView(recyclerView, viewHolder)
        // ItemTouchHelper 在自己的恢复动画结束后会强行将位移归零
        // 如果当前项是我们需要保持展开的项，我们要把它再强制设回去
        if (viewHolder == openedViewHolder) {
            viewHolder.itemView.translationX = -swipeThresholdLimit
        }
    }
}
