package com.rycl.igba

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.view.MotionEvent
import android.view.View
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.widget.Button
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.floatingactionbutton.FloatingActionButton
import java.io.File
import java.io.FileOutputStream

class MainActivity : AppCompatActivity() {

    private lateinit var gbaView: GbaView
    private lateinit var btnOpenRom: FloatingActionButton
    private var currentKeys = 0

    // Standard Bitmask Libretro Joypad Constants (Disesuaikan dengan libretro.h)
    companion object {
        const val DEVICE_ID_JOYPAD_B = 0
        const val DEVICE_ID_JOYPAD_Y = 1
        const val DEVICE_ID_JOYPAD_SELECT = 2
        const val DEVICE_ID_JOYPAD_START = 3
        const val DEVICE_ID_JOYPAD_UP = 4
        const val DEVICE_ID_JOYPAD_DOWN = 5
        const val DEVICE_ID_JOYPAD_LEFT = 6
        const val DEVICE_ID_JOYPAD_RIGHT = 7
        const val DEVICE_ID_JOYPAD_A = 8
        const val DEVICE_ID_JOYPAD_X = 9
        const val DEVICE_ID_JOYPAD_L = 10
        const val DEVICE_ID_JOYPAD_R = 11
    }

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

        hideSystemUI()

        gbaView = findViewById(R.id.gba_view)
        btnOpenRom = findViewById(R.id.btn_open_rom)

        btnOpenRom.setOnClickListener {
            openFilePicker()
        }

        setupControllerButtons()
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun bindButton(buttonId: Int, bitmaskId: Int) {
        val btn = findViewById<Button>(buttonId)
        btn?.setOnTouchListener { _, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    handleButtonTouch(bitmaskId, true)
                    true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    handleButtonTouch(bitmaskId, false)
                    true
                }
                else -> false
            }
        }
    }

    private fun setupControllerButtons() {
        bindButton(R.id.btn_up, DEVICE_ID_JOYPAD_UP)
        bindButton(R.id.btn_down, DEVICE_ID_JOYPAD_DOWN)
        bindButton(R.id.btn_left, DEVICE_ID_JOYPAD_LEFT)
        bindButton(R.id.btn_right, DEVICE_ID_JOYPAD_RIGHT)
        bindButton(R.id.btn_a, DEVICE_ID_JOYPAD_A)
        bindButton(R.id.btn_b, DEVICE_ID_JOYPAD_B)
        bindButton(R.id.btn_l, DEVICE_ID_JOYPAD_L)
        bindButton(R.id.btn_r, DEVICE_ID_JOYPAD_R)
        bindButton(R.id.btn_start, DEVICE_ID_JOYPAD_START)
        bindButton(R.id.btn_select, DEVICE_ID_JOYPAD_SELECT)
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) {
            hideSystemUI()
        }
    }

    private fun hideSystemUI() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            window.insetsController?.let { controller ->
                controller.hide(WindowInsets.Type.statusBars() or WindowInsets.Type.navigationBars())
                controller.systemBarsBehavior = WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            }
        } else {
            @Suppress("DEPRECATION")
            window.decorView.systemUiVisibility = (
                View.SYSTEM_UI_FLAG_FULLSCREEN
                or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                or View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                or View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
            )
        }
    }

    private fun openFilePicker() {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "*/*"
        }
        openRomLauncher.launch(intent)
    }

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

            val absolutePath = tempFile.absolutePath
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
