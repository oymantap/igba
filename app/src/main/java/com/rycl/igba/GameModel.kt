package com.rycl.igba

import android.graphics.Bitmap
import java.io.File

data class GameModel(
    val title: String,
    val file: File?,
    val assetPath: String? = null,
    var coverBitmap: Bitmap? = null,
    val isAsset: Boolean = false
)
