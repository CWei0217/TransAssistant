package com.example.myapplication

import android.content.Context
import android.graphics.*
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View

class SelectionView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null, defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private val paint = Paint().apply {
        color = Color.RED
        style = Paint.Style.STROKE
        strokeWidth = 5f
        pathEffect = DashPathEffect(floatArrayOf(10f, 10f), 0f)
    }

    private val fillPaint = Paint().apply {
        color = Color.parseColor("#44FF0000")
        style = Paint.Style.FILL
    }

    private var startX = 0f
    private var startY = 0f
    private var currentX = 0f
    private var currentY = 0f
    private var isDrawing = false

    var onSelectionComplete: ((Rect) -> Unit)? = null

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.action) {
            MotionEvent.ACTION_DOWN -> {
                startX = event.x
                startY = event.y
                isDrawing = true
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                currentX = event.x
                currentY = event.y
                invalidate()
            }
            MotionEvent.ACTION_UP -> {
                isDrawing = false
                val rect = getSelectionRect()
                if (rect.width() > 10 && rect.height() > 10) {
                    onSelectionComplete?.invoke(rect)
                }
                startX = 0f
                startY = 0f
                currentX = 0f
                currentY = 0f
                invalidate()
            }
        }
        return super.onTouchEvent(event)
    }

    override fun onDraw(canvas: Canvas) {
        if (isDrawing) {
            val rect = getSelectionRect()
            canvas.drawRect(rect, fillPaint)
            canvas.drawRect(rect, paint)
        }
    }

    private fun getSelectionRect(): Rect {
        val left = Math.min(startX, currentX).toInt()
        val top = Math.min(startY, currentY).toInt()
        val right = Math.max(startX, currentX).toInt()
        val bottom = Math.max(startY, currentY).toInt()
        return Rect(left, top, right, bottom)
    }
}
