package dev.t4xsuite.ui

import android.app.Activity
import android.app.AlertDialog
import android.content.ActivityNotFoundException
import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.RippleDrawable
import android.os.Build
import android.os.Bundle
import android.text.Editable
import android.text.InputFilter
import android.text.InputType
import android.text.TextUtils
import android.text.TextWatcher
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsets
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.BaseAdapter
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.PopupMenu
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import dev.t4xsuite.core.Capture
import dev.t4xsuite.core.Exchange
import dev.t4xsuite.core.Finding
import dev.t4xsuite.core.Header
import java.util.Locale

private const val IMPORT = 100
private const val EXPORT = 101
private const val FILL = ViewGroup.LayoutParams.MATCH_PARENT
private const val WRAP = ViewGroup.LayoutParams.WRAP_CONTENT

@Suppress("DEPRECATION")
class MainActivity : Activity() {
    private lateinit var model: ReviewSession
    private lateinit var p: Palette
    private val liquid by lazy { LiquidUi(this) }
    private lateinit var summary: TextView
    private lateinit var meta: TextView
    private lateinit var importButton: TextView
    private lateinit var demoButton: TextView
    private lateinit var trafficTab: TextView
    private lateinit var findingsTab: TextView
    private lateinit var search: EditText
    private lateinit var reportButton: TextView
    private lateinit var info: TextView
    private lateinit var empty: LinearLayout
    private lateinit var emptyTitle: TextView
    private lateinit var emptyText: TextView
    private lateinit var list: ListView
    private lateinit var rows: Rows
    private var lastCapture: Capture? = null
    private var showFindings = false

    override fun onCreate(state: Bundle?) {
        super.onCreate(state)
        model = lastNonConfigurationInstance as? ReviewSession ?: ReviewSession()
        p = Palette(this)
        showFindings = state?.getBoolean("findings") ?: false
        build()
        model.observer = ::update
        update()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putBoolean("findings", showFindings)
    }

    override fun onRetainNonConfigurationInstance(): Any = model

    override fun onDestroy() {
        model.observer = null
        if (!isChangingConfigurations) model.close()
        super.onDestroy()
    }

    private fun build() {
        val compact = resources.configuration.screenHeightDp < 550 || resources.configuration.fontScale > 1.3f
        val root = FrameLayout(this).apply { setBackgroundColor(p.bg) }
        if (Build.VERSION.SDK_INT >= 30) {
            window.setDecorFitsSystemWindows(false)
            root.setOnApplyWindowInsetsListener { view, insets ->
                val safe = insets.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout() or WindowInsets.Type.ime())
                view.setPadding(safe.left, safe.top, safe.right, safe.bottom)
                insets
            }
        }
        val panel = column().apply {
            isFocusableInTouchMode = true
            setPadding(liquid.dim("screen_padding"), dp(if (compact) 3 else 10), liquid.dim("screen_padding"), dp(8))
        }
        root.addView(panel, FrameLayout.LayoutParams(FILL, FILL))
        setContentView(root)

        val brand = row()
        val markId = resources.getIdentifier("ic_t4xsuite", "drawable", packageName)
        brand.addView(ImageView(this).apply { setImageResource(markId) }, LinearLayout.LayoutParams(dp(34), dp(34)))
        brand.addView(label("T4X SUITE", 17, p.text, bold = true), weighted(start = 9))
        brand.addView(label("모", 12, liquid.colors.gold, mono = true).apply {
            background = box(p.soft, 10)
            setPadding(dp(8), dp(5), dp(8), dp(5))
        })
        val menu = button("⋮") { openMenu(it) }.apply {
            contentDescription = "Session options"
            textSize = 22f
            minWidth = dp(48)
        }
        brand.addView(menu, LinearLayout.LayoutParams(WRAP, WRAP).apply { marginStart = dp(8) })
        panel.addView(brand)

        val overview = column().apply {
            background = liquid.glass()
            val y = dp(if (compact) 7 else 12)
            setPadding(dp(13), y, dp(13), y)
        }
        if (!compact) overview.addView(label("OFFLINE / HAR REVIEW", 12, p.accent, mono = true).apply { letterSpacing = .08f })
        summary = label("", if (compact) 16 else 22, p.text, bold = true).apply { isAccessibilityHeadingCompat() }
        overview.addView(summary, line(if (compact) 0 else 4))
        meta = label("", 13, p.sub)
        if (!compact) overview.addView(meta, line(3))
        panel.addView(overview, line(if (compact) 3 else 10))

        val actions = row()
        importButton = button("Import HAR", primary = true) { pickImport() }
        demoButton = button("Load demo") { model.loadDemo(application.assets) }
        actions.addView(importButton, weighted())
        actions.addView(demoButton, weighted(start = 8))
        panel.addView(actions, line(8))

        val tabs = row().apply {
            background = liquid.glass()
            setPadding(dp(3), dp(2), dp(3), dp(2))
        }
        trafficTab = button("Traffic") { selectTab(false) }
        findingsTab = button("Findings") { selectTab(true) }
        tabs.addView(trafficTab, weighted())
        tabs.addView(findingsTab, weighted(start = 4))
        panel.addView(tabs, line(if (compact) 4 else 8))

        search = EditText(this).apply {
            hint = "Filter by host, path, method or status"
            setTextSize(14f)
            setTextColor(p.text)
            setHintTextColor(p.sub)
            setSingleLine(true)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
            importantForAutofill = View.IMPORTANT_FOR_AUTOFILL_NO
            imeOptions = EditorInfo.IME_ACTION_SEARCH or EditorInfo.IME_FLAG_NO_EXTRACT_UI or EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING
            filters = arrayOf(InputFilter.LengthFilter(200))
            isSaveEnabled = false
            minHeight = dp(48)
            setPadding(dp(13), 0, dp(13), 0)
            background = box(p.card, 16, p.line)
            setOnEditorActionListener { _, id, _ ->
                if (id != EditorInfo.IME_ACTION_SEARCH) false else { hideKeyboard(); true }
            }
            addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
                override fun afterTextChanged(s: Editable?) {
                    if (::list.isInitialized) renderList()
                }
            })
        }
        panel.addView(search, line(8))

        reportButton = button("Save Markdown report") { pickExport() }
        panel.addView(reportButton, line(8))
        info = label("", 12, p.sub).apply { accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE }
        panel.addView(info, line(5))

        val stack = FrameLayout(this)
        rows = Rows()
        list = ListView(this).apply {
            adapter = rows
            divider = null
            dividerHeight = dp(5)
            setPadding(0, 0, 0, dp(5))
            clipToPadding = false
            setSelector(android.R.color.transparent)
            setOnItemClickListener { _, _, position, _ ->
                if (showFindings) rows.findings.getOrNull(position)?.let(::showFinding)
                else rows.traffic.getOrNull(position)?.let(::showExchange)
            }
        }
        stack.addView(list, FrameLayout.LayoutParams(FILL, FILL))
        empty = column().apply {
            background = liquid.glass()
            setPadding(dp(15), dp(12), dp(15), dp(12))
        }
        emptyTitle = label("", 17, p.text, bold = true)
        emptyText = label("", 14, p.sub)
        empty.addView(emptyTitle)
        empty.addView(emptyText, line(4))
        val emptyScroll = ScrollView(this).apply { addView(empty, FrameLayout.LayoutParams(FILL, WRAP)) }
        stack.addView(emptyScroll, FrameLayout.LayoutParams(FILL, FILL))
        panel.addView(stack, LinearLayout.LayoutParams(FILL, 0, 1f).apply { topMargin = dp(6) })
        panel.requestFocus()
    }

    private fun update() {
        val capture = model.capture
        val busy = model.job != null
        if (lastCapture !== capture) {
            lastCapture = capture
            search.setText("")
            list.setSelection(0)
        }
        summary.text = if (capture == null) "Inspect HTTP captures" else {
            val count = capture.exchanges.size
            val findings = capture.findings.size
            "$count ${if (count == 1) "exchange" else "exchanges"}  ·  $findings ${if (findings == 1) "check" else "checks"} flagged"
        }
        meta.text = when {
            capture == null -> "Passive security review · memory-only sessions"
            model.demo -> "Bundled demo · synthetic capture"
            else -> "Imported HAR · metadata only"
        }
        importButton.text = if (model.job == ReviewSession.Job.IMPORT) "Reading…" else "Import HAR"
        toggle(importButton, !busy)
        toggle(demoButton, !busy)
        updateTabControls()
        renderList()
        model.takeMessage()?.let { Toast.makeText(this, it, Toast.LENGTH_LONG).show() }
    }

    private fun selectTab(findings: Boolean) {
        if (showFindings == findings) return
        showFindings = findings
        hideKeyboard()
        search.clearFocus()
        updateTabControls()
        renderList()
        list.setSelection(0)
    }

    private fun updateTabControls() {
        for ((selected, tab) in listOf(!showFindings to trafficTab, showFindings to findingsTab)) {
            tab.isSelected = selected
            tab.setTextColor(if (selected) p.accent else p.sub)
            tab.background = ripple(if (selected) p.soft else Color.TRANSPARENT, 11)
        }
        findingsTab.text = "Findings${model.capture?.findings?.size?.takeIf { it > 0 }?.let { " · $it" } ?: ""}"
        search.visibility = if (showFindings || model.capture?.exchanges.isNullOrEmpty()) View.GONE else View.VISIBLE
        reportButton.visibility = if (showFindings && model.capture != null) View.VISIBLE else View.GONE
        toggle(reportButton, model.job == null)
        reportButton.text = if (model.job == ReviewSession.Job.EXPORT) "Saving…" else "Save Markdown report"
    }

    private fun renderList() {
        val capture = model.capture
        val all = capture?.exchanges.orEmpty()
        val query = search.text?.toString().orEmpty().trim().lowercase(Locale.ROOT)
        val terms = query.split(' ').filter(String::isNotEmpty)
        rows.traffic = if (showFindings || terms.isEmpty()) all else all.filter { entry -> terms.all { it in entry.searchable } }
        rows.findings = capture?.findings.orEmpty()
        rows.notifyDataSetChanged()

        info.text = when {
            model.job == ReviewSession.Job.IMPORT -> "Reading selected capture…"
            showFindings && capture != null -> "Review candidates, not confirmed vulnerabilities."
            capture != null -> "${rows.traffic.size} of ${all.size} exchanges  ·  Sensitive values hidden"
            else -> ""
        }
        info.visibility = if (info.text.isEmpty()) View.GONE else View.VISIBLE
        list.visibility = if (rows.count == 0) View.GONE else View.VISIBLE
        (empty.parent as View).visibility = if (rows.count == 0) View.VISIBLE else View.GONE
        val (title, copy) = when {
            capture == null -> "Start with a capture" to "Choose an authorized HAR file, or load the demo. Bodies, query strings and credential header values are not retained."
            showFindings -> "No candidates matched" to "The built-in checks cover a small subset of HTTP security controls. This does not establish that a system is secure."
            all.isEmpty() -> "No exchanges in this capture" to "The HAR opened successfully, but its traffic history was empty."
            else -> "No matching exchanges" to "Try a shorter method, host, path or status filter."
        }
        emptyTitle.text = title
        emptyText.text = copy
    }

    private fun openMenu(anchor: View) {
        PopupMenu(this, anchor).apply {
            if (model.job == ReviewSession.Job.IMPORT) menu.add("Cancel import").setOnMenuItemClickListener {
                model.cancelImport(); true
            }
            menu.add("Privacy & limits").setOnMenuItemClickListener {
                dialog("Privacy & limits", column().apply {
                    addView(label("T4X-SUITE makes no network requests and only reads the files you choose. Android’s file picker can list cloud providers. Hosts and paths are visible locally and may contain sensitive data.", 15, p.text))
                    addView(label("This session stays in memory. Bodies, query strings and credential header values are discarded. Markdown includes counts and fixed guidance only. HAR limit: 8 MiB, 2,500 exchanges, 96 headers per side.", 14, p.sub), line(12))
                }); true
            }
            menu.add("Clear session").setEnabled(model.capture != null && model.job == null).setOnMenuItemClickListener {
                hideKeyboard()
                model.clear()
                true
            }
            show()
        }
    }

    private fun pickImport() {
        pick(Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "*/*" // Android providers frequently label .har as octet-stream or plain text.
        }, IMPORT)
    }

    private fun pickExport() {
        pick(Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "text/markdown"
            putExtra(Intent.EXTRA_TITLE, "t4xsuite-review.md")
        }, EXPORT)
    }

    private fun pick(intent: Intent, request: Int) {
        hideKeyboard()
        try {
            startActivityForResult(intent, request)
        } catch (_: ActivityNotFoundException) {
            Toast.makeText(this, "No system document picker is available.", Toast.LENGTH_LONG).show()
        }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (resultCode != RESULT_OK) return
        val uri = data?.data?.takeIf { it.scheme == "content" } ?: return
        when (requestCode) {
            IMPORT -> model.loadFile(application.contentResolver, uri)
            EXPORT -> if (model.capture != null) model.export(application.contentResolver, uri) else {
                Toast.makeText(this, "The previous session is no longer in memory.", Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun showExchange(exchange: Exchange) {
        hideKeyboard()
        dialog("${exchange.method} · ${status(exchange)}", column().apply {
            addView(label("${exchange.scheme}://${exchange.host}${exchange.path}", 14, p.text, mono = true))
            addView(label(exchange.elapsedMs?.let { "Captured duration: $it ms" } ?: "No duration in capture", 13, p.sub), line(6))
            addHeaders("Request headers", exchange.requestHeaders)
            addHeaders("Response headers", exchange.responseHeaders)
            if (exchange.rules.isNotEmpty()) {
                addView(label("Review candidates", 16, p.accent, bold = true).apply { isAccessibilityHeadingCompat() }, line(16))
                exchange.rules.forEach { addView(label(it.title, 14, p.text), line(5)) }
            }
        })
    }

    private fun LinearLayout.addHeaders(title: String, headers: List<Header>) {
        addView(label(title, 16, p.accent, bold = true).apply { isAccessibilityHeadingCompat() }, line(16))
        if (headers.isEmpty()) addView(label("None in capture", 14, p.sub), line(3))
        headers.forEach { (name, summary) -> addView(label("$name: ${summary ?: "[hidden]"}", 13, p.text, mono = true), line(6)) }
    }

    private fun showFinding(finding: Finding) {
        dialog(finding.rule.title, column().apply {
            addView(label("${finding.rule.severity} · Observed on ${finding.count} ${if (finding.count == 1) "exchange" else "exchanges"}.", 15, p.sub))
            addView(label(finding.rule.action, 16, p.text), line(10))
            addView(label("Verify the affected controls in context. This is not a confirmed vulnerability.", 14, p.sub), line(10))
        })
    }

    private fun dialog(title: String, content: LinearLayout) {
        content.setPadding(dp(22), dp(10), dp(22), dp(14))
        val wrapped = ScrollView(this).apply { addView(content) }
        AlertDialog.Builder(this).setTitle(title).setView(wrapped).setPositiveButton("Done", null).create().apply {
            show()
        }
    }

    private fun hideKeyboard() {
        getSystemService(InputMethodManager::class.java)?.hideSoftInputFromWindow(search.windowToken, 0)
    }

    private fun button(title: String, primary: Boolean = false, click: (View) -> Unit): TextView =
        liquid.button(title, primary) { }.apply { setOnClickListener(click) }

    private fun toggle(view: View, enabled: Boolean) {
        view.isEnabled = enabled
        view.alpha = if (enabled) 1f else .65f
    }

    private fun label(value: String, size: Int, color: Int, bold: Boolean = false, mono: Boolean = false) = TextView(this).apply {
        text = value
        textSize = size.toFloat()
        setTextColor(color)
        typeface = if (mono) Typeface.MONOSPACE else Typeface.create("sans-serif", if (bold) Typeface.BOLD else Typeface.NORMAL)
    }

    private fun View.isAccessibilityHeadingCompat() { if (Build.VERSION.SDK_INT >= 28) isAccessibilityHeading = true }
    private fun row() = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
    private fun column() = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
    private fun line(top: Int = 0) = LinearLayout.LayoutParams(FILL, WRAP).apply { topMargin = dp(top) }
    private fun weighted(start: Int = 0) = LinearLayout.LayoutParams(0, WRAP, 1f).apply { marginStart = dp(start) }
    private fun dp(size: Int): Int = (size * resources.displayMetrics.density + .5f).toInt()
    private fun box(color: Int, radius: Int, border: Int? = null) = liquid.shape(color, radius, border)
    private fun ripple(color: Int, radius: Int, border: Int? = null) = RippleDrawable(
        ColorStateList.valueOf(p.ripple), box(color, radius, border), box(Color.WHITE, radius)
    )
    private fun status(exchange: Exchange) = if (exchange.status == 0) "Unknown status" else exchange.status.toString()

    private inner class Rows : BaseAdapter() {
        var traffic: List<Exchange> = emptyList()
        var findings: List<Finding> = emptyList()
        override fun getCount() = if (showFindings) findings.size else traffic.size
        override fun getItem(position: Int): Any = if (showFindings) findings[position] else traffic[position]
        override fun getItemId(position: Int) = position.toLong()

        override fun getView(position: Int, convertView: View?, parent: ViewGroup?): View {
            val cell = convertView as? ReviewRow ?: ReviewRow()
            if (showFindings) {
                val (rule, count) = findings[position]
                cell.title.text = rule.title
                cell.detail.text = "Observed on $count ${if (count == 1) "exchange" else "exchanges"}"
                cell.badge.text = rule.severity.uppercase(Locale.ROOT)
                cell.badge.setTextColor(p.accent)
            } else {
                val entry = traffic[position]
                cell.title.text = "${entry.method}  ${entry.path}"
                cell.detail.text = "${entry.host}${entry.elapsedMs?.let { "  ·  $it ms" } ?: ""}"
                cell.badge.text = if (entry.status == 0) "—" else entry.status.toString()
                cell.badge.setTextColor(if (entry.status >= 400) p.warn else p.accent)
            }
            return cell
        }
    }

    private inner class ReviewRow : LinearLayout(this) {
        val title = label("", 15, p.text, bold = true).apply { maxLines = 1; ellipsize = TextUtils.TruncateAt.END }
        val detail = label("", 13, p.sub).apply { maxLines = 1; ellipsize = TextUtils.TruncateAt.END }
        val badge = label("", 11, p.accent, mono = true).apply {
            gravity = Gravity.CENTER
            setPadding(dp(8), dp(5), dp(8), dp(5))
            background = box(p.soft, 8)
        }

        init {
            orientation = HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            minimumHeight = dp(56)
            background = ripple(p.card, 18, p.line)
            setPadding(dp(12), dp(7), dp(12), dp(7))
            addView(column().apply { addView(title); addView(detail, line(2)) }, weighted())
            addView(badge, LinearLayout.LayoutParams(WRAP, WRAP).apply { marginStart = dp(8) })
        }
    }
}

private class Palette(context: android.content.Context) {
    private val shared = LiquidColors(context)
    val bg = shared.background
    val card = shared.panel
    val line = shared.stroke
    val text = shared.foreground
    val sub = shared.secondary
    val soft = shared.selected
    val accent = shared.accent
    val onAccent = shared.onAccent
    val warn = shared.gold
    val ripple = shared.ripple
}
