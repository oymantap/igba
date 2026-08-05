package com.rycl.igba

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.widget.Button
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import com.rycl.igba.R // <-- FIXED: Import R ditambahkan di sini
import java.io.File
import java.io.FileOutputStream

class MainActivity : AppCompatActivity() {

    private lateinit var gbaView: GbaView
    private var currentKeys = 0

    // Launcher buat buka File Picker Android
    private val openRomLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            result.data?.data?.let { uri ->
                loadRomFromUri(uri)
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        gbaView = findViewById(R.id.gba_view)

        // Otomatis buka File Picker saat aplikasi dibuka
        openFilePicker()
    }

    private fun openFilePicker() {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "*/*" // Mengizinkan milih file game
        }
        openRomLauncher.launch(intent)
    }

    // Salin URI dari File Picker ke cache lokal biar C++ (NDK) bisa baca path aslinya dengan aman
    private fun loadRomFromUri(uri: Uri) {
        try {
            val inputStream = contentResolver.openInputStream(uri)
            val tempFile = File(cacheDir, "current_game.gba")
            val outputStream = FileOutputStream(tempFile)

            inputStream?.use { input ->
                outputStream.use { output ->
                    input.copyTo(output)
                }
            }

            // Path lokal aman yang bisa dibaca C++
            val absolutePath = tempFile.absolutePath
            
            // Panggil GbaView buat muat ROM
            val isLoaded = gbaView.loadRom(absolutePath)
            if (isLoaded) {
                Toast.makeText(this, "ROM Berhasil Dimuat!", Toast.LENGTH_SHORT).show()
            } else {
                Toast.makeText(this, "Gagal memuat ROM GBA", Toast.LENGTH_LONG).show()
            }
        } catch (e: Exception) {
            e.printStackTrace()
            Toast.makeText(this, "Error: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }

    // Fungsi helper buat kontroler (D-Pad & Button)
    fun handleButtonTouch(bitmaskId: Int, isPressed: Boolean) {
        val mask = 1 shl bitmaskId
        if (isPressed) {
            currentKeys = currentKeys or mask
        } else {
            currentKeys = currentKeys and mask.inv()
        }
        gbaView.updateInput(currentKeys)
    }
}
