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

import android.content.Context
import android.graphics.Color
import android.os.Bundle
import android.text.InputType
import android.view.Display
import android.view.Gravity
import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.Menu
import android.view.View
import android.view.ViewGroup
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.view.ViewTreeObserver
import android.view.animation.AccelerateInterpolator
import android.view.animation.DecelerateInterpolator
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.core.content.edit
import androidx.core.content.getSystemService
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.children
import androidx.core.view.forEach
import androidx.core.view.isEmpty
import androidx.core.view.isNotEmpty
import androidx.core.view.isVisible
import androidx.core.view.updateLayoutParams
import androidx.core.widget.doAfterTextChanged
import androidx.lifecycle.lifecycleScope
import com.ichi2.anki.Flag
import com.ichi2.anki.R
import com.ichi2.anki.ViewerResourceHandler
import com.ichi2.anki.cardviewer.ViewerCommand
import com.ichi2.anki.preferences.sharedPrefs
import com.ichi2.anki.reviewer.Binding
import com.ichi2.anki.reviewer.CardSide
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.roundToInt
import androidx.appcompat.widget.PopupMenu as AppCompatPopupMenu

class SecondaryDisplayReviewer(
    activity: ComponentActivity,
    private val onCommand: (ViewerCommand) -> Unit,
    private val onLayoutChanged: (isActive: Boolean, showMainAnswerButtons: Boolean) -> Unit,
) : SecondaryDisplay<SecondaryDisplayPresentation>(activity) {
    private var isDisplayingAnswer = false
    private var cardHtml: String? = null
    private var baseUrl: String? = null
    private var nextTimes = emptyList<String>()
    private var counts = listOf<CharSequence>("", "", "")
    private var timer: TextView? = null
    private var isMarked = false
    private var flag = Flag.NONE
    private var undoEnabled = false
    private var redoEnabled = false
    private var isEraserMode = false

    private var whiteboard: View? = null
    private var whiteboardHome: ViewGroup? = null
    private var penPalette: View? = null
    private var penPaletteHome: ViewGroup? = null

    private var controls: ReviewerControls? = null
    private var statusPill: StatusPill? = null
    private var cardView: WebView? = null
    private var activityHost: FrameLayout? = null
    private var presentationHost: FrameLayout? = null
    private var runningTransitions = 0
    private var hints = emptyMap<ViewerCommand, String>()
    private var isMainCardPadded = false
    private var isUsingSystemKeyboard = false
    private var isImeOnMainDisplay = false
    private var answerFieldInputType: Int? = null
    private var keyboardLayouts = emptyList<KeyboardLayout>()
    private var wasImeVisible = false
    private var wasPresentationImeVisible = false
    private var systemInput: EditText? = null

    private val answerField: EditText?
        get() = activity.findViewById(R.id.answer_field)

    val isTypingOnPanel: Boolean
        get() =
            presentation != null && !isDisplayingAnswer && !isUsingSystemKeyboard && keyboardLayouts.isNotEmpty() &&
                !isDrawingOnSecondaryDisplay && answerField?.isVisible == true

    private val typingField: EditText?
        get() = answerField?.takeIf { it.isShown && !isDisplayingAnswer && !isSwapped }

    private val isSwapped: Boolean
        get() = isPreferenceEnabled(R.string.pref_dual_screen_swapped_key, default = false)

    private val isAnswerOnSecondaryDisplay: Boolean
        get() = !isSwapped && isPreferenceEnabled(R.string.pref_dual_screen_answer_key)

    private val isDrawingOnSecondaryDisplay: Boolean
        get() = !isSwapped && whiteboard?.isVisible == true

    private val isQuestionOnPanel: Boolean
        get() = isUsingSystemKeyboard && isImeOnMainDisplay && !isDisplayingAnswer && !isSwapped

    private val isShowingAnswerCard: Boolean
        get() =
            isAnswerOnSecondaryDisplay && isDisplayingAnswer && !isDrawingOnSecondaryDisplay &&
                cardHtml?.let { ANSWER_SEPARATOR.containsMatchIn(it) } == true

    private var reportedLayout: Triple<Boolean, Boolean, Boolean>? = null

    fun showCard(
        html: String?,
        baseUrl: String,
        isAnswer: Boolean,
        expectedAnswer: String? = null,
    ) {
        isDisplayingAnswer = isAnswer
        closeSystemInput()
        if (!isAnswer) {
            nextTimes = emptyList()
            isUsingSystemKeyboard = false
            keyboardLayouts = keyboardLayoutsFor(activity, expectedAnswer, answerField?.imeHintLocales?.get(0))
        }
        cardHtml = html
        this.baseUrl = baseUrl
        update()
        loadCards()
    }

    override fun stickScrollCandidates() = listOfNotNull(controls?.answerView, cardView)

    fun decorateMainCard(webView: WebView) {
        val reservePill = presentation != null && !isSwapped
        if (reservePill) {
            webView.evaluateJavascript(RESERVE_STATUS_PILL_SCRIPT, null)
        } else if (isMainCardPadded) {
            webView.evaluateJavascript(RELEASE_STATUS_PILL_SCRIPT, null)
        }
        isMainCardPadded = reservePill
        if (presentation != null && isShowingAnswerCard) webView.evaluateJavascript(HIDE_ANSWER_SCRIPT, null)
        if (presentation != null && activity.currentFocus?.let { it.onCheckIsTextEditor() && it.isShown } != true) {
            webView.evaluateJavascript(HIDE_FOCUS_OUTLINE_SCRIPT, null)
            webView.isFocusableInTouchMode = true
            webView.requestFocus()
        }
    }

    fun setNextTimes(labels: List<String>) {
        nextTimes = labels
        update()
    }

    fun setCounts(
        new: CharSequence,
        learn: CharSequence,
        review: CharSequence,
    ) {
        counts = listOf(new, learn, review)
        update()
    }

    fun setActionState(
        isMarked: Boolean,
        flag: Flag,
        undoEnabled: Boolean,
        redoEnabled: Boolean,
        isEraserMode: Boolean,
    ) {
        this.isMarked = isMarked
        this.flag = flag
        this.undoEnabled = undoEnabled
        this.redoEnabled = redoEnabled
        this.isEraserMode = isEraserMode
        update()
    }

    fun attachWhiteboard(
        view: View,
        palette: View,
    ) {
        whiteboard = view
        whiteboardHome = view.parent as? ViewGroup
        penPalette = palette
        penPaletteHome = palette.parent as? ViewGroup
        hostWhiteboard()
        update()
    }

    fun onWhiteboardVisibilityChanged() = update()

    fun mirrorTimer(cardTimer: TextView) {
        timer = cardTimer
        cardTimer.doAfterTextChanged { statusPill?.updateTimer() }
    }

    override fun createPresentation(display: Display): SecondaryDisplayPresentation {
        hints = readGamepadHints()
        return ReviewerPresentation(display)
    }

    override fun onShown(presentation: SecondaryDisplayPresentation) {
        val host = FrameLayout(activity).apply { descendantFocusability = ViewGroup.FOCUS_BLOCK_DESCENDANTS }
        activity.addContentView(host, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
        ViewCompat.setOnApplyWindowInsetsListener(host) { view, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            view.updateLayoutParams<ViewGroup.MarginLayoutParams> { setMargins(bars.left, bars.top, bars.right, bars.bottom) }
            val isImeVisible = insets.isVisible(WindowInsetsCompat.Type.ime())
            if (isTypingOnPanel && isImeVisible) hideSystemKeyboard()
            if (wasImeVisible && !isImeVisible) onSystemKeyboardClosed()
            wasImeVisible = isImeVisible
            insets
        }
        ViewCompat.requestApplyInsets(host)
        activityHost = host
        presentationHost = (presentation as ReviewerPresentation).host
        showRoles(animate = false)
    }

    override fun onDismissed() {
        activityHost?.let {
            (it.parent as? ViewGroup)?.removeView(it)
            it.destroyWebViews()
        }
        activityHost = null
        presentationHost = null
        controls = null
        statusPill = null
        cardView = null
        runningTransitions = 0
        systemInput = null
        reportLayout(isActive = false)
        updateAnswerFieldInput()
    }

    private fun swap() {
        if (runningTransitions > 0) return
        activity.sharedPrefs().edit { putBoolean(activity.getString(R.string.pref_dual_screen_swapped_key), !isSwapped) }
        showRoles(animate = true)
    }

    private fun showRoles(animate: Boolean) {
        dismissMenu()
        val top = activityHost ?: return
        val bottom = presentationHost ?: return
        val bottomContext = bottom.context
        controls = null
        statusPill = null
        cardView = null
        val newTop: View
        val newBottom: View
        if (isSwapped) {
            newTop = inflate(activity, R.layout.view_dualscreen_reviewer_controls).also { controls = ReviewerControls(it) }
            newBottom =
                inflate(bottomContext, R.layout.view_dualscreen_reviewer_card).also {
                    cardView = it.findViewById<WebView>(R.id.dualscreen_card).apply { setupAsCardMirror(RESERVE_STATUS_PILL_SCRIPT) }
                    statusPill = StatusPill(it.findViewById(R.id.dualscreen_status_pill))
                }
        } else {
            newTop = inflate(activity, R.layout.view_dualscreen_status_pill).also { statusPill = StatusPill(it) }
            newBottom =
                inflate(bottomContext, R.layout.view_dualscreen_reviewer_controls).also { controls = ReviewerControls(it) }
        }
        replaceContent(top, newTop, topLayoutParams(), direction = 1, animate)
        replaceContent(bottom, newBottom, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT), direction = -1, animate)
        hostWhiteboard()
        update()
        loadCards()
    }

    private fun topLayoutParams() =
        if (isSwapped) {
            FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT)
        } else {
            val margin = activity.dp(OVERLAY_MARGIN_DP)
            FrameLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT, Gravity.TOP or Gravity.END).apply {
                setMargins(margin, margin, margin, margin)
            }
        }

    private fun replaceContent(
        host: FrameLayout,
        content: View,
        layoutParams: FrameLayout.LayoutParams,
        direction: Int,
        animate: Boolean,
    ) {
        val previous = host.children.toList()
        host.addView(content, layoutParams)
        if (!animate || host.height == 0) {
            previous.forEach {
                host.removeView(it)
                it.destroyWebViews()
            }
            return
        }
        val distance = host.height * SWAP_DISTANCE * direction
        content.alpha = 0f
        runningTransitions++
        previous.forEach { old ->
            old
                .animate()
                .translationY(distance)
                .scaleX(SWAP_SCALE)
                .scaleY(SWAP_SCALE)
                .alpha(0f)
                .setDuration(SWAP_OUT_MS)
                .setInterpolator(AccelerateInterpolator())
                .withEndAction {
                    host.removeView(old)
                    old.destroyWebViews()
                }
        }
        content.translationY = distance
        content.scaleX = SWAP_SCALE
        content.scaleY = SWAP_SCALE
        content
            .animate()
            .translationY(0f)
            .scaleX(1f)
            .scaleY(1f)
            .alpha(1f)
            .setStartDelay(SWAP_OUT_MS)
            .setDuration(SWAP_IN_MS)
            .setInterpolator(DecelerateInterpolator())
            .withEndAction { runningTransitions = (runningTransitions - 1).coerceAtLeast(0) }
    }

    private fun hostWhiteboard() {
        val host = presentationHost ?: return
        val content = host.getChildAt(host.childCount - 1) ?: return
        val container = content.findViewById<ViewGroup>(R.id.dualscreen_whiteboard) ?: return
        whiteboard?.moveTo(container)
        penPalette?.moveTo(content.findViewById(R.id.dualscreen_pen_palette))
    }

    private fun update() {
        if (presentation != null) reportLayout(isActive = true)
        controls?.update()
        statusPill?.update()
        refreshNavigation()
        updateAnswerFieldInput()
    }

    private fun updateAnswerFieldInput() {
        val field = answerField ?: return
        if (presentation != null) field.imeOptions = field.imeOptions or NO_FULLSCREEN_IME
        if (isTypingOnPanel) {
            if (answerFieldInputType != null) return
            answerFieldInputType = field.inputType
            field.inputType = InputType.TYPE_NULL
            hideSystemKeyboard()
        } else {
            answerFieldInputType?.let { field.inputType = it }
            answerFieldInputType = null
        }
    }

    private fun hideSystemKeyboard() {
        val field = answerField ?: return
        WindowCompat.getInsetsController(activity.window, field).hide(WindowInsetsCompat.Type.ime())
    }

    private fun switchToSystemKeyboard() {
        val window = presentation?.window
        isImeOnMainDisplay = window == null || isSwapped || isNavigating
        isUsingSystemKeyboard = true
        update()
        controls?.loadAnswer()
        val field = answerField ?: return
        if (window == null || isImeOnMainDisplay) {
            field.requestFocus()
            activity.getSystemService<InputMethodManager>()?.restartInput(field)
            WindowCompat.getInsetsController(activity.window, field).show(WindowInsetsCompat.Type.ime())
            return
        }
        val input =
            EditText(window.context).apply {
                alpha = 0f
                inputType = field.inputType
                imeOptions = EditorInfo.IME_ACTION_DONE or NO_FULLSCREEN_IME
                imeHintLocales = field.imeHintLocales
                setText(field.text)
                setSelection(length())
                doAfterTextChanged { field.setText(it) }
                setOnEditorActionListener { _, actionId, _ ->
                    field.onEditorAction(actionId)
                    true
                }
            }
        presentationHost?.addView(input, FrameLayout.LayoutParams(1, 1))
        (window.decorView as ViewGroup).descendantFocusability = ViewGroup.FOCUS_AFTER_DESCENDANTS
        systemInput = input
        input.doOnWindowFocus {
            if (systemInput !== input) return@doOnWindowFocus
            input.requestFocus()
            WindowCompat.getInsetsController(window, input).show(WindowInsetsCompat.Type.ime())
        }
    }

    private fun closeSystemInput() {
        val input = systemInput ?: return
        systemInput = null
        presentation?.window?.let {
            WindowCompat.getInsetsController(it, input).hide(WindowInsetsCompat.Type.ime())
            (it.decorView as ViewGroup).descendantFocusability = ViewGroup.FOCUS_BLOCK_DESCENDANTS
        }
        (input.parent as? ViewGroup)?.removeView(input)
    }

    private fun onSystemKeyboardClosed() {
        if (!isUsingSystemKeyboard) return
        isUsingSystemKeyboard = false
        isImeOnMainDisplay = false
        closeSystemInput()
        update()
        controls?.loadAnswer()
    }

    override val navigationRoot: View?
        get() = controls?.root

    override val navigationExitDirection: Int
        get() = if (isSwapped) View.FOCUS_DOWN else View.FOCUS_UP

    override fun canEnterNavigation(direction: Int): Boolean {
        if (isSwapped) return true
        return direction != navigationExitDirection &&
            (activity.currentFocus?.onCheckIsTextEditor() != true || direction == navigationExitDirection.opposite())
    }

    override fun highlightsActivityFocus(view: View) = view === typingField

    override fun onNavigationExit(
        direction: Int,
        position: Float,
    ) {
        typingField?.requestFocusFromTouch()
    }

    override fun onActivityConfirm(highlighted: View): Boolean {
        if (highlighted !== typingField) return false
        isUsingSystemKeyboard = false
        update()
        enterNavigation()
        return true
    }

    override fun defaultSelection(root: View): View? = controls?.primaryTile?.takeIf { it.isShown } ?: super.defaultSelection(root)

    private fun reportLayout(isActive: Boolean) {
        val layout = Triple(isActive, isActive && isDrawingOnSecondaryDisplay, isSwapped)
        if (layout == reportedLayout) return
        val drawingChanged = reportedLayout?.second != layout.second
        reportedLayout = layout
        onLayoutChanged(layout.first, layout.second)
        if (drawingChanged) controls?.loadAnswer()
    }

    private fun loadCards() {
        controls?.loadAnswer()
        cardView?.loadCard(cardHtml)
    }

    private fun WebView.loadCard(html: String?) {
        if (html == null) {
            loadUrl("about:blank")
        } else {
            loadDataWithBaseURL(baseUrl, html, "text/html", null, null)
        }
    }

    private fun readGamepadHints(): Map<ViewerCommand, String> {
        val prefs = activity.sharedPrefs()

        fun hint(
            command: ViewerCommand,
            side: CardSide,
        ): String? =
            command
                .getBindings(prefs)
                .filter { it.side == side || it.side == CardSide.BOTH }
                .firstNotNullOfOrNull { (it.binding as? Binding.KeyCode)?.keycode?.takeIf(KeyEvent::isGamepadButton) }
                ?.let { KeyEvent.keyCodeToString(it).removePrefix("KEYCODE_BUTTON_").removePrefix("KEYCODE_") }
        return buildMap {
            hint(ViewerCommand.SHOW_ANSWER, CardSide.QUESTION)?.let { put(ViewerCommand.SHOW_ANSWER, it) }
            EASE_COMMANDS.forEach { command -> hint(command, CardSide.ANSWER)?.let { put(command, it) } }
        }
    }

    private inner class ReviewerPresentation(
        display: Display,
    ) : SecondaryDisplayPresentation(activity, display) {
        lateinit var host: FrameLayout

        override fun onCreate(savedInstanceState: Bundle?) {
            super.onCreate(savedInstanceState)
            host = FrameLayout(context)
            setContentView(host)
            ViewCompat.setOnApplyWindowInsetsListener(host) { _, insets ->
                val isImeVisible = insets.isVisible(WindowInsetsCompat.Type.ime())
                if (wasPresentationImeVisible && !isImeVisible) onSystemKeyboardClosed()
                wasPresentationImeVisible = isImeVisible
                insets
            }
        }

        override fun onStop() {
            whiteboardHome?.let { whiteboard?.moveTo(it) }
            penPaletteHome?.let { penPalette?.moveTo(it) }
            host.destroyWebViews()
            super.onStop()
        }
    }

    private inner class StatusPill(
        root: View,
    ) {
        private val countViews =
            listOf(R.id.dualscreen_pill_new, R.id.dualscreen_pill_learn, R.id.dualscreen_pill_review)
                .map { root.findViewById<TextView>(it) }
        private val timerView = root.findViewById<TextView>(R.id.dualscreen_pill_timer)
        private val markView = root.findViewById<ImageView>(R.id.dualscreen_pill_mark)
        private val flagView = root.findViewById<ImageView>(R.id.dualscreen_pill_flag)

        fun update() {
            countViews.forEachIndexed { i, view -> view.text = counts[i] }
            markView.isVisible = isMarked
            flagView.isVisible = flag != Flag.NONE
            flagView.setImageResource(flag.drawableRes)
            updateTimer()
        }

        fun updateTimer() {
            val source = timer
            timerView.isVisible = source?.isVisible == true
            if (source != null) timerView.text = source.text
        }
    }

    private inner class ReviewerControls(
        val root: View,
    ) {
        private val cardArea = root.findViewById<View>(R.id.dualscreen_card_area)
        val answerView: WebView =
            root.findViewById<WebView>(R.id.dualscreen_answer).apply { setupAsCardMirror(onPageFinished = HIDE_QUESTION_SCRIPT) }
        private val buttonArea = root.findViewById<View>(R.id.dualscreen_button_area)
        private val showAnswerTile = root.findViewById<View>(R.id.dualscreen_show_answer)
        private val easeTiles = root.findViewById<View>(R.id.dualscreen_ease_tiles)
        private val firstRow = root.findViewById<LinearLayout>(R.id.dualscreen_tile_row1)
        private val secondRow = root.findViewById<LinearLayout>(R.id.dualscreen_tile_row2)
        private val tiles =
            listOf(R.id.dualscreen_ease1, R.id.dualscreen_ease2, R.id.dualscreen_ease3, R.id.dualscreen_ease4)
                .map { root.findViewById<View>(it) }
        private val nextTimeViews = tiles.map { it.findViewWithTag<TextView>("next_time") }
        private val keyboard = root.findViewById<LinearLayout>(R.id.dualscreen_keyboard)
        private val typedAnswer = root.findViewById<TextView>(R.id.dualscreen_typed_answer)
        private val keyboardInput = SecondaryDisplayKeyboard(root, { answerField }) { update() }
        private val undoButton = root.findViewById<ImageButton>(R.id.dualscreen_action_undo)
        private val redoButton = root.findViewById<ImageButton>(R.id.dualscreen_action_redo)
        private val markButton = root.findViewById<ImageButton>(R.id.dualscreen_action_mark)
        private val flagButton = root.findViewById<ImageButton>(R.id.dualscreen_action_flag)
        private val replayButton = root.findViewById<View>(R.id.dualscreen_action_replay)
        private val eraserButton = root.findViewById<ImageButton>(R.id.dualscreen_action_eraser)
        private val dock = root.findViewById<View>(R.id.dualscreen_dock)
        private val drawTools = root.findViewById<View>(R.id.dualscreen_draw_tools)
        private val drawUndoButton = root.findViewById<ImageButton>(R.id.dualscreen_draw_undo)
        private val drawEraserButton = root.findViewById<ImageButton>(R.id.dualscreen_draw_eraser)
        private val whiteboardTools =
            listOf(
                eraserButton,
                root.findViewById(R.id.dualscreen_action_clear_whiteboard),
                root.findViewById(R.id.dualscreen_action_pen_color),
            )
        private val stripHeight = root.resources.getDimensionPixelSize(R.dimen.dualscreen_strip_height)

        init {
            DOCK_COMMANDS.forEach { (id, command) -> root.findViewById<View>(id).setOnClickListener { onCommand(command) } }
            mapOf(
                R.id.dualscreen_action_flag to ::showFlagMenu,
                R.id.dualscreen_action_swap to ::swap,
                R.id.dualscreen_draw_swap to ::swap,
                R.id.dualscreen_action_more to ::showMoreMenu,
                R.id.dualscreen_draw_more to ::showMoreMenu,
            ).forEach { (id, action) -> root.findViewById<View>(id).setOnClickListener { action() } }
            buttonArea.addOnLayoutChangeListener { _, left, top, right, bottom, oldLeft, oldTop, oldRight, oldBottom ->
                if (right - left != oldRight - oldLeft || bottom - top != oldBottom - oldTop) buttonArea.post { arrangeTiles() }
            }
            val keyboardShowAnswer = root.findViewById<View>(R.id.dualscreen_key_show_answer)
            listOf(showAnswerTile, keyboardShowAnswer).forEach {
                it.setOnClickListener { onCommand(ViewerCommand.SHOW_ANSWER) }
                it.showHint(hints[ViewerCommand.SHOW_ANSWER])
            }
            tiles.zip(EASE_COMMANDS).forEach { (tile, command) ->
                tile.setOnClickListener { onCommand(command) }
                tile.showHint(hints[command])
            }
            root.findViewById<View>(R.id.dualscreen_key_system).setOnClickListener { switchToSystemKeyboard() }
        }

        fun update() {
            val isDrawing = whiteboard?.isVisible == true
            val showsCard = isShowingAnswerCard || isQuestionOnPanel
            val isFullCanvas = isDrawingOnSecondaryDisplay
            cardArea.isVisible = showsCard || isFullCanvas
            answerView.isVisible = showsCard
            drawTools.isVisible = isFullCanvas
            dock.isVisible = !isFullCanvas
            buttonArea.isVisible = !isFullCanvas
            buttonArea.layoutParams =
                if (cardArea.isVisible) {
                    LinearLayout.LayoutParams(MATCH_PARENT, stripHeight)
                } else {
                    LinearLayout.LayoutParams(MATCH_PARENT, 0, 1f)
                }
            drawUndoButton.setEnabledWithAlpha(undoEnabled)
            drawEraserButton.alpha = if (isEraserMode) 1f else DISABLED_ALPHA
            val isTyping = isTypingOnPanel
            showAnswerTile.isVisible = !isDisplayingAnswer && !isTyping
            keyboard.isVisible = isTyping
            keyboardInput.setLayouts(keyboardLayouts)
            typedAnswer.isVisible = isSwapped
            typedAnswer.text = answerField?.text
            easeTiles.isVisible = isDisplayingAnswer
            nextTimeViews.forEachIndexed { i, view -> view.text = nextTimes.getOrNull(i) ?: "" }

            undoButton.setEnabledWithAlpha(undoEnabled)
            redoButton.setEnabledWithAlpha(redoEnabled)
            markButton.setImageResource(if (isMarked) R.drawable.ic_star_white else R.drawable.ic_star_border_white)
            flagButton.setImageResource(flag.drawableRes)
            whiteboardTools.forEach { it.isVisible = isDrawing }
            redoButton.isVisible = !isDrawing
            replayButton.isVisible = !isDrawing
            eraserButton.alpha = if (isEraserMode) 1f else DISABLED_ALPHA
            arrangeTiles()
        }

        val primaryTile: View
            get() =
                when {
                    isDisplayingAnswer -> tiles[2]
                    keyboard.isVisible -> keyboardInput.firstKey ?: showAnswerTile
                    else -> showAnswerTile
                }

        fun loadAnswer() = answerView.loadCard(if (isShowingAnswerCard || isQuestionOnPanel) cardHtml else null)

        private fun arrangeTiles() {
            val width = buttonArea.width
            val height = buttonArea.height
            if (width == 0 || height == 0) return
            val singleRow = cardArea.isVisible || width > height * 2
            val (_, _, good, easy) = tiles
            if (singleRow && secondRow.isNotEmpty()) {
                secondRow.removeAllViews()
                firstRow.addView(good)
                firstRow.addView(easy)
            } else if (!singleRow && secondRow.isEmpty()) {
                firstRow.removeView(good)
                firstRow.removeView(easy)
                secondRow.addView(good)
                secondRow.addView(easy)
            }
            secondRow.isVisible = !singleRow
        }

        private fun actionsInDock(): List<Int> {
            val drawing = listOf(R.id.action_toggle_eraser, R.id.action_clear_whiteboard, R.id.action_change_whiteboard_pen_color)
            val reviewing = listOf(R.id.action_mark_card, R.id.action_flag, R.id.action_edit)
            return listOf(R.id.action_undo) +
                when {
                    isDrawingOnSecondaryDisplay -> drawing
                    whiteboard?.isVisible == true -> reviewing + drawing
                    else -> reviewing + listOf(R.id.action_redo, R.id.action_replay)
                }
        }

        private fun showMoreMenu() {
            val menu = AppCompatPopupMenu(root.context, root).menu
            activity.onCreateOptionsMenu(menu)
            actionsInDock().forEach { menu.removeItem(it) }
            showMenu(root.parent as ViewGroup, menu) { activity.onOptionsItemSelected(it) }
        }

        private fun showFlagMenu() {
            activity.lifecycleScope.launch {
                val names = Flag.queryDisplayNames()
                val menu = AppCompatPopupMenu(root.context, flagButton).menu
                FLAG_COMMANDS.keys.forEach { flag -> menu.add(COLORED_ICONS, flag.code, Menu.NONE, names[flag]).setIcon(flag.drawableRes) }
                showMenu(root.parent as ViewGroup, menu) { onCommand(FLAG_COMMANDS.getValue(Flag.fromCode(it.itemId))) }
            }
        }
    }

    companion object {
        private const val OVERLAY_MARGIN_DP = 12
        private const val SWAP_DISTANCE = 0.3f
        private const val SWAP_SCALE = 0.92f
        private const val SWAP_OUT_MS = 380L
        private const val SWAP_IN_MS = 520L
        private const val NO_FULLSCREEN_IME = EditorInfo.IME_FLAG_NO_EXTRACT_UI or EditorInfo.IME_FLAG_NO_FULLSCREEN

        private val EASE_COMMANDS =
            listOf(ViewerCommand.ANSWER_AGAIN, ViewerCommand.ANSWER_HARD, ViewerCommand.ANSWER_GOOD, ViewerCommand.ANSWER_EASY)

        private val DOCK_COMMANDS =
            mapOf(
                R.id.dualscreen_action_undo to ViewerCommand.UNDO,
                R.id.dualscreen_action_redo to ViewerCommand.REDO,
                R.id.dualscreen_action_mark to ViewerCommand.MARK,
                R.id.dualscreen_action_edit to ViewerCommand.EDIT,
                R.id.dualscreen_action_replay to ViewerCommand.PLAY_MEDIA,
                R.id.dualscreen_action_eraser to ViewerCommand.TOGGLE_ERASER,
                R.id.dualscreen_action_clear_whiteboard to ViewerCommand.CLEAR_WHITEBOARD,
                R.id.dualscreen_action_pen_color to ViewerCommand.CHANGE_WHITEBOARD_PEN_COLOR,
                R.id.dualscreen_draw_undo to ViewerCommand.UNDO,
                R.id.dualscreen_draw_eraser to ViewerCommand.TOGGLE_ERASER,
                R.id.dualscreen_draw_clear to ViewerCommand.CLEAR_WHITEBOARD,
                R.id.dualscreen_draw_pen_color to ViewerCommand.CHANGE_WHITEBOARD_PEN_COLOR,
            )

        private val FLAG_COMMANDS =
            mapOf(
                Flag.RED to ViewerCommand.TOGGLE_FLAG_RED,
                Flag.ORANGE to ViewerCommand.TOGGLE_FLAG_ORANGE,
                Flag.GREEN to ViewerCommand.TOGGLE_FLAG_GREEN,
                Flag.BLUE to ViewerCommand.TOGGLE_FLAG_BLUE,
                Flag.PINK to ViewerCommand.TOGGLE_FLAG_PINK,
                Flag.TURQUOISE to ViewerCommand.TOGGLE_FLAG_TURQUOISE,
                Flag.PURPLE to ViewerCommand.TOGGLE_FLAG_PURPLE,
            )

        private val ANSWER_SEPARATOR = Regex("""id=["']?answer\b""")

        private const val RESERVE_STATUS_PILL_SCRIPT =
            "document.body.style.paddingTop = '56px'; document.documentElement.style.scrollPaddingTop = '100000px'; window.scrollTo(0, 0);"

        private const val RELEASE_STATUS_PILL_SCRIPT =
            "document.body.style.paddingTop = ''; document.documentElement.style.scrollPaddingTop = '';"

        private const val HIDE_FOCUS_OUTLINE_SCRIPT =
            "document.head.insertAdjacentHTML('beforeend', '<style>:focus { outline: none; }</style>');"

        private const val HIDE_NODES = """
            const answer = document.getElementById("answer");
            const hide = (node, next) => {
                while (node) {
                    const following = next(node);
                    if (node.nodeType === Node.ELEMENT_NODE) node.style.display = "none";
                    else if (node.nodeType === Node.TEXT_NODE) node.textContent = "";
                    node = following;
                }
            };
        """

        private const val HIDE_ANSWER_SCRIPT = "(() => { $HIDE_NODES hide(answer, n => n.nextSibling); })();"

        private const val HIDE_QUESTION_SCRIPT =
            "(() => { $HIDE_NODES if (!answer) return; hide(answer, n => n.previousSibling); window.scrollTo(0, 0); })();"

        private fun inflate(
            context: Context,
            layout: Int,
        ): View = LayoutInflater.from(context).inflate(layout, null, false)

        private fun Context.dp(value: Int) = (value * resources.displayMetrics.density).toInt()

        private fun View.destroyWebViews() {
            when (this) {
                is WebView -> destroy()
                is ViewGroup -> forEach { it.destroyWebViews() }
            }
        }

        private fun View.doOnWindowFocus(action: () -> Unit) {
            if (hasWindowFocus()) return action()
            viewTreeObserver.addOnWindowFocusChangeListener(
                object : ViewTreeObserver.OnWindowFocusChangeListener {
                    override fun onWindowFocusChanged(hasFocus: Boolean) {
                        if (!hasFocus) return
                        viewTreeObserver.removeOnWindowFocusChangeListener(this)
                        action()
                    }
                },
            )
        }

        private fun View.moveTo(parent: ViewGroup) {
            (this.parent as? ViewGroup)?.removeView(this)
            parent.addView(this, layoutParams)
        }

        private fun View.showHint(hint: String?) {
            findViewWithTag<TextView>("hint").apply {
                text = hint
                isVisible = hint != null
            }
        }

        private fun WebView.setupAsCardMirror(onPageFinished: String? = null) {
            val resourceHandler = ViewerResourceHandler(context)
            with(settings) {
                javaScriptEnabled = true
                domStorageEnabled = true
                allowFileAccess = true
                mediaPlaybackRequiresUserGesture = true
            }
            setBackgroundColor(Color.TRANSPARENT)
            webViewClient =
                object : WebViewClient() {
                    override fun shouldInterceptRequest(
                        view: WebView,
                        request: WebResourceRequest,
                    ): WebResourceResponse? = resourceHandler.shouldInterceptRequest(request)

                    override fun shouldOverrideUrlLoading(
                        view: WebView,
                        request: WebResourceRequest,
                    ) = true

                    override fun onPageFinished(
                        view: WebView,
                        url: String?,
                    ) {
                        onPageFinished?.let { view.evaluateJavascript(it, null) }
                    }
                }
        }
    }
}
