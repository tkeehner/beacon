package com.tyler.primelauncher.emitkb.ui

import android.content.Context
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

data class Emoji(val cat: String, val e: String, val name: String, val tones: List<String>)

/** Emoji 14.0 set (Android 13 renders all of it in color), generated from Unicode emoji-test.txt into assets/emoji.tsv. */
object EmojiData {
    val CATS = listOf("recent" to "🕘", "smileys" to "😀", "people" to "👋", "nature" to "🐻", "food" to "🍔",
        "activities" to "⚽", "travel" to "🚗", "objects" to "💡", "symbols" to "❤️", "flags" to "🏁")
    @Volatile private var all: List<Emoji>? = null
    fun parse(lines: Sequence<String>): List<Emoji> = lines.mapNotNull { l ->
        val p = l.split('\t'); if (p.size < 3) null else Emoji(p[0], p[1], p[2], p.getOrNull(3)?.split(' ')?.filter { it.isNotBlank() } ?: emptyList())
    }.toList()
    fun load(c: Context): List<Emoji> = all ?: c.assets.open("emoji.tsv").bufferedReader().useLines { parse(it) }.also { all = it }
    fun search(list: List<Emoji>, q: String): List<Emoji> {
        val w = q.trim().lowercase(); if (w.isEmpty()) return emptyList()
        return list.filter { it.name.contains(w) }.sortedBy { if (it.name.startsWith(w) || it.name.contains(" $w")) 0 else 1 }.take(60)
    }
}

/** Recently used emoji, newest first, persisted in SharedPreferences. */
class EmojiRecents(c: Context) {
    private val p = c.getSharedPreferences("emoji_recents", Context.MODE_PRIVATE)
    fun get(): List<String> = p.getString("r", "")!!.split('\u0001').filter { it.isNotEmpty() }
    fun add(e: String) = p.edit().putString("r", push(get(), e).joinToString("\u0001")).apply()
    companion object { fun push(list: List<String>, e: String, max: Int = 32) = (listOf(e) + list.filter { it != e }).take(max) }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun EmojiCell(e: Emoji?, glyph: String, size: Int, onPick: (String) -> Unit) {
    var tones by remember { mutableStateOf(false) }
    Box(Modifier.size(size.dp).combinedClickable(onClick = { onPick(glyph) },
        onLongClick = { if (e != null && e.tones.isNotEmpty()) tones = true else onPick(glyph) }), contentAlignment = Alignment.Center) {
        Text(glyph, fontSize = (size * 0.62f).sp)
        if (e != null && e.tones.isNotEmpty()) Box(Modifier.align(Alignment.BottomEnd).size(4.dp).background(DimGreen))
        if (tones && e != null) Popup(alignment = Alignment.TopCenter, offset = androidx.compose.ui.unit.IntOffset(0, -140),
            onDismissRequest = { tones = false }, properties = PopupProperties(focusable = true)) {
            Row(Modifier.panel(Pal.S2c, Pal.Line2, Pal.R12).padding(4.dp)) {
                (listOf(e.e) + e.tones).forEach { t ->
                    Box(Modifier.size(44.dp).tap { tones = false; onPick(t) }, contentAlignment = Alignment.Center) { Text(t, fontSize = 26.sp) }
                }
            }
        }
    }
}

/** Replaces the letter keys while open. Same footprint as the keys + suggestion strip. */
@Composable
fun EmojiPanel(kb: KbController) {
    val ctx = LocalContext.current
    val recents = remember { EmojiRecents(ctx) }
    var recent by remember { mutableStateOf(recents.get()) }
    val all by produceState(emptyList<Emoji>()) { value = withContext(Dispatchers.IO) { EmojiData.load(ctx) } }
    val byGlyph = remember(all) { all.associateBy { it.e } }
    var cat by remember { mutableStateOf(if (recent.isEmpty()) "smileys" else "recent") }
    val grid = rememberLazyGridState()
    LaunchedEffect(cat) { grid.scrollToItem(0) }
    fun pick(g: String) { kb.edit { it.insert(g) }; recents.add(g); recent = recents.get() }
    Column(Modifier.fillMaxWidth()) {
        Row(Modifier.fillMaxWidth().height(40.dp).horizontalScroll(rememberScrollState()), verticalAlignment = Alignment.CenterVertically) {
            EmojiData.CATS.forEach { (id, icon) ->
                Box(Modifier.size(40.dp).then(if (cat == id) Modifier.border(1.dp, Green, Pal.R8) else Modifier).tap { cat = id },
                    contentAlignment = Alignment.Center) { Text(icon, fontSize = 20.sp) }
            }
        }
        Box(Modifier.fillMaxWidth().height(1.dp).background(FaintGreen))
        val list: List<Pair<Emoji?, String>> = if (cat == "recent") recent.map { byGlyph[it] to it }
            else all.filter { it.cat == cat }.map { it to it.e }
        Box(Modifier.fillMaxWidth().height(170.dp)) {
            if (list.isEmpty()) Text(if (all.isEmpty()) "> loading emoji..." else "> no recent emoji yet", color = DimGreen, fontFamily = UiFont,
                modifier = Modifier.align(Alignment.Center))
            LazyVerticalGrid(GridCells.Adaptive(42.dp), state = grid, modifier = Modifier.fillMaxSize()) {
                items(list, key = { it.second }) { (e, g) -> EmojiCell(e, g, 42) { pick(it) } }
            }
        }
        Row(Modifier.fillMaxWidth().height(50.dp), verticalAlignment = Alignment.CenterVertically) {
            @Composable fun K(label: String, w: Float, f: () -> Unit) = Box(Modifier.weight(w).fillMaxHeight().padding(2.dp)
                .border(1.dp, DimGreen, Pal.R8).tap { f() }, contentAlignment = Alignment.Center) { Text(label, color = Green, fontFamily = UiFont, fontSize = TypeScale.Body) }
            K("ABC", 1.4f) { kb.emojiMode = false }
            K("🔍", 1f) { kb.emojiQuery = "" }
            K("space", 3f) { kb.separator(" ") }
            K("⌫", 1.4f) { kb.backspace() }
        }
    }
}

/** Search mode: results strip on top, the normal letter keys below type into the query. */
@Composable
fun EmojiSearchStrip(kb: KbController) {
    val ctx = LocalContext.current
    val recents = remember { EmojiRecents(ctx) }
    val all by produceState(emptyList<Emoji>()) { value = withContext(Dispatchers.IO) { EmojiData.load(ctx) } }
    val q = kb.emojiQuery ?: ""
    val hits = remember(all, q) { EmojiData.search(all, q) }
    Column {
        Row(Modifier.fillMaxWidth().height(30.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("search: $q▌", color = Green, fontFamily = UiFont, fontSize = TypeScale.Label, modifier = Modifier.weight(1f).padding(horizontal = 8.dp))
            Text("[x]", color = DimGreen, fontFamily = UiFont, fontSize = TypeScale.Label, modifier = Modifier.tap { kb.emojiQuery = null }.padding(8.dp))
        }
        LazyRow(Modifier.fillMaxWidth().height(44.dp)) {
            items(hits, key = { it.e }) { e -> EmojiCell(e, e.e, 44) { g -> kb.edit { it.insert(g) }; recents.add(g) } }
        }
    }
}
