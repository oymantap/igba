package com.rycl.igba

import android.graphics.Bitmap

class GbaEngine {

    init {
        System.loadLibrary("igameboy")
    }

    external fun nativeInit()
    external fun nativeLoadRom(romPath: String): Boolean
    external fun nativeStepFrame(bitmap: Bitmap)
    external fun nativeSendInput(keys: Int)

    // BARU
    external fun nativeReadAudio(buffer: ShortArray): Int

    companion object {
        @JvmStatic
        external fun nativeDebugInfo(): String
    }
}