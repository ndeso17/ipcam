package com.example.ipcam.gpuimage

import android.graphics.Bitmap
import android.opengl.GLES20
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Captures the current GL framebuffer as a Bitmap for MJPEG streaming.
 * Uses glReadPixels to read GPU output back to CPU.
 */
object FrameCaptureHelper {

    private var pixelBuffer: ByteBuffer? = null
    private var lastWidth = 0
    private var lastHeight = 0

    /**
     * Captures the current GL framebuffer content as a Bitmap.
     * Must be called on the GL thread after rendering.
     *
     * @param width Framebuffer width
     * @param height Framebuffer height
     * @return Bitmap with the captured frame, or null on failure
     */
    fun captureFrame(width: Int, height: Int): Bitmap? {
        return try {
            // Allocate/reuse buffer
            if (pixelBuffer == null || lastWidth != width || lastHeight != height) {
                pixelBuffer = ByteBuffer.allocateDirect(width * height * 4)
                    .order(ByteOrder.LITTLE_ENDIAN)
                lastWidth = width
                lastHeight = height
            }

            pixelBuffer?.let { buffer ->
                buffer.rewind()
                GLES20.glReadPixels(0, 0, width, height, GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, buffer)
                buffer.rewind()

                // Create bitmap from pixel data
                val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
                bitmap.copyPixelsFromBuffer(buffer)

                // Flip vertically (GL coordinates are bottom-up)
                val matrix = android.graphics.Matrix().apply { postScale(1f, -1f, width / 2f, height / 2f) }
                val flipped = Bitmap.createBitmap(bitmap, 0, 0, width, height, matrix, true)
                bitmap.recycle()

                flipped
            }
        } catch (e: Exception) {
            null
        }
    }

    /**
     * Releases cached resources.
     */
    fun release() {
        pixelBuffer = null
        lastWidth = 0
        lastHeight = 0
    }
}
