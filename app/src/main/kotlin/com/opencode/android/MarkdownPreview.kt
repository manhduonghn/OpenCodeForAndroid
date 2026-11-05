package com.opencode.android

import android.app.Activity
import android.app.Dialog
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.view.ViewGroup
import android.view.Window
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.LinearLayout
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.dialog.MaterialAlertDialogBuilder

/**
 * Small, dependency-free Markdown renderer used by the editor preview.
 *
 * The output is escaped before any markup is added, links are limited to the schemes accepted by
 * [EditorProtocol], and images are rendered as their alt text. The preview therefore never loads
 * a local file or remote resource; a Markdown document cannot turn into an HTML/script injection.
 */
object MarkdownRenderer {

    private val fence = Regex("""^\s*(`{3,}|~{3,})\s*([\w+#.-]*)""")
    private val heading = Regex("""^(#{1,6})\s+(.*)$""")
    private val rule = Regex("""^\s*((\*\s*){3,}|(-\s*){3,}|(_\s*){3,})\s*$""")
    private val unordered = Regex("""^\s*[-*+]\s+(.*)$""")
    private val ordered = Regex("""^\s*\d+[.)]\s+(.*)$""")
    private const val HARD_BREAK = '\u0000'

    /** Body HTML only; useful for tests and for embedding in another page. */
    fun render(markdown: String): String {
        val lines = markdown.replace("\r\n", "\n").replace('\r', '\n').split('\n')
        return renderBlocks(lines)
    }

    /** A complete, offline preview document with the current Material You palette. */
    fun toHtml(markdown: String, theme: EditorTheme): String {
        val background = theme.colors["background"] ?: "#fffbfe"
        val foreground = theme.colors["foreground"] ?: "#1c1b1f"
        val surface = theme.colors["surface"] ?: background
        val gutter = theme.colors["gutter"] ?: surface
        val link = theme.markdown["link"] ?: theme.syntax["keyword"] ?: foreground
        val code = theme.markdown["code"] ?: foreground
        val quote = theme.markdown["quote"] ?: foreground
        val ruleColor = theme.markdown["rule"] ?: theme.colors["gutterText"] ?: foreground
        return buildString {
            append("<!doctype html><html><head><meta charset=\"utf-8\">")
            append(
                "<meta http-equiv=\"Content-Security-Policy\" content=\"" +
                    "default-src 'none'; img-src data:; style-src 'unsafe-inline'\">",
            )
            append("<meta name=\"viewport\" content=\"width=device-width, initial-scale=1\">")
            append("<style>")
            append(":root{color-scheme:")
            append(if (theme.dark) "dark" else "light")
            append("}")
            append("body{margin:0;padding:20px 20px 40px;background:")
            append(background)
            append(";color:")
            append(foreground)
            append(";font:16px/1.6 -apple-system,Roboto,sans-serif;word-wrap:break-word}")
            append("h1,h2,h3,h4,h5,h6{line-height:1.25;margin:1.4em 0 .5em;font-weight:600}")
            append("h1{font-size:1.8em}h2{font-size:1.45em}h3{font-size:1.2em}")
            append("p{margin:.6em 0}ul,ol{margin:.5em 0;padding-inline-start:1.6em}")
            append("li{margin:.25em 0}")
            append("a{color:")
            append(link)
            append(";text-decoration:none}a:hover{text-decoration:underline}")
            append("code{font-family:monospace;background:")
            append(gutter)
            append(";color:")
            append(code)
            append(";border-radius:4px;padding:.1em .3em}")
            append("pre{background:")
            append(gutter)
            append(";border-radius:8px;padding:12px;overflow:auto}")
            append("pre code{background:none;padding:0;white-space:pre}")
            append("blockquote{margin:.8em 0;padding:.1em 0 .1em 14px;border-inline-start:3px solid ")
            append(ruleColor)
            append(";color:")
            append(quote)
            append("}")
            append("hr{border:0;border-top:1px solid ")
            append(ruleColor)
            append(";margin:1.4em 0}")
            append(".task{font-family:monospace;margin-inline-end:6px}")
            append("</style></head><body>")
            append(render(markdown))
            append("</body></html>")
        }
    }

    private fun renderBlocks(lines: List<String>): String {
        val out = StringBuilder()
        var index = 0
        while (index < lines.size) {
            val raw = lines[index]
            val trimmed = raw.trim()
            if (trimmed.isEmpty()) {
                index++
                continue
            }

            val fenceMatch = fence.find(raw)
            if (fenceMatch != null) {
                val marker = fenceMatch.groupValues[1]
                val language = fenceMatch.groupValues[2]
                index++
                val code = StringBuilder()
                while (index < lines.size) {
                    val closing = lines[index].trim()
                    if (closing.startsWith(marker.take(1).repeat(marker.length)) &&
                        closing.all { it == marker[0] }
                    ) {
                        index++
                        break
                    }
                    if (code.isNotEmpty()) code.append('\n')
                    code.append(lines[index])
                    index++
                }
                out.append("<pre><code")
                if (language.isNotEmpty()) {
                    out.append(" class=\"language-")
                    out.append(escapeAttribute(language))
                    out.append('"')
                }
                out.append('>')
                out.append(escape(code.toString()))
                out.append("</code></pre>")
                continue
            }

            val headingMatch = heading.find(trimmed)
            if (headingMatch != null) {
                val level = headingMatch.groupValues[1].length
                out.append("<h").append(level).append('>')
                out.append(inline(headingMatch.groupValues[2]))
                out.append("</h").append(level).append('>')
                index++
                continue
            }

            if (rule.matches(raw)) {
                out.append("<hr>")
                index++
                continue
            }

            if (trimmed.startsWith(">")) {
                val quote = ArrayList<String>()
                while (index < lines.size && lines[index].trimStart().startsWith(">")) {
                    quote.add(lines[index].trimStart().removePrefix(">").removePrefix(" "))
                    index++
                }
                out.append("<blockquote>")
                out.append(renderBlocks(quote))
                out.append("</blockquote>")
                continue
            }

            if (unordered.matches(raw) || ordered.matches(raw)) {
                val orderedList = ordered.matches(raw) && !unordered.matches(raw)
                out.append(if (orderedList) "<ol>" else "<ul>")
                while (index < lines.size) {
                    val item = (if (orderedList) {
                        ordered.find(lines[index])
                    } else {
                        unordered.find(lines[index])
                    }) ?: break
                    val content = item.groupValues[1]
                    out.append("<li>")
                    val task = Regex("""^\[([ xX])]\s+(.*)$""").find(content)
                    if (task != null) {
                        out.append("<span class=\"task\">")
                        out.append(if (task.groupValues[1].isBlank()) "☐" else "☑")
                        out.append("</span>")
                        out.append(inline(task.groupValues[2]))
                    } else {
                        out.append(inline(content))
                    }
                    out.append("</li>")
                    index++
                }
                out.append(if (orderedList) "</ol>" else "</ul>")
                continue
            }

            val paragraph = StringBuilder()
            while (index < lines.size) {
                val line = lines[index]
                if (line.trim().isEmpty() || startsBlock(line)) break
                if (paragraph.isNotEmpty()) paragraph.append('\n')
                val hard = line.endsWith("  ") || line.trimEnd().endsWith("\\")
                paragraph.append(if (hard) line.trimEnd().trimEnd('\\') else line)
                if (hard) paragraph.append(HARD_BREAK)
                index++
            }
            out.append("<p>")
            out.append(inline(paragraph.toString()))
            out.append("</p>")
        }
        return out.toString()
    }

    private fun startsBlock(line: String): Boolean {
        val trimmed = line.trim()
        return fence.find(line) != null ||
            heading.find(trimmed) != null ||
            rule.matches(line) ||
            trimmed.startsWith(">") ||
            unordered.matches(line) ||
            ordered.matches(line)
    }

    private fun inline(text: String): String {
        val out = StringBuilder(text.length + 16)
        var index = 0
        while (index < text.length) {
            val char = text[index]
            when {
                char == HARD_BREAK -> {
                    out.append("<br>")
                    index++
                }
                char == '`' -> {
                    val end = text.indexOf('`', index + 1)
                    if (end < 0) {
                        out.append('`')
                        index++
                    } else {
                        out.append("<code>")
                        out.append(escape(text.substring(index + 1, end)))
                        out.append("</code>")
                        index = end + 1
                    }
                }
                char == '!' && index + 1 < text.length && text[index + 1] == '[' -> {
                    val parsed = parseLink(text, index + 1)
                    if (parsed == null) {
                        out.append('!')
                        index++
                    } else {
                        // Images stay textual: the preview has no safe file loader and no network.
                        out.append("<em>")
                        out.append(escape(parsed.label.ifEmpty { "image" }))
                        out.append("</em>")
                        index = parsed.end
                    }
                }
                char == '[' -> {
                    val parsed = parseLink(text, index)
                    if (parsed == null) {
                        out.append('[')
                        index++
                    } else {
                        val label = inline(parsed.label)
                        if (EditorProtocol.isSafeLink(parsed.url)) {
                            out.append("<a href=\"")
                            out.append(escapeAttribute(parsed.url))
                            out.append("\">")
                            out.append(label)
                            out.append("</a>")
                        } else {
                            out.append(label)
                        }
                        index = parsed.end
                    }
                }
                char == '*' && index + 1 < text.length && text[index + 1] == '*' -> {
                    val end = text.indexOf("**", index + 2)
                    if (end < 0) {
                        out.append("**")
                        index += 2
                    } else {
                        out.append("<strong>")
                        out.append(inline(text.substring(index + 2, end)))
                        out.append("</strong>")
                        index = end + 2
                    }
                }
                char == '*' -> {
                    val end = text.indexOf('*', index + 1)
                    if (end < 0) {
                        out.append('*')
                        index++
                    } else {
                        out.append("<em>")
                        out.append(inline(text.substring(index + 1, end)))
                        out.append("</em>")
                        index = end + 1
                    }
                }
                char == '_' && isWordBoundary(text, index) -> {
                    val end = text.indexOf('_', index + 1)
                    if (end < 0) {
                        out.append('_')
                        index++
                    } else {
                        out.append("<em>")
                        out.append(inline(text.substring(index + 1, end)))
                        out.append("</em>")
                        index = end + 1
                    }
                }
                char == '\\' && index + 1 < text.length -> {
                    out.append(escape(text[index + 1].toString()))
                    index += 2
                }
                char == '&' || char == '<' || char == '>' || char == '"' -> {
                    out.append(escape(char.toString()))
                    index++
                }
                else -> {
                    out.append(char)
                    index++
                }
            }
        }
        return out.toString()
    }

    private data class ParsedLink(val label: String, val url: String, val end: Int)

    private fun parseLink(text: String, start: Int): ParsedLink? {
        if (start >= text.length || text[start] != '[') return null
        var index = start + 1
        var depth = 0
        while (index < text.length) {
            when (text[index]) {
                '[' -> depth++
                ']' -> {
                    if (depth == 0) break
                    depth--
                }
            }
            index++
        }
        if (index >= text.length || text[index] != ']') return null
        val label = text.substring(start + 1, index)
        if (index + 1 >= text.length || text[index + 1] != '(') return null
        var end = index + 2
        var parens = 1
        while (end < text.length) {
            when (text[end]) {
                '(' -> parens++
                ')' -> {
                    parens--
                    if (parens == 0) break
                }
            }
            end++
        }
        if (end >= text.length) return null
        val url = text.substring(index + 2, end).trim().substringBefore(' ')
        return ParsedLink(label, url, end + 1)
    }

    private fun isWordBoundary(text: String, index: Int): Boolean {
        val before = if (index > 0) text[index - 1] else ' '
        val after = if (index + 1 < text.length) text[index + 1] else ' '
        return !before.isLetterOrDigit() && !after.isLetterOrDigit()
    }

    private fun escape(text: String): String = buildString(text.length) {
        for (char in text) {
            when (char) {
                '&' -> append("&amp;")
                '<' -> append("&lt;")
                '>' -> append("&gt;")
                '"' -> append("&quot;")
                '\'' -> append("&#39;")
                else -> append(char)
            }
        }
    }

    private fun escapeAttribute(text: String): String = escape(text).replace("\n", " ")
}

/** Full-screen, read-only Markdown preview with no JavaScript and no network access. */
object MarkdownPreviewDialog {

    @Suppress("DEPRECATION")
    fun show(activity: Activity, title: String, markdown: String, theme: EditorTheme) {
        val background = theme.colors["background"] ?: "#fffbfe"
        val foreground = theme.colors["foreground"] ?: "#1c1b1f"
        val surface = theme.colors["surface"] ?: background
        val web = WebView(activity).apply {
            contentDescription = activity.getString(R.string.opencode_editor_preview_description, title)
            setBackgroundColor(parseColor(background, Color.WHITE))
            settings.javaScriptEnabled = false
            settings.domStorageEnabled = false
            settings.databaseEnabled = false
            settings.allowFileAccess = false
            settings.allowContentAccess = false
            settings.blockNetworkLoads = true
            settings.allowFileAccessFromFileURLs = false
            settings.allowUniversalAccessFromFileURLs = false
            settings.cacheMode = android.webkit.WebSettings.LOAD_NO_CACHE
            webViewClient = object : WebViewClient() {
                override fun shouldOverrideUrlLoading(
                    view: WebView,
                    request: WebResourceRequest,
                ): Boolean {
                    confirmLink(activity, request.url.toString())
                    return true
                }

                @Deprecated("Deprecated in Java")
                override fun shouldOverrideUrlLoading(view: WebView, url: String): Boolean {
                    confirmLink(activity, url)
                    return true
                }
            }
        }
        val toolbar = MaterialToolbar(activity).apply {
            setTitle(title)
            setNavigationIcon(R.drawable.ic_close)
            navigationContentDescription = activity.getString(R.string.opencode_cancel)
            setNavigationOnClickListener { /* set after the dialog exists */ }
            setBackgroundColor(parseColor(surface, Color.TRANSPARENT))
            setTitleTextColor(parseColor(foreground, Color.BLACK))
        }
        val container = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(parseColor(background, Color.WHITE))
            addView(
                toolbar,
                LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    activity.resources.getDimensionPixelSize(R.dimen.toolbar_height),
                ),
            )
            addView(
                web,
                LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    0,
                    1f,
                ),
            )
        }
        val dialog = Dialog(activity)
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE)
        dialog.setContentView(container)
        dialog.window?.setBackgroundDrawable(ColorDrawable(parseColor(background, Color.WHITE)))
        toolbar.setNavigationOnClickListener { dialog.dismiss() }
        dialog.setOnDismissListener {
            // The preview is disposable; releasing the WebView avoids keeping a renderer process
            // (and the rendered document) alive after the dialog closes.
            web.stopLoading()
            web.destroy()
        }
        dialog.show()
        dialog.window?.setLayout(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.MATCH_PARENT,
        )
        web.loadDataWithBaseURL(
            null,
            MarkdownRenderer.toHtml(markdown, theme),
            "text/html",
            "utf-8",
            null,
        )
    }

    private fun confirmLink(activity: Activity, url: String) {
        if (!EditorProtocol.isSafeLink(url)) return
        MaterialAlertDialogBuilder(activity)
            .setTitle(R.string.opencode_editor_preview_open_link)
            .setMessage(url)
            .setPositiveButton(R.string.opencode_ok) { _, _ ->
                try {
                    activity.startActivity(
                        android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse(url)),
                    )
                } catch (e: Exception) {
                    // A missing browser must not crash the preview.
                }
            }
            .setNegativeButton(R.string.opencode_cancel, null)
            .show()
    }

    private fun parseColor(value: String, fallback: Int): Int = try {
        Color.parseColor(value)
    } catch (e: Exception) {
        fallback
    }
}
