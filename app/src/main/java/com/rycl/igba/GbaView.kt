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
    private val bitmap = Bitmap.createBitmap(240, 160, Bitmap.Config.RGB_565)
    private val paint = Paint().apply {
        isFilterBitmap = false
    }

    @Volatile
    private var isRunning = false
    private var isRomLoaded = false
    private var renderThread: Thread? = null

    private val srcRect = Rect(0, 0, 240, 160)
    private val dstRect = Rect()

    private var audioTrack: AudioTrack? = null

    init {
        engine.nativeInit()
        initAudio()
    }

    private fun initAudio() {
        val sampleRate = 32000 // GBA System Audio Output
        val minBufferSize = AudioTrack.getMinBufferSize(
            sampleRate,
            AudioFormat.CHANNEL_OUT_STEREO,
            AudioFormat.ENCODING_PCM_16BIT
        )

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
            .setBufferSizeInBytes(minBufferSize * 2)
            .setTransferMode(AudioTrack.MODE_STREAM)
            .build()
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

    private synchronized fun startLoop() {
        if (isRunning) return
        isRunning = true
        audioTrack?.play()
        renderThread = Thread(this, "GbaRenderThread").apply { start() }
    }

    fun stopLoop() {
        isRunning = false
        audioTrack?.stop()
        try {
            renderThread?.join(500)
        } catch (e: InterruptedException) {
            e.printStackTrace()
        }
        renderThread = null
    }

    override fun run() {
        var lastTime = System.nanoTime()
        val nsPerFrame = 1_000_000_000.0 / 60.0

        while (isRunning) {
            val now = System.nanoTime()
            if (now - lastTime >= nsPerFrame) {
                if (isRomLoaded) {
                    engine.nativeStepFrame(bitmap)
                    postInvalidateOnAnimation()

                    val audioData = engine.nativeReadAudio()
                    if (audioData != null && audioData.isNotEmpty()) {
                        audioTrack?.write(audioData, 0, audioData.size)
                    }
                }
                lastTime = now
            } else {
                try {
                    Thread.sleep(1)
                } catch (e: InterruptedException) {
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
        audioTrack?.release()
    }
}
