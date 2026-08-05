package com.rycl.igba

import android.graphics.Bitmap

class GbaEngine {
    companion object {
        init {
            System.loadLibrary("igba_core")
        }
    }

    external fun nativeInit()
    external fun nativeLoadRom(path: String): Boolean
    external fun nativeStepFrame(bitmap: Bitmap)
    external fun nativeSendInput(keys: Int)
}
