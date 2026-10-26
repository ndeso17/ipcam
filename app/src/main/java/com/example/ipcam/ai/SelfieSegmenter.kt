package com.example.ipcam.ai

import android.graphics.Bitmap
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.segmentation.Segmentation
import com.google.mlkit.vision.segmentation.selfie.SelfieSegmenterOptions
import java.nio.ByteBuffer

data class SegmentationResult(
    val mask: ByteBuffer,
    val maskWidth: Int,
    val maskHeight: Int
)

class SelfieSegmenter {
    private val options = SelfieSegmenterOptions.Builder()
        .setDetectorMode(SelfieSegmenterOptions.STREAM_MODE)
        .enableRawSizeMask()
        .build()
    private val segmenter = Segmentation.getClient(options)

    fun segment(bitmap: Bitmap, rotation: Int): SegmentationResult {
        val image = InputImage.fromBitmap(bitmap, rotation)
        val mask = com.google.android.gms.tasks.Tasks.await(segmenter.process(image))
        return SegmentationResult(
            mask = mask.buffer,
            maskWidth = mask.width,
            maskHeight = mask.height
        )
    }

    fun close() { segmenter.close() }
}
