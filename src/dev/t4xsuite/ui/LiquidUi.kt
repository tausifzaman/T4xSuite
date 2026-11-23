package dev.t4xsuite.ui

import android.animation.ValueAnimator
import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Canvas
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.ColorFilter
import android.graphics.LinearGradient
import android.graphics.Matrix
import android.graphics.Outline
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.RadialGradient
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.RenderEffect
import android.graphics.Shader
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.LayerDrawable
import android.graphics.drawable.InsetDrawable
import android.graphics.drawable.RippleDrawable
import android.graphics.drawable.StateListDrawable
import android.os.Build
import android.provider.Settings
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewOutlineProvider
import android.view.ViewTreeObserver
import android.view.accessibility.AccessibilityNodeInfo
import android.view.animation.DecelerateInterpolator
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView

/** Presentation-only tokens and native components. Never owns traffic or session state. */
internal class LiquidColors(private val context: Context) {
    private fun token(name: String) = context.getColor(context.resources.getIdentifier("liquid_$name", "color", context.packageName))
    val background = token("background")
    val backgroundSecondary = token("background_secondary")
    val panel = token("surface")
    val glass = token("surface_secondary")
    val elevated = token("elevated")
    val editor = token("editor")
    val stroke = token("stroke")
    val foreground = token("text")
    val secondary = token("secondary")
    val muted = token("muted")
    val accent = token("accent")
    val cyan = token("cyan")
    val gold = token("gold")
    val success = token("success")
    val error = token("error")
    val selected = token("selected")
    val onAccent = token("on_accent")
    val ripple = token("ripple")
    val highlight = token("highlight")
    val materialTop = token("material_top")
    val materialBottom = token("material_bottom")
    val materialPrimary = token("material_primary")
    val materialDanger = token("material_danger")
    val materialDangerTop = token("material_danger_top")
    val materialDangerBottom = token("material_danger_bottom")
    val materialNeutral = token("material_neutral")
    val materialDisabled = token("material_disabled")
    val borderPrimary = token("border_primary")
    val borderDanger = token("border_danger")
    val borderNeutral = token("border_neutral")
    val innerHighlight = token("inner_highlight")
    val edgeHighlight = token("edge_highlight")
}

internal class LiquidUi(private val context: Context) {
    private companion object {
        const val DOCK_RADIUS_DP = 34
        const val DOCK_DOT_DP = 5
        const val WALLPAPER_SCRIM = "#A8080A0E"
        const val GLASS_TOP_ALPHA = 0xE0
        const val GLASS_BOTTOM_ALPHA = 0xD6
    }

    val colors = LiquidColors(context)
    // ConstantState shares immutable material definitions, never a View's pressed/ripple state.
    private val buttonMaterials = mutableMapOf<Int, Drawable.ConstantState>()
    fun dp(value: Int) = (value * context.resources.displayMetrics.density + .5f).toInt()
    fun dim(name: String) = context.resources.getDimensionPixelSize(context.resources.getIdentifier("liquid_$name", "dimen", context.packageName))
    fun css(color: Int) = "#%06X".format(java.util.Locale.ROOT, color and 0xFFFFFF)
    fun column() = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
    fun row() = LinearLayout(context).apply { gravity = Gravity.CENTER_VERTICAL }
    fun line(top: Int = 0) = LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(top) }
    fun touch(weight: Float = 1f) = LinearLayout.LayoutParams(0, -2, weight)

    fun text(value: String, size: Int = 14, color: Int = colors.foreground, bold: Boolean = false) = TextView(context).apply {
        text = value
        textSize = size.toFloat()
        setTextColor(color)
        typeface = Typeface.create(if (bold) "sans-serif-medium" else "sans-serif", Typeface.NORMAL)
        setLineSpacing(dp(2).toFloat(), 1f)
        when (size) {
            28 -> typography(this, "title")
            18 -> typography(this, "section")
            14 -> typography(this, "body")
            12 -> typography(this, "metadata")
        }
    }

    fun typography(view: TextView, token: String) {
        view.setTextSize(TypedValue.COMPLEX_UNIT_PX, dim("${token}_text").toFloat())
    }

    fun shape(color: Int, radius: Int = 18, border: Int? = null) = GradientDrawable().apply {
        setColor(color)
        cornerRadius = dp(radius).toFloat()
        border?.let { setStroke(dp(1), it) }
    }

    /** Original JPEG, center-cropped once per bounds change; shared by the root and dock sample. */
    fun wallpaper(): Drawable {
        val image = BitmapFactory.decodeResource(context.resources,
            context.resources.getIdentifier("bg_obsidian_vibe", "drawable", context.packageName),
            BitmapFactory.Options().apply { inScaled = false })
        return object : Drawable() {
            private val matrix = Matrix()
            private val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
            private val scrim = Paint().apply { color = Color.parseColor(WALLPAPER_SCRIM) }
            override fun onBoundsChange(bounds: Rect) {
                val scale = maxOf(bounds.width().toFloat() / image.width, bounds.height().toFloat() / image.height)
                matrix.setScale(scale, scale)
                matrix.postTranslate(bounds.left + (bounds.width() - image.width * scale) / 2,
                    bounds.top + (bounds.height() - image.height * scale) / 2)
            }
            override fun draw(canvas: Canvas) {
                val save = canvas.save()
                canvas.clipRect(bounds)
                canvas.drawBitmap(image, matrix, paint)
                canvas.drawRect(bounds, scrim)
                canvas.restoreToCount(save)
            }
            override fun setAlpha(alpha: Int) { paint.alpha = alpha; invalidateSelf() }
            override fun setColorFilter(filter: ColorFilter?) { paint.colorFilter = filter; invalidateSelf() }
            @Suppress("OVERRIDE_DEPRECATION")
            override fun getOpacity() = PixelFormat.OPAQUE
        }
    }

    /** Native translucent fallback; no blur, bitmap capture, layout or session ownership. */
    private fun material(radius: Float, primary: Boolean = false, danger: Boolean = false, enabled: Boolean = true): Drawable {
        val inset = dim("material_inset")
        if (!enabled) return GradientDrawable().apply {
            setColor(this@LiquidUi.colors.materialDisabled)
            cornerRadius = radius
            setStroke(inset, this@LiquidUi.colors.stroke)
        }
        val tint = when { danger -> colors.materialDanger; primary -> colors.materialPrimary; else -> colors.materialNeutral }
        val border = when { danger -> colors.borderDanger; primary -> colors.borderPrimary; else -> colors.borderNeutral }
        val top = if (danger) colors.materialDangerTop else colors.materialTop
        val bottom = if (danger) colors.materialDangerBottom else colors.materialBottom
        val base = GradientDrawable(GradientDrawable.Orientation.TL_BR,
            intArrayOf((top and 0x00FFFFFF) or (GLASS_TOP_ALPHA shl 24),
                (bottom and 0x00FFFFFF) or (GLASS_BOTTOM_ALPHA shl 24))).apply {
            cornerRadius = radius
            setStroke(inset, border)
        }
        val reflection = GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
            intArrayOf(tint, Color.TRANSPARENT, Color.TRANSPARENT)).apply { cornerRadius = radius }
        val inner = GradientDrawable().apply {
            setColor(Color.TRANSPARENT)
            cornerRadius = (radius - inset).coerceAtLeast(0f)
            setStroke(inset, this@LiquidUi.colors.innerHighlight)
        }
        val depth = GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
            intArrayOf(Color.TRANSPARENT, Color.parseColor("#18080A0E"))).apply { cornerRadius = radius }
        // A rounded, inset stroke fades down naturally; never a rectangular 1dp strip.
        val edge = RoundedSpecular((radius - inset).coerceAtLeast(0f), inset.toFloat(), colors.edgeHighlight)
        return LayerDrawable(arrayOf(base, reflection, inner, depth, edge)).apply {
            setPaddingMode(LayerDrawable.PADDING_MODE_STACK)
            setLayerInset(2, inset, inset, inset, inset)
            setLayerInset(4, inset, inset, inset, inset)
        }
    }

    private class RoundedSpecular(private val radius: Float, private val stroke: Float, private val highlight: Int) : Drawable() {
        private val rect = RectF()
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = stroke }
        override fun onBoundsChange(bounds: Rect) {
            rect.set(bounds)
            rect.inset(stroke / 2, stroke / 2)
            paint.shader = LinearGradient(0f, rect.top, 0f, rect.bottom,
                intArrayOf(highlight, Color.TRANSPARENT, Color.TRANSPARENT), floatArrayOf(0f, .55f, 1f), Shader.TileMode.CLAMP)
        }
        override fun draw(canvas: Canvas) = canvas.drawRoundRect(rect, radius, radius, paint)
        override fun setAlpha(alpha: Int) { paint.alpha = alpha; invalidateSelf() }
        override fun setColorFilter(filter: ColorFilter?) { paint.colorFilter = filter; invalidateSelf() }
        override fun getConstantState(): ConstantState = object : ConstantState() {
            override fun newDrawable(): Drawable = RoundedSpecular(radius, stroke, highlight)
            override fun getChangingConfigurations() = 0
        }
        @Suppress("OVERRIDE_DEPRECATION")
        override fun getOpacity() = PixelFormat.TRANSLUCENT
    }

    fun glass(radius: Float = dim("panel_radius").toFloat()) = material(radius)

    fun addressGo(click: () -> Unit) = button("Go", primary = true, click = click).apply {
        val fill = GradientDrawable(GradientDrawable.Orientation.TL_BR,
            intArrayOf(colors.materialPrimary, colors.materialNeutral)).apply { cornerRadius = dp(24).toFloat() }
        background = RippleDrawable(ColorStateList.valueOf(colors.ripple), fill, shape(Color.WHITE, 24))
        elevation = 0f
        setPadding(dp(16), 0, dp(16), 0)
    }

    fun browserModeChip() = browserChip("Mobile") {}

    fun browserClearDataChip(click: () -> Unit) = browserChip("Clear Data", danger = true, click = click).apply {
        contentDescription = "Clear Data. Clear this app's browser data for all sites."
    }

    /** Compact visual pill inside an accessible 48dp-high touch target. */
    private fun browserChip(label: String, danger: Boolean = false, click: () -> Unit) = button(label, danger = danger, click = click).apply {
        background = InsetDrawable(buttonBackground(danger = danger), 0, dp(8), 0, dp(8))
        minWidth = dim("touch_target")
        textSize = 12f
        setPadding(dp(10), dp(8), dp(10), dp(8))
        elevation = 0f
    }

    /** Sibling of the route host: only its background is blurred, never the tabs. */
    fun dock(wallpaperHost: View, wallpaper: Drawable) = object : FrameLayout(context) {
        override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
            // Match-parent decorative layers must not make a wrap-height dock fill the screen.
            val items = getChildAt(childCount - 1)
            measureChildWithMargins(items, widthMeasureSpec, 0, heightMeasureSpec, 0)
            val height = resolveSize(items.measuredHeight, heightMeasureSpec)
            super.onMeasure(widthMeasureSpec, MeasureSpec.makeMeasureSpec(height, MeasureSpec.EXACTLY))
        }
    }.apply {
        val radius = dp(DOCK_RADIUS_DP).toFloat()
        outlineProvider = object : ViewOutlineProvider() {
            override fun getOutline(view: View, outline: Outline) {
                outline.setRoundRect(0, 0, view.width, view.height, radius)
                outline.alpha = 1f
            }
        }
        clipChildren = false // The capsule outline, not the child bounds, clips the overscanned blur.
        clipToOutline = true
        elevation = dp(10).toFloat()
        // Overscan supplies real neighbouring pixels to the blur, avoiding a dark capsule edge.
        if (Build.VERSION.SDK_INT >= 31) {
            val overscan = dp(24)
            addView(DockWallpaper(context, wallpaperHost, wallpaper, dp(18).toFloat()), FrameLayout.LayoutParams(-1, -1).apply {
                setMargins(-overscan, -overscan, -overscan, -overscan)
            })
        }
        // Worst-case white backing on the fallback path still leaves enabled dock text >= 4.5:1.
        val base = shape(Color.parseColor("#E0080A0E"), DOCK_RADIUS_DP)
        val sheen = GradientDrawable(GradientDrawable.Orientation.TL_BR,
            intArrayOf(Color.parseColor("#0CFFFFFF"), Color.parseColor("#08D6B56D"), Color.TRANSPARENT)).apply {
            cornerRadius = radius
        }
        val frost = GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
            intArrayOf(Color.parseColor("#06000000"), Color.TRANSPARENT, Color.parseColor("#18080A0E"))).apply {
            cornerRadius = radius
        }
        // The same rich translucent material is also the API 29–30/software fallback.
        addView(View(context).apply {
            background = LayerDrawable(arrayOf(base, sheen, frost))
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }, FrameLayout.LayoutParams(-1, -1))
        foreground = DockRim(radius, dp(1).toFloat())
    }

    fun dockItems(dock: FrameLayout) = row().apply {
        setPadding(dp(5), dp(5), dp(5), dp(5))
        dock.addView(this, FrameLayout.LayoutParams(-1, -2))
    }

    fun dockItem(view: TextView) {
        // Symmetric padding centers the icon/label body. The dot never participates in measurement.
        val indicator = DockIndicator(dp(DOCK_DOT_DP), colors.accent, view, dp(4))
        view.background = RippleDrawable(ColorStateList.valueOf(Color.parseColor("#30000000")), indicator.halo,
            shape(colors.highlight, DOCK_RADIUS_DP))
        view.foreground = indicator
        view.addOnAttachStateChangeListener(object : View.OnAttachStateChangeListener {
            override fun onViewAttachedToWindow(v: View) = Unit
            override fun onViewDetachedFromWindow(v: View) = indicator.finish()
        })
    }

    fun selectDockItem(view: TextView, selected: Boolean, animate: Boolean) {
        (view.foreground as? DockIndicator)?.select(view, selected, animate)
    }

    /** Sample only the static root wallpaper; never draw or observe the route tree. API 31+ only. */
    private class DockWallpaper(context: Context, private val wallpaperHost: View,
                                private val wallpaper: Drawable, private val blur: Float) : View(context) {
        private val sourceLocation = IntArray(2)
        private val glassLocation = IntArray(2)
        private var offsetX = Int.MIN_VALUE
        private var offsetY = Int.MIN_VALUE
        private var sourceWidth = 0
        private var sourceHeight = 0
        private var observer: ViewTreeObserver? = null
        private val positionListener = ViewTreeObserver.OnGlobalLayoutListener { alignWallpaper() }

        init { importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO }

        private fun alignWallpaper() {
            wallpaperHost.getLocationInWindow(sourceLocation)
            getLocationInWindow(glassLocation)
            val x = sourceLocation[0] - glassLocation[0]
            val y = sourceLocation[1] - glassLocation[1]
            val w = wallpaperHost.width
            val h = wallpaperHost.height
            if (offsetX == x && offsetY == y && sourceWidth == w && sourceHeight == h) return
            offsetX = x; offsetY = y
            sourceWidth = w; sourceHeight = h
            invalidate()
        }

        override fun onAttachedToWindow() {
            super.onAttachedToWindow()
            observer = viewTreeObserver.also { it.addOnGlobalLayoutListener(positionListener) }
            alignWallpaper()
            if (Build.VERSION.SDK_INT >= 31) setRenderEffect(RenderEffect.createBlurEffect(blur, blur, Shader.TileMode.CLAMP))
        }

        override fun onDraw(canvas: Canvas) {
            if (!canvas.isHardwareAccelerated || sourceWidth == 0 || sourceHeight == 0) return
            val save = canvas.save()
            canvas.translate(offsetX.toFloat(), offsetY.toFloat())
            wallpaper.draw(canvas)
            canvas.restoreToCount(save)
        }

        override fun onDetachedFromWindow() {
            observer?.takeIf { it.isAlive }?.removeOnGlobalLayoutListener(positionListener)
            observer = null
            if (Build.VERSION.SDK_INT >= 31) setRenderEffect(null)
            super.onDetachedFromWindow()
        }
    }

    /** Crisp, top-lit 1dp rim above both the sampled background and tab content. */
    private class DockRim(private val radius: Float, private val stroke: Float) : Drawable() {
        private val rect = RectF()
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = stroke }
        override fun onBoundsChange(bounds: Rect) {
            rect.set(bounds)
            rect.inset(stroke / 2, stroke / 2)
            paint.shader = LinearGradient(0f, rect.top, 0f, rect.bottom,
                Color.parseColor("#40FFFFFF"), Color.parseColor("#10FFFFFF"), Shader.TileMode.CLAMP)
        }
        override fun draw(canvas: Canvas) = canvas.drawRoundRect(rect, radius, radius, paint)
        override fun setAlpha(alpha: Int) { paint.alpha = alpha; invalidateSelf() }
        override fun setColorFilter(filter: ColorFilter?) { paint.colorFilter = filter; invalidateSelf() }
        @Suppress("OVERRIDE_DEPRECATION") // Required Drawable contract on the supported older APIs.
        override fun getOpacity() = PixelFormat.TRANSLUCENT
    }

    /** One progress value crossfades/scales the active radial halo and glowing dot together. */
    private class DockIndicator(private val size: Int, private val color: Int, private val tab: TextView, private val gap: Int) : Drawable() {
        private var active = false
        private var animator: ValueAnimator? = null
        private var progress = 0f
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        private val glow = Paint(Paint.ANTI_ALIAS_FLAG)
        private var opacity = 255
        val halo: Drawable = object : Drawable() {
            private val haloPaint = Paint(Paint.ANTI_ALIAS_FLAG)
            private val rect = RectF()
            override fun onBoundsChange(bounds: Rect) {
                rect.set(bounds.left.toFloat(), bounds.top + bounds.height() * .06f,
                    bounds.right.toFloat(), bounds.bottom - bounds.height() * .06f)
                val gradient = RadialGradient(0f, 0f, 1f,
                    intArrayOf(Color.parseColor("#28D6B56D"), Color.TRANSPARENT), null, Shader.TileMode.CLAMP)
                gradient.setLocalMatrix(Matrix().apply {
                    setScale(rect.width() / 2, rect.height() / 2)
                    postTranslate(rect.centerX(), rect.centerY())
                })
                haloPaint.shader = gradient
            }
            override fun draw(canvas: Canvas) {
                if (progress == 0f) return
                haloPaint.alpha = (255 * progress).toInt()
                val save = canvas.save()
                val scale = .85f + .15f * progress
                canvas.scale(scale, scale, rect.centerX(), rect.centerY())
                canvas.drawOval(rect, haloPaint)
                canvas.restoreToCount(save)
            }
            override fun setAlpha(alpha: Int) = Unit
            override fun setColorFilter(filter: ColorFilter?) = Unit
            override fun getOpacity() = PixelFormat.TRANSLUCENT
        }

        init {
            glow.shader = RadialGradient(0f, 0f, size * .8f,
                intArrayOf(Color.parseColor("#70D6B56D"), Color.TRANSPARENT), null, Shader.TileMode.CLAMP)
        }

        override fun draw(canvas: Canvas) {
            if (progress == 0f) return
            val icon = tab.compoundDrawables[1]
            val bodyHeight = (icon?.bounds?.height() ?: 0) + tab.compoundDrawablePadding + (tab.layout?.height ?: tab.lineHeight)
            val cx = bounds.exactCenterX()
            val cy = bounds.exactCenterY() + bodyHeight / 2f + gap
            val save = canvas.save()
            canvas.translate(cx, cy)
            val scale = .72f + .28f * progress
            canvas.scale(scale, scale)
            glow.alpha = (opacity * progress).toInt()
            canvas.drawCircle(0f, 0f, size * .8f, glow)
            paint.color = color
            paint.alpha = (opacity * progress).toInt()
            canvas.drawCircle(0f, 0f, size / 2f, paint)
            canvas.restoreToCount(save)
        }

        override fun setAlpha(alpha: Int) { opacity = alpha; invalidateSelf() }
        override fun setColorFilter(filter: ColorFilter?) { paint.colorFilter = filter; glow.colorFilter = filter; invalidateSelf() }
        @Suppress("OVERRIDE_DEPRECATION") // Required Drawable contract on the supported older APIs.
        override fun getOpacity() = PixelFormat.TRANSLUCENT

        private fun show(value: Float) {
            progress = value
            invalidateSelf()
            halo.invalidateSelf()
        }

        fun select(view: View, selected: Boolean, animate: Boolean) {
            if (active == selected && animate) return
            active = selected
            animator?.cancel()
            animator = null
            val target = if (selected) 1f else 0f
            if (!animate || progress == target || !view.isShown || !view.isAttachedToWindow || !LiquidMotion.enabled(view)) {
                show(target)
                return
            }
            animator = ValueAnimator.ofFloat(progress, target).apply {
                duration = LiquidMotion.TAB
                interpolator = DecelerateInterpolator()
                addUpdateListener { show(it.animatedValue as Float) }
                start()
            }
        }

        fun finish() {
            animator?.cancel()
            animator = null
            show(if (active) 1f else 0f)
        }
    }

    /** Native parent inset, not HTML padding or transforms: the full page ends above the dock. */
    fun browserViewport(webView: View) = DockBrowserViewport(context, webView)

    class DockBrowserViewport(context: Context, browser: View) : FrameLayout(context) {
        init {
            clipToPadding = true
            addView(browser, FrameLayout.LayoutParams(-1, -1))
        }

        fun setDockClearance(clearance: Int) {
            if (paddingBottom == clearance) return
            setPadding(0, 0, 0, clearance)
        }
    }

    fun panel() = column().apply {
        background = glass()
        val pad = dim("panel_padding")
        setPadding(pad, pad, pad, pad)
        elevation = dim("shadow").toFloat()
    }

    fun heading(title: String, description: String? = null) = column().apply {
        addView(text(title, bold = true).apply { typography(this, "title"); isAccessibilityHeading = true })
        if (description != null) addView(text(description, color = colors.secondary), line(4))
    }

    fun section(title: String, description: String) = panel().apply {
        addView(text(title, color = colors.foreground, bold = true).apply { typography(this, "section"); isAccessibilityHeading = true })
        addView(text(description, color = colors.secondary), line(8))
    }

    fun badge(value: String, color: Int = colors.accent) = text(value, 12, color, true).apply {
        setPadding(dp(9), dp(5), dp(9), dp(5))
        background = shape(colors.selected, 10)
    }

    fun button(value: String, primary: Boolean = false, danger: Boolean = false, click: () -> Unit) = text(value, 14,
        colors.foreground, true).apply {
        gravity = Gravity.CENTER
        minHeight = dim("touch_target")
        setPadding(dp(12), dp(10), dp(12), dp(10))
        styleButton(this, primary, danger)
        elevation = dim("shadow").toFloat()
        isFocusable = true
        accessibilityDelegate = object : View.AccessibilityDelegate() {
            override fun onInitializeAccessibilityNodeInfo(host: View, info: AccessibilityNodeInfo) {
                super.onInitializeAccessibilityNodeInfo(host, info)
                info.className = "android.widget.Button"
            }
        }
        setOnClickListener { click() }
        LiquidMotion.press(this)
    }

    fun styleButton(view: TextView, primary: Boolean = false, danger: Boolean = false) {
        val foreground = when { danger -> colors.error; primary -> colors.onAccent; else -> colors.foreground }
        view.setTextColor(ColorStateList(arrayOf(intArrayOf(-android.R.attr.state_enabled), intArrayOf()),
            intArrayOf(colors.muted, foreground)))
        view.background = buttonBackground(primary = primary, danger = danger)
    }

    fun buttonBackground(primary: Boolean = false, selected: Boolean = false, danger: Boolean = false): Drawable {
        val key = (if (primary) 1 else 0) or (if (selected) 2 else 0) or (if (danger) 4 else 0)
        buttonMaterials[key]?.let { return it.newDrawable(context.resources).mutate() }
        val radius = dim("button_radius").toFloat()
        val states = StateListDrawable().apply {
            addState(intArrayOf(-android.R.attr.state_enabled), material(radius, enabled = false))
            addState(intArrayOf(), material(radius, primary || selected, danger))
        }
        val mask = GradientDrawable().apply { setColor(this@LiquidUi.colors.highlight); cornerRadius = radius }
        // Dark feedback preserves muted-red enabled text contrast instead of washing it out.
        val ripple = if (danger) Color.parseColor("#30000000") else colors.ripple
        val background = RippleDrawable(ColorStateList.valueOf(ripple), states, mask)
        background.constantState?.let { buttonMaterials[key] = it }
        return background.mutate()
    }

    fun icon(view: TextView, name: String) {
        val drawable = context.getDrawable(context.resources.getIdentifier("ic_liquid_$name", "drawable", context.packageName))!!.mutate()
        drawable.setTint(colors.accent)
        drawable.setBounds(0, 0, dim("icon"), dim("icon"))
        view.setCompoundDrawablesRelative(drawable, null, null, null)
        view.compoundDrawablePadding = dp(8)
    }

    fun editor(view: EditText) {
        view.setTextColor(colors.foreground)
        view.setHintTextColor(colors.muted)
        typography(view, "editor")
        view.typeface = Typeface.MONOSPACE
        view.gravity = Gravity.TOP or Gravity.START
        view.setHorizontallyScrolling(true)
        view.isHorizontalScrollBarEnabled = true
        view.setPadding(dp(14), dp(14), dp(14), dp(14))
        view.background = shape(colors.editor, border = colors.stroke).apply { cornerRadius = dim("input_radius").toFloat() }
        view.importantForAutofill = View.IMPORTANT_FOR_AUTOFILL_NO
        view.imeOptions = android.view.inputmethod.EditorInfo.IME_FLAG_NO_EXTRACT_UI or android.view.inputmethod.EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING
        view.isSaveEnabled = false
        view.setOnTouchListener { v, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    val scrollable = v.canScrollVertically(1) || v.canScrollVertically(-1) ||
                        v.canScrollHorizontally(1) || v.canScrollHorizontally(-1)
                    v.parent?.requestDisallowInterceptTouchEvent(scrollable)
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> v.parent?.requestDisallowInterceptTouchEvent(false)
            }
            false // Preserve native selection, cursor, scrolling, focus and keyboard actions.
        }
    }

    fun methodColor(method: String) = when (method) {
        "GET", "HEAD" -> colors.accent
        "POST" -> colors.cyan
        "PUT", "PATCH" -> colors.gold
        "DELETE" -> colors.error
        else -> colors.secondary
    }
}

/** Finite, decorative animations only. All traffic actions remain synchronous callbacks. */
internal object LiquidMotion {
    const val PRESS = 140L
    const val TAB = 240L
    const val CARD = 200L
    const val STATUS = 280L

    fun enabled(view: View) = ValueAnimator.areAnimatorsEnabled() &&
        Settings.Global.getFloat(view.context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) > 0f

    private fun cleanup(view: View) {
        view.addOnAttachStateChangeListener(object : View.OnAttachStateChangeListener {
            override fun onViewAttachedToWindow(v: View) = Unit
            override fun onViewDetachedFromWindow(v: View) {
                v.animate().cancel()
                v.alpha = 1f; v.translationY = 0f; v.scaleX = 1f; v.scaleY = 1f
            }
        })
    }

    fun press(view: View) {
        cleanup(view)
        view.setOnTouchListener { v, event ->
            val scale = when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> .98f
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> 1f
                else -> null
            }
            if (scale != null) {
                if (enabled(v) && v.isEnabled)
                    v.animate().scaleX(scale).scaleY(scale).setDuration(PRESS).start()
                else { v.animate().cancel(); v.scaleX = 1f; v.scaleY = 1f }
            }
            false
        }
    }

    fun enter(view: View, duration: Long = CARD) {
        view.animate().cancel()
        if (!enabled(view) || !view.isAttachedToWindow) return
        view.alpha = .65f
        view.translationY = 5f * view.resources.displayMetrics.density
        view.animate().alpha(1f).translationY(0f).setInterpolator(DecelerateInterpolator()).setDuration(duration).start()
    }

    fun watch(view: View) = cleanup(view)

    fun expand(view: View, show: Boolean) {
        view.animate().cancel()
        view.visibility = if (show) View.VISIBLE else View.GONE
        view.alpha = 1f
        if (show) enter(view)
    }
}
