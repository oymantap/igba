package com.rycl.igba

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Rect
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.view.animation.AlphaAnimation
import android.widget.ImageView
import android.widget.RelativeLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.button.MaterialButton
import com.google.android.material.floatingactionbutton.FloatingActionButton
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream

class MainActivity : AppCompatActivity() {

    private lateinit var gbaView: GbaView
    private lateinit var emulatorLayout: View
    private lateinit var ps4DashboardLayout: RelativeLayout
    private lateinit var ps4SplashOverlay: RelativeLayout
    private lateinit var rvPs4Games: RecyclerView
    private lateinit var tvSelectedGameTitle: TextView
    private lateinit var tvSelectedGameSub: TextView
    private lateinit var btnBackPs4: MaterialButton
    private lateinit var btnManualPickRom: MaterialButton

    private val gamesList = mutableListOf<GameModel>()
    private lateinit var ps4Adapter: Ps4GameAdapter
    private var selectedGameForCover: GameModel? = null

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

    // Permission Launcher for External Storage (Android 11+)
    private val storageManagerLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) {
        checkAndCreateFolders()
    }

    // Permission Launcher for Legacy Storage (Android 10 and lower)
    private val legacyStorageLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        if (permissions.values.all { it }) {
            checkAndCreateFolders()
        } else {
            Toast.makeText(this, "Izin penyimpanan dibutuhkan!", Toast.LENGTH_LONG).show()
        }
    }

    // Picker Launcher for Manual ROM
    private val openRomLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            result.data?.data?.let { uri -> loadRomFromUri(uri) }
        }
    }

    // Picker Launcher for Custom Cover Art
    private val pickCoverLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            result.data?.data?.let { uri -> saveCustomCoverUri(uri) }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        hideSystemUI()
        initViews()
        setupController()

        // 1. Request Permission & Setup Folder Directory First
        requestStoragePermissions()
    }

    private fun initViews() {
        gbaView = findViewById(R.id.gba_view)
        emulatorLayout = findViewById(R.id.emulator_layout)
        ps4DashboardLayout = findViewById(R.id.ps4_dashboard_layout)
        ps4SplashOverlay = findViewById(R.id.ps4_splash_overlay)
        rvPs4Games = findViewById(R.id.rv_ps4_games)
        tvSelectedGameTitle = findViewById(R.id.tv_selected_game_title)
        tvSelectedGameSub = findViewById(R.id.tv_selected_game_sub)
        btnBackPs4 = findViewById(R.id.btn_back_ps4)
        btnManualPickRom = findViewById(R.id.btn_manual_pick_rom)

        btnManualPickRom.setOnClickListener { openFilePicker() }
        btnBackPs4.setOnClickListener { showPs4Dashboard() }

        ps4Adapter = Ps4GameAdapter(
            games = gamesList,
            onItemClick = { game -> launchGame(game) },
            onItemLongClick = { game -> promptChangeCover(game) },
            onItemFocused = { game ->
                tvSelectedGameTitle.text = game.title
                tvSelectedGameSub.text = "File: ${game.file.name}"
            }
        )
        rvPs4Games.adapter = ps4Adapter
    }

    // ================= 1 & 2. PERMISSIONS & FOLDER IGBA =================

    private fun requestStoragePermissions() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            if (!Environment.isExternalStorageManager()) {
                val intent = Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION).apply {
                    data = Uri.parse("package:$packageName")
                }
                storageManagerLauncher.launch(intent)
            } else {
                checkAndCreateFolders()
            }
        } else {
            val permissions = arrayOf(
                Manifest.permission.READ_EXTERNAL_STORAGE,
                Manifest.permission.WRITE_EXTERNAL_STORAGE
            )
            legacyStorageLauncher.launch(permissions)
        }
    }

    private fun checkAndCreateFolders() {
        val docsDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOCUMENTS)
        val igbaFolder = File(docsDir, "igba")
        val coversFolder = File(igbaFolder, "covers")

        if (!igbaFolder.exists()) {
            igbaFolder.mkdirs()
        }
        if (!coversFolder.exists()) {
            coversFolder.mkdirs()
        }

        // Start Boot PS4 Animation Overlay
        playPs4BootAnimation()
    }

    // ================= 3. PS4 BOOT ANIMATION =================

    private fun playPs4BootAnimation() {
        ps4SplashOverlay.visibility = View.VISIBLE

        val tvLogo = findViewById<TextView>(R.id.tv_ps4_logo)
        tvLogo.alpha = 0f
        tvLogo.animate().alpha(1f).setDuration(1200).start()

        Handler(Looper.getMainLooper()).postDelayed({
            val fadeOut = AlphaAnimation(1f, 0f).apply {
                duration = 800
            }
            ps4SplashOverlay.startAnimation(fadeOut)
            ps4SplashOverlay.visibility = View.GONE

            // Show PS4 Dashboard Screen
            showPs4Dashboard()
        }, 2800)
    }

    // ================= 4. PS4 DASHBOARD & ROM SCANNING =================

    private fun showPs4Dashboard() {
        gbaView.stopLoop()
        emulatorLayout.visibility = View.GONE
        ps4DashboardLayout.visibility = View.VISIBLE

        scanIgbaDirectory()
    }

    @SuppressLint("NotifyDataSetChanged")
    private fun scanIgbaDirectory() {
        gamesList.clear()
        val docsDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOCUMENTS)
        val igbaFolder = File(docsDir, "igba")
        val coversFolder = File(igbaFolder, "covers")

        if (igbaFolder.exists() && igbaFolder.isDirectory) {
            val files = igbaFolder.listFiles { file ->
                val name = file.name.lowercase()
                name.endsWith(".gba") || name.endsWith(".zip")
            }

            files?.forEach { romFile ->
                val gameTitle = romFile.nameWithoutExtension
                val coverImage = File(coversFolder, "$gameTitle.png")
                var bitmap: Bitmap? = null

                if (coverImage.exists()) {
                    bitmap = BitmapFactory.decodeFile(coverImage.absolutePath)
                }

                gamesList.add(GameModel(gameTitle, romFile, bitmap))
            }
        }

        ps4Adapter.notifyDataSetChanged()

        if (gamesList.isNotEmpty()) {
            val firstGame = gamesList[0]
            tvSelectedGameTitle.text = firstGame.title
            tvSelectedGameSub.text = "File: ${firstGame.file.name}"
        } else {
            tvSelectedGameTitle.text = "No Games Found"
            tvSelectedGameSub.text = "Put .gba files in Documents/igba/ directory"
        }
    }

    private fun promptChangeCover(game: GameModel) {
        selectedGameForCover = game
        AlertDialog.Builder(this)
            .setTitle("Custom Cover Art")
            .setMessage("Change thumbnail art for ${game.title}?")
            .setPositiveButton("Select Image") { _, _ ->
                val intent = Intent(Intent.ACTION_PICK).apply {
                    type = "image/*"
                }
                pickCoverLauncher.launch(intent)
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun saveCustomCoverUri(uri: Uri) {
        val game = selectedGameForCover ?: return
        try {
            val docsDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOCUMENTS)
            val coversFolder = File(docsDir, "igba/covers")
            val targetCover = File(coversFolder, "${game.title}.png")

            contentResolver.openInputStream(uri)?.use { input ->
                FileOutputStream(targetCover).use { output ->
                    input.copyTo(output)
                }
            }

            Toast.makeText(this, "Cover Updated!", Toast.LENGTH_SHORT).show()
            scanIgbaDirectory()
        } catch (e: Exception) {
            e.printStackTrace()
            Toast.makeText(this, "Failed saving cover", Toast.LENGTH_SHORT).show()
        }
    }

    // ================= 5. SEAMLESS EMULATOR LAUNCH =================

    private fun launchGame(game: GameModel) {
        ps4DashboardLayout.visibility = View.GONE
        emulatorLayout.visibility = View.VISIBLE

        val isLoaded = gbaView.loadRom(game.file.absolutePath)
        if (isLoaded) {
            Toast.makeText(this, "Playing: ${game.title}", Toast.LENGTH_SHORT).show()
        } else {
            Toast.makeText(this, "Gagal memuat ROM GBA", Toast.LENGTH_LONG).show()
        }
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

            ps4DashboardLayout.visibility = View.GONE
            emulatorLayout.visibility = View.VISIBLE

            val isLoaded = gbaView.loadRom(tempFile.absolutePath)
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

    // ================= CONTROLLER ENGINE INTEGRATION =================

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
        if (emulatorLayout.visibility != View.VISIBLE) return

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
}
