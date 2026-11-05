package com.opencode.android

import android.app.Activity
import android.os.Handler
import android.os.Looper
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageButton
import android.widget.PopupMenu
import android.widget.ProgressBar
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.button.MaterialButton
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.materialswitch.MaterialSwitch
import com.google.android.material.snackbar.Snackbar
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import java.io.File
import java.util.concurrent.Executors

/**
 * Full-screen Git changes destination.
 *
 * It shows the working tree of one repository, stages individual paths and commits. A commit is
 * local: the Push button is the only action that sends commits to GitHub. The auto-commit switch
 * is the same preference the editor and the Files screen read, so it can be changed here too.
 */
class GitChangesScreen(
    private val activity: Activity,
    private val listener: Listener,
) {

    interface Listener {
        /** The screen is gone; the destination below it becomes interactive again. */
        fun onGitChangesClosed()

        /** The user asked to edit [file] in the in-app editor. */
        fun onOpenFile(file: File)

        /** The user asked to browse [directory] in the Files screen. */
        fun onOpenDirectory(directory: File)

        /** The user needs to sign in, so leave this screen and open the GitHub destination. */
        fun onOpenGitHub()
    }

    private val panel: View = activity.findViewById(R.id.git_panel)
    private val toolbar: MaterialToolbar = activity.findViewById(R.id.git_toolbar)
    private val statusText: TextView = activity.findViewById(R.id.git_status)
    private val errorText: TextView = activity.findViewById(R.id.git_error)
    private val list: RecyclerView = activity.findViewById(R.id.git_change_list)
    private val empty: View = activity.findViewById(R.id.git_empty)
    private val progress: ProgressBar = activity.findViewById(R.id.git_progress)
    private val commitCard: View = activity.findViewById(R.id.git_commit_card)
    private val messageLayout: TextInputLayout = activity.findViewById(R.id.git_message_layout)
    private val message: TextInputEditText = activity.findViewById(R.id.git_message)
    private val autoSwitch: MaterialSwitch = activity.findViewById(R.id.git_auto)
    private val commitAllSwitch: MaterialSwitch = activity.findViewById(R.id.git_commit_all)
    private val pushButton: MaterialButton = activity.findViewById(R.id.git_push)
    private val commitButton: MaterialButton = activity.findViewById(R.id.git_commit)

    private val executor = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "opencode-git-changes").apply { isDaemon = true }
    }
    private val handler = Handler(Looper.getMainLooper())

    private val adapter = ChangeAdapter()

    private var root: File? = null
    private var status: OpenCodeGitHub.GitStatus? = null
    private var loading = false
    private var working = false
    private var suppressAuto = false
    private var ticket = 0

    @Volatile
    private var released = false

    val isVisible: Boolean
        get() = panel.visibility == View.VISIBLE

    init {
        toolbar.setNavigationOnClickListener { goBack() }
        list.layoutManager = LinearLayoutManager(activity)
        list.adapter = adapter
        commitAllSwitch.setOnCheckedChangeListener { _, checked ->
            if (suppressAuto) return@setOnCheckedChangeListener
            commitAllSwitch.setText(
                if (checked) R.string.opencode_git_commit_all
                else R.string.opencode_git_commit_staged_only,
            )
            renderButtons()
        }
        autoSwitch.setOnCheckedChangeListener { _, checked ->
            if (suppressAuto) return@setOnCheckedChangeListener
            OpenCodeSettings.setGitAutoCommit(activity, checked)
        }
        commitButton.setOnClickListener { commit() }
        pushButton.setOnClickListener { push() }
    }

    /* ------------------------------------------------------------------ lifecycle */

    fun show(repoRoot: File, after: (() -> Unit)? = null) {
        // A status read for the previous root may still be in flight. Invalidate it before the
        // new root is installed, otherwise its result would render in this repository.
        if (loading && !working) {
            ticket++
            loading = false
        }
        root = repoRoot
        OverlayMotion.reveal(panel)
        toolbar.subtitle = repoRoot.name
        errorText.visibility = View.GONE
        if (message.text.isNullOrBlank()) {
            message.setText(activity.getString(R.string.opencode_files_git_message_default))
        }
        suppressAuto = true
        autoSwitch.isChecked = OpenCodeSettings.gitAutoCommit(activity)
        suppressAuto = false
        renderButtons()
        refresh(after = after)
    }

    fun hide() {
        ticket++
        OverlayMotion.conceal(panel)
    }

    fun goBack() {
        if (working) return
        listener.onGitChangesClosed()
        hide()
    }

    fun release() {
        released = true
        ticket++
        handler.removeCallbacksAndMessages(null)
        executor.shutdownNow()
    }

    /* ------------------------------------------------------------------ status */

    fun refresh(after: (() -> Unit)? = null) {
        val repo = root ?: return
        if (released || loading || working) {
            after?.invoke()
            return
        }
        loading = true
        val request = ++ticket
        errorText.visibility = View.GONE
        progress.visibility = View.VISIBLE
        statusText.setText(R.string.opencode_git_changes_loading)
        renderButtons()
        executor.execute {
            val next = OpenCodeGitHub.status(repo)
            activity.runOnUiThread {
                if (released || request != ticket) return@runOnUiThread
                if (activity.isFinishing || activity.isDestroyed) return@runOnUiThread
                loading = false
                progress.visibility = View.GONE
                if (next == null) {
                    errorText.text = activity.getString(
                        R.string.opencode_git_error,
                        activity.getString(R.string.opencode_github_detail_status_unknown),
                    )
                    errorText.visibility = View.VISIBLE
                    statusText.text = ""
                    adapter.submit(emptyList())
                    empty.visibility = View.VISIBLE
                } else {
                    status = next
                    adapter.submit(next.changes)
                    renderSummary(next)
                }
                renderButtons()
                after?.invoke()
            }
        }
    }

    private fun renderSummary(current: OpenCodeGitHub.GitStatus) {
        statusText.text = if (current.isClean) {
            activity.getString(R.string.opencode_git_clean_title)
        } else {
            activity.getString(
                R.string.opencode_git_summary,
                current.changes.size,
                current.ahead,
            )
        }
        empty.visibility = if (current.isClean) View.VISIBLE else View.GONE
        commitCard.visibility = if (current.isClean && current.ahead == 0) View.GONE else View.VISIBLE
        pushButton.visibility = if (current.ahead > 0) View.VISIBLE else View.GONE
    }

    private fun renderButtons() {
        val current = status
        val busy = loading || working
        val hasStaged = current?.changes?.any { it.isStaged } == true
        commitButton.isEnabled = !busy && current != null && !current.isClean &&
            (commitAllSwitch.isChecked || hasStaged)
        pushButton.isEnabled = !busy && current != null && current.ahead > 0
        messageLayout.isEnabled = !busy
        commitAllSwitch.isEnabled = !busy
        autoSwitch.isEnabled = !busy
    }

    /* ------------------------------------------------------------------ commit / push */

    private fun commit() {
        val repo = root ?: return
        if (working) return
        val text = message.text?.toString()?.trim().orEmpty()
        if (text.isEmpty()) {
            messageLayout.error = activity.getString(R.string.opencode_files_git_message_required)
            return
        }
        messageLayout.error = null
        val all = commitAllSwitch.isChecked
        val current = status
        if (!all && current != null && current.changes.none { it.isStaged }) {
            snack(activity.getString(R.string.opencode_git_no_staged))
            return
        }
        working = true
        errorText.visibility = View.GONE
        statusText.setText(R.string.opencode_files_git_committing)
        progress.visibility = View.VISIBLE
        renderButtons()
        val request = ++ticket
        executor.execute {
            val result = OpenCodeGitHub.commit(repo, text, all = all)
            val next = if (result.ok) OpenCodeGitHub.status(repo) else null
            activity.runOnUiThread {
                if (released || request != ticket) return@runOnUiThread
                if (activity.isFinishing || activity.isDestroyed) return@runOnUiThread
                working = false
                progress.visibility = View.GONE
                if (result.ok) {
                    status = next
                    next?.let {
                        adapter.submit(it.changes)
                        renderSummary(it)
                    }
                    message.setText("")
                    snack(activity.getString(R.string.opencode_files_git_committed))
                } else {
                    showError(
                        activity.getString(
                            R.string.opencode_files_git_commit_failed,
                            failureDetail(result.output),
                        ),
                    )
                }
                renderButtons()
            }
        }
    }

    private fun push() {
        val repo = root ?: return
        if (working) return
        if (OpenCodeGitHub.storedToken() == null) {
            showError(
                activity.getString(R.string.opencode_files_git_not_logged_in),
                githubAction = true,
            )
            return
        }
        working = true
        errorText.visibility = View.GONE
        statusText.setText(R.string.opencode_files_git_pushing)
        progress.visibility = View.VISIBLE
        renderButtons()
        val request = ++ticket
        executor.execute {
            val result = OpenCodeGitHub.push(repo)
            val next = if (result.ok) OpenCodeGitHub.status(repo) else null
            activity.runOnUiThread {
                if (released || request != ticket) return@runOnUiThread
                if (activity.isFinishing || activity.isDestroyed) return@runOnUiThread
                working = false
                progress.visibility = View.GONE
                if (result.ok) {
                    status = next
                    next?.let {
                        adapter.submit(it.changes)
                        renderSummary(it)
                    }
                    snack(activity.getString(R.string.opencode_files_git_pushed))
                } else {
                    showError(
                        activity.getString(
                            R.string.opencode_files_git_push_failed,
                            failureDetail(result.output),
                        ),
                        githubAction = true,
                    )
                }
                renderButtons()
            }
        }
    }

    private fun showError(message: String, githubAction: Boolean = false) {
        errorText.text = message
        errorText.visibility = View.VISIBLE
        val bar = Snackbar.make(panel, message, Snackbar.LENGTH_LONG)
        if (githubAction) {
            bar.setAction(R.string.opencode_files_git_connect) { listener.onOpenGitHub() }
        }
        bar.show()
    }

    /* ------------------------------------------------------------------ rows */

    private fun toggleStage(change: OpenCodeGitHub.GitChange) {
        val repo = root ?: return
        if (working) return
        working = true
        progress.visibility = View.VISIBLE
        renderButtons()
        val request = ++ticket
        val stage = !change.isStaged
        executor.execute {
            val result = if (stage) {
                OpenCodeGitHub.stage(repo, change.path)
            } else {
                OpenCodeGitHub.unstage(repo, change.path)
            }
            val next = if (result.ok) OpenCodeGitHub.status(repo) else null
            activity.runOnUiThread {
                if (released || request != ticket) return@runOnUiThread
                if (activity.isFinishing || activity.isDestroyed) return@runOnUiThread
                working = false
                progress.visibility = View.GONE
                if (!result.ok) {
                    showError(
                        activity.getString(
                            R.string.opencode_git_error,
                            failureDetail(result.output),
                        ),
                    )
                } else if (next != null) {
                    status = next
                    adapter.submit(next.changes)
                    renderSummary(next)
                }
                renderButtons()
            }
        }
    }

    private fun showDiff(change: OpenCodeGitHub.GitChange) {
        val repo = root ?: return
        if (working) return
        executor.execute {
            val result = OpenCodeGitHub.diff(repo, change.path)
            val text = if (result.ok) result.output.trimEnd() else ""
            activity.runOnUiThread {
                if (released || !isVisible) return@runOnUiThread
                if (activity.isFinishing || activity.isDestroyed) return@runOnUiThread
                val content = LayoutInflater.from(activity)
                    .inflate(R.layout.dialog_diff, null, false)
                val body: TextView = content.findViewById(R.id.diff_text)
                body.text = if (text.isBlank()) {
                    activity.getString(R.string.opencode_git_diff_empty)
                } else {
                    text
                }
                MaterialAlertDialogBuilder(activity)
                    .setTitle(activity.getString(R.string.opencode_git_diff_title, change.path))
                    .setView(content)
                    .setPositiveButton(R.string.opencode_files_close, null)
                    .show()
            }
        }
    }

    private fun openFile(change: OpenCodeGitHub.GitChange) {
        val repo = root ?: return
        val file = File(repo, change.path)
        if (!file.exists()) {
            showDiff(change)
            return
        }
        if (file.isDirectory) {
            listener.onOpenDirectory(file)
            return
        }
        if (!FileActions.isTextFile(file)) {
            showDiff(change)
            return
        }
        listener.onOpenFile(file)
    }

    private fun showMenu(change: OpenCodeGitHub.GitChange, anchor: View) {
        val popup = PopupMenu(activity, anchor)
        popup.menuInflater.inflate(R.menu.menu_git_change, popup.menu)
        popup.menu.findItem(R.id.action_change_stage)?.isVisible = !change.isStaged
        popup.menu.findItem(R.id.action_change_unstage)?.isVisible = change.isStaged
        popup.menu.findItem(R.id.action_change_open)?.isVisible =
            root?.let { File(it, change.path).isFile } == true
        popup.setOnMenuItemClickListener { item ->
            when (item.itemId) {
                R.id.action_change_diff -> showDiff(change)
                R.id.action_change_stage,
                R.id.action_change_unstage -> toggleStage(change)
                R.id.action_change_open -> openFile(change)
                R.id.action_change_folder -> {
                    val repo = root ?: return@setOnMenuItemClickListener false
                    val target = File(repo, change.path)
                    listener.onOpenDirectory(
                        if (target.isDirectory) target else target.parentFile ?: repo,
                    )
                }
                else -> return@setOnMenuItemClickListener false
            }
            true
        }
        popup.show()
    }

    private fun failureDetail(output: String): String {
        val line = output.lineSequence()
            .map { it.trim() }
            .lastOrNull { it.isNotEmpty() }
            ?: return "?"
        return if (line.length > 220) line.take(220) + "…" else line
    }

    private fun snack(text: String) {
        Snackbar.make(panel, text, Snackbar.LENGTH_LONG).show()
    }

    private val OpenCodeGitHub.GitChange.isStaged: Boolean
        get() = indexStatus != ' ' && indexStatus != '?'

    private inner class ChangeAdapter : RecyclerView.Adapter<ChangeAdapter.Holder>() {

        private var items: List<OpenCodeGitHub.GitChange> = emptyList()

        fun submit(next: List<OpenCodeGitHub.GitChange>) {
            items = next
            notifyDataSetChanged()
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
            val view = LayoutInflater.from(parent.context)
                .inflate(R.layout.item_git_change, parent, false)
            return Holder(view)
        }

        override fun getItemCount(): Int = items.size

        override fun onBindViewHolder(holder: Holder, position: Int) {
            holder.bind(items[position])
        }

        inner class Holder(view: View) : RecyclerView.ViewHolder(view) {

            private val code: TextView = view.findViewById(R.id.change_code)
            private val name: TextView = view.findViewById(R.id.change_name)
            private val dir: TextView = view.findViewById(R.id.change_dir)
            private val more: ImageButton = view.findViewById(R.id.change_more)

            fun bind(change: OpenCodeGitHub.GitChange) {
                val raw = "${change.indexStatus}${change.workTreeStatus}".trim()
                code.text = when {
                    change.indexStatus == '?' && change.workTreeStatus == '?' -> "A"
                    raw.isEmpty() -> "?"
                    else -> raw
                }
                code.background = ContextCompat.getDrawable(
                    activity,
                    when {
                        change.indexStatus == '?' -> R.drawable.bg_chip_ok
                        change.isStaged -> R.drawable.bg_chip_ok
                        change.indexStatus == 'D' || change.workTreeStatus == 'D' ->
                            R.drawable.bg_chip_warn
                        else -> R.drawable.bg_chip
                    },
                )
                name.text = change.path.substringAfterLast('/')
                val parent = change.path.substringBeforeLast('/', "")
                val staged = activity.getString(
                    if (change.isStaged) R.string.opencode_git_staged
                    else R.string.opencode_git_unstaged,
                )
                dir.text = if (parent.isEmpty()) staged else "$parent · $staged"
                itemView.setOnClickListener {
                    if (change.isStaged) showDiff(change) else openFile(change)
                }
                itemView.setOnLongClickListener {
                    showMenu(change, more)
                    true
                }
                more.setOnClickListener { showMenu(change, it) }
            }
        }
    }
}
