package com.example.ipcam

import android.Manifest
import android.content.pm.PackageManager
import android.content.pm.ActivityInfo
import android.content.res.Configuration
import android.graphics.*
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Bundle
import android.os.SystemClock
import android.util.Log
import android.view.Gravity
import android.view.View
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.*
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.coordinatorlayout.widget.CoordinatorLayout
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.example.ipcam.ai.AiProcessor
import com.example.ipcam.gpuimage.FilterType
import com.example.ipcam.pipeline.FilterPipeline
import com.google.android.material.bottomsheet.BottomSheetBehavior
import java.io.ByteArrayOutputStream
import java.net.NetworkInterface
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

class MainActivity : AppCompatActivity() {

    private lateinit var previewView: PreviewView
    private lateinit var tvFpsInfo: TextView
    private lateinit var tvQualityInfo: TextView
    private lateinit var tvIpInfo: TextView
    private lateinit var tvFpsValue: TextView
    private lateinit var tvQualityValue: TextView
    private lateinit var btnRecordBg: View
    private lateinit var btnRecordInner: View
    private lateinit var equalizerView: EqualizerView
    private lateinit var btnIncreaseFps: Button
    private lateinit var btnDecreaseFps: Button
    private lateinit var btnIncreaseQuality: Button
    private lateinit var btnDecreaseQuality: Button
    private lateinit var btnMinimize: ImageButton
    private lateinit var btnSwitchCamera: ImageButton
    private lateinit var btnMicToggle: ImageButton
    private lateinit var btnOrientationLock: ImageButton
    private lateinit var tvUsbStatus: TextView
    private lateinit var controlPanel: LinearLayout
    private lateinit var sheetBehavior: BottomSheetBehavior<LinearLayout>
    private lateinit var coordinatorLayout: CoordinatorLayout

    // Filter menu
    private lateinit var filterMenuContainer: FrameLayout
    private lateinit var btnFilterToggle: ImageButton
    private lateinit var filterChipsContainer: LinearLayout
    private var isFilterMenuExpanded = false
    private var selectedFilterChip: TextView? = null

    private var isStreaming = false
    private var currentFps = 20
    private var currentQuality = 80
    private var lastFrameTimestamp = 0L
    private var useFrontCamera = false
    private var isOrientationLocked = false

    private lateinit var cameraExecutor: ExecutorService
    private var cameraProvider: ProcessCameraProvider? = null
    private var camera: androidx.camera.core.Camera? = null

    // H.264 streaming
    private var streamEncoder: StreamEncoder? = null
    private var tcpStreamServer: TcpStreamServer? = null

    companion object {
        private const val TAG = "IPCam.MainActivity"
        private const val REQUEST_CODE_PERMISSIONS = 10
        private val REQUIRED_PERMISSIONS = arrayOf(Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        initViews()
        setupBottomSheet()
        setupButtonListeners()
        setupFilterMenu()
        cameraExecutor = Executors.newSingleThreadExecutor()

        FilterPipeline.init(this)

        if (allPermissionsGranted()) {
            updateUI()
        } else {
            ActivityCompat.requestPermissions(this, REQUIRED_PERMISSIONS, REQUEST_CODE_PERMISSIONS)
        }
    }

    private fun initViews() {
        previewView = findViewById(R.id.previewView)
        tvFpsInfo = findViewById(R.id.tvFpsInfo)
        tvQualityInfo = findViewById(R.id.tvQualityInfo)
        tvIpInfo = findViewById(R.id.tvIpInfo)
        tvFpsValue = findViewById(R.id.tvFpsValue)
        tvQualityValue = findViewById(R.id.tvQualityValue)
        btnRecordBg = findViewById(R.id.btnRecordBg)
        btnRecordInner = findViewById(R.id.btnRecordInner)
        equalizerView = findViewById(R.id.equalizerView)
        btnIncreaseFps = findViewById(R.id.btnIncreaseFps)
        btnDecreaseFps = findViewById(R.id.btnDecreaseFps)
        btnIncreaseQuality = findViewById(R.id.btnIncreaseQuality)
        btnDecreaseQuality = findViewById(R.id.btnDecreaseQuality)
        btnMinimize = findViewById(R.id.btnTogglePanel)
        controlPanel = findViewById(R.id.controlPanel)
        btnMicToggle = findViewById(R.id.btnMicToggle)
        btnSwitchCamera = findViewById(R.id.btnSwitchCamera)
        btnOrientationLock = findViewById(R.id.btnOrientationLock)
        tvUsbStatus = findViewById(R.id.tvUsbStatus)
        coordinatorLayout = findViewById(R.id.coordinator_layout)
    }

    private fun setupFilterMenu() {
        // Inflate overlay
        val overlay = layoutInflater.inflate(R.layout.overlay_filter_menu, coordinatorLayout, false)
        coordinatorLayout.addView(overlay)

        filterMenuContainer = overlay.findViewById(R.id.overlayFilterMenu)
        btnFilterToggle = overlay.findViewById(R.id.btnFilterToggle)
        filterChipsContainer = overlay.findViewById(R.id.filterChipsContainer)

        // Position top-right, below status bar
        val params = filterMenuContainer.layoutParams as CoordinatorLayout.LayoutParams
        params.gravity = Gravity.TOP or Gravity.END
        params.topMargin = resources.getDimensionPixelSize(R.dimen.filter_menu_top_margin)
        params.rightMargin = resources.getDimensionPixelSize(R.dimen.filter_menu_right_margin)
        filterMenuContainer.layoutParams = params

        filterMenuContainer.visibility = View.GONE

        btnFilterToggle.setOnClickListener {
            isFilterMenuExpanded = !isFilterMenuExpanded
            filterChipsContainer.visibility = if (isFilterMenuExpanded) View.VISIBLE else View.GONE
        }

        // Wire filter chips
        val filterMap = mapOf(
            R.id.chipNone to FilterType.NONE,
            R.id.chipBeauty to FilterType.BEAUTY,
            R.id.chipFaceBlur to FilterType.BLUR,
            R.id.chipBgReplace to FilterType.BG_REPLACE,
            R.id.chipSepia to FilterType.SEPIA,
            R.id.chipGrayscale to FilterType.GRAYSCALE,
            R.id.chipNegative to FilterType.NEGATIVE,
            R.id.chipVignette to FilterType.VIGNETTE,
            R.id.chipBrightness to FilterType.BRIGHTNESS,
            R.id.chipContrast to FilterType.CONTRAST,
            R.id.chipSaturation to FilterType.SATURATION,
            R.id.chipGlow to FilterType.GLOW,
            R.id.chipSketch to FilterType.SKETCH,
            R.id.chipToon to FilterType.TOON,
            R.id.chipPixelate to FilterType.PIXELATE,
            R.id.chipKaleidoscope to FilterType.KALEIDOSCOPE
        )

        for ((chipId, filterType) in filterMap) {
            val chip = overlay.findViewById<TextView>(chipId)
            chip?.setOnClickListener {
                selectFilterChip(chip, filterType)
            }
        }

        // Select "None" by default
        val noneChip = overlay.findViewById<TextView>(R.id.chipNone)
        selectFilterChip(noneChip, FilterType.NONE)
    }

    private fun selectFilterChip(chip: TextView, filterType: FilterType) {
        // Deselect previous
        selectedFilterChip?.setBackgroundResource(R.drawable.bg_filter_chip)
        selectedFilterChip?.setTextColor(Color.WHITE)

        // Select new
        chip.setBackgroundResource(R.drawable.bg_filter_chip_selected)
        chip.setTextColor(Color.WHITE)
        selectedFilterChip = chip

        // Apply filter
        FilterPipeline.isFaceBlurEnabled = false
        FilterPipeline.isBgReplaceEnabled = false
        when (filterType) {
            FilterType.BLUR -> FilterPipeline.isFaceBlurEnabled = true
            FilterType.BEAUTY -> FilterPipeline.activeFilter = FilterType.BEAUTY
            FilterType.BG_REPLACE -> {
                FilterPipeline.isBgReplaceEnabled = true
                AiProcessor.isSegmentationEnabled = true
            }
            else -> {
                FilterPipeline.activeFilter = filterType
                AiProcessor.isSegmentationEnabled = false
            }
        }
        Log.i(TAG, "Filter: ${filterType.displayName}")
    }

    private fun setupBottomSheet() {
        sheetBehavior = BottomSheetBehavior.from(controlPanel).apply {
            isHideable = false
            isFitToContents = true
            peekHeight = resources.getDimensionPixelSize(R.dimen.sheet_peek_height)
            state = BottomSheetBehavior.STATE_COLLAPSED
            addBottomSheetCallback(object : BottomSheetBehavior.BottomSheetCallback() {
                override fun onStateChanged(bottomSheet: View, newState: Int) {
                    btnMinimize.setImageResource(
                        if (newState == BottomSheetBehavior.STATE_EXPANDED)
                            android.R.drawable.arrow_down_float
                        else
                            android.R.drawable.arrow_up_float
                    )
                }

                override fun onSlide(bottomSheet: View, slideOffset: Float) = Unit
            })
        }
    }

    private fun setupButtonListeners() {
        btnRecordBg.setOnClickListener {
            if (isStreaming) {
                stopCamera()
                isStreaming = false
                filterMenuContainer.visibility = View.GONE
            } else {
                startCamera()
                isStreaming = true
                filterMenuContainer.visibility = View.VISIBLE
            }
            updateUI()
        }

        btnMinimize.setOnClickListener {
            sheetBehavior.state =
                if (sheetBehavior.state == BottomSheetBehavior.STATE_EXPANDED)
                    BottomSheetBehavior.STATE_COLLAPSED
                else
                    BottomSheetBehavior.STATE_EXPANDED
        }

        btnSwitchCamera.setOnClickListener {
            if (isStreaming) {
                useFrontCamera = !useFrontCamera
                Log.i(TAG, "Beralih ke kamera: ${if (useFrontCamera) "Depan" else "Belakang"}")
                previewView.post { bindCameraUseCases() }
            } else {
                Toast.makeText(this, "Start stream dulu untuk flip kamera", Toast.LENGTH_SHORT).show()
            }
        }

        btnMicToggle.setOnClickListener {
            MicManager.toggle()
            equalizerView.setActive(MicManager.isActive)
            updateUI()
        }

        btnOrientationLock.setOnClickListener {
            isOrientationLocked = !isOrientationLocked
            if (isOrientationLocked) {
                requestedOrientation = if (resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE)
                    ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
                else
                    ActivityInfo.SCREEN_ORIENTATION_SENSOR_PORTRAIT
            } else {
                requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
            }
            updateUI()
        }

        btnIncreaseFps.setOnClickListener {
            currentFps = (currentFps + 5).coerceAtMost(30)
            updateUI()
        }
        btnDecreaseFps.setOnClickListener {
            currentFps = (currentFps - 5).coerceAtLeast(5)
            updateUI()
        }
        btnIncreaseQuality.setOnClickListener {
            currentQuality = (currentQuality + 10).coerceAtMost(100)
            updateUI()
        }
        btnDecreaseQuality.setOnClickListener {
            currentQuality = (currentQuality - 10).coerceAtLeast(30)
            updateUI()
        }
    }

    private fun updateUI() {
        val qualityLabel = getQualityLabel(currentQuality)
        tvFpsInfo.text = "FPS $currentFps"
        tvQualityInfo.text = "Quality $qualityLabel"
        tvFpsValue.text = currentFps.toString()
        tvQualityValue.text = qualityLabel
        MjpegHttpServer.currentFps = currentFps
        MjpegHttpServer.currentQuality = currentQuality

        if (isStreaming && MJPEGServerInstance.isRunning()) {
            val ipAddress = getDeviceIpAddress()
            val h264Info = if (tcpStreamServer?.isRunning() == true) " | H.264: $ipAddress:8086" else ""
            tvIpInfo.text = "http://$ipAddress:${MJPEGServerInstance.getPort()}/video$h264Info"
        } else {
            tvIpInfo.text = "Stream offline"
        }

        btnRecordBg.setBackgroundResource(
            if (isStreaming) R.drawable.bg_btn_record_on else R.drawable.bg_btn_record_off
        )
        btnRecordInner.visibility = if (isStreaming) View.GONE else View.VISIBLE
        equalizerView.setActive(isStreaming && MicManager.isActive)

        btnIncreaseFps.isEnabled = isStreaming
        btnDecreaseFps.isEnabled = isStreaming
        btnIncreaseQuality.isEnabled = isStreaming
        btnDecreaseQuality.isEnabled = isStreaming
        btnSwitchCamera.isEnabled = true
        btnMicToggle.isEnabled = true

        btnMicToggle.setImageResource(
            if (MicManager.isActive) R.drawable.ic_mic else R.drawable.ic_mic_off
        )
        btnOrientationLock.setImageResource(
            if (isOrientationLocked) R.drawable.ic_rotate_locked else R.drawable.ic_rotate
        )
        tvUsbStatus.text = getUsbTetheringIp()
    }

    private fun getQualityLabel(quality: Int): String {
        return when {
            quality >= 80 -> "High"
            quality >= 50 -> "Medium"
            else -> "Low"
        }
    }

    private fun allPermissionsGranted() = REQUIRED_PERMISSIONS.all {
        ContextCompat.checkSelfPermission(baseContext, it) == PackageManager.PERMISSION_GRANTED
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQUEST_CODE_PERMISSIONS) {
            if (allPermissionsGranted()) {
                updateUI()
            } else {
                Log.e(TAG, "Izin kamera tidak diberikan.")
                Toast.makeText(this, "Aplikasi memerlukan izin kamera", Toast.LENGTH_LONG).show()
                finish()
            }
        }
    }

    private fun startCamera() {
        val cameraProviderFuture = ProcessCameraProvider.getInstance(this)
        cameraProviderFuture.addListener({
            try {
                cameraProvider = cameraProviderFuture.get()
                bindCameraUseCases()
            } catch (exc: Exception) {
                Log.e(TAG, "Gagal memulai kamera: ${exc.message}", exc)
                Toast.makeText(this, "Gagal memulai kamera", Toast.LENGTH_SHORT).show()
            }
        }, ContextCompat.getMainExecutor(this))
    }

    private fun bindCameraUseCases() {
        if (cameraProvider == null) return

        val preview = Preview.Builder().build().also {
            it.setSurfaceProvider(previewView.surfaceProvider)
        }

        val imageAnalysis = ImageAnalysis.Builder()
            .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
            .setTargetResolution(android.util.Size(1280, 720))
            .build()

        // H.264 stream encoder
        val encoder = StreamEncoder()
        streamEncoder = encoder
        try {
            encoder.start()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start H.264 encoder", e)
            streamEncoder = null
        }

        var frameCount = 0L
        val startTimeNs = System.nanoTime()

        imageAnalysis.setAnalyzer(cameraExecutor) { imageProxy ->
            if (imageProxy.format != ImageFormat.YUV_420_888 || imageProxy.planes.size < 3) {
                imageProxy.close()
                return@setAnalyzer
            }

            val currentTime = SystemClock.elapsedRealtime()
            val frameInterval = 1000 / currentFps

            if (currentTime - lastFrameTimestamp < frameInterval) {
                imageProxy.close()
                return@setAnalyzer
            }
            lastFrameTimestamp = currentTime
            frameCount++

            try {
                val nv21 = yuv420ToNv21(imageProxy)
                val rotationDegrees = imageProxy.imageInfo.rotationDegrees

                // MJPEG pipeline (existing)
                FilterPipeline.process(nv21, imageProxy.width, imageProxy.height, rotationDegrees, currentQuality)

                // H.264 pipeline (new)
                streamEncoder?.let { enc ->
                    val ptsUs = (System.nanoTime() - startTimeNs) / 1000
                    enc.encode(nv21, ptsUs)
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error pada analyzer: ${e.message}", e)
                val jpeg = imageProxyToJpegBytes(imageProxy)
                if (jpeg != null) FrameBuffer.latestFrame = jpeg
            } finally {
                imageProxy.close()
            }
        }

        val cameraSelector =
            if (useFrontCamera) CameraSelector.DEFAULT_FRONT_CAMERA else CameraSelector.DEFAULT_BACK_CAMERA

        try {
            cameraProvider?.unbindAll()
            camera = cameraProvider?.bindToLifecycle(this, cameraSelector, preview, imageAnalysis)

            if (!MJPEGServerInstance.isRunning()) {
                MJPEGServerInstance.start(this, 8085)
                Log.i(TAG, "Server MJPEG aktif di port ${MJPEGServerInstance.getPort()}")
            }

            if (streamEncoder != null) {
                tcpStreamServer = TcpStreamServer(streamEncoder!!)
                tcpStreamServer?.start()
                Log.i(TAG, "H.264 TCP stream server started on port 8086")
            }

            updateUI()
        } catch (exc: Exception) {
            Log.e(TAG, "Gagal mengikat kamera: ${exc.message}", exc)
            Toast.makeText(this, "Gagal menghubungkan kamera", Toast.LENGTH_SHORT).show()
        }
    }

    private fun stopCamera() {
        tcpStreamServer?.stop()
        tcpStreamServer = null
        streamEncoder?.release()
        streamEncoder = null
        cameraProvider?.unbindAll()
        camera = null
        Log.i(TAG, "Kamera dihentikan.")
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        Log.i(TAG, "Orientasi berubah: ${newConfig.orientation}")
        if (isStreaming) {
            previewView.post { bindCameraUseCases() }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        stopCamera()
        MicManager.stop()
        MJPEGServerInstance.stop()
        FilterPipeline.shutdown()
        cameraExecutor.shutdown()
    }

    /** Fallback: original YUV-to-JPEG conversion if pipeline fails */
    private fun imageProxyToJpegBytes(image: ImageProxy): ByteArray? {
        val nv21 = yuv420ToNv21(image)
        val yuvImage = YuvImage(nv21, ImageFormat.NV21, image.width, image.height, null)
        val out = ByteArrayOutputStream()
        val success = yuvImage.compressToJpeg(Rect(0, 0, image.width, image.height), currentQuality, out)
        if (!success) return null

        val rotationDegrees = image.imageInfo.rotationDegrees
        if (rotationDegrees != 0) {
            val bmp = BitmapFactory.decodeByteArray(out.toByteArray(), 0, out.size())
            val matrix = Matrix().apply { postRotate(rotationDegrees.toFloat()) }
            val rotatedBmp = Bitmap.createBitmap(bmp, 0, 0, bmp.width, bmp.height, matrix, true)
            bmp.recycle()
            val rotatedStream = ByteArrayOutputStream()
            rotatedBmp.compress(Bitmap.CompressFormat.JPEG, currentQuality, rotatedStream)
            rotatedBmp.recycle()
            return rotatedStream.toByteArray()
        }
        return out.toByteArray()
    }

    private fun yuv420ToNv21(image: ImageProxy): ByteArray {
        val planes = image.planes
        val yPlane = planes[0]
        val uPlane = planes[1]
        val vPlane = planes[2]
        val yBuffer = yPlane.buffer
        val uBuffer = uPlane.buffer
        val vBuffer = vPlane.buffer
        val ySize = yBuffer.remaining()
        val uSize = uBuffer.remaining()
        val vSize = vBuffer.remaining()

        val nv21 = ByteArray(ySize + uSize + vSize)
        yBuffer.get(nv21, 0, ySize)

        var uvPosition = ySize
        val vRowStride = vPlane.rowStride
        val vPixelStride = vPlane.pixelStride
        val uRowStride = uPlane.rowStride
        val uPixelStride = uPlane.pixelStride

        for (row in 0 until image.height / 2) {
            for (col in 0 until image.width / 2) {
                val vPos = row * vRowStride + col * vPixelStride
                val uPos = row * uRowStride + col * uPixelStride
                nv21[uvPosition++] = vBuffer.get(vPos)
                nv21[uvPosition++] = uBuffer.get(uPos)
            }
        }
        return nv21
    }

    private fun getUsbTetheringIp(): String {
        try {
            val interfaces = NetworkInterface.getNetworkInterfaces()
            while (interfaces.hasMoreElements()) {
                val intf = interfaces.nextElement()
                val name = intf.name.lowercase()
                if (intf.isUp && (name == "usb0" || name == "rndis0" || name == "eth0")) {
                    val enumIpAddr = intf.inetAddresses
                    while (enumIpAddr.hasMoreElements()) {
                        val inetAddress = enumIpAddr.nextElement()
                        if (!inetAddress.isLoopbackAddress && inetAddress is java.net.Inet4Address) {
                            return "USB: ${inetAddress.hostAddress} | MJPEG:8085 H.264:8086"
                        }
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Gagal mendapatkan USB IP", e)
        }
        return "USB: --"
    }

    private fun getDeviceIpAddress(): String {
        try {
            val interfaces = NetworkInterface.getNetworkInterfaces()
            while (interfaces.hasMoreElements()) {
                val intf = interfaces.nextElement()
                if (intf.isUp && !intf.isLoopback) {
                    val enumIpAddr = intf.inetAddresses
                    while (enumIpAddr.hasMoreElements()) {
                        val inetAddress = enumIpAddr.nextElement()
                        if (!inetAddress.isLoopbackAddress && inetAddress is java.net.Inet4Address) {
                            return inetAddress.hostAddress ?: "0.0.0.0"
                        }
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Gagal mendapatkan IP Address", e)
        }
        return "0.0.0.0"
    }
}