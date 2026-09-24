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
import android.text.Editable
import android.util.TypedValue
import android.view.View
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.Space
import android.widget.TextView
import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import androidx.core.content.getSystemService
import androidx.core.view.isVisible
import androidx.core.widget.TextViewCompat
import com.ichi2.anki.R
import java.text.BreakIterator
import java.text.Normalizer
import java.util.Locale
import kotlin.math.max
import kotlin.math.min

private const val KEY_MARGIN_DP = 3
private const val WIDE_KEY_WEIGHT = 1.5f
private const val EXTRA_KEYS_PER_ROW = 11
private const val GAP = "　"
private const val DOTTED_CIRCLE = '◌'
private const val SMALL_KANA_KEY = "小"
private const val SMALLABLE_KANA = "あいうえおつやゆよわアイウエオツヤユヨワ"
private const val SMALL_KANA = "ぁぃぅぇぉっゃゅょゎァィゥェォッャュョヮ"
private const val QWERTY = "qwertyuiop|asdfghjkl|zxcvbnm"
private const val QWERTZ = "qwertzuiop|asdfghjkl|yxcvbnm"
private const val AZERTY = "azertyuiop|qsdfghjklm|wxcvbn"
private const val RUSSIAN = "йцукенгшщзх|фывапролджэ|ячсмитьбю"
private const val PERSIAN = "ضصثقفغعهخحج|شسیبلاتنمکگ|ظطزرذدپو"
private const val HIRAGANA = "あかさたなはまやらわ|いきしちにひみ　り　|うくすつぬふむゆるを|えけせてねへめ　れ　|おこそとのほもよろん|゛゜小ー、。"

private const val HANGUL_INITIALS = "ㄱㄲㄴㄷㄸㄹㅁㅂㅃㅅㅆㅇㅈㅉㅊㅋㅌㅍㅎ"
private const val HANGUL_VOWELS = "ㅏㅐㅑㅒㅓㅔㅕㅖㅗㅘㅙㅚㅛㅜㅝㅞㅟㅠㅡㅢㅣ"
private const val HANGUL_FINALS = " ㄱㄲㄳㄴㄵㄶㄷㄹㄺㄻㄼㄽㄾㄿㅀㅁㅂㅄㅅㅆㅇㅈㅊㅋㅌㅍㅎ"
private val HANGUL_VOWEL_PAIRS = mapOf("ㅗㅏ" to 'ㅘ', "ㅗㅐ" to 'ㅙ', "ㅗㅣ" to 'ㅚ', "ㅜㅓ" to 'ㅝ', "ㅜㅔ" to 'ㅞ', "ㅜㅣ" to 'ㅟ', "ㅡㅣ" to 'ㅢ')
private val HANGUL_FINAL_PAIRS =
    mapOf(
        "ㄱㅅ" to 'ㄳ',
        "ㄴㅈ" to 'ㄵ',
        "ㄴㅎ" to 'ㄶ',
        "ㄹㄱ" to 'ㄺ',
        "ㄹㅁ" to 'ㄻ',
        "ㄹㅂ" to 'ㄼ',
        "ㄹㅅ" to 'ㄽ',
        "ㄹㅌ" to 'ㄾ',
        "ㄹㅍ" to 'ㄿ',
        "ㄹㅎ" to 'ㅀ',
        "ㅂㅅ" to 'ㅄ',
    )

private val SYMBOL_ROWS = "1234567890|@#&*-+=()/|.,?!:;'\"%".split('|').map { it.graphemes() }

private val LAYOUTS =
    listOf(
        KeyboardLayout("en", QWERTY),
        KeyboardLayout("de", QWERTZ, "äöüß"),
        KeyboardLayout("fr", AZERTY, "éèêëàâçîïôùûüœ"),
        KeyboardLayout("es", QWERTY, "ñáéíóúü"),
        KeyboardLayout("pt", QWERTY, "áâãàçéêíóôõú"),
        KeyboardLayout("it", QWERTY, "àèéìòù"),
        KeyboardLayout("tr", "qwertyuıopğü|asdfghjklşi|zxcvbnmöç"),
        KeyboardLayout("nl", QWERTY, "éëïóü"),
        KeyboardLayout("ca", QWERTY, "àçèéíïòóúü"),
        KeyboardLayout("pl", QWERTY, "ąćęłńóśźż"),
        KeyboardLayout("cs", QWERTZ, "áčďéěíňóřšťúůýž"),
        KeyboardLayout("sk", QWERTZ, "áäčďéíĺľňóôŕšťúýž"),
        KeyboardLayout("hu", QWERTZ, "áéíóöőúüű"),
        KeyboardLayout("ro", QWERTY, "ăâîșț"),
        KeyboardLayout("hr", QWERTZ, "čćđšž"),
        KeyboardLayout("sl", QWERTZ, "čšž"),
        KeyboardLayout("sq", QWERTY, "çë"),
        KeyboardLayout("sv", QWERTY, "åäö"),
        KeyboardLayout("fi", QWERTY, "åäöšž"),
        KeyboardLayout("no", QWERTY, "æøå"),
        KeyboardLayout("da", QWERTY, "æøå"),
        KeyboardLayout("is", QWERTY, "áðéíóúýþæö"),
        KeyboardLayout("et", QWERTY, "äõöüšž"),
        KeyboardLayout("lv", QWERTY, "āčēģīķļņšūž"),
        KeyboardLayout("lt", QWERTY, "ąčęėįšųūž"),
        KeyboardLayout("az", QWERTY, "çəğıöşü"),
        KeyboardLayout("vi", QWERTY, "ăâđêôơư◌́◌̀◌̉◌̃◌̣"),
        KeyboardLayout("eo", QWERTY, "ĉĝĥĵŝŭ"),
        KeyboardLayout("ga", QWERTY, "áéíóú"),
        KeyboardLayout("cy", QWERTY, "âêîôûŵŷ"),
        KeyboardLayout("mt", QWERTY, "ċġħż"),
        KeyboardLayout("ru", RUSSIAN, "ёъ"),
        KeyboardLayout("uk", "йцукенгшщзхї|фівапролджє|ячсмитьбю", "ґ'"),
        KeyboardLayout("be", "йцукенгшўзх|фывапролджэ|ячсмітьбю", "ё'"),
        KeyboardLayout("bg", RUSSIAN, "ѝ"),
        KeyboardLayout("sr", "љњертзуиопш|асдфгхјклчћ|џцвбнмђж"),
        KeyboardLayout("mk", "љњертѕуиопш|асдфгхјклчќ|зџцвбнмѓж"),
        KeyboardLayout("kk", RUSSIAN, "әғқңөұүһіёъ"),
        KeyboardLayout("ky", RUSSIAN, "ңөүёъ"),
        KeyboardLayout("mn", RUSSIAN, "өүёъ"),
        KeyboardLayout("el", "ςερτυθιοπ|ασδφγηξκλ|ζχψωβνμ", "άέήίόύώϊϋΐΰ"),
        KeyboardLayout("he", "קראטוןםפ|שדגכעיחלךף|זסבהנמצתץ"),
        KeyboardLayout("ar", "ضصثقفغعهخحج|شسيبلاتنمكط|ذءؤرىةوزظد", "أإآئ"),
        KeyboardLayout("fa", PERSIAN, "چژآئ"),
        KeyboardLayout("ur", PERSIAN, "چژآٹڈڑںےھ"),
        KeyboardLayout("ka", "ქწერტყუიოპ|ასდფგჰჯკლ|ზხცვბნმ", shiftRows = "ქჭეღთყუიოპ|აშდფგჰჟკლ|ძხჩვბნმ"),
        KeyboardLayout(
            "ko",
            "ㅂㅈㄷㄱㅅㅛㅕㅑㅐㅔ|ㅁㄴㅇㄹㅎㅗㅓㅏㅣ|ㅋㅌㅊㅍㅠㅜㅡ",
            shiftRows = "ㅃㅉㄸㄲㅆㅛㅕㅑㅒㅖ|ㅁㄴㅇㄹㅎㅗㅓㅏㅣ|ㅋㅌㅊㅍㅠㅜㅡ",
            composesHangul = true,
        ),
        KeyboardLayout("ja-Hira", HIRAGANA),
        KeyboardLayout("ja-Kana", HIRAGANA.map { if (it in 'ぁ'..'ゖ') it + 0x60 else it }.joinToString("")),
        KeyboardLayout(
            "hi",
            "अआइईउऊऋएऐओऔ|◌ा◌ि◌ी◌ु◌ू◌ृ◌े◌ै◌ो◌ौ◌ं◌ः◌्◌ँ|कखगघङचछजझञ|टठडढणतथदधन|पफबभमयरलवशषसह",
        ),
    ).associateBy { it.languageTag }

private val SUPPORTED_SCRIPTS = LAYOUTS.values.map { it.script }.toSet()

internal class KeyboardLayout(
    val languageTag: String,
    letters: String,
    extras: String = "",
    shiftRows: String? = null,
    val composesHangul: Boolean = false,
) {
    val locale: Locale = Locale.forLanguageTag(languageTag)
    val rows = extras.graphemes().chunked(EXTRA_KEYS_PER_ROW) + letters.split('|').map { it.graphemes() }
    private val shiftRows = shiftRows?.split('|')?.map { it.graphemes() }
    val script: Character.UnicodeScript = rows.flatten().firstNotNullOf { it.letterScripts().firstOrNull() }
    val hasShift = this.shiftRows != null || rows.flatten().any { it.shifted() != it }
    val label: String = locale.getDisplayName(locale).replaceFirstChar { it.titlecase(locale) }
    val sample = rows.last().take(3).joinToString("")
    private val codePoints by lazy {
        (rows + this.shiftRows.orEmpty())
            .flatten()
            .joinToString("") { it.inserted() }
            .decomposed()
            .toSet()
    }

    fun key(
        row: Int,
        column: Int,
        isShifted: Boolean,
    ) = when {
        !isShifted -> rows[row][column]
        shiftRows != null -> shiftRows[row][column]
        else -> rows[row][column].shifted()
    }

    fun covers(answer: String) =
        answer.lowercase(locale).decomposed().all { (!Character.isLetter(it) && !it.isCombiningMark()) || it in codePoints }

    private fun String.shifted() = uppercase(locale).takeIf { it.codePointCount(0, it.length) == 1 } ?: this
}

internal fun keyboardLayoutsFor(
    context: Context,
    answer: String?,
    hint: Locale?,
): List<KeyboardLayout> {
    val preferred =
        (listOfNotNull(hint?.toLanguageTag()) + context.keyboardLanguages() + Locale.getDefault().toLanguageTag() + "en")
            .mapNotNull(::layoutFor)
            .distinct()
    if (answer.isNullOrBlank()) return preferred
    val scripts = answer.letterScripts()
    if (scripts.any { it !in SUPPORTED_SCRIPTS }) return emptyList()
    val best =
        preferred.firstOrNull { it.covers(answer) }
            ?: LAYOUTS.values.firstOrNull { it.covers(answer) }
            ?: preferred.firstOrNull { it.script in scripts }
    val forScripts = scripts.mapNotNull { script -> LAYOUTS.values.firstOrNull { it.script == script } }
    return (listOfNotNull(best) + forScripts + preferred).distinct()
}

internal class SecondaryDisplayKeyboard(
    root: View,
    private val field: () -> EditText?,
    private val onEdited: () -> Unit,
) {
    private val rowsView = root.findViewById<LinearLayout>(R.id.dualscreen_key_rows)
    private val languageKey = root.findViewById<View>(R.id.dualscreen_key_language)
    private val symbolsKey = root.findViewById<TextView>(R.id.dualscreen_key_symbols)
    private val spaceKey = root.findViewById<TextView>(R.id.dualscreen_key_space)
    private val letterKeys = mutableListOf<Triple<TextView, Int, Int>>()
    private var shiftKey: ImageView? = null
    private var layouts = listOf(LAYOUTS.getValue("en"))
    private var index = 0
    private var isShifted = false
    private var showsSymbols = false
    private val layout get() = layouts[index]

    var firstKey: View? = null
        private set

    init {
        languageKey.setOnClickListener { select(index + 1) }
        symbolsKey.setOnClickListener {
            showsSymbols = !showsSymbols
            build()
        }
        spaceKey.setOnClickListener { type(" ") }
        build()
    }

    fun setLayouts(value: List<KeyboardLayout>) {
        if (value.isEmpty() || value == layouts) return
        layouts = value
        select(0)
    }

    private fun select(position: Int) {
        index = position % layouts.size
        isShifted = false
        showsSymbols = false
        build()
    }

    private fun build() {
        rowsView.removeAllViews()
        letterKeys.clear()
        shiftKey = null
        val rows = if (showsSymbols) SYMBOL_ROWS else layout.rows
        rows.forEachIndexed { rowIndex, keys ->
            val row = LinearLayout(rowsView.context)
            val isLast = rowIndex == rows.lastIndex
            if (isLast && !showsSymbols && layout.hasShift) {
                shiftKey =
                    row.addIconKey(R.drawable.ic_dualscreen_shift, R.string.dual_screen_shift) {
                        isShifted = !isShifted
                        updateLabels()
                    }
            }
            keys.forEachIndexed { column, key ->
                if (key == GAP) {
                    row.addView(Space(row.context), LinearLayout.LayoutParams(0, MATCH_PARENT, 1f))
                } else {
                    val view = TextView(row.context, null, 0, R.style.DualScreenKey)
                    TextViewCompat.setAutoSizeTextTypeUniformWithConfiguration(view, 10, 22, 1, TypedValue.COMPLEX_UNIT_SP)
                    row.addKey(view) { type(view.text.toString()) }
                    letterKeys += Triple(view, rowIndex, column)
                }
            }
            if (isLast) row.addIconKey(R.drawable.ic_dualscreen_backspace, R.string.dual_screen_backspace) { delete() }
            rowsView.addView(row, LinearLayout.LayoutParams(MATCH_PARENT, 0, 1f))
        }
        rowsView.layoutParams = (rowsView.layoutParams as LinearLayout.LayoutParams).apply { weight = rows.size.toFloat() }
        firstKey = letterKeys.firstOrNull()?.first
        languageKey.isVisible = layouts.size > 1
        symbolsKey.text = if (showsSymbols) layout.sample else symbolsKey.context.getString(R.string.dual_screen_symbols)
        spaceKey.text = layout.label
        updateLabels()
    }

    private fun updateLabels() {
        letterKeys.forEach { (view, row, column) ->
            view.text = if (showsSymbols) SYMBOL_ROWS[row][column] else layout.key(row, column, isShifted)
        }
        shiftKey?.setImageLevel(if (isShifted) 1 else 0)
    }

    private fun type(label: String) {
        val field = field() ?: return
        val text = field.text
        val (start, end) = field.selectionRange()
        val key = label.inserted()
        val previous = text.getOrNull(start - 1).takeIf { start == end }
        val replacement =
            when {
                previous == null -> null
                key == SMALL_KANA_KEY -> toggledSmallKana(previous)
                layout.composesHangul && key.length == 1 -> composeHangul(previous, key[0])
                else -> null
            }
        if (replacement != null) {
            text.replace(start - 1, start, replacement)
        } else if (key != SMALL_KANA_KEY) {
            text.replace(start, end, key)
            if (key.codePointAt(0).isCombiningMark()) text.composeBefore(start + key.length)
        }
        if (isShifted) {
            isShifted = false
            updateLabels()
        }
        onEdited()
    }

    private fun delete() {
        val field = field() ?: return
        val (start, end) = field.selectionRange()
        if (start != end) {
            field.text.delete(start, end)
        } else if (start > 0) {
            field.text.delete(start - 1, start)
        }
        onEdited()
    }
}

private fun layoutFor(tag: String): KeyboardLayout? {
    val locale = Locale.forLanguageTag(tag)
    val language =
        when (val code = locale.language) {
            "iw" -> "he"
            "nb", "nn" -> "no"
            else -> code
        }
    return if (language == "sr" && locale.script == "Latn") LAYOUTS["hr"] else LAYOUTS[language]
}

private fun Context.keyboardLanguages(): List<String> {
    val manager = getSystemService<InputMethodManager>() ?: return emptyList()
    val enabled = manager.enabledInputMethodList.flatMap { manager.getEnabledInputMethodSubtypeList(it, true) }
    val subtypes = listOfNotNull(manager.currentInputMethodSubtype) + enabled
    return subtypes.map { it.languageTag }.filter { it.isNotEmpty() }
}

private fun toggledSmallKana(previous: Char): String? =
    SMALLABLE_KANA.indexOf(previous).takeIf { it >= 0 }?.let { SMALL_KANA[it].toString() }
        ?: SMALL_KANA.indexOf(previous).takeIf { it >= 0 }?.let { SMALLABLE_KANA[it].toString() }

private fun composeHangul(
    previous: Char,
    jamo: Char,
): String? {
    val syllable = previous - '가'
    val isSyllable = syllable in 0 until 11172
    val initial = syllable / 588
    val vowel = syllable % 588 / 28
    val final = syllable % 28

    fun build(
        i: Int,
        v: Int,
        f: Int = 0,
    ) = ('가' + (i * 21 + v) * 28 + f).toString()

    if (jamo in HANGUL_VOWELS) {
        val v = HANGUL_VOWELS.indexOf(jamo)
        return when {
            previous in HANGUL_INITIALS -> build(HANGUL_INITIALS.indexOf(previous), v)
            !isSyllable -> HANGUL_VOWEL_PAIRS["$previous$jamo"]?.toString()
            final == 0 -> HANGUL_VOWEL_PAIRS["${HANGUL_VOWELS[vowel]}$jamo"]?.let { build(initial, HANGUL_VOWELS.indexOf(it)) }
            else -> {
                val pair = HANGUL_FINAL_PAIRS.entries.firstOrNull { it.value == HANGUL_FINALS[final] }?.key
                val (kept, moved) = if (pair != null) HANGUL_FINALS.indexOf(pair[0]) to pair[1] else 0 to HANGUL_FINALS[final]
                build(initial, vowel, kept) + build(HANGUL_INITIALS.indexOf(moved), v)
            }
        }
    }
    if (!isSyllable) return null
    val combined = if (final == 0) jamo else HANGUL_FINAL_PAIRS["${HANGUL_FINALS[final]}$jamo"] ?: return null
    return HANGUL_FINALS.indexOf(combined).takeIf { it > 0 }?.let { build(initial, vowel, it) }
}

private fun LinearLayout.addKey(
    key: View,
    weight: Float = 1f,
    onClick: () -> Unit,
) {
    val margin = (KEY_MARGIN_DP * resources.displayMetrics.density).toInt()
    addView(key, LinearLayout.LayoutParams(0, MATCH_PARENT, weight).apply { setMargins(margin, margin, margin, margin) })
    key.setOnClickListener { onClick() }
}

private fun LinearLayout.addIconKey(
    @DrawableRes icon: Int,
    @StringRes description: Int,
    onClick: () -> Unit,
) = ImageView(context, null, 0, R.style.DualScreenKey).apply {
    setImageResource(icon)
    contentDescription = context.getString(description)
    addKey(this, WIDE_KEY_WEIGHT, onClick)
}

private fun EditText.selectionRange(): Pair<Int, Int> {
    val start = selectionStart.takeIf { it >= 0 } ?: length()
    val end = selectionEnd.takeIf { it >= 0 } ?: length()
    return min(start, end) to max(start, end)
}

private fun Editable.composeBefore(cursor: Int) {
    val from = max(0, cursor - 3)
    replace(from, cursor, Normalizer.normalize(subSequence(from, cursor), Normalizer.Form.NFC))
}

private fun String.inserted() =
    when {
        startsWith(DOTTED_CIRCLE) -> drop(1)
        this == "゛" -> "\u3099"
        this == "゜" -> "\u309A"
        else -> this
    }

private fun String.graphemes(): List<String> {
    val iterator = BreakIterator.getCharacterInstance()
    iterator.setText(this)
    val boundaries = generateSequence(iterator.first()) { iterator.next().takeIf { it != BreakIterator.DONE } }.toList()
    return boundaries.zipWithNext { start, end -> substring(start, end) }
}

private fun String.decomposed() =
    Normalizer
        .normalize(this, Normalizer.Form.NFD)
        .codePoints()
        .toArray()
        .toList()

private fun String.letterScripts() =
    decomposed()
        .filter { Character.isLetter(it) }
        .map { Character.UnicodeScript.of(it) }
        .filter { it != Character.UnicodeScript.COMMON && it != Character.UnicodeScript.INHERITED }
        .distinct()

private fun Int.isCombiningMark() =
    Character.getType(this).let { it == Character.NON_SPACING_MARK.toInt() || it == Character.COMBINING_SPACING_MARK.toInt() }
