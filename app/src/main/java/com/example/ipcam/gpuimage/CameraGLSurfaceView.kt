package com.example.ipcam.gpuimage

import android.content.Context
import android.graphics.SurfaceTexture
import android.opengl.GLES11Ext
import android.opengl.GLES20
import android.opengl.GLSurfaceView
import android.util.AttributeSet
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10

/**
 * GLSurfaceView that renders camera preview with GPU filters.
 * Uses OES external texture for camera frames.
 */
class CameraGLSurfaceView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : GLSurfaceView(context, attrs) {

    private var renderer: CameraRenderer? = null
    var onFrameAvailable: ((SurfaceTexture) -> Unit)? = null

    init {
        setEGLContextClientVersion(2)
        renderer = CameraRenderer()
        setRenderer(renderer)
        renderMode = RENDERMODE_WHEN_DIRTY
    }

    fun getSurfaceTexture(): SurfaceTexture? = renderer?.surfaceTexture

    fun setFilter(filterType: FilterType) {
        renderer?.activeFilter = filterType
        requestRender()
    }

    fun setIntensity(intensity: Float) {
        renderer?.intensity = intensity
        requestRender()
    }

    fun requestUpdate() {
        requestRender()
    }

    private inner class CameraRenderer : Renderer {

        var surfaceTexture: SurfaceTexture? = null
            private set
        var activeFilter: FilterType = FilterType.NONE
        var intensity: Float = 1.0f

        private var oesTextureId: Int = 0
        private var shaderProgram: Int = 0
        private var vertexBuffer: FloatBuffer? = null
        private var texCoordBuffer: FloatBuffer? = null
        private var frameAvailable = false

        private val vertexShaderCode = """
            attribute vec4 aPosition;
            attribute vec2 aTexCoord;
            varying vec2 vTexCoord;
            uniform mat4 uMatrix;
            void main() {
                gl_Position = uMatrix * aPosition;
                vTexCoord = aTexCoord;
            }
        """.trimIndent()

        // Passthrough fragment shader (OES texture)
        private val fragmentShaderPassthrough = """
            #extension GL_OES_EGL_image_external : require
            precision mediump float;
            varying vec2 vTexCoord;
            uniform samplerExternalOES uTexture;
            void main() {
                gl_FragColor = texture2D(uTexture, vTexCoord);
            }
        """.trimIndent()

        // Sepia filter
        private val fragmentShaderSepia = """
            #extension GL_OES_EGL_image_external : require
            precision mediump float;
            varying vec2 vTexCoord;
            uniform samplerExternalOES uTexture;
            uniform float uIntensity;
            void main() {
                vec4 color = texture2D(uTexture, vTexCoord);
                float gray = dot(color.rgb, vec3(0.299, 0.587, 0.114));
                vec3 sepia = vec3(gray) * vec3(1.2, 1.0, 0.8);
                gl_FragColor = vec4(mix(color.rgb, sepia, uIntensity), color.a);
            }
        """.trimIndent()

        // Grayscale filter
        private val fragmentShaderGrayscale = """
            #extension GL_OES_EGL_image_external : require
            precision mediump float;
            varying vec2 vTexCoord;
            uniform samplerExternalOES uTexture;
            uniform float uIntensity;
            void main() {
                vec4 color = texture2D(uTexture, vTexCoord);
                float gray = dot(color.rgb, vec3(0.299, 0.587, 0.114));
                gl_FragColor = vec4(mix(color.rgb, vec3(gray), uIntensity), color.a);
            }
        """.trimIndent()

        // Negative filter
        private val fragmentShaderNegative = """
            #extension GL_OES_EGL_image_external : require
            precision mediump float;
            varying vec2 vTexCoord;
            uniform samplerExternalOES uTexture;
            uniform float uIntensity;
            void main() {
                vec4 color = texture2D(uTexture, vTexCoord);
                vec3 negative = 1.0 - color.rgb;
                gl_FragColor = vec4(mix(color.rgb, negative, uIntensity), color.a);
            }
        """.trimIndent()

        // Brightness filter
        private val fragmentShaderBrightness = """
            #extension GL_OES_EGL_image_external : require
            precision mediump float;
            varying vec2 vTexCoord;
            uniform samplerExternalOES uTexture;
            uniform float uIntensity;
            void main() {
                vec4 color = texture2D(uTexture, vTexCoord);
                float brightness = uIntensity * 2.0 - 1.0;
                gl_FragColor = vec4(color.rgb + brightness, color.a);
            }
        """.trimIndent()

        // Contrast filter
        private val fragmentShaderContrast = """
            #extension GL_OES_EGL_image_external : require
            precision mediump float;
            varying vec2 vTexCoord;
            uniform samplerExternalOES uTexture;
            uniform float uIntensity;
            void main() {
                vec4 color = texture2D(uTexture, vTexCoord;
                float contrast = uIntensity * 3.0;
                gl_FragColor = vec4((color.rgb - 0.5) * contrast + 0.5, color.a);
            }
        """.trimIndent()

        // Vignette filter
        private val fragmentShaderVignette = """
            #extension GL_OES_EGL_image_external : require
            precision mediump float;
            varying vec2 vTexCoord;
            uniform samplerExternalOES uTexture;
            uniform float uIntensity;
            void main() {
                vec4 color = texture2D(uTexture, vTexCoord);
                vec2 center = vTexCoord - 0.5;
                float dist = length(center);
                float vignette = smoothstep(0.7, 0.3, dist);
                gl_FragColor = vec4(color.rgb * mix(1.0, vignette, uIntensity), color.a);
            }
        """.trimIndent()

        // Beauty filter (blur + brighten)
        private val fragmentShaderBeauty = """
            #extension GL_OES_EGL_image_external : require
            precision mediump float;
            varying vec2 vTexCoord;
            uniform samplerExternalOES uTexture;
            uniform vec2 uTexelSize;
            uniform float uIntensity;
            void main() {
                vec4 color = texture2D(uTexture, vTexCoord);
                // Simple box blur
                vec4 blurred = vec4(0.0);
                for (int x = -2; x <= 2; x++) {
                    for (int y = -2; y <= 2; y++) {
                        vec2 offset = vec2(float(x), float(y)) * uTexelSize * 2.0;
                        blurred += texture2D(uTexture, vTexCoord + offset);
                    }
                }
                blurred /= 25.0;
                // Blend: brighten + smooth
                vec3 brightened = color.rgb * 1.1;
                vec3 smoothed = mix(brightened, blurred.rgb, 0.4 * uIntensity);
                gl_FragColor = vec4(smoothed, color.a);
            }
        """.trimIndent()

        // Glow filter
        private val fragmentShaderGlow = """
            #extension GL_OES_EGL_image_external : require
            precision mediump float;
            varying vec2 vTexCoord;
            uniform samplerExternalOES uTexture;
            uniform vec2 uTexelSize;
            uniform float uIntensity;
            void main() {
                vec4 color = texture2D(uTexture, vTexCoord);
                // Blur for glow
                vec4 blurred = vec4(0.0);
                for (int x = -2; x <= 2; x++) {
                    for (int y = -2; y <= 2; y++) {
                        vec2 offset = vec2(float(x), float(y)) * uTexelSize * 3.0;
                        blurred += texture2D(uTexture, vTexCoord + offset);
                    }
                }
                blurred /= 25.0;
                // Screen blend
                vec3 glow = 1.0 - (1.0 - color.rgb) * (1.0 - blurred.rgb * uIntensity);
                gl_FragColor = vec4(glow, color.a);
            }
        """.trimIndent()

        private val vertices = floatArrayOf(
            -1f, -1f,  // bottom left
             1f, -1f,  // bottom right
            -1f,  1f,  // top left
             1f,  1f   // top right
        )

        private val texCoords = floatArrayOf(
            0f, 1f,  // bottom left (flipped for OES)
            1f, 1f,  // bottom right
            0f, 0f,  // top left
            1f, 0f   // top right
        )

        private val projectionMatrix = floatArrayOf(
            1f, 0f, 0f, 0f,
            0f, 1f, 0f, 0f,
            0f, 0f, 1f, 0f,
            0f, 0f, 0f, 1f
        )

        override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
            GLES20.glClearColor(0f, 0f, 0f, 1f)

            // Create OES texture
            val textures = IntArray(1)
            GLES20.glGenTextures(1, textures, 0)
            oesTextureId = textures[0]
            GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, oesTextureId)
            GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
            GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
            GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
            GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)

            // Create SurfaceTexture
            surfaceTexture = SurfaceTexture(oesTextureId)
            surfaceTexture?.setOnFrameAvailableListener {
                frameAvailable = true
                requestRender()
                onFrameAvailable?.invoke(it)
            }

            // Init buffers
            vertexBuffer = ByteBuffer.allocateDirect(vertices.size * 4)
                .order(ByteOrder.nativeOrder()).asFloatBuffer().put(vertices).apply { position(0) }
            texCoordBuffer = ByteBuffer.allocateDirect(texCoords.size * 4)
                .order(ByteOrder.nativeOrder()).asFloatBuffer().put(texCoords).apply { position(0) }

            // Compile default shader
            shaderProgram = createProgram(vertexShaderCode, fragmentShaderPassthrough)
        }

        override fun onSurfaceChanged(gl: GL10?, width: Int, height: Int) {
            GLES20.glViewport(0, 0, width, height)
        }

        override fun onDrawFrame(gl: GL10?) {
            if (frameAvailable) {
                surfaceTexture?.updateTexImage()
                frameAvailable = false
            }

            GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)

            // Select shader based on filter
            val fragmentShader = when (activeFilter) {
                FilterType.NONE -> fragmentShaderPassthrough
                FilterType.BEAUTY -> fragmentShaderBeauty
                FilterType.GLOW -> fragmentShaderGlow
                FilterType.SEPIA -> fragmentShaderSepia
                FilterType.GRAYSCALE -> fragmentShaderGrayscale
                FilterType.NEGATIVE -> fragmentShaderNegative
                FilterType.BRIGHTNESS -> fragmentShaderBrightness
                FilterType.CONTRAST -> fragmentShaderContrast
                FilterType.VIGNETTE -> fragmentShaderVignette
                else -> fragmentShaderPassthrough
            }

            // Recompile shader if filter changed
            val newProgram = createProgram(vertexShaderCode, fragmentShader)
            if (newProgram != shaderProgram) {
                GLES20.glDeleteProgram(shaderProgram)
                shaderProgram = newProgram
            }

            // Use shader
            GLES20.glUseProgram(shaderProgram)

            // Set uniforms
            val textureLoc = GLES20.glGetUniformLocation(shaderProgram, "uTexture")
            GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
            GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, oesTextureId)
            GLES20.glUniform1i(textureLoc, 0)

            val intensityLoc = GLES20.glGetUniformLocation(shaderProgram, "uIntensity")
            if (intensityLoc >= 0) {
                GLES20.glUniform1f(intensityLoc, intensity)
            }

            // Set texel size for blur-based filters
            if (activeFilter == FilterType.BEAUTY || activeFilter == FilterType.GLOW) {
                val texelSizeLoc = GLES20.glGetUniformLocation(shaderProgram, "uTexelSize")
                if (texelSizeLoc >= 0) {
                    GLES20.glUniform2f(texelSizeLoc, 1f / width, 1f / height)
                }
            }

            // Set matrix
            val matrixLoc = GLES20.glGetUniformLocation(shaderProgram, "uMatrix")
            if (matrixLoc >= 0) {
                GLES20.glUniformMatrix4fv(matrixLoc, 1, false, projectionMatrix, 0)
            }

            // Bind vertex data
            val positionLoc = GLES20.glGetAttribLocation(shaderProgram, "aPosition")
            GLES20.glEnableVertexAttribArray(positionLoc)
            GLES20.glVertexAttribPointer(positionLoc, 2, GLES20.GL_FLOAT, false, 0, vertexBuffer)

            val texCoordLoc = GLES20.glGetAttribLocation(shaderProgram, "aTexCoord")
            GLES20.glEnableVertexAttribArray(texCoordLoc)
            GLES20.glVertexAttribPointer(texCoordLoc, 2, GLES20.GL_FLOAT, false, 0, texCoordBuffer)

            // Draw
            GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)

            // Cleanup
            GLES20.glDisableVertexAttribArray(positionLoc)
            GLES20.glDisableVertexAttribArray(texCoordLoc)
        }

        private fun createProgram(vertexSource: String, fragmentSource: String): Int {
            val vertexShader = loadShader(GLES20.GL_VERTEX_SHADER, vertexSource)
            val fragmentShader = loadShader(GLES20.GL_FRAGMENT_SHADER, fragmentSource)

            val program = GLES20.glCreateProgram()
            GLES20.glAttachShader(program, vertexShader)
            GLES20.glAttachShader(program, fragmentShader)
            GLES20.glLinkProgram(program)

            val linkStatus = IntArray(1)
            GLES20.glGetProgramiv(program, GLES20.GL_LINK_STATUS, linkStatus, 0)
            if (linkStatus[0] == 0) {
                val log = GLES20.glGetProgramInfoLog(program)
                GLES20.glDeleteProgram(program)
                throw RuntimeException("Program link failed: $log")
            }

            GLES20.glDeleteShader(vertexShader)
            GLES20.glDeleteShader(fragmentShader)

            return program
        }

        private fun loadShader(type: Int, source: String): Int {
            val shader = GLES20.glCreateShader(type)
            GLES20.glShaderSource(shader, source)
            GLES20.glCompileShader(shader)

            val compiled = IntArray(1)
            GLES20.glGetShaderiv(shader, GLES20.GL_COMPILE_STATUS, compiled, 0)
            if (compiled[0] == 0) {
                val log = GLES20.glGetShaderInfoLog(shader)
                GLES20.glDeleteShader(shader)
                throw RuntimeException("Shader compile failed: $log")
            }

            return shader
        }
    }
}
