/*
 *  Copyright (c) 2026 Anki-Android-Dualscreen contributors
 *
 *  This program is free software; you can redistribute it and/or modify it under
 *  the terms of the GNU General Public License as published by the Free Software
 *  Foundation; either version 3 of the License, or (at your option) any later
 *  version.
 *
 *  This program is distributed in the hope that it will be useful, but WITHOUT ANY
 *  WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A
 *  PARTICULAR PURPOSE. See the GNU General Public License for more details.
 *
 *  You should have received a copy of the GNU General Public License along with
 *  this program.  If not, see <http://www.gnu.org/licenses/>.
 */

package com.ichi2.anki.dualscreen

import android.animation.ValueAnimator
import android.app.Activity
import android.app.ActivityOptions
import android.app.Presentation
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorFilter
import android.graphics.Outline
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.drawable.Drawable
import android.hardware.display.DisplayManager
import android.os.Build
import android.os.Bundle
import android.util.TypedValue
import android.view.Choreographer
import android.view.Display
import android.view.InputDevice
import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.Menu
import android.view.MenuItem
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.ViewTreeObserver
import android.view.WindowManager
import android.view.animation.PathInterpolator
import android.webkit.WebView
import android.widget.LinearLayout
import android.widget.TextView
import android.window.OnBackInvokedDispatcher
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.annotation.StringRes
import androidx.core.content.ContextCompat
import androidx.core.content.getSystemService
import androidx.core.graphics.ColorUtils
import androidx.core.graphics.createBitmap
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.core.view.allViews
import androidx.core.view.forEach
import androidx.core.widget.TextViewCompat
import com.ichi2.anki.R
import com.ichi2.anki.preferences.sharedPrefs
import com.ichi2.themes.Themes
import timber.log.Timber
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sign

internal const val DISABLED_ALPHA = 0.4f
private const val MENU_ICON_DP = 24
private const val MOVE_MS = 180L
private const val PRESS_MS = 160L
private const val FILL_ALPHA = 0.32f
private const val COMPACT_FILL_ALPHA = 0.45f
private const val PRESSED_FILL_ALPHA = 0.55f
private const val COMPACT_ICON_MAX_DP = 64
private const val MAJOR_AXIS_WEIGHT = 13L
private const val SAMPLE_SIZE = 8
private const val DARK_LUMINANCE = 0.5
internal const val COLORED_ICONS = 1
private const val OPAQUE_ALPHA = 200
private const val STICK_DEAD_ZONE = 0.15f
private const val STICK_VERTICAL_RATIO = 0.5f
private const val STICK_SCROLL_DP_PER_SECOND = 500f
private val IGNORED_DISPLAY_NAMES = listOf("HiddenDisplay", "WebRTC_ScreenCapture")

private val NAVIGATION_KEYS =
    mapOf(
        KeyEvent.KEYCODE_DPAD_UP to View.FOCUS_UP,
        KeyEvent.KEYCODE_DPAD_DOWN to View.FOCUS_DOWN,
        KeyEvent.KEYCODE_DPAD_LEFT to View.FOCUS_LEFT,
        KeyEvent.KEYCODE_DPAD_RIGHT to View.FOCUS_RIGHT,
    )

private val CONFIRM_KEYS =
    setOf(KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER, KeyEvent.KEYCODE_BUTTON_A)

fun Activity.mainDisplayLaunchOptions(): Bundle? {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O || !isDualScreenEnabled()) return null
    if (ContextCompat.getDisplayOrDefault(this).displayId == Display.DEFAULT_DISPLAY) return null
    Timber.i("Opened on a secondary display: launching on the main display")
    return ActivityOptions.makeBasic().setLaunchDisplayId(Display.DEFAULT_DISPLAY).toBundle()
}

abstract class SecondaryDisplay<P : SecondaryDisplayPresentation>(
    protected val activity: ComponentActivity,
) : DisplayManager.DisplayListener {
    private val displayManager = activity.getSystemService<DisplayManager>()!!

    protected var presentation: P? = null
        private set

    private val panelHighlight = FocusHighlight()
    private val activityHighlight = FocusHighlight()
    private val selected: View?
        get() = panelHighlight.view
    protected val isNavigating: Boolean
        get() = selected != null
    private var handledKeyCode: Int? = null
    private val passedKeyCodes = mutableSetOf<Int>()
    private var stickTilt = 0f
    private var stickTarget: View? = null
    private var lastStickFrame = 0L
    private val stickScroller =
        object : Choreographer.FrameCallback {
            override fun doFrame(frameTimeNanos: Long) {
                val target = stickTarget?.takeIf { stickTilt != 0f && it.isShown } ?: return
                val seconds = if (lastStickFrame == 0L) 0f else (frameTimeNanos - lastStickFrame) / 1e9f
                lastStickFrame = frameTimeNanos
                val speed = STICK_SCROLL_DP_PER_SECOND * target.resources.displayMetrics.density
                target.scrollBy(0, (stickTilt * speed * seconds).roundToInt())
                Choreographer.getInstance().postFrameCallback(this)
            }
        }
    private var menu: View? = null
    private var menuItems: ViewGroup? = null
    private var menuAnchor: View? = null
    private val menuBackCallback =
        object : OnBackPressedCallback(false) {
            override fun handleOnBackPressed() = dismissMenu()
        }
    private val focusChangeListener = ViewTreeObserver.OnGlobalFocusChangeListener { _, newFocus -> highlightActivityFocus(newFocus) }
    private val touchModeChangeListener =
        ViewTreeObserver.OnTouchModeChangeListener { isInTouchMode -> if (isInTouchMode) highlightActivityFocus(null) }

    init {
        activity.onBackPressedDispatcher.addCallback(activity, menuBackCallback)
    }

    protected open val isEnabled: Boolean
        get() = activity.isDualScreenEnabled()

    protected open val navigationRoot: View?
        get() = presentation?.window?.decorView

    protected open val navigationExitDirection: Int
        get() = View.FOCUS_UP

    protected open fun highlightsActivityFocus(view: View) = true

    protected open fun onActivityConfirm(highlighted: View) = false

    protected abstract fun createPresentation(display: Display): P

    protected open fun onShown(presentation: P) {}

    protected open fun onDismissed() {}

    protected open fun stickScrollCandidates(): List<View> = emptyList()

    protected open fun defaultSelection(root: View): View? = root.navigableViews().firstOrNull()

    protected open fun canEnterNavigation(direction: Int): Boolean {
        if (direction == navigationExitDirection) return false
        val focused = activity.currentFocus
        if (focused == null || focused.isInTouchMode) return presentation?.window?.decorView?.hasWindowFocus() == true
        if (focused.onCheckIsTextEditor()) return false
        return direction == navigationExitDirection.opposite() && focused.focusSearch(direction) == null
    }

    protected open fun onNavigationExit(
        direction: Int,
        position: Float,
    ) = activity.focusFromEdge(direction, position)

    protected fun isPreferenceEnabled(
        @StringRes key: Int,
        default: Boolean = true,
    ) = activity.sharedPrefs().getBoolean(activity.getString(key), default)

    fun start() {
        displayManager.registerDisplayListener(this, null)
        activity.window.decorView.viewTreeObserver.apply {
            addOnGlobalFocusChangeListener(focusChangeListener)
            addOnTouchModeChangeListener(touchModeChangeListener)
        }
        refresh()
    }

    fun stop() {
        displayManager.unregisterDisplayListener(this)
        activity.window.decorView.viewTreeObserver
            .takeIf { it.isAlive }
            ?.apply {
                removeOnGlobalFocusChangeListener(focusChangeListener)
                removeOnTouchModeChangeListener(touchModeChangeListener)
            }
        dismiss()
    }

    fun scrollWithStick(event: MotionEvent): Boolean {
        if (!event.isFromSource(InputDevice.SOURCE_JOYSTICK) || event.actionMasked != MotionEvent.ACTION_MOVE) return false
        val tilt = event.verticalStickTilt()
        val wasScrolling = stickTilt != 0f
        stickTilt = tilt * abs(tilt)
        if (tilt == 0f) return wasScrolling
        val direction = if (tilt > 0) 1 else -1
        val targets = scrollTargets().filter { it.isShown }
        stickTarget = targets.firstOrNull { it.canScrollVertically(direction) }
        if (stickTarget == null) {
            stickTilt = 0f
            return targets.any { it.canScrollVertically(-direction) }
        }
        if (!wasScrolling) {
            lastStickFrame = 0L
            Choreographer.getInstance().postFrameCallback(stickScroller)
        }
        return true
    }

    private fun scrollTargets(): List<View> {
        val focused = generateSequence(selected ?: activity.currentFocus) { it.parent as? View }
        val windows = listOfNotNull(activity.window.decorView, presentation?.window?.decorView)
        val scrollables =
            windows
                .flatMap { it.allViews.filter { view -> view.canScrollVertically(1) || view.canScrollVertically(-1) } }
                .sortedByDescending { it.width * it.height }
        return focused.toList() + stickScrollCandidates() + scrollables
    }

    fun dispatchKeyEvent(event: KeyEvent) = handleKeyEvent(event) || dispatchPastCard(event)

    private fun dispatchPastCard(event: KeyEvent): Boolean {
        val isPassed =
            when (event.action) {
                KeyEvent.ACTION_DOWN ->
                    presentation != null && KeyEvent.isGamepadButton(event.keyCode) && activity.currentFocus is WebView
                KeyEvent.ACTION_UP -> passedKeyCodes.remove(event.keyCode)
                else -> false
            }
        if (!isPassed) return false
        if (event.action == KeyEvent.ACTION_DOWN) passedKeyCodes += event.keyCode
        event.dispatch(activity, activity.window.decorView.keyDispatcherState, activity)
        return true
    }

    private fun handleKeyEvent(event: KeyEvent): Boolean {
        if (event.keyCode in NAVIGATION_KEYS) activity.window.decorView.allowTouchscreenFocus()
        if (event.keyCode in CONFIRM_KEYS && event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0) activityHighlight.press()
        if (event.action == KeyEvent.ACTION_UP) {
            if (event.keyCode != handledKeyCode) return false
            handledKeyCode = null
            return true
        }
        if (event.action != KeyEvent.ACTION_DOWN || !onKeyDown(event)) return false
        handledKeyCode = event.keyCode
        return true
    }

    fun clearNavigation() = select(null)

    protected fun showMenu(
        host: ViewGroup,
        items: Menu,
        onSelected: (MenuItem) -> Unit,
    ) {
        dismissMenu()
        val view = LayoutInflater.from(host.context).inflate(R.layout.view_dualscreen_menu, host, false)
        view.setOnClickListener { dismissMenu() }
        host.addView(view)
        menuAnchor = selected
        menu = view
        menuItems = view.findViewById<ViewGroup>(R.id.dualscreen_menu_items).also { fillMenu(it, items, onSelected) }
        menuBackCallback.isEnabled = true
    }

    protected fun dismissMenu() {
        val view = menu ?: return
        val wasNavigating = selected != null
        (view.parent as? ViewGroup)?.removeView(view)
        menu = null
        menuItems = null
        menuBackCallback.isEnabled = false
        select(menuAnchor.takeIf { wasNavigating })
        menuAnchor = null
    }

    protected fun enterNavigation() {
        val root = menuItems ?: navigationRoot ?: return
        select(firstSelection(root))
    }

    protected fun refreshNavigation() {
        val current = selected ?: return
        val root = menuItems ?: navigationRoot
        if (root == null) {
            select(null)
        } else if (!current.isNavigableIn(root)) {
            val sameControl = current.id.takeIf { it != View.NO_ID }?.let { root.findViewById<View>(it) }
            select(sameControl?.takeIf { it.isNavigableIn(root) } ?: firstSelection(root))
        }
    }

    private fun fillMenu(
        list: ViewGroup,
        items: Menu,
        onSelected: (MenuItem) -> Unit,
    ) {
        val wasNavigating = selected != null
        list.removeAllViews()
        items.forEach { item ->
            if (!item.isVisible) return@forEach
            list.addView(
                list.menuRow(item).apply {
                    setOnClickListener {
                        val subMenu = item.subMenu
                        if (subMenu != null) {
                            fillMenu(list, subMenu, onSelected)
                        } else {
                            dismissMenu()
                            onSelected(item)
                        }
                    }
                },
            )
        }
        if (wasNavigating) select(list.navigableViews().firstOrNull())
    }

    private fun firstSelection(root: View) = if (root === menuItems) root.navigableViews().firstOrNull() else defaultSelection(root)

    private fun onKeyDown(event: KeyEvent): Boolean {
        if (menu != null && event.keyCode == KeyEvent.KEYCODE_BUTTON_B) {
            dismissMenu()
            return true
        }
        val direction = NAVIGATION_KEYS[event.keyCode]
        if (direction == null && event.keyCode !in CONFIRM_KEYS) return false
        refreshNavigation()
        val root = menuItems ?: navigationRoot ?: return false
        val current = selected
        if (current == null) {
            if (direction == null) return event.repeatCount == 0 && activityHighlight.view?.let(::onActivityConfirm) == true
            if (root !== menuItems && !canEnterNavigation(direction)) return false
            select(firstSelection(root))
            return selected != null
        }
        if (direction == null) {
            if (event.repeatCount == 0) {
                panelHighlight.press()
                current.performClick()
            }
            return true
        }
        val next = root.findNavigable(current, direction)
        if (next != null) {
            select(next)
        } else if (direction == navigationExitDirection && root !== menuItems) {
            val position = (current.windowRect().centerX() - root.windowRect().left).toFloat() / root.width
            select(null)
            onNavigationExit(direction, position)
            highlightActivityFocus(activity.currentFocus)
        }
        return true
    }

    private fun select(view: View?) {
        panelHighlight.show(view)
        if (view == null) return
        activityHighlight.hide()
        view.requestRectangleOnScreen(Rect(0, 0, view.width, view.height), false)
    }

    private fun highlightActivityFocus(view: View?) {
        val isHighlighted =
            selected == null && view != null && highlightsActivityFocus(view) &&
                !view.isInTouchMode && !view.onCheckIsTextEditor() && view !is WebView
        activityHighlight.show(view.takeIf { isHighlighted })
    }

    override fun onDisplayAdded(displayId: Int) = refresh()

    override fun onDisplayRemoved(displayId: Int) = refresh()

    override fun onDisplayChanged(displayId: Int) {}

    private fun refresh() {
        val display = if (isEnabled) findSecondaryDisplay() else null
        if (display != null && presentation?.display?.displayId == display.displayId) return
        dismiss()
        if (display == null) return

        Timber.i("%s: showing on display %d", javaClass.simpleName, display.displayId)
        val newPresentation = createPresentation(display)
        try {
            newPresentation.show()
        } catch (e: WindowManager.InvalidDisplayException) {
            Timber.w(e, "%s: unable to show on display %d", javaClass.simpleName, display.displayId)
            return
        }
        newPresentation.onTouchDown = ::clearNavigation
        newPresentation.setOnDismissListener {
            if (presentation !== newPresentation) return@setOnDismissListener
            dismiss()
            refresh()
        }
        presentation = newPresentation
        onShown(newPresentation)
    }

    private fun findSecondaryDisplay(): Display? {
        val activityDisplayId = ContextCompat.getDisplayOrDefault(activity).displayId
        return displayManager
            .getDisplays(DisplayManager.DISPLAY_CATEGORY_PRESENTATION)
            .firstOrNull { it.displayId != activityDisplayId && it.name !in IGNORED_DISPLAY_NAMES }
    }

    private fun dismiss() {
        val shown = presentation ?: return
        dismissMenu()
        select(null)
        highlightActivityFocus(null)
        shown.dismiss()
        presentation = null
        onDismissed()
    }
}

open class SecondaryDisplayPresentation(
    private val activity: ComponentActivity,
    display: Display,
) : Presentation(activity, display, Themes.currentTheme.styleResId) {
    internal var onTouchDown: (() -> Unit)? = null

    init {
        setCancelable(false)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            onBackInvokedDispatcher.registerOnBackInvokedCallback(OnBackInvokedDispatcher.PRIORITY_DEFAULT) {
                activity.onBackPressedDispatcher.onBackPressed()
            }
        }
    }

    override fun onStart() {
        super.onStart()
        val window = window ?: return
        (window.decorView as? ViewGroup)?.descendantFocusability = ViewGroup.FOCUS_BLOCK_DESCENDANTS
        WindowCompat.setDecorFitsSystemWindows(window, false)
        WindowInsetsControllerCompat(window, window.decorView).apply {
            hide(WindowInsetsCompat.Type.systemBars())
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (currentFocus?.onCheckIsTextEditor() == true && super.dispatchKeyEvent(event)) return true
        if (activity.dispatchKeyEvent(event)) return true
        val direction = NAVIGATION_KEYS[event.keyCode] ?: return super.dispatchKeyEvent(event)
        if (event.action == KeyEvent.ACTION_DOWN) activity.moveFocus(direction)
        return true
    }

    override fun dispatchTouchEvent(event: MotionEvent): Boolean {
        if (event.actionMasked == MotionEvent.ACTION_DOWN) onTouchDown?.invoke()
        return super.dispatchTouchEvent(event)
    }

    override fun dispatchGenericMotionEvent(event: MotionEvent): Boolean =
        activity.dispatchGenericMotionEvent(event) || super.dispatchGenericMotionEvent(event)
}

private class FocusHighlight {
    var view: View? = null
        private set
    private var host: View? = null
    private var highlight: HighlightDrawable? = null
    private val start = Rect()
    private val target = Rect()
    private val frame = Rect()
    private val outline = Outline()
    private var startRadius = 0f
    private var targetRadius = 0f
    private var frameRadius = 0f
    private var progress = 1f
    private var pressAmount = 0f
    private var isAppearing = false
    private var isBackgroundChecked = false
    private val moveAnimator =
        ValueAnimator.ofFloat(0f, 1f).apply {
            duration = MOVE_MS
            interpolator = PathInterpolator(0.2f, 0f, 0f, 1f)
            addUpdateListener {
                progress = it.animatedValue as Float
                render()
            }
        }
    private val pressAnimator =
        ValueAnimator.ofFloat(0f, 1f, 0f).apply {
            duration = PRESS_MS
            addUpdateListener {
                pressAmount = it.animatedValue as Float
                render()
            }
        }
    private val updater =
        ViewTreeObserver.OnPreDrawListener {
            render()
            true
        }

    fun show(next: View?) {
        if (next === view) return
        if (next == null || next.rootView !== host) hide()
        if (next == null) return
        isAppearing = host == null
        if (isAppearing) {
            host = next.rootView.apply { viewTreeObserver.addOnPreDrawListener(updater) }
            highlight = HighlightDrawable(next.context).also { next.rootView.overlay.add(it) }
        } else {
            start.set(frame)
            startRadius = frameRadius
        }
        view = next
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) next.defaultFocusHighlightEnabled = false
        isBackgroundChecked = false
        measure(next)
        if (isAppearing) {
            start.set(target)
            startRadius = targetRadius
        }
        progress = 0f
        moveAnimator.start()
    }

    fun press() {
        if (view != null) pressAnimator.start()
    }

    fun hide() {
        moveAnimator.cancel()
        pressAnimator.cancel()
        val host = host ?: return
        highlight?.let { host.overlay.remove(it) }
        host.viewTreeObserver.takeIf { it.isAlive }?.removeOnPreDrawListener(updater)
        this.host = null
        highlight = null
        view = null
        frame.setEmpty()
    }

    private fun measure(view: View) {
        val bounds = view.windowRect()
        val isCompact = view.isCompactIcon()
        highlight?.strength = if (isCompact) COMPACT_FILL_ALPHA else FILL_ALPHA
        outline.setEmpty()
        view.outlineProvider?.getOutline(view, outline)
        if (outline.getRect(target) && !target.isEmpty) {
            target.offset(bounds.left, bounds.top)
            targetRadius = outline.radius
        } else if (isCompact) {
            val radius = min(bounds.width(), bounds.height()) / 2
            target.set(bounds.centerX() - radius, bounds.centerY() - radius, bounds.centerX() + radius, bounds.centerY() + radius)
            targetRadius = radius.toFloat()
        } else {
            target.set(bounds)
            targetRadius = 0f
        }
    }

    private fun render() {
        val view = view ?: return
        val highlight = highlight ?: return
        val host = host ?: return
        if (view.isShown) {
            if (!isBackgroundChecked && view.isLaidOut) {
                highlight.isOnDarkBackground = view.hasDarkBackground()
                isBackgroundChecked = true
            }
            measure(view)
            frame.set(
                lerp(start.left, target.left),
                lerp(start.top, target.top),
                lerp(start.right, target.right),
                lerp(start.bottom, target.bottom),
            )
            frameRadius = startRadius + (targetRadius - startRadius) * progress
            if (!frame.intersect(0, 0, host.width, host.height)) frame.setEmpty()
        } else {
            frame.setEmpty()
        }
        val alpha = if (isAppearing) (progress * 255).roundToInt() else 255
        if (highlight.bounds != frame || highlight.alpha != alpha || highlight.emphasis != pressAmount || highlight.radius != frameRadius) {
            highlight.alpha = alpha
            highlight.emphasis = pressAmount
            highlight.radius = frameRadius
            highlight.bounds = frame
            host.invalidate()
        }
    }

    private fun lerp(
        from: Int,
        to: Int,
    ) = (from + (to - from) * progress).roundToInt()
}

private class HighlightDrawable(
    context: Context,
) : Drawable() {
    private val color = ContextCompat.getColor(context, R.color.material_light_blue_900)
    var isOnDarkBackground = false
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val rect = RectF()
    private var highlightAlpha = 255
    var emphasis = 0f
    var radius = 0f
    var strength = FILL_ALPHA

    override fun draw(canvas: Canvas) {
        if (bounds.isEmpty) return
        rect.set(bounds)
        val fillAlpha = strength + (PRESSED_FILL_ALPHA - strength) * emphasis
        val base = if (isOnDarkBackground) Color.WHITE else color
        paint.color = ColorUtils.setAlphaComponent(base, (fillAlpha * highlightAlpha).roundToInt())
        canvas.drawRoundRect(rect, radius, radius, paint)
    }

    override fun setAlpha(alpha: Int) {
        highlightAlpha = alpha
        invalidateSelf()
    }

    override fun getAlpha() = highlightAlpha

    override fun setColorFilter(colorFilter: ColorFilter?) {}

    @Deprecated("Deprecated in Java")
    override fun getOpacity() = PixelFormat.TRANSLUCENT
}

internal fun View.setEnabledWithAlpha(enabled: Boolean) {
    isEnabled = enabled
    alpha = if (enabled) 1f else DISABLED_ALPHA
}

internal fun Int.opposite() =
    when (this) {
        View.FOCUS_UP -> View.FOCUS_DOWN
        View.FOCUS_DOWN -> View.FOCUS_UP
        View.FOCUS_LEFT -> View.FOCUS_RIGHT
        else -> View.FOCUS_LEFT
    }

private fun View.navigableViews(): List<View> =
    buildList {
        fun collect(view: View) {
            if (!view.isShown) return
            if (view.hasOnClickListeners() && view.isEnabled) {
                add(view)
            } else if (view is ViewGroup) {
                view.forEach(::collect)
            }
        }
        collect(this@navigableViews)
    }

private fun Context.isDualScreenEnabled() = sharedPrefs().getBoolean(getString(R.string.pref_dual_screen_key), true)

private fun ViewGroup.menuRow(item: MenuItem) =
    TextView(context, null, 0, R.style.DualScreenMenuItem).apply {
        layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
        text = item.title
        val size = (MENU_ICON_DP * resources.displayMetrics.density).toInt()
        val icon = item.icon?.mutate()?.apply { setBounds(0, 0, size, size) }
        val chevron = ContextCompat.getDrawable(context, R.drawable.ic_chevron_right_black)?.takeIf { item.hasSubMenu() }
        chevron?.setBounds(0, 0, size, size)
        setCompoundDrawablesRelative(icon, null, chevron, null)
        if (item.groupId != COLORED_ICONS) TextViewCompat.setCompoundDrawableTintList(this, textColors)
        setEnabledWithAlpha(item.isEnabled)
    }

private fun View.hasDarkBackground(): Boolean {
    val luminance =
        generateSequence(this) { it.parent as? View }
            .firstNotNullOfOrNull { view -> view.background?.let { view.sampleLuminance(it) ?: view.tintLuminance() } }
            ?: TypedValue()
                .also { context.theme.resolveAttribute(android.R.attr.colorBackground, it, true) }
                .let { ColorUtils.calculateLuminance(it.data) }
    return luminance < DARK_LUMINANCE
}

private fun View.tintLuminance() =
    backgroundTintList?.defaultColor?.takeIf { Color.alpha(it) > OPAQUE_ALPHA }?.let(ColorUtils::calculateLuminance)

private fun View.sampleLuminance(drawable: Drawable): Double? {
    if (width == 0 || height == 0) return null
    if (drawable.bounds.isEmpty) drawable.setBounds(0, 0, width, height)
    val sample = createBitmap(SAMPLE_SIZE, SAMPLE_SIZE)
    Canvas(sample).apply {
        scale(SAMPLE_SIZE.toFloat() / width, SAMPLE_SIZE.toFloat() / height)
        drawable.draw(this)
    }
    val pixels = IntArray(SAMPLE_SIZE * SAMPLE_SIZE).also { sample.getPixels(it, 0, SAMPLE_SIZE, 0, 0, SAMPLE_SIZE, SAMPLE_SIZE) }
    sample.recycle()
    val opaque = pixels.filter { Color.alpha(it) > OPAQUE_ALPHA }
    return if (opaque.size * 2 < pixels.size) null else opaque.map(ColorUtils::calculateLuminance).average()
}

private fun View.isCompactIcon(): Boolean {
    val maxSize = COMPACT_ICON_MAX_DP * resources.displayMetrics.density
    return width <= maxSize && height <= maxSize && abs(width - height) <= min(width, height) / 3
}

private fun View.allowTouchscreenFocus() {
    if (this !is ViewGroup) return
    if (touchscreenBlocksFocus) touchscreenBlocksFocus = false
    forEach { it.allowTouchscreenFocus() }
}

private fun View.isNavigableIn(root: View) =
    isAttachedToWindow && isShown && isEnabled && generateSequence(parent) { it.parent }.any { it === root }

private fun Activity.moveFocus(direction: Int) {
    val focused = currentFocus
    if (focused != null && !focused.isInTouchMode) {
        focused.focusSearch(direction)?.requestFocus(direction)
    } else {
        focusFromEdge(direction, 0.5f)
    }
}

private fun Activity.focusFromEdge(
    direction: Int,
    position: Float,
) {
    val root = window.decorView
    val x = (root.width * position).toInt()
    val y = (root.height * position).toInt()
    val source =
        when (direction) {
            View.FOCUS_UP -> Rect(x, root.height, x + 1, root.height + 1)
            View.FOCUS_DOWN -> Rect(x, -1, x + 1, 0)
            View.FOCUS_LEFT -> Rect(root.width, y, root.width + 1, y + 1)
            else -> Rect(-1, y, 0, y + 1)
        }
    val focusables = ArrayList<View>()
    root.addFocusables(focusables, direction, View.FOCUSABLES_ALL)
    focusables.filter { it.isShown }.nearest(source, direction)?.requestFocusFromTouch()
}

private fun View.windowRect(): Rect {
    val location = IntArray(2)
    getLocationInWindow(location)
    return Rect(location[0], location[1], location[0] + width, location[1] + height)
}

private fun View.findNavigable(
    from: View,
    direction: Int,
) = navigableViews().filter { it !== from }.nearest(from.windowRect(), direction)

private fun List<View>.nearest(
    source: Rect,
    direction: Int,
): View? {
    val candidates = map { it to it.windowRect() }.filter { (_, rect) -> rect.isBeyond(source, direction) }
    return candidates
        .filter { (_, rect) -> rect.isInBeam(source, direction) }
        .ifEmpty { candidates }
        .minByOrNull { (_, rect) -> rect.distanceFrom(source, direction) }
        ?.first
}

private fun Rect.isInBeam(
    source: Rect,
    direction: Int,
) = when (direction) {
    View.FOCUS_LEFT, View.FOCUS_RIGHT -> top < source.bottom && bottom > source.top
    else -> left < source.right && right > source.left
}

private fun Rect.isBeyond(
    source: Rect,
    direction: Int,
) = when (direction) {
    View.FOCUS_UP -> (source.bottom > bottom || source.top >= bottom) && source.top > top
    View.FOCUS_DOWN -> (source.top < top || source.bottom <= top) && source.bottom < bottom
    View.FOCUS_LEFT -> (source.right > right || source.left >= right) && source.left > left
    else -> (source.left < left || source.right <= left) && source.right < right
}

private fun Rect.distanceFrom(
    source: Rect,
    direction: Int,
): Long {
    val (gap, offset) =
        when (direction) {
            View.FOCUS_UP -> source.top - bottom to centerX() - source.centerX()
            View.FOCUS_DOWN -> top - source.bottom to centerX() - source.centerX()
            View.FOCUS_LEFT -> source.left - right to centerY() - source.centerY()
            else -> left - source.right to centerY() - source.centerY()
        }
    val major = gap.coerceAtLeast(0).toLong()
    return MAJOR_AXIS_WEIGHT * major * major + offset.toLong() * offset
}

private fun MotionEvent.verticalStickTilt(): Float {
    val (horizontal, vertical) =
        listOf(MotionEvent.AXIS_X to MotionEvent.AXIS_Y, MotionEvent.AXIS_Z to MotionEvent.AXIS_RZ)
            .map { (x, y) -> getAxisValue(x) to getAxisValue(y) }
            .maxBy { (x, y) -> hypot(x, y) }
    val magnitude = min(hypot(horizontal, vertical), 1f)
    if (magnitude <= STICK_DEAD_ZONE || abs(vertical) < abs(horizontal) * STICK_VERTICAL_RATIO) return 0f
    return sign(vertical) * (magnitude - STICK_DEAD_ZONE) / (1 - STICK_DEAD_ZONE)
}
