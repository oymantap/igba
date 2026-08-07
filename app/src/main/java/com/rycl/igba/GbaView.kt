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
) : SurfaceView(
    context,
    attrs,
    defStyleAttr
),
    Runnable,
    SurfaceHolder.Callback {

    private val engine =
        GbaEngine()

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


    // ========================================================
    // AUDIO BUFFER
    // ========================================================

    private val audioBuffer =
        ShortArray(4096)

    private val prefillBuffer =
        ShortArray(8192)

    private val audioPrefillSamples =
        8192


    // ========================================================
    // INIT
    // ========================================================

    init {

        holder.addCallback(this)

        engine.nativeInit()
    }


    // ========================================================
    // FAST FORWARD
    // ========================================================

    fun setFastForward(
        enabled: Boolean
    ) {

        isFastForward =
            enabled
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

        if (sampleRate <= 0) {
            return
        }


        val minBufferSize =
            AudioTrack.getMinBufferSize(
                sampleRate,
                AudioFormat.CHANNEL_OUT_STEREO,
                AudioFormat.ENCODING_PCM_16BIT
            )


        if (minBufferSize <= 0) {
            return
        }


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

    fun loadRom(
        path: String
    ): Boolean {

        val loaded =
            engine.nativeLoadRom(path)

        isRomLoaded =
            loaded


        if (!loaded) {
            return false
        }


        /*
         * ROM sudah diload.
         * Sekarang sample rate core sudah diketahui.
         */

        try {
            audioTrack?.release()
        } catch (_: Exception) {
        }

        audioTrack = null

        initAudio()


        if (
            holder.surface.isValid &&
            !isRunning
        ) {

            startLoop()
        }


        return true
    }


    // ========================================================
    // INPUT
    // ========================================================

    fun updateInput(
        keys: Int
    ) {

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


        if (!isRomLoaded) {
            return
        }


        isRunning = true

        frameCount = 0L


        renderThread =
            Thread(
                this,
                "GbaRenderThread"
            ).apply {

                priority =
                    Thread.NORM_PRIORITY

                start()
            }


        audioThread =
            Thread(
                {
                    audioLoop()
                },
                "GbaAudioThread"
            ).apply {

                priority =
                    Thread.NORM_PRIORITY

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

            audioTrack?.pause()

            audioTrack?.flush()

        } catch (_: Exception) {
        }
    }


    // ========================================================
    // RENDER LOOP
    // ========================================================

    override fun run() {

        var nextFrameTime =
            System.nanoTime()

        val frameDuration =
            1_000_000_000L / 60L


        while (isRunning) {

            val now =
                System.nanoTime()


            if (now >= nextFrameTime) {

                if (
                    isRomLoaded &&
                    holder.surface.isValid
                ) {

                    engine.nativeStepFrame()

                    frameCount++
                }


                nextFrameTime +=
                    frameDuration


                /*
                 * Jangan mencoba mengejar frame
                 * terlalu jauh setelah stall.
                 */

                if (
                    now - nextFrameTime >
                    100_000_000L
                ) {

                    nextFrameTime =
                        now
                }

            } else {

                val sleepNs =
                    nextFrameTime - now


                try {

                    if (
                        sleepNs >
                        2_000_000L
                    ) {

                        Thread.sleep(
                            sleepNs /
                                1_000_000L
                        )

                    } else {

                        Thread.yield()
                    }

                } catch (
                    _: InterruptedException
                ) {

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
            audioTrack
                ?: return


        if (
            track.state !=
            AudioTrack.STATE_INITIALIZED
        ) {

            return
        }


        // ====================================================
        // PREFILL
        // ====================================================

        var prefilled =
            0


        while (
            isRunning &&
            prefilled < audioPrefillSamples
        ) {

            if (isFastForward) {

                try {

                    engine.nativeReadAudio(
                        audioBuffer
                    )

                    Thread.sleep(2)

                } catch (
                    _: InterruptedException
                ) {

                    return
                }

                continue
            }


            try {

                val count =
                    engine.nativeReadAudio(
                        prefillBuffer
                    )


                if (count > 0) {

                    var offset = 0


                    while (
                        offset < count &&
                        isRunning
                    ) {

                        val written =
                            track.write(
                                prefillBuffer,
                                offset,
                                count - offset,
                                AudioTrack.WRITE_BLOCKING
                            )


                        if (written <= 0) {
                            return
                        }


                        offset +=
                            written
                    }


                    prefilled +=
                        count

                } else {

                    Thread.sleep(1)
                }

            } catch (
                _: InterruptedException
            ) {

                return

            } catch (_: Exception) {

                return
            }
        }


        if (!isRunning) {
            return
        }


        // ====================================================
        // START PLAYBACK
        // ====================================================

        try {

            if (
                track.state ==
                AudioTrack.STATE_INITIALIZED
            ) {

                track.play()

            } else {

                return
            }

        } catch (_: Exception) {

            return
        }


        // ====================================================
        // NORMAL AUDIO
        // ====================================================

        while (isRunning) {

            if (
                track.state !=
                AudioTrack.STATE_INITIALIZED
            ) {

                return
            }


            // ------------------------------------------------
            // FAST FORWARD
            // ------------------------------------------------

            if (isFastForward) {

                try {

                    engine.nativeReadAudio(
                        audioBuffer
                    )

                    Thread.sleep(2)

                } catch (
                    _: InterruptedException
                ) {

                    break
                }

                continue
            }


            // ------------------------------------------------
            // NORMAL
            // ------------------------------------------------

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


                        offset +=
                            written
                    }

                } else {

                    Thread.sleep(1)
                }

            } catch (
                _: InterruptedException
            ) {

                break

            } catch (_: Exception) {

                // AudioTrack dapat berubah state
                // saat Activity/Surface dihancurkan.
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


        if (
            isRomLoaded &&
            !isRunning
        ) {

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