package com.rycl.igba

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.util.AttributeSet
import android.view.View

class GbaView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr), Runnable {

    private val engine = GbaEngine()
    private val bitmap: Bitmap = Bitmap.createBitmap(240, 160, Bitmap.Config.RGB_565)
    private val paint: Paint = Paint().apply {
        isFilterBitmap = false
    }

    @Volatile
    private var isRunning = false
    private var isRomLoaded = false
    private var renderThread: Thread? = null

    @Volatile
    private var isFastForward = false

    private val srcRect = Rect(0, 0, 240, 160)
    private val dstRect = Rect()

    private var audioTrack: AudioTrack? = null

    init {
        engine.nativeInit()
        initAudio()
    }

    fun setFastForward(enabled: Boolean) {
        isFastForward = enabled
    }

    private fun initAudio() {
        val sampleRate = 32000 // GBA System Native Audio Output
        val minBufferSize = AudioTrack.getMinBufferSize(
            sampleRate,
            AudioFormat.CHANNEL_OUT_STEREO,
            AudioFormat.ENCODING_PCM_16BIT
        )

        // Buffer Headroom 4x untuk mencegah audio underrun/stutter
        val bufferSize = if (minBufferSize > 0) minBufferSize * 4 else 8192

        try {
            audioTrack = AudioTrack.Builder()
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_GAME)
                        .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                        .build()
                )
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .setSampleRate(sampleRate)
                        .setChannelMask(AudioFormat.CHANNEL_OUT_STEREO)
                        .build()
                )
                .setBufferSizeInBytes(bufferSize)
                .setTransferMode(AudioTrack.MODE_STREAM)
                .build()
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    fun loadRom(path: String): Boolean {
        isRomLoaded = engine.nativeLoadRom(path)
        if (isRomLoaded && !isRunning) {
            startLoop()
        }
        return isRomLoaded
    }

    fun updateInput(keys: Int) {
        engine.nativeSendInput(keys)
    }

    @Synchronized
    private fun startLoop() {
        if (isRunning) return
        isRunning = true
        try {
            if (audioTrack?.state == AudioTrack.STATE_INITIALIZED) {
                audioTrack?.play()
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
        renderThread = Thread(this, "GbaRenderThread").apply { start() }
    }

    fun stopLoop() {
        isRunning = false
        try {
            if (audioTrack?.state == AudioTrack.STATE_INITIALIZED &&
                audioTrack?.playState == AudioTrack.PLAYSTATE_PLAYING) {
                audioTrack?.stop()
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
        try {
            renderThread?.join(500)
        } catch (e: InterruptedException) {
            e.printStackTrace()
        }
        renderThread = null
    }

    override fun run() {
        var lastTime = System.nanoTime()
        var frameSkipCounter = 0

        while (isRunning) {
            // Jika Fast Forward -> Target 180 FPS (3x Speed), Normal -> 60 FPS
            val targetFps = if (isFastForward) 180.0 else 60.0
            val nsPerFrame = 1_000_000_000.0 / targetFps

            val now = System.nanoTime()
            val delta = now - lastTime

            if (delta >= nsPerFrame) {
                if (isRomLoaded) {
                    // Step emulasi frame
                    engine.nativeStepFrame(bitmap)

                    // Skip canvas rendering jika telat berat
                    if (delta > nsPerFrame * 1.5 && frameSkipCounter < 2) {
                        frameSkipCounter++
                    } else {
                        frameSkipCounter = 0
                        postInvalidateOnAnimation()
                    }

                    // Audio Buffer Handler
                    val audioData = engine.nativeReadAudio()
                    if (audioData != null && audioData.isNotEmpty() && isRunning) {
                        // Jangan putar audio berisik saat fast forward
                        if (!isFastForward) {
                            try {
                                audioTrack?.write(
                                    audioData,
                                    0,
                                    audioData.size,
                                    AudioTrack.WRITE_NON_BLOCKING
                                )
                            } catch (_: Exception) {}
                        }
                    }
                }
                lastTime = now
            } else {
                try {
                    Thread.sleep(1)
                } catch (_: InterruptedException) {
                    break
                }
            }
        }
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        dstRect.set(0, 0, w, h)
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (isRomLoaded) {
            canvas.drawBitmap(bitmap, srcRect, dstRect, paint)
        }
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        stopLoop()
        try {
            audioTrack?.release()
            audioTrack = null
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }
}
