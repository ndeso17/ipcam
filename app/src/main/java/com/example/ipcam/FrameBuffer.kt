package com.example.ipcam

object FrameBuffer {
    @Volatile
    var latestFrame: ByteArray? = null
}