package com.example.bowlingtracker

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.util.AttributeSet
import android.view.MotionEvent
import androidx.appcompat.widget.AppCompatImageView

class TapImageView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : AppCompatImageView(context, attrs) {

    private val taps = mutableListOf<Pair<Float, Float>>()
    var onTap: ((Float, Float) -> Unit)? = null

    private val paint = Paint().apply {
        strokeWidth = 10f
        style = Paint.Style.FILL
        isAntiAlias = true
    }

    fun setTaps(points: List<Pair<Float, Float>>) {
        taps.clear()
        taps.addAll(points)
        invalidate()
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (event.action == MotionEvent.ACTION_DOWN) {
            onTap?.invoke(event.x, event.y)
            return true
        }
        return super.onTouchEvent(event)
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        // Draw tap markers in view coordinates
        taps.forEachIndexed { idx, (x, y) ->
            canvas.drawCircle(x, y, 14f, paint)
            // tiny label-ish mark
            canvas.drawCircle(x, y, 2f, paint)
        }
    }
}
