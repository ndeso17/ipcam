package com.example.ipcam.gpuimage

import android.opengl.GLES20
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer

class GLTextureRenderer {

    companion object {
        private val QUAD_VERTICES = floatArrayOf(
            -1f, -1f,
             1f, -1f,
            -1f,  1f,
             1f,  1f
        )
        private val QUAD_TEX_COORDS = floatArrayOf(
            0f, 0f,
            1f, 0f,
            0f, 1f,
            1f, 1f
        )
        private const val FLOAT_SIZE = 4
        private const val COORDS_PER_VERTEX = 2
    }

    private val vertexBuffer: FloatBuffer = ByteBuffer
        .allocateDirect(QUAD_VERTICES.size * FLOAT_SIZE)
        .order(ByteOrder.nativeOrder())
        .asFloatBuffer()
        .put(QUAD_VERTICES)
        .apply { position(0) }

    private val texCoordBuffer: FloatBuffer = ByteBuffer
        .allocateDirect(QUAD_TEX_COORDS.size * FLOAT_SIZE)
        .order(ByteOrder.nativeOrder())
        .asFloatBuffer()
        .put(QUAD_TEX_COORDS)
        .apply { position(0) }

    private val fboId = IntArray(1)
    private val tempTexId = IntArray(1)

    /**
     * Renders inputTexId through the given shader into a new texture.
     * Returns the output texture ID.
     */
    fun renderToTexture(
        inputTexId: Int,
        width: Int,
        height: Int,
        shader: ShaderProgram,
        uniforms: Map<String, Float> = emptyMap()
    ): Int {
        // Create FBO
        GLES20.glGenFramebuffers(1, fboId, 0)

        // Create output texture
        GLES20.glGenTextures(1, tempTexId, 0)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, tempTexId[0])
        GLES20.glTexImage2D(
            GLES20.GL_TEXTURE_2D, 0, GLES20.GL_RGBA,
            width, height, 0,
            GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, null
        )
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)

        // Attach texture to FBO
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, fboId[0])
        GLES20.glFramebufferTexture2D(
            GLES20.GL_FRAMEBUFFER, GLES20.GL_COLOR_ATTACHMENT0,
            GLES20.GL_TEXTURE_2D, tempTexId[0], 0
        )

        GLES20.glViewport(0, 0, width, height)

        // Bind input texture
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, inputTexId)

        // Use shader and set uniforms
        shader.use()
        shader.setUniform1f("inputWidth", width.toFloat())
        shader.setUniform1f("inputHeight", height.toFloat())
        for ((name, value) in uniforms) {
            shader.setUniform1f(name, value)
        }

        // Draw full-screen quad
        val posLoc = GLES20.glGetAttribLocation(shader.programId, "position")
        val texLoc = GLES20.glGetAttribLocation(shader.programId, "texCoord")

        GLES20.glEnableVertexAttribArray(posLoc)
        GLES20.glVertexAttribPointer(posLoc, COORDS_PER_VERTEX, GLES20.GL_FLOAT, false, 0, vertexBuffer)

        GLES20.glEnableVertexAttribArray(texLoc)
        GLES20.glVertexAttribPointer(texLoc, COORDS_PER_VERTEX, GLES20.GL_FLOAT, false, 0, texCoordBuffer)

        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)

        GLES20.glDisableVertexAttribArray(posLoc)
        GLES20.glDisableVertexAttribArray(texLoc)

        val outputTexId = tempTexId[0]

        // Cleanup: unbind FBO, delete FBO object (texture persists)
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0)
        GLES20.glDeleteFramebuffers(1, fboId, 0)

        return outputTexId
    }

    /**
     * Reads RGBA pixels from the currently bound framebuffer.
     */
    fun readPixels(width: Int, height: Int): ByteBuffer {
        val buffer = ByteBuffer.allocateDirect(width * height * 4)
        buffer.order(ByteOrder.nativeOrder())
        GLES20.glReadPixels(0, 0, width, height, GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, buffer)
        buffer.rewind()
        return buffer
    }
}
