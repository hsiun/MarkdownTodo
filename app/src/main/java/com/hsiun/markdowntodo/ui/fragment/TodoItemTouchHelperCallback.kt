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
    private val onDeleteClicked: (Int) -> Unit,
    private val onMoveClicked: (Int) -> Unit
) : ItemTouchHelper.Callback() {

    private var openedViewHolder: RecyclerView.ViewHolder? = null
    private var swipeThresholdLimit = 0f
    private val deleteIcon = ContextCompat.getDrawable(context, R.drawable.ic_delete)?.apply {
        setTint(Color.parseColor("#FF3B30"))
    }
    private val deleteBgPaint = Paint().apply { color = Color.parseColor("#FF3B30") }
    private val moveBgPaint = Paint().apply { color = Color.parseColor("#FF9800") }
    private val moveIcon = ContextCompat.getDrawable(context, R.drawable.ic_list)?.apply {
        setTint(Color.parseColor("#FF9800")) // Orange
    }
    private var isDeleteButtonClicked = false
    private var isMoveButtonClicked = false
    private var isDragging = false

    private val buttonWidth: Float
        get() = swipeThresholdLimit / 2f

    init {
        // 总宽度 140dp，每个按钮 70dp
        swipeThresholdLimit = 140 * context.resources.displayMetrics.density

        // 拦截 RecyclerView 触摸事件，用于处理点击、保持展开等逻辑
                recyclerView.addItemDecoration(object : RecyclerView.ItemDecoration() {
            override fun onDraw(c: Canvas, parent: RecyclerView, state: RecyclerView.State) {
                super.onDraw(c, parent, state)
                for (i in 0 until parent.childCount) {
                    val itemView = parent.getChildAt(i)
                    val currentTx = itemView.translationX
                    if (currentTx < -10) {
                        val itemRight = itemView.right + currentTx.toInt()
                        
                        val deleteLeft = itemRight - buttonWidth.toInt()
                        c.drawRect(
                            deleteLeft.toFloat(),
                            itemView.top.toFloat(),
                            itemRight.toFloat(),
                            itemView.bottom.toFloat(),
                            deleteBgPaint
                        )
                        
                        val moveLeft = itemRight - swipeThresholdLimit.toInt()
                        c.drawRect(
                            moveLeft.toFloat(),
                            itemView.top.toFloat(),
                            deleteLeft.toFloat(),
                            itemView.bottom.toFloat(),
                            moveBgPaint
                        )

                        // Draw Delete Icon
                        deleteIcon?.let {
                            val iconMargin = (buttonWidth - it.intrinsicWidth) / 2
                            val iconTop = itemView.top + (itemView.bottom - itemView.top - it.intrinsicHeight) / 2
                            val iconLeft = deleteLeft + iconMargin
                            val iconRight = iconLeft + it.intrinsicWidth
                            val iconBottom = iconTop + it.intrinsicHeight
                            it.setBounds(iconLeft.toInt(), iconTop.toInt(), iconRight.toInt(), iconBottom.toInt())
                            it.setTint(Color.WHITE)
                            it.draw(c)
                        }
                        
                        // Draw Move Icon
                        moveIcon?.let {
                            val iconMargin = (buttonWidth - it.intrinsicWidth) / 2
                            val iconTop = itemView.top + (itemView.bottom - itemView.top - it.intrinsicHeight) / 2
                            val iconLeft = moveLeft + iconMargin
                            val iconRight = iconLeft + it.intrinsicWidth
                            val iconBottom = iconTop + it.intrinsicHeight
                            it.setBounds(iconLeft.toInt(), iconTop.toInt(), iconRight.toInt(), iconBottom.toInt())
                            it.setTint(Color.WHITE)
                            it.draw(c)
                        }
                    }
                }
            }
        })
        
        recyclerView.addOnItemTouchListener(object : RecyclerView.SimpleOnItemTouchListener() {
            override fun onInterceptTouchEvent(rv: RecyclerView, e: MotionEvent): Boolean {
                if (e.action == MotionEvent.ACTION_DOWN) {
                    if (openedViewHolder != null) {
                        val itemView = openedViewHolder!!.itemView
                        
                        val rightBoundary = itemView.right.toFloat()
                        val deleteRect = RectF(
                            rightBoundary - buttonWidth,
                            itemView.top.toFloat(),
                            rightBoundary,
                            itemView.bottom.toFloat()
                        )
                        val moveRect = RectF(
                            rightBoundary - swipeThresholdLimit,
                            itemView.top.toFloat(),
                            rightBoundary - buttonWidth,
                            itemView.bottom.toFloat()
                        )
                        
                        if (deleteRect.contains(e.x, e.y)) {
                            // 点到了删除按钮
                            isDeleteButtonClicked = true
                            return true
                        } else if (moveRect.contains(e.x, e.y)) {
                            // 点到了移动按钮
                            isMoveButtonClicked = true
                            return true
                        } else {
                            // 点到了其他地方，将其自动收回
                            val oldView = openedViewHolder!!.itemView
                            openedViewHolder = null
                            oldView.animate().translationX(0f).setDuration(200).start()
                            return true // 拦截本次触摸，防止误触进入详情
                        }
                    }
                } else if (e.action == MotionEvent.ACTION_UP) {
                    val pos = openedViewHolder?.adapterPosition ?: RecyclerView.NO_POSITION
                    
                    if (isDeleteButtonClicked || isMoveButtonClicked) {
                        val isDelete = isDeleteButtonClicked
                        isDeleteButtonClicked = false
                        isMoveButtonClicked = false
                        
                        val oldView = openedViewHolder?.itemView
                        openedViewHolder = null
                        oldView?.animate()?.translationX(0f)?.setDuration(200)?.start()
                        
                        if (pos != RecyclerView.NO_POSITION) {
                            if (isDelete) {
                                onDeleteClicked(pos)
                            } else {
                                onMoveClicked(pos)
                            }
                        }
                        return true
                    }
                }
                return false
            }

            override fun onTouchEvent(rv: RecyclerView, e: MotionEvent) {
                if (e.action == MotionEvent.ACTION_UP && (isDeleteButtonClicked || isMoveButtonClicked)) {
                    val isDelete = isDeleteButtonClicked
                    isDeleteButtonClicked = false
                    isMoveButtonClicked = false
                    
                    val pos = openedViewHolder?.adapterPosition ?: RecyclerView.NO_POSITION
                    val oldView = openedViewHolder?.itemView
                    openedViewHolder = null
                    oldView?.animate()?.translationX(0f)?.setDuration(200)?.start()
                    
                    if (pos != RecyclerView.NO_POSITION) {
                        if (isDelete) {
                            onDeleteClicked(pos)
                        } else {
                            onMoveClicked(pos)
                        }
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
                
                // 限制最多只能向左滑出所有按钮的宽度，且不能向右滑出屏幕
                if (newDx < -swipeThresholdLimit) newDx = -swipeThresholdLimit
                if (newDx > 0f) newDx = 0f
                
                itemView.translationX = newDx
            } else {
                if (isDragging) {
                    isDragging = false
                    // 手指刚刚松开，判断释放时的位置决定是合上还是展开
                    val targetX = if (itemView.translationX <= -buttonWidth / 2) {
                        openedViewHolder = viewHolder
                        -swipeThresholdLimit
                    } else {
                        if (openedViewHolder == viewHolder) openedViewHolder = null
                        0f
                    }
                    // 启动我们自己的动画
                    itemView.animate().translationX(targetX).setDuration(200).start()
                }
            }

        } else {
            super.onChildDraw(c, recyclerView, viewHolder, dX, dY, actionState, isCurrentlyActive)
        }
    }

    override fun clearView(recyclerView: RecyclerView, viewHolder: RecyclerView.ViewHolder) {
        super.clearView(recyclerView, viewHolder)
        if (viewHolder == openedViewHolder) {
            viewHolder.itemView.translationX = -swipeThresholdLimit
        }
    }
}
