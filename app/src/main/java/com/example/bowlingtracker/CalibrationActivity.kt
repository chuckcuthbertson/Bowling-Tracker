package com.example.bowlingtracker

import android.content.Intent
import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import android.os.Bundle
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity

class CalibrationActivity : AppCompatActivity() {

    private lateinit var frameView: TapImageView
    private lateinit var continueButton: Button
    private lateinit var farDistanceFt: EditText
    private lateinit var breakpointFt: EditText
    private lateinit var instructions: TextView

    private val tapsViewCoords = mutableListOf<Pair<Float, Float>>() // view coords
    private var frameBitmap: Bitmap? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_calibration)

        frameView = findViewById(R.id.frameView)
        continueButton = findViewById(R.id.continueButton)
        farDistanceFt = findViewById(R.id.farDistanceFt)
        breakpointFt = findViewById(R.id.breakpointFt)
        instructions = findViewById(R.id.instructions)

        val videoPath = intent.getStringExtra("videoPath") ?: run {
            finish()
            return
        }

        frameBitmap = extractFrame(videoPath)
        frameView.setImageBitmap(frameBitmap)

        frameView.onTap = { x, y ->
            if (tapsViewCoords.size < 4) {
                tapsViewCoords.add(x to y)
                frameView.setTaps(tapsViewCoords)
                instructions.text = "Points: ${tapsViewCoords.size}/4"
                if (tapsViewCoords.size == 4) {
                    continueButton.isEnabled = true
                    instructions.text = "Points set. Tap Analyze."
                }
            }
        }

        continueButton.setOnClickListener {
            val bmp = frameBitmap ?: return@setOnClickListener
            val imgW = bmp.width.toFloat()
            val imgH = bmp.height.toFloat()

            // Convert view taps -> image normalized coords.
            // Because ImageView is fitCenter, mapping precisely is complex.
            // For V1, we use an approximation using drawable bounds.
            val mapped = ImageViewMapper.mapViewTapsToImage(frameView, tapsViewCoords, imgW, imgH)
            if (mapped.size != 4) return@setOnClickListener

            val farFt = farDistanceFt.text.toString().toDoubleOrNull() ?: 50.0
            val breakFt = breakpointFt.text.toString().toDoubleOrNull() ?: 40.0

            val intent = Intent(this, ResultActivity::class.java)
            intent.putExtra("videoPath", videoPath)
            intent.putExtra("p1x", mapped[0].first); intent.putExtra("p1y", mapped[0].second)
            intent.putExtra("p2x", mapped[1].first); intent.putExtra("p2y", mapped[1].second)
            intent.putExtra("p3x", mapped[2].first); intent.putExtra("p3y", mapped[2].second)
            intent.putExtra("p4x", mapped[3].first); intent.putExtra("p4y", mapped[3].second)
            intent.putExtra("farFt", farFt)
            intent.putExtra("breakFt", breakFt)
            startActivity(intent)
        }
    }

    private fun extractFrame(path: String): Bitmap? {
        val retriever = MediaMetadataRetriever()
        return try {
            retriever.setDataSource(path)
            // Grab a frame ~0.5s into the clip.
            retriever.getFrameAtTime(500_000, MediaMetadataRetriever.OPTION_CLOSEST)
        } finally {
            retriever.release()
        }
    }
}
