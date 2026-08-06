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
import android.widget.RelativeLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.button.MaterialButton
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

class MainActivity : AppCompatActivity() {

    private lateinit var gbaView: GbaView
    private lateinit var emulatorLayout: View
    private lateinit var igbaDashboardLayout: RelativeLayout
    private lateinit var igbaSplashOverlay: RelativeLayout
    private lateinit var rvIgbaGames: RecyclerView
    private lateinit var tvUserProfile: TextView
    private lateinit var tvSelectedGameTitle: TextView
    private lateinit var tvSelectedGameSub: TextView
    private lateinit var btnBackIgba: MaterialButton
    private lateinit var btnManualPickRom: MaterialButton
    private lateinit var btnSettings: MaterialButton
    
    // CONTROLS & ANALOG
    private lateinit var dpadContainer: View
    private lateinit var analogContainer: View
    private lateinit var analogThumb: View
    private lateinit var btnToggleAnalog: MaterialButton
    private lateinit var btnFastForward: MaterialButton
    private var isAnalogMode = false

    private var analogKeysMask = 0

    // EXIT GUARD
    private var backPressedOnce = false
    private var currentDialog: AlertDialog? = null

    // DEBUG HUD
    private lateinit var tvDebugHud: TextView
    private val debugHandler = Handler(Looper.getMainLooper())
    private var isDebugVisible = false

    private val secretCheatCode = listOf(
        DEVICE_ID_JOYPAD_UP, DEVICE_ID_JOYPAD_DOWN,
        DEVICE_ID_JOYPAD_UP, DEVICE_ID_JOYPAD_DOWN,
        DEVICE_ID_JOYPAD_A, DEVICE_ID_JOYPAD_B,
        DEVICE_ID_JOYPAD_L, DEVICE_ID_JOYPAD_R
    )
    private val keyHistory = ArrayDeque<Int>()

    private val gamesList = mutableListOf<GameModel>()
    private lateinit var ps4Adapter: Ps4GameAdapter
    private var selectedGameForCover: GameModel? = null

    private var currentKeys = 0

    private data class ControllerButton(
        val view: View,
        val bitmaskList: List<Int>,
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

    private val storageManagerLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { checkAndCreateFolders() }

    private val legacyStorageLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        if (permissions.values.all { it }) {
            checkAndCreateFolders()
        } else {
            Toast.makeText(this, "Izin penyimpanan dibutuhkan!", Toast.LENGTH_LONG).show()
        }
    }

    private val openRomLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            result.data?.data?.let { uri -> loadRomFromUri(uri) }
        }
    }

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
        setupAnalogTouch()
        setupBackButtonHandler()
        requestStoragePermissions()
    }

    private fun initViews() {
        gbaView = findViewById(R.id.gba_view)
        emulatorLayout = findViewById(R.id.emulator_layout)
        igbaDashboardLayout = findViewById(R.id.igba_dashboard_layout)
        igbaSplashOverlay = findViewById(R.id.igba_splash_overlay)
        rvIgbaGames = findViewById(R.id.rv_igba_games)
        tvUserProfile = findViewById(R.id.tv_user_profile)
        tvSelectedGameTitle = findViewById(R.id.tv_selected_game_title)
        tvSelectedGameSub = findViewById(R.id.tv_selected_game_sub)
        btnBackIgba = findViewById(R.id.btn_back_igba)
        btnManualPickRom = findViewById(R.id.btn_manual_pick_rom)
        btnSettings = findViewById(R.id.btn_settings)

        dpadContainer = findViewById(R.id.dpad_container)
        analogContainer = findViewById(R.id.analog_container)
        analogThumb = findViewById(R.id.analog_thumb)
        btnToggleAnalog = findViewById(R.id.btn_toggle_analog)
        btnFastForward = findViewById(R.id.btn_fast_forward)

        tvDebugHud = findViewById(R.id.tv_debug_hud)

        btnManualPickRom.setOnClickListener { openFilePicker() }
        btnBackIgba.setOnClickListener { showIgbaDashboard() }
        
        btnSettings.setOnClickListener {
            try {
                val intent = Intent(this, Class.forName("com.rycl.igba.SettingsActivity"))
                startActivity(intent)
            } catch (e: Exception) {
                Toast.makeText(this, "SettingsActivity belum dibuat!", Toast.LENGTH_SHORT).show()
            }
        }

        btnToggleAnalog.setOnClickListener {
            isAnalogMode = !isAnalogMode
            if (isAnalogMode) {
                dpadContainer.visibility = View.GONE
                analogContainer.visibility = View.VISIBLE
                btnToggleAnalog.text = "🎯"
            } else {
                dpadContainer.visibility = View.VISIBLE
                analogContainer.visibility = View.GONE
                btnToggleAnalog.text = "🔘"
                resetAnalogThumb()
            }
        }

        // Fast Forward Button Listener (Audio Resample Fix)
        btnFastForward.setOnTouchListener { _, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    gbaView.setFastForward(true)
                    btnFastForward.alpha = 0.5f
                    true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    gbaView.setFastForward(false)
                    btnFastForward.alpha = 1.0f
                    true
                }
                else -> false
            }
        }

        ps4Adapter = Ps4GameAdapter(
            games = gamesList,
            onItemClick = { game -> launchGame(game) },
            onItemLongClick = { game -> promptChangeCover(game) },
            onItemFocused = { game ->
                tvUserProfile.text = "🎮 ${game.title}"
                tvSelectedGameTitle.text = game.title
                tvSelectedGameSub.text = if (game.isAsset) "Built-in IGBA HEN Game" else "File: ${game.file?.name}"
            }
        )
        rvIgbaGames.adapter = ps4Adapter
    }

    private fun registerButton(viewId: Int, vararg bitmasks: Int) {
        val btnView = findViewById<View>(viewId) ?: return
        registeredButtons.add(ControllerButton(btnView, bitmasks.toList()))
    }

    private fun setupController() {
        registeredButtons.clear()
        // Single Direction D-Pad
        registerButton(R.id.btn_up, DEVICE_ID_JOYPAD_UP)
        registerButton(R.id.btn_down, DEVICE_ID_JOYPAD_DOWN)
        registerButton(R.id.btn_left, DEVICE_ID_JOYPAD_LEFT)
        registerButton(R.id.btn_right, DEVICE_ID_JOYPAD_RIGHT)

        // Diagonal D-Pad Hotspots (Aktifkan 2 tombol sekaligus)
        registerButton(R.id.btn_up_left, DEVICE_ID_JOYPAD_UP, DEVICE_ID_JOYPAD_LEFT)
        registerButton(R.id.btn_up_right, DEVICE_ID_JOYPAD_UP, DEVICE_ID_JOYPAD_RIGHT)
        registerButton(R.id.btn_down_left, DEVICE_ID_JOYPAD_DOWN, DEVICE_ID_JOYPAD_LEFT)
        registerButton(R.id.btn_down_right, DEVICE_ID_JOYPAD_DOWN, DEVICE_ID_JOYPAD_RIGHT)

        // Action Buttons
        registerButton(R.id.btn_a, DEVICE_ID_JOYPAD_A)
        registerButton(R.id.btn_b, DEVICE_ID_JOYPAD_B)
        registerButton(R.id.btn_l, DEVICE_ID_JOYPAD_L)
        registerButton(R.id.btn_r, DEVICE_ID_JOYPAD_R)
        registerButton(R.id.btn_start, DEVICE_ID_JOYPAD_START)
        registerButton(R.id.btn_select, DEVICE_ID_JOYPAD_SELECT)
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun setupAnalogTouch() {
        analogContainer.setOnTouchListener { _, event ->
            if (!isAnalogMode) return@setOnTouchListener false

            val width = analogContainer.width.toFloat()
            val height = analogContainer.height.toFloat()
            val centerX = width / 2f
            val centerY = height / 2f
            val maxRadius = (width / 2f) - (analogThumb.width / 2f)

            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN, MotionEvent.ACTION_MOVE -> {
                    val touchX = event.x - centerX
                    val touchY = event.y - centerY

                    val distance = sqrt(touchX * touchX + touchY * touchY)
                    val angle = atan2(touchY.toDouble(), touchX.toDouble())

                    val constrainedDistance = distance.coerceAtMost(maxRadius)
                    val thumbX = constrainedDistance * cos(angle).toFloat()
                    val thumbY = constrainedDistance * sin(angle).toFloat()

                    // Gerakkan visual thumb
                    analogThumb.translationX = thumbX
                    analogThumb.translationY = thumbY

                    // Hitung Mask Input Arah berdasarkan Sudut (Angle)
                    if (distance > 15f) { // Deadzone threshold
                        var newAnalogMask = 0
                        val deg = Math.toDegrees(angle)

                        // 8-Way Direction Mapping
                        if (deg >= -112.5 && deg <= -67.5) {
                            newAnalogMask = newAnalogMask or (1 shl DEVICE_ID_JOYPAD_UP)
                        } else if (deg >= -67.5 && deg <= -22.5) {
                            newAnalogMask = newAnalogMask or (1 shl DEVICE_ID_JOYPAD_UP) or (1 shl DEVICE_ID_JOYPAD_RIGHT)
                        } else if (deg >= -22.5 && deg <= 22.5) {
                            newAnalogMask = newAnalogMask or (1 shl DEVICE_ID_JOYPAD_RIGHT)
                        } else if (deg >= 22.5 && deg <= 67.5) {
                            newAnalogMask = newAnalogMask or (1 shl DEVICE_ID_JOYPAD_DOWN) or (1 shl DEVICE_ID_JOYPAD_RIGHT)
                        } else if (deg >= 67.5 && deg <= 112.5) {
                            newAnalogMask = newAnalogMask or (1 shl DEVICE_ID_JOYPAD_DOWN)
                        } else if (deg >= 112.5 && deg <= 157.5) {
                            newAnalogMask = newAnalogMask or (1 shl DEVICE_ID_JOYPAD_DOWN) or (1 shl DEVICE_ID_JOYPAD_LEFT)
                        } else if (deg >= 157.5 || deg <= -157.5) {
                            newAnalogMask = newAnalogMask or (1 shl DEVICE_ID_JOYPAD_LEFT)
                        } else if (deg >= -157.5 && deg <= -112.5) {
                            newAnalogMask = newAnalogMask or (1 shl DEVICE_ID_JOYPAD_UP) or (1 shl DEVICE_ID_JOYPAD_LEFT)
                        }

                        analogKeysMask = newAnalogMask
                    } else {
                        analogKeysMask = 0
                    }
                    updateInputKeys()
                    true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    resetAnalogThumb()
                    true
                }
                else -> false
            }
        }
    }

    private fun resetAnalogThumb() {
        analogThumb.animate()
            .translationX(0f)
            .translationY(0f)
            .setDuration(100)
            .start()
        analogKeysMask = 0
        updateInputKeys()
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
                // Jangan hiraukan D-Pad standar jika Mode Analog aktif
                if (isAnalogMode && isDpadButton(btn.view.id)) continue

                btn.view.getGlobalVisibleRect(tempRect)
                if (tempRect.contains(x, y)) {
                    btn.bitmaskList.forEach { activeMasks.add(it) }
                }
            }
        }

        var dpadButtonsMask = 0
        for (btn in registeredButtons) {
            if (isAnalogMode && isDpadButton(btn.view.id)) continue

            val shouldBePressed = btn.bitmaskList.any { activeMasks.contains(it) }
            if (btn.isPressed != shouldBePressed) {
                btn.isPressed = shouldBePressed
                updateButtonVisuals(btn.view, shouldBePressed)

                if (shouldBePressed && btn.bitmaskList.isNotEmpty()) {
                    checkSecretCheat(btn.bitmaskList[0])
                }
            }
            if (btn.isPressed) {
                btn.bitmaskList.forEach { bit ->
                    dpadButtonsMask = dpadButtonsMask or (1 shl bit)
                }
            }
        }

        updateInputKeys(dpadButtonsMask)
    }

    private fun isDpadButton(id: Int): Boolean {
        return id == R.id.btn_up || id == R.id.btn_down || id == R.id.btn_left || id == R.id.btn_right ||
               id == R.id.btn_up_left || id == R.id.btn_up_right || id == R.id.btn_down_left || id == R.id.btn_down_right
    }

    private fun updateInputKeys(buttonsMask: Int = currentKeys) {
        val finalKeys = if (isAnalogMode) {
            (buttonsMask and DPAD_MASK_CLEAR) or analogKeysMask
        } else {
            buttonsMask
        }

        if (currentKeys != finalKeys) {
            currentKeys = finalKeys
            gbaView.updateInput(currentKeys)
        }
    }

    private val DPAD_MASK_CLEAR = (
        (1 shl DEVICE_ID_JOYPAD_UP) or
        (1 shl DEVICE_ID_JOYPAD_DOWN) or
        (1 shl DEVICE_ID_JOYPAD_LEFT) or
        (1 shl DEVICE_ID_JOYPAD_RIGHT)
    ).inv()

    private fun checkSecretCheat(bitmask: Int) {
        keyHistory.addLast(bitmask)

        while (keyHistory.size > secretCheatCode.size) {
            keyHistory.removeFirst()
        }

        if (keyHistory.toList() == secretCheatCode) {
            toggleDebugHUD()
            keyHistory.clear()
        }
    }

    private fun toggleDebugHUD() {
        isDebugVisible = !isDebugVisible
        if (isDebugVisible) {
            tvDebugHud.visibility = View.VISIBLE
            Toast.makeText(this, "⚙️ Debug HUD Activated!", Toast.LENGTH_SHORT).show()
            startDebugLoop()
        } else {
            tvDebugHud.visibility = View.GONE
            stopDebugLoop()
            Toast.makeText(this, "⚙️ Debug HUD Hidden", Toast.LENGTH_SHORT).show()
        }
    }

    private val debugRunnable = object : Runnable {
        override fun run() {
            if (isDebugVisible && emulatorLayout.visibility == View.VISIBLE) {
                try {
                    val info = GbaEngine.nativeDebugInfo()
                    tvDebugHud.text = info
                } catch (e: Exception) {
                    tvDebugHud.text = "Debug Error: ${e.message}"
                }
                debugHandler.postDelayed(this, 200)
            }
        }
    }

    private fun startDebugLoop() {
        debugHandler.removeCallbacks(debugRunnable)
        debugHandler.post(debugRunnable)
    }

    private fun stopDebugLoop() {
        debugHandler.removeCallbacks(debugRunnable)
    }

    private fun updateButtonVisuals(view: View, isPressed: Boolean) {
        // Jangan beri feedback animasi pada hotspot transparan
        if (isDpadDiagonalHotspot(view.id)) return

        if (isPressed) {
            view.animate().scaleX(0.88f).scaleY(0.88f).setDuration(50).start()
            view.alpha = 0.5f
            view.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
        } else {
            view.animate().scaleX(1.0f).scaleY(1.0f).setDuration(50).start()
            view.alpha = 1.0f
        }
    }

    private fun isDpadDiagonalHotspot(id: Int): Boolean {
        return id == R.id.btn_up_left || id == R.id.btn_up_right || id == R.id.btn_down_left || id == R.id.btn_down_right
    }

    private fun setupBackButtonHandler() {
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (currentDialog != null && currentDialog!!.isShowing) {
                    currentDialog?.dismiss()
                    currentDialog = null
                    return
                }

                if (emulatorLayout.visibility == View.VISIBLE) {
                    showIgbaDashboard()
                    return
                }

                if (backPressedOnce) {
                    finish()
                    return
                }

                backPressedOnce = true
                Toast.makeText(this@MainActivity, "Tekan sekali lagi untuk keluar", Toast.LENGTH_SHORT).show()

                Handler(Looper.getMainLooper()).postDelayed({
                    backPressedOnce = false
                }, 2000)
            }
        })
    }

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

        if (!igbaFolder.exists()) igbaFolder.mkdirs()
        if (!coversFolder.exists()) coversFolder.mkdirs()

        playIgbaBootAnimation()
    }

    private fun playIgbaBootAnimation() {
        igbaSplashOverlay.visibility = View.VISIBLE

        val tvLogo = findViewById<TextView>(R.id.tv_igba_logo)
        tvLogo.alpha = 0f
        tvLogo.animate().alpha(1f).setDuration(1200).start()

        Handler(Looper.getMainLooper()).postDelayed({
            val fadeOut = AlphaAnimation(1f, 0f).apply { duration = 800 }
            igbaSplashOverlay.startAnimation(fadeOut)
            igbaSplashOverlay.visibility = View.GONE
            showIgbaDashboard()
        }, 2800)
    }

    private fun showIgbaDashboard() {
        stopDebugLoop()
        gbaView.stopLoop()
        emulatorLayout.visibility = View.GONE
        igbaDashboardLayout.visibility = View.VISIBLE
        scanIgbaDirectory()
    }

    @SuppressLint("NotifyDataSetChanged")
    private fun scanIgbaDirectory() {
        gamesList.clear()
        scanAssetsGmsFolder()

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

                gamesList.add(GameModel(gameTitle, romFile, assetPath = null, coverBitmap = bitmap, isAsset = false))
            }
        }

        ps4Adapter.notifyDataSetChanged()

        if (gamesList.isNotEmpty()) {
            val firstGame = gamesList[0]
            tvUserProfile.text = "🎮 ${firstGame.title}"
            tvSelectedGameTitle.text = firstGame.title
            tvSelectedGameSub.text = if (firstGame.isAsset) "Built-in IGBA HEN Game" else "File: ${firstGame.file?.name}"
        } else {
            tvUserProfile.text = "🎮 IGBA System"
            tvSelectedGameTitle.text = "No Games Found"
            tvSelectedGameSub.text = "Put .gba files in Documents/igba/ directory"
        }
    }

    private fun scanAssetsGmsFolder() {
        try {
            val gmsFiles = assets.list("gms") ?: return
            for (filename in gmsFiles) {
                if (filename.lowercase().endsWith(".gba") || filename.lowercase().endsWith(".zip")) {
                    val gameTitle = File(filename).nameWithoutExtension
                    var coverBitmap: Bitmap? = null

                    val extensions = arrayOf("png", "jpg", "jpeg")
                    for (ext in extensions) {
                        try {
                            val stream = assets.open("gms/cover/$gameTitle.$ext")
                            coverBitmap = BitmapFactory.decodeStream(stream)
                            stream.close()
                            break
                        } catch (_: Exception) {}
                    }

                    gamesList.add(
                        GameModel(
                            title = gameTitle,
                            file = null,
                            assetPath = "gms/$filename",
                            coverBitmap = coverBitmap,
                            isAsset = true
                        )
                    )
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun promptChangeCover(game: GameModel) {
        if (game.isAsset) {
            Toast.makeText(this, "Game Bawaan Asset tidak bisa ganti cover!", Toast.LENGTH_SHORT).show()
            return
        }
        selectedGameForCover = game
        currentDialog = AlertDialog.Builder(this)
            .setTitle("Custom Cover Art")
            .setMessage("Change thumbnail art for ${game.title}?")
            .setPositiveButton("Select Image") { _, _ ->
                val intent = Intent(Intent.ACTION_PICK).apply { type = "image/*" }
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
                FileOutputStream(targetCover).use { output -> input.copyTo(output) }
            }

            Toast.makeText(this, "Cover Updated!", Toast.LENGTH_SHORT).show()
            scanIgbaDirectory()
        } catch (e: Exception) {
            e.printStackTrace()
            Toast.makeText(this, "Failed saving cover", Toast.LENGTH_SHORT).show()
        }
    }

    private fun launchGame(game: GameModel) {
        igbaDashboardLayout.visibility = View.GONE
        emulatorLayout.visibility = View.VISIBLE

        val romFilePath = if (game.isAsset && game.assetPath != null) {
            copyAssetToCache(game.assetPath)
        } else {
            game.file?.absolutePath
        }

        if (romFilePath != null) {
            val isLoaded = gbaView.loadRom(romFilePath)
            if (isLoaded) {
                Toast.makeText(this, "Playing: ${game.title}", Toast.LENGTH_SHORT).show()
            } else {
                Toast.makeText(this, "Gagal memuat ROM GBA", Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun copyAssetToCache(assetPath: String): String? {
        return try {
            val inputStream: InputStream = assets.open(assetPath)
            val tempFile = File(cacheDir, "temp_asset_game.gba")
            val outputStream = FileOutputStream(tempFile)

            inputStream.use { input -> outputStream.use { output -> input.copyTo(output) } }
            tempFile.absolutePath
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }

    private fun loadRomFromUri(uri: Uri) {
        try {
            val inputStream = contentResolver.openInputStream(uri)
            val tempFile = File(cacheDir, "current_game.gba")
            val outputStream = FileOutputStream(tempFile)

            inputStream?.use { input -> outputStream.use { output -> input.copyTo(output) } }

            igbaDashboardLayout.visibility = View.GONE
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

    override fun onPause() {
        super.onPause()
        stopDebugLoop()
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