package com.example.ipcam.gpuimage

import android.content.Context
import android.opengl.GLES20
import android.util.Log

class ShaderProgram(context: Context, vertexResId: Int, fragmentResId: Int) {

    companion object {
        private const val TAG = "ShaderProgram"
    }

    val programId: Int

    init {
        val vertexSrc = loadRawResource(context, vertexResId)
        val fragmentSrc = loadRawResource(context, fragmentResId)

        val vertexShader = compileShader(GLES20.GL_VERTEX_SHADER, vertexSrc)
        val fragmentShader = compileShader(GLES20.GL_FRAGMENT_SHADER, fragmentSrc)

        programId = GLES20.glCreateProgram()
        GLES20.glAttachShader(programId, vertexShader)
        GLES20.glAttachShader(programId, fragmentShader)
        GLES20.glLinkProgram(programId)

        val linkStatus = IntArray(1)
        GLES20.glGetProgramiv(programId, GLES20.GL_LINK_STATUS, linkStatus, 0)
        if (linkStatus[0] == 0) {
            val log = GLES20.glGetProgramInfoLog(programId)
            GLES20.glDeleteProgram(programId)
            throw RuntimeException("Program link failed: $log")
        }

        GLES20.glDeleteShader(vertexShader)
        GLES20.glDeleteShader(fragmentShader)
    }

    fun use() {
        GLES20.glUseProgram(programId)
    }

    fun release() {
        GLES20.glDeleteProgram(programId)
    }

    fun setUniform1f(name: String, value: Float) {
        val loc = GLES20.glGetUniformLocation(programId, name)
        if (loc >= 0) GLES20.glUniform1f(loc, value)
    }

    fun setUniform2f(name: String, x: Float, y: Float) {
        val loc = GLES20.glGetUniformLocation(programId, name)
        if (loc >= 0) GLES20.glUniform2f(loc, x, y)
    }

    private fun loadRawResource(context: Context, resId: Int): String {
        if (resId == 0) return ""
        context.resources.openRawResource(resId).use { stream ->
            return stream.bufferedReader().readText()
        }
    }

    private fun compileShader(type: Int, source: String): Int {
        val shader = GLES20.glCreateShader(type)
        GLES20.glShaderSource(shader, source)
        GLES20.glCompileShader(shader)

        val status = IntArray(1)
        GLES20.glGetShaderiv(shader, GLES20.GL_COMPILE_STATUS, status, 0)
        if (status[0] == 0) {
            val log = GLES20.glGetShaderInfoLog(shader)
            GLES20.glDeleteShader(shader)
            val typeName = if (type == GLES20.GL_VERTEX_SHADER) "vertex" else "fragment"
            throw RuntimeException("$typeName shader compile failed: $log")
        }
        return shader
    }
}
