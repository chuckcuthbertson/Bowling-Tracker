package com.example.bowlingtracker

import android.media.MediaMetadataRetriever
import android.os.Bundle
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.opencv.android.OpenCVLoader
import org.opencv.core.*
import org.opencv.imgproc.Imgproc
import kotlin.math.abs

class ResultActivity : AppCompatActivity() {

    private lateinit var resultText: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_result)

        resultText = findViewById(R.id.resultText)

        val ok = OpenCVLoader.initDebug()
        if (!ok) {
            resultText.text = "OpenCV failed to initialize."
            return
        }

        val videoPath = intent.getStringExtra("videoPath") ?: return
        val farFt = intent.getDoubleExtra("farFt", 50.0)
        val breakFt = intent.getDoubleExtra("breakFt", 40.0)

        val quad = listOf(
            Point(intent.getFloatExtra("p1x", 0f).toDouble(), intent.getFloatExtra("p1y", 0f).toDouble()),
            Point(intent.getFloatExtra("p2x", 0f).toDouble(), intent.getFloatExtra("p2y", 0f).toDouble()),
            Point(intent.getFloatExtra("p3x", 0f).toDouble(), intent.getFloatExtra("p3y", 0f).toDouble()),
            Point(intent.getFloatExtra("p4x", 0f).toDouble(), intent.getFloatExtra("p4y", 0f).toDouble()),
        )

        resultText.text = "Analyzing (local)…\nThis may take ~10–30 seconds for short clips."

        CoroutineScope(Dispatchers.Default).launch {
            val res = analyze(videoPath, quad, farFt, breakFt)
            withContext(Dispatchers.Main) {
                resultText.text = res
            }
        }
    }

    private fun analyze(videoPath: String, laneQuad: List<Point>, farFt: Double, breakFt: Double): String {
        val retriever = MediaMetadataRetriever()
        try {
            retriever.setDataSource(videoPath)
            val durMs = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
            if (durMs <= 0) return "Could not read video duration."

            // Sample ~15 fps
            val stepMs = 66L
            val pointsPx = mutableListOf<Pair<Double, Point>>() // (tSec, pxPoint)

            var prevGray: Mat? = null
            var frameSize: Size? = null

            val mask = createLaneMask(laneQuad)

            var tMs = 0L
            while (tMs < durMs) {
                val bmp = retriever.getFrameAtTime(tMs * 1000, MediaMetadataRetriever.OPTION_CLOSEST) ?: run {
                    tMs += stepMs
                    continue
                }
                frameSize = Size(bmp.width.toDouble(), bmp.height.toDouble())

                val mat = Mat()
                org.opencv.android.Utils.bitmapToMat(bmp, mat)
                Imgproc.cvtColor(mat, mat, Imgproc.COLOR_RGBA2BGR)

                val gray = Mat()
                Imgproc.cvtColor(mat, gray, Imgproc.COLOR_BGR2GRAY)

                val ball = detectBall(prevGray, gray, mask, frameSize!!)
                if (ball != null) {
                    pointsPx.add((tMs / 1000.0) to ball)
                }

                prevGray?.release()
                prevGray = gray

                mat.release()
                tMs += stepMs
            }

            prevGray?.release()

            if (pointsPx.size < 8) {
                return "Not enough tracking points. Try:\n- brighter lane\n- tripod\n- keep lane centered\n- longer clip"
            }

            // Compute homography: map lane quad pixels -> lane rectangle (in inches)
            val laneWIn = 41.5
            val farIn = farFt * 12.0

            val src = MatOfPoint2f(*laneQuad.toTypedArray())
            val dst = MatOfPoint2f(
                Point(0.0, 0.0),
                Point(laneWIn, 0.0),
                Point(0.0, farIn),
                Point(laneWIn, farIn)
            )
            val H = Imgproc.getPerspectiveTransform(src, dst)

            // Map points to lane coords (inches)
            val lanePts = pointsPx.map { (t, p) ->
                val inMat = MatOfPoint2f(p)
                val outMat = MatOfPoint2f()
                Core.perspectiveTransform(inMat, outMat, H)
                val out = outMat.toArray()[0]
                inMat.release(); outMat.release()
                LanePoint(t, out.x, out.y) // x,y inches
            }.filter { it.yIn >= 0 }

            if (lanePts.size < 8) return "Tracking points were outside lane. Recalibrate."

            val arrowsFt = 15.0
            val arrowsIn = arrowsFt * 12.0
            val breakIn = breakFt * 12.0

            val xAtArrows = interpolateXAtY(lanePts, arrowsIn)
            val xAtBreak = interpolateXAtY(lanePts, breakIn)

            val arrowsBoard = if (xAtArrows != null) inchesToBoard(xAtArrows, laneWIn) else null
            val breakBoard = if (xAtBreak != null) inchesToBoard(xAtBreak, laneWIn) else null

            val mph = estimateSpeedMph(lanePts, yMinIn = 5.0 * 12.0, yMaxIn = minOf(45.0, farFt) * 12.0)

            val sb = StringBuilder()
            sb.append("✅ Analysis complete\n\n")
            sb.append("Arrows (15 ft): ").append(arrowsBoard?.let { "Board $it" } ?: "n/a").append("\n")
            sb.append("Breakpoint (${breakFt} ft): ").append(breakBoard?.let { "Board $it" } ?: "n/a").append("\n")
            sb.append("Speed: ").append(if (mph != null) String.format("%.1f mph", mph) else "n/a").append("\n\n")
            sb.append("Tips if results look off:\n")
            sb.append("- Re-tap lane corners more carefully\n")
            sb.append("- Set far distance closer to where you tapped (e.g. 45–55 ft)\n")
            sb.append("- Use a tripod; keep lane centered\n")
            return sb.toString()
        } catch (e: Exception) {
            return "Error during analysis: ${e.message}"
        } finally {
            retriever.release()
        }
    }

    data class LanePoint(val tSec: Double, val xIn: Double, val yIn: Double)

    private fun createLaneMask(quad: List<Point>): Mat? {
        // Mask created later after knowing frame size; for V1 we return null and do masking via point-in-quad check.
        return null
    }

    private fun pointInQuad(p: Point, q: List<Point>): Boolean {
        // Simple convex quad check using sign of cross products (assumes user taps in consistent order).
        fun cross(a: Point, b: Point, c: Point): Double {
            // cross of AB x AC
            val abx = b.x - a.x
            val aby = b.y - a.y
            val acx = c.x - a.x
            val acy = c.y - a.y
            return abx * acy - aby * acx
        }
        val c1 = cross(q[0], q[1], p)
        val c2 = cross(q[1], q[3], p)
        val c3 = cross(q[3], q[2], p)
        val c4 = cross(q[2], q[0], p)
        val hasNeg = (c1 < 0) || (c2 < 0) || (c3 < 0) || (c4 < 0)
        val hasPos = (c1 > 0) || (c2 > 0) || (c3 > 0) || (c4 > 0)
        return !(hasNeg && hasPos)
    }

    private fun detectBall(prevGray: Mat?, gray: Mat, mask: Mat?, frameSize: Size): Point? {
        if (prevGray == null) return null

        val diff = Mat()
        Core.absdiff(prevGray, gray, diff)

        // Threshold motion
        Imgproc.GaussianBlur(diff, diff, Size(7.0, 7.0), 0.0)
        Imgproc.threshold(diff, diff, 22.0, 255.0, Imgproc.THRESH_BINARY)

        // Clean up
        val kernel = Imgproc.getStructuringElement(Imgproc.MORPH_ELLIPSE, Size(7.0, 7.0))
        Imgproc.morphologyEx(diff, diff, Imgproc.MORPH_OPEN, kernel)
        Imgproc.morphologyEx(diff, diff, Imgproc.MORPH_DILATE, kernel)

        val contours = ArrayList<MatOfPoint>()
        Imgproc.findContours(diff, contours, Mat(), Imgproc.RETR_EXTERNAL, Imgproc.CHAIN_APPROX_SIMPLE)

        var best: Point? = null
        var bestArea = 0.0

        for (c in contours) {
            val area = Imgproc.contourArea(c)
            if (area < 80 || area > 25_000) {
                c.release()
                continue
            }
            val m = Imgproc.moments(c)
            if (m.m00 == 0.0) { c.release(); continue }
            val cx = m.m10 / m.m00
            val cy = m.m01 / m.m00
            val p = Point(cx, cy)

            // Lane ROI check using tapped quad (reduces false positives)
            // (Assumes taps are in order: nearL, nearR, farL, farR)
            // If tap order is wrong, this check may fail; V1 keeps it simple.
            // We'll do a soft check: if outside, penalize heavily.
            val inLane = pointInQuad(p, listOf(
                Point(intent.getFloatExtra("p1x", 0f).toDouble(), intent.getFloatExtra("p1y", 0f).toDouble()),
                Point(intent.getFloatExtra("p2x", 0f).toDouble(), intent.getFloatExtra("p2y", 0f).toDouble()),
                Point(intent.getFloatExtra("p3x", 0f).toDouble(), intent.getFloatExtra("p3y", 0f).toDouble()),
                Point(intent.getFloatExtra("p4x", 0f).toDouble(), intent.getFloatExtra("p4y", 0f).toDouble()),
            ))
            val scoreArea = if (inLane) area else area * 0.05

            if (scoreArea > bestArea) {
                bestArea = scoreArea
                best = p
            }
            c.release()
        }

        diff.release()
        kernel.release()
        return best
    }

    private fun interpolateXAtY(pts: List<LanePoint>, yTarget: Double): Double? {
        // Find first segment that crosses yTarget (increasing y)
        for (i in 1 until pts.size) {
            val a = pts[i - 1]
            val b = pts[i]
            if ((a.yIn <= yTarget && b.yIn >= yTarget) || (a.yIn >= yTarget && b.yIn <= yTarget)) {
                val dy = (b.yIn - a.yIn)
                if (abs(dy) < 1e-6) return a.xIn
                val t = (yTarget - a.yIn) / dy
                return a.xIn + (b.xIn - a.xIn) * t
            }
        }
        return null
    }

    private fun inchesToBoard(xIn: Double, laneWIn: Double): Int {
        val boardW = laneWIn / 39.0
        val clamped = xIn.coerceIn(0.0, laneWIn)
        val idx = (clamped / boardW) + 1.0
        return idx.toInt().coerceIn(1, 39)
    }

    private fun estimateSpeedMph(pts: List<LanePoint>, yMinIn: Double, yMaxIn: Double): Double? {
        val filtered = pts.filter { it.yIn in yMinIn..yMaxIn }
        if (filtered.size < 6) return null

        // Least squares slope of yIn vs tSec
        val n = filtered.size.toDouble()
        val sumT = filtered.sumOf { it.tSec }
        val sumY = filtered.sumOf { it.yIn }
        val sumTT = filtered.sumOf { it.tSec * it.tSec }
        val sumTY = filtered.sumOf { it.tSec * it.yIn }

        val denom = (n * sumTT - sumT * sumT)
        if (abs(denom) < 1e-9) return null

        val slopeInPerSec = (n * sumTY - sumT * sumY) / denom
        val ftPerSec = slopeInPerSec / 12.0
        val mph = ftPerSec * 0.681818
        return mph
    }
}
