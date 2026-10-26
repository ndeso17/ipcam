package com.example.ipcam

import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import java.nio.ByteBuffer

class StreamEncoder(
    private val width: Int = DEFAULT_WIDTH,
    private val height: Int = DEFAULT_HEIGHT,
    private val bitrate: Int = DEFAULT_BITRATE,
    private val frameRate: Int = DEFAULT_FRAME_RATE,
    private val iFrameInterval: Int = DEFAULT_I_FRAME_INTERVAL
) {
    companion object {
        private const val TAG = "StreamEncoder"
        private const val MIME_TYPE = MediaFormat.MIMETYPE_VIDEO_AVC
        private const val DEFAULT_WIDTH = 1280
        private const val DEFAULT_HEIGHT = 720
        private const val DEFAULT_BITRATE = 3_000_000
        private const val DEFAULT_FRAME_RATE = 30
        private const val DEFAULT_I_FRAME_INTERVAL = 1
    }

    interface NalCallback {
        fun onNalUnit(data: ByteBuffer, info: MediaCodec.BufferInfo)
    }

    @Volatile
    private var callbackRef: NalCallback? = null

    fun setNalCallback(cb: NalCallback?) {
        callbackRef = cb
    }

    @Volatile
    var sps: ByteArray? = null
        private set

    @Volatile
    var pps: ByteArray? = null
        private set

    private var codec: MediaCodec? = null
    private var callbackThread: HandlerThread? = null
    private var callbackHandler: Handler? = null

    @Volatile
    private var isStarted = false

    fun start() {
        if (isStarted) {
            Log.w(TAG, "Already started")
            return
        }

        try {
            callbackThread = HandlerThread("StreamEncoder-callback").apply { start() }
            callbackHandler = Handler(callbackThread!!.looper)

            val format = MediaFormat.createVideoFormat(MIME_TYPE, width, height).apply {
                setInteger(
                    MediaFormat.KEY_COLOR_FORMAT,
                    MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible
                )
                setInteger(MediaFormat.KEY_BIT_RATE, bitrate)
                setInteger(MediaFormat.KEY_FRAME_RATE, frameRate)
                setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, iFrameInterval)
                setInteger(
                    MediaFormat.KEY_PROFILE,
                    MediaCodecInfo.CodecProfileLevel.AVCProfileBaseline
                )
            }

            codec = MediaCodec.createEncoderByType(MIME_TYPE).apply {
                configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
                start()
            }

            isStarted = true
            Log.i(TAG, "Encoder started: ${width}x${height} ${bitrate / 1000}kbps ${frameRate}fps")

            drainOutput()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start encoder", e)
            release()
            throw e
        }
    }

    fun encode(yuvData: ByteArray, presentationTimeUs: Long) {
        val codec = this.codec ?: return
        if (!isStarted) return

        try {
            val inputIndex = codec.dequeueInputBuffer(10_000)
            if (inputIndex < 0) return

            val inputBuffer = codec.getInputBuffer(inputIndex) ?: return
            inputBuffer.clear()
            inputBuffer.put(yuvData)

            codec.queueInputBuffer(
                inputIndex,
                0,
                yuvData.size,
                presentationTimeUs,
                0
            )
        } catch (e: Exception) {
            Log.e(TAG, "Error feeding encoder", e)
        }
    }

    fun stop() {
        if (!isStarted) return

        isStarted = false

        try {
            codec?.signalEndOfInputStream()
        } catch (_: Exception) {}

        try {
            callbackThread?.quitSafely()
            callbackThread?.join(2000)
        } catch (e: Exception) {
            Log.w(TAG, "Callback thread join failed", e)
        }

        try {
            codec?.stop()
        } catch (e: Exception) {
            Log.w(TAG, "Codec stop failed", e)
        }

        try {
            codec?.release()
        } catch (e: Exception) {
            Log.w(TAG, "Codec release failed", e)
        }

        codec = null
        callbackThread = null
        callbackHandler = null

        Log.i(TAG, "Encoder stopped")
    }

    fun release() {
        stop()
    }

    fun requestKeyFrame() {
        try {
            codec?.setParameters(
                android.os.Bundle().apply {
                    putInt(MediaCodec.PARAMETER_KEY_REQUEST_SYNC_FRAME, 0)
                }
            )
        } catch (e: Exception) {
            Log.w(TAG, "requestKeyFrame failed", e)
        }
    }

    private fun drainOutput() {
        val codec = this.codec ?: return
        val handler = this.callbackHandler ?: return

        try {
            codec.setCallback(object : MediaCodec.Callback() {
                override fun onInputBufferAvailable(codec: MediaCodec, index: Int) {}

                override fun onOutputFormatChanged(codec: MediaCodec, format: MediaFormat) {
                    Log.i(TAG, "Output format changed: $format")
                    try {
                        val csd0 = format.getByteBuffer("csd-0")
                        val csd1 = format.getByteBuffer("csd-1")
                        sps = extractNalUnit(csd0)
                        pps = extractNalUnit(csd1)
                        Log.d(TAG, "SPS (${sps?.size}B) PPS (${pps?.size}B) extracted")
                    } catch (e: Exception) {
                        Log.w(TAG, "Failed to extract SPS/PPS", e)
                    }
                }

                override fun onOutputBufferAvailable(
                    codec: MediaCodec,
                    index: Int,
                    info: MediaCodec.BufferInfo
                ) {
                    try {
                        val buffer = codec.getOutputBuffer(index) ?: run {
                            codec.releaseOutputBuffer(index, false)
                            return
                        }

                        if (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0) {
                            codec.releaseOutputBuffer(index, false)
                            return
                        }

                        callbackRef?.onNalUnit(buffer, info)

                        codec.releaseOutputBuffer(index, false)
                    } catch (e: Exception) {
                        Log.e(TAG, "Error processing output buffer", e)
                    }
                }

                override fun onError(codec: MediaCodec, e: MediaCodec.CodecException) {
                    Log.e(TAG, "MediaCodec error", e)
                }
            }, handler)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to set codec callback", e)
        }
    }

    private fun extractNalUnit(buf: ByteBuffer?): ByteArray? {
        if (buf == null) return null
        val bytes = ByteArray(buf.remaining())
        buf.duplicate().get(bytes)
        buf.rewind()
        val offset = findStartCode(bytes)
        if (offset < 0) return bytes
        return bytes.copyOfRange(offset, bytes.size)
    }

    private fun findStartCode(data: ByteArray): Int {
        for (i in 0..data.size - 4) {
            if (data[i] == 0.toByte() && data[i + 1] == 0.toByte() &&
                data[i + 2] == 0.toByte() && data[i + 3] == 1.toByte()
            ) {
                return i + 4
            }
        }
        for (i in 0..data.size - 3) {
            if (data[i] == 0.toByte() && data[i + 1] == 0.toByte() &&
                data[i + 2] == 1.toByte()
            ) {
                return i + 3
            }
        }
        return -1
    }
}