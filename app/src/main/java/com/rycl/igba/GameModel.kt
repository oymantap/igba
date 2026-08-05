package com.rycl.igba

import android.graphics.Bitmap
import java.io.File

data class GameModel(
    val title: String,
    val file: File,
    var coverBitmap: Bitmap? = null
)
