package com.opencode.android

import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.res.ColorStateList
import android.os.Build
import android.view.HapticFeedbackConstants
import android.view.MenuItem
import android.view.MotionEvent
import android.view.View
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.core.content.ContextCompat
import androidx.core.graphics.ColorUtils
import androidx.core.graphics.drawable.DrawableCompat
import androidx.core.view.ViewCompat
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.button.MaterialButton
import com.google.android.material.color.MaterialColors
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.opencode.android.terminal.KeyModifiers
import com.opencode.android.terminal.TermKey
import com.opencode.android.terminal.TerminalColors
import com.opencode.android.terminal.TerminalSession
import com.opencode.android.terminal.TerminalSessions
import com.opencode.android.terminal.TerminalView
import java.util.concurrent.Executors
import kotlin.math.roundToInt

/** Sticky modifier: off, on for the next key, or locked until tapped again. */
private enum class Latch {
    OFF,
    ONCE,
    LOCKED;

    val on: Boolean get() = this != OFF

    fun next(): Latch = when (this) {
        OFF -> ONCE
        ONCE -> LOCKED
        LOCKED -> OFF
    }
}

/**
 * The interactive terminal: the PTY screen, the start/exit notice and the
 * extra-keys row that supplies the keys a phone keyboard lacks.
 *
 * The session itself lives in [TerminalSessions], so closing this panel only
 * detaches the view. The shell and every process started in it keep running,
 * and opening the panel again reattaches to the same screen, scrollback and
 * cursor position.
 */
class TerminalScreen(
    private val activity: Activity,
    private val listener: Listener,
) {

    interface Listener {
        fun onTerminalClosed()
    }

    private val panel: View = activity.findViewById(R.id.terminal_panel)
    private val toolbar: MaterialToolbar = activity.findViewById(R.id.terminal_toolbar)
    private val view: TerminalView = activity.findViewById(R.id.terminal_view)
    private val notice: View = activity.findViewById(R.id.terminal_notice)
    private val noticeProgress: ProgressBar = activity.findViewById(R.id.terminal_notice_progress)
    private val noticeText: TextView = activity.findViewById(R.id.terminal_notice_text)
    private val noticeAction: MaterialButton = activity.findViewById(R.id.terminal_notice_action)
    private val hint: TextView = activity.findViewById(R.id.terminal_hint)
    private val fnScroll: View = activity.findViewById(R.id.terminal_fn_scroll)

    private val ctrlKey: TextView = activity.findViewById(R.id.terminal_key_ctrl)
    private val altKey: TextView = activity.findViewById(R.id.terminal_key_alt)
    private val fnKey: TextView = activity.findViewById(R.id.terminal_key_fn)

    private val executor = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "opencode-terminal-start").apply { isDaemon = true }
    }

    private var session: TerminalSession? = null
    private var starting = false
    private var startError: String? = null
    private var lastGrid: IntArray? = null
    private var ctrl = Latch.OFF
    private var alt = Latch.OFF
    private var fnOpen = false

    @Volatile
    private var released = false

    /** True while the Linux runtime is being downloaded because the prefix is missing. */
    private var installing = false

    /** True when the shell has to be restarted after the runtime finished installing. */
    private var pendingRestart = false

    /** Last error of a failed runtime installation, shown in the notice. */
    private var runtimeError: String? = null

    /** Progress of the runtime installation, null while it is indeterminate. */
    private var installProgress: Int? = null

    /** Detail line published by the installer, eg. the download progress. */
    private var installDetail: String? = null

    /** Feeds the progress of the runtime download into the terminal notice. */
    private val installListener = object : OpenCodeBootstrap.InstallListener {
        override fun onOpenCodeInstallStateChanged(state: OpenCodeInstallState?) {
            if (released || !installing || state == null) return
            installProgress = state.overallProgress()
            installDetail = listOf(state.message, state.detail)
                .filter { it.isNotBlank() }
                .joinToString(" ")
                .ifBlank { null }
            renderNotice()
        }
    }

    private var repeatAction: (() -> Unit)? = null
    private val repeat = object : Runnable {
        override fun run() {
            val action = repeatAction ?: return
            action()
            activity.window?.decorView?.postDelayed(this, REPEAT_MS)
        }
    }

    val isVisible: Boolean
        get() = panel.visibility == View.VISIBLE

    private val sessionListener: () -> Unit = {
        activity.runOnUiThread {
            if (released) return@runOnUiThread
            renderNotice()
            renderSubtitle()
        }
    }

    init {
        toolbar.setNavigationOnClickListener { goBack() }
        toolbar.tintActionIcons()
        toolbar.setOnMenuItemClickListener(::onMenuItem)

        view.fontSizeSp = OpenCodeSettings.terminalFontSize(activity)
        view.colors = resolveColors()
        view.latchedModifiers = { KeyModifiers(ctrl = ctrl.on, alt = alt.on) }
        view.onModifiersUsed = { consumeModifiers() }
        view.onPasteRequest = { pasteClipboard() }
        view.onCopyRequest = { copyOutput() }
        view.onFontSizeChange = { size ->
            OpenCodeSettings.setTerminalFontSize(activity, size)
        }
        view.onGridSize = { rows, cols, widthPx, heightPx ->
            onGridSize(rows, cols, widthPx, heightPx)
        }

        noticeAction.setOnClickListener {
            when {
                installing -> Unit
                // A failed runtime download is retried on the next start, which installs the
                // runtime again instead of leaving the user with Android's shell.
                runtimeError != null -> startFromView()
                startError != null -> startFromView()
                session?.running == false -> restart()
                else -> startFromView()
            }
        }

        bindKey(R.id.terminal_key_esc) { view.pressKey(TermKey.ESCAPE) }
        ctrlKey.setOnClickListener {
            haptic(ctrlKey)
            ctrl = ctrl.next()
            renderModifiers()
        }
        altKey.setOnClickListener {
            haptic(altKey)
            alt = alt.next()
            renderModifiers()
        }
        bindKey(R.id.terminal_key_tab) { view.pressKey(TermKey.TAB) }
        bindKey(R.id.terminal_key_home) { view.pressKey(TermKey.HOME) }
        bindRepeatKey(R.id.terminal_key_up) { view.pressKey(TermKey.UP) }
        bindKey(R.id.terminal_key_end) { view.pressKey(TermKey.END) }
        bindRepeatKey(R.id.terminal_key_pgup) { view.pressKey(TermKey.PAGE_UP) }

        fnKey.setOnClickListener {
            haptic(fnKey)
            fnOpen = !fnOpen
            fnScroll.visibility = if (fnOpen) View.VISIBLE else View.GONE
            renderModifiers()
        }
        bindKey(R.id.terminal_key_interrupt) { view.pressChar(3) }
        bindChar(R.id.terminal_key_slash, '/'.code)
        bindChar(R.id.terminal_key_dash, '-'.code)
        bindRepeatKey(R.id.terminal_key_left) { view.pressKey(TermKey.LEFT) }
        bindRepeatKey(R.id.terminal_key_down) { view.pressKey(TermKey.DOWN) }
        bindRepeatKey(R.id.terminal_key_right) { view.pressKey(TermKey.RIGHT) }
        bindRepeatKey(R.id.terminal_key_pgdn) { view.pressKey(TermKey.PAGE_DOWN) }

        val functionKeys = listOf(
            R.id.terminal_key_f1 to TermKey.F1,
            R.id.terminal_key_f2 to TermKey.F2,
            R.id.terminal_key_f3 to TermKey.F3,
            R.id.terminal_key_f4 to TermKey.F4,
            R.id.terminal_key_f5 to TermKey.F5,
            R.id.terminal_key_f6 to TermKey.F6,
            R.id.terminal_key_f7 to TermKey.F7,
            R.id.terminal_key_f8 to TermKey.F8,
            R.id.terminal_key_f9 to TermKey.F9,
            R.id.terminal_key_f10 to TermKey.F10,
            R.id.terminal_key_f11 to TermKey.F11,
            R.id.terminal_key_f12 to TermKey.F12,
        )
        functionKeys.forEach { (id, key) -> bindKey(id) { view.pressKey(key) } }
        bindKey(R.id.terminal_key_ins) { view.pressKey(TermKey.INSERT) }
        bindRepeatKey(R.id.terminal_key_del) { view.pressKey(TermKey.DELETE) }

        val symbols = listOf(
            R.id.terminal_key_pipe to "|",
            R.id.terminal_key_tilde to "~",
            R.id.terminal_key_backtick to "`",
            R.id.terminal_key_backslash to "\\",
            R.id.terminal_key_brace_left to "{",
            R.id.terminal_key_brace_right to "}",
            R.id.terminal_key_bracket_left to "[",
            R.id.terminal_key_bracket_right to "]",
            R.id.terminal_key_angle_left to "<",
            R.id.terminal_key_angle_right to ">",
            R.id.terminal_key_amp to "&",
            R.id.terminal_key_dollar to "$",
            R.id.terminal_key_star to "*",
        )
        symbols.forEach { (id, text) -> bindChar(id, text[0].code) }

        renderModifiers()
        fnScroll.visibility = View.GONE
    }

    fun show() {
        OverlayMotion.reveal(panel)
        attach(TerminalSessions.session)
        view.fontSizeSp = OpenCodeSettings.terminalFontSize(activity)
        view.requestFocus()
        renderNotice()
        renderSubtitle()
        startIfNeeded()
    }

    fun hide() {
        if (!isVisible) return
        stopRepeat()
        resetModifiers()
        view.hideKeyboard()
        listener.onTerminalClosed()
        OverlayMotion.conceal(panel)
    }

    fun goBack() = hide()

    /** The activity is going to the background: the shell stays alive. */
    fun onBackground() {
        stopRepeat()
        resetModifiers()
        OpenCodeSettings.setTerminalFontSize(activity, view.fontSizeSp)
        if (isVisible) view.hideKeyboard()
    }

    fun onActivityResumed() {
        if (isVisible) {
            attach(TerminalSessions.session)
            renderNotice()
            startIfNeeded()
        }
    }

    fun release() {
        released = true
        installing = false
        OpenCodeBootstrap.removeInstallListener(installListener)
        stopRepeat()
        executor.shutdownNow()
        session?.removeListener(sessionListener)
        session = null
        view.attach(null)
    }

    private fun onGridSize(rows: Int, cols: Int, widthPx: Int, heightPx: Int) {
        lastGrid = intArrayOf(rows, cols, widthPx, heightPx)
        TerminalSessions.rememberGrid(rows, cols, widthPx, heightPx)
        val current = TerminalSessions.session
        if (current != null) {
            current.resize(rows, cols, widthPx, heightPx)
            if (current !== session) attach(current)
            return
        }
        if (!starting && !installing && startError == null) start(rows, cols, widthPx, heightPx)
    }

    private fun startFromView() {
        val grid = lastGrid ?: return
        start(grid[0], grid[1], grid[2], grid[3])
    }

    /**
     * Start a shell when there is none, and replace the fallback shell of a failed runtime
     * download once the runtime is available. An exited shell is left alone so its notice can
     * offer the restart; a running bash is never touched.
     *
     * Without the upgrade the panel would keep the Android `sh` of the failed download forever,
     * and `pkg` would stay "command not found" even though the runtime is installed by then.
     */
    private fun startIfNeeded() {
        if (starting || installing || startError != null || view.width <= 0) return
        val current = TerminalSessions.session
        val fallback = current != null && current.shellName == "sh" &&
            OpenCodeBootstrap.isPrefixReady()
        if (current != null && !fallback) return
        startFromView()
    }

    private fun start(rows: Int, cols: Int, widthPx: Int, heightPx: Int) {
        if (released) return
        if (!TerminalSessions.supported) {
            startError = activity.getString(R.string.opencode_terminal_no_native)
            renderNotice()
            return
        }
        // The Linux runtime is missing. Download it instead of handing the user Android's bare
        // shell, then start bash once the installation finished.
        if (!OpenCodeBootstrap.isPrefixReady()) {
            installRuntime(restart = false)
            return
        }
        runtimeError = null
        // A fallback shell may still be open when the runtime was installed by the app in the
        // meantime; replace it with bash now that the prefix is ready.
        val fallback = TerminalSessions.session?.shellName == "sh"
        launch(restart = fallback, rows = rows, cols = cols, widthPx = widthPx, heightPx = heightPx)
    }

    private fun launch(restart: Boolean, rows: Int, cols: Int, widthPx: Int, heightPx: Int) {
        starting = true
        startError = null
        renderNotice()
        executor.execute {
            val result = runCatching {
                if (restart) {
                    TerminalSessions.restart()
                } else {
                    TerminalSessions.start(rows, cols, widthPx, heightPx)
                }
            }
            activity.runOnUiThread {
                if (released) return@runOnUiThread
                starting = false
                result.onSuccess { attach(it) }
                    .onFailure { startError = it.message ?: it.javaClass.simpleName }
                renderNotice()
                renderSubtitle()
            }
        }
    }

    /**
     * Start the download of the Linux runtime and start (or restart) the shell when it is done.
     *
     * Opening the terminal is the user's way of asking for a working shell, so it is better to
     * fetch the runtime than to silently open Android's `sh`. When the installation fails the
     * fallback shell still opens, and the notice offers a retry.
     */
    private fun installRuntime(restart: Boolean) {
        pendingRestart = pendingRestart || restart || TerminalSessions.session != null
        if (installing) return

        installing = true
        startError = null
        runtimeError = null
        installProgress = null
        installDetail = null
        OpenCodeBootstrap.addInstallListener(installListener)
        renderNotice()
        // setupIfNeeded calls the callback on the UI thread when the installation finished,
        // successfully or not. It also queues the request when the app is already installing.
        OpenCodeBootstrap.setupIfNeeded(activity) { onRuntimeInstalled() }
    }

    private fun onRuntimeInstalled() {
        if (released) return
        installing = false
        OpenCodeBootstrap.removeInstallListener(installListener)
        val restart = pendingRestart
        pendingRestart = false
        installProgress = null
        installDetail = null

        if (OpenCodeBootstrap.isPrefixReady()) {
            if (restart) {
                launch(
                    restart = true,
                    rows = TerminalSessions.lastRows,
                    cols = TerminalSessions.lastCols,
                    widthPx = TerminalSessions.lastWidthPx,
                    heightPx = TerminalSessions.lastHeightPx,
                )
            } else {
                startFromView()
            }
            return
        }

        val state = OpenCodeBootstrap.getInstallState()
        runtimeError = state.error.ifBlank { state.message }
            .ifBlank { activity.getString(R.string.opencode_terminal_install_unknown) }
        renderNotice()
        // Keep the terminal usable while the runtime is missing, the notice offers a retry.
        if (restart) {
            launch(
                restart = true,
                rows = TerminalSessions.lastRows,
                cols = TerminalSessions.lastCols,
                widthPx = TerminalSessions.lastWidthPx,
                heightPx = TerminalSessions.lastHeightPx,
            )
        } else {
            val grid = lastGrid
            if (grid != null) {
                launch(false, grid[0], grid[1], grid[2], grid[3])
            } else {
                launch(
                    restart = false,
                    rows = TerminalSessions.lastRows,
                    cols = TerminalSessions.lastCols,
                    widthPx = TerminalSessions.lastWidthPx,
                    heightPx = TerminalSessions.lastHeightPx,
                )
            }
        }
    }

    private fun restart() {
        if (!TerminalSessions.supported) {
            startError = activity.getString(R.string.opencode_terminal_no_native)
            renderNotice()
            return
        }
        MaterialAlertDialogBuilder(activity)
            .setTitle(R.string.opencode_terminal_restart_title)
            .setMessage(R.string.opencode_terminal_restart_message)
            .setPositiveButton(R.string.opencode_terminal_restart) { _, _ -> beginRestart() }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun beginRestart() {
        if (released) return
        resetModifiers()
        if (!OpenCodeBootstrap.isPrefixReady()) {
            installRuntime(restart = true)
            return
        }
        launch(
            restart = true,
            rows = TerminalSessions.lastRows,
            cols = TerminalSessions.lastCols,
            widthPx = TerminalSessions.lastWidthPx,
            heightPx = TerminalSessions.lastHeightPx,
        )
    }

    private fun attach(next: TerminalSession?) {
        if (session !== next) {
            session?.removeListener(sessionListener)
            session = next
            next?.addListener(sessionListener)
            view.attach(next)
        }
        renderModifiers()
    }

    private fun renderNotice() {
        val current = session
        val message: String?
        val action: String?
        val busy: Boolean
        val progress: Int?
        when {
            installing -> {
                val detail = installDetail
                message = if (detail == null) {
                    activity.getString(R.string.opencode_terminal_installing)
                } else {
                    "${activity.getString(R.string.opencode_terminal_installing)} $detail"
                }
                action = null
                busy = true
                progress = installProgress
            }
            runtimeError != null -> {
                message = activity.getString(R.string.opencode_terminal_install_failed, runtimeError)
                action = activity.getString(R.string.opencode_terminal_install_retry)
                busy = false
                progress = null
            }
            starting -> {
                message = activity.getString(R.string.opencode_terminal_starting)
                action = null
                busy = true
                progress = null
            }
            startError != null -> {
                message = activity.getString(R.string.opencode_terminal_start_failed, startError)
                action = activity.getString(R.string.opencode_terminal_restart)
                busy = false
                progress = null
            }
            current == null -> {
                message = activity.getString(R.string.opencode_terminal_starting)
                action = null
                busy = true
                progress = null
            }
            current.exitCode != null -> {
                message = activity.getString(R.string.opencode_terminal_exited, current.exitCode ?: -1)
                action = activity.getString(R.string.opencode_terminal_restart)
                busy = false
                progress = null
            }
            else -> {
                message = null
                action = null
                busy = false
                progress = null
            }
        }
        if (message == null) {
            notice.visibility = View.GONE
        } else {
            notice.visibility = View.VISIBLE
            noticeText.text = message
            noticeProgress.visibility = if (busy) View.VISIBLE else View.GONE
            if (progress != null) {
                noticeProgress.isIndeterminate = false
                noticeProgress.progress = progress
            } else {
                noticeProgress.isIndeterminate = true
            }
            noticeAction.visibility = if (action == null) View.GONE else View.VISIBLE
            if (action != null) noticeAction.text = action
        }
        hint.visibility = if (current?.shellName == "sh") View.VISIBLE else View.GONE
    }

    private fun renderSubtitle() {
        val current = session
        val subtitle = when {
            current == null -> ""
            current.title.isNotEmpty() -> current.title
            else -> current.workingDirectory.orEmpty()
        }
        toolbar.subtitle = subtitle.ifEmpty { activity.getString(R.string.opencode_terminal_subtitle) }
    }

    private fun renderModifiers() {
        tintKey(ctrlKey, ctrl, activity.getString(R.string.opencode_terminal_key_ctrl))
        tintKey(altKey, alt, activity.getString(R.string.opencode_terminal_key_alt))
        tintKey(
            fnKey,
            if (fnOpen) Latch.ONCE else Latch.OFF,
            activity.getString(R.string.opencode_terminal_key_fn),
        )
    }

    /**
     * A modifier is off, armed for one key (primary) or locked on (tertiary with
     * a small lock badge). The three states are also spelled out for TalkBack.
     */
    private fun tintKey(key: TextView, latch: Latch, label: String) {
        val containerAttr = when (latch) {
            Latch.OFF -> com.google.android.material.R.attr.colorSecondaryContainer
            Latch.ONCE -> androidx.appcompat.R.attr.colorPrimary
            Latch.LOCKED -> com.google.android.material.R.attr.colorTertiary
        }
        val contentAttr = when (latch) {
            Latch.OFF -> com.google.android.material.R.attr.colorOnSecondaryContainer
            Latch.ONCE -> com.google.android.material.R.attr.colorOnPrimary
            Latch.LOCKED -> com.google.android.material.R.attr.colorOnTertiary
        }
        val container = MaterialColors.getColor(key, containerAttr)
        val content = MaterialColors.getColor(key, contentAttr)
        ViewCompat.setBackgroundTintList(key, ColorStateList.valueOf(container))
        key.setTextColor(content)

        // The badge sits above the label so a wide monospace label still fits
        // in the narrow, equally weighted keys of the extra row.
        val badge = if (latch == Latch.LOCKED) {
            val density = key.resources.displayMetrics.density
            ContextCompat.getDrawable(activity, R.drawable.ic_lock)?.mutate()?.apply {
                val size = (10 * density).roundToInt()
                setBounds(0, 0, size, size)
                DrawableCompat.setTint(this, content)
            }
        } else {
            null
        }
        key.setCompoundDrawables(null, badge, null, null)
        key.compoundDrawablePadding = if (badge != null) {
            key.resources.displayMetrics.density.roundToInt()
        } else {
            0
        }
        key.contentDescription = when (latch) {
            Latch.OFF -> label
            Latch.ONCE -> activity.getString(R.string.opencode_terminal_modifier_once, label)
            Latch.LOCKED -> activity.getString(R.string.opencode_terminal_modifier_locked, label)
        }
    }

    /** Clear sticky modifiers so a hidden or restarted shell never eats the next key. */
    private fun resetModifiers() {
        ctrl = Latch.OFF
        alt = Latch.OFF
        fnOpen = false
        fnScroll.visibility = View.GONE
        renderModifiers()
    }

    /** Material You colours: the terminal shares the palette of the native shell. */
    private fun resolveColors(): TerminalColors {
        fun color(attr: Int) = MaterialColors.getColor(panel, attr)
        val background = color(com.google.android.material.R.attr.colorSurfaceContainerLowest)
        val dark = ColorUtils.calculateLuminance(background) < 0.5
        val ansi = (if (dark) DARK_ANSI else LIGHT_ANSI)
            .map { (0xFF shl 24) or it }
            .toIntArray()
        return TerminalColors(
            background = background,
            foreground = color(com.google.android.material.R.attr.colorOnSurface),
            cursor = color(androidx.appcompat.R.attr.colorPrimary),
            cursorText = color(com.google.android.material.R.attr.colorOnPrimary),
            ansi = ansi,
        )
    }

    private fun consumeModifiers() {
        if (ctrl == Latch.ONCE) ctrl = Latch.OFF
        if (alt == Latch.ONCE) alt = Latch.OFF
        renderModifiers()
    }

    private fun bindKey(id: Int, action: () -> Unit) {
        activity.findViewById<TextView>(id).setOnClickListener {
            haptic(it)
            action()
            view.requestFocus()
        }
    }

    private fun bindChar(id: Int, codePoint: Int) {
        bindKey(id) { view.pressChar(codePoint) }
    }

    /**
     * A key that repeats while it is held, like an arrow on a hardware keyboard.
     * TalkBack still reaches it through [View.performClick].
     */
    private fun bindRepeatKey(id: Int, action: () -> Unit) {
        val key = activity.findViewById<TextView>(id)
        key.setOnClickListener {
            haptic(key)
            action()
            view.requestFocus()
        }
        key.setOnTouchListener { touched, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    touched.isPressed = true
                    haptic(touched)
                    action()
                    view.requestFocus()
                    stopRepeat()
                    repeatAction = action
                    activity.window?.decorView?.postDelayed(repeat, REPEAT_DELAY_MS)
                    true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    touched.isPressed = false
                    stopRepeat()
                    true
                }
                else -> false
            }
        }
    }

    private fun stopRepeat() {
        repeatAction = null
        activity.window?.decorView?.removeCallbacks(repeat)
    }

    private fun haptic(target: View) {
        target.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
    }

    private fun onMenuItem(item: MenuItem): Boolean {
        when (item.itemId) {
            R.id.action_terminal_copy -> copyOutput()
            R.id.action_terminal_paste -> pasteClipboard()
            R.id.action_terminal_keyboard -> view.showKeyboard()
            R.id.action_terminal_font_reset -> {
                view.fontSizeSp = DEFAULT_FONT_SIZE
                OpenCodeSettings.setTerminalFontSize(activity, DEFAULT_FONT_SIZE)
            }
            R.id.action_terminal_restart -> restart()
            else -> return false
        }
        return true
    }

    private fun copyOutput() {
        val current = session ?: return
        val text = current.transcript()
        if (text.isBlank()) {
            toast(R.string.opencode_terminal_empty)
            return
        }
        try {
            val clipboard = activity.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
            clipboard?.setPrimaryClip(
                ClipData.newPlainText(activity.getString(R.string.opencode_terminal_title), text),
            )
            // Android 13+ shows its own clipboard confirmation.
            if (Build.VERSION.SDK_INT < 33) toast(R.string.opencode_terminal_copied)
        } catch (e: Exception) {
            toast(R.string.opencode_terminal_copy_failed)
        }
    }

    private fun pasteClipboard() {
        val current = session ?: return
        val clipboard = activity.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
        val text = clipboard?.primaryClip
            ?.takeIf { it.itemCount > 0 }
            ?.getItemAt(0)
            ?.coerceToText(activity)
            ?.toString()
        if (text.isNullOrEmpty()) {
            toast(R.string.opencode_terminal_clipboard_empty)
            return
        }
        current.paste(text)
        view.requestFocus()
    }

    private fun toast(resource: Int) {
        Toast.makeText(activity, resource, Toast.LENGTH_SHORT).show()
    }

    private companion object {
        const val DEFAULT_FONT_SIZE = 13f
        const val REPEAT_DELAY_MS = 400L
        const val REPEAT_MS = 55L

        val DARK_ANSI = intArrayOf(
            0x45434A, 0xF28B82, 0x81C995, 0xFDD663, 0x8AB4F8, 0xD7AEFB, 0x78D9EC, 0xDADCE0,
            0x9AA0A6, 0xF6AEA9, 0xA8DAB5, 0xFDE293, 0xAECBFA, 0xE9D2FD, 0xA1E4F2, 0xFFFFFF,
        )
        val LIGHT_ANSI = intArrayOf(
            0x202124, 0xC5221F, 0x188038, 0x9A6700, 0x1A73E8, 0x9334E6, 0x007B83, 0x6E7176,
            0x80868B, 0xD93025, 0x1E8E3E, 0xB06000, 0x4285F4, 0xA142F4, 0x129EAF, 0x3C4043,
        )
    }
}
