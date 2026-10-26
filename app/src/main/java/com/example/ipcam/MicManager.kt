package com.example.ipcam

import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaRecorder
import android.util.Log
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicBoolean

object MicManager {
    private const val TAG = "IPCam.MicManager"
    private const val SAMPLE_RATE = 16000
    private const val CHANNEL_CONFIG = AudioFormat.CHANNEL_IN_MONO
    private const val AUDIO_FORMAT = AudioFormat.ENCODING_PCM_16BIT
    private const val BIT_RATE = 64000
    private const val ADTS_HEADER_SIZE = 7

    enum class MicState { ON, OFF }

    @Volatile
    var isActive: Boolean = false
        private set

    @Volatile
    var amplitude: Float = 0f
        private set

    val audioQueue = ConcurrentLinkedQueue<ByteArray>()

    @Volatile
    private var audioRecord: AudioRecord? = null
    @Volatile
    private var mediaCodec: MediaCodec? = null
    @Volatile
    private var captureThread: Thread? = null
    private val stopRequested = AtomicBoolean(false)

    fun start() {
        if (isActive) {
            Log.w(TAG, "Mic already active")
            return
        }

        try {
            val bufferSize = AudioRecord.getMinBufferSize(SAMPLE_RATE, CHANNEL_CONFIG, AUDIO_FORMAT)
            if (bufferSize == AudioRecord.ERROR_BAD_VALUE || bufferSize == AudioRecord.ERROR) {
                Log.e(TAG, "Invalid buffer size: $bufferSize")
                return
            }

            audioRecord = AudioRecord(
                MediaRecorder.AudioSource.MIC,
                SAMPLE_RATE,
                CHANNEL_CONFIG,
                AUDIO_FORMAT,
                bufferSize
            )

            if (audioRecord?.state != AudioRecord.STATE_INITIALIZED) {
                Log.e(TAG, "AudioRecord failed to initialize")
                release()
                return
            }

            mediaCodec = try {
                MediaCodec.createByCodecName("OMX.google.aac.encoder")
            } catch (e: Exception) {
                Log.w(TAG, "OMX.google.aac.encoder not available, trying MIME type", e)
                MediaCodec.createEncoderByType("audio/mp4a-latm")
            }

            val format = MediaFormat.createAudioFormat(
                MediaFormat.MIMETYPE_AUDIO_AAC,
                SAMPLE_RATE,
                1
            ).apply {
                setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
                setInteger(MediaFormat.KEY_BIT_RATE, BIT_RATE)
                setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, bufferSize)
            }

            mediaCodec?.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            mediaCodec?.start()

            audioRecord?.startRecording()
            isActive = true
            stopRequested.set(false)

            captureThread = Thread({
                captureLoop()
            }, "MicCapture").also { it.start() }

            Log.i(TAG, "Mic started: ${SAMPLE_RATE}Hz, mono, AAC-LC ${BIT_RATE}bps")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start mic", e)
            release()
        }
    }

    fun stop() {
        if (!isActive) {
            Log.w(TAG, "Mic not active")
            return
        }

        stopRequested.set(true)
        isActive = false

        try {
            captureThread?.join(2000)
            if (captureThread?.isAlive == true) {
                Log.w(TAG, "Capture thread did not stop in time")
                captureThread?.interrupt()
            }
        } catch (e: InterruptedException) {
            Log.w(TAG, "Interrupted waiting for capture thread", e)
        }

        release()
        audioQueue.clear()
        Log.i(TAG, "Mic stopped")
    }

    fun toggle(): MicState {
        if (isActive) {
            stop()
            return MicState.OFF
        } else {
            start()
            return if (isActive) MicState.ON else MicState.OFF
        }
    }

    private fun captureLoop() {
        val codec = mediaCodec ?: return
        val record = audioRecord ?: return
        val bufferInfo = MediaCodec.BufferInfo()
        val pcmBuffer = ByteArray(1024)

        try {
            while (!stopRequested.get()) {
                val bytesRead = record.read(pcmBuffer, 0, pcmBuffer.size)
                if (bytesRead > 0) {
                    amplitude = calculateAmplitude(pcmBuffer, bytesRead)
                    feedToCodec(codec, pcmBuffer, bytesRead)
                    drainCodec(codec, bufferInfo)
                } else if (bytesRead < 0) {
                    Log.e(TAG, "AudioRecord read error: $bytesRead")
                    break
                }
            }

            // Flush remaining data
            codec.flush()
            drainCodec(codec, bufferInfo)
        } catch (e: Exception) {
            if (!stopRequested.get()) {
                Log.e(TAG, "Capture loop error", e)
            }
        }
    }

    private fun calculateAmplitude(buffer: ByteArray, size: Int): Float {
        var sum = 0L
        val samples = size / 2
        for (i in 0 until samples) {
            val lo = buffer[i * 2].toInt() and 0xFF
            val hi = buffer[i * 2 + 1].toInt()
            val sample = (hi shl 8) or lo
            sum += (sample * sample).toLong()
        }
        val rms = kotlin.math.sqrt(sum.toDouble() / samples)
        return (rms / 32768.0).toFloat().coerceIn(0f, 1f)
    }

    private fun feedToCodec(codec: MediaCodec, data: ByteArray, size: Int) {
        val inputIndex = codec.dequeueInputBuffer(10_000)
        if (inputIndex >= 0) {
            val inputBuffer = codec.getInputBuffer(inputIndex) ?: return
            inputBuffer.clear()
            inputBuffer.put(data, 0, size)
            codec.queueInputBuffer(inputIndex, 0, size, 0, 0)
        }
    }

    private fun drainCodec(codec: MediaCodec, bufferInfo: MediaCodec.BufferInfo) {
        while (true) {
            val outputIndex = codec.dequeueOutputBuffer(bufferInfo, 0)
            if (outputIndex >= 0) {
                try {
                    if (bufferInfo.size > 0 &&
                        (bufferInfo.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG) == 0
                    ) {
                        val outputBuffer = codec.getOutputBuffer(outputIndex)
                        if (outputBuffer != null) {
                            val frameLen = ADTS_HEADER_SIZE + bufferInfo.size
                            val adtsPacket = ByteArray(frameLen)
                            writeAdtsHeader(adtsPacket, frameLen)
                            outputBuffer.position(bufferInfo.offset)
                            outputBuffer.get(adtsPacket, ADTS_HEADER_SIZE, bufferInfo.size)
                            audioQueue.offer(adtsPacket)
                            while (audioQueue.size > 200) {
                                audioQueue.poll()
                            }
                        }
                    }
                } finally {
                    codec.releaseOutputBuffer(outputIndex, false)
                }
            } else if (outputIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                Log.d(TAG, "Output format changed: ${codec.outputFormat}")
            } else {
                break
            }
        }
    }

    private fun writeAdtsHeader(packet: ByteArray, frameLength: Int) {
        val profile = 1
        val freqIdx = 8
        val chanCfg = 1
        packet[0] = 0xFF.toByte()
        packet[1] = 0xF1.toByte()
        packet[2] = (((profile and 0x03) shl 6) or ((freqIdx and 0x0F) shl 2) or ((chanCfg shr 2) and 0x01)).toByte()
        packet[3] = (((chanCfg and 0x03) shl 6) or ((frameLength shr 11) and 0x03)).toByte()
        packet[4] = ((frameLength shr 3) and 0xFF).toByte()
        packet[5] = (((frameLength and 0x07) shl 5) or 0x1F).toByte()
        packet[6] = 0xFC.toByte()
    }

    private fun release() {
        try {
            mediaCodec?.stop()
        } catch (e: Exception) {
            Log.w(TAG, "MediaCodec stop error", e)
        }
        try {
            mediaCodec?.release()
        } catch (e: Exception) {
            Log.w(TAG, "MediaCodec release error", e)
        }
        try {
            audioRecord?.stop()
        } catch (e: Exception) {
            Log.w(TAG, "AudioRecord stop error", e)
        }
        try {
            audioRecord?.release()
        } catch (e: Exception) {
            Log.w(TAG, "AudioRecord release error", e)
        }

        mediaCodec = null
        audioRecord = null
        captureThread = null
    }
}
