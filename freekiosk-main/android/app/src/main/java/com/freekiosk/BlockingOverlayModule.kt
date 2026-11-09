package com.freekiosk

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.drawable.Drawable
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.TextView
import com.facebook.react.bridge.*
import org.json.JSONArray

/**
 * React Native module for managing blocking overlays
 */
class BlockingOverlayModule(reactContext: ReactApplicationContext) : 
    ReactContextBaseJavaModule(reactContext) {
    
    companion object {
        private const val TAG = "BlockingOverlayModule"
    }
    
    private val mainHandler = Handler(Looper.getMainLooper())
    private var touchLoggerView: View? = null
    private var gridHelperView: View? = null
    private var homeButtonView: View? = null
    private var bannerView: View? = null
    
    override fun getName() = "BlockingOverlayModule"
    
    private fun getManager(): BlockingOverlayManager {
        return BlockingOverlayManager.getInstance(reactApplicationContext)
    }
    
    /**
     * Enable or disable blocking overlays
     */
    @ReactMethod
    fun setEnabled(enabled: Boolean, promise: Promise) {
        mainHandler.post {
            try {
                getManager().setEnabled(enabled)
                promise.resolve(true)
            } catch (e: Exception) {
                DebugLog.e(TAG, "setEnabled error: ${e.message}")
                promise.reject("ERROR", e.message)
            }
        }
    }
    
    /**
     * Check if blocking overlays are enabled
     */
    @ReactMethod
    fun isEnabled(promise: Promise) {
        try {
            promise.resolve(getManager().isEnabled())
        } catch (e: Exception) {
            promise.reject("ERROR", e.message)
        }
    }
    
    /**
     * Set the list of blocking regions from JSON
     */
    @ReactMethod
    fun setRegions(regionsJson: String, promise: Promise) {
        mainHandler.post {
            try {
                val regions = parseRegions(regionsJson)
                getManager().setRegions(regions)
                promise.resolve(true)
            } catch (e: Exception) {
                DebugLog.e(TAG, "setRegions error: ${e.message}")
                promise.reject("ERROR", e.message)
            }
        }
    }
    
    /**
     * Force update all overlays
     */
    @ReactMethod
    fun updateOverlays(promise: Promise) {
        mainHandler.post {
            try {
                getManager().updateOverlays()
                promise.resolve(true)
            } catch (e: Exception) {
                DebugLog.e(TAG, "updateOverlays error: ${e.message}")
                promise.reject("ERROR", e.message)
            }
        }
    }
    
    /**
     * Remove all overlays
     */
    @ReactMethod
    fun removeAllOverlays(promise: Promise) {
        mainHandler.post {
            try {
                getManager().removeAllOverlays()
                promise.resolve(true)
            } catch (e: Exception) {
                promise.reject("ERROR", e.message)
            }
        }
    }
    
    /**
     * Show touch logger overlay that displays touch coordinates
     */
    @ReactMethod
    private var touchLoggerCountdownRunnable: Runnable? = null
    private var touchLoggerRemainingSeconds: Int = 0

    @ReactMethod
    fun showTouchLogger(durationSeconds: Int, promise: Promise) {
        mainHandler.post {
            try {
                hideTouchLogger()
                
                val windowManager = reactApplicationContext.getSystemService(
                    android.content.Context.WINDOW_SERVICE
                ) as WindowManager
                
                val container = FrameLayout(reactApplicationContext).apply {
                    setBackgroundColor(Color.TRANSPARENT)
                }
                
                touchLoggerRemainingSeconds = durationSeconds
                
                val infoText = TextView(reactApplicationContext).apply {
                    text = "Touch Logger Active (${durationSeconds}s)\nTap anywhere to see coordinates"
                    setTextColor(Color.WHITE)
                    textSize = 14f
                    setBackgroundColor(Color.argb(200, 0, 0, 0))
                    setPadding(24, 16, 24, 16)
                }
                
                val textParams = FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.WRAP_CONTENT,
                    FrameLayout.LayoutParams.WRAP_CONTENT
                ).apply {
                    gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
                    topMargin = 100
                }
                container.addView(infoText, textParams)
                
                // Countdown timer runnable
                var lastTouchInfo = ""
                touchLoggerCountdownRunnable = object : Runnable {
                    override fun run() {
                        if (touchLoggerRemainingSeconds > 0) {
                            touchLoggerRemainingSeconds--
                            if (lastTouchInfo.isEmpty()) {
                                infoText.text = "Touch Logger Active (${touchLoggerRemainingSeconds}s)\nTap anywhere to see coordinates"
                            } else {
                                infoText.text = "$lastTouchInfo\n\n⏱ ${touchLoggerRemainingSeconds}s remaining"
                            }
                            mainHandler.postDelayed(this, 1000)
                        } else {
                            hideTouchLogger()
                        }
                    }
                }
                mainHandler.postDelayed(touchLoggerCountdownRunnable!!, 1000)
                
                // Touch listener to show coordinates
                container.setOnTouchListener { _, event ->
                    if (event.action == MotionEvent.ACTION_DOWN) {
                        val screenWidth = container.width.toFloat()
                        val screenHeight = container.height.toFloat()
                        
                        val xPercent = (event.x / screenWidth * 100).toInt()
                        val yPercent = (event.y / screenHeight * 100).toInt()
                        
                        lastTouchInfo = """
                            |Touch Detected:
                            |X: ${event.x.toInt()}px ($xPercent%)
                            |Y: ${event.y.toInt()}px ($yPercent%)
                            |
                            |Screen: ${screenWidth.toInt()} × ${screenHeight.toInt()}
                        """.trimMargin()
                        
                        infoText.text = "$lastTouchInfo\n\n⏱ ${touchLoggerRemainingSeconds}s remaining"
                    }
                    false // Allow touch to pass through
                }
                
                val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                } else {
                    @Suppress("DEPRECATION")
                    WindowManager.LayoutParams.TYPE_PHONE
                }
                
                val params = WindowManager.LayoutParams(
                    WindowManager.LayoutParams.MATCH_PARENT,
                    WindowManager.LayoutParams.MATCH_PARENT,
                    type,
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                    WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH,
                    PixelFormat.TRANSLUCENT
                )
                
                windowManager.addView(container, params)
                touchLoggerView = container
                
                // Auto-hide after duration
                mainHandler.postDelayed({
                    hideTouchLogger()
                }, durationSeconds * 1000L)
                
                promise.resolve(true)
            } catch (e: Exception) {
                DebugLog.e(TAG, "showTouchLogger error: ${e.message}")
                promise.reject("ERROR", e.message)
            }
        }
    }
    
    /**
     * Hide touch logger overlay
     */
    @ReactMethod
    fun hideTouchLogger(promise: Promise) {
        mainHandler.post {
            hideTouchLogger()
            promise.resolve(true)
        }
    }
    
    private fun hideTouchLogger() {
        touchLoggerCountdownRunnable?.let { mainHandler.removeCallbacks(it) }
        touchLoggerCountdownRunnable = null
        touchLoggerView?.let { view ->
            try {
                val windowManager = reactApplicationContext.getSystemService(
                    android.content.Context.WINDOW_SERVICE
                ) as WindowManager
                windowManager.removeView(view)
            } catch (e: Exception) {
                DebugLog.e(TAG, "hideTouchLogger error: ${e.message}")
            }
            touchLoggerView = null
        }
    }
    
    /**
     * Show grid helper overlay with percentage markers
     */
    private var gridHelperCountdownRunnable: Runnable? = null
    private var gridHelperRemainingSeconds: Int = 0

    @ReactMethod
    fun showGridHelper(durationSeconds: Int, promise: Promise) {
        mainHandler.post {
            try {
                hideGridHelper()
                
                val windowManager = reactApplicationContext.getSystemService(
                    android.content.Context.WINDOW_SERVICE
                ) as WindowManager
                
                gridHelperRemainingSeconds = durationSeconds
                
                // Create container with grid view and countdown text
                val container = FrameLayout(reactApplicationContext)
                
                val gridView = GridHelperView(reactApplicationContext)
                container.addView(gridView, FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT,
                    FrameLayout.LayoutParams.MATCH_PARENT
                ))
                
                val countdownText = TextView(reactApplicationContext).apply {
                    text = "⏱ ${durationSeconds}s"
                    setTextColor(Color.WHITE)
                    textSize = 16f
                    setBackgroundColor(Color.argb(180, 0, 0, 0))
                    setPadding(20, 12, 20, 12)
                }
                
                val textParams = FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.WRAP_CONTENT,
                    FrameLayout.LayoutParams.WRAP_CONTENT
                ).apply {
                    gravity = Gravity.TOP or Gravity.END
                    topMargin = 50
                    marginEnd = 20
                }
                container.addView(countdownText, textParams)
                
                // Touch listener to dismiss on tap
                container.setOnTouchListener { _, event ->
                    if (event.action == MotionEvent.ACTION_DOWN) {
                        hideGridHelper()
                    }
                    true
                }
                
                // Countdown timer
                gridHelperCountdownRunnable = object : Runnable {
                    override fun run() {
                        if (gridHelperRemainingSeconds > 0) {
                            gridHelperRemainingSeconds--
                            countdownText.text = "⏱ ${gridHelperRemainingSeconds}s"
                            mainHandler.postDelayed(this, 1000)
                        } else {
                            hideGridHelper()
                        }
                    }
                }
                mainHandler.postDelayed(gridHelperCountdownRunnable!!, 1000)
                
                val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                } else {
                    @Suppress("DEPRECATION")
                    WindowManager.LayoutParams.TYPE_PHONE
                }
                
                val params = WindowManager.LayoutParams(
                    WindowManager.LayoutParams.MATCH_PARENT,
                    WindowManager.LayoutParams.MATCH_PARENT,
                    type,
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
                    PixelFormat.TRANSLUCENT
                )
                
                windowManager.addView(container, params)
                gridHelperView = container
                
                promise.resolve(true)
            } catch (e: Exception) {
                DebugLog.e(TAG, "showGridHelper error: ${e.message}")
                promise.reject("ERROR", e.message)
            }
        }
    }
    
    /**
     * Hide grid helper overlay
     */
    @ReactMethod
    fun hideGridHelper(promise: Promise) {
        mainHandler.post {
            hideGridHelper()
            promise.resolve(true)
        }
    }
    
    private fun hideGridHelper() {
        gridHelperCountdownRunnable?.let { mainHandler.removeCallbacks(it) }
        gridHelperCountdownRunnable = null
        gridHelperView?.let { view ->
            try {
                val windowManager = reactApplicationContext.getSystemService(
                    android.content.Context.WINDOW_SERVICE
                ) as WindowManager
                windowManager.removeView(view)
            } catch (e: Exception) {
                DebugLog.e(TAG, "hideGridHelper error: ${e.message}")
            }
            gridHelperView = null
        }
    }
    
    /**
     * Show a native home button overlay that floats above the WebView.
     * Touch events on the button are forwarded to React Native via the
     * "homeButtonPressed" RCTDeviceEventEmitter event.
     *
     * @param xPercent  Horizontal position as percentage of screen width (0-100)
     * @param yPercent  Vertical position as percentage of screen height (0-100)
     */
    @ReactMethod
    fun showHomeButton(xPercent: Double, yPercent: Double, promise: Promise) {
        mainHandler.post {
            try {
                hideHomeButton()

                val windowManager = reactApplicationContext.getSystemService(
                    android.content.Context.WINDOW_SERVICE
                ) as WindowManager

                val dm = reactApplicationContext.resources.displayMetrics
                val screenW = dm.widthPixels
                val screenH = dm.heightPixels

                // Button size: 56dp circle
                val sizePx = (56 * dm.density).toInt()

                // Position: center of the button at (xPercent, yPercent)
                // We use Gravity.TOP|START and set x/y as top-left, so subtract half size
                val posX = (screenW * xPercent / 100.0 - sizePx / 2.0).toInt()
                val posY = (screenH * yPercent / 100.0 - sizePx / 2.0).toInt()

                val buttonView = HomeButtonView(reactApplicationContext).apply {
                    setSize(sizePx)
                }

                // Touch listener — forward to RN
                buttonView.setOnTouchListener { _, event ->
                    if (event.action == MotionEvent.ACTION_DOWN) {
                        android.util.Log.d(TAG, "HomeButton overlay tapped")
                        try {
                            KioskModule.sendEventFromNative("homeButtonPressed", null)
                        } catch (e: Exception) {
                            android.util.Log.e(TAG, "Failed to send homeButtonPressed: ${e.message}")
                        }
                    }
                    true // consume the touch
                }

                val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                } else {
                    @Suppress("DEPRECATION")
                    WindowManager.LayoutParams.TYPE_PHONE
                }

                val params = WindowManager.LayoutParams(
                    sizePx,
                    sizePx,
                    type,
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                        WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                        WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                    PixelFormat.TRANSLUCENT
                ).apply {
                    gravity = Gravity.TOP or Gravity.START
                    x = posX
                    y = posY
                }

                windowManager.addView(buttonView, params)
                homeButtonView = buttonView

                android.util.Log.d(TAG, "HomeButton overlay shown at ($posX,$posY) size=${sizePx}px")
                promise.resolve(true)
            } catch (e: Exception) {
                DebugLog.e(TAG, "showHomeButton error: ${e.message}")
                promise.reject("ERROR", e.message)
            }
        }
    }

    /**
     * Hide the native home button overlay.
     */
    @ReactMethod
    fun hideHomeButton(promise: Promise) {
        mainHandler.post {
            hideHomeButton()
            promise.resolve(true)
        }
    }

    private fun hideHomeButton() {
        homeButtonView?.let { view ->
            try {
                val windowManager = reactApplicationContext.getSystemService(
                    android.content.Context.WINDOW_SERVICE
                ) as WindowManager
                windowManager.removeView(view)
            } catch (e: Exception) {
                DebugLog.e(TAG, "hideHomeButton error: ${e.message}")
            }
            homeButtonView = null
        }
    }

    /**
     * Show a native anti-theft banner overlay above the WebView.
     * The banner is a semi-transparent bar at the top of the screen with text + optional icon.
     *
     * @param text     Banner text to display
     * @param fontSize Font size in sp (default 14)
     * @param showIcon Whether to show the anti-theft icon
     */
    @ReactMethod
    fun showBanner(text: String, fontSize: Double, showIcon: Boolean, promise: Promise) {
        mainHandler.post {
            try {
                hideBanner()

                val windowManager = reactApplicationContext.getSystemService(
                    android.content.Context.WINDOW_SERVICE
                ) as WindowManager

                val dm = reactApplicationContext.resources.displayMetrics
                val screenW = dm.widthPixels

                val bannerOverlayView = BannerOverlayView(reactApplicationContext).apply {
                    setText(text)
                    setFontSize(fontSize.toFloat())
                    setShowIcon(showIcon)
                }

                val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                } else {
                    @Suppress("DEPRECATION")
                    WindowManager.LayoutParams.TYPE_PHONE
                }

                val params = WindowManager.LayoutParams(
                    WindowManager.LayoutParams.MATCH_PARENT,
                    WindowManager.LayoutParams.WRAP_CONTENT,
                    type,
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                        WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                        WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                    PixelFormat.TRANSLUCENT
                ).apply {
                    gravity = Gravity.TOP or Gravity.START
                    x = 0
                    y = 0
                }

                windowManager.addView(bannerOverlayView, params)
                bannerView = bannerOverlayView

                android.util.Log.d(TAG, "Banner overlay shown: text='$text' fontSize=$fontSize showIcon=$showIcon")
                promise.resolve(true)
            } catch (e: Exception) {
                DebugLog.e(TAG, "showBanner error: ${e.message}")
                promise.reject("ERROR", e.message)
            }
        }
    }

    /**
     * Hide the native banner overlay.
     */
    @ReactMethod
    fun hideBanner(promise: Promise) {
        mainHandler.post {
            hideBanner()
            promise.resolve(true)
        }
    }

    private fun hideBanner() {
        bannerView?.let { view ->
            try {
                val windowManager = reactApplicationContext.getSystemService(
                    android.content.Context.WINDOW_SERVICE
                ) as WindowManager
                windowManager.removeView(view)
            } catch (e: Exception) {
                DebugLog.e(TAG, "hideBanner error: ${e.message}")
            }
            bannerView = null
        }
    }

    /**
     * Parse JSON string to list of BlockingRegion
     */
    private fun parseRegions(json: String): List<BlockingRegion> {
        val regions = mutableListOf<BlockingRegion>()
        val jsonArray = JSONArray(json)
        
        for (i in 0 until jsonArray.length()) {
            val obj = jsonArray.getJSONObject(i)
            regions.add(BlockingRegion(
                id = obj.getString("id"),
                name = obj.getString("name"),
                enabled = obj.getBoolean("enabled"),
                xStart = obj.getDouble("xStart").toFloat(),
                yStart = obj.getDouble("yStart").toFloat(),
                xEnd = obj.getDouble("xEnd").toFloat(),
                yEnd = obj.getDouble("yEnd").toFloat(),
                displayMode = obj.getString("displayMode"),
                targetPackage = if (obj.has("targetPackage") && !obj.isNull("targetPackage")) {
                    obj.getString("targetPackage")
                } else null
            ))
        }
        
        return regions
    }
}

/**
 * Custom view for drawing grid with percentage markers
 */
private class GridHelperView(context: android.content.Context) : View(context) {
    
    private val gridPaint = android.graphics.Paint().apply {
        color = Color.argb(100, 255, 255, 255)
        strokeWidth = 2f
        style = android.graphics.Paint.Style.STROKE
    }
    
    private val textPaint = android.graphics.Paint().apply {
        color = Color.WHITE
        textSize = 28f
        textAlign = android.graphics.Paint.Align.CENTER
        isAntiAlias = true
    }
    
    private val bgPaint = android.graphics.Paint().apply {
        color = Color.argb(150, 0, 0, 0)
    }
    
    override fun onDraw(canvas: android.graphics.Canvas) {
        super.onDraw(canvas)
        
        val w = width.toFloat()
        val h = height.toFloat()
        
        // Draw semi-transparent background
        canvas.drawRect(0f, 0f, w, h, bgPaint)
        
        // Draw grid lines every 10%
        for (i in 0..10) {
            val xPos = w * i / 10
            val yPos = h * i / 10
            
            // Vertical lines
            canvas.drawLine(xPos, 0f, xPos, h, gridPaint)
            // Horizontal lines
            canvas.drawLine(0f, yPos, w, yPos, gridPaint)
            
            // X-axis labels at top
            if (i > 0 && i < 10) {
                canvas.drawText("${i * 10}%", xPos, 40f, textPaint)
            }
            
            // Y-axis labels on left
            if (i > 0 && i < 10) {
                textPaint.textAlign = android.graphics.Paint.Align.LEFT
                canvas.drawText("${i * 10}%", 10f, yPos + 10f, textPaint)
                textPaint.textAlign = android.graphics.Paint.Align.CENTER
            }
        }
        
        // Draw corner labels
        textPaint.textAlign = android.graphics.Paint.Align.LEFT
        canvas.drawText("0%", 10f, 40f, textPaint)
        
        textPaint.textAlign = android.graphics.Paint.Align.RIGHT
        canvas.drawText("100%", w - 10f, 40f, textPaint)
        
        textPaint.textAlign = android.graphics.Paint.Align.LEFT
        canvas.drawText("100%", 10f, h - 20f, textPaint)
        
        // Draw info text
        textPaint.textAlign = android.graphics.Paint.Align.CENTER
        textPaint.textSize = 32f
        canvas.drawText("Grid Helper - Tap to dismiss", w / 2, h / 2, textPaint)
        textPaint.textSize = 28f
    }
}

/**
 * Custom view that draws a circular home button with a home glyph.
 * Used as a native WindowManager overlay so it receives touch events
 * even when a React Native WebView (Platform View) covers the screen.
 */
private class HomeButtonView(context: android.content.Context) : View(context) {

    private var sizePx: Int = (56 * resources.displayMetrics.density).toInt()

    private val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(64, 0, 60, 120) // 25% opacity blue
        style = Paint.Style.FILL
    }

    private val iconPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        style = Paint.Style.STROKE
        strokeWidth = 3f * resources.displayMetrics.density
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }

    private val iconFillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        style = Paint.Style.FILL
    }

    fun setSize(px: Int) {
        sizePx = px
        requestLayout()
        invalidate()
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        setMeasuredDimension(sizePx, sizePx)
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val w = width.toFloat()
        val h = height.toFloat()
        val cx = w / 2f
        val cy = h / 2f
        val radius = (minOf(w, h) / 2f) * 0.9f

        // Draw circular background
        canvas.drawCircle(cx, cy, radius, bgPaint)

        // Draw home icon: house with roof and door
        val scale = radius / 28f // icon designed in 56-unit space, radius ~28
        val s = scale

        // Roof: triangle from (cx, cy-12s) to (cx-14s, cy+2s) to (cx+14s, cy+2s)
        val roofTopY = cy - 12f * s
        val roofBottomY = cy + 2f * s
        val roofLeftX = cx - 14f * s
        val roofRightX = cx + 14f * s

        // House body: rectangle from (cx-10s, cy+2s) to (cx+10s, cy+14s)
        val bodyTop = cy + 2f * s
        val bodyBottom = cy + 14f * s
        val bodyLeft = cx - 10f * s
        val bodyRight = cx + 10f * s

        // Draw roof
        canvas.drawLine(roofLeftX, roofBottomY, cx, roofTopY, iconPaint)
        canvas.drawLine(cx, roofTopY, roofRightX, roofBottomY, iconPaint)

        // Draw house body (left, right, bottom)
        canvas.drawLine(bodyLeft, bodyTop, bodyLeft, bodyBottom, iconPaint)
        canvas.drawLine(bodyRight, bodyTop, bodyRight, bodyBottom, iconPaint)
        canvas.drawLine(bodyLeft, bodyBottom, bodyRight, bodyBottom, iconPaint)

        // Draw door (filled rectangle)
        val doorLeft = cx - 3f * s
        val doorRight = cx + 3f * s
        val doorTop = cy + 6f * s
        val doorBottom = bodyBottom
        canvas.drawRect(doorLeft, doorTop, doorRight, doorBottom, iconFillPaint)
    }
}

/**
 * Custom view that draws a semi-transparent banner with anti-theft text and optional icon.
 * Used as a native WindowManager overlay above the WebView.
 */
private class BannerOverlayView(context: android.content.Context) : View(context) {

    private var bannerText: String = ""
    private var fontSizeSp: Float = 14f
    private var showIcon: Boolean = true

    private val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(180, 0, 0, 0) // semi-transparent black (slightly more opaque)
        style = Paint.Style.FILL
    }

    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        style = Paint.Style.FILL
    }

    // Load the anti-theft VectorDrawable from resources
    private val iconDrawable: android.graphics.drawable.Drawable? = try {
        androidx.core.content.ContextCompat.getDrawable(context, com.freekiosk.R.drawable.banner_icon)
    } catch (e: Exception) {
        DebugLog.e("BannerOverlayView", "Failed to load banner_icon drawable: ${e.message}")
        null
    }

    fun setText(text: String) {
        bannerText = text
        requestLayout()
        invalidate()
    }

    fun setFontSize(sp: Float) {
        fontSizeSp = sp
        textPaint.textSize = sp * resources.displayMetrics.scaledDensity
        requestLayout()
        invalidate()
    }

    fun setShowIcon(show: Boolean) {
        showIcon = show
        requestLayout()
        invalidate()
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val screenW = MeasureSpec.getSize(widthMeasureSpec)
        val density = resources.displayMetrics.density
        val padH = (20 * density).toInt()
        val padV = (12 * density).toInt()
        // Icon size: 2.5x the font size in dp, minimum 36dp
        val iconSize = if (showIcon) maxOf((fontSizeSp * 2.5f * density).toInt(), (36 * density).toInt()) else 0
        val iconGap = if (showIcon) (12 * density).toInt() else 0

        textPaint.textSize = fontSizeSp * resources.displayMetrics.scaledDensity
        val fm = textPaint.fontMetrics
        val textHeight = (fm.descent - fm.ascent).toInt()

        // Height is max of text height and icon size, plus padding
        val contentHeight = maxOf(textHeight, iconSize)
        val height = contentHeight + padV * 2
        setMeasuredDimension(screenW, height)
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val w = width.toFloat()
        val h = height.toFloat()
        val density = resources.displayMetrics.density
        val padH = 20f * density

        // Draw background
        canvas.drawRect(0f, 0f, w, h, bgPaint)

        textPaint.textSize = fontSizeSp * resources.displayMetrics.scaledDensity
        textPaint.color = Color.WHITE
        textPaint.textAlign = Paint.Align.LEFT

        val fm = textPaint.fontMetrics
        val textBaseline = h / 2f - (fm.ascent + fm.descent) / 2f

        var textX = padH

        // Draw anti-theft icon from VectorDrawable
        if (showIcon && iconDrawable != null) {
            val iconSize = maxOf(fontSizeSp * 2.5f * density, 36f * density)
            val iconLeft = padH.toInt()
            val iconTop = ((h - iconSize) / 2f).toInt()
            val iconRight = iconLeft + iconSize.toInt()
            val iconBottom = iconTop + iconSize.toInt()

            iconDrawable.setBounds(iconLeft, iconTop, iconRight, iconBottom)
            iconDrawable.draw(canvas)

            textX = padH + iconSize + 12f * density
        } else if (showIcon) {
            // Fallback: draw a simple red circle with slash if drawable failed to load
            val iconSize = maxOf(fontSizeSp * 2.5f * density, 36f * density)
            val iconCx = padH + iconSize / 2f
            val iconCy = h / 2f
            val r = iconSize / 2f * 0.85f

            val fallbackPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.argb(220, 205, 39, 38)
                style = Paint.Style.STROKE
                strokeWidth = 3f * density
                strokeCap = Paint.Cap.ROUND
            }
            canvas.drawCircle(iconCx, iconCy, r, fallbackPaint)
            canvas.drawLine(iconCx - r * 0.7f, iconCy - r * 0.7f, iconCx + r * 0.7f, iconCy + r * 0.7f, fallbackPaint)

            textX = padH + iconSize + 12f * density
        }

        // Draw text
        canvas.drawText(bannerText, textX, textBaseline, textPaint)
    }
}
