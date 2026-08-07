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

    /*
     * 4096 samples = 2048 stereo frames.
     *
     * Kita gunakan buffer reusable supaya tidak membuat
     * object baru terus-menerus di audio thread.
     */
    private val audioBuffer = ShortArray(4096)

    /*
     * Audio diprefill sebelum AudioTrack.play().
     *
     * 8192 samples stereo = 4096 stereo frames.
     *
     * Pada 32 kHz kira-kira 128 ms audio.
     * Ini sengaja cukup besar untuk menghindari startup underrun.
     */
    private val prefillBuffer = ShortArray(8192)

    /*
     * Target minimum audio yang harus terkumpul sebelum
     * AudioTrack mulai dimainkan.
     */
    private val audioPrefillSamples = 8192

    init {
        holder.addCallback(this)

        engine.nativeInit()

        /*
         * JANGAN initAudio() di sini.
         *
         * Sample rate core baru diketahui setelah ROM diload.
         */
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

        /*
         * nativeLoadRom() sudah dipanggil sebelumnya,
         * jadi sekarang sample rate sudah berasal dari core.
         */
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

        /*
         * Jangan terlalu kecil.
         *
         * 4x minimum memberi headroom terhadap scheduler
         * Android tanpa membuat latency terlalu ekstrem.
         */
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

        /*
         * Load ROM dahulu.
         *
         * nativeLoadRom() akan mendapatkan sample rate
         * asli dari retro_system_av_info.
         */
        val loaded =
            engine.nativeLoadRom(path)

        isRomLoaded = loaded

        if (!loaded) {
            return false
        }

        /*
         * AudioTrack dibuat SETELAH core mengetahui sample rate.
         */
        audioTrack?.release()
        audioTrack = null

        initAudio()

        if (holder.surface.isValid && !isRunning) {
            startLoop()
        }

        return true
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

        if (!isRomLoaded) {
            return
        }

        isRunning = true

        frameCount = 0L

        /*
         * AudioTrack JANGAN play() di sini.
         *
         * Audio thread akan:
         *
         * retro_run()
         *      ↓
         * ring buffer terisi
         *      ↓
         * prefill
         *      ↓
         * AudioTrack.play()
         */
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
                { audioLoop() },
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

        while (isRunning) {

            val now =
                System.nanoTime()

            if (now >= nextFrameTime) {

                if (isRomLoaded &&
                    holder.surface.isValid) {

                    /*
                     * Satu retro_run().
                     *
                     * Video:
                     *   callback → frame_buffer
                     *
                     * Audio:
                     *   callback → audio ring buffer
                     */
                    engine.nativeStepFrame()

                    frameCount++
                }

                /*
                 * Jadwalkan frame berikutnya berdasarkan
                 * timeline, bukan sekadar now + frame duration.
                 *
                 * Ini mengurangi drift frame.
                 */
                nextFrameTime +=
                    1_000_000_000L / 60L

                /*
                 * Kalau thread tertinggal sangat jauh,
                 * jangan mencoba mengejar puluhan frame sekaligus.
                 */
                if (now - nextFrameTime >
                    100_000_000L) {

                    nextFrameTime =
                        now
                }

            } else {

                val sleepNs =
                    nextFrameTime - now

                try {

                    if (sleepNs >
                        2_000_000L) {

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
            audioTrack ?: return

        if (track.state !=
            AudioTrack.STATE_INITIALIZED) {

            return
        }

        /*
         * ====================================================
         * PHASE 1: PREFILL
         * ====================================================
         *
         * Jangan langsung play().
         *
         * Kita tunggu sampai emulator menghasilkan
         * sejumlah audio yang cukup.
         */
        var prefilled =
            0

        while (
            isRunning &&
            prefilled < audioPrefillSamples
        ) {

            if (isFastForward) {

                /*
                 * Saat fast-forward audio tidak dimainkan.
                 * Buang data supaya ring buffer tidak penuh.
                 */
                engine.nativeReadAudio(
                    audioBuffer
                )

                Thread.sleep(2)

                continue
            }

            try {

                val count =
                    engine.nativeReadAudio(
                        prefillBuffer
                    )

                if (count > 0) {

                    /*
                     * Simpan sementara ke AudioTrack.
                     *
                     * Kita belum memanggil play().
                     */
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

                        offset += written
                    }

                    prefilled += count

                } else {

                    /*
                     * Emulator belum menghasilkan cukup audio.
                     */
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

        /*
         * ====================================================
         * PHASE 2: START PLAYBACK
         * ====================================================
         *
         * Sekarang AudioTrack sudah diprime.
         */
        try {

            if (track.state ==
                AudioTrack.STATE_INITIALIZED) {

                track.play()

            } else {

                return
            }

        } catch (_: Exception) {

            return
        }

        /*
         * ====================================================
         * PHASE 3: NORMAL AUDIO
         * ====================================================
         */

        while (isRunning) {

            if (track.state !=
                AudioTrack.STATE_INITIALIZED) {

                return
            }

            /*
             * Fast-forward:
             *
             * jangan kirim audio 3x speed ke speaker.
             * Cukup drain ring buffer.
             */
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

                    /*
                     * Tidak ada audio saat ini.
                     *
                     * Jangan spin 100% CPU.
                     */
                    Thread.sleep(1)
                }

            } catch (
                _: InterruptedException
            ) {

                break

            } catch (_: Exception) {

                /*
                 * AudioTrack dapat berubah state ketika
                 * Surface/Activity dihancurkan.
                 */
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