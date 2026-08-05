package com.rycl.igba

import android.app.Activity
import android.content.Intent
import android.graphics.Rect
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.view.WindowInsets
import android.view.WindowInsetsController
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

    private data class ControllerButton(
        val view: View,
        val bitmask: Int,
        var isPressed: Boolean = false
    )

    private val registeredButtons = mutableListOf<ControllerButton>()

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

    private val openRomLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            result.data?.data?.let { uri -> loadRomFromUri(uri) }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        hideSystemUI()

        gbaView = findViewById(R.id.gba_view)
        btnOpenRom = findViewById(R.id.btn_open_rom)

        btnOpenRom.setOnClickListener { openFilePicker() }

        setupController()
    }

    private fun registerButton(viewId: Int, bitmask: Int) {
        val btnView = findViewById<View>(viewId) ?: return
        registeredButtons.add(ControllerButton(btnView, bitmask))
    }

    private fun setupController() {
        registerButton(R.id.btn_up, DEVICE_ID_JOYPAD_UP)
        registerButton(R.id.btn_down, DEVICE_ID_JOYPAD_DOWN)
        registerButton(R.id.btn_left, DEVICE_ID_JOYPAD_LEFT)
        registerButton(R.id.btn_right, DEVICE_ID_JOYPAD_RIGHT)
        registerButton(R.id.btn_a, DEVICE_ID_JOYPAD_A)
        registerButton(R.id.btn_b, DEVICE_ID_JOYPAD_B)
        registerButton(R.id.btn_l, DEVICE_ID_JOYPAD_L)
        registerButton(R.id.btn_r, DEVICE_ID_JOYPAD_R)
        registerButton(R.id.btn_start, DEVICE_ID_JOYPAD_START)
        registerButton(R.id.btn_select, DEVICE_ID_JOYPAD_SELECT)
    }

    override fun dispatchTouchEvent(ev: MotionEvent?): Boolean {
        ev?.let { handleGlobalTouch(it) }
        return super.dispatchTouchEvent(ev)
    }

    private fun handleGlobalTouch(event: MotionEvent) {
        val activeMasks = mutableSetOf<Int>()
        val tempRect = Rect()

        for (pointerIndex in 0 until event.pointerCount) {
            val action = event.actionMasked
            if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_POINTER_UP) {
                if (pointerIndex == event.actionIndex) continue
            }
            if (action == MotionEvent.ACTION_CANCEL) continue

            val x = event.getX(pointerIndex).toInt()
            val y = event.getY(pointerIndex).toInt()

            for (btn in registeredButtons) {
                btn.view.getGlobalVisibleRect(tempRect)
                if (tempRect.contains(x, y)) {
                    activeMasks.add(btn.bitmask)
                }
            }
        }

        var newKeys = 0
        for (btn in registeredButtons) {
            val shouldBePressed = activeMasks.contains(btn.bitmask)
            if (btn.isPressed != shouldBePressed) {
                btn.isPressed = shouldBePressed
                updateButtonVisuals(btn.view, shouldBePressed)
            }
            if (btn.isPressed) {
                newKeys = newKeys or (1 shl btn.bitmask)
            }
        }

        if (currentKeys != newKeys) {
            currentKeys = newKeys
            gbaView.updateInput(currentKeys)
        }
    }

    private fun updateButtonVisuals(view: View, isPressed: Boolean) {
        if (isPressed) {
            view.animate().scaleX(0.88f).scaleY(0.88f).setDuration(50).start()
            view.alpha = 0.5f
            view.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
        } else {
            view.animate().scaleX(1.0f).scaleY(1.0f).setDuration(50).start()
            view.alpha = 1.0f
        }
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) hideSystemUI()
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
                Toast.makeText(this, "ROM Dimuat!", Toast.LENGTH_SHORT).show()
            } else {
                Toast.makeText(this, "Gagal memuat ROM GBA", Toast.LENGTH_LONG).show()
            }
        } catch (e: Exception) {
            e.printStackTrace()
            Toast.makeText(this, "Error: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }
}
