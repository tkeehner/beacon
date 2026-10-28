package com.tyler.primelauncher.emitkb.ui

// Emit 0.1.47's in-app keyboard, ported for the launcher's Message Prime box. Only change: KbTarget.actionLabel/onAction.

import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.Column
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.semantics.role
import android.content.Context
import android.view.HapticFeedbackConstants
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.PointerId
import androidx.compose.ui.input.pointer.changedToDown
import androidx.compose.ui.input.pointer.changedToUp
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties
import com.tyler.primelauncher.emitkb.keyboard.*
import kotlinx.coroutines.*
import kotlin.random.Random

/** A field the keyboard is currently typing into. */
class KbTarget(
    val id: Any,
    val phone: Boolean,
    val singleLine: Boolean,
    val get: () -> TextFieldValue,
    val set: (TextFieldValue) -> Unit,
    /** Paste from the clipboard into this field (null = field doesn't support paste). */
    val paste: (() -> Unit)? = null,
    /** Launcher port: label + handler for the enter key on a single-line field (e.g. "send"). Null = Emit's "done". */
    val actionLabel: String? = null,
    val onAction: (() -> Unit)? = null,
)

/** App-wide keyboard state + offline word engine. Custom keyboard is used unless [useSystem]. */
class KbController {
    var target by mutableStateOf<KbTarget?>(null)
    var useSystem by mutableStateOf(false)
    val visible get() = target != null && !useSystem
    fun hide() { target = null; lastCorrection = null; emojiMode = false; emojiQuery = null }

    /** Engine, once the dictionary is ready (driven by Dict.state collected in MatrixKeyboard). */
    val engine: WordEngine? get() = (dictState as? DictState.Ready)?.engine
    var autocorrect by mutableStateOf(true)
    var autocap by mutableStateOf(true)
    var showSuggestions by mutableStateOf(true)
    /** Emoji panel open (replaces the letter keys). */
    var emojiMode by mutableStateOf(false)
    /** Non-null while searching emoji: letter keys type into this query instead of the field. */
    var emojiQuery by mutableStateOf<String?>(null)
    var lastCorrection: Correction? = null
    /** Bumped on every edit so suggestions recompute. */
    var version by mutableIntStateOf(0)
    private var prefs: KeyboardPrefs? = null
    private var appContext: Context? = null
    private val io = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var saveJob: Job? = null

    var dictState by mutableStateOf<DictState>(DictState.Loading)
        private set
    private var dictJob: Job? = null

    /** Single entry point for dictionary state (called by the Dict collector, and by tests). */
    fun onDictState(st: DictState) {
        val was = dictState
        dictState = st
        when {
            st is DictState.Ready && was !is DictState.Ready -> log("dict: state -> Ready observed by keyboard (${st.words} words)")
            st is DictState.Failed -> log("dict: state -> Failed observed by keyboard: ${st.msg}")
        }
    }

    /** The controller itself observes Dict.state (no Compose glue), so it can never miss Ready. */
    fun observe(flow: kotlinx.coroutines.flow.StateFlow<DictState>, scope: CoroutineScope) {
        if (dictJob?.isActive == true) return
        onDictState(flow.value)
        dictJob = scope.launch { flow.collect { onDictState(it) } }
    }

    /** Suggestions for the current field state (pure engine call; run off the main thread by the strip). */
    fun computeSlots(): Slots {
        val e = engine ?: return Slots(null, null, null)
        val s = state() ?: return Slots(null, null, null)
        if (!showSuggestions) return Slots(null, null, null)
        val word = AutoText.currentWord(s)
        val r = e.slots(AutoText.previousWord(s), word, autocorrect)
        log("suggest: word='$word' results=[${r.left}, ${r.center}, ${r.right}] ac=${r.autocorrect}")
        return r
    }

    fun init(c: Context, contactNames: () -> List<String>) {
        if (appContext == null) {
            appContext = c.applicationContext
            prefs = KeyboardPrefs(c).also { autocorrect = it.autocorrect; autocap = it.autocap; showSuggestions = it.suggestions }
        }
        Dict.ensureLoaded(c, contactNames)
        observe(Dict.state, CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate))
    }
    fun toggleAutocorrectTo(v: Boolean) { autocorrect = v; prefs?.autocorrect = v }
    fun toggleAutocapTo(v: Boolean) { autocap = v; prefs?.autocap = v }
    fun toggleSuggestionsTo(v: Boolean) { showSuggestions = v; prefs?.suggestions = v }

    fun state(): EditState? = target?.get()?.let { v ->
        val n = v.text.length
        EditState(v.text, v.selection.start.coerceIn(0, n), v.selection.end.coerceIn(0, n))
    }
    fun apply(s: EditState) { target?.set(TextFieldValue(s.text, TextRange(s.start))); version++ }

    fun edit(f: (EditState) -> EditState) { val s = state() ?: return; lastCorrection = null; apply(f(s)) }

    fun learn(word: String, prev: String?) {
        val c = appContext; val e = engine ?: return
        if (word.isEmpty() || target?.phone == true) return
        e.learn(word, prev)
        saveJob?.cancel(); saveJob = io.launch { delay(3000); if (c != null) UserDict.save(c, e) }
    }

    /** Separator key with autocorrect + learning. */
    fun separator(sep: String) {
        val s = state() ?: return
        val e = engine
        val word = AutoText.currentWord(s); val prev = AutoText.previousWord(s)
        val corrector: ((String) -> String?)? = if (autocorrect && e != null && target?.phone != true) ({ w -> e.autocorrect(w) }) else null
        if (e == null) log("autocorrect: skipped, dict not ready (${dictState.javaClass.simpleName})")
        val (ns, corr) = AutoText.separator(s, sep, corrector)
        if (corr != null) log("autocorrect: ${corr.original} -> ${corr.corrected}")
        lastCorrection = corr
        if (word.isNotEmpty()) learn(corr?.corrected ?: word, prev)
        apply(ns)
    }

    fun backspace() {
        val s = state() ?: return
        val (ns, reverted) = AutoText.backspace(s, lastCorrection)
        if (reverted) lastCorrection?.let { engine?.accept(it.original) }
        lastCorrection = null
        apply(ns)
    }

    fun pick(word: String) {
        val s = state() ?: return
        val w = word.removeSurrounding("\"")
        val prev = AutoText.previousWord(s)
        if (word.startsWith("\"")) engine?.accept(w)
        lastCorrection = null
        learn(w, prev)
        apply(AutoText.applySuggestion(s, w))
    }
}

private fun log(msg: String) { runCatching { android.util.Log.i("MatrixSMS", msg) } }

val LocalKb = staticCompositionLocalOf { KbController() }

private enum class Layer { LETTERS, NUM, SYM }

private val ROWS_LETTERS = listOf("qwertyuiop", "asdfghjkl", "zxcvbnm")
private val ROWS_NUM = listOf("1234567890", "-/:;()$&@\"", ".,?!'")
private val ROWS_SYM = listOf("[]{}#%^*+=", "_\\|~<>€£¥•", ".,?!'")
private val PAD = listOf(listOf("1", "2", "3"), listOf("4", "5", "6"), listOf("7", "8", "9"), listOf("+", "0", "#"))
private val SEPARATORS = setOf(".", ",", "!", "?", ";", ":")

/** Neon key color. Matrix ignores it and stays green. */
private enum class KeyTone { NORMAL, SECONDARY, ACCENT, PRIMARY }

/** One key's spec. [preview] = show the enlarged popup (character keys). */
private class KeySpec(
    val label: String, val weight: Float, val repeat: Boolean = false, val highlight: Boolean = false,
    val preview: Boolean = false, val onLong: (() -> Unit)? = null, val tone: KeyTone = KeyTone.NORMAL,
    val action: () -> Unit,
)

@Composable
fun MatrixKeyboard(kb: KbController) {
    // (Root cause of 0.1.13 bug: the collected state was only read inside SideEffect, so this composable never
    // subscribed to it and never pushed Ready to the controller. The controller now observes Dict.state itself.)
    CompositionLocalProvider(LocalAccent provides ChatPalette.DEFAULT) {
        AnimatedVisibility(kb.visible, enter = slideInVertically(tween(140)) { it }, exit = slideOutVertically(tween(110)) { it }) {
            val jitter = remember { Animatable(0f) }
            val glitchOk = glitchEnabled() && !rememberReduceMotion()
            LaunchedEffect(Unit) {
                if (!glitchOk) return@LaunchedEffect
                repeat(5) { jitter.snapTo((Random.nextFloat() - 0.5f) * 18f); delay(35) }; jitter.snapTo(0f)
            }
            var settings by remember { mutableStateOf(false) }
            val neonKeys = LocalTheme.current.keyStyle != KeyStyle.MATRIX
            Box(Modifier.fillMaxWidth().background(Black).graphicsLayer { translationX = jitter.value }) {
                CodeRain(Modifier.matchParentSize(), alpha = 0.06f)
                Column(Modifier.fillMaxWidth().padding(horizontal = 1.dp, vertical = 2.dp)) {
                    if (neonKeys) Box(Modifier.fillMaxWidth().height(2.dp).background(
                        Brush.horizontalGradient(listOf(Color(LocalTheme.current.palette.accent3), Pal.Primary, Pal.Cyan))))
                    else Box(Modifier.fillMaxWidth().height(1.dp).background(FaintGreen))
                    if (kb.target?.phone == true) DialPad(kb)
                    else if (kb.emojiMode && kb.emojiQuery == null) EmojiPanel(kb)
                    else {
                        if (kb.emojiQuery != null) EmojiSearchStrip(kb)
                        else if (settings) SettingsRow(kb) { settings = false } else SuggestionStrip(kb) { settings = true }
                        TextKeys(kb)
                    }
                }
            }
        }
    }
}

@Composable
private fun SuggestionStrip(kb: KbController, onGear: () -> Unit) {
    // Read the global state directly too, so the strip recovers even if it was Ready at first composition.
    val global by Dict.state.collectAsState()
    LaunchedEffect(global) { kb.onDictState(global) }
    val e = kb.engine ?: (global as? DictState.Ready)?.engine
    val v = kb.version
    val slots by produceState(Slots(null, null, null), e, v, kb.autocorrect, kb.showSuggestions, kb.target) {
        value = if (e == null) Slots(null, null, null)
        else try { withContext(Dispatchers.Default) { kb.computeSlots() } }
        catch (c: kotlinx.coroutines.CancellationException) { throw c }
        catch (t: Throwable) { android.util.Log.w("MatrixSMS", "suggest: failed", t); Slots(null, null, null) }
    }
    val neon = LocalTheme.current.keyStyle != KeyStyle.MATRIX
    Row(Modifier.fillMaxWidth().height(40.dp), verticalAlignment = Alignment.CenterVertically) {
        listOf(slots.left, slots.center, slots.right).forEachIndexed { i, w ->
            val center = i == 1
            // Matrix: the border is the whole third of the strip. Neon: a chip around the word.
            Box(Modifier.weight(1f).fillMaxHeight().clickable(enabled = w != null) { w?.let(kb::pick) }
                .then(if (!neon && center && w != null) Modifier.border(1.dp, if (slots.autocorrect != null) Green else FaintGreen, Pal.R8) else Modifier),
                contentAlignment = Alignment.Center) {
                val label = w ?: if (e == null && center) (if (kb.dictState is DictState.Failed) "> dict offline" else "> loading dict...") else ""
                if (neon && center && w != null) {
                    Box(Modifier.border(1.dp, Pal.Cyan, CutCornerShape(6.dp, 6.dp)).padding(horizontal = 12.dp, vertical = 4.dp)) {
                        Text(label, color = Pal.Cyan, fontFamily = KeyFont, fontSize = 16.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                } else {
                    Text(label, color = if (center) Green else DimGreen, fontFamily = UiFont,
                        fontSize = if (center) 16.sp else 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
            if (i < 2) Box(Modifier.width(1.dp).height(22.dp).background(if (neon) Pal.Line2 else FaintGreen))
        }
        kb.target?.paste?.let { p ->
            Box(Modifier.width(44.dp).fillMaxHeight().tap { p() }, contentAlignment = Alignment.Center) {
                Text("[⎘]", color = Green, fontFamily = UiFont, fontSize = TypeScale.Label)
            }
        }
        Box(Modifier.width(36.dp).fillMaxHeight().tap { onGear() }, contentAlignment = Alignment.Center) {
            Text("⚙", color = DimGreen, fontSize = TypeScale.Title)
        }
    }
}

@Composable
private fun SettingsRow(kb: KbController, onClose: () -> Unit) {
    // Raised sheet in the settings.png style: label + sublabel + pill per row, filled OK.
    Column(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 6.dp).panel(Pal.S2c, Pal.Line2, Pal.R12).padding(6.dp)) {
        Text("KEYBOARD", color = FaintGreen, fontFamily = UiFont, fontSize = TypeScale.Micro, modifier = Modifier.padding(start = 8.dp, top = 4.dp, bottom = 4.dp))
        @Composable fun T(label: String, sub: String, on: Boolean, f: () -> Unit) = Row(Modifier.fillMaxWidth().padding(vertical = 2.dp)
            .panel(Pal.S1c, Pal.Line, Pal.R10).semantics(mergeDescendants = true) { role = androidx.compose.ui.semantics.Role.Switch; stateDescription = if (on) "on" else "off" }
            .tap { f() }.padding(horizontal = 12.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(label, color = if (on) Pal.Body else DimGreen, fontFamily = UiFont, fontSize = TypeScale.Label, maxLines = 1)
                Text(sub, color = FaintGreen, fontFamily = UiFont, fontSize = TypeScale.Meta)
            }
            Spacer(Modifier.width(10.dp)); Pill(on)
        }
        T("autocorrect", "fix typos when you hit space", kb.autocorrect) { kb.toggleAutocorrectTo(!kb.autocorrect) }
        T("auto caps", "capitalize sentence starts", kb.autocap) { kb.toggleAutocapTo(!kb.autocap) }
        T("suggestions", "show the word strip", kb.showSuggestions) { kb.toggleSuggestionsTo(!kb.showSuggestions) }
        Box(Modifier.fillMaxWidth().padding(top = 6.dp).heightIn(min = Ui.Touch).background(Pal.Primary, Pal.R8).tap { onClose() }, contentAlignment = Alignment.Center) {
            Text("[ OK ]", color = Pal.OnPrimary, fontFamily = DisplayFont, fontWeight = androidx.compose.ui.text.font.FontWeight.Bold, fontSize = TypeScale.Label) }
    }
}

@Composable
private fun TextKeys(kb: KbController) {
    var layer by remember { mutableStateOf(Layer.LETTERS) }
    var shift by remember { mutableStateOf(ShiftMode.OFF) }
    var lastShiftTap by remember { mutableLongStateOf(0L) }
    var manualShift by remember { mutableStateOf(false) }
    // Auto-capitalize at sentence starts (unless caps lock / user toggled shift).
    LaunchedEffect(kb.version, kb.target, kb.autocap) {
        if (shift == ShiftMode.LOCK || manualShift) return@LaunchedEffect
        val s = kb.state() ?: return@LaunchedEffect
        shift = if (kb.autocap && AutoText.shouldCapitalize(s)) ShiftMode.ONCE else ShiftMode.OFF
    }
    fun type(s: String) {
        kb.emojiQuery?.let { kb.emojiQuery = it + s.lowercase(); return }
        if (s in SEPARATORS) kb.separator(s) else kb.edit { it.insert(s) }
        if (layer == Layer.LETTERS) { shift = afterChar(shift); manualShift = false }
    }
    val rows = when (layer) { Layer.LETTERS -> ROWS_LETTERS; Layer.NUM -> ROWS_NUM; Layer.SYM -> ROWS_SYM }
    rows.forEachIndexed { i, row ->
        val keys = ArrayList<KeySpec>()
        if (i == 2) keys += when (layer) {
            Layer.LETTERS -> KeySpec(when (shift) { ShiftMode.OFF -> "⇧"; ShiftMode.ONCE -> "⬆"; ShiftMode.LOCK -> "⇪" }, 1.4f, highlight = shift != ShiftMode.OFF, tone = KeyTone.SECONDARY) {
                val now = System.currentTimeMillis(); shift = nextShift(shift, now, lastShiftTap); lastShiftTap = now; manualShift = true
            }
            Layer.NUM -> KeySpec("#+=", 1.4f, tone = KeyTone.SECONDARY) { layer = Layer.SYM }
            Layer.SYM -> KeySpec("123", 1.4f, tone = KeyTone.SECONDARY) { layer = Layer.NUM }
        }
        row.forEach { ch ->
            val label = if (layer == Layer.LETTERS && shift != ShiftMode.OFF) ch.uppercaseChar().toString() else ch.toString()
            keys += KeySpec(label, 1f, preview = true) { type(label) }
        }
        if (i == 2) keys += KeySpec("⌫", 1.4f, repeat = true, tone = KeyTone.ACCENT) { kb.emojiQuery?.let { kb.emojiQuery = it.dropLast(1) } ?: kb.backspace() }
        // Row 2 (9 keys) is inset by half a key on each side; the inset still maps to the nearest key.
        KeyRow(keys, inset = if (i == 1 && layer == Layer.LETTERS) 0.5f else 0f)
    }
    KeyRow(listOf(
        KeySpec(if (layer == Layer.LETTERS) "123" else "ABC", 1.3f, tone = KeyTone.SECONDARY) { layer = if (layer == Layer.LETTERS) Layer.NUM else Layer.LETTERS },
        KeySpec("⌨", 1f, onLong = { kb.toggleAutocorrectTo(!kb.autocorrect) }) { kb.useSystem = true },
        KeySpec("☺", 1f) { kb.emojiQuery = null; kb.emojiMode = true },
        KeySpec(",", 1f, preview = true) { type(",") },
        KeySpec("space", 3.2f) { kb.emojiQuery?.let { kb.emojiQuery = "$it " } ?: run { kb.separator(" "); shift = afterChar(shift) } },
        KeySpec(".", 1f, preview = true) { type(".") },
        KeySpec(kb.target?.actionLabel ?: if (kb.target?.singleLine == true) "done" else "⏎", 1.5f, tone = KeyTone.PRIMARY) {
            val act = kb.target?.onAction
            if (act != null) act() else if (kb.target?.singleLine == true) kb.hide() else kb.separator("\n")
        },
    ))
}

@Composable
private fun DialPad(kb: KbController) {
    PAD.forEach { row -> KeyRow(row.map { d -> KeySpec(d, 1f, preview = true) { kb.edit { it.insert(d) } } }, tall = true) }
    KeyRow(listOf(
        KeySpec("⌨", 1f) { kb.useSystem = true },
        KeySpec("⌫", 1f, repeat = true, tone = KeyTone.ACCENT) { kb.edit { it.backspace() } },
        KeySpec("done", 1f, tone = KeyTone.PRIMARY) { kb.hide() },
    ), tall = true)
}

/**
 * A row of keys with ONE touch handler: every x position (including gaps and insets) maps to the nearest key,
 * so there are no dead zones. Fires on press-down for zero lag; supports multi-touch rollover and auto-repeat.
 */
@Composable
private fun KeyRow(keys: List<KeySpec>, inset: Float = 0f, tall: Boolean = false) {
    val view = LocalView.current
    val scope = rememberCoroutineScope()
    val pressed = remember { mutableStateMapOf<PointerId, Int>() }
    val latest by rememberUpdatedState(keys)
    val total = keys.sumOf { it.weight.toDouble() }.toFloat() + 2 * inset
    val h = if (tall) 60.dp else 55.dp
    Row(Modifier.fillMaxWidth().height(h).pointerInput(keys.size, inset) {
        val repeats = HashMap<PointerId, Job>()
        val longs = HashMap<PointerId, Job>()
        awaitPointerEventScope {
            while (true) {
                val ev = awaitPointerEvent()
                ev.changes.forEach { c ->
                    if (c.changedToDown()) {
                        val ks = latest
                        val unit = size.width / total
                        var x = c.position.x / unit - inset
                        var idx = ks.lastIndex
                        for ((i, k) in ks.withIndex()) { if (x < k.weight) { idx = i; break }; x -= k.weight }
                        idx = idx.coerceIn(0, ks.lastIndex)
                        val k = ks[idx]
                        pressed[c.id] = idx
                        view.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                        if (k.onLong != null) {
                            longs[c.id] = scope.launch { delay(450); k.onLong.invoke(); longs.remove(c.id) }
                        } else {
                            k.action()
                            if (k.repeat) repeats[c.id] = scope.launch { delay(400); while (isActive) { k.action(); delay(55) } }
                        }
                        c.consume()
                    } else if (c.changedToUp() || !c.pressed) {
                        val idx = pressed.remove(c.id)
                        repeats.remove(c.id)?.cancel()
                        longs.remove(c.id)?.let { j -> if (j.isActive) { j.cancel(); idx?.let { latest.getOrNull(it)?.action?.invoke() } } }
                        c.consume()
                    }
                }
            }
        }
    }) {
        if (inset > 0) Spacer(Modifier.weight(inset))
        keys.forEachIndexed { i, k -> KeyFace(k, i in pressed.values, h) }
        if (inset > 0) Spacer(Modifier.weight(inset))
    }
}

@Composable
private fun RowScope.KeyFace(k: KeySpec, down: Boolean, h: androidx.compose.ui.unit.Dp) {
    when (LocalTheme.current.keyStyle) {
        KeyStyle.NEON -> NeonKeyFace(k, down, h)
        KeyStyle.ARMOR -> ArmorKeyFace(k, down, h)
        KeyStyle.MATRIX -> MatrixKeyFace(k, down, h)
    }
}

@Composable
private fun RowScope.MatrixKeyFace(k: KeySpec, down: Boolean, h: androidx.compose.ui.unit.Dp) {
    val green = Green
    Box(
        Modifier.weight(k.weight).fillMaxHeight().padding(horizontal = 1.5.dp, vertical = 2.5.dp)
            .border(1.dp, if (down || k.highlight) green else DimGreen, Pal.R8)
            .background(if (down) green.copy(alpha = 0.35f) else if (k.highlight) green.copy(alpha = 0.12f) else Black),
        contentAlignment = Alignment.Center,
    ) {
        Text(k.label, color = if (down) Pal.KeyDown else green, fontFamily = KeyFont, fontSize = if (k.label.length > 2) 15.sp else 22.sp)
        if (down && k.preview) {
            val lift = with(androidx.compose.ui.platform.LocalDensity.current) { (h + 8.dp).roundToPx() }
            Popup(alignment = Alignment.Center, offset = IntOffset(0, -lift), properties = PopupProperties(focusable = false, clippingEnabled = false)) {
                Box(Modifier.testTag("key-preview").size(width = 52.dp, height = 62.dp).background(Black).border(2.dp, green), contentAlignment = Alignment.Center) {
                    Text(k.label, color = green, fontFamily = KeyFont, fontSize = 32.sp)
                }
            }
        }
    }
}

@Composable
private fun RowScope.NeonKeyFace(k: KeySpec, down: Boolean, h: androidx.compose.ui.unit.Dp) {
    val shape = CutCornerShape(6.dp, 6.dp)
    val accent = when (k.tone) {
        KeyTone.SECONDARY -> Pal.Cyan
        KeyTone.ACCENT -> Color(LocalTheme.current.palette.accent3)
        KeyTone.PRIMARY -> Pal.Primary
        KeyTone.NORMAL -> null
    }
    val glow = down && keyGlowEnabled()
    val border = when {
        down || k.highlight -> Pal.Primary
        accent != null -> accent.copy(alpha = 0.6f)
        else -> Pal.Line2
    }
    val label = k.label
    Box(
        Modifier.weight(k.weight).fillMaxHeight().padding(horizontal = 1.5.dp, vertical = 2.5.dp)
            .then(if (glow) Modifier.shadow(8.dp, shape, ambientColor = Pal.Primary, spotColor = Pal.Primary) else Modifier)
            .background(if (down) Pal.Primary else Pal.S2c, shape)
            .border(1.dp, border, shape),
        contentAlignment = Alignment.Center,
    ) {
        Text(label, color = if (down) Pal.OnPrimary else (accent ?: Pal.Body), fontFamily = KeyFont,
            fontWeight = if (down || k.tone != KeyTone.NORMAL) androidx.compose.ui.text.font.FontWeight.SemiBold else androidx.compose.ui.text.font.FontWeight.Medium,
            fontSize = if (k.label.length > 2) 15.sp else 22.sp)
        if (down && k.preview) {
            val lift = with(androidx.compose.ui.platform.LocalDensity.current) { (h + 8.dp).roundToPx() }
            val pop = CutCornerShape(9.dp, 9.dp)
            Popup(alignment = Alignment.Center, offset = IntOffset(0, -lift), properties = PopupProperties(focusable = false, clippingEnabled = false)) {
                Box(Modifier.testTag("key-preview").size(width = 56.dp, height = 66.dp)
                    .then(if (glow) Modifier.shadow(10.dp, pop, ambientColor = Pal.Primary, spotColor = Pal.Primary) else Modifier)
                    .background(Black, pop).border(1.dp, Pal.Primary, pop), contentAlignment = Alignment.Center) {
                    if (glow) {
                        Text(label, color = Color(LocalTheme.current.palette.accent3), fontFamily = KeyFont, fontWeight = androidx.compose.ui.text.font.FontWeight.Bold,
                            fontSize = 34.sp, modifier = Modifier.offset(x = (-2).dp))
                        Text(label, color = Pal.Cyan, fontFamily = KeyFont, fontWeight = androidx.compose.ui.text.font.FontWeight.Bold,
                            fontSize = 34.sp, modifier = Modifier.offset(x = 2.dp))
                    }
                    Text(label, color = Pal.Primary, fontFamily = KeyFont, fontWeight = androidx.compose.ui.text.font.FontWeight.Bold, fontSize = 34.sp)
                }
            }
        }
    }
}


/**
 * ODST armor plate: chamfered top-left/bottom-right, a steel gradient with a top highlight and a bottom shadow line,
 * amber accent keys, and a VISR edge + glow while pressed. The plate sinks 2dp on press.
 */
@Composable
private fun RowScope.ArmorKeyFace(k: KeySpec, down: Boolean, h: androidx.compose.ui.unit.Dp) {
    val shape = CutCornerShape(5.dp, 6.dp)
    val visr = Color(LocalTheme.current.palette.accent3)
    val accentKey = k.tone != KeyTone.NORMAL
    val glow = down && keyGlowEnabled()
    val top = if (accentKey) Color(0xFF5A4216) else Color(0xFF323A45)
    val bottom = if (accentKey) Color(0xFF2A1D08) else Color(0xFF151A21)
    val label = k.label
    Box(
        Modifier.weight(k.weight).fillMaxHeight().padding(horizontal = 1.5.dp, vertical = 2.5.dp)
            .offset(y = if (down) 2.dp else 0.dp)
            .then(if (glow) Modifier.shadow(8.dp, shape, ambientColor = visr, spotColor = visr) else Modifier)
            .background(Brush.verticalGradient(listOf(top, bottom)), shape)
            .border(if (down) 1.6.dp else 1.dp, if (down) visr else if (k.highlight) Pal.Primary else Color(0xFF4A5563), shape)
            .drawWithContent { drawContent()
                val y = 2.5.dp.toPx(); drawLine(Color.White.copy(alpha = 0.16f), androidx.compose.ui.geometry.Offset(6.dp.toPx(), y), androidx.compose.ui.geometry.Offset(size.width - 2.dp.toPx(), y), 1.dp.toPx())
                drawRect(Color.Black.copy(alpha = 0.35f), androidx.compose.ui.geometry.Offset(3.dp.toPx(), size.height - 4.dp.toPx()), androidx.compose.ui.geometry.Size((size.width - 8.dp.toPx()).coerceAtLeast(0f), 1.5.dp.toPx())) },
        contentAlignment = Alignment.Center,
    ) {
        Text(label, color = if (accentKey) Pal.Primary else Color(0xFFE6EBF2), fontFamily = KeyFont,
            fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold, fontSize = if (k.label.length > 2) 14.sp else 21.sp)
        if (down && k.preview) {
            val lift = with(androidx.compose.ui.platform.LocalDensity.current) { (h + 8.dp).roundToPx() }
            val pop = CutCornerShape(7.dp, 7.dp)
            Popup(alignment = Alignment.Center, offset = IntOffset(0, -lift), properties = PopupProperties(focusable = false, clippingEnabled = false)) {
                Box(Modifier.testTag("key-preview").size(width = 54.dp, height = 64.dp)
                    .background(Color(0xFF1A222C), pop).border(1.dp, visr, pop), contentAlignment = Alignment.Center) {
                    Text(label, color = Color.White, fontFamily = KeyFont, fontWeight = androidx.compose.ui.text.font.FontWeight.Bold, fontSize = 32.sp)
                }
            }
        }
    }
}
