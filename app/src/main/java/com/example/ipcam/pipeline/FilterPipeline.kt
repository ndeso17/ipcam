package com.example.ipcam.pipeline

import android.content.Context
import android.graphics.*
import android.opengl.EGL14
import android.opengl.EGLConfig
import android.opengl.EGLContext
import android.opengl.EGLDisplay
import android.opengl.EGLSurface
import android.opengl.GLES20
import android.opengl.GLUtils
import android.util.Log
import com.example.ipcam.FrameBuffer
import com.example.ipcam.ai.AiProcessor
import com.example.ipcam.ai.AiResult
import com.example.ipcam.gpuimage.FilterManager
import com.example.ipcam.gpuimage.FilterType
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

object FilterPipeline {

    private const val TAG = "FilterPipeline"

    @Volatile var activeFilter: FilterType = FilterType.NONE
    @Volatile var filterIntensity: Float = 1.0f
    @Volatile var isFaceBlurEnabled: Boolean = false
    @Volatile var isBgReplaceEnabled: Boolean = false

    private var isInitialized = false
    private var eglDisplay: EGLDisplay? = null
    private var eglContext: EGLContext? = null
    private var eglSurface: EGLSurface? = null
    private var inputTextureId: Int = 0
    private var lastWidth: Int = 0
    private var lastHeight: Int = 0
    private var reuseBitmap: Bitmap? = null
    private var glAvailable = false

    fun init(context: Context) {
        if (isInitialized) return
        FilterManager.init(context)
        AiProcessor.isFaceDetectionEnabled = true
        isInitialized = true
        Log.i(TAG, "FilterPipeline initialized")
    }

    fun shutdown() {
        FilterManager.shutdown()
        AiProcessor.shutdown()
        destroyEGL()
        reuseBitmap?.recycle()
        reuseBitmap = null
        isInitialized = false
    }

    fun process(nv21: ByteArray, width: Int, height: Int, rotationDegrees: Int, quality: Int) {
        try {
            val yuvImage = YuvImage(nv21, ImageFormat.NV21, width, height, null)
            val jpegStream = ByteArrayOutputStream()
            yuvImage.compressToJpeg(Rect(0, 0, width, height), 100, jpegStream)
            val bitmap = BitmapFactory.decodeByteArray(jpegStream.toByteArray(), 0, jpegStream.size())
                ?: return

            val aiResult = AiProcessor.process(bitmap, rotationDegrees)

            val filter = if (isBgReplaceEnabled) FilterType.BG_REPLACE
                         else if (isFaceBlurEnabled) FilterType.BLUR
                         else activeFilter

            val rotated = if (rotationDegrees != 0) rotateBitmap(bitmap, rotationDegrees) else bitmap
            if (rotationDegrees != 0) bitmap.recycle()

            val result = when (filter) {
                FilterType.NONE -> {
                    if (aiResult.faces.isEmpty()) rotated
                    else applyFaceBlurBitmap(rotated, aiResult)
                }
                FilterType.BLUR -> applyFaceBlurBitmap(rotated, aiResult)
                FilterType.BG_REPLACE -> applyBgReplaceBitmap(rotated, aiResult)
                else -> {
                    val filtered = applyColorFilter(rotated, filter)
                    if (aiResult.faces.isNotEmpty() && (isFaceBlurEnabled || isBgReplaceEnabled)) {
                        applyFaceBlurBitmap(filtered, aiResult)
                    } else filtered
                }
            }

            val out = ByteArrayOutputStream()
            result.compress(Bitmap.CompressFormat.JPEG, quality, out)
            if (result !== rotated) result.recycle()
            rotated.recycle()
            FrameBuffer.latestFrame = out.toByteArray()
        } catch (e: Exception) {
            Log.e(TAG, "Pipeline error: ${e.message}", e)
        }
    }

    // ─── Bitmap-based color filters (no GL needed) ─────────────────────

    private fun applyColorFilter(source: Bitmap, filter: FilterType): Bitmap {
        val cm = when (filter) {
            FilterType.SEPIA -> ColorMatrix().apply {
                setSaturation(0f)
                val sepia = floatArrayOf(
                    0.393f, 0.769f, 0.189f, 0f, 0f,
                    0.349f, 0.686f, 0.168f, 0f, 0f,
                    0.272f, 0.534f, 0.131f, 0f, 0f,
                    0f, 0f, 0f, 1f, 0f
                )
                set(sepia)
            }
            FilterType.GRAYSCALE -> ColorMatrix().apply { setSaturation(0f) }
            FilterType.NEGATIVE -> ColorMatrix(
                floatArrayOf(
                    -1f, 0f, 0f, 0f, 255f,
                    0f, -1f, 0f, 0f, 255f,
                    0f, 0f, -1f, 0f, 255f,
                    0f, 0f, 0f, 1f, 0f
                )
            )
            FilterType.BRIGHTNESS -> {
                val s = filterIntensity * 2f - 1f
                ColorMatrix().apply { setScale(1f + s, 1f + s, 1f + s, 1f) }
            }
            FilterType.CONTRAST -> {
                val scale = filterIntensity * 3f
                val translate = (-0.5f * scale + 0.5f) * 255f
                ColorMatrix(floatArrayOf(
                    scale, 0f, 0f, 0f, translate,
                    0f, scale, 0f, 0f, translate,
                    0f, 0f, scale, 0f, translate,
                    0f, 0f, 0f, 1f, 0f
                ))
            }
            FilterType.SATURATION -> ColorMatrix().apply { setSaturation(filterIntensity * 2f) }
            FilterType.VIGNETTE -> return applyVignetteBitmap(source)
            FilterType.GLOW -> return applyGlowBitmap(source)
            FilterType.BEAUTY -> return applyBeautyBitmap(source)
            FilterType.SKETCH -> return applySketchBitmap(source)
            FilterType.TOON -> return applyToonBitmap(source)
            FilterType.PIXELATE -> return applyPixelateBitmap(source)
            FilterType.KALEIDOSCOPE -> return applyKaleidoscopeBitmap(source)
            else -> return source
        }

        val result = Bitmap.createBitmap(source.width, source.height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(result)
        val paint = Paint().apply { colorFilter = ColorMatrixColorFilter(cm) }
        canvas.drawBitmap(source, 0f, 0f, paint)
        return result
    }

    private fun applyVignetteBitmap(source: Bitmap): Bitmap {
        val result = Bitmap.createBitmap(source.width, source.height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(result)
        canvas.drawBitmap(source, 0f, 0f, null)
        val paint = Paint().apply {
            shader = RadialGradient(
                source.width / 2f, source.height / 2f,
                source.width * 0.75f,
                intArrayOf(Color.TRANSPARENT, Color.TRANSPARENT, Color.argb(180, 0, 0, 0)),
                floatArrayOf(0.3f, 0.7f, 1.0f),
                Shader.TileMode.CLAMP
            )
        }
        canvas.drawRect(0f, 0f, source.width.toFloat(), source.height.toFloat(), paint)
        return result
    }

    private fun applyBeautyBitmap(source: Bitmap): Bitmap {
        // Skin smoothing via stack blur + brightness boost + blend
        val blurred = blurBitmap(source, 12)
        val result = Bitmap.createBitmap(source.width, source.height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(result)
        // Lighten original
        val cm = ColorMatrix().apply { setScale(1.15f, 1.15f, 1.15f, 1f) }
        canvas.drawBitmap(source, 0f, 0f, Paint().apply { colorFilter = ColorMatrixColorFilter(cm) })
        // Blend with blurred for skin smoothing (stronger alpha)
        canvas.drawBitmap(blurred, 0f, 0f, Paint().apply { alpha = 160 })
        blurred.recycle()
        return result
    }

    private fun applyGlowBitmap(source: Bitmap): Bitmap {
        val blurred = blurBitmap(source, 15)
        val result = Bitmap.createBitmap(source.width, source.height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(result)
        // Draw original
        canvas.drawBitmap(source, 0f, 0f, null)
        // Draw blurred with stronger additive-like blend
        val paint = Paint().apply {
            alpha = (filterIntensity * 180).toInt().coerceIn(50, 255)
            xfermode = PorterDuffXfermode(PorterDuff.Mode.SCREEN)
        }
        canvas.drawBitmap(blurred, 0f, 0f, paint)
        blurred.recycle()
        return result
    }

    private fun applySketchBitmap(source: Bitmap): Bitmap {
        val gray = Bitmap.createBitmap(source.width, source.height, Bitmap.Config.ARGB_8888)
        val c = Canvas(gray)
        val cm = ColorMatrix().apply { setSaturation(0f) }
        c.drawBitmap(source, 0f, 0f, Paint().apply { colorFilter = ColorMatrixColorFilter(cm) })
        val inverted = Bitmap.createBitmap(source.width, source.height, Bitmap.Config.ARGB_8888)
        val ci = Canvas(inverted)
        ci.drawBitmap(gray, 0f, 0f, Paint().apply {
            colorFilter = ColorMatrixColorFilter(ColorMatrix(floatArrayOf(
                -1f, 0f, 0f, 0f, 255f,
                0f, -1f, 0f, 0f, 255f,
                0f, 0f, -1f, 0f, 255f,
                0f, 0f, 0f, 1f, 0f
            )))
        })
        val blurred = blurBitmap(inverted, 5)
        gray.recycle()
        inverted.recycle()
        val result = Bitmap.createBitmap(source.width, source.height, Bitmap.Config.ARGB_8888)
        val cr = Canvas(result)
        cr.drawBitmap(blurred, 0f, 0f, Paint().apply {
            xfermode = PorterDuffXfermode(PorterDuff.Mode.SCREEN)
            alpha = 220
        })
        cr.drawBitmap(source, 0f, 0f, Paint().apply { alpha = 200 })
        blurred.recycle()
        return result
    }

    private fun applyToonBitmap(source: Bitmap): Bitmap {
        val result = Bitmap.createBitmap(source.width, source.height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(result)
        val cm = ColorMatrix().apply {
            setScale(0.33f, 0.33f, 0.33f, 1f)
        }
        canvas.drawBitmap(source, 0f, 0f, Paint().apply { colorFilter = ColorMatrixColorFilter(cm) })
        val cm2 = ColorMatrix().apply { setScale(4f, 4f, 4f, 1f) }
        canvas.drawBitmap(source, 0f, 0f, Paint().apply { colorFilter = ColorMatrixColorFilter(cm2) })
        return result
    }

    private fun applyPixelateBitmap(source: Bitmap): Bitmap {
        val pixelSize = (filterIntensity * 20f).toInt().coerceIn(2, 50)
        val small = Bitmap.createScaledBitmap(source, source.width / pixelSize, source.height / pixelSize, false)
        val result = Bitmap.createScaledBitmap(small, source.width, source.height, false)
        small.recycle()
        return result
    }

    private fun applyKaleidoscopeBitmap(source: Bitmap): Bitmap {
        val result = Bitmap.createBitmap(source.width, source.height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(result)
        for (i in 0 until 4) {
            canvas.save()
            canvas.rotate(90f * i, source.width / 2f, source.height / 2f)
            if (i % 2 == 1) canvas.scale(-1f, 1f, source.width / 2f, source.height / 2f)
            canvas.drawBitmap(source, 0f, 0f, null)
            canvas.restore()
        }
        return result
    }

    // ─── Face blur & BG replace ────────────────────────────────────────

    private fun applyFaceBlurBitmap(source: Bitmap, aiResult: AiResult): Bitmap {
        if (aiResult.faces.isEmpty()) return source
        val result = source.copy(Bitmap.Config.ARGB_8888, true)
        val canvas = Canvas(result)
        for (face in aiResult.faces) {
            val box = face.boundingBox
            val expanded = Rect(
                (box.left - box.width() * 0.1f).toInt().coerceAtLeast(0),
                (box.top - box.height() * 0.1f).toInt().coerceAtLeast(0),
                (box.right + box.width() * 0.1f).toInt().coerceAtMost(source.width),
                (box.bottom + box.height() * 0.1f).toInt().coerceAtMost(source.height)
            )
            val faceBmp = Bitmap.createBitmap(source, expanded.left, expanded.top,
                expanded.width(), expanded.height())
            val blurred = blurBitmap(faceBmp, 25)
            canvas.drawBitmap(blurred, expanded.left.toFloat(), expanded.top.toFloat(), null)
            faceBmp.recycle()
            blurred.recycle()
        }
        return result
    }

    private fun applyBgReplaceBitmap(source: Bitmap, aiResult: AiResult): Bitmap {
        val result = source.copy(Bitmap.Config.ARGB_8888, true)
        val canvas = Canvas(result)
        val bgPaint = Paint().apply { color = Color.argb(200, 30, 30, 30) }
        if (aiResult.faces.isNotEmpty()) {
            for (face in aiResult.faces) {
                val box = face.boundingBox
                val expanded = Rect(
                    (box.left - box.width() * 0.5f).toInt().coerceAtLeast(0),
                    (box.top - box.height() * 0.3f).toInt().coerceAtLeast(0),
                    (box.right + box.width() * 0.5f).toInt().coerceAtMost(source.width),
                    (box.bottom + box.height() * 1.0f).toInt().coerceAtMost(source.height)
                )
                canvas.drawRect(expanded.left.toFloat(), expanded.top.toFloat(),
                    expanded.right.toFloat(), expanded.bottom.toFloat(), bgPaint)
            }
        } else {
            canvas.drawColor(Color.argb(150, 0, 0, 0))
        }
        return result
    }

    // ─── Utility ────────────────────────────────────────────────────────

    private fun blurBitmap(source: Bitmap, radius: Int): Bitmap {
        if (radius <= 0) return source
        val r = radius.coerceIn(1, 25)
        val result = source.copy(Bitmap.Config.ARGB_8888, true) ?: return source
        applyStackBlur(result, r)
        return result
    }

    /**
     * Stack Blur — fast approximation of Gaussian blur.
     * Operates in-place on the bitmap pixels.
     */
    private fun applyStackBlur(bitmap: Bitmap, radius: Int) {
        val w = bitmap.width
        val h = bitmap.height
        if (w == 0 || h == 0) return

        val pixels = IntArray(w * h)
        bitmap.getPixels(pixels, 0, w, 0, 0, w, h)

        val wm = w - 1
        val hm = h - 1
        val div = radius + radius + 1
        val r = IntArray(w)
        val g = IntArray(w)
        val b = IntArray(w)
        val vMin = IntArray(Math.max(w, h))

        var rsum: Int
        var gsum: Int
        var bsum: Int
        var p: Int
        var stackStart: Int
        val sir: IntArray
        val stack = Array(div) { IntArray(3) }

        val mulSum = intArrayOf(
            512,512,456,512,328,456,335,512,405,328,271,456,388,335,292,512,
            454,405,364,328,298,271,496,456,420,388,360,335,312,292,273,512,
            482,454,428,405,383,364,345,328,312,298,284,271,259,496,475,456,
            437,420,404,388,374,360,347,335,323,312,302,292,282,273,265,512,
            497,482,468,454,441,428,417,405,394,383,373,364,354,345,337,328,
            320,312,305,298,291,284,278,271,265,259,507,496,485,475,465,456,
            446,437,428,420,412,404,396,388,381,374,367,360,354,347,341,335,
            329,323,318,312,307,302,297,292,287,282,278,273,269,265,261,512,
            505,497,489,482,475,468,461,454,447,441,435,428,422,417,411,405,
            399,394,389,383,378,373,368,364,359,354,350,345,341,337,332,328,
            324,320,316,312,309,305,301,298,294,291,287,284,281,278,274,271,
            268,265,262,259,257,507,501,496,491,485,480,475,470,465,460,456,
            451,446,442,437,433,428,424,420,416,412,408,404,400,396,392,388,
            385,381,377,374,370,367,363,360,357,354,350,347,344,341,338,335,
            332,329,326,323,320,318,315,312,310,307,304,302,299,297,294,292,
            289,287,285,282,280,278,275,273,271,269,267,265,263,261,259
        )
        val dv = IntArray(256 * div)
        for (i in dv.indices) dv[i] = i / div

        // Horizontal pass
        for (y in 0 until h) {
            rsum = 0; gsum = 0; bsum = 0
            for (i in -radius..radius) {
                val idx = (i + radius)
                val px = pixels[y * w + i.coerceIn(0, wm)]
                stack[idx][0] = (px shr 16) and 0xff
                stack[idx][1] = (px shr 8) and 0xff
                stack[idx][2] = px and 0xff
                val rbs = radius + 1 - Math.abs(i)
                rsum += stack[idx][0] * rbs
                gsum += stack[idx][1] * rbs
                bsum += stack[idx][2] * rbs
            }
            stackStart = 0
            for (x in 0 until w) {
                r[x] = dv[rsum]
                g[x] = dv[gsum]
                b[x] = dv[bsum]

                rsum -= stack[stackStart][0] * (radius + 1)
                gsum -= stack[stackStart][1] * (radius + 1)
                bsum -= stack[stackStart][2] * (radius + 1)

                val nextIdx = (x + radius + 1).coerceIn(0, wm)
                val stackEnd = (stackStart + radius) % div
                val px = pixels[y * w + nextIdx]
                stack[stackEnd][0] = (px shr 16) and 0xff
                stack[stackEnd][1] = (px shr 8) and 0xff
                stack[stackEnd][2] = px and 0xff

                rsum += stack[stackEnd][0] * (radius + 1)
                gsum += stack[stackEnd][1] * (radius + 1)
                bsum += stack[stackEnd][2] * (radius + 1)

                stackStart = (stackStart + 1) % div

                pixels[y * w + x] = (pixels[y * w + x] and -0x1000000) or
                    (r[x] shl 16) or (g[x] shl 8) or b[x]
            }
        }

        // Vertical pass
        for (x in 0 until w) {
            rsum = 0; gsum = 0; bsum = 0
            for (i in -radius..radius) {
                val yi = (i).coerceIn(0, hm)
                val idx = i + radius
                val px = pixels[yi * w + x]
                stack[idx][0] = (px shr 16) and 0xff
                stack[idx][1] = (px shr 8) and 0xff
                stack[idx][2] = px and 0xff
                val rbs = radius + 1 - Math.abs(i)
                rsum += stack[idx][0] * rbs
                gsum += stack[idx][1] * rbs
                bsum += stack[idx][2] * rbs
            }
            stackStart = 0
            for (y in 0 until h) {
                r[y] = dv[rsum]
                g[y] = dv[gsum]
                b[y] = dv[bsum]

                rsum -= stack[stackStart][0] * (radius + 1)
                gsum -= stack[stackStart][1] * (radius + 1)
                bsum -= stack[stackStart][2] * (radius + 1)

                val nextY = (y + radius + 1).coerceIn(0, hm)
                val stackEnd = (stackStart + radius) % div
                val px = pixels[nextY * w + x]
                stack[stackEnd][0] = (px shr 16) and 0xff
                stack[stackEnd][1] = (px shr 8) and 0xff
                stack[stackEnd][2] = px and 0xff

                rsum += stack[stackEnd][0] * (radius + 1)
                gsum += stack[stackEnd][1] * (radius + 1)
                bsum += stack[stackEnd][2] * (radius + 1)

                stackStart = (stackStart + 1) % div

                pixels[y * w + x] = (pixels[y * w + x] and -0x1000000) or
                    (r[y] shl 16) or (g[y] shl 8) or b[y]
            }
        }

        bitmap.setPixels(pixels, 0, w, 0, 0, w, h)
    }

    private fun rotateBitmap(bitmap: Bitmap, degrees: Int): Bitmap {
        if (degrees == 0) return bitmap
        val matrix = Matrix().apply { postRotate(degrees.toFloat()) }
        return Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
    }

    // ─── EGL (unused now, kept for future GL integration) ──────────────

    private fun ensureEGL(w: Int, h: Int): Boolean {
        if (eglDisplay != null && eglContext != null) return true
        try {
            eglDisplay = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
            val v = IntArray(2)
            if (!EGL14.eglInitialize(eglDisplay, v, 0, v, 1)) return false
            val configs = arrayOfNulls<EGLConfig>(1)
            val n = IntArray(1)
            EGL14.eglChooseConfig(eglDisplay, intArrayOf(
                EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT,
                EGL14.EGL_RED_SIZE, 8, EGL14.EGL_GREEN_SIZE, 8,
                EGL14.EGL_BLUE_SIZE, 8, EGL14.EGL_ALPHA_SIZE, 8, EGL14.EGL_NONE
            ), 0, configs, 0, 1, n, 0)
            eglContext = EGL14.eglCreateContext(eglDisplay, configs[0], EGL14.EGL_NO_CONTEXT,
                intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION, 2, EGL14.EGL_NONE), 0)
            eglSurface = EGL14.eglCreatePbufferSurface(eglDisplay, configs[0],
                intArrayOf(EGL14.EGL_WIDTH, w, EGL14.EGL_HEIGHT, h, EGL14.EGL_NONE), 0)
            EGL14.eglMakeCurrent(eglDisplay, eglSurface, eglSurface, eglContext)
            glAvailable = true
            return true
        } catch (e: Exception) {
            Log.w(TAG, "EGL init failed, using bitmap fallback: ${e.message}")
            return false
        }
    }

    private fun destroyEGL() {
        if (eglDisplay != null) {
            EGL14.eglMakeCurrent(eglDisplay, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT)
            if (eglSurface != null) EGL14.eglDestroySurface(eglDisplay, eglSurface)
            if (eglContext != null) EGL14.eglDestroyContext(eglDisplay, eglContext)
            EGL14.eglTerminate(eglDisplay)
        }
        glAvailable = false
    }
}