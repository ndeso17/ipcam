package com.example.ipcam

import android.content.Context
import android.util.Log

/**
 * Singleton wrapper untuk MjpegHttpServer.
 * Tujuan: agar server tidak mati saat orientasi berubah (Activity restart).
 */
object MJPEGServerInstance {

    @Volatile
    private var server: MjpegHttpServer? = null

    private const val TAG = "MJPEGServerInstance"

    /**
     * Start server jika belum berjalan.
     * Jika sudah berjalan, abaikan.
     */
    @Synchronized
    fun start(context: Context, port: Int = 8085) {
        if (server != null) {
            Log.i(TAG, "Server sudah berjalan di port ${server?.listeningPort}")
            return
        }

        try {
            // Gunakan ApplicationContext agar tidak leak Activity
            val appContext = context.applicationContext
            server = MjpegHttpServer(appContext, port)
            server?.start()
            Log.i(TAG, "Server MJPEG berjalan di port $port")
        } catch (e: Exception) {
            Log.e(TAG, "Gagal memulai server: ${e.message}", e)
        }
    }

    /**
     * Stop server (misalnya saat aplikasi ditutup total)
     */
    @Synchronized
    fun stop() {
        try {
            server?.destroy()
            server?.stop()
            Log.i(TAG, "Server MJPEG dihentikan")
        } catch (e: Exception) {
            Log.e(TAG, "Gagal menghentikan server: ${e.message}", e)
        } finally {
            server = null
        }
    }

    /**
     * Mengecek apakah server sedang berjalan.
     */
    fun isRunning(): Boolean {
        return server != null
    }

    /**
     * Mendapatkan port aktif saat ini (jika ada).
     */
    fun getPort(): Int? {
        return server?.listeningPort
    }
}
