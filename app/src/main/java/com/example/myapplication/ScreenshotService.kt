package com.example.myapplication

import android.annotation.SuppressLint
import android.app.*
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Point
import android.graphics.Rect
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.*
import android.provider.Settings
import android.util.DisplayMetrics
import android.util.Log
import android.view.*
import android.view.KeyEvent
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.view.ContextThemeWrapper
import androidx.core.app.NotificationCompat
import com.google.gson.Gson
import com.google.android.material.switchmaterial.SwitchMaterial
import com.google.gson.reflect.TypeToken
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import kotlin.math.roundToInt

class ScreenshotService : Service() {
    companion object {
        const val ACTION_APP_VISIBILITY = "com.example.myapplication.ACTION_APP_VISIBILITY"
        const val EXTRA_APP_IN_FOREGROUND = "extra_app_in_foreground"
    }

    private enum class ResultTouchMode { MOVE, RESIZE_LEFT, RESIZE_RIGHT, RESIZE_TOP, RESIZE_BOTTOM }

    /** 选区浮层拖动时 params.x/y 的合法范围，使内层选区矩形可贴齐屏幕四边（与字幕窗「整窗贴边」体验一致）。 */
    private data class SelectionWindowDragLimits(
        val minX: Int,
        val maxX: Int,
        val minY: Int,
        val maxY: Int
    )

    /**
     * 按选区框在窗口内的偏移计算拖动范围：允许窗口略超出屏幕，从而让选区矩形能顶到物理边缘。
     * 字幕结果窗无额外顶栏留白，用整窗测量即可贴边；选区层有 padding + 工具条区 + margin，不能仅用 params 宽高钳制。
     */
    private fun computeSelectionWindowDragLimits(selectionFrame: View): SelectionWindowDragLimits {
        val fv = floatingView ?: return SelectionWindowDragLimits(0, 0, 0, 0)
        val wmParams = fv.layoutParams as? WindowManager.LayoutParams
        val winW = fv.width
        val winH = fv.height
        val selW = selectionFrame.width
        val selH = selectionFrame.height
        if (winW <= 0 || winH <= 0 || selW <= 0 || selH <= 0) {
            val pw = wmParams?.width?.takeIf { it > 0 } ?: (screenWidth / 2).coerceAtLeast(1)
            val ph = wmParams?.height?.takeIf { it > 0 } ?: (screenHeight / 2).coerceAtLeast(1)
            val maxX = (screenWidth - pw).coerceAtLeast(0)
            val maxY = (screenHeight - ph).coerceAtLeast(0)
            return SelectionWindowDragLimits(0, maxX, 0, maxY)
        }
        val winLoc = IntArray(2)
        val selLoc = IntArray(2)
        fv.getLocationOnScreen(winLoc)
        selectionFrame.getLocationOnScreen(selLoc)
        val leftOff = selLoc[0] - winLoc[0]
        val topOff = selLoc[1] - winLoc[1]
        val minX = -leftOff
        val maxX = (screenWidth - leftOff - selW).coerceAtLeast(minX)
        val minY = -topOff
        val maxY = (screenHeight - topOff - selH).coerceAtLeast(minY)
        return SelectionWindowDragLimits(minX, maxX, minY, maxY)
    }

    private var mediaProjection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var imageReader: ImageReader? = null
    private lateinit var windowManager: WindowManager
    private var floatingView: View? = null
    private var ballView: View? = null
    private var resultView: View? = null
    private var menuOverlayView: View? = null
    private lateinit var prefs: SharedPreferences

    private var screenWidth = 0
    private var screenHeight = 0
    private var screenDensity = 0
    private var orientationListener: OrientationEventListener? = null
    private var lastOrientation = -1

    private lateinit var cacheManager: OcrCacheManager
    private val serviceScope = CoroutineScope(Dispatchers.Main)
    private var resultViewTimer: Handler? = null
    private var gamepadMappingMode = false
    private var appInForeground = false
    
    private val localOcrEngine by lazy { LocalOcrEngine() }
    private val defaultStartKeyCode = KeyEvent.KEYCODE_BUTTON_A

    // 悬浮球自动收回逻辑
    private val hideHandler = Handler(Looper.getMainLooper())
    private val hideRunnable = Runnable { retractBall() }

    /** PopupMenu 打开期间不缩回边缘，避免菜单只露出一截 */
    private var isBallPopupMenuShowing = false

    /** 避免应用主题里 Material 默认 colorSurface 等在 inflate 时给悬浮窗叠灰底 */
    private fun overlayTransparentLayoutInflater(): LayoutInflater {
        val wrapped = ContextThemeWrapper(this, R.style.Theme_Overlay_TransparentInflate)
        return LayoutInflater.from(wrapped).cloneInContext(wrapped)
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        prefs = getSharedPreferences("app_prefs", Context.MODE_PRIVATE)
        cacheManager = OcrCacheManager(this)
        
        updateScreenMetrics()
        createNotificationChannel()
        startForeground(1, createNotification())
        setupOrientationListener()
        
        if (canDrawOverlays()) {
            showFloatingBall()
        }
    }

    private fun updateScreenMetrics() {
        screenDensity = resources.configuration.densityDpi
        // 与 MediaProjection / 物理显示一致，避免横竖屏、平板与 WindowMetrics 边界不一致导致截图错位
        @Suppress("DEPRECATION")
        val display = windowManager.defaultDisplay
        val size = Point()
        display.getRealSize(size)
        screenWidth = size.x
        screenHeight = size.y
    }

    /** 横竖屏切换后，将已保存的翻译结果窗位置限制在当前屏幕内（宽度来自 prefs，高度按内容故用估算高度做钳制） */
    private fun clampSavedResultWindowToScreen() {
        if (screenWidth <= 0 || screenHeight <= 0) return
        val w = prefs.getInt("result_fixed_width", 280.dpToPx()).coerceAtLeast(1)
        val h = (screenHeight / 3).coerceAtLeast(120.dpToPx())
        var x = prefs.getInt("result_fixed_x", 0)
        var y = prefs.getInt("result_fixed_y", 0)
        x = x.coerceIn(0, (screenWidth - w).coerceAtLeast(0))
        y = y.coerceIn(0, (screenHeight - h).coerceAtLeast(0))
        prefs.edit().putInt("result_fixed_x", x).putInt("result_fixed_y", y).apply()
    }

    private fun clampSubtitleLayoutToScreen() {
        if (screenWidth <= 0 || screenHeight <= 0) return
        val w = prefs.getInt("subtitle_width", 280.dpToPx()).coerceAtLeast(1)
        val h = (screenHeight / 3).coerceAtLeast(120.dpToPx())
        var x = prefs.getInt("subtitle_x", 0)
        var y = prefs.getInt("subtitle_y", 0)
        x = x.coerceIn(0, (screenWidth - w).coerceAtLeast(0))
        y = y.coerceIn(0, (screenHeight - h).coerceAtLeast(0))
        prefs.edit().putInt("subtitle_x", x).putInt("subtitle_y", y).apply()
    }

    private fun setupOrientationListener() {
        orientationListener = object : OrientationEventListener(this) {
            override fun onOrientationChanged(orientation: Int) {
                val newOrientation = when {
                    orientation >= 45 && orientation < 135 -> 90
                    orientation >= 135 && orientation < 225 -> 180
                    orientation >= 225 && orientation < 315 -> 270
                    else -> 0
                }
                if (newOrientation != lastOrientation && newOrientation != -1) {
                    lastOrientation = newOrientation
                    Handler(Looper.getMainLooper()).post { 
                        updateScreenMetrics()
                        clampSavedResultWindowToScreen()
                        clampSubtitleLayoutToScreen()
                        if (floatingView != null) {
                            windowManager.removeView(floatingView)
                            floatingView = null
                            showFloatingSelection()
                        }
                        showFloatingBall()
                    }
                }
            }
        }
        orientationListener?.enable()
    }
    
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_APP_VISIBILITY) {
            appInForeground = intent.getBooleanExtra(EXTRA_APP_IN_FOREGROUND, false)
            if (appInForeground) {
                ballView?.let { try { windowManager.removeView(it) } catch(_: Exception) {} }
                ballView = null
            } else {
                if (canDrawOverlays()) showFloatingBall()
            }
            return START_NOT_STICKY
        }

        val resultCode = intent?.getIntExtra("resultCode", Activity.RESULT_CANCELED) ?: Activity.RESULT_CANCELED
        val data = intent?.getParcelableExtra<Intent>("data")
        if (resultCode == Activity.RESULT_OK && data != null) {
            val mpManager = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
            mediaProjection = mpManager.getMediaProjection(resultCode, data)
        }
        return START_NOT_STICKY
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun showFloatingBall() {
        if (!canDrawOverlays()) return
        if (appInForeground) return
        ballView?.let { try { windowManager.removeView(it) } catch(e:Exception){} }
        ballView = LayoutInflater.from(this).inflate(R.layout.layout_floating_ball, null)
        
        val ballSize = 40.dpToPx()
        val params = WindowManager.LayoutParams(
            ballSize, ballSize,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = prefs.getInt("ball_x", screenWidth - ballSize)
            y = prefs.getInt("ball_y", screenHeight / 3)
        }

        ballView!!.setOnTouchListener(object : View.OnTouchListener {
            private var initialX = 0
            private var initialY = 0
            private var startRawX = 0f
            private var startRawY = 0f
            private var isMove = false

            override fun onTouch(v: View, event: MotionEvent): Boolean {
                when (event.action) {
                    MotionEvent.ACTION_DOWN -> {
                        expandBall() // 触摸瞬间恢复
                        initialX = params.x
                        initialY = params.y
                        startRawX = event.rawX
                        startRawY = event.rawY
                        isMove = false
                        return true
                    }
                    MotionEvent.ACTION_MOVE -> {
                        val dx = event.rawX - startRawX
                        val dy = event.rawY - startRawY
                        if (Math.abs(dx) > 10 || Math.abs(dy) > 10) {
                            params.x = initialX + dx.toInt()
                            params.y = initialY + dy.toInt()
                            windowManager.updateViewLayout(ballView, params)
                            isMove = true
                        }
                        return true
                    }
                    MotionEvent.ACTION_UP -> {
                        if (!isMove) {
                            showSettingsMenu(v)
                            // 菜单由 OnDismiss 后再启动缩回计时；此处 post 会导致菜单仍打开时球已缩回
                        } else {
                            // 吸附到最近的屏幕边缘
                            if (params.x + ballSize / 2 < screenWidth / 2) params.x = 0
                            else params.x = screenWidth - ballSize
                            windowManager.updateViewLayout(ballView, params)
                            prefs.edit().putInt("ball_x", params.x).putInt("ball_y", params.y).apply()
                            hideHandler.postDelayed(hideRunnable, 2000)
                        }
                        return true
                    }
                }
                return false
            }
        })

        windowManager.addView(ballView, params)
        hideHandler.postDelayed(hideRunnable, 2000)
    }

    private fun retractBall() {
        if (isBallPopupMenuShowing) return
        val v = ballView ?: return
        val params = v.layoutParams as WindowManager.LayoutParams
        val ballSize = v.width
        // 物理收回：将 X 坐标移出屏幕一半宽度
        if (params.x < screenWidth / 2) {
            params.x = -(ballSize / 2)
        } else {
            params.x = screenWidth - (ballSize / 2)
        }
        params.alpha = 0.5f // 设为半透明
        try { windowManager.updateViewLayout(v, params) } catch(e:Exception){}
    }

    private fun expandBall() {
        hideHandler.removeCallbacks(hideRunnable)
        val v = ballView ?: return
        val params = v.layoutParams as WindowManager.LayoutParams
        if (params.alpha < 1.0f) {
            val ballSize = v.width
            // 恢复到完全在屏幕内
            if (params.x < 0) params.x = 0
            if (params.x + ballSize > screenWidth) params.x = screenWidth - ballSize
            params.alpha = 1.0f
            try { windowManager.updateViewLayout(v, params) } catch(e:Exception){}
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun showFloatingSelection() {
        if (!canDrawOverlays()) return
        if (floatingView != null) return
        updateScreenMetrics()
        floatingView = overlayTransparentLayoutInflater().inflate(R.layout.layout_floating_selection, null)
        
        val toolbarTopMargin = 45.dpToPx()

        val savedW = prefs.getInt("selection_w", (screenWidth * 0.8).toInt())
        val savedH = prefs.getInt("selection_h", (screenHeight * 0.25).toInt())
        val savedX = prefs.getInt("selection_x", (screenWidth - savedW) / 2)
        val savedY = prefs.getInt("selection_y", screenHeight / 2)
        
        // 使用 WRAP_CONTENT 让窗口尺寸由内容自动决定，与字幕窗一致，
        // 避免系统按固定像素宽度钳制窗口位置/尺寸，使选区框可到达屏幕边缘
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = savedX
            y = savedY
        }

        val selectionFrame = floatingView!!.findViewById<View>(R.id.selection_frame)
        selectionFrame.layoutParams.width = savedW
        selectionFrame.layoutParams.height = savedH
        
        val showBorder = prefs.getBoolean("show_selection_border", true)
        selectionFrame.setBackgroundResource(if (showBorder) R.drawable.selection_border else 0)
        val handleVis = if (showBorder) View.VISIBLE else View.GONE
        floatingView!!.findViewById<View>(R.id.handle_left).visibility = handleVis
        floatingView!!.findViewById<View>(R.id.handle_right).visibility = handleVis
        floatingView!!.findViewById<View>(R.id.handle_top).visibility = handleVis
        floatingView!!.findViewById<View>(R.id.handle_bottom).visibility = handleVis

        setupResizeAndMoveLogic(params, selectionFrame)
        setupToolbarButtons(params, selectionFrame)
        setupGamepadControls(params, selectionFrame)

        windowManager.addView(floatingView, params)
        floatingView?.post {
            updateScreenMetrics()
            val frame = floatingView?.findViewById<View>(R.id.selection_frame) ?: return@post
            val lim = computeSelectionWindowDragLimits(frame)
            val p = floatingView?.layoutParams as? WindowManager.LayoutParams ?: return@post
            p.x = p.x.coerceIn(lim.minX, lim.maxX)
            p.y = p.y.coerceIn(lim.minY, lim.maxY)
            try {
                windowManager.updateViewLayout(floatingView, p)
            } catch (_: Exception) {
            }
        }
        ensureBallOnTop()
        applyToolbarVisibilityAndPosition(selectionFrame)
        floatingView?.isFocusable = true
        floatingView?.isFocusableInTouchMode = true
        floatingView?.requestFocus()
    }

    private fun ensureBallOnTop() {
        val ball = ballView ?: return
        val lp = ball.layoutParams as? WindowManager.LayoutParams ?: return
        try {
            windowManager.removeView(ball)
            windowManager.addView(ball, lp)
        } catch (_: Exception) {
        }
    }

    /** 翻译结果窗置于选区等其它 overlay 之上，避免胶囊被下层窗口吃掉触摸 */
    private fun ensureResultViewOnTop() {
        val v = resultView ?: return
        val lp = v.layoutParams as? WindowManager.LayoutParams ?: return
        try {
            windowManager.removeView(v)
            windowManager.addView(v, lp)
            ensureBallOnTop()
        } catch (_: Exception) {
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun setupResizeAndMoveLogic(params: WindowManager.LayoutParams, selectionFrame: View) {
        val innerContent = floatingView!!.findViewById<View>(R.id.inner_content)
        val toolbar = floatingView!!.findViewById<View>(R.id.toolbar)
        val handles = mapOf(
            R.id.handle_left to floatingView!!.findViewById<View>(R.id.handle_left),
            R.id.handle_right to floatingView!!.findViewById<View>(R.id.handle_right),
            R.id.handle_top to floatingView!!.findViewById<View>(R.id.handle_top),
            R.id.handle_bottom to floatingView!!.findViewById<View>(R.id.handle_bottom)
        )
        
        innerContent.setOnTouchListener(object : View.OnTouchListener {
            private var initialX = 0
            private var initialY = 0
            private var startRawX = 0f
            private var startRawY = 0f

            override fun onTouch(v: View, event: MotionEvent): Boolean {
                when (event.action) {
                    MotionEvent.ACTION_DOWN -> {
                        initialX = params.x
                        initialY = params.y
                        startRawX = event.rawX
                        startRawY = event.rawY
                        return true
                    }
                    MotionEvent.ACTION_MOVE -> {
                        val dx = (event.rawX - startRawX).roundToInt()
                        val dy = (event.rawY - startRawY).roundToInt()
                        val lim = computeSelectionWindowDragLimits(selectionFrame)
                        params.x = (initialX + dx).coerceIn(lim.minX, lim.maxX)
                        params.y = (initialY + dy).coerceIn(lim.minY, lim.maxY)
                        windowManager.updateViewLayout(floatingView, params)
                        return true
                    }
                    MotionEvent.ACTION_UP -> {
                        savePosition(params.x, params.y, selectionFrame.width, selectionFrame.height)
                        return true
                    }
                }
                return false
            }
        })
        
        val edgeTouchListener = object : View.OnTouchListener {
            private var initialWindowX = 0
            private var initialWindowY = 0
            private var initialFrameW = 0
            private var initialFrameH = 0
            private var startRawX = 0f
            private var startRawY = 0f

            override fun onTouch(v: View, event: MotionEvent): Boolean {
                when (event.action) {
                    MotionEvent.ACTION_DOWN -> {
                        initialWindowX = params.x
                        initialWindowY = params.y
                        initialFrameW = selectionFrame.width
                        initialFrameH = selectionFrame.height
                        startRawX = event.rawX
                        startRawY = event.rawY
                        return true
                    }
                    MotionEvent.ACTION_MOVE -> {
                        val dx = (event.rawX - startRawX).roundToInt()
                        val dy = (event.rawY - startRawY).roundToInt()
                        val minSize = 50.dpToPx()
                        val maxFrameW = screenWidth.coerceAtLeast(minSize)
                        val maxFrameH = screenHeight.coerceAtLeast(minSize)
                        val lim = computeSelectionWindowDragLimits(selectionFrame)

                        when (v.id) {
                            R.id.handle_left -> {
                                val newW = (initialFrameW - dx).coerceIn(minSize, maxFrameW)
                                // 保持选区框右缘位置不动：左移窗口补偿宽度增大
                                params.x = (initialWindowX + (initialFrameW - newW)).coerceIn(lim.minX, Int.MAX_VALUE)
                                selectionFrame.layoutParams.width = newW
                            }
                            R.id.handle_right -> {
                                val newW = (initialFrameW + dx).coerceIn(minSize, maxFrameW)
                                selectionFrame.layoutParams.width = newW
                            }
                            R.id.handle_top -> {
                                val newH = (initialFrameH - dy).coerceIn(minSize, maxFrameH)
                                params.y = (initialWindowY + (initialFrameH - newH)).coerceIn(lim.minY, Int.MAX_VALUE)
                                selectionFrame.layoutParams.height = newH
                            }
                            R.id.handle_bottom -> {
                                val newH = (initialFrameH + dy).coerceIn(minSize, maxFrameH)
                                selectionFrame.layoutParams.height = newH
                            }
                        }
                        selectionFrame.requestLayout()
                        clampAndSaveToolbarOffset(toolbar, selectionFrame.width, persist = false)
                        windowManager.updateViewLayout(floatingView, params)
                        return true
                    }
                    MotionEvent.ACTION_UP -> {
                        savePosition(params.x, params.y, selectionFrame.width, selectionFrame.height)
                        clampAndSaveToolbarOffset(toolbar, selectionFrame.width, persist = true)
                        return true
                    }
                }
                return false
            }
        }
        handles.values.forEach { it.setOnTouchListener(edgeTouchListener) }
    }

    private fun setupToolbarButtons(params: WindowManager.LayoutParams, selectionFrame: View) {
        val toolbar = floatingView?.findViewById<View>(R.id.toolbar)
        setupToolbarDrag(toolbar, selectionFrame)

        floatingView?.findViewById<ImageView>(R.id.btn_start)?.setOnClickListener {
            startCaptureFromSelection(params, selectionFrame)
        }

        floatingView?.findViewById<ImageView>(R.id.btn_map_key)?.setOnClickListener {
            gamepadMappingMode = true
            Toast.makeText(this, "按下任意按键以设置映射", Toast.LENGTH_SHORT).show()
            floatingView?.requestFocus()
        }
        
        floatingView?.findViewById<ImageView>(R.id.btn_close)?.setOnClickListener { 
            gamepadMappingMode = false
            windowManager.removeView(floatingView)
            floatingView = null
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun setupToolbarDrag(toolbar: View?, selectionFrame: View) {
        if (toolbar == null) return
        toolbar.setOnTouchListener(object : View.OnTouchListener {
            private var initialLeft = 0
            private var startRawX = 0f

            override fun onTouch(v: View, event: MotionEvent): Boolean {
                when (event.action) {
                    MotionEvent.ACTION_DOWN -> {
                        val lp = v.layoutParams as FrameLayout.LayoutParams
                        initialLeft = lp.leftMargin
                        startRawX = event.rawX
                        return true
                    }
                    MotionEvent.ACTION_MOVE -> {
                        val dx = (event.rawX - startRawX).toInt()
                        val lp = v.layoutParams as FrameLayout.LayoutParams
                        lp.leftMargin = (initialLeft + dx).coerceIn(0, (selectionFrame.width - v.width).coerceAtLeast(0))
                        v.layoutParams = lp
                        return true
                    }
                    MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                        clampAndSaveToolbarOffset(v, selectionFrame.width, persist = true)
                        return true
                    }
                }
                return false
            }
        })
    }

    private fun applyToolbarVisibilityAndPosition(selectionFrame: View) {
        val toolbar = floatingView?.findViewById<View>(R.id.toolbar) ?: return
        val visible = prefs.getBoolean("show_selection_toolbar", true)
        toolbar.visibility = if (visible) View.VISIBLE else View.GONE
        adjustSelectionWrapperMargin()
        toolbar.post {
            val lp = toolbar.layoutParams as FrameLayout.LayoutParams
            lp.gravity = Gravity.TOP or Gravity.START
            lp.leftMargin = prefs.getInt("selection_toolbar_offset_x", (selectionFrame.width - toolbar.width) / 2)
            toolbar.layoutParams = lp
            clampAndSaveToolbarOffset(toolbar, selectionFrame.width, persist = false)
        }
    }

    /** 根据工具栏显示/隐藏动态调整 selection_wrapper 的 topMargin，消除工具栏隐藏后的顶部空白 */
    private fun adjustSelectionWrapperMargin() {
        val wrapper = floatingView?.findViewById<View>(R.id.selection_wrapper) ?: return
        val toolbar = floatingView?.findViewById<View>(R.id.toolbar) ?: return
        val lp = wrapper.layoutParams as FrameLayout.LayoutParams
        lp.topMargin = if (toolbar.visibility == View.VISIBLE) {
            45.dpToPx()
        } else {
            4.dpToPx()
        }
        wrapper.layoutParams = lp
    }

    private fun clampAndSaveToolbarOffset(toolbar: View, selectionWidth: Int, persist: Boolean) {
        val lp = toolbar.layoutParams as FrameLayout.LayoutParams
        lp.gravity = Gravity.TOP or Gravity.START
        lp.leftMargin = lp.leftMargin.coerceIn(0, (selectionWidth - toolbar.width).coerceAtLeast(0))
        toolbar.layoutParams = lp
        if (persist) {
            prefs.edit().putInt("selection_toolbar_offset_x", lp.leftMargin).apply()
        }
    }

    private fun setupGamepadControls(params: WindowManager.LayoutParams, selectionFrame: View) {
        floatingView?.setOnKeyListener { _, keyCode, event ->
            if (event.action != KeyEvent.ACTION_DOWN || event.repeatCount > 0) return@setOnKeyListener false
            if (keyCode == KeyEvent.KEYCODE_BACK) return@setOnKeyListener false

            if (gamepadMappingMode) {
                saveMappedTriggerKey(keyCode)
                gamepadMappingMode = false
                Toast.makeText(this, "映射已设置为: ${KeyEvent.keyCodeToString(keyCode)}", Toast.LENGTH_SHORT).show()
                return@setOnKeyListener true
            }

            if (getMappedGamepadStartKeys().contains(keyCode)) {
                startCaptureFromSelection(params, selectionFrame)
                return@setOnKeyListener true
            }
            false
        }
    }

    private fun startCaptureFromSelection(params: WindowManager.LayoutParams, selectionFrame: View) {
        // 布局稳定后再取屏上坐标，横竖屏、平板下避免量到错误位置
        selectionFrame.post {
            updateScreenMetrics()
            val location = IntArray(2)
            selectionFrame.getLocationOnScreen(location)
            val absoluteRect = Rect(
                location[0],
                location[1],
                location[0] + selectionFrame.width,
                location[1] + selectionFrame.height
            )
            captureArea(absoluteRect)
        }
    }

    private fun getMappedGamepadStartKeys(): Set<Int> {
        val single = prefs.getInt("trigger_key_code", -1)
        if (single != -1) return setOf(single)
        val legacySingle = prefs.getInt("gamepad_start_key_code", -1)
        if (legacySingle != -1) return setOf(legacySingle)
        val raw = prefs.getString("gamepad_start_key_codes", null)
        if (raw.isNullOrBlank()) return setOf(defaultStartKeyCode)
        val parsed = raw.split(",")
            .mapNotNull { it.trim().toIntOrNull() }
            .toSet()
        return if (parsed.isEmpty()) setOf(defaultStartKeyCode) else parsed
    }

    private fun saveMappedTriggerKey(keyCode: Int) {
        prefs.edit()
            .putInt("trigger_key_code", keyCode)
            .putString("gamepad_start_key_codes", keyCode.toString())
            .remove("gamepad_start_key_code")
            .apply()
    }

    private fun dismissMenuOverlay() {
        menuOverlayView?.let { try { windowManager.removeView(it) } catch (_: Exception) {} }
        menuOverlayView = null
        isBallPopupMenuShowing = false
        hideHandler.postDelayed(hideRunnable, 2000)
    }

    private fun updateMenuRadioIndicators(menuView: View, selectedMode: String) {
        fun setRadio(viewId: Int, selected: Boolean) {
            (menuView.findViewById<View>(viewId) as? ImageView)?.setImageResource(
                if (selected) R.drawable.ic_menu_radio_on else R.drawable.ic_menu_radio_off
            )
        }
        setRadio(R.id.indicator_mode_follow, selectedMode == "follow")
        setRadio(R.id.indicator_mode_fixed, selectedMode == "fixed")
        setRadio(R.id.indicator_mode_subtitle, selectedMode == "subtitle")
    }

    private fun showSettingsMenu(anchor: View) {
        hideHandler.removeCallbacks(hideRunnable)
        isBallPopupMenuShowing = true
        expandBall()
        updateScreenMetrics()

        // 菜单已显示时再次点击球 → 关闭
        if (menuOverlayView != null) {
            dismissMenuOverlay()
            return
        }
        menuOverlayView?.let { try { windowManager.removeView(it) } catch (_: Exception) {} }

        val themedCtx = ContextThemeWrapper(this, R.style.Theme_MyApplication)
        val menuView = LayoutInflater.from(themedCtx).cloneInContext(themedCtx)
            .inflate(R.layout.popup_ball_menu, null)
        menuOverlayView = menuView

        // ---- 卡片位置（全屏 overlay 内嵌 ScrollView 卡片） ----
        val loc = IntArray(2)
        anchor.getLocationOnScreen(loc)
        val ballSize = anchor.width
        val cardWidth = 240.dpToPx()
        val isOnRight = screenWidth > 0 && (loc[0] + ballSize / 2) * 2 >= screenWidth

        val card = menuView.findViewById<View>(R.id.menu_scroll)
        val cardLp = card.layoutParams as FrameLayout.LayoutParams
        cardLp.gravity = Gravity.TOP or Gravity.START
        cardLp.topMargin = loc[1]
        cardLp.leftMargin = if (isOnRight) {
            (loc[0] + ballSize - cardWidth).coerceIn(0, screenWidth - cardWidth)
        } else {
            (loc[0] + ballSize).coerceAtMost(screenWidth - cardWidth)
        }
        // 限制卡片高度为屏幕剩余空间的 75%；内容超出时 ScrollView 可滚
        val availBelow = screenHeight - loc[1] - 16.dpToPx()
        val maxH = (availBelow.coerceAtMost((screenHeight * 0.75f).toInt())).coerceAtLeast(200.dpToPx())
        cardLp.height = maxH
        card.layoutParams = cardLp

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
        }

        // ---- 根视图点击关闭 / 卡片区域阻止冒泡 ----
        menuView.setOnClickListener { dismissMenuOverlay() }
        card.setOnClickListener { /* 消费事件，防止冒泡到根视图 */ }

        // ---- 动态文本 ----
        menuView.findViewById<TextView>(R.id.label_toggle_selection).text =
            if (floatingView == null) "开启选择" else "关闭选择"

        // ---- 初始化开关 ----
        fun initSwitch(switchId: Int, prefKey: String, default: Boolean) {
            menuView.findViewById<SwitchMaterial>(switchId).isChecked =
                prefs.getBoolean(prefKey, default)
        }
        initSwitch(R.id.switch_show_border, "show_selection_border", true)
        initSwitch(R.id.switch_show_toolbar, "show_selection_toolbar", true)
        initSwitch(R.id.switch_show_source, "show_source_text", true)
        initSwitch(R.id.switch_dynamic_mode, "dynamic_mode", false)
        initSwitch(R.id.switch_show_subtitle_border, "show_subtitle_border", true)
        initSwitch(R.id.switch_show_subtitle_toolbar, "show_subtitle_toolbar", true)

        // ---- 字幕子项可见性 ----
        val subtitleGroup = menuView.findViewById<View>(R.id.group_subtitle_options)
        val currentMode = prefs.getString("result_window_mode", "follow") ?: "follow"
        subtitleGroup.visibility = if (currentMode == "subtitle") View.VISIBLE else View.GONE

        // ---- 初始化 radio 指示器 ----
        updateMenuRadioIndicators(menuView, currentMode)

        // ==================== 识别区域 ====================

        // 开启选择 / 关闭选择
        menuView.findViewById<View>(R.id.item_toggle_selection).setOnClickListener {
            if (floatingView == null) showFloatingSelection()
            else { windowManager.removeView(floatingView); floatingView = null }
            dismissMenuOverlay()
        }

        // 显示边框
        val switchBorder = menuView.findViewById<SwitchMaterial>(R.id.switch_show_border)
        menuView.findViewById<View>(R.id.item_show_border).setOnClickListener { switchBorder.toggle() }
        switchBorder.setOnCheckedChangeListener { _, isChecked ->
            prefs.edit().putBoolean("show_selection_border", isChecked).apply()
            floatingView?.findViewById<View>(R.id.selection_frame)?.setBackgroundResource(if (isChecked) R.drawable.selection_border else 0)
            val vis = if (isChecked) View.VISIBLE else View.GONE
            floatingView?.findViewById<View>(R.id.handle_left)?.visibility = vis
            floatingView?.findViewById<View>(R.id.handle_right)?.visibility = vis
            floatingView?.findViewById<View>(R.id.handle_top)?.visibility = vis
            floatingView?.findViewById<View>(R.id.handle_bottom)?.visibility = vis
        }

        // 操作胶囊
        val switchToolbar = menuView.findViewById<SwitchMaterial>(R.id.switch_show_toolbar)
        menuView.findViewById<View>(R.id.item_show_toolbar).setOnClickListener { switchToolbar.toggle() }
        switchToolbar.setOnCheckedChangeListener { _, isChecked ->
            prefs.edit().putBoolean("show_selection_toolbar", isChecked).apply()
            floatingView?.findViewById<View>(R.id.toolbar)?.visibility = if (isChecked) View.VISIBLE else View.GONE
            adjustSelectionWrapperMargin()
        }

        // ==================== 翻译结果 ====================

        // 显示原文
        val switchSource = menuView.findViewById<SwitchMaterial>(R.id.switch_show_source)
        menuView.findViewById<View>(R.id.item_show_source).setOnClickListener { switchSource.toggle() }
        switchSource.setOnCheckedChangeListener { _, isChecked ->
            prefs.edit().putBoolean("show_source_text", isChecked).apply()
        }

        // 跟随选区
        menuView.findViewById<View>(R.id.item_mode_follow).setOnClickListener {
            prefs.edit().putString("result_window_mode", "follow").apply()
            updateMenuRadioIndicators(menuView, "follow")
            subtitleGroup.visibility = View.GONE
            dismissMenuOverlay()
        }

        // 自由调节
        menuView.findViewById<View>(R.id.item_mode_fixed).setOnClickListener {
            prefs.edit().putString("result_window_mode", "fixed").apply()
            updateMenuRadioIndicators(menuView, "fixed")
            subtitleGroup.visibility = View.GONE
            dismissMenuOverlay()
        }

        // 字幕风格 — 选中时展开子项
        menuView.findViewById<View>(R.id.item_mode_subtitle).setOnClickListener {
            prefs.edit().putString("result_window_mode", "subtitle").apply()
            updateMenuRadioIndicators(menuView, "subtitle")
            subtitleGroup.visibility = View.VISIBLE
            dismissMenuOverlay()
        }

        // 字幕框线
        val switchSubBorder = menuView.findViewById<SwitchMaterial>(R.id.switch_show_subtitle_border)
        menuView.findViewById<View>(R.id.item_show_subtitle_border).setOnClickListener { switchSubBorder.toggle() }
        switchSubBorder.setOnCheckedChangeListener { _, isChecked ->
            prefs.edit().putBoolean("show_subtitle_border", isChecked).apply()
            resultView?.findViewById<View>(R.id.subtitle_result_card)
                ?.setBackgroundResource(if (isChecked) R.drawable.subtitle_frame_stroke else 0)
            val vis = if (isChecked) View.VISIBLE else View.GONE
            resultView?.findViewById<View>(R.id.subtitle_handle_left)?.visibility = vis
            resultView?.findViewById<View>(R.id.subtitle_handle_right)?.visibility = vis
            resultView?.findViewById<View>(R.id.subtitle_handle_top)?.visibility = vis
            resultView?.findViewById<View>(R.id.subtitle_handle_bottom)?.visibility = vis
        }

        // 字幕胶囊
        val switchSubToolbar = menuView.findViewById<SwitchMaterial>(R.id.switch_show_subtitle_toolbar)
        menuView.findViewById<View>(R.id.item_show_subtitle_toolbar).setOnClickListener { switchSubToolbar.toggle() }
        switchSubToolbar.setOnCheckedChangeListener { _, isChecked ->
            prefs.edit().putBoolean("show_subtitle_toolbar", isChecked).apply()
            resultView?.findViewById<View>(R.id.subtitle_toolbar_scroll)?.visibility =
                if (isChecked) View.VISIBLE else View.GONE
        }

        // ==================== 数据管理 ====================

        // 动态模式
        val switchDynamic = menuView.findViewById<SwitchMaterial>(R.id.switch_dynamic_mode)
        menuView.findViewById<View>(R.id.item_dynamic_mode).setOnClickListener { switchDynamic.toggle() }
        switchDynamic.setOnCheckedChangeListener { _, isChecked ->
            prefs.edit().putBoolean("dynamic_mode", isChecked).apply()
        }

        // 清空缓存
        menuView.findViewById<View>(R.id.item_clear_cache).setOnClickListener {
            cacheManager.clearCache()
            dismissMenuOverlay()
        }

        windowManager.addView(menuView, params)
    }

    private fun savePosition(x: Int, y: Int, w: Int, h: Int) {
        prefs.edit().putInt("selection_x", x).putInt("selection_y", y)
            .putInt("selection_w", w).putInt("selection_h", h).apply()
    }

    /**
     * 将基于 getLocationOnScreen 的选区（与 screenWidth/Height 一致）映射到实际截图帧尺寸。
     * 部分平板横屏时 VirtualDisplay 输出与 WindowMetrics 存在 1～2 像素或更大偏差，需按比例对齐。
     */
    private fun mapRectToCaptureFrame(screenRect: Rect, imgW: Int, imgH: Int): Rect {
        if (screenWidth <= 0 || screenHeight <= 0) return screenRect
        val dw = kotlin.math.abs(imgW - screenWidth)
        val dh = kotlin.math.abs(imgH - screenHeight)
        if (dw <= 2 && dh <= 2) {
            return Rect(
                screenRect.left.coerceIn(0, imgW - 1),
                screenRect.top.coerceIn(0, imgH - 1),
                screenRect.right.coerceIn(screenRect.left + 1, imgW),
                screenRect.bottom.coerceIn(screenRect.top + 1, imgH)
            )
        }
        val sx = imgW.toFloat() / screenWidth
        val sy = imgH.toFloat() / screenHeight
        var l = (screenRect.left * sx).roundToInt()
        var t = (screenRect.top * sy).roundToInt()
        var r = (screenRect.right * sx).roundToInt()
        var b = (screenRect.bottom * sy).roundToInt()
        l = l.coerceIn(0, imgW - 1)
        t = t.coerceIn(0, imgH - 1)
        r = r.coerceIn(l + 1, imgW)
        b = b.coerceIn(t + 1, imgH)
        return Rect(l, t, r, b)
    }

    private fun Int.dpToPx(): Int = (this * resources.displayMetrics.density).toInt()

    private fun captureArea(rect: Rect) {
        floatingView?.visibility = View.INVISIBLE
        Handler(Looper.getMainLooper()).postDelayed({
            realCapture(rect)
        }, 40)
    }

    private fun realCapture(rect: Rect) {
        updateScreenMetrics()
        imageReader = ImageReader.newInstance(screenWidth, screenHeight, PixelFormat.RGBA_8888, 2)
        virtualDisplay = mediaProjection?.createVirtualDisplay(
            "Screenshot", screenWidth, screenHeight, screenDensity,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR, imageReader?.surface, null, null
        )

        imageReader?.setOnImageAvailableListener({ reader ->
            val image = reader.acquireLatestImage()
            if (image == null) {
                floatingView?.alpha = 1f
                floatingView?.visibility = View.VISIBLE
                return@setOnImageAvailableListener
            }
            val imgW = image.width
            val imgH = image.height
            val planes = image.planes
            val buffer = planes[0].buffer
            val pixelStride = planes[0].pixelStride
            val rowStride = planes[0].rowStride
            val rowPadding = rowStride - pixelStride * imgW

            val fullBitmap = Bitmap.createBitmap(
                imgW + rowPadding / pixelStride, imgH, Bitmap.Config.ARGB_8888
            )
            fullBitmap.copyPixelsFromBuffer(buffer)
            image.close()
            stopVirtualDisplay()
            floatingView?.alpha = 1f
            floatingView?.visibility = View.VISIBLE

            // 若虚拟屏像素与当前记录的屏幕尺寸不一致（部分机型横屏），将选区映射到实际帧坐标
            val cropRect = mapRectToCaptureFrame(rect, imgW, imgH)

            val left = cropRect.left.coerceIn(0, fullBitmap.width - 1)
            val top = cropRect.top.coerceIn(0, fullBitmap.height - 1)
            val width = cropRect.width().coerceAtMost(fullBitmap.width - left)
            val height = cropRect.height().coerceAtMost(fullBitmap.height - top)

            if (width > 0 && height > 0) {
                val cropped = Bitmap.createBitmap(fullBitmap, left, top, width, height)
                fullBitmap.recycle()
                performOcrAndTranslate(cropped, cropRect)
            } else {
                fullBitmap.recycle()
                Toast.makeText(this@ScreenshotService, "选区无效", Toast.LENGTH_SHORT).show()
            }
        }, Handler(Looper.getMainLooper()))
    }

    private fun performOcrAndTranslate(bitmap: Bitmap, rect: Rect) {
        val ocrProvider = prefs.getString("ocr_provider", "local")
        val lang = prefs.getString("source_lang", "自动检测") ?: "自动检测"
        val isDynamic = prefs.getBoolean("dynamic_mode", false)
        val cachedText = cacheManager.getCachedResult(bitmap, rect, isDynamic)
        
        serviceScope.launch {
            try {
                val sourceText = cachedText ?: withContext(Dispatchers.IO) {
                    when (ocrProvider) {
                        "local" -> localOcrEngine.recognize(bitmap, lang)
                        "alibaba" -> {
                            val akId = prefs.getString("alibaba_access_key_id", "") ?: ""
                            val akSecret = prefs.getString("alibaba_access_key_secret", "") ?: ""
                            AlibabaOcrEngine(akId, akSecret).recognize(bitmap)
                        }
                        "paddle" -> {
                            val paddleToken = prefs.getString("paddle_ocr_token", "") ?: ""
                            PaddleLayoutParsingOcrEngine(paddleToken).recognize(bitmap)
                        }
                        "glm_ocr" -> {
                            val glmKey = prefs.getString("glm_ocr_api_key", "") ?: ""
                            GlmOcrEngine(glmKey).recognize(bitmap)
                        }
                        else -> {
                            val apiKey = prefs.getString("baidu_api_key", "") ?: ""
                            val secretKey = prefs.getString("baidu_secret_key", "") ?: ""
                            BaiduOcrEngine(apiKey, secretKey).recognize(bitmap)
                        }
                    }
                }

                if (sourceText.isNotBlank() && !sourceText.contains("失败")) {
                    if (cachedText == null) cacheManager.putCache(sourceText, bitmap, rect, isDynamic)
                    translateText(sourceText, rect)
                } else {
                    Toast.makeText(this@ScreenshotService, "未识别到文字", Toast.LENGTH_SHORT).show()
                }
            } catch (e: Exception) {
                AppLog.e("ScreenshotService", "performOcrAndTranslate failed", e)
                Toast.makeText(this@ScreenshotService, "错误: ${e.message}", Toast.LENGTH_SHORT).show()
            } finally {
                bitmap.recycle()
            }
        }
    }

    private suspend fun translateText(sourceText: String, rect: Rect) {
        val aiKey = prefs.getString("ai_api_key", "") ?: ""
        val baseUrl = prefs.getString("ai_base_url", "https://api.openai.com/v1") ?: ""
        val model = prefs.getString("ai_model", "gpt-3.5-turbo") ?: ""
        val sourceLang = prefs.getString("source_lang", "自动检测") ?: "自动检测"
        val targetLang = prefs.getString("target_lang", "中文") ?: "中文"
        val systemPrompt = prefs.getString("ai_system_prompt", "") ?: ""
        val userPromptTemplate = prefs.getString("ai_user_prompt", "") ?: ""

        if (aiKey.isBlank()) {
            withContext(Dispatchers.Main) { Toast.makeText(this@ScreenshotService, "请配置 AI Key", Toast.LENGTH_LONG).show() }
            return
        }

        val aiEngine = AiTranslationEngine(aiKey, baseUrl, model, systemPrompt, userPromptTemplate)
        val translatedText = withContext(Dispatchers.IO) { aiEngine.translate(sourceText, sourceLang, targetLang) }

        withContext(Dispatchers.Main) {
            showResultFloatingWindow(sourceText, translatedText, rect)
            saveToHistory(sourceText, translatedText)
        }
    }

    private fun saveToHistory(source: String, translated: String) {
        val historyJson = prefs.getString("translation_history", "[]") ?: "[]"
        val type = object : TypeToken<MutableList<HistoryItem>>() {}.type
        val history: MutableList<HistoryItem> = Gson().fromJson(historyJson, type)
        history.add(0, HistoryItem(source, translated, System.currentTimeMillis()))
        if (history.size > 20) history.removeAt(history.size - 1)
        prefs.edit().putString("translation_history", Gson().toJson(history)).apply()
    }

    private fun showResultFloatingWindow(source: String, translated: String, rect: Rect) {
        if (!canDrawOverlays()) return
        resultView?.let { try { windowManager.removeView(it) } catch(e:Exception){} }
        val mode = prefs.getString("result_window_mode", "follow") ?: "follow"
        if (mode == "subtitle") {
            showSubtitleResultWindow(source, translated, rect)
            return
        }

        try {
            resultView = overlayTransparentLayoutInflater().inflate(R.layout.layout_floating_result, null)
        } catch (e: Exception) {
            AppLog.e("ScreenshotService", "inflate layout_floating_result failed", e)
            throw e
        }
        val resultCard = resultView!!.findViewById<View>(R.id.result_card)
        val leftHandle = resultView!!.findViewById<View>(R.id.result_handle_left)
        val rightHandle = resultView!!.findViewById<View>(R.id.result_handle_right)
        val topHandle = resultView!!.findViewById<View>(R.id.result_handle_top)
        val bottomHandle = resultView!!.findViewById<View>(R.id.result_handle_bottom)
        val isFixedMode = mode == "fixed"

        val resultScroll = resultView!!.findViewById<ScrollView>(R.id.result_scroll)
        if (isFixedMode) {
            val savedWidth = prefs.getInt("result_fixed_width", 280.dpToPx())
            applyResultCardFixedLayout(resultCard, resultScroll, savedWidth)
        } else {
            applyResultCardFollowLayout(resultCard, resultScroll)
        }

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            if (isFixedMode) {
                x = prefs.getInt("result_fixed_x", rect.left)
                y = prefs.getInt("result_fixed_y", rect.bottom + 10.dpToPx())
            } else {
                x = rect.left
                y = rect.bottom + 10.dpToPx()
            }
            alpha = prefs.getFloat("result_alpha", 1.0f)
        }

        val tvSource = resultView!!.findViewById<TextView>(R.id.tv_source_text)
        val tvTranslated = resultView!!.findViewById<TextView>(R.id.tv_translated_text)
        val showSource = prefs.getBoolean("show_source_text", true)
        tvSource.visibility = if (showSource) View.VISIBLE else View.GONE
        tvSource.text = source
        tvTranslated.text = translated
        tvTranslated.textSize = prefs.getFloat("result_font_size", 15f)

        resultView!!.findViewById<View>(R.id.btn_close_result).setOnClickListener {
            windowManager.removeView(resultView)
            resultView = null
        }

        windowManager.addView(resultView, params)
        ensureResultViewOnTop()

        if (isFixedMode) {
            setupResultWindowAdjustments(params, resultCard, leftHandle, rightHandle, topHandle, bottomHandle)
        } else {
            leftHandle.visibility = View.GONE
            rightHandle.visibility = View.GONE
            topHandle.visibility = View.GONE
            bottomHandle.visibility = View.GONE
            resultCard.setOnTouchListener(null)
        }

        scheduleResultAutoClose()
    }

    private fun scheduleResultAutoClose() {
        val duration = prefs.getFloat("result_duration", 0.0f)
        if (duration > 0) {
            resultViewTimer?.removeCallbacksAndMessages(null)
            resultViewTimer = Handler(Looper.getMainLooper())
            resultViewTimer?.postDelayed({
                resultView?.let { try { windowManager.removeView(it) } catch(e:Exception){} }
                resultView = null
            }, (duration * 1000).toLong())
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun showSubtitleResultWindow(source: String, translated: String, selectionRect: Rect) {
        updateScreenMetrics()
        try {
            resultView = overlayTransparentLayoutInflater().inflate(R.layout.layout_floating_result_subtitle, null)
        } catch (e: Exception) {
            AppLog.e("ScreenshotService", "inflate layout_floating_result_subtitle failed", e)
            throw e
        }
        val toolbarScroll = resultView!!.findViewById<View>(R.id.subtitle_toolbar_scroll)
        val subtitleCard = resultView!!.findViewById<View>(R.id.subtitle_result_card)
        val resultScroll = resultView!!.findViewById<ScrollView>(R.id.subtitle_result_scroll)
        val left = resultView!!.findViewById<View>(R.id.subtitle_handle_left)
        val right = resultView!!.findViewById<View>(R.id.subtitle_handle_right)
        val top = resultView!!.findViewById<View>(R.id.subtitle_handle_top)
        val bottom = resultView!!.findViewById<View>(R.id.subtitle_handle_bottom)

        val cardW = prefs.getInt("subtitle_width", 280.dpToPx())
        // 高度随内容（WRAP_CONTENT），不写入 prefs；仅记忆位置与宽度直至用户再调
        applySubtitleCardLayout(subtitleCard, resultScroll, cardW)

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            if (prefs.contains("subtitle_x")) {
                x = prefs.getInt("subtitle_x", 0)
                y = prefs.getInt("subtitle_y", 0)
            } else {
                val (ix, iy) = computeSubtitleInitialPosition(selectionRect, cardW)
                x = ix
                y = iy
            }
            alpha = prefs.getFloat("result_alpha", 1.0f)
        }

        val tvSource = resultView!!.findViewById<TextView>(R.id.tv_subtitle_source)
        val tvTranslated = resultView!!.findViewById<StrokedTextView>(R.id.tv_subtitle_translated)
        val showSource = prefs.getBoolean("show_source_text", true)
        tvSource.visibility = if (showSource) View.VISIBLE else View.GONE
        tvSource.text = source
        tvTranslated.text = translated
        applySubtitleTextStyle(tvSource, tvTranslated)

        toolbarScroll.visibility = if (prefs.getBoolean("show_subtitle_toolbar", true)) View.VISIBLE else View.GONE
        subtitleCard.setBackgroundResource(
            if (prefs.getBoolean("show_subtitle_border", true)) R.drawable.subtitle_frame_stroke else 0
        )

        resultView!!.findViewById<View>(R.id.btn_close_subtitle_result).setOnClickListener {
            windowManager.removeView(resultView)
            resultView = null
        }

        setupSubtitleToolbarButtons(tvSource, tvTranslated, subtitleCard)

        windowManager.addView(resultView, params)

        resultView?.post {
            prefs.edit()
                .putInt("subtitle_x", params.x)
                .putInt("subtitle_y", params.y)
                .putInt("subtitle_width", subtitleCard.width)
                .apply()
        }

        setupSubtitleWindowAdjustments(params, subtitleCard, resultScroll, left, right, top, bottom)

        ensureResultViewOnTop()

        scheduleResultAutoClose()
    }

    private fun rectsOverlap(a: Rect, b: Rect): Boolean {
        return a.left < b.right && a.right > b.left && a.top < b.bottom && a.bottom > b.top
    }

    private fun getStatusBarInsetPx(): Int {
        val resId = resources.getIdentifier("status_bar_height", "dimen", "android")
        return if (resId > 0) resources.getDimensionPixelSize(resId) else 0
    }

    /**
     * 首次字幕窗口：优先靠顶居中；若与截图选区重叠则下移到选区下方，仍保持水平居中。
     */
    private fun computeSubtitleInitialPosition(selectionRect: Rect, cardWidth: Int): Pair<Int, Int> {
        val insetTop = getStatusBarInsetPx()
        val margin = 12.dpToPx()
        var x = ((screenWidth - cardWidth) / 2).coerceIn(0, (screenWidth - cardWidth).coerceAtLeast(0))
        var y = insetTop + margin
        val estH = (screenHeight / 4).coerceAtLeast(160.dpToPx())
        val candidate = Rect(x, y, x + cardWidth, y + estH)
        if (rectsOverlap(candidate, selectionRect)) {
            y = selectionRect.bottom + margin
            if (y + estH > screenHeight) {
                y = (screenHeight - estH).coerceAtLeast(insetTop + margin)
            }
            x = ((screenWidth - cardWidth) / 2).coerceIn(0, (screenWidth - cardWidth).coerceAtLeast(0))
        }
        return x to y
    }

    private fun applySubtitleTextStyle(tvSource: TextView, tvTranslated: StrokedTextView) {
        val fs = prefs.getFloat("result_font_size", 15f)
        tvSource.textSize = (fs * 0.85f).coerceAtLeast(10f)
        tvTranslated.textSize = fs
        val textColor = prefs.getInt("subtitle_text_color", Color.WHITE)
        tvSource.setTextColor(textColor)
        tvTranslated.setTextColor(textColor)
        val enabled = prefs.getBoolean("subtitle_stroke_enabled", true)
        val swDp = if (enabled) prefs.getFloat("subtitle_stroke_width_dp", 2f) else 0f
        tvTranslated.strokeWidthPx = swDp * resources.displayMetrics.density
        tvTranslated.strokeColorValue = prefs.getInt("subtitle_stroke_color", Color.BLACK)
    }

    private fun applySubtitleCardLayout(
        card: View,
        scroll: ScrollView,
        widthPx: Int,
        heightPx: Int? = null
    ) {
        val cardLp = card.layoutParams as FrameLayout.LayoutParams
        cardLp.width = widthPx
        if (heightPx != null && heightPx > 0) {
            cardLp.height = heightPx
            ensureSubtitleFixedHeightScrollLayout(card, scroll)
        } else {
            cardLp.height = FrameLayout.LayoutParams.WRAP_CONTENT
            val scrollLp = scroll.layoutParams as LinearLayout.LayoutParams
            scrollLp.height = LinearLayout.LayoutParams.WRAP_CONTENT
            scrollLp.weight = 0f
            scroll.isFillViewport = false
            scroll.layoutParams = scrollLp
        }
        card.layoutParams = cardLp
        card.requestLayout()
    }

    private fun setupSubtitleToolbarButtons(tvSource: TextView, tvTranslated: StrokedTextView, subtitleCard: View) {
        val refresh: () -> Unit = {
            applySubtitleTextStyle(tvSource, tvTranslated)
            subtitleCard.invalidate()
        }
        resultView?.findViewById<View>(R.id.btn_subtitle_font_size)?.setOnClickListener {
            SubtitleStyleDialogs.showFontSizeDialog(this, prefs, refresh)
        }
        resultView?.findViewById<View>(R.id.btn_subtitle_text_color)?.setOnClickListener {
            SubtitleStyleDialogs.showTextColorDialog(this, prefs, refresh)
        }
        resultView?.findViewById<View>(R.id.btn_subtitle_stroke_toggle)?.setOnClickListener {
            SubtitleStyleDialogs.showStrokeToggleDialog(this, prefs, refresh)
        }
        resultView?.findViewById<View>(R.id.btn_subtitle_stroke_color)?.setOnClickListener {
            SubtitleStyleDialogs.showStrokeColorDialog(this, prefs, refresh)
        }
        resultView?.findViewById<View>(R.id.btn_subtitle_stroke_width)?.setOnClickListener {
            SubtitleStyleDialogs.showStrokeWidthDialog(this, prefs, refresh)
        }
    }

    private fun ensureSubtitleFixedHeightScrollLayout(card: View, scroll: ScrollView) {
        val scrollLp = scroll.layoutParams as LinearLayout.LayoutParams
        scrollLp.height = 0
        scrollLp.weight = 1f
        scroll.isFillViewport = true
        scroll.layoutParams = scrollLp
    }

    private fun freezeSubtitleCardHeightIfWrap(card: View, scroll: ScrollView): Int {
        val cardLp = card.layoutParams as FrameLayout.LayoutParams
        if (cardLp.height != FrameLayout.LayoutParams.WRAP_CONTENT) return -1
        val h = card.height.takeIf { it > 0 } ?: 120.dpToPx()
        cardLp.height = h
        ensureSubtitleFixedHeightScrollLayout(card, scroll)
        card.layoutParams = cardLp
        card.requestLayout()
        return h
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun setupSubtitleWindowAdjustments(
        params: WindowManager.LayoutParams,
        subtitleCard: View,
        resultScroll: ScrollView,
        leftHandle: View,
        rightHandle: View,
        topHandle: View,
        bottomHandle: View
    ) {
        val minW = 180.dpToPx()
        val minH = 120.dpToPx()
        val maxW = (screenWidth * 0.95f).toInt()
        val maxH = (screenHeight * 0.9f).toInt()

        fun saveSubtitleWindowPrefs() {
            prefs.edit()
                .putInt("subtitle_x", params.x)
                .putInt("subtitle_y", params.y)
                .putInt("subtitle_width", subtitleCard.width)
                .apply()
        }

        fun createEdgeResizeListener(mode: ResultTouchMode): View.OnTouchListener {
            require(mode != ResultTouchMode.MOVE)
            return object : View.OnTouchListener {
                private var initialWindowX = 0
                private var initialWindowY = 0
                private var initialWidth = 0
                private var initialHeight = 0
                private var startRawX = 0f
                private var startRawY = 0f

                override fun onTouch(v: View, event: MotionEvent): Boolean {
                    when (event.action) {
                        MotionEvent.ACTION_DOWN -> {
                            initialWindowX = params.x
                            initialWindowY = params.y
                            startRawX = event.rawX
                            startRawY = event.rawY
                            initialWidth = subtitleCard.width
                            initialHeight = if (mode == ResultTouchMode.RESIZE_TOP || mode == ResultTouchMode.RESIZE_BOTTOM) {
                                val locked = freezeSubtitleCardHeightIfWrap(subtitleCard, resultScroll)
                                if (locked > 0) locked else subtitleCard.height.coerceAtLeast(minH)
                            } else {
                                subtitleCard.height.coerceAtLeast(minH)
                            }
                            return true
                        }
                        MotionEvent.ACTION_MOVE -> {
                            val deltaX = (event.rawX - startRawX).roundToInt()
                            val deltaY = (event.rawY - startRawY).roundToInt()
                            when (mode) {
                                ResultTouchMode.RESIZE_LEFT -> {
                                    var newW = (initialWidth - deltaX).coerceIn(minW, maxW)
                                    var newX = initialWindowX + (initialWidth - newW)
                                    newX = newX.coerceIn(
                                        0,
                                        (initialWindowX + initialWidth - minW).coerceAtLeast(0)
                                    )
                                    newW = (initialWindowX + initialWidth - newX).coerceIn(minW, maxW)
                                    params.x = newX
                                    subtitleCard.layoutParams.width = newW
                                    subtitleCard.requestLayout()
                                }
                                ResultTouchMode.RESIZE_RIGHT -> {
                                    var newW = (initialWidth + deltaX).coerceIn(minW, maxW)
                                    newW = newW.coerceAtMost(screenWidth - initialWindowX)
                                    subtitleCard.layoutParams.width = newW
                                    subtitleCard.requestLayout()
                                }
                                ResultTouchMode.RESIZE_TOP -> {
                                    var newH = (initialHeight - deltaY).coerceIn(minH, maxH)
                                    var newY = initialWindowY + (initialHeight - newH)
                                    newY = newY.coerceIn(
                                        0,
                                        (initialWindowY + initialHeight - minH).coerceAtLeast(0)
                                    )
                                    newH = (initialWindowY + initialHeight - newY).coerceIn(minH, maxH)
                                    params.y = newY
                                    subtitleCard.layoutParams.height = newH
                                    ensureSubtitleFixedHeightScrollLayout(subtitleCard, resultScroll)
                                    subtitleCard.requestLayout()
                                }
                                ResultTouchMode.RESIZE_BOTTOM -> {
                                    var newH = (initialHeight + deltaY).coerceIn(minH, maxH)
                                    newH = newH.coerceAtMost(screenHeight - initialWindowY)
                                    subtitleCard.layoutParams.height = newH
                                    ensureSubtitleFixedHeightScrollLayout(subtitleCard, resultScroll)
                                    subtitleCard.requestLayout()
                                }
                                else -> {}
                            }
                            windowManager.updateViewLayout(resultView, params)
                            return true
                        }
                        MotionEvent.ACTION_UP -> {
                            saveSubtitleWindowPrefs()
                            return true
                        }
                    }
                    return false
                }
            }
        }

        val showSubtitleBorder = prefs.getBoolean("show_subtitle_border", true)
        val subHandleVis = if (showSubtitleBorder) View.VISIBLE else View.GONE
        leftHandle.visibility = subHandleVis
        rightHandle.visibility = subHandleVis
        topHandle.visibility = subHandleVis
        bottomHandle.visibility = subHandleVis
        leftHandle.setOnTouchListener(createEdgeResizeListener(ResultTouchMode.RESIZE_LEFT))
        rightHandle.setOnTouchListener(createEdgeResizeListener(ResultTouchMode.RESIZE_RIGHT))
        topHandle.setOnTouchListener(createEdgeResizeListener(ResultTouchMode.RESIZE_TOP))
        bottomHandle.setOnTouchListener(createEdgeResizeListener(ResultTouchMode.RESIZE_BOTTOM))

        /** 卡片区域仅拖动窗口；改宽高请拖四边胶囊 */
        subtitleCard.setOnTouchListener(object : View.OnTouchListener {
            private var initialX = 0
            private var initialY = 0
            private var startRawX = 0f
            private var startRawY = 0f

            override fun onTouch(v: View, event: MotionEvent): Boolean {
                when (event.action) {
                    MotionEvent.ACTION_DOWN -> {
                        initialX = params.x
                        initialY = params.y
                        startRawX = event.rawX
                        startRawY = event.rawY
                        return true
                    }
                    MotionEvent.ACTION_MOVE -> {
                        val deltaX = (event.rawX - startRawX).roundToInt()
                        val deltaY = (event.rawY - startRawY).roundToInt()
                        val vw = resultView?.width?.takeIf { it > 0 }
                            ?: subtitleCard.width.coerceAtLeast(1)
                        val vh = resultView?.height?.takeIf { it > 0 }
                            ?: subtitleCard.height.coerceAtLeast(1)
                        val maxX = (screenWidth - vw).coerceAtLeast(0)
                        val maxY = (screenHeight - vh).coerceAtLeast(0)
                        params.x = (initialX + deltaX).coerceIn(0, maxX)
                        params.y = (initialY + deltaY).coerceIn(0, maxY)
                        windowManager.updateViewLayout(resultView, params)
                        return true
                    }
                    MotionEvent.ACTION_UP -> {
                        saveSubtitleWindowPrefs()
                        return true
                    }
                }
                return false
            }
        })
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun setupResultWindowAdjustments(
        params: WindowManager.LayoutParams,
        resultCard: View,
        leftHandle: View,
        rightHandle: View,
        topHandle: View,
        bottomHandle: View
    ) {
        leftHandle.visibility = View.GONE
        rightHandle.visibility = View.GONE
        topHandle.visibility = View.GONE
        bottomHandle.visibility = View.GONE

        resultCard.setOnTouchListener(object : View.OnTouchListener {
            private var initialX = 0
            private var initialY = 0
            private var initialWidth = 0
            private var startRawX = 0f
            private var startRawY = 0f
            private var mode = ResultTouchMode.MOVE
            private val edgeSize by lazy { 24.dpToPx() }

            override fun onTouch(v: View, event: MotionEvent): Boolean {
                if (event.action == MotionEvent.ACTION_DOWN) {
                    initialX = params.x
                    initialY = params.y
                    startRawX = event.rawX
                    startRawY = event.rawY
                    val localX = event.x
                    mode = when {
                        localX <= edgeSize -> ResultTouchMode.RESIZE_LEFT
                        localX >= (v.width - edgeSize) -> ResultTouchMode.RESIZE_RIGHT
                        else -> ResultTouchMode.MOVE
                    }
                    initialWidth = resultCard.width
                    return true
                }
                if (event.action == MotionEvent.ACTION_MOVE) {
                    val deltaX = (event.rawX - startRawX).roundToInt()
                    val deltaY = (event.rawY - startRawY).roundToInt()
                    val minW = 180.dpToPx()
                    val maxW = (screenWidth * 0.95f).toInt()
                    var newW = initialWidth
                    var newX = initialX

                    when (mode) {
                        ResultTouchMode.MOVE -> {
                            val vw = resultView?.width?.takeIf { it > 0 }
                                ?: resultCard.width.coerceAtLeast(1)
                            val vh = resultView?.height?.takeIf { it > 0 }
                                ?: resultCard.height.coerceAtLeast(1)
                            val maxX = (screenWidth - vw).coerceAtLeast(0)
                            val maxY = (screenHeight - vh).coerceAtLeast(0)
                            params.x = (initialX + deltaX).coerceIn(0, maxX)
                            params.y = (initialY + deltaY).coerceIn(0, maxY)
                        }
                        ResultTouchMode.RESIZE_LEFT -> {
                            newW = (initialWidth - deltaX).coerceIn(minW, maxW)
                            newX = initialX + (initialWidth - newW)
                            newX = newX.coerceIn(0, (initialX + initialWidth - minW).coerceAtLeast(0))
                            newW = (initialX + initialWidth - newX).coerceIn(minW, maxW)
                            params.x = newX
                            resultCard.layoutParams.width = newW
                            resultCard.requestLayout()
                        }
                        ResultTouchMode.RESIZE_RIGHT -> {
                            newW = (initialWidth + deltaX).coerceIn(minW, maxW)
                            newW = newW.coerceAtMost(screenWidth - initialX)
                            resultCard.layoutParams.width = newW
                            resultCard.requestLayout()
                        }
                        ResultTouchMode.RESIZE_TOP,
                        ResultTouchMode.RESIZE_BOTTOM -> { }
                    }
                    windowManager.updateViewLayout(resultView, params)
                    return true
                }
                if (event.action == MotionEvent.ACTION_UP) {
                    prefs.edit()
                        .putInt("result_fixed_x", params.x)
                        .putInt("result_fixed_y", params.y)
                        .putInt("result_fixed_width", resultCard.width)
                        .remove("result_fixed_height")
                        .apply()
                    return true
                }
                return false
            }
        })
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel("screenshot_service", "翻译工具", NotificationManager.IMPORTANCE_LOW)
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
    }

    private fun createNotification(): Notification {
        return NotificationCompat.Builder(this, "screenshot_service")
            .setContentTitle("翻译工具正在运行")
            .setContentText("已准备好截取屏幕文字")
            .build()
    }

    override fun onDestroy() {
        super.onDestroy()
        orientationListener?.disable()
        floatingView?.let { try { windowManager.removeView(it) } catch(e:Exception){} }
        ballView?.let { try { windowManager.removeView(it) } catch(e:Exception){} }
        resultView?.let { try { windowManager.removeView(it) } catch(e:Exception){} }
        menuOverlayView?.let { try { windowManager.removeView(it) } catch (_: Exception) {} }
        resultViewTimer?.removeCallbacksAndMessages(null)
        mediaProjection?.stop()
        stopVirtualDisplay()
    }

    private fun stopVirtualDisplay() {
        virtualDisplay?.release()
        virtualDisplay = null
        imageReader?.close()
        imageReader = null
    }

    /**
     * 自由调节模式：持久化宽度为 [widthPx]；高度始终随译文内容撑开（不持久化高度）。
     */
    private fun applyResultCardFixedLayout(resultCard: View, resultScroll: ScrollView, widthPx: Int) {
        val cardLp = resultCard.layoutParams as FrameLayout.LayoutParams
        cardLp.width = widthPx
        cardLp.height = FrameLayout.LayoutParams.WRAP_CONTENT
        val scrollLp = resultScroll.layoutParams as LinearLayout.LayoutParams
        scrollLp.height = LinearLayout.LayoutParams.WRAP_CONTENT
        scrollLp.weight = 0f
        resultScroll.isFillViewport = false
        resultScroll.layoutParams = scrollLp
        resultCard.layoutParams = cardLp
        resultCard.requestLayout()
    }

    /** 跟随选区模式：与布局默认一致（约 280dp 宽、高度随内容），不套用自由调节的持久化尺寸。 */
    private fun applyResultCardFollowLayout(resultCard: View, resultScroll: ScrollView) {
        val cardLp = resultCard.layoutParams as FrameLayout.LayoutParams
        cardLp.width = 280.dpToPx()
        cardLp.height = FrameLayout.LayoutParams.WRAP_CONTENT
        resultCard.layoutParams = cardLp
        val scrollLp = resultScroll.layoutParams as LinearLayout.LayoutParams
        scrollLp.height = LinearLayout.LayoutParams.WRAP_CONTENT
        scrollLp.weight = 0f
        resultScroll.layoutParams = scrollLp
        resultCard.requestLayout()
    }

    private fun canDrawOverlays(): Boolean {
        return Build.VERSION.SDK_INT < Build.VERSION_CODES.M || Settings.canDrawOverlays(this)
    }
}
