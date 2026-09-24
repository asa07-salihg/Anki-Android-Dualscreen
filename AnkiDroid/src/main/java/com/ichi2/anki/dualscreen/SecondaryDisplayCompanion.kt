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

import android.content.Intent
import android.os.Bundle
import android.view.Display
import android.view.View
import android.view.ViewGroup
import android.view.ViewTreeObserver
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.annotation.IdRes
import androidx.core.view.forEach
import androidx.core.view.isVisible
import androidx.lifecycle.lifecycleScope
import com.ichi2.anki.AnkiActivity
import com.ichi2.anki.CardBrowser
import com.ichi2.anki.CollectionManager.withCol
import com.ichi2.anki.DeckPicker
import com.ichi2.anki.IntroductionActivity
import com.ichi2.anki.NoteEditorActivity
import com.ichi2.anki.R
import com.ichi2.anki.Reviewer
import com.ichi2.anki.SingleFragmentActivity
import com.ichi2.anki.libanki.sched.DeckNode
import com.ichi2.anki.noteeditor.NoteEditorLauncher
import com.ichi2.anki.pages.Statistics
import com.ichi2.anki.pages.StatisticsDestination
import com.ichi2.anki.preferences.PreferencesActivity
import com.ichi2.anki.ui.windows.permissions.AllPermissionsExplanationActivity
import com.ichi2.anki.ui.windows.permissions.PermissionsActivity
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import timber.log.Timber
import kotlin.reflect.KClass

fun AnkiActivity.supportsCompanionDisplay() =
    this !is IntroductionActivity && this !is PermissionsActivity && this !is AllPermissionsExplanationActivity

open class SecondaryDisplayCompanion(
    private val ankiActivity: AnkiActivity,
) : SecondaryDisplay<SecondaryDisplayCompanion.CompanionPresentation>(ankiActivity) {
    var deck: DeckNode? = null
        set(value) {
            field = value
            presentation?.update()
        }
    var studiedToday = ""
        set(value) {
            field = value
            presentation?.update()
        }

    override fun createPresentation(display: Display) = CompanionPresentation(display)

    override fun onShown(presentation: CompanionPresentation) {
        presentation.update()
        reload()
    }

    protected open fun reload() {
        ankiActivity.lifecycleScope.launch {
            try {
                val (studied, node) = withCol { sched.studiedToday().replace("\n", " ") to sched.deckDueTree().find(decks.current().id) }
                studiedToday = studied
                deck = node
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Timber.w(e, "Unable to load the companion display")
            }
        }
    }

    protected open fun study() = ankiActivity.startActivity(Reviewer.getIntent(ankiActivity))

    inner class CompanionPresentation(
        display: Display,
    ) : SecondaryDisplayPresentation(ankiActivity, display) {
        private lateinit var deckView: TextView
        private lateinit var countsView: View
        private lateinit var countViews: List<TextView>
        private lateinit var studiedTodayView: TextView
        private lateinit var studyButton: View
        private val actions = mutableMapOf<TextView, Boolean>()
        private var isStatisticsScreen = false
        private val layoutListener = ViewTreeObserver.OnGlobalLayoutListener { hideDuplicates() }

        override fun onCreate(savedInstanceState: Bundle?) {
            super.onCreate(savedInstanceState)
            setContentView(R.layout.view_dualscreen_companion)
            deckView = findViewById(R.id.dualscreen_companion_deck)
            countsView = findViewById(R.id.dualscreen_companion_counts)
            countViews =
                listOf(R.id.dualscreen_companion_new, R.id.dualscreen_companion_learn, R.id.dualscreen_companion_review)
                    .map { findViewById(it) }
            studiedTodayView = findViewById(R.id.dualscreen_companion_studied_today)
            studyButton = findViewById<View>(R.id.dualscreen_companion_study).apply { setOnClickListener { study() } }
            val screens = listOfNotNull(ankiActivity, (ankiActivity as? SingleFragmentActivity)?.fragment)
            isStatisticsScreen = screens.any { it is Statistics }
            shortcuts().forEach { shortcut ->
                val action = findViewById<TextView>(shortcut.id)
                actions[action] = shortcut.hiddenOn.none { screen -> screens.any { screen.isInstance(it) } }
                action.setOnClickListener { ankiActivity.startActivity(shortcut.intent()) }
            }
        }

        override fun onStart() {
            super.onStart()
            ankiActivity.window.decorView.viewTreeObserver
                .addOnGlobalLayoutListener(layoutListener)
        }

        override fun onStop() {
            ankiActivity.window.decorView.viewTreeObserver
                .takeIf { it.isAlive }
                ?.removeOnGlobalLayoutListener(layoutListener)
            super.onStop()
        }

        fun update() {
            val deck = deck
            deckView.text = deck?.lastDeckNameComponent
            val counts = listOf(deck?.newCount, deck?.lrnCount, deck?.revCount)
            countViews.zip(counts).forEach { (view, count) -> view.text = count?.toString() }
            studiedTodayView.text = studiedToday
            studiedTodayView.isVisible = studiedToday.isNotBlank() && !isStatisticsScreen
            studyButton.setEnabledWithAlpha(deck?.hasCardsReadyToStudy() == true)
            hideDuplicates()
        }

        private fun hideDuplicates() {
            val shownAbove = ankiActivity.window.decorView.visibleTexts()
            val name = deckView.text.normalized()
            deckView.isVisible = name.isNotBlank() && shownAbove.none { it == name || it.endsWith("::$name") }
            countsView.isVisible = countViews.any { it.text.isNotBlank() } && !countViews.all { it.text.normalized() in shownAbove }
            actions.forEach { (action, isAllowed) -> action.isVisible = isAllowed && action.text.normalized() !in shownAbove }
        }

        private fun shortcuts() =
            listOf(
                Shortcut(R.id.dualscreen_companion_decks, DeckPicker::class) {
                    Intent(ankiActivity, DeckPicker::class.java).addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                },
                Shortcut(R.id.dualscreen_companion_add, NoteEditorActivity::class, DeckPicker::class) {
                    NoteEditorLauncher.AddNote(deck?.did).toIntent(ankiActivity)
                },
                Shortcut(R.id.dualscreen_companion_browse, CardBrowser::class) { Intent(ankiActivity, CardBrowser::class.java) },
                Shortcut(R.id.dualscreen_companion_statistics, Statistics::class) { StatisticsDestination().toIntent(ankiActivity) },
                Shortcut(R.id.dualscreen_companion_settings, PreferencesActivity::class) { PreferencesActivity.getIntent(ankiActivity) },
            )
    }
}

class SecondaryDisplayDeckPicker(
    deckPicker: DeckPicker,
    private val onStudy: () -> Unit,
    private val onActiveChanged: (Boolean) -> Unit,
) : SecondaryDisplayCompanion(deckPicker) {
    override val isEnabled: Boolean
        get() = super.isEnabled && isPreferenceEnabled(R.string.pref_dual_screen_deck_picker_key)

    override fun reload() {}

    override fun study() = onStudy()

    override fun onShown(presentation: CompanionPresentation) {
        super.onShown(presentation)
        onActiveChanged(true)
    }

    override fun onDismissed() = onActiveChanged(false)
}

class SecondaryDisplayGuide(
    activity: ComponentActivity,
) : SecondaryDisplay<SecondaryDisplayPresentation>(activity) {
    override fun createPresentation(display: Display) =
        object : SecondaryDisplayPresentation(activity, display) {
            override fun onCreate(savedInstanceState: Bundle?) {
                super.onCreate(savedInstanceState)
                setContentView(R.layout.view_dualscreen_guide)
            }
        }
}

private class Shortcut(
    @IdRes val id: Int,
    vararg val hiddenOn: KClass<*>,
    val intent: () -> Intent,
)

private fun CharSequence.normalized() = toString().trim()

private fun View.visibleTexts(): Set<String> =
    buildSet {
        fun collect(view: View) {
            if (!view.isShown) return
            (view as? TextView)?.text?.takeIf { it.isNotBlank() }?.let { add(it.normalized()) }
            view.contentDescription?.takeIf { it.isNotBlank() }?.let { add(it.normalized()) }
            if (view is ViewGroup) view.forEach(::collect)
        }
        collect(this@visibleTexts)
    }
