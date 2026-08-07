package com.rycl.igba

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.util.AttributeSet
import android.view.SurfaceHolder
import android.view.SurfaceView

class GbaView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : SurfaceView(context, attrs, defStyleAttr), Runnable, SurfaceHolder.Callback {

    private val engine = GbaEngine()

    @Volatile
    private var isRunning = false
    private var isRomLoaded = false
    private var renderThread: Thread? = null

    @Volatile
    private var isFastForward = false

    // COUNTER FRAME UNTUK FPS HUD
    @Volatile
    private var frameCount: Long = 0L

    private var audioTrack: AudioTrack? = null
    private val audioBuffer = ShortArray(4096)

    init {
        holder.addCallback(this)
        engine.nativeInit()
        initAudio()
    }

    fun setFastForward(enabled: Boolean) {
        isFastForward = enabled
    }

    // Getter untuk dipanggil oleh MainActivity
    fun getFrameCount(): Long {
        return frameCount
    }

    private fun initAudio() {
        val sampleRate = 32000 // GBA System Native Audio Output
        val minBufferSize = AudioTrack.getMinBufferSize(
            sampleRate,
            AudioFormat.CHANNEL_OUT_STEREO,
            AudioFormat.ENCODING_PCM_16BIT
        )

        val bufferSize = if (minBufferSize > 0) minBufferSize * 8 else 16384

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
        if (isRomLoaded && holder.surface.isValid && !isRunning) {
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

        while (isRunning) {
            val targetFps = if (isFastForward) 180.0 else 60.0
            val nsPerFrame = 1_000_000_000.0 / targetFps

            val now = System.nanoTime()
            val delta = now - lastTime

            if (delta >= nsPerFrame) {
                if (isRomLoaded) {
                    // Rendering frame langsung via ANativeWindow di C++
                    engine.nativeStepFrame()

                    // INCREMENT COUNTER DI SINI!
                    frameCount++

                    // Process Audio
                    val count = engine.nativeReadAudio(audioBuffer)

                    if (count > 0 && !isFastForward) {
                        try {
                            var offset = 0
                            while (offset < count) {
                                val written = audioTrack?.write(
                                    audioBuffer,
                                    offset,
                                    count - offset,
                                    AudioTrack.WRITE_BLOCKING
                                ) ?: 0

                                if (written <= 0) break
                                offset += written
                            }
                        } catch (_: Exception) {}
                    }
                }
                lastTime = now
            } else {
                val sleepNs = (nsPerFrame - delta).toLong()
                try {
                    if (sleepNs > 1_000_000L) {
                        Thread.sleep(sleepNs / 1_000_000L)
                    } else {
                        Thread.yield()
                    }
                } catch (_: InterruptedException) {
                    break
                }
            }
        }
    }

    // SURFACE LIFECYCLE CALLBACKS
    override fun surfaceCreated(holder: SurfaceHolder) {
        engine.nativeSetSurface(holder.surface)
        if (isRomLoaded && !isRunning) {
            startLoop()
        }
    }

    override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
        engine.nativeSetSurface(holder.surface)
    }

    override fun surfaceDestroyed(holder: SurfaceHolder) {
        stopLoop()
        engine.nativeSetSurface(null)
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
