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

class NoteItemTouchHelperCallback(
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
    private val deleteBgPaint = Paint().apply { color = Color.WHITE }
    private val moveBgPaint = Paint().apply { color = Color.WHITE }
    private val moveIcon = ContextCompat.getDrawable(context, R.drawable.ic_list)?.apply {
        setTint(Color.parseColor("#FF9800")) // Orange
    }
    private var isDeleteButtonClicked = false
    private var isMoveButtonClicked = false
    private var isDragging = false

    private val buttonWidth: Float
        get() = swipeThresholdLimit / 2f

    init {
        swipeThresholdLimit = 140 * context.resources.displayMetrics.density

                recyclerView.addItemDecoration(object : RecyclerView.ItemDecoration() {
            override fun onDraw(c: Canvas, parent: RecyclerView, state: RecyclerView.State) {
                super.onDraw(c, parent, state)
                for (i in 0 until parent.childCount) {
                    val itemView = parent.getChildAt(i)
                    val currentTx = itemView.translationX
                    if (currentTx < 0) {
                        // Draw Delete Background
                        c.drawRect(
                            itemView.right - buttonWidth,
                            itemView.top.toFloat(),
                            itemView.right.toFloat(),
                            itemView.bottom.toFloat(),
                            deleteBgPaint
                        )
                        // Draw Move Background
                        c.drawRect(
                            itemView.right - swipeThresholdLimit,
                            itemView.top.toFloat(),
                            itemView.right - buttonWidth,
                            itemView.bottom.toFloat(),
                            moveBgPaint
                        )

                        // Draw Delete Icon
                        deleteIcon?.let {
                            val iconMargin = (buttonWidth - it.intrinsicWidth) / 2
                            val iconTop = itemView.top + (itemView.bottom - itemView.top - it.intrinsicHeight) / 2
                            val iconLeft = itemView.right - buttonWidth + iconMargin
                            val iconRight = iconLeft + it.intrinsicWidth
                            val iconBottom = iconTop + it.intrinsicHeight
                            it.setBounds(iconLeft.toInt(), iconTop.toInt(), iconRight.toInt(), iconBottom.toInt())
                            // Tinting back to white for contrast against colored backgrounds
                            it.setTint(Color.parseColor("#FF3B30"))
                            it.draw(c)
                        }
                        
                        // Draw Move Icon
                        moveIcon?.let {
                            val iconMargin = (buttonWidth - it.intrinsicWidth) / 2
                            val iconTop = itemView.top + (itemView.bottom - itemView.top - it.intrinsicHeight) / 2
                            val iconLeft = itemView.right - swipeThresholdLimit + iconMargin
                            val iconRight = iconLeft + it.intrinsicWidth
                            val iconBottom = iconTop + it.intrinsicHeight
                            it.setBounds(iconLeft.toInt(), iconTop.toInt(), iconRight.toInt(), iconBottom.toInt())
                            // Tinting back to white
                            it.setTint(Color.parseColor("#FF3B30"))
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
                            isDeleteButtonClicked = true
                            return true
                        } else if (moveRect.contains(e.x, e.y)) {
                            isMoveButtonClicked = true
                            return true
                        } else {
                            val oldView = openedViewHolder!!.itemView
                            openedViewHolder = null
                            oldView.animate().translationX(0f).setDuration(200).start()
                            return true
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
        return makeMovementFlags(0, ItemTouchHelper.LEFT)
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

            if (isCurrentlyActive) {
                isDragging = true
                var newDx = dX
                if (viewHolder == openedViewHolder) {
                    newDx = dX - swipeThresholdLimit
                }
                
                if (newDx < -swipeThresholdLimit) newDx = -swipeThresholdLimit
                if (newDx > 0f) newDx = 0f
                
                itemView.translationX = newDx
            } else {
                if (isDragging) {
                    isDragging = false
                    val targetX = if (itemView.translationX <= -buttonWidth / 2) {
                        openedViewHolder = viewHolder
                        -swipeThresholdLimit
                    } else {
                        if (openedViewHolder == viewHolder) openedViewHolder = null
                        0f
                    }
                    itemView.animate().translationX(targetX).setDuration(200).start()
                }
            }

            val currentTx = itemView.translationX
            if (currentTx < 0) {
                deleteIcon?.let {
                    val iconMargin = (buttonWidth - it.intrinsicWidth) / 2
                    val iconTop = itemView.top + (itemView.bottom - itemView.top - it.intrinsicHeight) / 2
                    val iconLeft = itemView.right - buttonWidth + iconMargin
                    val iconRight = iconLeft + it.intrinsicWidth
                    val iconBottom = iconTop + it.intrinsicHeight

                    it.setBounds(iconLeft.toInt(), iconTop.toInt(), iconRight.toInt(), iconBottom.toInt())
                    it.draw(c)
                }
                
                moveIcon?.let {
                    val iconMargin = (buttonWidth - it.intrinsicWidth) / 2
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
        if (viewHolder == openedViewHolder) {
            viewHolder.itemView.translationX = -swipeThresholdLimit
        }
    }
}
