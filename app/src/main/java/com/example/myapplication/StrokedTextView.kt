package com.example.myapplication

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.util.AttributeSet
import androidx.appcompat.widget.AppCompatTextView

/**
 * 字体轮廓描边：先沿字形路径 [Paint.Style.STROKE]，再 [Paint.Style.FILL] 填充，
 * 使用 [Layout.draw]，不是阴影（shadowLayer）。
 */
class StrokedTextView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = android.R.attr.textViewStyle
) : AppCompatTextView(context, attrs, defStyleAttr) {

    var strokeWidthPx: Float = 0f
        set(value) {
            field = value
            invalidate()
        }

    var strokeColorValue: Int = 0xFFFFFFFF.toInt()
        set(value) {
            field = value
            invalidate()
        }

    init {
        // 避免与轮廓描边混淆的阴影
        setShadowLayer(0f, 0f, 0f, 0)
    }

    override fun onDraw(canvas: Canvas) {
        if (strokeWidthPx <= 0f) {
            super.onDraw(canvas)
            return
        }

        val textLayout = getLayout()
        if (textLayout == null) {
            super.onDraw(canvas)
            return
        }

        // 背景仍走系统绘制
        val bg = background
        if (bg != null) {
            bg.setBounds(0, 0, width, height)
            bg.draw(canvas)
        }

        canvas.save()
        // 与 TextView 内文字绘制一致的纵向滚动偏移
        canvas.translate(0f, scrollY.toFloat())

        val textPaint = paint
        val oldStyle = textPaint.style
        val oldStrokeWidth = textPaint.strokeWidth
        val oldStrokeJoin = textPaint.strokeJoin
        val oldStrokeMiter = textPaint.strokeMiter
        val oldColor = textPaint.color
        val oldAntiAlias = textPaint.isAntiAlias

        textPaint.isAntiAlias = true
        textPaint.strokeJoin = Paint.Join.ROUND
        textPaint.strokeMiter = 10f

        // 1) 轮廓：仅描边，不填充字形内部
        textPaint.style = Paint.Style.STROKE
        textPaint.strokeWidth = strokeWidthPx
        textPaint.color = strokeColorValue
        textLayout.draw(canvas)

        // 2) 填充：正常实心字
        textPaint.style = Paint.Style.FILL
        textPaint.color = currentTextColor
        textLayout.draw(canvas)

        textPaint.style = oldStyle
        textPaint.strokeWidth = oldStrokeWidth
        textPaint.strokeJoin = oldStrokeJoin
        textPaint.strokeMiter = oldStrokeMiter
        textPaint.color = oldColor
        textPaint.isAntiAlias = oldAntiAlias

        canvas.restore()
    }
}
