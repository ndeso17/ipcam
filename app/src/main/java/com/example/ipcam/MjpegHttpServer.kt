package com.example.ipcam

import android.content.Context
import com.example.ipcam.MicManager
import fi.iki.elonen.NanoHTTPD
import fi.iki.elonen.NanoHTTPD.IHTTPSession
import fi.iki.elonen.NanoHTTPD.Response
import java.io.InputStream
import java.util.concurrent.Executors
import org.json.JSONObject

/**
 * MjpegHttpServer — server ringan berbasis NanoHTTPD
 * Menyajikan frame JPEG terbaru dalam format MJPEG stream
 */
class MjpegHttpServer(
    private val context: Context,
    port: Int = 8085
) : NanoHTTPD(port) {

    private val executor = Executors.newCachedThreadPool()
    private val activeStreams = mutableListOf<java.io.PipedOutputStream>()

    companion object {
        @Volatile var currentFps: Int = 20
        @Volatile var currentQuality: Int = 80
    }

    override fun serve(session: IHTTPSession?): Response {
        val uri = session?.uri ?: "/"
        return when (uri) {
            "/" -> newFixedLengthResponse(
                Response.Status.OK,
                "text/html",
                """
                <html>
                  <head><title>Android IP Cam</title></head>
                  <body style="margin:0;background:#000;">
                    <h3 style="color:white;text-align:center;">Android IP Cam</h3>
                    <img src='/video' width='100%'/>
                  </body>
                </html>
                """.trimIndent()
            )

            "/video" -> {
                val boundary = "frame"
                newChunkedResponse(
                    Response.Status.OK,
                    "multipart/x-mixed-replace; boundary=$boundary",
                    mjpegInputStream(boundary)
                )
            }

            "/audio" -> {
                newChunkedResponse(
                    Response.Status.OK,
                    "audio/aac",
                    audioInputStream()
                )
            }

            "/status" -> {
                val json = JSONObject().apply {
                    put("streaming", FrameBuffer.latestFrame != null)
                    put("mic", MicManager.isActive)
                    put("fps", currentFps)
                    put("quality", currentQuality)
                    put("clients", synchronized(activeStreams) { activeStreams.size })
                }
                newFixedLengthResponse(Response.Status.OK, "application/json", json.toString())
            }

            "/mic/toggle" -> {
                if (session?.method != Method.POST) {
                    newFixedLengthResponse(Response.Status.METHOD_NOT_ALLOWED, "text/plain", "POST required")
                } else {
                    MicManager.toggle()
                    val json = JSONObject().apply {
                        put("mic", MicManager.isActive)
                    }
                    newFixedLengthResponse(Response.Status.OK, "application/json", json.toString())
                }
            }

            else -> newFixedLengthResponse(Response.Status.NOT_FOUND, "text/plain", "404 Not Found")
        }
    }

    private fun mjpegInputStream(boundary: String): InputStream {
        val pipeIn = java.io.PipedInputStream(65536)
        val pipeOut = java.io.PipedOutputStream(pipeIn)

        synchronized(activeStreams) { activeStreams.add(pipeOut) }

        executor.execute {
            try {
                while (!Thread.currentThread().isInterrupted) {
                    val frame = FrameBuffer.latestFrame
                    if (frame != null) {
                        val header = ("--$boundary\r\n" +
                                "Content-Type: image/jpeg\r\n" +
                                "Content-Length: ${frame.size}\r\n\r\n").toByteArray()
                        pipeOut.write(header)
                        pipeOut.write(frame)
                        pipeOut.write("\r\n".toByteArray())
                        pipeOut.flush()
                    }
                    Thread.sleep(50) // ~20 fps
                }
            } catch (_: Exception) {
                // Client disconnect or stream closed
            } finally {
                synchronized(activeStreams) { activeStreams.remove(pipeOut) }
                try { pipeOut.close() } catch (_: Exception) {}
            }
        }

        return pipeIn
    }

    private fun audioInputStream(): InputStream {
        val pipeIn = java.io.PipedInputStream(65536)
        val pipeOut = java.io.PipedOutputStream(pipeIn)

        synchronized(activeStreams) { activeStreams.add(pipeOut) }

        executor.execute {
            try {
                while (!Thread.currentThread().isInterrupted) {
                    val packet = MicManager.audioQueue.poll()
                    if (packet != null) {
                        pipeOut.write(packet)
                        pipeOut.flush()
                    } else {
                        Thread.sleep(20)
                    }
                }
            } catch (_: Exception) {
            } finally {
                synchronized(activeStreams) { activeStreams.remove(pipeOut) }
                try { pipeOut.close() } catch (_: Exception) {}
            }
        }

        return pipeIn
    }

    /**
     * Clean shutdown: close all active streams, then shutdown executor.
     * Closing pipes unblocks any writes blocked on full buffers.
     */
    fun destroy() {
        val streams: List<java.io.PipedOutputStream>
        synchronized(activeStreams) {
            streams = activeStreams.toList()
            activeStreams.clear()
        }
        streams.forEach {
            try { it.close() } catch (_: Exception) {}
        }
        executor.shutdownNow()
    }
}
