package com.example.ipcam.ai

import android.graphics.Bitmap
import android.graphics.Rect
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.face.FaceDetection
import com.google.mlkit.vision.face.FaceDetectorOptions

data class FaceResult(
    val boundingBox: Rect,
    val leftEyeOpenProb: Float?,
    val rightEyeOpenProb: Float?,
    val smilingProb: Float?,
    val headEulerAngleY: Float,
    val headEulerAngleZ: Float
)

class FaceDetector {
    private val options = FaceDetectorOptions.Builder()
        .setPerformanceMode(FaceDetectorOptions.PERFORMANCE_MODE_FAST)
        .setLandmarkMode(FaceDetectorOptions.LANDMARK_MODE_ALL)
        .setClassificationMode(FaceDetectorOptions.CLASSIFICATION_MODE_ALL)
        .setMinFaceSize(0.15f)
        .build()
    private val detector = FaceDetection.getClient(options)

    fun detect(bitmap: Bitmap, rotation: Int): List<FaceResult> {
        val image = InputImage.fromBitmap(bitmap, rotation)
        val faces = com.google.android.gms.tasks.Tasks.await(detector.process(image))
        return faces.map { face ->
            FaceResult(
                boundingBox = face.boundingBox,
                leftEyeOpenProb = face.leftEyeOpenProbability,
                rightEyeOpenProb = face.rightEyeOpenProbability,
                smilingProb = face.smilingProbability,
                headEulerAngleY = face.headEulerAngleY,
                headEulerAngleZ = face.headEulerAngleZ
            )
        }
    }

    fun close() { detector.close() }
}
