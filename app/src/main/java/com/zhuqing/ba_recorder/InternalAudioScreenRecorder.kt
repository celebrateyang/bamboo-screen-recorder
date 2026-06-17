package com.zhuqing.ba_recorder

import android.content.Context
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioPlaybackCaptureConfiguration
import android.media.AudioRecord
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaMuxer
import android.media.projection.MediaProjection
import android.os.Build
import java.io.FileDescriptor
import java.nio.ByteBuffer
import java.util.concurrent.atomic.AtomicBoolean

class InternalAudioScreenRecorder(
    private val context: Context,
    private val mediaProjection: MediaProjection,
    private val width: Int,
    private val height: Int,
    private val densityDpi: Int,
    private val outputFd: FileDescriptor,
    private val videoBitrate: Int
) {
    private val running = AtomicBoolean(false)
    private val muxerLock = Object()

    private var muxer: MediaMuxer? = null
    private var videoEncoder: MediaCodec? = null
    private var audioEncoder: MediaCodec? = null
    private var audioRecord: AudioRecord? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var inputSurface: android.view.Surface? = null

    private var videoThread: Thread? = null
    private var audioThread: Thread? = null
    private var videoTrackIndex = -1
    private var audioTrackIndex = -1
    private var muxerStarted = false
    private var stopping = false
    private var videoBasePtsUs = -1L

    fun start() {
        require(Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            "Internal audio capture requires Android 10 or newer"
        }

        muxer = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            MediaMuxer(outputFd, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
        } else {
            throw IllegalStateException("FileDescriptor muxing requires Android 8 or newer")
        }

        prepareVideoEncoder()
        prepareAudioEncoder()

        running.set(true)
        videoEncoder!!.start()
        audioEncoder!!.start()
        audioRecord!!.startRecording()

        virtualDisplay = mediaProjection.createVirtualDisplay(
            "BA_Recorder_Display",
            width,
            height,
            densityDpi,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            inputSurface,
            null,
            null
        )

        videoThread = Thread({ drainVideoEncoder() }, "ba-video-encoder").also { it.start() }
        audioThread = Thread({ captureAndEncodeAudio() }, "ba-audio-encoder").also { it.start() }
    }

    fun stop() {
        if (!running.getAndSet(false) && stopping) return
        stopping = true

        try {
            audioRecord?.stop()
        } catch (_: Exception) {
        }
        try {
            videoEncoder?.signalEndOfInputStream()
        } catch (_: Exception) {
        }

        try {
            audioThread?.join(3_000)
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
        }
        try {
            videoThread?.join(3_000)
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
        }

        release()
    }

    private fun prepareVideoEncoder() {
        val format = MediaFormat.createVideoFormat(VIDEO_MIME, width, height).apply {
            setInteger(
                MediaFormat.KEY_COLOR_FORMAT,
                MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface
            )
            setInteger(MediaFormat.KEY_BIT_RATE, videoBitrate)
            setInteger(MediaFormat.KEY_FRAME_RATE, 30)
            setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
        }

        videoEncoder = MediaCodec.createEncoderByType(VIDEO_MIME).apply {
            configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            inputSurface = createInputSurface()
        }
    }

    private fun prepareAudioEncoder() {
        val captureConfig = AudioPlaybackCaptureConfiguration.Builder(mediaProjection)
            .addMatchingUsage(AudioAttributes.USAGE_MEDIA)
            .addMatchingUsage(AudioAttributes.USAGE_GAME)
            .addMatchingUsage(AudioAttributes.USAGE_UNKNOWN)
            .build()

        val audioFormat = AudioFormat.Builder()
            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
            .setSampleRate(AUDIO_SAMPLE_RATE)
            .setChannelMask(AudioFormat.CHANNEL_IN_STEREO)
            .build()

        val minBufferSize = AudioRecord.getMinBufferSize(
            AUDIO_SAMPLE_RATE,
            AudioFormat.CHANNEL_IN_STEREO,
            AudioFormat.ENCODING_PCM_16BIT
        )
        val bufferSize = (minBufferSize.coerceAtLeast(AUDIO_READ_BYTES) * 2)

        audioRecord = AudioRecord.Builder()
            .setAudioPlaybackCaptureConfig(captureConfig)
            .setAudioFormat(audioFormat)
            .setBufferSizeInBytes(bufferSize)
            .build()

        val encoderFormat = MediaFormat.createAudioFormat(
            AUDIO_MIME,
            AUDIO_SAMPLE_RATE,
            AUDIO_CHANNEL_COUNT
        ).apply {
            setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
            setInteger(MediaFormat.KEY_BIT_RATE, AUDIO_BITRATE)
        }

        audioEncoder = MediaCodec.createEncoderByType(AUDIO_MIME).apply {
            configure(encoderFormat, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        }
    }

    private fun drainVideoEncoder() {
        val codec = videoEncoder ?: return
        val bufferInfo = MediaCodec.BufferInfo()

        while (true) {
            when (val outputIndex = codec.dequeueOutputBuffer(bufferInfo, CODEC_TIMEOUT_US)) {
                MediaCodec.INFO_TRY_AGAIN_LATER -> {
                    if (!running.get()) continue
                }
                MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                    synchronized(muxerLock) {
                        videoTrackIndex = muxer!!.addTrack(codec.outputFormat)
                        startMuxerIfReadyLocked()
                    }
                }
                else -> {
                    if (outputIndex >= 0) {
                        val encodedData = codec.getOutputBuffer(outputIndex)
                        if (bufferInfo.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0) {
                            bufferInfo.size = 0
                        }
                        if (bufferInfo.size > 0 && encodedData != null) {
                            if (videoBasePtsUs < 0) videoBasePtsUs = bufferInfo.presentationTimeUs
                            bufferInfo.presentationTimeUs =
                                (bufferInfo.presentationTimeUs - videoBasePtsUs).coerceAtLeast(0)
                            writeSample(videoTrackIndex, encodedData, bufferInfo)
                        }
                        val endOfStream =
                            bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0
                        codec.releaseOutputBuffer(outputIndex, false)
                        if (endOfStream) return
                    }
                }
            }
        }
    }

    private fun captureAndEncodeAudio() {
        val record = audioRecord ?: return
        val codec = audioEncoder ?: return
        val readBuffer = ByteBuffer.allocateDirect(AUDIO_READ_BYTES)
        val bufferInfo = MediaCodec.BufferInfo()
        val startedAtNs = System.nanoTime()
        var sentEos = false

        while (true) {
            if (running.get()) {
                readBuffer.clear()
                val read = record.read(readBuffer, AUDIO_READ_BYTES)
                if (read > 0) {
                    queueAudioInput(codec, readBuffer, read, (System.nanoTime() - startedAtNs) / 1_000L)
                }
            } else if (!sentEos) {
                queueAudioEos(codec, (System.nanoTime() - startedAtNs) / 1_000L)
                sentEos = true
            }

            drainAudioEncoder(codec, bufferInfo)
            if (sentEos && bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) return
        }
    }

    private fun queueAudioInput(
        codec: MediaCodec,
        source: ByteBuffer,
        size: Int,
        presentationTimeUs: Long
    ) {
        val inputIndex = codec.dequeueInputBuffer(CODEC_TIMEOUT_US)
        if (inputIndex < 0) return

        val inputBuffer = codec.getInputBuffer(inputIndex) ?: return
        inputBuffer.clear()
        source.flip()
        source.limit(size)
        inputBuffer.put(source)
        codec.queueInputBuffer(inputIndex, 0, size, presentationTimeUs, 0)
    }

    private fun queueAudioEos(codec: MediaCodec, presentationTimeUs: Long) {
        val inputIndex = codec.dequeueInputBuffer(CODEC_TIMEOUT_US)
        if (inputIndex >= 0) {
            codec.queueInputBuffer(
                inputIndex,
                0,
                0,
                presentationTimeUs,
                MediaCodec.BUFFER_FLAG_END_OF_STREAM
            )
        }
    }

    private fun drainAudioEncoder(codec: MediaCodec, bufferInfo: MediaCodec.BufferInfo) {
        while (true) {
            when (val outputIndex = codec.dequeueOutputBuffer(bufferInfo, 0)) {
                MediaCodec.INFO_TRY_AGAIN_LATER -> return
                MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                    synchronized(muxerLock) {
                        audioTrackIndex = muxer!!.addTrack(codec.outputFormat)
                        startMuxerIfReadyLocked()
                    }
                }
                else -> {
                    if (outputIndex >= 0) {
                        val encodedData = codec.getOutputBuffer(outputIndex)
                        if (bufferInfo.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0) {
                            bufferInfo.size = 0
                        }
                        if (bufferInfo.size > 0 && encodedData != null) {
                            writeSample(audioTrackIndex, encodedData, bufferInfo)
                        }
                        val endOfStream =
                            bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0
                        codec.releaseOutputBuffer(outputIndex, false)
                        if (endOfStream) return
                    }
                }
            }
        }
    }

    private fun startMuxerIfReadyLocked() {
        if (!muxerStarted && videoTrackIndex >= 0 && audioTrackIndex >= 0) {
            muxer!!.start()
            muxerStarted = true
            muxerLock.notifyAll()
        }
    }

    private fun writeSample(
        trackIndex: Int,
        encodedData: ByteBuffer,
        bufferInfo: MediaCodec.BufferInfo
    ) {
        if (trackIndex < 0) return

        synchronized(muxerLock) {
            while (!muxerStarted && !stopping) {
                try {
                    muxerLock.wait(100)
                } catch (_: InterruptedException) {
                    Thread.currentThread().interrupt()
                    return
                }
            }
            if (!muxerStarted) return
            encodedData.position(bufferInfo.offset)
            encodedData.limit(bufferInfo.offset + bufferInfo.size)
            muxer!!.writeSampleData(trackIndex, encodedData, bufferInfo)
        }
    }

    private fun release() {
        try {
            virtualDisplay?.release()
        } catch (_: Exception) {
        } finally {
            virtualDisplay = null
        }
        try {
            inputSurface?.release()
        } catch (_: Exception) {
        } finally {
            inputSurface = null
        }
        try {
            audioRecord?.release()
        } catch (_: Exception) {
        } finally {
            audioRecord = null
        }
        try {
            audioEncoder?.stop()
        } catch (_: Exception) {
        }
        try {
            audioEncoder?.release()
        } catch (_: Exception) {
        } finally {
            audioEncoder = null
        }
        try {
            videoEncoder?.stop()
        } catch (_: Exception) {
        }
        try {
            videoEncoder?.release()
        } catch (_: Exception) {
        } finally {
            videoEncoder = null
        }
        try {
            if (muxerStarted) muxer?.stop()
        } catch (_: Exception) {
        }
        try {
            muxer?.release()
        } catch (_: Exception) {
        } finally {
            muxer = null
            muxerStarted = false
        }
    }

    companion object {
        private const val VIDEO_MIME = "video/avc"
        private const val AUDIO_MIME = "audio/mp4a-latm"
        private const val AUDIO_SAMPLE_RATE = 44_100
        private const val AUDIO_CHANNEL_COUNT = 2
        private const val AUDIO_BITRATE = 128_000
        private const val AUDIO_READ_BYTES = 4096
        private const val CODEC_TIMEOUT_US = 10_000L
    }
}
