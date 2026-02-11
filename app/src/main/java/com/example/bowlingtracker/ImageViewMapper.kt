package com.example.bowlingtracker

import android.graphics.RectF
import android.widget.ImageView

object ImageViewMapper {
    /**
     * Map taps in ImageView coordinates to image pixel coordinates, assuming FIT_CENTER.
     * This is good enough for V1 calibration.
     */
    fun mapViewTapsToImage(
        imageView: ImageView,
        taps: List<Pair<Float, Float>>,
        imgW: Float,
        imgH: Float
    ): List<Pair<Float, Float>> {
        val d = imageView.drawable ?: return emptyList()

        val viewW = imageView.width.toFloat()
        val viewH = imageView.height.toFloat()
        if (viewW <= 0 || viewH <= 0) return emptyList()

        // Drawable intrinsic size
        val dw = d.intrinsicWidth.toFloat()
        val dh = d.intrinsicHeight.toFloat()
        if (dw <= 0 || dh <= 0) return emptyList()

        val scale = minOf(viewW / dw, viewH / dh)
        val displayW = dw * scale
        val displayH = dh * scale
        val left = (viewW - displayW) / 2f
        val top = (viewH - displayH) / 2f
        val rect = RectF(left, top, left + displayW, top + displayH)

        return taps.mapNotNull { (x, y) ->
            if (!rect.contains(x, y)) {
                null
            } else {
                val nx = (x - rect.left) / rect.width()
                val ny = (y - rect.top) / rect.height()
                (nx * imgW) to (ny * imgH)
            }
        }
    }
}
