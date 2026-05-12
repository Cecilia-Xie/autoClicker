package com.autoclicker.basic

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Color
import android.graphics.Path
import android.graphics.PixelFormat
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.Gravity
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import android.view.accessibility.AccessibilityEvent

class AutoClickAccessibilityService : AccessibilityService() {

    companion object {
        @Volatile
        var isRunning: Boolean = false
            private set
    }

    private data class ClickConfig(
        var targetX: Int = 300,
        var targetY: Int = 600,
        var intervalMs: Long = 300L,
        var durationMs: Long = 0L
    )

    private val handler = Handler(Looper.getMainLooper())
    private var startedAt = 0L
    private var isClicking = false
    private val clickConfig = ClickConfig()

    private var windowManager: WindowManager? = null
    private var floatingButton: TextView? = null
    private var panelView: View? = null
    private var pickMaskView: View? = null

    private lateinit var bubbleParams: WindowManager.LayoutParams
    private lateinit var panelParams: WindowManager.LayoutParams

    private val clickTask = object : Runnable {
        override fun run() {
            if (!isClicking) return

            if (clickConfig.durationMs > 0) {
                val elapsed = SystemClock.elapsedRealtime() - startedAt
                if (elapsed >= clickConfig.durationMs) {
                    stopClicking(showToast = true, autoStopped = true)
                    return
                }
            }

            performSingleClick(clickConfig.targetX, clickConfig.targetY)
            handler.postDelayed(this, clickConfig.intervalMs)
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
            setOnClickListener { togglePanel() }
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
            dp(280),
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
        root.findViewById<Button>(R.id.pickCoordBtn).setOnClickListener { startPickPoint() }
        root.findViewById<Button>(R.id.intervalMinusBtn).setOnClickListener {
            clickConfig.intervalMs = (clickConfig.intervalMs - 50L).coerceAtLeast(50L)
            renderConfig()
        }
        root.findViewById<Button>(R.id.intervalPlusBtn).setOnClickListener {
            clickConfig.intervalMs = (clickConfig.intervalMs + 50L).coerceAtMost(5_000L)
            renderConfig()
        }
        root.findViewById<Button>(R.id.durationMinusBtn).setOnClickListener {
            clickConfig.durationMs = (clickConfig.durationMs - 1_000L).coerceAtLeast(0L)
            renderConfig()
        }
        root.findViewById<Button>(R.id.durationPlusBtn).setOnClickListener {
            clickConfig.durationMs = (clickConfig.durationMs + 1_000L).coerceAtMost(120 * 60 * 1_000L)
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
            panel.visibility = if (panel.visibility == View.VISIBLE) View.GONE else View.VISIBLE
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
                    clickConfig.targetX = event.rawX.toInt()
                    clickConfig.targetY = event.rawY.toInt()
                    renderConfig()
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
        root.findViewById<TextView>(R.id.coordText).text =
            getString(R.string.coord_value, clickConfig.targetX, clickConfig.targetY)
        root.findViewById<TextView>(R.id.intervalText).text =
            getString(R.string.interval_value, clickConfig.intervalMs)
        root.findViewById<TextView>(R.id.durationText).text =
            getString(R.string.duration_value, clickConfig.durationMs)
    }

    private fun startClicking() {
        stopClicking(showToast = false, autoStopped = false)
        startedAt = SystemClock.elapsedRealtime()
        isClicking = true
        handler.post(clickTask)
        Toast.makeText(this, getString(R.string.toast_started), Toast.LENGTH_SHORT).show()
    }

    private fun stopClicking(showToast: Boolean, autoStopped: Boolean) {
        isClicking = false
        handler.removeCallbacks(clickTask)
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

    private fun exitApp() {
        stopClicking(showToast = false, autoStopped = false)
        Toast.makeText(this, getString(R.string.toast_exit), Toast.LENGTH_SHORT).show()
        cleanupAll()
        disableSelf()
    }

    private fun cleanupAll() {
        stopClicking(showToast = false, autoStopped = false)
        removePickMask()
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
}
