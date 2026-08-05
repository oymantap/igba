package com.rycl.igba

import android.graphics.Bitmap

class GbaEngine {

    init {
        // Nama library disesuaikan persis dengan add_library(igameboy ...) di CMakeLists.txt
        System.loadLibrary("igameboy")
    }

    external fun nativeInit()
    external fun nativeLoadRom(romPath: String): Boolean
    external fun nativeStepFrame(bitmap: Bitmap)
    external fun nativeSendInput(keys: Int)
}
