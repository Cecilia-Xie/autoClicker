package com.autoclicker.basic

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.graphics.Path
import android.graphics.PixelFormat
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.Gravity
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.WindowManager
import android.view.animation.AlphaAnimation
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import android.view.accessibility.AccessibilityEvent
import kotlin.math.abs

class AutoClickAccessibilityService : AccessibilityService() {

    companion object {
        @Volatile
        var isRunning: Boolean = false
            private set
    }

    private data class ClickPoint(
        var x: Int = 300,
        var y: Int = 600,
        var delayMs: Long = 0L
    )

    private val handler = Handler(Looper.getMainLooper())
    private var startedAt = 0L
    private var isClicking = false
    private val clickPoints = mutableListOf(ClickPoint())
    private var selectedPointIndex = 0
    private var intervalMs: Long = 300L
    private var durationMs: Long = 0L

    private var windowManager: WindowManager? = null
    private var floatingButton: TextView? = null
    private var panelView: View? = null
    private var pickMaskView: View? = null
    private val clickIndicators = mutableMapOf<Int, View>()

    private lateinit var bubbleParams: WindowManager.LayoutParams
    private lateinit var panelParams: WindowManager.LayoutParams

    private val clickTask = object : Runnable {
        override fun run() {
            if (!isClicking) return

            if (durationMs > 0) {
                val elapsed = SystemClock.elapsedRealtime() - startedAt
                if (elapsed >= durationMs) {
                    stopClicking(showToast = true, autoStopped = true)
                    return
                }
            }

            clickPoints.forEachIndexed { index, point ->
                if (point.delayMs > 0) {
                    handler.postDelayed({
                        if (isClicking) {
                            performSingleClick(point.x, point.y)
                            showClickIndicator(point.x, point.y, index)
                        }
                    }, point.delayMs)
                } else {
                    performSingleClick(point.x, point.y)
                    showClickIndicator(point.x, point.y, index)
                }
            }

            handler.postDelayed(this, intervalMs)
        }
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        isRunning = true
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        initOverlay()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) = Unit

    override fun onInterrupt() = Unit

    override fun onDestroy() {
        cleanupAll()
        isRunning = false
        super.onDestroy()
    }

    private fun initOverlay() {
        val wm = windowManager ?: return
        if (floatingButton != null) return

        floatingButton = TextView(this).apply {
            text = getString(R.string.floating_button_text)
            setTextColor(Color.WHITE)
            setBackgroundColor(Color.parseColor("#AA6750A4"))
            textSize = 18f
            setPadding(28, 18, 28, 18)
            setupFloatingButtonTouch(this)
        }

        panelView = LayoutInflater.from(this).inflate(R.layout.view_overlay_panel, null).apply {
            setupPanelActions(this)
            visibility = View.GONE
        }

        bubbleParams = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.START or Gravity.CENTER_VERTICAL
            x = 0
            y = 0
        }

        panelParams = WindowManager.LayoutParams(
            dp(320),
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.START or Gravity.CENTER_VERTICAL
            x = dp(62)
            y = 0
        }

        wm.addView(floatingButton, bubbleParams)
        wm.addView(panelView, panelParams)
        renderConfig()
    }

    private fun setupPanelActions(root: View) {
        root.findViewById<Button>(R.id.addPointBtn).setOnClickListener {
            clickPoints.add(ClickPoint())
            renderConfig()
        }

        root.findViewById<Button>(R.id.pickCoordBtn).setOnClickListener {
            startPickPoint()
        }

        root.findViewById<Button>(R.id.intervalMinusBtn).setOnClickListener {
            intervalMs = (intervalMs - 100L).coerceAtLeast(100L)
            renderConfig()
        }

        root.findViewById<Button>(R.id.intervalPlusBtn).setOnClickListener {
            intervalMs = (intervalMs + 100L).coerceAtMost(5_000L)
            renderConfig()
        }

        root.findViewById<Button>(R.id.selectedDelayMinusBtn).setOnClickListener {
            val point = getSelectedPointOrNull() ?: return@setOnClickListener
            point.delayMs = (point.delayMs - 100L).coerceAtLeast(0L)
            renderConfig()
        }

        root.findViewById<Button>(R.id.selectedDelayPlusBtn).setOnClickListener {
            val point = getSelectedPointOrNull() ?: return@setOnClickListener
            point.delayMs = (point.delayMs + 100L).coerceAtMost(10_000L)
            renderConfig()
        }

        root.findViewById<Button>(R.id.durationMinusBtn).setOnClickListener {
            durationMs = (durationMs - 1_000L).coerceAtLeast(0L)
            renderConfig()
        }

        root.findViewById<Button>(R.id.durationPlusBtn).setOnClickListener {
            durationMs = (durationMs + 1_000L).coerceAtMost(120 * 60 * 1_000L)
            renderConfig()
        }

        root.findViewById<Button>(R.id.startBtn).setOnClickListener {
            startClicking()
        }

        root.findViewById<Button>(R.id.stopBtn).setOnClickListener {
            stopClicking(showToast = true, autoStopped = false)
        }

        root.findViewById<Button>(R.id.exitBtn).setOnClickListener {
            exitApp()
        }
    }

    private fun togglePanel() {
        panelView?.let { panel ->
            val opening = panel.visibility != View.VISIBLE
            if (opening && isClicking) {
                stopClicking(showToast = false, autoStopped = false)
                Toast.makeText(this, getString(R.string.toast_panel_pause_clicking), Toast.LENGTH_SHORT).show()
            }
            panel.visibility = if (opening) View.VISIBLE else View.GONE
        }
    }

    private fun startPickPoint() {
        if (pickMaskView != null) return
        val wm = windowManager ?: return
        Toast.makeText(this, getString(R.string.toast_pick_tip), Toast.LENGTH_SHORT).show()

        val tipView = TextView(this).apply {
            setBackgroundColor(Color.parseColor("#66000000"))
            setTextColor(Color.WHITE)
            textSize = 20f
            gravity = Gravity.CENTER
            text = getString(R.string.toast_pick_tip)
            setOnTouchListener { _, event ->
                if (event.action == MotionEvent.ACTION_DOWN) {
                    val point = getSelectedPointOrNull()
                    if (point != null) {
                        point.x = event.rawX.toInt()
                        point.y = event.rawY.toInt()
                        renderConfig()
                    }
                    removePickMask()
                    true
                } else {
                    false
                }
            }
        }

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        )

        pickMaskView = tipView
        wm.addView(tipView, params)
    }

    private fun removePickMask() {
        val wm = windowManager ?: return
        pickMaskView?.let {
            wm.removeView(it)
            pickMaskView = null
        }
    }

    private fun renderConfig() {
        val root = panelView ?: return

        val selectedPoint = getSelectedPointOrNull()
        root.findViewById<TextView>(R.id.coordText).text =
            if (selectedPoint != null) {
                getString(R.string.coord_value, selectedPoint.x, selectedPoint.y)
            } else {
                getString(R.string.coord_value, 0, 0)
            }

        root.findViewById<TextView>(R.id.selectedPointText).text =
            getString(R.string.selected_point_value, selectedPointIndex + 1)

        root.findViewById<TextView>(R.id.selectedDelayText).text =
            getString(R.string.selected_delay_value, selectedPoint?.delayMs ?: 0L)

        root.findViewById<TextView>(R.id.intervalText).text =
            getString(R.string.interval_value, intervalMs)

        root.findViewById<TextView>(R.id.durationText).text =
            getString(R.string.duration_value, durationMs)

        val pointsContainer = root.findViewById<LinearLayout>(R.id.pointsContainer)
        pointsContainer.removeAllViews()

        clickPoints.forEachIndexed { index, point ->
            val pointView = createPointView(index, point)
            pointsContainer.addView(pointView)
        }
    }

    private fun createPointView(index: Int, point: ClickPoint): View {
        val isSelected = index == selectedPointIndex
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, dp(8), 0, dp(8))
            setBackgroundColor(if (isSelected) Color.parseColor("#66FFD54F") else Color.parseColor("#33FFFFFF"))
            setOnClickListener {
                selectedPointIndex = index
                renderConfig()
            }
        }

        val headerLayout = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
        }

        val titleText = TextView(this).apply {
            text = "坐标点 ${index + 1}"
            setTextColor(if (isSelected) Color.parseColor("#212121") else Color.parseColor("#FFD700"))
            textSize = 14f
        }

        val deleteBtn = Button(this).apply {
            text = "删除"
            setOnClickListener {
                if (clickPoints.size > 1) {
                    clickPoints.removeAt(index)
                    selectedPointIndex = selectedPointIndex.coerceAtMost(clickPoints.lastIndex)
                    renderConfig()
                } else {
                    Toast.makeText(this@AutoClickAccessibilityService, "至少保留一个坐标点", Toast.LENGTH_SHORT).show()
                }
            }
        }

        headerLayout.addView(titleText)
        headerLayout.addView(deleteBtn)

        val coordText = TextView(this).apply {
            text = "坐标: (${point.x}, ${point.y})"
            setTextColor(Color.WHITE)
            textSize = 12f
        }

        val delayText = TextView(this).apply {
            text = "延迟: ${point.delayMs} ms"
            setTextColor(Color.WHITE)
            textSize = 12f
        }

        layout.addView(headerLayout)
        layout.addView(coordText)
        layout.addView(delayText)

        return layout
    }

    private fun startClicking() {
        stopClicking(showToast = false, autoStopped = false)
        panelView?.visibility = View.GONE
        handler.postDelayed({
            startedAt = SystemClock.elapsedRealtime()
            isClicking = true
            handler.post(clickTask)
            Toast.makeText(this, getString(R.string.toast_started), Toast.LENGTH_SHORT).show()
        }, 150L)
    }

    private fun stopClicking(showToast: Boolean, autoStopped: Boolean) {
        isClicking = false
        handler.removeCallbacks(clickTask)
        hideAllIndicators()
        if (showToast) {
            val msg = if (autoStopped) {
                getString(R.string.toast_auto_stopped)
            } else {
                getString(R.string.toast_stopped)
            }
            Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
        }
    }

    private fun performSingleClick(x: Int, y: Int) {
        val path = Path().apply { moveTo(x.toFloat(), y.toFloat()) }
        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0, 40))
            .build()
        dispatchGesture(gesture, null, null)
    }

    private fun showClickIndicator(x: Int, y: Int, pointIndex: Int) {
        val wm = windowManager ?: return

        hideAllIndicators()

        val indicator = View(this).apply {
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(Color.parseColor("#55FF4444"))
                setStroke(dp(2), Color.parseColor("#EEFF4444"))
            }
        }

        val size = dp(34)
        val params = WindowManager.LayoutParams(
            size,
            size,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.START or Gravity.TOP
            this.x = x - size / 2
            this.y = y - size / 2
        }

        clickIndicators[pointIndex] = indicator
        wm.addView(indicator, params)

        val fadeOut = AlphaAnimation(1.0f, 0.0f).apply {
            duration = 400
            startOffset = 200
        }

        indicator.startAnimation(fadeOut)

        handler.postDelayed({
            try {
                if (clickIndicators.containsKey(pointIndex)) {
                    wm.removeView(indicator)
                    clickIndicators.remove(pointIndex)
                }
            } catch (e: Exception) {
            }
        }, 600)
    }

    private fun hideAllIndicators() {
        val wm = windowManager ?: return
        clickIndicators.forEach { (_, view) ->
            try {
                wm.removeView(view)
            } catch (e: Exception) {
            }
        }
        clickIndicators.clear()
    }

    private fun exitApp() {
        stopClicking(showToast = false, autoStopped = false)
        Toast.makeText(this, getString(R.string.toast_exit), Toast.LENGTH_SHORT).show()
        cleanupAll()
        disableSelf()
    }

    private fun cleanupAll() {
        stopClicking(showToast = false, autoStopped = false)
        removePickMask()
        hideAllIndicators()
        val wm = windowManager ?: return
        panelView?.let {
            wm.removeView(it)
            panelView = null
        }
        floatingButton?.let {
            wm.removeView(it)
            floatingButton = null
        }
    }

    private fun dp(value: Int): Int {
        return (value * resources.displayMetrics.density).toInt()
    }

    private fun getSelectedPointOrNull(): ClickPoint? {
        if (clickPoints.isEmpty()) return null
        selectedPointIndex = selectedPointIndex.coerceIn(0, clickPoints.lastIndex)
        return clickPoints[selectedPointIndex]
    }

    private fun setupFloatingButtonTouch(button: TextView) {
        val touchSlop = ViewConfiguration.get(this).scaledTouchSlop
        val longPressTimeout = ViewConfiguration.getLongPressTimeout().toLong()
        var downRawX = 0f
        var downRawY = 0f
        var startX = 0
        var startY = 0
        var moved = false
        var longPressed = false
        var blockClick = false
        val longPressRunnable = Runnable { longPressed = true }

        button.setOnTouchListener { _, event ->
            val wm = windowManager ?: return@setOnTouchListener false
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downRawX = event.rawX
                    downRawY = event.rawY
                    startX = bubbleParams.x
                    startY = bubbleParams.y
                    moved = false
                    longPressed = false
                    blockClick = false
                    handler.removeCallbacks(longPressRunnable)
                    handler.postDelayed(longPressRunnable, longPressTimeout)
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = (event.rawX - downRawX).toInt()
                    val dy = (event.rawY - downRawY).toInt()
                    val overSlop = abs(dx) > touchSlop || abs(dy) > touchSlop
                    if (!longPressed) {
                        if (overSlop) {
                            blockClick = true
                            handler.removeCallbacks(longPressRunnable)
                        }
                        return@setOnTouchListener true
                    }
                    if (!moved && overSlop) {
                        moved = true
                    }
                    if (moved) {
                        bubbleParams.x = startX + dx
                        bubbleParams.y = startY + dy
                        wm.updateViewLayout(button, bubbleParams)
                    }
                    true
                }
                MotionEvent.ACTION_UP -> {
                    handler.removeCallbacks(longPressRunnable)
                    if (!moved && !longPressed && !blockClick) {
                        togglePanel()
                    }
                    true
                }
                MotionEvent.ACTION_CANCEL -> {
                    handler.removeCallbacks(longPressRunnable)
                    true
                }
                else -> false
            }
        }
    }
}
