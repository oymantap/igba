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
) : SurfaceView(context, attrs, defStyleAttr),
    Runnable,
    SurfaceHolder.Callback {

    private val engine = GbaEngine()

    @Volatile
    private var isRunning = false

    @Volatile
    private var isRomLoaded = false

    @Volatile
    private var isFastForward = false

    private var renderThread: Thread? = null
    private var audioThread: Thread? = null

    @Volatile
    private var frameCount = 0L

    private var audioTrack: AudioTrack? = null

    // Audio buffer reusable.
    // 4096 samples = 2048 stereo frames.
    private val audioBuffer =
        ShortArray(4096)

    init {
        holder.addCallback(this)

        engine.nativeInit()

        initAudio()
    }


    // ========================================================
    // FAST FORWARD
    // ========================================================

    fun setFastForward(enabled: Boolean) {
        isFastForward = enabled
    }


    // ========================================================
    // FRAME COUNTER
    // ========================================================

    fun getFrameCount(): Long {
        return frameCount
    }


    // ========================================================
    // AUDIO INIT
    // ========================================================

    private fun initAudio() {

        val sampleRate =
              engine.nativeGetAudioSampleRate()

        val minBufferSize =
            AudioTrack.getMinBufferSize(
                sampleRate,
                AudioFormat.CHANNEL_OUT_STEREO,
                AudioFormat.ENCODING_PCM_16BIT
            )

        if (minBufferSize <= 0) {
            return
        }

        // Jangan 8x terlalu besar.
        // Sekitar 4x sudah cukup untuk headroom
        // tanpa membuat latency berlebihan.
        val bufferSize =
            minBufferSize * 4

        try {

            audioTrack =
                AudioTrack.Builder()
                    .setAudioAttributes(
                        AudioAttributes.Builder()
                            .setUsage(
                                AudioAttributes.USAGE_GAME
                            )
                            .setContentType(
                                AudioAttributes.CONTENT_TYPE_MUSIC
                            )
                            .build()
                    )
                    .setAudioFormat(
                        AudioFormat.Builder()
                            .setEncoding(
                                AudioFormat.ENCODING_PCM_16BIT
                            )
                            .setSampleRate(
                                sampleRate
                            )
                            .setChannelMask(
                                AudioFormat.CHANNEL_OUT_STEREO
                            )
                            .build()
                    )
                    .setBufferSizeInBytes(
                        bufferSize
                    )
                    .setTransferMode(
                        AudioTrack.MODE_STREAM
                    )
                    .build()

        } catch (e: Exception) {

            e.printStackTrace()

            audioTrack = null
        }
    }


    // ========================================================
    // LOAD ROM
    // ========================================================

    fun loadRom(path: String): Boolean {

        isRomLoaded =
            engine.nativeLoadRom(path)

        if (isRomLoaded &&
            holder.surface.isValid &&
            !isRunning) {

            startLoop()
        }

        return isRomLoaded
    }


    // ========================================================
    // INPUT
    // ========================================================

    fun updateInput(keys: Int) {
        engine.nativeSendInput(keys)
    }


    // ========================================================
    // START
    // ========================================================

@Synchronized
private fun startLoop() {

    if (isRunning) {
        return
    }

    if (!holder.surface.isValid) {
        return
    }

    isRunning = true
    frameCount = 0L

    renderThread =
        Thread(
            this,
            "GbaRenderThread"
        ).apply {
            priority = Thread.NORM_PRIORITY
            start()
        }

    audioThread =
        Thread(
            { audioLoop() },
            "GbaAudioThread"
        ).apply {
            priority = Thread.NORM_PRIORITY
            start()
        }
}


    // ========================================================
    // STOP
    // ========================================================

    fun stopLoop() {

        isRunning = false

        try {

            renderThread?.interrupt()
            audioThread?.interrupt()

            renderThread?.join(500)
            audioThread?.join(500)

        } catch (_: InterruptedException) {
        }

        renderThread = null
        audioThread = null

        try {

            if (audioTrack?.state ==
                AudioTrack.STATE_INITIALIZED) {

                audioTrack?.pause()
                audioTrack?.flush()
            }

        } catch (_: Exception) {
        }
    }


    // ========================================================
    // RENDER LOOP
    // ========================================================

    override fun run() {

        var lastTime =
            System.nanoTime()

        val frameDurationNs =
            1_000_000_000L / 60L


        while (isRunning) {

            val now =
                System.nanoTime()

            val elapsed =
                now - lastTime


            if (elapsed >= frameDurationNs) {

                if (isRomLoaded &&
                    holder.surface.isValid) {

                    // HANYA emulator + video.
                    //
                    // Tidak ada AudioTrack.write()
                    // di sini lagi.
                    engine.nativeStepFrame()

                    frameCount++
                }

                lastTime = now

            } else {

                val sleepNs =
                    frameDurationNs - elapsed

                try {

                    if (sleepNs >
                        1_000_000L) {

                        Thread.sleep(
                            sleepNs /
                                    1_000_000L
                        )

                    } else {

                        Thread.yield()
                    }

                } catch (_: InterruptedException) {

                    break
                }
            }
        }
    }


    // ========================================================
    // AUDIO LOOP
    // ========================================================

private fun audioLoop() {

    val track =
        audioTrack ?: return

    try {
        if (track.state ==
            AudioTrack.STATE_INITIALIZED) {

            track.play()
        }
    } catch (_: Exception) {
        return
    }

    while (isRunning) {

        if (isFastForward) {
            Thread.sleep(5)
            continue
        }

        if (track.state !=
            AudioTrack.STATE_INITIALIZED) {
            return
        }

        try {

            val count =
                engine.nativeReadAudio(
                    audioBuffer
                )

            if (count > 0) {

                var offset = 0

                while (
                    offset < count &&
                    isRunning
                ) {

                    val written =
                        track.write(
                            audioBuffer,
                            offset,
                            count - offset,
                            AudioTrack.WRITE_BLOCKING
                        )

                    if (written <= 0) {
                        break
                    }

                    offset += written
                }

            } else {

                Thread.sleep(2)
            }

        } catch (
            _: InterruptedException
        ) {

            break

        } catch (_: Exception) {
        }
    }
}

    // ========================================================
    // SURFACE CREATED
    // ========================================================

    override fun surfaceCreated(
        holder: SurfaceHolder
    ) {

        engine.nativeSetSurface(
            holder.surface
        )

        if (isRomLoaded &&
            !isRunning) {

            startLoop()
        }
    }


    // ========================================================
    // SURFACE CHANGED
    // ========================================================

    override fun surfaceChanged(
        holder: SurfaceHolder,
        format: Int,
        width: Int,
        height: Int
    ) {

        engine.nativeSetSurface(
            holder.surface
        )
    }


    // ========================================================
    // SURFACE DESTROYED
    // ========================================================

    override fun surfaceDestroyed(
        holder: SurfaceHolder
    ) {

        stopLoop()

        engine.nativeSetSurface(null)
    }


    // ========================================================
    // DETACHED
    // ========================================================

    override fun onDetachedFromWindow() {

        stopLoop()

        try {

            audioTrack?.release()
            audioTrack = null

        } catch (_: Exception) {
        }

        super.onDetachedFromWindow()
    }
}