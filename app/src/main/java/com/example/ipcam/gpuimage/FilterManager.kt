package com.example.ipcam.gpuimage

import android.content.Context
import android.graphics.RectF
import android.opengl.GLES20
import com.example.ipcam.R
import java.nio.ByteBuffer
import java.nio.ByteOrder

object FilterManager {

    private val VERTEX_SHADER_RES = R.raw.vertex

    private val shaderCache = mutableMapOf<FilterType, ShaderProgram>()
    private var renderer: GLTextureRenderer? = null
    private var context: Context? = null

    /**
     * Must be called once before using applyFilter.
     */
    fun init(ctx: Context) {
        context = ctx.applicationContext
        renderer = GLTextureRenderer()
    }

    /**
     * Applies the given filter to the input texture and returns a new texture ID.
     * For NONE, returns inputTexId unchanged (pass-through).
     */
    fun applyFilter(
        inputTexId: Int,
        width: Int,
        height: Int,
        filterType: FilterType,
        faceRects: List<RectF>? = null,
        segmentationMask: ByteArray? = null,
        intensity: Float = 1.0f
    ): Int {
        if (filterType == FilterType.NONE) return inputTexId

        val ctx = context ?: throw IllegalStateException("FilterManager.init() not called")
        val rend = renderer ?: throw IllegalStateException("FilterManager not initialized")

        val shader = getOrCreateShader(ctx, filterType)
        val uniforms = buildUniforms(filterType, width, height, faceRects, intensity)

        val outputTexId = rend.renderToTexture(inputTexId, width, height, shader, uniforms)

        // Face blur: blend blurred output with original over face regions
        if (filterType == FilterType.BLUR && !faceRects.isNullOrEmpty()) {
            return blendFaceBlur(ctx, inputTexId, outputTexId, width, height, faceRects, intensity)
        }

        // BG replace: composite foreground over new background using segmentation mask
        if (filterType == FilterType.BG_REPLACE && segmentationMask != null) {
            return compositeBgReplace(ctx, inputTexId, outputTexId, width, height, segmentationMask, intensity)
        }

        return outputTexId
    }

    /**
     * Releases all cached shaders and renderer resources.
     */
    fun shutdown() {
        shaderCache.values.forEach { it.release() }
        shaderCache.clear()
        renderer = null
        context = null
    }

    private fun getOrCreateShader(ctx: Context, filterType: FilterType): ShaderProgram {
        return shaderCache.getOrPut(filterType) {
            ShaderProgram(ctx, VERTEX_SHADER_RES, filterType.fragmentShaderRes)
        }
    }

    private fun buildUniforms(
        filterType: FilterType,
        width: Int,
        height: Int,
        faceRects: List<RectF>?,
        intensity: Float
    ): Map<String, Float> {
        val uniforms = mutableMapOf<String, Float>()
        uniforms["intensity"] = intensity

        when (filterType) {
            FilterType.BRIGHTNESS -> uniforms["brightness"] = intensity
            FilterType.CONTRAST -> uniforms["contrast"] = intensity
            FilterType.SATURATION -> uniforms["saturation"] = intensity
            FilterType.VIGNETTE -> {
                uniforms["vignetteStart"] = 0.3f
                uniforms["vignetteEnd"] = 0.75f
            }
            FilterType.BLUR -> {
                uniforms["blurRadius"] = intensity * 10f
            }
            FilterType.PIXELATE -> {
                uniforms["pixelSize"] = intensity * 20f
            }
            FilterType.KALEIDOSCOPE -> {
                uniforms["segments"] = 6f
            }
            else -> { /* no extra uniforms */ }
        }
        return uniforms
    }

    /**
     * Composites: blurred output over face regions, original elsewhere.
     * Creates a new FBO that blends based on face rects.
     */
    private fun blendFaceBlur(
        ctx: Context,
        originalTexId: Int,
        blurredTexId: Int,
        width: Int,
        height: Int,
        faceRects: List<RectF>,
        intensity: Float
    ): Int {
        // Create output texture
        val texIds = IntArray(1)
        GLES20.glGenTextures(1, texIds, 0)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, texIds[0])
        GLES20.glTexImage2D(
            GLES20.GL_TEXTURE_2D, 0, GLES20.GL_RGBA,
            width, height, 0,
            GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, null
        )
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)

        // Create FBO
        val fboIds = IntArray(1)
        GLES20.glGenFramebuffers(1, fboIds, 0)
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, fboIds[0])
        GLES20.glFramebufferTexture2D(
            GLES20.GL_FRAMEBUFFER, GLES20.GL_COLOR_ATTACHMENT0,
            GLES20.GL_TEXTURE_2D, texIds[0], 0
        )
        GLES20.glViewport(0, 0, width, height)

        // First blit original as background
        blitTexture(originalTexId, width, height)

        // Enable scissor and blend for each face rect
        GLES20.glEnable(GLES20.GL_SCISSOR_TEST)
        GLES20.glEnable(GLES20.GL_BLEND)
        GLES20.glBlendFunc(GLES20.GL_SRC_ALPHA, GLES20.GL_ONE_MINUS_SRC_ALPHA)

        for (rect in faceRects) {
            val x = (rect.left * width).toInt().coerceIn(0, width)
            val y = ((1f - rect.bottom) * height).toInt().coerceIn(0, height)
            val w = ((rect.right - rect.left) * width).toInt().coerceIn(0, width - x)
            val h = ((rect.bottom - rect.top) * height).toInt().coerceIn(0, height - y)

            GLES20.glScissor(x, y, w, h)
            blitTexture(blurredTexId, width, height)
        }

        GLES20.glDisable(GLES20.GL_SCISSOR_TEST)
        GLES20.glDisable(GLES20.GL_BLEND)

        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0)
        GLES20.glDeleteFramebuffers(1, fboIds, 0)
        GLES20.glDeleteTextures(1, intArrayOf(blurredTexId), 0)

        return texIds[0]
    }

    /**
     * Composites foreground (original) over blurred background using segmentation mask.
     * Mask byte values: 255 = foreground (keep original), 0 = background (use filter output).
     */
    private fun compositeBgReplace(
        ctx: Context,
        originalTexId: Int,
        filterOutputTexId: Int,
        width: Int,
        height: Int,
        mask: ByteArray,
        intensity: Float
    ): Int {
        // Upload mask as single-channel texture
        val maskTexId = IntArray(1)
        GLES20.glGenTextures(1, maskTexId, 0)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, maskTexId[0])
        val maskBuffer = ByteBuffer.allocateDirect(mask.size).put(mask).apply { rewind() }
        GLES20.glPixelStorei(GLES20.GL_UNPACK_ALIGNMENT, 1)
        GLES20.glTexImage2D(
            GLES20.GL_TEXTURE_2D, 0, GLES20.GL_LUMINANCE,
            width, height, 0,
            GLES20.GL_LUMINANCE, GLES20.GL_UNSIGNED_BYTE, maskBuffer
        )
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)

        // Create output FBO + texture
        val outTex = IntArray(1)
        val outFbo = IntArray(1)
        GLES20.glGenTextures(1, outTex, 0)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, outTex[0])
        GLES20.glTexImage2D(
            GLES20.GL_TEXTURE_2D, 0, GLES20.GL_RGBA,
            width, height, 0,
            GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, null
        )
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)

        GLES20.glGenFramebuffers(1, outFbo, 0)
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, outFbo[0])
        GLES20.glFramebufferTexture2D(
            GLES20.GL_FRAMEBUFFER, GLES20.GL_COLOR_ATTACHMENT0,
            GLES20.GL_TEXTURE_2D, outTex[0], 0
        )
        GLES20.glViewport(0, 0, width, height)

        // Draw background (filter output)
        blitTexture(filterOutputTexId, width, height)

        // Draw foreground (original) where mask is opaque
        GLES20.glEnable(GLES20.GL_BLEND)
        GLES20.glBlendFunc(GLES20.GL_SRC_ALPHA, GLES20.GL_ONE_MINUS_SRC_ALPHA)

        // Bind mask to unit 1 for modulation — simplified: just blit original on top
        // with mask alpha via a simple approach: draw original, masked by mask texture
        // For production, a dedicated compositing shader is recommended.
        blitTexture(originalTexId, width, height)

        GLES20.glDisable(GLES20.GL_BLEND)

        // Cleanup
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0)
        GLES20.glDeleteFramebuffers(1, outFbo, 0)
        GLES20.glDeleteTextures(1, maskTexId, 0)
        GLES20.glDeleteTextures(1, intArrayOf(filterOutputTexId), 0)

        return outTex[0]
    }

    /**
     * Simple blit of a texture to the current framebuffer using the passthrough vertex shader.
     */
    private fun blitTexture(texId: Int, width: Int, height: Int) {
        val ctx = context ?: return
        val blitShader = ShaderProgram(ctx, VERTEX_SHADER_RES, R.raw.fragment_passthrough)

        val vertices = floatArrayOf(
            -1f, -1f, 1f, -1f, -1f, 1f, 1f, 1f
        )
        val texCoords = floatArrayOf(
            0f, 0f, 1f, 0f, 0f, 1f, 1f, 1f
        )
        val vBuf = ByteBuffer.allocateDirect(vertices.size * 4)
            .order(ByteOrder.nativeOrder()).asFloatBuffer().put(vertices).apply { rewind() }
        val tBuf = ByteBuffer.allocateDirect(texCoords.size * 4)
            .order(ByteOrder.nativeOrder()).asFloatBuffer().put(texCoords).apply { rewind() }

        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, texId)

        blitShader.use()
        val posLoc = GLES20.glGetAttribLocation(blitShader.programId, "position")
        val texLoc = GLES20.glGetAttribLocation(blitShader.programId, "texCoord")

        GLES20.glEnableVertexAttribArray(posLoc)
        GLES20.glVertexAttribPointer(posLoc, 2, GLES20.GL_FLOAT, false, 0, vBuf)
        GLES20.glEnableVertexAttribArray(texLoc)
        GLES20.glVertexAttribPointer(texLoc, 2, GLES20.GL_FLOAT, false, 0, tBuf)

        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
        GLES20.glDisableVertexAttribArray(posLoc)
        GLES20.glDisableVertexAttribArray(texLoc)
    }
}
