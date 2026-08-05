package com.rycl.igba

import android.graphics.Bitmap

class GbaEngine {

    init {
        System.loadLibrary("igameboy") // sesuaikan jika nama lib NDK kamu beda di CMakeLists
    }

    external fun nativeInit()
    external fun nativeLoadRom(romPath: String): Boolean
    external fun nativeStepFrame(bitmap: Bitmap)
    external fun nativeSendInput(keys: Int)
}
