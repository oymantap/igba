package com.rycl.igba

import android.view.Surface

class GbaEngine {

    init {
        System.loadLibrary("igameboy")
    }

    external fun nativeInit()
    external fun nativeLoadRom(romPath: String): Boolean
    
    // NATIVE VIDEO & SURFACE MANAGEMENT
    external fun nativeSetSurface(surface: Surface?)
    external fun nativeStepFrame()

    external fun nativeSendInput(keys: Int)
    external fun nativeReadAudio(buffer: ShortArray): Int

    companion object {
        @JvmStatic
        external fun nativeDebugInfo(): String
    }
}
