package com.tyler.primelauncher

import android.content.ClipboardManager
import android.view.View
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import com.tyler.primelauncher.emitkb.ui.EditState
import com.tyler.primelauncher.emitkb.ui.KbController
import com.tyler.primelauncher.emitkb.ui.KbTarget
import com.tyler.primelauncher.emitkb.ui.LocalKb
import com.tyler.primelauncher.emitkb.ui.LocalTheme
import com.tyler.primelauncher.emitkb.ui.MatrixKeyboard
import com.tyler.primelauncher.emitkb.ui.MatrixTheme
import com.tyler.primelauncher.emitkb.ui.NeonCityTheme

/**
 * Emit's in-app keyboard (MatrixKeyboard, ported in emitkb/) driving a plain EditText on the home screen.
 * The system IME is suppressed for the field (showSoftInputOnFocus = false) unless the user taps Emit's ⌨ key,
 * exactly like Emit; the field's [mx] chip switches back.
 */
class PrimeKeyboard(private val act: HomeActivity) {
    val kb = KbController()
    private var neon by mutableStateOf(false)
    private var navDp by mutableFloatStateOf(0f)
    private var field: EditText? = null
    /** Called whenever the keyboard opens/closes or switches to/from the system IME. */
    var onChange: () -> Unit = {}

    val view: ComposeView = ComposeView(act).apply {
        setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnDetachedFromWindow)
        setContent {
            CompositionLocalProvider(LocalKb provides kb, LocalTheme provides if (neon) NeonCityTheme else MatrixTheme) {
                LaunchedEffect(Unit) { snapshotFlow { kb.target to kb.useSystem }.collect { sync() } }
                Column(Modifier.fillMaxWidth()) {
                    MatrixKeyboard(kb)
                    // Black strip under the keys so they sit above the gesture/nav bar (the window draws edge to edge).
                    if (kb.visible) Spacer(Modifier.fillMaxWidth().height(navDp.dp).background(Color(if (neon) 0xFF07060B else 0xFF000000)))
                }
            }
        }
    }

    init { kb.init(act) { emptyList() } }

    /** Open = our keyboard showing, or the system IME the user switched to with ⌨. */
    val open: Boolean get() = kb.target != null
    val custom: Boolean get() = kb.visible

    fun theme(neon: Boolean) { this.neon = neon }
    fun navInset(dp: Float) { navDp = dp }

    /** Hook a field up: tapping it focuses it and opens Emit's keyboard instead of the system one. */
    fun attach(e: EditText, actionLabel: String, onAction: () -> Unit) {
        val old = field; field = e
        if (old != null && old !== e) old.onFocusChangeListener = null // the old box is gone; don't let its focus loss close the keyboard
        e.showSoftInputOnFocus = false
        e.isFocusable = true; e.isFocusableInTouchMode = true
        e.setOnFocusChangeListener { _, has -> if (has) show(e, actionLabel, onAction) else if (kb.target?.id === e) kb.hide() }
        e.setOnClickListener { if (kb.target?.id !== e) show(e, actionLabel, onAction) }
    }

    fun show(e: EditText, actionLabel: String, onAction: () -> Unit) {
        if (!e.hasFocus()) e.requestFocus()
        kb.target = KbTarget(e, phone = false, singleLine = true,
            get = { TextFieldValue(e.text.toString(), TextRange(e.selectionStart.coerceAtLeast(0), e.selectionEnd.coerceAtLeast(0))) },
            set = { v -> apply(e, v) },
            paste = { pasteInto(e) },
            actionLabel = actionLabel, onAction = onAction)
    }

    fun hide() { if (kb.target != null) kb.hide() else sync() }
    /** Back to Emit's keys from the system keyboard (the [mx] chip). */
    fun useMatrix() { kb.useSystem = false }

    /** Single place that reconciles the EditText focus / system IME with the controller state. */
    private fun sync() {
        val e = field ?: return; val imm = act.getSystemService(InputMethodManager::class.java)
        when {
            kb.target == null -> { kb.useSystem = false; e.showSoftInputOnFocus = false; imm.hideSoftInputFromWindow(e.windowToken, 0); if (e.hasFocus()) e.clearFocus() }
            kb.useSystem -> { e.showSoftInputOnFocus = true; if (!e.hasFocus()) e.requestFocus(); imm.restartInput(e); imm.showSoftInput(e, InputMethodManager.SHOW_IMPLICIT) }
            else -> { e.showSoftInputOnFocus = false; imm.hideSoftInputFromWindow(e.windowToken, 0) }
        }
        onChange()
    }

    private fun pasteInto(e: EditText) {
        val clip = act.getSystemService(ClipboardManager::class.java).primaryClip ?: return
        val s = clip.takeIf { it.itemCount > 0 }?.getItemAt(0)?.coerceToText(act)?.toString() ?: return
        kb.edit { it.insert(s) }
    }

    companion object {
        /** Write a keyboard edit back into the EditText (only touches the text when it actually changed). */
        fun apply(e: EditText, v: TextFieldValue) {
            if (e.text.toString() != v.text) e.setText(v.text)
            val n = e.text.length
            e.setSelection(v.selection.start.coerceIn(0, n), v.selection.end.coerceIn(0, n))
        }
        @Suppress("unused") fun state(e: EditText) = EditState(e.text.toString(), e.selectionStart, e.selectionEnd)
    }
    @Suppress("unused") private fun v(): View = view
}
