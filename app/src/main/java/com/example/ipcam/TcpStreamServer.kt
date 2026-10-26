package com.example.ipcam

import android.media.MediaCodec
import android.util.Log
import java.io.OutputStream
import java.net.ServerSocket
import java.net.Socket
import java.nio.ByteBuffer
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Raw TCP server that streams H.264 NAL units to a single client.
 *
 * Binds to port 8086, accepts one client at a time, and forwards
 * NAL units received from [StreamEncoder.NalCallback] as raw bytes
 * with Annex-B start code prefixes.
 */
class TcpStreamServer(private val encoder: StreamEncoder) {

    companion object {
        private const val TAG = "TcpStreamServer"
        private const val PORT = 8086

        /** Annex-B 4-byte start code: 00 00 00 01 */
        private val START_CODE = byteArrayOf(0x00, 0x00, 0x00, 0x01)
    }

    private val running = AtomicBoolean(false)
    private var serverSocket: ServerSocket? = null
    private var acceptExecutor: ExecutorService? = null
    private var clientExecutor: ExecutorService? = null

    @Volatile
    private var clientSocket: Socket? = null

    @Volatile
    private var clientOutputStream: OutputStream? = null

    /** Callback registered with the encoder to receive NAL units. */
    private val nalCallback = object : StreamEncoder.NalCallback {
        override fun onNalUnit(data: ByteBuffer, info: MediaCodec.BufferInfo) {
            val output = clientOutputStream ?: return
            try {
                val nalPayload = ByteArray(info.size)
                data.position(info.offset)
                data.get(nalPayload, 0, info.size)

                synchronized(output) {
                    output.write(START_CODE)
                    output.write(nalPayload)
                    output.flush()
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error writing NAL unit to client", e)
                disconnectClient()
            }
        }
    }

    /**
     * Start the TCP server. Binds to [PORT] and begins accepting
     * client connections on a background thread.
     *
     * Safe to call multiple times — no-op if already running.
     */
    fun start() {
        if (running.compareAndSet(false, true)) {
            acceptExecutor = Executors.newSingleThreadExecutor { r ->
                Thread(r, "TcpStreamServer-Accept").apply { isDaemon = true }
            }
            clientExecutor = Executors.newSingleThreadExecutor { r ->
                Thread(r, "TcpStreamServer-Stream").apply { isDaemon = true }
            }
            acceptExecutor?.execute { acceptLoop() }
            Log.i(TAG, "Server started on port $PORT")
        }
    }

    /**
     * Stop the server, disconnect any active client, and release resources.
     *
     * Safe to call multiple times — no-op if already stopped.
     */
    fun stop() {
        if (running.compareAndSet(true, false)) {
            encoder.setNalCallback(null)
            disconnectClient()
            try {
                serverSocket?.close()
            } catch (e: Exception) {
                Log.e(TAG, "Error closing server socket", e)
            }
            serverSocket = null
            acceptExecutor?.shutdownNow()
            clientExecutor?.shutdownNow()
            acceptExecutor = null
            clientExecutor = null
            Log.i(TAG, "Server stopped")
        }
    }

    /** Returns `true` if the server is currently running. */
    fun isRunning(): Boolean = running.get()

    // ── Internal ──────────────────────────────────────────────

    /**
     * Main accept loop running on a dedicated thread.
     * Blocks on [ServerSocket.accept] until stopped.
     */
    private fun acceptLoop() {
        try {
            serverSocket = ServerSocket(PORT).apply {
                reuseAddress = true
            }
            Log.i(TAG, "Listening on port $PORT")

            while (running.get()) {
                try {
                    val socket = serverSocket!!.accept()
                    // Only one client at a time — close extra connections
                    if (clientSocket != null) {
                        Log.w(TAG, "Rejecting additional client: ${socket.inetAddress}")
                        try {
                            socket.close()
                        } catch (_: Exception) {}
                        continue
                    }
                    handleNewClient(socket)
                } catch (e: Exception) {
                    if (running.get()) {
                        Log.e(TAG, "Error accepting connection", e)
                    }
                }
            }
        } catch (e: Exception) {
            if (running.get()) {
                Log.e(TAG, "Fatal error in accept loop", e)
            }
        }
    }

    /**
     * Set up a new client connection: store the socket, register the
     * NAL callback, and send cached SPS/PPS with start code prefixes.
     */
    private fun handleNewClient(socket: Socket) {
        try {
            socket.apply {
                tcpNoDelay = true
            }
            clientSocket = socket
            val output = socket.getOutputStream()
            clientOutputStream = output

            Log.i(TAG, "Client connected: ${socket.inetAddress}")

            // Register to receive NAL units from the encoder
            encoder.setNalCallback(nalCallback)

            // Send SPS + PPS on a background thread so we don't block accept
            clientExecutor?.execute {
                try {
                    sendSpsPps(output)
                    Log.i(TAG, "SPS/PPS sent to client")
                } catch (e: Exception) {
                    Log.e(TAG, "Error sending SPS/PPS", e)
                    disconnectClient()
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error setting up client", e)
            disconnectClient()
        }
    }

    /**
     * Send cached SPS and PPS NAL units with Annex-B start code prefixes.
     * Blocks until both are written or an error occurs.
     */
    @Throws(Exception::class)
    private fun sendSpsPps(output: OutputStream) {
        synchronized(output) {
            encoder.sps?.let { sps ->
                output.write(START_CODE)
                output.write(sps)
                Log.d(TAG, "Sent SPS (${sps.size} bytes)")
            }
            encoder.pps?.let { pps ->
                output.write(START_CODE)
                output.write(pps)
                Log.d(TAG, "Sent PPS (${pps.size} bytes)")
            }
            output.flush()
        }
    }

    /**
     * Disconnect the current client, clean up socket and output stream.
     * Ready for a new connection afterward.
     */
    private fun disconnectClient() {
        try {
            clientOutputStream?.close()
        } catch (_: Exception) {}
        clientOutputStream = null

        try {
            clientSocket?.close()
        } catch (_: Exception) {}
        clientSocket = null

        Log.i(TAG, "Client disconnected")
    }
}
