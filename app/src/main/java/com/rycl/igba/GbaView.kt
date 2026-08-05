package com.rycl.igba

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Rect
import android.util.AttributeSet
import android.view.SurfaceHolder
import android.view.SurfaceView

class GbaView(context: Context, attrs: AttributeSet?) : SurfaceView(context, attrs), SurfaceHolder.Callback, Runnable {

    private var isRunning = false
    private var isRomLoaded = false
    private var renderThread: Thread? = null
    
    private val bitmap = Bitmap.createBitmap(240, 160, Bitmap.Config.RGB_565)
    private val engine = GbaEngine()

    init {
        holder.addCallback(this)
        engine.nativeInit()
    }

    fun loadRom(path: String): Boolean {
        isRomLoaded = engine.nativeLoadRom(path)
        return isRomLoaded
    }

    fun updateInput(keys: Int) {
        engine.nativeSendInput(keys)
    }

    override fun surfaceCreated(holder: SurfaceHolder) {
        isRunning = true
        renderThread = Thread(this)
        renderThread?.start()
    }

    override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {}

    override fun surfaceDestroyed(holder: SurfaceHolder) {
        isRunning = false
        var retry = true
        while (retry) {
            try {
                renderThread?.join()
                retry = false
            } catch (e: InterruptedException) {
                e.printStackTrace()
            }
        }
    }

    override fun run() {
        val targetFps = 60
        val targetFrameTime = 1000 / targetFps // ~16ms per frame

        while (isRunning) {
            val startTime = System.currentTimeMillis()

            // Hanya step frame jika ROM sudah berhasil di-load
            if (isRomLoaded && holder.surface.isValid) {
                engine.nativeStepFrame(bitmap)

                val canvas = holder.lockCanvas()
                if (canvas != null) {
                    // Scale gambar GBA (240x160) memenuhi layar SurfaceView
                    val destRect = Rect(0, 0, canvas.width, canvas.height)
                    canvas.drawBitmap(bitmap, null, destRect, null)
                    holder.unlockCanvasAndPost(canvas)
                }
            }

            // Cap Frame Rate ke 60 FPS
            val timeTaken = System.currentTimeMillis() - startTime
            val sleepTime = targetFrameTime - timeTaken
            if (sleepTime > 0) {
                try {
                    Thread.sleep(sleepTime)
                } catch (e: Exception) {
                    e.printStackTrace()
                }
            }
        }
    }
}
