package com.zhuqing.ba_recorder

import android.Manifest
import android.app.Activity
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.MediaRecorder
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.util.DisplayMetrics
import android.view.View
import android.widget.Button
import android.widget.Toast
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import android.os.ParcelFileDescriptor

class MainActivity : Activity() {
    // 录屏相关
    private lateinit var projectionManager: MediaProjectionManager
    private var mediaProjection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var mediaRecorder: MediaRecorder? = null

    private var isRecording = false

    // 屏幕参数
    private var screenDensityDpi: Int = 0
    private var screenWidth: Int = 0
    private var screenHeight: Int = 0

    private val REQ_CAPTURE = 1001
    private val REQ_NOTIF = 2001
    private val REQ_WRITE = 2002

    // 按钮
    private lateinit var btnStart: Button
    private lateinit var btnStop: Button

    // 输出目标状态（MediaStore 或公共文件）
    private var outputUri: Uri? = null
    private var outputPfd: ParcelFileDescriptor? = null
    private var outputFile: File? = null
    private var usingMediaStore: Boolean = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        // 通知权限（Android 13+）
        ensurePostNotificationPermission()

        // 不在冷启动时就启动前台服务，避免未授予通知权限导致的崩溃
        // RecordingFgService.start(this)

        // 初始化
        projectionManager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        computeScreenMetrics()

        btnStart = findViewById(R.id.btnStart)
        btnStop = findViewById(R.id.btnStop)
        showStartOnly()

        btnStart.setOnClickListener {
            if (isRecording) {
                Toast.makeText(this, "已经在录制中", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            // 仅在已具备通知权限时才启动前台服务；否则先申请权限
            if (Build.VERSION.SDK_INT >= 33 &&
                checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
            ) {
                requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), REQ_NOTIF)
                Toast.makeText(this, "请允许通知权限后再开始录制", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            RecordingFgService.start(this)
            requestScreenCapture()
        }
        btnStop.setOnClickListener {
            if (!isRecording) {
                Toast.makeText(this, "当前未在录制", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            stopRecordingSafely(showToast = true)
        }
    }

    private fun showStartOnly() {
        btnStart.visibility = View.VISIBLE
        btnStop.visibility = View.GONE
    }

    private fun showStopOnly() {
        btnStart.visibility = View.GONE
        btnStop.visibility = View.VISIBLE
    }

    private fun requestScreenCapture() {
        try {
            val intent = projectionManager.createScreenCaptureIntent()
            @Suppress("DEPRECATION")
            startActivityForResult(intent, REQ_CAPTURE)
        } catch (e: Exception) {
            Toast.makeText(this, "发起录屏请求失败: ${e.message}", Toast.LENGTH_LONG).show()
            showStartOnly()
            RecordingFgService.stop(this)
        }
    }

    @Deprecated("Deprecated API for backward compatibility")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQ_CAPTURE) {
            if (resultCode == RESULT_OK && data != null) {
                startRecordingWithPermission(resultCode, data)
            } else {
                Toast.makeText(this, "未获得录屏授权", Toast.LENGTH_SHORT).show()
                showStartOnly()
                RecordingFgService.stop(this)
            }
        }
    }

    private fun startRecordingWithPermission(resultCode: Int, data: Intent) {
        try {
            // Android 9 及以下需要写外部存储权限
            if (!ensureLegacyWritePermission()) {
                Toast.makeText(this, "请先允许存储权限后再开始录制", Toast.LENGTH_SHORT).show()
                showStartOnly()
                RecordingFgService.stop(this)
                return
            }

            mediaProjection = projectionManager.getMediaProjection(resultCode, data)
            if (mediaProjection == null) {
                Toast.makeText(this, "获取 MediaProjection 失败", Toast.LENGTH_SHORT).show()
                showStartOnly()
                RecordingFgService.stop(this)
                return
            }

            // 准备输出到 Movies/BA_Recorder
            if (!prepareOutputTarget()) {
                Toast.makeText(this, "创建输出文件失败", Toast.LENGTH_LONG).show()
                showStartOnly()
                RecordingFgService.stop(this)
                return
            }

            initRecorderWithCurrentOutput()

            // 必须在开始捕获前注册回调
            val mainHandler = Handler(Looper.getMainLooper())
            mediaProjection?.registerCallback(object : MediaProjection.Callback() {
                override fun onStop() {
                    runOnUiThread { stopRecordingSafely(showToast = true) }
                }
            }, mainHandler)

            val surface = mediaRecorder!!.surface
            virtualDisplay = mediaProjection!!.createVirtualDisplay(
                "BA_Recorder_Display",
                screenWidth,
                screenHeight,
                screenDensityDpi,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                surface,
                null,
                null
            )

            mediaRecorder?.start()
            isRecording = true
            showStopOnly()
            Toast.makeText(this, "开始录屏", Toast.LENGTH_SHORT).show()
        } catch (e: Exception) {
            cleanupPendingIfNeeded()
            stopRecordingSafely(showToast = false)
            showStartOnly()
            RecordingFgService.stop(this)
            Toast.makeText(this, "启动录屏失败: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    private fun ensurePostNotificationPermission() {
        if (Build.VERSION.SDK_INT >= 33) {
            if (checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), REQ_NOTIF)
            }
        }
    }

    private fun ensureLegacyWritePermission(): Boolean {
        return if (Build.VERSION.SDK_INT < 29) {
            if (checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED) {
                requestPermissions(arrayOf(Manifest.permission.WRITE_EXTERNAL_STORAGE), REQ_WRITE)
                false
            } else true
        } else true
    }

    private fun computeScreenMetrics() {
        val dm: DisplayMetrics = resources.displayMetrics
        screenWidth = dm.widthPixels
        screenHeight = dm.heightPixels
        screenDensityDpi = dm.densityDpi
    }

    private fun initRecorderWithCurrentOutput() {
        mediaRecorder?.reset()
        mediaRecorder?.release()
        mediaRecorder = null

        val recorder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) MediaRecorder(this) else MediaRecorder()
        recorder.setVideoSource(MediaRecorder.VideoSource.SURFACE)
        recorder.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
        if (usingMediaStore && outputPfd != null) {
            recorder.setOutputFile(outputPfd!!.fileDescriptor)
        } else if (outputFile != null) {
            recorder.setOutputFile(outputFile!!.absolutePath)
        } else {
            throw IllegalStateException("无有效输出目标")
        }
        recorder.setVideoEncoder(MediaRecorder.VideoEncoder.H264)
        recorder.setVideoEncodingBitRate(calculateBitrate(screenWidth, screenHeight))
        recorder.setVideoFrameRate(30)
        recorder.setVideoSize(makeEven(screenWidth), makeEven(screenHeight))
        recorder.prepare()
        mediaRecorder = recorder
    }

    private fun prepareOutputTarget(): Boolean {
        clearOutputState()
        val fileName = "Screen_" + SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date()) + ".mp4"
        return try {
            if (Build.VERSION.SDK_INT >= 29) {
                val values = ContentValues().apply {
                    put(MediaStore.MediaColumns.DISPLAY_NAME, fileName)
                    put(MediaStore.MediaColumns.MIME_TYPE, "video/mp4")
                    put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_MOVIES + "/BA_Recorder/")
                    put(MediaStore.MediaColumns.IS_PENDING, 1)
                }
                val resolver = contentResolver
                val collection = MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
                val uri = resolver.insert(collection, values) ?: return false
                val pfd = resolver.openFileDescriptor(uri, "w") ?: run {
                    resolver.delete(uri, null, null)
                    return false
                }
                outputUri = uri
                outputPfd = pfd
                usingMediaStore = true
                true
            } else {
                val movies = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MOVIES)
                val dir = File(movies, "BA_Recorder")
                if (!dir.exists()) dir.mkdirs()
                val f = File(dir, fileName)
                outputFile = f
                usingMediaStore = false
                true
            }
        } catch (_: Exception) { false }
    }

    private fun publishIfNeeded() {
        if (usingMediaStore && outputUri != null) {
            try {
                val values = ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }
                contentResolver.update(outputUri!!, values, null, null)
            } catch (_: Exception) {}
        } else if (outputFile != null && Build.VERSION.SDK_INT < 29) {
            try {
                @Suppress("DEPRECATION")
                sendBroadcast(Intent(Intent.ACTION_MEDIA_SCANNER_SCAN_FILE).setData(Uri.fromFile(outputFile)))
            } catch (_: Exception) {}
        }
    }

    private fun cleanupPendingIfNeeded() {
        if (!isRecording && usingMediaStore && outputUri != null) {
            try { contentResolver.delete(outputUri!!, null, null) } catch (_: Exception) {}
            try { outputPfd?.close() } catch (_: Exception) {}
            clearOutputState()
        }
    }

    private fun clearOutputState() {
        outputFile = null
        outputUri = null
        try { outputPfd?.close() } catch (_: Exception) {}
        outputPfd = null
        usingMediaStore = false
    }

    private fun calculateBitrate(w: Int, h: Int): Int {
        val base = (w.toLong() * h.toLong() * 8L).coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
        return base.coerceAtLeast(2_000_000)
    }

    private fun makeEven(v: Int): Int = if (v % 2 == 0) v else v - 1

    private fun stopRecordingSafely(showToast: Boolean) {
        try {
            mediaRecorder?.apply {
                try { stop() } catch (_: Exception) {}
                reset()
                release()
            }
        } catch (_: Exception) { } finally { mediaRecorder = null }

        try { virtualDisplay?.release() } catch (_: Exception) { } finally { virtualDisplay = null }
        try { mediaProjection?.stop() } catch (_: Exception) { } finally { mediaProjection = null }

        val wasRecording = isRecording
        isRecording = false

        publishIfNeeded()
        try { outputPfd?.close() } catch (_: Exception) {}
        usingMediaStore = false

        if (this::btnStart.isInitialized && this::btnStop.isInitialized) {
            showStartOnly()
        }
        RecordingFgService.stop(this)
        if (showToast && wasRecording) {
            Toast.makeText(this, "已停止录屏，视频已保存", Toast.LENGTH_SHORT).show()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        if (isRecording) {
            stopRecordingSafely(showToast = false)
        }
    }
}
