package com.kinetica.keyboard.ui

import android.content.Context
import android.graphics.Paint
import android.graphics.drawable.GradientDrawable
import android.util.Log
import android.util.TypedValue
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.View
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.kinetica.keyboard.R
import java.io.IOException
import org.json.JSONObject

/**
 * Emoji picker: category tabs over a scrollable grid, loaded from the bundled
 * assets/emoji_data.json (no network, ever), with a search pane and skin tones. Swapped in
 * place of the KeyboardView by InputContainerView while open.
 *
 * [onEmoji] gets the emoji as it goes into the field, toned; [onTones] gets the user's new
 * tone choices to store.
 */
class EmojiPickerView(
    context: Context,
    private val onEmoji: (String) -> Unit,
    private val onBackspace: () -> Unit,
    private val onClose: () -> Unit,
    private val onTones: (EmojiTone.Choices) -> Unit = {},
) : LinearLayout(context) {

    /** One emoji with the metadata the asset carries; search reads [name] and [keywords]. */
    private class Emoji(val ch: String, val name: String, val keywords: List<String>)

    private class Category(val name: String, val icon: String, val emoji: List<Emoji>)

    private val categories: List<Category>
    private val tabsScroll = HorizontalScrollView(context)
    private val gridScroll = ScrollView(context)
    private val bottom = LinearLayout(context).apply { orientation = HORIZONTAL }
    private val grid = LinearLayout(context).apply { orientation = VERTICAL }
    private val density = resources.displayMetrics.density
    private val tabs = LinearLayout(context).apply { orientation = HORIZONTAL }
    private val tabViews = ArrayList<TextView>()
    private val footerViews = ArrayList<TextView>()
    private var shownCategory = 0

    /**
     * The row of one emoji in each tone, shown above the panel while choosing: from a long
     * press on an emoji, or for the default from the footer's hand. Null [toneTarget] is the
     * default.
     */
    private val toneStrip = LinearLayout(context).apply { orientation = HORIZONTAL }
    private var toneTarget: String? = null
    private var toneFooter: TextView? = null

    /** The search pane, in place of the tabs, grid and footer while [searching]. */
    private val searchPane = LinearLayout(context).apply { orientation = VERTICAL }
    private val queryView = TextView(context)
    private val resultsRow = LinearLayout(context).apply { orientation = HORIZONTAL }
    private val resultsScroll = HorizontalScrollView(context)
    private val searchKeys = ArrayList<TextView>()
    private val searchSpecialKeys = ArrayList<TextView>()
    private var query = StringBuilder()
    private var searching = false
    private var search: EmojiSearch? = null

    /**
     * Whether this device's font can draw [ch]. The asset carries emoji up to Unicode 15 and
     * the system font is fixed per OS version, so an undrawable emoji is left out, not shown
     * as a tofu box. One Paint is reused because this runs once per entry at load.
     *
     * Must stay above the `init` block: initializers run in declaration order, and below it
     * the Paint is still null when `init` calls [loadCategories], which kills the keyboard.
     */
    private val glyphPaint = Paint()

    private fun canDraw(ch: String): Boolean = glyphPaint.hasGlyph(ch)

    /** The asset's own entries by character, so a recorded emoji keeps its name. */
    private val byChar = HashMap<String, Emoji>()
    private var recentEntries: List<Emoji> = emptyList()

    /**
     * The emoji this user picks most, best first, from [EmojiRecents].
     *
     * Pushed when the panel opens, not after every tap, so the first tab never reorders under
     * a finger still tapping. The tab is absent until it has contents, so a fresh install sees
     * the plain panel.
     */
    var recents: List<String> = emptyList()
        set(value) {
            if (field == value) return
            field = value
            // Filtered like the asset is: a recent picked on another device, or before a
            // system update removed a font, must not come back as a tofu box. Stored plain,
            // so a recent shows in today's tone.
            recentEntries = value.map { EmojiTone.strip(it) }.distinct().filter { canDraw(it) }
                .map { byChar[it] ?: Emoji(it, "", emptyList()) }
            rebuildTabs()
            showCategory(shownCategory.coerceIn(0, (panels().size - 1).coerceAtLeast(0)))
        }

    /** The user's skin tones, pushed from the service's store; the grid redraws in them. */
    var tones: EmojiTone.Choices = EmojiTone.Choices()
        set(value) {
            field = value
            toneFooter?.text = shown(EmojiTone.SAMPLE)
            if (panels().isNotEmpty()) showCategory(shownCategory)
            if (searching) showResults()
        }

    /**
     * [plain] as the panel shows and sends it: in the user's tone when the device can draw
     * that, else plain. An older font may lack the toned forms of a newer emoji.
     */
    private fun shown(plain: String): String {
        val toned = tones.render(plain)
        return if (toned == plain || canDraw(toned)) toned else plain
    }

    /**
     * Resolved color roles, pushed from the service like [SuggestionBarView.theme] and
     * [KeyboardView.theme]. Everything the panel draws comes from here, so it follows a custom
     * hue and the light theme.
     */
    var theme: KeyboardTheme = KeyboardTheme.fromResources(context)
        set(value) {
            field = value
            applyTheme()
            invalidate()
        }

    init {
        orientation = VERTICAL
        categories = loadCategories()
        for (cat in categories) for (e in cat.emoji) byChar.putIfAbsent(e.ch, e)

        toneStrip.visibility = GONE
        addView(toneStrip, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))

        rebuildTabs()
        tabsScroll.isHorizontalScrollBarEnabled = false
        tabsScroll.addView(tabs)
        addView(tabsScroll, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))

        gridScroll.addView(grid)
        addView(gridScroll, LayoutParams(LayoutParams.MATCH_PARENT, 0, 1f))

        // TextViews, not Buttons: a platform Button takes its colours from the app theme and
        // came out light inside a dark keyboard. These are painted from the palette.
        bottom.addView(
            footerControl(context.getString(R.string.emoji_back_to_letters)) { onClose() },
            LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f),
        )
        toneFooter = footerControl(shown(EmojiTone.SAMPLE)) { showToneStrip(null) }.apply {
            contentDescription = context.getString(R.string.emoji_skin_tone)
        }
        bottom.addView(toneFooter, LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f))
        bottom.addView(
            footerControl("\u232b") { onBackspace() },
            LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f),
        )
        addView(bottom, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))

        buildSearchPane()
        searchPane.visibility = GONE
        addView(searchPane, LayoutParams(LayoutParams.MATCH_PARENT, 0, 1f))

        applyTheme()
        if (panels().isNotEmpty()) showCategory(0)
    }

    /** Back to the tabs, with no search or tone row up; the service calls it on each open. */
    fun reset() {
        hideToneStrip()
        if (searching) closeSearch()
    }

    /**
     * What the tab strip addresses: the frequently-used panel when it has contents, then the
     * asset's categories. Recomputed, not stored, so the tab index and the list always agree.
     */
    private fun panels(): List<Category> =
        if (recentEntries.isEmpty()) {
            categories
        } else {
            listOf(Category(context.getString(R.string.emoji_recent), RECENT_ICON, recentEntries)) + categories
        }

    private fun rebuildTabs() {
        tabs.removeAllViews()
        tabViews.clear()
        val searchTab = TextView(context).apply {
            text = SEARCH_ICON
            contentDescription = context.getString(R.string.emoji_search)
            textSize = 20f
            gravity = Gravity.CENTER
            setPadding(dp(12), dp(6), dp(12), dp(6))
            setTextColor(theme.keyText)
            setOnClickListener { openSearch() }
        }
        tabViews.add(searchTab)
        tabs.addView(searchTab)
        for ((i, cat) in panels().withIndex()) {
            val tab = TextView(context).apply {
                text = cat.icon
                contentDescription = cat.name
                textSize = 22f
                gravity = Gravity.CENTER
                setPadding(dp(10), dp(6), dp(10), dp(6))
                setTextColor(theme.keyText)
                setOnClickListener { showCategory(i) }
            }
            tabViews.add(tab)
            tabs.addView(tab)
        }
    }

    private fun footerControl(label: String, onTap: () -> Unit): TextView {
        val v = TextView(context).apply {
            text = label
            textSize = 16f
            gravity = Gravity.CENTER
            setPadding(dp(8), dp(10), dp(8), dp(10))
            isClickable = true
            setOnClickListener { onTap() }
        }
        footerViews.add(v)
        return v
    }

    /** Stains every surface the panel owns from the resolved roles. */
    private fun applyTheme() {
        setBackgroundColor(theme.background)
        for (t in tabViews) t.setTextColor(theme.keyText)
        for (f in footerViews) {
            f.setTextColor(theme.keyText)
            f.setBackgroundColor(theme.keySpecial)
        }
        toneStrip.setBackgroundColor(theme.popupBg)
        queryView.setTextColor(theme.keyText)
        queryView.setHintTextColor(theme.keyHint)
        queryView.background = rounded(theme.key)
        for (k in searchKeys) {
            k.setTextColor(theme.keyText)
            k.background = rounded(theme.key)
        }
        for (k in searchSpecialKeys) {
            k.setTextColor(theme.keyText)
            k.background = rounded(theme.keySpecial)
        }
        // The grid, results and tone row are rebuilt as they change, so their cells take the
        // colour there.
        if (panels().isNotEmpty()) showCategory(shownCategory)
        if (searching) showResults()
        toneTarget?.let { if (toneStrip.visibility == VISIBLE) showToneStrip(it) }
    }

    private fun rounded(color: Int) = GradientDrawable().apply {
        setColor(color)
        cornerRadius = 6f * density
    }

    private fun dp(v: Int): Int =
        TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v.toFloat(), resources.displayMetrics).toInt()

    /**
     * The shipped set, minus anything this device cannot draw.
     *
     * Never throws: an empty picker is an inconvenience, a dead IME leaves the user unable to
     * type, and a malformed asset must not take the service down.
     */
    private fun loadCategories(): List<Category> = try {
        parseCategories()
    } catch (e: RuntimeException) {
        Log.w(TAG, "emoji asset unusable", e)
        emptyList()
    } catch (e: IOException) {
        Log.w(TAG, "emoji asset unreadable", e)
        emptyList()
    }

    private fun parseCategories(): List<Category> {
        val json = context.assets.open("emoji_data.json").bufferedReader().use { it.readText() }
        val root = JSONObject(json).getJSONArray("categories")
        val out = ArrayList<Category>(root.length())
        for (i in 0 until root.length()) {
            val c = root.getJSONObject(i)
            val arr = c.getJSONArray("emoji")
            val entries = ArrayList<Emoji>(arr.length())
            for (j in 0 until arr.length()) {
                val e = c.getJSONArray("emoji").getJSONObject(j)
                val kwArr = e.optJSONArray("kw")
                val kw = if (kwArr == null) {
                    emptyList()
                } else {
                    List(kwArr.length()) { kwArr.getString(it) }
                }
                val ch = e.getString("ch")
                if (canDraw(ch)) entries.add(Emoji(ch, e.optString("name", ""), kw))
            }
            // A category with nothing drawable is dropped, not left as an empty tab, and its
            // icon must be drawable too or the tab itself is a tofu box.
            val icon = c.getString("icon")
            if (entries.isNotEmpty() && canDraw(icon)) {
                out.add(Category(c.getString("name"), icon, entries))
            }
        }
        return out
    }

    /**
     * One emoji's cell, in the user's tone. A tap sends it; a long press on one that takes a
     * tone opens the tone row for it, so a tone is picked once and kept for that emoji.
     */
    private fun emojiCell(plain: String, size: Float): TextView = TextView(context).apply {
        text = shown(plain)
        textSize = size
        gravity = Gravity.CENTER
        setPadding(0, dp(6), 0, dp(6))
        setTextColor(theme.keyText)
        setOnClickListener {
            hideToneStrip()
            onEmoji(shown(plain))
        }
        if (EmojiTone.supports(plain)) {
            setOnLongClickListener {
                performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
                showToneStrip(plain)
                true
            }
        }
    }

    /**
     * The tone row for [target], or for the default tone when it is null: the emoji (or a
     * raised hand) in no tone and each of the five, the current one marked. A tap on one
     * stores it, and for an emoji also sends it, as Gboard's long press does.
     */
    private fun showToneStrip(target: String?) {
        toneTarget = target
        toneStrip.removeAllViews()
        val plain = target ?: EmojiTone.SAMPLE
        val current = if (target == null) tones.default else tones.toneFor(plain)
        for (tone in EmojiTone.ALL) {
            val variant = EmojiTone.apply(plain, tone)
            if (!canDraw(variant)) continue
            toneStrip.addView(
                TextView(context).apply {
                    text = variant
                    textSize = 26f
                    gravity = Gravity.CENTER
                    setPadding(0, dp(8), 0, dp(8))
                    if (tone == current) background = rounded(theme.keyPressed)
                    setOnClickListener { pickTone(target, tone) }
                },
                LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f),
            )
        }
        toneStrip.addView(
            TextView(context).apply {
                text = "\u2715"
                contentDescription = context.getString(android.R.string.cancel)
                textSize = 18f
                gravity = Gravity.CENTER
                setPadding(0, dp(8), 0, dp(8))
                setTextColor(theme.keyText)
                setOnClickListener { hideToneStrip() }
            },
            LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f),
        )
        toneStrip.setBackgroundColor(theme.popupBg)
        toneStrip.visibility = VISIBLE
        if (!searching) tabsScroll.visibility = GONE
    }

    private fun hideToneStrip() {
        if (toneStrip.visibility != VISIBLE) return
        toneStrip.visibility = GONE
        toneTarget = null
        if (!searching) tabsScroll.visibility = VISIBLE
    }

    private fun pickTone(target: String?, tone: Int) {
        hideToneStrip()
        val next = if (target == null) tones.withDefault(tone) else tones.withEmoji(target, tone)
        tones = next
        onTones(next)
        if (target != null) onEmoji(shown(target))
    }

    // ------------------------------------------------------------------ search

    /**
     * The search pane: the query, one scrolling row of results, and a tap-only letter board
     * that types into the query, never into the field. The swipe keyboard is gone while the
     * panel is up, and sending its words here would mean a second decoder path.
     */
    private fun buildSearchPane() {
        queryView.apply {
            hint = context.getString(R.string.emoji_search_hint)
            textSize = 16f
            gravity = Gravity.CENTER_VERTICAL
            setSingleLine()
            setPadding(dp(12), dp(8), dp(12), dp(8))
        }
        val queryRow = LinearLayout(context).apply { orientation = HORIZONTAL }
        queryRow.addView(
            searchKey("\u2190", special = true) { closeSearch() }
                .apply { contentDescription = context.getString(R.string.emoji_search_close) },
            LayoutParams(dp(52), LayoutParams.MATCH_PARENT).apply { setMargins(dp(3), dp(3), dp(3), dp(3)) },
        )
        queryRow.addView(
            queryView,
            LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f).apply { setMargins(dp(3), dp(3), dp(3), dp(3)) },
        )
        searchPane.addView(queryRow, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))

        resultsScroll.isHorizontalScrollBarEnabled = false
        // Held at a cell's height while empty, so the letters do not jump when results come.
        resultsScroll.minimumHeight = dp(48)
        resultsScroll.addView(resultsRow)
        searchPane.addView(resultsScroll, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))

        for ((r, letters) in SEARCH_ROWS.withIndex()) {
            val row = LinearLayout(context).apply {
                orientation = HORIZONTAL
                gravity = Gravity.CENTER_HORIZONTAL
            }
            // The middle row is a key short, so it is inset half a key each side, like a board.
            if (r == 1) row.addView(View(context), LayoutParams(0, LayoutParams.MATCH_PARENT, 0.5f))
            for (c in letters) {
                row.addView(searchKey(c.toString()) { typeQuery(c) }, keyParams(1f))
            }
            if (r == 1) row.addView(View(context), LayoutParams(0, LayoutParams.MATCH_PARENT, 0.5f))
            if (r == 2) row.addView(searchKey("\u232b", special = true) { backspaceQuery() }, keyParams(1.5f))
            searchPane.addView(row, LayoutParams(LayoutParams.MATCH_PARENT, 0, 1f))
        }
        val last = LinearLayout(context).apply { orientation = HORIZONTAL }
        last.addView(
            searchKey(context.getString(R.string.emoji_back_to_letters), special = true) { onClose() },
            keyParams(1.5f),
        )
        last.addView(searchKey(" ") { typeQuery(' ') }, keyParams(6.5f))
        searchPane.addView(last, LayoutParams(LayoutParams.MATCH_PARENT, 0, 1f))
    }

    private fun keyParams(weight: Float) =
        LayoutParams(0, LayoutParams.MATCH_PARENT, weight).apply { setMargins(dp(2), dp(3), dp(2), dp(3)) }

    private fun searchKey(label: String, special: Boolean = false, onTap: () -> Unit): TextView {
        val v = TextView(context).apply {
            text = label
            textSize = 18f
            gravity = Gravity.CENTER
            isClickable = true
            setOnClickListener {
                performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                onTap()
            }
        }
        if (special) searchSpecialKeys.add(v) else searchKeys.add(v)
        return v
    }

    private fun openSearch() {
        hideToneStrip()
        searching = true
        query = StringBuilder()
        tabsScroll.visibility = GONE
        gridScroll.visibility = GONE
        bottom.visibility = GONE
        searchPane.visibility = VISIBLE
        showResults()
    }

    private fun closeSearch() {
        hideToneStrip()
        searching = false
        searchPane.visibility = GONE
        tabsScroll.visibility = VISIBLE
        gridScroll.visibility = VISIBLE
        bottom.visibility = VISIBLE
    }

    private fun typeQuery(c: Char) {
        if (query.length >= MAX_QUERY) return
        // No leading or doubled space: either would only blank the results.
        if (c == ' ' && (query.isEmpty() || query.last() == ' ')) return
        query.append(c)
        showResults()
    }

    private fun backspaceQuery() {
        if (query.isEmpty()) return
        query.setLength(query.length - 1)
        showResults()
    }

    /**
     * The results for the query, or the frequently used ones while it is empty, so the row
     * is never blank on open. Built on first use: most sessions never search.
     */
    private fun showResults() {
        queryView.text = query.toString()
        val engine = search ?: EmojiSearch(
            categories.flatMap { cat -> cat.emoji.map { EmojiSuggest.Entry(it.ch, it.name, it.keywords) } },
        ).also { search = it }
        val uses = recentEntries.withIndex().associate { (i, e) -> e.ch to recentEntries.size - i }
        val found = if (query.isBlank()) recentEntries.map { it.ch } else engine.find(query.toString(), uses)
        resultsRow.removeAllViews()
        resultsScroll.scrollTo(0, 0)
        if (found.isEmpty() && query.isNotBlank()) {
            resultsRow.addView(
                TextView(context).apply {
                    text = context.getString(R.string.emoji_search_none)
                    textSize = 14f
                    setTextColor(theme.keyHint)
                    setPadding(dp(12), dp(12), dp(12), dp(12))
                },
            )
            return
        }
        for (ch in found) {
            resultsRow.addView(
                emojiCell(ch, 26f).apply { setPadding(dp(8), dp(6), dp(8), dp(6)) },
                LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT),
            )
        }
    }

    private fun showCategory(index: Int) {
        val shown = panels().getOrNull(index) ?: return
        shownCategory = index
        grid.removeAllViews()
        gridScroll.scrollTo(0, 0)
        val perRow = COLUMNS
        var row: LinearLayout? = null
        for ((i, entry) in shown.emoji.withIndex()) {
            if (i % perRow == 0) {
                row = LinearLayout(context).apply { orientation = HORIZONTAL }
                grid.addView(row, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
            }
            row?.addView(emojiCell(entry.ch, 26f), LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f))
        }
        // Pad the last row so cells keep equal width.
        val last = grid.getChildAt(grid.childCount - 1) as? LinearLayout
        if (last != null) {
            while (last.childCount < perRow) {
                last.addView(TextView(context), LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f))
            }
        }
    }

    private companion object {
        const val TAG = "EmojiPickerView"
        const val COLUMNS = 8

        /** Longest query typed; no name in the asset is near it. */
        const val MAX_QUERY = 32

        /** Marks the frequently-used tab. A star, not a clock: it is ordered by
         *  how often an emoji is used, not by when it last was. */
        const val RECENT_ICON = "\u2605"

        /** Opens the search pane. A magnifier, drawn by every emoji font since Android 4. */
        const val SEARCH_ICON = "\uD83D\uDD0D"

        /** The search pane's letters, a row each; the last row ends in the query's backspace. */
        val SEARCH_ROWS = listOf("qwertyuiop", "asdfghjkl", "zxcvbnm")
    }
}
