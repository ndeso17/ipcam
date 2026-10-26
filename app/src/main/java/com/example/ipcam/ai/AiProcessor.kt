package com.example.ipcam.ai

import android.graphics.Bitmap

data class AiResult(
    val faces: List<FaceResult>,
    val segmentation: SegmentationResult?
)

object AiProcessor {
    private val faceDetector = FaceDetector()
    private val selfieSegmenter = SelfieSegmenter()

    @Volatile var isFaceDetectionEnabled = true
    @Volatile var isSegmentationEnabled = false

    fun process(bitmap: Bitmap, rotation: Int): AiResult {
        val faces = if (isFaceDetectionEnabled) faceDetector.detect(bitmap, rotation) else emptyList()
        val seg = if (isSegmentationEnabled) selfieSegmenter.segment(bitmap, rotation) else null
        return AiResult(faces, seg)
    }

    fun shutdown() {
        faceDetector.close()
        selfieSegmenter.close()
    }
}
