package com.autoclicker.basic

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.text.Editable
import android.text.TextWatcher
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
import android.view.inputmethod.InputMethodManager
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import android.view.accessibility.AccessibilityEvent
import java.math.BigDecimal
import java.math.RoundingMode
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
        var delayMs: Long = 0L,
        var intervalMs: Long = 300L,
        var durationMs: Long = 0L
    )

    private data class PointRunState(
        val pointIndex: Int,
        var nextClickAt: Long,
        val endsAt: Long?
    )

    private val handler = Handler(Looper.getMainLooper())
    private var isClicking = false
    private val clickPoints = mutableListOf(ClickPoint())
    private val pointRunStates = mutableListOf<PointRunState>()
    private var selectedPointIndex = 0
    private var runGeneration = 0L
    private var pendingStartTask: Runnable? = null
    private var isRenderingConfig = false

    private var windowManager: WindowManager? = null
    private var floatingButton: TextView? = null
    private var panelView: View? = null
    private var pickMaskView: View? = null
    private val clickIndicators = mutableMapOf<Int, View>()

    private lateinit var bubbleParams: WindowManager.LayoutParams
    private lateinit var panelParams: WindowManager.LayoutParams

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
            WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.START or Gravity.CENTER_VERTICAL
            x = dp(62)
            y = 0
        }

        wm.addView(floatingButton, bubbleParams)
        wm.addView(panelView, panelParams)
        if (clickPoints.isEmpty()) {
            clickPoints.add(ClickPoint())
        }
        renderConfig()
    }

    private fun setupPanelActions(root: View) {
        setupTimeInputs(root)

        root.findViewById<Button>(R.id.addPointBtn).setOnClickListener {
            clickPoints.add(ClickPoint())
            selectedPointIndex = clickPoints.lastIndex
            renderConfig()
        }

        root.findViewById<Button>(R.id.pickCoordBtn).setOnClickListener {
            startPickPoint()
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
            if (opening && (isClicking || pendingStartTask != null)) {
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

        isRenderingConfig = true
        root.findViewById<EditText>(R.id.delayInput).setText(formatMillisecondsAsSeconds(selectedPoint?.delayMs ?: 0L))
        root.findViewById<EditText>(R.id.intervalInput).setText(formatMillisecondsAsSeconds(selectedPoint?.intervalMs ?: 300L))
        root.findViewById<EditText>(R.id.durationInput).setText(formatMillisecondsAsSeconds(selectedPoint?.durationMs ?: 0L))
        isRenderingConfig = false

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
            setPadding(dp(8), dp(8), dp(8), dp(8))
            background = GradientDrawable().apply {
                cornerRadius = dp(6).toFloat()
                if (isSelected) {
                    setColor(Color.parseColor("#DD1565C0"))
                    setStroke(dp(2), Color.parseColor("#FF90CAF9"))
                } else {
                    setColor(Color.parseColor("#55343A40"))
                    setStroke(dp(1), Color.parseColor("#6670787F"))
                }
            }
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
            setTextColor(if (isSelected) Color.WHITE else Color.parseColor("#FFFFC857"))
            textSize = 14f
        }

        val deleteBtn = Button(this).apply {
            text = "删除"
            setOnClickListener {
                if (clickPoints.size > 1) {
                    clickPoints.removeAt(index)
                    if (index < selectedPointIndex) {
                        selectedPointIndex--
                    } else {
                        selectedPointIndex = selectedPointIndex.coerceAtMost(clickPoints.lastIndex)
                    }
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
            text = "延迟: ${formatMillisecondsAsSeconds(point.delayMs)} 秒　" +
                "间隔: ${formatMillisecondsAsSeconds(point.intervalMs)} 秒\n" +
                "总时长: ${formatMillisecondsAsSeconds(point.durationMs)} 秒"
            setTextColor(if (isSelected) Color.WHITE else Color.parseColor("#FFE0E0E0"))
            textSize = 12f
        }

        layout.addView(headerLayout)
        layout.addView(coordText)
        layout.addView(delayText)

        return layout
    }

    private fun setupTimeInputs(root: View) {
        root.findViewById<EditText>(R.id.delayInput).addTextChangedListener(
            timeInputWatcher { value ->
                getSelectedPointOrNull()?.delayMs = value
            }
        )
        root.findViewById<EditText>(R.id.intervalInput).addTextChangedListener(
            timeInputWatcher { value ->
                if (value > 0L) getSelectedPointOrNull()?.intervalMs = value
            }
        )
        root.findViewById<EditText>(R.id.durationInput).addTextChangedListener(
            timeInputWatcher { value ->
                getSelectedPointOrNull()?.durationMs = value
            }
        )
    }

    private fun timeInputWatcher(onValidValue: (Long) -> Unit): TextWatcher {
        return object : TextWatcher {
            override fun beforeTextChanged(text: CharSequence?, start: Int, count: Int, after: Int) = Unit

            override fun onTextChanged(text: CharSequence?, start: Int, before: Int, count: Int) {
                if (isRenderingConfig) return
                parseSecondsToMilliseconds(text?.toString())?.let(onValidValue)
            }

            override fun afterTextChanged(editable: Editable?) = Unit
        }
    }

    private fun readAndValidateInputs(root: View): Boolean {
        val delayInput = root.findViewById<EditText>(R.id.delayInput)
        val intervalInput = root.findViewById<EditText>(R.id.intervalInput)
        val durationInput = root.findViewById<EditText>(R.id.durationInput)

        val delay = parseSecondsToMilliseconds(delayInput.text.toString())
        if (delay == null) {
            showInputError(delayInput, R.string.toast_invalid_delay)
            return false
        }

        val interval = parseSecondsToMilliseconds(intervalInput.text.toString())
        if (interval == null || interval <= 0L) {
            showInputError(intervalInput, R.string.toast_invalid_interval)
            return false
        }

        val duration = parseSecondsToMilliseconds(durationInput.text.toString())
        if (duration == null) {
            showInputError(durationInput, R.string.toast_invalid_duration)
            return false
        }

        getSelectedPointOrNull()?.apply {
            delayMs = delay
            intervalMs = interval
            durationMs = duration
        }
        return true
    }

    private fun showInputError(input: EditText, messageRes: Int) {
        input.requestFocus()
        input.selectAll()
        Toast.makeText(this, getString(messageRes), Toast.LENGTH_SHORT).show()
    }

    private fun parseSecondsToMilliseconds(text: String?): Long? {
        val seconds = text?.trim()?.toBigDecimalOrNull() ?: return null
        if (seconds < BigDecimal.ZERO) return null
        val milliseconds = seconds.multiply(BigDecimal(1_000)).setScale(0, RoundingMode.HALF_UP)
        if (milliseconds > BigDecimal.valueOf(Long.MAX_VALUE)) return null
        return milliseconds.toLong()
    }

    private fun formatMillisecondsAsSeconds(milliseconds: Long): String {
        return BigDecimal.valueOf(milliseconds)
            .divide(BigDecimal(1_000))
            .stripTrailingZeros()
            .toPlainString()
    }

    private fun startClicking() {
        val root = panelView ?: return
        if (!readAndValidateInputs(root)) return
        if (clickPoints.isEmpty()) return

        stopClicking(showToast = false, autoStopped = false)
        root.clearFocus()
        (getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager)
            .hideSoftInputFromWindow(root.windowToken, 0)
        panelView?.visibility = View.GONE
        runGeneration++
        val generation = runGeneration
        val startTask = Runnable {
            if (generation != runGeneration) return@Runnable
            pendingStartTask = null
            val startedAt = SystemClock.elapsedRealtime()
            pointRunStates.clear()
            clickPoints.forEachIndexed { index, point ->
                pointRunStates.add(
                    PointRunState(
                        pointIndex = index,
                        nextClickAt = safeAdd(startedAt, point.delayMs),
                        endsAt = if (point.durationMs > 0L) {
                            safeAdd(startedAt, point.durationMs)
                        } else {
                            null
                        }
                    )
                )
            }
            isClicking = true
            scheduleNextPoint(generation)
            Toast.makeText(this, getString(R.string.toast_started), Toast.LENGTH_SHORT).show()
        }
        pendingStartTask = startTask
        handler.postDelayed(startTask, 300L)
    }

    private fun stopClicking(showToast: Boolean, autoStopped: Boolean) {
        isClicking = false
        runGeneration++
        pendingStartTask?.let(handler::removeCallbacks)
        pendingStartTask = null
        pointRunStates.clear()
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

    private fun scheduleNextPoint(generation: Long) {
        if (!isRunActive(generation)) return

        val now = SystemClock.elapsedRealtime()
        val activeStates = pointRunStates.filter { state ->
            state.endsAt == null || now < state.endsAt
        }
        if (activeStates.isEmpty()) {
            stopClicking(showToast = true, autoStopped = true)
            return
        }

        val nextState = activeStates.minByOrNull { state ->
            minOf(state.nextClickAt, state.endsAt ?: Long.MAX_VALUE)
        } ?: return
        val wakeAt = minOf(nextState.nextClickAt, nextState.endsAt ?: Long.MAX_VALUE)
        val waitMs = (wakeAt - now).coerceAtLeast(0L).coerceAtMost(Int.MAX_VALUE.toLong())

        handler.postDelayed({
            if (!isRunActive(generation)) return@postDelayed

            val currentTime = SystemClock.elapsedRealtime()
            if (nextState.endsAt != null && currentTime >= nextState.endsAt) {
                scheduleNextPoint(generation)
                return@postDelayed
            }
            if (currentTime < nextState.nextClickAt) {
                scheduleNextPoint(generation)
                return@postDelayed
            }

            val point = clickPoints.getOrNull(nextState.pointIndex)
            if (point == null) {
                scheduleNextPoint(generation)
                return@postDelayed
            }

            showClickIndicator(point.x, point.y, nextState.pointIndex)
            performSingleClick(point.x, point.y) {
                if (isRunActive(generation)) {
                    nextState.nextClickAt = safeAdd(SystemClock.elapsedRealtime(), point.intervalMs)
                    scheduleNextPoint(generation)
                }
            }
        }, waitMs)
    }

    private fun safeAdd(base: Long, duration: Long): Long {
        return if (duration > Long.MAX_VALUE - base) {
            Long.MAX_VALUE
        } else {
            base + duration
        }
    }

    private fun isRunActive(generation: Long): Boolean {
        return isClicking && generation == runGeneration
    }

    private fun performSingleClick(x: Int, y: Int, onFinished: () -> Unit) {
        val path = Path().apply { moveTo(x.toFloat(), y.toFloat()) }
        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0, 40))
            .build()
        val callback = object : GestureResultCallback() {
            override fun onCompleted(gestureDescription: GestureDescription?) {
                onFinished()
            }

            override fun onCancelled(gestureDescription: GestureDescription?) {
                onFinished()
            }
        }
        if (!dispatchGesture(gesture, callback, handler)) {
            handler.post(onFinished)
        }
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
