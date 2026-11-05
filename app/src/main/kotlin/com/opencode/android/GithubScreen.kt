package com.opencode.android

import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Typeface
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.text.Editable
import android.text.TextWatcher
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.PopupMenu
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.button.MaterialButton
import com.google.android.material.color.MaterialColors
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.progressindicator.LinearProgressIndicator
import com.google.android.material.snackbar.Snackbar
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import com.opencode.android.OpenCodeGitHub.Repo
import java.io.File
import java.time.Duration
import java.time.Instant
import java.util.Locale
import java.util.concurrent.Executors

/**
 * Full-screen GitHub destination.
 *
 * The screen owns the whole device-login flow, the repository list and the local mapping of a
 * repository to a checkout. It talks to [OpenCodeGitHub] directly on its own executor so the
 * activity only has to coordinate which overlay is on top. Nothing is pushed implicitly: a clone
 * and a commit stay local until the user opens the Git changes screen and presses Push.
 */
class GithubScreen(
    private val activity: Activity,
    private val listener: Listener,
) {

    interface Listener {
        /** The GitHub screen is gone; the Files screen below it becomes interactive again. */
        fun onGithubClosed()

        /** The GitHub token was created or removed; the server has to be restarted. */
        fun onGitHubAuthChanged()

        /** A repository finished cloning to [directory]. */
        fun onCloneCompleted(directory: File)

        /** The user asked to browse [directory] in the Files screen. */
        fun onOpenDirectory(directory: File)

        /** The user asked to inspect the changes of the repository at [root]. */
        fun onOpenGitChanges(root: File)
    }

    private enum class State { CHECKING, PREREQUISITE, SIGNED_OUT, WAITING, SIGNED_IN }

    private enum class Filter { ALL, PRIVATE, PUBLIC }

    private enum class SortMode { UPDATED, NAME }

    private val panel: View = activity.findViewById(R.id.github_panel)
    private val toolbar: MaterialToolbar = activity.findViewById(R.id.github_toolbar)

    private val checking: View = activity.findViewById(R.id.github_checking)
    private val prerequisite: View = activity.findViewById(R.id.github_prereq)
    private val signedOut: View = activity.findViewById(R.id.github_signed_out)
    private val waiting: View = activity.findViewById(R.id.github_waiting)
    private val signedIn: View = activity.findViewById(R.id.github_signed_in)

    private val installProgress: LinearProgressIndicator =
        activity.findViewById(R.id.github_install_progress)
    private val installStatus: TextView = activity.findViewById(R.id.github_install_status)
    private val installButton: MaterialButton = activity.findViewById(R.id.github_install)
    private val installLog: TextView = activity.findViewById(R.id.github_install_log)
    private val installDetails: MaterialButton = activity.findViewById(R.id.github_install_details)
    private val installCopy: MaterialButton = activity.findViewById(R.id.github_install_copy)
    private val installRetry: MaterialButton = activity.findViewById(R.id.github_install_retry)

    private val signedOutError: TextView = activity.findViewById(R.id.github_signed_out_error)
    private val loginButton: MaterialButton = activity.findViewById(R.id.github_login)
    private val signedOutRetry: MaterialButton = activity.findViewById(R.id.github_signed_out_retry)
    private val publicUrlLayout: TextInputLayout =
        activity.findViewById(R.id.github_public_url_layout)
    private val publicUrl: TextInputEditText = activity.findViewById(R.id.github_public_url)
    private val publicClone: MaterialButton = activity.findViewById(R.id.github_public_clone)

    private val waitCode: TextView = activity.findViewById(R.id.github_wait_code)
    private val waitCopy: MaterialButton = activity.findViewById(R.id.github_wait_copy)
    private val waitLink: MaterialButton = activity.findViewById(R.id.github_wait_link)
    private val waitStatus: TextView = activity.findViewById(R.id.github_wait_status)
    private val waitRemaining: TextView = activity.findViewById(R.id.github_wait_remaining)
    private val waitCancel: MaterialButton = activity.findViewById(R.id.github_wait_cancel)
    private val waitNewCode: MaterialButton = activity.findViewById(R.id.github_wait_new_code)

    private val accountMonogram: TextView = activity.findViewById(R.id.github_account_monogram)
    private val accountUser: TextView = activity.findViewById(R.id.github_account_user)
    private val accountMeta: TextView = activity.findViewById(R.id.github_account_meta)
    private val accountChip: TextView = activity.findViewById(R.id.github_account_chip)
    private val reauthChip: TextView = activity.findViewById(R.id.github_reauth_chip)
    private val accountScope: MaterialButton = activity.findViewById(R.id.github_account_scope)
    private val accountLogout: MaterialButton = activity.findViewById(R.id.github_account_logout)

    private val panes: LinearLayout = activity.findViewById(R.id.github_panes)
    private val listPane: View = activity.findViewById(R.id.github_list_pane)
    private val detailPane: View = activity.findViewById(R.id.github_detail_pane)

    private val searchLayout: TextInputLayout = activity.findViewById(R.id.github_search_layout)
    private val search: TextInputEditText = activity.findViewById(R.id.github_search)
    private val filterAll: MaterialButton = activity.findViewById(R.id.github_filter_all)
    private val filterPrivate: MaterialButton = activity.findViewById(R.id.github_filter_private)
    private val filterPublic: MaterialButton = activity.findViewById(R.id.github_filter_public)
    private val sortButton: MaterialButton = activity.findViewById(R.id.github_sort)

    private val reposProgress: View = activity.findViewById(R.id.github_repos_progress)
    private val reposStatus: TextView = activity.findViewById(R.id.github_repos_status)
    private val repoList: RecyclerView = activity.findViewById(R.id.github_repo_list)
    private val reposSkeleton: View = activity.findViewById(R.id.github_repos_skeleton)
    private val reposEmpty: View = activity.findViewById(R.id.github_repos_empty)
    private val reposEmptyText: TextView = activity.findViewById(R.id.github_repos_empty_text)
    private val reposRefresh: MaterialButton = activity.findViewById(R.id.github_repos_refresh)
    private val reposError: View = activity.findViewById(R.id.github_repos_error)
    private val reposErrorText: TextView = activity.findViewById(R.id.github_repos_error_text)
    private val reposRetry: MaterialButton = activity.findViewById(R.id.github_repos_retry)
    private val reposCopy: MaterialButton = activity.findViewById(R.id.github_repos_copy)

    private val detailName: TextView = activity.findViewById(R.id.github_detail_name)
    private val detailChip: TextView = activity.findViewById(R.id.github_detail_chip)
    private val detailDescription: TextView = activity.findViewById(R.id.github_detail_desc)
    private val detailMeta: TextView = activity.findViewById(R.id.github_detail_meta)
    private val detailDownload: MaterialButton = activity.findViewById(R.id.github_detail_download)
    private val detailWeb: MaterialButton = activity.findViewById(R.id.github_detail_web)
    private val detailNotLocal: TextView = activity.findViewById(R.id.github_detail_not_local)
    private val detailPath: TextView = activity.findViewById(R.id.github_detail_path)
    private val detailStatus: TextView = activity.findViewById(R.id.github_detail_status)
    private val detailLocalActions: View = activity.findViewById(R.id.github_detail_local_actions)
    private val detailOpenFolder: MaterialButton =
        activity.findViewById(R.id.github_detail_open_folder)
    private val detailGit: MaterialButton = activity.findViewById(R.id.github_detail_git)
    private val detailPickLocal: MaterialButton =
        activity.findViewById(R.id.github_detail_pick_local)

    private val executor = Executors.newCachedThreadPool { runnable ->
        Thread(runnable, "opencode-github").apply { isDaemon = true }
    }
    private val handler = Handler(Looper.getMainLooper())

    private val adapter = RepoAdapter(
        onOpen = ::selectRepo,
        onMore = ::showRepoMenu,
    )

    private var state = State.CHECKING
    private var repos: List<Repo> = emptyList()
    private var selectedRepo: Repo? = null
    private var filter = Filter.ALL
    private var sortMode = SortMode.UPDATED
    private var query = ""

    private var reposLoaded = false
    private var reposLoading = false
    private var refreshingState = false
    private var installing = false
    private var loginInFlight = false
    private var cloneInFlight = false

    private var device: OpenCodeGitHub.DeviceCode? = null
    private var loginDeadline = 0L
    private var loginError: String? = null
    private var cloneHandle: OpenCodeGitHub.CloneHandle? = null
    private var cloneDialog: AlertDialog? = null
    private val cloneLog = StringBuilder()

    private var stateTicket = 0
    private var detailTicket = 0
    private var loginTicket = 0

    @Volatile
    private var released = false

    private val loginTicker = object : Runnable {
        override fun run() {
            if (released || state != State.WAITING || !loginInFlight) return
            val left = loginDeadline - SystemClock.uptimeMillis()
            if (left <= 0L) {
                waitRemaining.text = activity.getString(R.string.opencode_github_expired)
                waitNewCode.visibility = View.VISIBLE
                waitStatus.setText(R.string.opencode_github_login_expired)
                return
            }
            waitRemaining.text = activity.getString(
                R.string.opencode_github_remaining,
                formatDuration(left),
            )
            handler.postDelayed(this, 1000L)
        }
    }

    val isVisible: Boolean
        get() = panel.visibility == View.VISIBLE

    init {
        toolbar.setNavigationOnClickListener { goBack() }
        repoList.layoutManager = LinearLayoutManager(activity)
        repoList.adapter = adapter

        installButton.setOnClickListener { startInstall() }
        installRetry.setOnClickListener { startInstall() }
        installDetails.setOnClickListener { toggleInstallLog() }
        installCopy.setOnClickListener { copyInstallLog() }

        loginButton.setOnClickListener { startLogin() }
        signedOutRetry.setOnClickListener { refreshState() }
        publicClone.setOnClickListener { startPublicClone() }
        publicUrl.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
            override fun afterTextChanged(s: Editable?) {
                publicUrlLayout.error = null
            }
        })

        waitCopy.setOnClickListener { copyCode() }
        waitLink.setOnClickListener { openVerification() }
        waitCancel.setOnClickListener { cancelLogin() }
        waitNewCode.setOnClickListener { restartLogin() }

        accountScope.setOnClickListener { showScopeInfo() }
        accountLogout.setOnClickListener { confirmLogout() }

        search.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
            override fun afterTextChanged(s: Editable?) {
                query = s?.toString().orEmpty()
                renderRepos()
            }
        })
        filterAll.setOnClickListener { setFilter(Filter.ALL) }
        filterPrivate.setOnClickListener { setFilter(Filter.PRIVATE) }
        filterPublic.setOnClickListener { setFilter(Filter.PUBLIC) }
        sortButton.setOnClickListener {
            sortMode = if (sortMode == SortMode.UPDATED) SortMode.NAME else SortMode.UPDATED
            renderSort()
            renderRepos()
        }

        reposRefresh.setOnClickListener { refreshRepos(force = true) }
        reposRetry.setOnClickListener { refreshRepos(force = true) }
        reposCopy.setOnClickListener { copyReposError() }

        detailDownload.setOnClickListener { selectedRepo?.let { startClone(it) } }
        detailWeb.setOnClickListener { selectedRepo?.let { openWeb(it) } }
        detailOpenFolder.setOnClickListener {
            selectedRepo?.let { repo ->
                localDirectory(repo)?.let(listener::onOpenDirectory)
            }
        }
        detailGit.setOnClickListener {
            selectedRepo?.let { repo ->
                localDirectory(repo)?.let(listener::onOpenGitChanges)
            }
        }
        detailPickLocal.setOnClickListener { pickLocalFolder() }

        renderSort()
        renderFilters()
        updatePanes()
    }

    /* ------------------------------------------------------------------ lifecycle */

    fun show() {
        OverlayMotion.reveal(panel)
        applyLayoutMode()
        renderState()
        refreshState()
    }

    fun hide() {
        if (loginInFlight) cancelLogin()
        OverlayMotion.conceal(panel)
    }

    /** Back: close a detail pane first, then cancel a login, then leave GitHub. */
    fun goBack() {
        if (state == State.WAITING && loginInFlight) {
            cancelLogin()
            return
        }
        if (selectedRepo != null) {
            selectedRepo = null
            updatePanes()
            return
        }
        listener.onGithubClosed()
        hide()
    }

    fun release() {
        released = true
        stateTicket++
        detailTicket++
        if (loginInFlight) OpenCodeGitHub.cancelLogin()
        cloneHandle?.cancel()
        cloneDialog?.dismiss()
        handler.removeCallbacksAndMessages(null)
        executor.shutdownNow()
    }

    /** Called from `Activity.onConfigurationChanged`, which does not recreate the activity. */
    fun onConfigurationChanged() {
        applyLayoutMode()
    }

    /* ------------------------------------------------------------------ state */

    private fun refreshState() {
        if (released || refreshingState || loginInFlight || cloneInFlight) return
        refreshingState = true
        val ticket = ++stateTicket
        state = State.CHECKING
        renderState()
        executor.execute {
            val installed = OpenCodeGitHub.bothAvailable()
            val needsReauth = installed && OpenCodeGitHub.needsScopeUpgrade()
            val loggedIn = installed && OpenCodeGitHub.isLoggedIn()
            val user = if (loggedIn) OpenCodeGitHub.currentUser() else null
            activity.runOnUiThread {
                if (released || ticket != stateTicket) return@runOnUiThread
                if (activity.isFinishing || activity.isDestroyed) return@runOnUiThread
                refreshingState = false
                when {
                    !installed -> {
                        state = State.PREREQUISITE
                    }
                    !loggedIn -> {
                        state = State.SIGNED_OUT
                        if (needsReauth) {
                            signedOutError.setText(R.string.opencode_github_reauth)
                            signedOutError.visibility = View.VISIBLE
                        } else if (loginError != null) {
                            signedOutError.text = loginError
                            signedOutError.visibility = View.VISIBLE
                        } else {
                            signedOutError.visibility = View.GONE
                        }
                    }
                    else -> {
                        state = State.SIGNED_IN
                        loginError = null
                        bindAccount(user ?: "GitHub", needsReauth)
                        if (!reposLoaded) refreshRepos(force = true)
                    }
                }
                renderState()
            }
        }
    }

    private fun renderState() {
        checking.visibility = if (state == State.CHECKING) View.VISIBLE else View.GONE
        prerequisite.visibility = if (state == State.PREREQUISITE) View.VISIBLE else View.GONE
        signedOut.visibility = if (state == State.SIGNED_OUT) View.VISIBLE else View.GONE
        waiting.visibility = if (state == State.WAITING) View.VISIBLE else View.GONE
        signedIn.visibility = if (state == State.SIGNED_IN) View.VISIBLE else View.GONE
        if (state != State.SIGNED_IN) toolbar.subtitle = ""
        updatePanes()
    }

    private fun bindAccount(user: String, needsReauth: Boolean) {
        toolbar.subtitle = user
        accountMonogram.text = user.firstOrNull()?.uppercaseChar()?.toString() ?: "?"
        accountUser.text = user
        accountMeta.text = if (reposLoaded) {
            activity.getString(R.string.opencode_github_account_repos, repos.size)
        } else {
            activity.getString(R.string.opencode_github_connected)
        }
        accountChip.visibility = View.VISIBLE
        reauthChip.visibility = if (needsReauth) View.VISIBLE else View.GONE
    }

    /* ------------------------------------------------------------------ install */

    private fun startInstall() {
        if (installing || released) return
        installing = true
        state = State.PREREQUISITE
        renderState()
        installProgress.visibility = View.VISIBLE
        installStatus.visibility = View.VISIBLE
        installStatus.setText(R.string.opencode_github_installing)
        installButton.visibility = View.GONE
        installRetry.visibility = View.GONE
        installLog.visibility = View.GONE
        installCopy.visibility = View.GONE
        installDetails.visibility = View.GONE
        val log = StringBuilder()

        executor.execute {
            val result = OpenCodeGitHub.install(activity) { message ->
                handler.post {
                    if (released || activity.isFinishing || activity.isDestroyed) return@post
                    log.append(message).append('\n')
                    if (log.length > MAX_INSTALL_LOG) {
                        val kept = log.toString().takeLast(MAX_INSTALL_LOG)
                        log.setLength(0)
                        log.append(kept)
                    }
                    installStatus.text = message
                    installLog.text = log.toString().trimEnd('\n')
                }
            }
            handler.post {
                if (released || activity.isFinishing || activity.isDestroyed) return@post
                installing = false
                installProgress.visibility = View.GONE
                installLog.text = log.toString().trimEnd('\n')
                if (result.ok) {
                    installStatus.setText(R.string.opencode_github_install_done)
                    installStatus.visibility = View.VISIBLE
                    installButton.visibility = View.GONE
                    installRetry.visibility = View.GONE
                    installDetails.visibility = if (installLog.text.isBlank()) View.GONE else View.VISIBLE
                    installCopy.visibility = View.GONE
                    refreshState()
                } else {
                    val detail = failureDetail(result.output).ifEmpty { "exit ${result.exitCode}" }
                    installStatus.text =
                        activity.getString(R.string.opencode_github_install_failed, detail)
                    installStatus.visibility = View.VISIBLE
                    installButton.visibility = View.GONE
                    installRetry.visibility = View.VISIBLE
                    installDetails.visibility = if (installLog.text.isBlank()) View.GONE else View.VISIBLE
                    installCopy.visibility = if (installLog.text.isBlank()) View.GONE else View.VISIBLE
                    installLog.visibility = if (installLog.text.isBlank()) View.GONE else View.VISIBLE
                }
            }
        }
    }

    private fun toggleInstallLog() {
        val show = installLog.visibility != View.VISIBLE
        installLog.visibility = if (show) View.VISIBLE else View.GONE
        installDetails.setText(
            if (show) R.string.opencode_github_details_hide
            else R.string.opencode_github_details_show,
        )
    }

    private fun copyInstallLog() {
        val text = installLog.text?.toString().orEmpty()
        if (text.isBlank()) return
        copyToClipboard("github-install", text)
        snack(activity.getString(R.string.opencode_github_copied))
    }

    /* ------------------------------------------------------------------ login */

    private fun startLogin() {
        if (loginInFlight || released) return
        if (!OpenCodeGitHub.bothAvailable()) {
            state = State.PREREQUISITE
            renderState()
            return
        }
        val session = OpenCodeGitHub.beginLogin()
        val ticket = ++loginTicket
        loginInFlight = true
        loginError = null
        state = State.WAITING
        renderState()
        waitCode.text = ""
        waitCopy.visibility = View.GONE
        waitLink.visibility = View.GONE
        waitRemaining.text = ""
        waitNewCode.visibility = View.GONE
        waitCancel.visibility = View.VISIBLE
        waitStatus.setText(R.string.opencode_settings_github_logging_in)

        executor.execute {
            try {
                val requested = OpenCodeGitHub.requestDeviceCode()
                if (released || ticket != loginTicket) return@execute
                device = requested
                loginDeadline = SystemClock.uptimeMillis() + requested.expiresIn * 1000L
                handler.post {
                    if (released || activity.isFinishing || activity.isDestroyed) return@post
                    if (ticket != loginTicket || !loginInFlight) return@post
                    waitCode.text = requested.userCode
                    waitCopy.visibility = View.VISIBLE
                    waitLink.visibility = View.VISIBLE
                    waitStatus.setText(R.string.opencode_github_waiting)
                    waitRemaining.text = activity.getString(
                        R.string.opencode_github_remaining,
                        formatDuration(requested.expiresIn * 1000L),
                    )
                    handler.removeCallbacks(loginTicker)
                    handler.postDelayed(loginTicker, 1000L)
                }
                OpenCodeGitHub.completeLogin(requested, session)
                handler.post {
                    if (released || activity.isFinishing || activity.isDestroyed) return@post
                    if (ticket != loginTicket) return@post
                    loginInFlight = false
                    device = null
                    handler.removeCallbacks(loginTicker)
                    state = State.SIGNED_IN
                    reposLoaded = false
                    repos = emptyList()
                    bindAccount(OpenCodeGitHub.currentUser() ?: "GitHub", needsReauth = false)
                    renderState()
                    snack(activity.getString(R.string.opencode_github_login_ok, accountUser.text))
                    listener.onGitHubAuthChanged()
                    refreshRepos(force = true)
                }
            } catch (e: OpenCodeGitHub.LoginCancelledException) {
                handler.post {
                    if (released || activity.isFinishing || activity.isDestroyed) return@post
                    if (ticket != loginTicket || !loginInFlight) return@post
                    loginInFlight = false
                    device = null
                    handler.removeCallbacks(loginTicker)
                    loginError = activity.getString(R.string.opencode_github_login_cancelled)
                    state = State.SIGNED_OUT
                    renderState()
                }
            } catch (e: Exception) {
                handler.post {
                    if (released || activity.isFinishing || activity.isDestroyed) return@post
                    if (ticket != loginTicket) return@post
                    loginInFlight = false
                    device = null
                    handler.removeCallbacks(loginTicker)
                    loginError = activity.getString(
                        R.string.opencode_github_login_failed,
                        e.message ?: e.toString(),
                    )
                    state = State.SIGNED_OUT
                    renderState()
                    snack(loginError.orEmpty())
                }
            }
        }
    }

    /**
     * Ask for a fresh device code after the current one expired. The previous session and its
     * polling loop are invalidated first, so a late response from GitHub cannot win the race.
     */
    private fun restartLogin() {
        if (released) return
        if (loginInFlight) {
            OpenCodeGitHub.cancelLogin()
            loginTicket++
            loginInFlight = false
            device = null
            handler.removeCallbacks(loginTicker)
        }
        startLogin()
    }

    private fun cancelLogin() {
        if (!loginInFlight) return
        OpenCodeGitHub.cancelLogin()
        loginTicket++
        loginInFlight = false
        device = null
        handler.removeCallbacks(loginTicker)
        loginError = activity.getString(R.string.opencode_github_login_cancelled)
        state = State.SIGNED_OUT
        renderState()
    }

    private fun copyCode() {
        val code = waitCode.text?.toString().orEmpty()
        if (code.isBlank()) return
        copyToClipboard("github-device-code", code)
        snack(activity.getString(R.string.opencode_github_copied))
    }

    private fun openVerification() {
        val code = device
        if (code == null) {
            snack(activity.getString(R.string.opencode_github_open_failed))
            return
        }
        try {
            activity.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(code.verificationUri)))
        } catch (e: Exception) {
            snack(activity.getString(R.string.opencode_github_open_failed))
        }
    }

    private fun confirmLogout() {
        MaterialAlertDialogBuilder(activity)
            .setTitle(R.string.opencode_github_logout_title)
            .setMessage(R.string.opencode_github_logout_message)
            .setPositiveButton(R.string.opencode_github_logout) { _, _ -> logout() }
            .setNegativeButton(R.string.opencode_cancel, null)
            .show()
    }

    private fun logout() {
        executor.execute {
            OpenCodeGitHub.logout()
            activity.runOnUiThread {
                if (released || activity.isFinishing || activity.isDestroyed) return@runOnUiThread
                repos = emptyList()
                reposLoaded = false
                selectedRepo = null
                query = ""
                search.setText("")
                state = State.SIGNED_OUT
                signedOutError.visibility = View.GONE
                renderState()
                listener.onGitHubAuthChanged()
            }
        }
    }

    private fun showScopeInfo() {
        MaterialAlertDialogBuilder(activity)
            .setTitle(R.string.opencode_github_scope_title)
            .setMessage(R.string.opencode_github_scope_message)
            .setPositiveButton(R.string.opencode_files_close, null)
            .show()
    }

    /* ------------------------------------------------------------------ repositories */

    private fun refreshRepos(force: Boolean) {
        if (released || reposLoading) return
        if (!force && reposLoaded) return
        if (!OpenCodeGitHub.bothAvailable()) {
            state = State.PREREQUISITE
            renderState()
            return
        }
        reposLoading = true
        reposError.visibility = View.GONE
        reposProgress.visibility = View.VISIBLE
        reposStatus.setText(R.string.opencode_github_repos_loading)
        reposSkeleton.visibility = if (repos.isEmpty()) View.VISIBLE else View.GONE
        renderRepos()
        executor.execute {
            val result = OpenCodeGitHub.listRepos()
            val parsed = if (result.ok) OpenCodeGitHub.parseRepos(result.output) else emptyList()
            activity.runOnUiThread {
                if (released || activity.isFinishing || activity.isDestroyed) return@runOnUiThread
                reposLoading = false
                reposProgress.visibility = View.GONE
                reposSkeleton.visibility = View.GONE
                if (!result.ok) {
                    reposError.visibility = View.VISIBLE
                    reposErrorText.text = activity.getString(
                        R.string.opencode_github_repos_failed,
                        failureDetail(result.output).ifEmpty { "exit ${result.exitCode}" },
                    )
                    renderRepos()
                    return@runOnUiThread
                }
                repos = parsed
                reposLoaded = true
                reposError.visibility = View.GONE
                reposStatus.text =
                    activity.getString(R.string.opencode_github_account_repos, repos.size)
                accountMeta.text =
                    activity.getString(R.string.opencode_github_account_repos, repos.size)
                renderRepos()
                selectedRepo?.let { current ->
                    repos.firstOrNull { it.name == current.name }?.let { fresh ->
                        selectedRepo = fresh
                        renderDetail(fresh)
                    }
                }
            }
        }
    }

    private fun renderRepos() {
        if (state != State.SIGNED_IN) {
            adapter.submitList(emptyList())
            return
        }
        val filtered = repos.asSequence()
            .filter { repo ->
                when (filter) {
                    Filter.ALL -> true
                    Filter.PRIVATE -> repo.privateRepo
                    Filter.PUBLIC -> !repo.privateRepo
                }
            }
            .filter { repo ->
                query.isBlank() ||
                    repo.name.contains(query, ignoreCase = true) ||
                    repo.description.contains(query, ignoreCase = true) ||
                    repo.primaryLanguage.contains(query, ignoreCase = true)
            }
            .sortedWith(
                when (sortMode) {
                    SortMode.UPDATED -> compareByDescending { parseInstant(it.updatedAt) ?: Instant.EPOCH }
                    SortMode.NAME -> compareBy { it.name.lowercase(Locale.ROOT) }
                },
            )
            .map { RepoRow(it, isLocal(it)) }
            .toList()
        adapter.submitList(filtered)
        val empty = !reposLoading && filtered.isEmpty()
        reposEmpty.visibility = if (empty) View.VISIBLE else View.GONE
        reposSkeleton.visibility = if (reposLoading && repos.isEmpty()) View.VISIBLE else View.GONE
        if (empty) {
            reposEmptyText.setText(R.string.opencode_github_repos_empty)
        }
        if (!reposLoading && reposLoaded) {
            reposStatus.text =
                activity.getString(R.string.opencode_github_account_repos, filtered.size)
        }
    }

    private fun setFilter(next: Filter) {
        filter = next
        renderFilters()
        renderRepos()
    }

    private fun renderFilters() {
        styleFilter(filterAll, filter == Filter.ALL)
        styleFilter(filterPrivate, filter == Filter.PRIVATE)
        styleFilter(filterPublic, filter == Filter.PUBLIC)
    }

    private fun styleFilter(button: MaterialButton, active: Boolean) {
        button.alpha = if (active) 1f else 0.72f
        button.typeface = Typeface.create(
            button.typeface,
            if (active) Typeface.BOLD else Typeface.NORMAL,
        )
    }

    private fun renderSort() {
        sortButton.setText(
            if (sortMode == SortMode.UPDATED) R.string.opencode_github_sort_updated
            else R.string.opencode_github_sort_name,
        )
    }

    private fun showRepoMenu(repo: Repo, anchor: View) {
        val popup = PopupMenu(activity, anchor)
        popup.menuInflater.inflate(R.menu.menu_repo_actions, popup.menu)
        val local = isLocal(repo)
        popup.menu.findItem(R.id.action_repo_open_folder)?.isVisible = local
        popup.menu.findItem(R.id.action_repo_download)?.isVisible = true
        popup.setOnMenuItemClickListener { item ->
            when (item.itemId) {
                R.id.action_repo_download -> startClone(repo)
                R.id.action_repo_open_folder -> localDirectory(repo)?.let(listener::onOpenDirectory)
                R.id.action_repo_web -> openWeb(repo)
                R.id.action_repo_copy_url -> {
                    copyToClipboard("repo-url", repo.url)
                    snack(activity.getString(R.string.opencode_github_copied))
                }
                else -> return@setOnMenuItemClickListener false
            }
            true
        }
        popup.show()
    }

    private fun copyReposError() {
        val text = reposErrorText.text?.toString().orEmpty()
        if (text.isBlank()) return
        copyToClipboard("github-repos-error", text)
        snack(activity.getString(R.string.opencode_github_copied))
    }

    /* ------------------------------------------------------------------ detail */

    private fun selectRepo(repo: Repo) {
        selectedRepo = repo
        updatePanes()
        renderDetail(repo)
    }

    private fun renderDetail(repo: Repo) {
        detailName.text = repo.name
        detailDescription.text = repo.description.ifBlank {
            activity.getString(R.string.opencode_github_repo_no_description)
        }
        detailChip.text = chipText(repo)
        detailChip.background = ContextCompat.getDrawable(
            activity,
            if (repo.isArchived) R.drawable.bg_chip_warn else R.drawable.bg_chip,
        )
        detailChip.setTextColor(
            MaterialColors.getColor(
                activity,
                com.google.android.material.R.attr.colorOnSecondaryContainer,
                ContextCompat.getColor(activity, R.color.oc_text),
            ),
        )
        detailMeta.text = detailMetaText(repo)
        detailDownload.setText(R.string.opencode_github_repo_download)
        detailWeb.setText(R.string.opencode_github_repo_web)

        val stored = storedLocalPath(repo)
        val local = localDirectory(repo)
        if (local == null) {
            detailNotLocal.visibility = View.VISIBLE
            detailNotLocal.setText(
                if (stored != null) R.string.opencode_github_detail_local_missing
                else R.string.opencode_github_detail_not_local,
            )
            detailPath.visibility = View.GONE
            detailStatus.visibility = View.GONE
            detailLocalActions.visibility = View.GONE
        } else {
            detailNotLocal.visibility = View.GONE
            detailPath.visibility = View.VISIBLE
            detailPath.text = local.absolutePath
            detailStatus.visibility = View.VISIBLE
            detailStatus.setText(R.string.opencode_github_detail_loading)
            detailLocalActions.visibility = View.VISIBLE
            loadLocalStatus(repo, local)
        }
    }

    private fun loadLocalStatus(repo: Repo, local: File) {
        val ticket = ++detailTicket
        executor.execute {
            val status = OpenCodeGitHub.status(local)
            activity.runOnUiThread {
                if (released || ticket != detailTicket) return@runOnUiThread
                if (activity.isFinishing || activity.isDestroyed) return@runOnUiThread
                if (selectedRepo?.name != repo.name) return@runOnUiThread
                if (status == null) {
                    detailStatus.setText(R.string.opencode_github_detail_status_unknown)
                    return@runOnUiThread
                }
                val parts = ArrayList<String>()
                parts.add(status.branch)
                if (status.isClean) {
                    parts.add(activity.getString(R.string.opencode_files_git_clean))
                } else {
                    parts.add(
                        activity.getString(
                            R.string.opencode_files_git_changes,
                            status.changes.size,
                        ),
                    )
                }
                if (status.ahead > 0) {
                    parts.add(activity.getString(R.string.opencode_files_git_ahead, status.ahead))
                }
                detailStatus.text = parts.joinToString(" · ")
            }
        }
    }

    private fun pickLocalFolder() {
        val repo = selectedRepo ?: return
        val start = localDirectory(repo) ?: storedLocalPath(repo)?.let(::File)
        DirectoryPickerSheet(
            activity,
            start = start,
            title = activity.getString(R.string.opencode_github_detail_pick_local),
        ) { picked ->
            val root = OpenCodeGitHub.findRepoRoot(picked)
            if (root == null) {
                snack(activity.getString(R.string.opencode_github_detail_not_repo))
                return@DirectoryPickerSheet
            }
            OpenCodeSettings.setRepoPath(activity, repo.name, root.absolutePath)
            snack(activity.getString(R.string.opencode_github_detail_picked))
            renderRepos()
            renderDetail(repo)
        }.show()
    }

    /* ------------------------------------------------------------------ clone */

    private fun startClone(repo: Repo) {
        val suggested = repo.name.substringAfterLast('/')
        startCloneFlow(repo.url, suggested, repo.name)
    }

    private fun startPublicClone() {
        val raw = publicUrl.text?.toString()?.trim().orEmpty()
        if (raw.isEmpty()) {
            publicUrlLayout.error = activity.getString(R.string.opencode_github_url_required)
            return
        }
        publicUrlLayout.error = null
        val url = OpenCodeGitHub.normalizeCloneUrl(raw)
        val key = repoKey(url)
        val suggested = key.substringAfterLast('/').ifEmpty {
            url.trimEnd('/').substringAfterLast('/').removeSuffix(".git")
        }
        startCloneFlow(url, suggested, key)
    }

    private fun startCloneFlow(url: String, suggestedName: String, key: String) {
        if (cloneInFlight) return
        val start = OpenCodeSettings.lastCloneDir(activity)?.let(::File)
        DirectoryPickerSheet(
            activity,
            start = start,
            title = activity.getString(R.string.opencode_github_clone_pick_title),
        ) { directory ->
            promptCloneName(directory, suggestedName, url, key)
        }.show()
    }

    private fun promptCloneName(
        directory: File,
        suggestedName: String,
        url: String,
        key: String,
    ) {
        val content = LayoutInflater.from(activity)
            .inflate(R.layout.dialog_text_input, null, false)
        val layout = content.findViewById<TextInputLayout>(R.id.dialog_input_layout)
        val edit = content.findViewById<TextInputEditText>(R.id.dialog_input)
        layout.hint = activity.getString(R.string.opencode_github_clone_name_hint)
        edit.setText(suggestedName)
        edit.setSelection(edit.length())

        val dialog = MaterialAlertDialogBuilder(activity)
            .setTitle(R.string.opencode_github_clone_name_title)
            .setView(content)
            .setPositiveButton(R.string.opencode_github_download, null)
            .setNegativeButton(R.string.opencode_cancel, null)
            .create()
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val name = edit.text?.toString()?.trim().orEmpty()
                val error = when {
                    name.isEmpty() -> activity.getString(R.string.opencode_files_name_required)
                    !FileBrowser.isValidName(name) ->
                        activity.getString(R.string.opencode_files_name_invalid)
                    File(directory, name).exists() ->
                        activity.getString(R.string.opencode_github_clone_name_exists, name)
                    !directory.canWrite() ->
                        activity.getString(R.string.opencode_github_clone_dir_error)
                    else -> null
                }
                if (error != null) {
                    layout.error = error
                    return@setOnClickListener
                }
                layout.error = null
                dialog.dismiss()
                startClone(url, File(directory, name), key)
            }
        }
        dialog.show()
    }

    private fun startClone(url: String, destination: File, key: String) {
        if (cloneInFlight || released) return
        cloneInFlight = true
        cloneLog.setLength(0)
        val handle = OpenCodeGitHub.CloneHandle()
        cloneHandle = handle

        val content = LayoutInflater.from(activity)
            .inflate(R.layout.dialog_progress, null, false)
        val message: TextView = content.findViewById(R.id.progress_message)
        val logView: TextView = content.findViewById(R.id.progress_log)
        message.text = activity.getString(R.string.opencode_github_clone_progress)
        val dialog = MaterialAlertDialogBuilder(activity)
            .setTitle(R.string.opencode_github_repo_download)
            .setView(content)
            .setNegativeButton(R.string.opencode_github_clone_cancel) { _, _ -> handle.cancel() }
            .setCancelable(false)
            .create()
        cloneDialog = dialog
        dialog.show()

        executor.execute {
            val result = OpenCodeGitHub.clone(
                repoUrl = url,
                destination = destination,
                onLine = { line ->
                    handler.post {
                        if (released || !cloneInFlight) return@post
                        cloneLog.append(line).append('\n')
                        if (cloneLog.length > MAX_CLONE_LOG) {
                            val kept = cloneLog.toString().takeLast(MAX_CLONE_LOG)
                            cloneLog.setLength(0)
                            cloneLog.append(kept)
                        }
                        logView.visibility = View.VISIBLE
                        logView.text = cloneLog.toString().trimEnd('\n')
                    }
                },
                handle = handle,
            )
            activity.runOnUiThread {
                if (released || activity.isFinishing || activity.isDestroyed) return@runOnUiThread
                cloneInFlight = false
                cloneHandle = null
                cloneDialog = null
                if (dialog.isShowing) dialog.dismiss()
                if (result.ok) {
                    OpenCodeSettings.setRepoPath(activity, key, destination.absolutePath)
                    OpenCodeSettings.setLastCloneDir(
                        activity,
                        destination.parentFile?.absolutePath.orEmpty(),
                    )
                    snack(activity.getString(R.string.opencode_github_clone_ok, destination.name))
                    renderRepos()
                    selectedRepo?.let(::renderDetail)
                    listener.onCloneCompleted(destination)
                } else if (handle.cancelled) {
                    snack(activity.getString(R.string.opencode_github_clone_cancelled))
                    executor.execute { FileBrowser.delete(destination) }
                } else {
                    snack(
                        activity.getString(
                            R.string.opencode_github_clone_failed,
                            failureDetail(result.output).ifEmpty { "exit ${result.exitCode}" },
                        ),
                    )
                    executor.execute { FileBrowser.delete(destination) }
                }
            }
        }
    }

    /* ------------------------------------------------------------------ pane layout */

    private fun applyLayoutMode() {
        updatePanes()
    }

    private fun updatePanes() {
        val wide = activity.resources.configuration.screenWidthDp >= WIDE_SCREEN_DP
        panes.orientation = if (wide) LinearLayout.HORIZONTAL else LinearLayout.VERTICAL
        val showDetail = selectedRepo != null
        if (wide) {
            listPane.visibility = View.VISIBLE
            detailPane.visibility = if (showDetail) View.VISIBLE else View.GONE
            listPane.layoutParams = LinearLayout.LayoutParams(
                0,
                ViewGroup.LayoutParams.MATCH_PARENT,
                1f,
            )
            detailPane.layoutParams = LinearLayout.LayoutParams(
                0,
                ViewGroup.LayoutParams.MATCH_PARENT,
                1f,
            )
        } else {
            listPane.visibility = if (showDetail) View.GONE else View.VISIBLE
            detailPane.visibility = if (showDetail) View.VISIBLE else View.GONE
            listPane.layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                0,
                1f,
            )
            detailPane.layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                0,
                1f,
            )
        }
    }

    /* ------------------------------------------------------------------ rows */

    private fun isLocal(repo: Repo): Boolean = localDirectory(repo) != null

    private fun chipText(repo: Repo, local: Boolean = isLocal(repo)): String = when {
        local -> activity.getString(R.string.opencode_github_repo_local)
        repo.isArchived -> activity.getString(R.string.opencode_github_repo_archived)
        repo.privateRepo -> activity.getString(R.string.opencode_github_repo_private)
        else -> activity.getString(R.string.opencode_github_repo_public)
    }

    private fun metaText(repo: Repo): String {
        val parts = ArrayList<String>()
        if (repo.primaryLanguage.isNotBlank()) parts.add(repo.primaryLanguage)
        if (repo.isFork) parts.add(activity.getString(R.string.opencode_github_repo_fork))
        val updated = relativeTime(repo.updatedAt)
        if (updated.isNotEmpty()) {
            parts.add(activity.getString(R.string.opencode_github_repo_updated, updated))
        }
        return parts.joinToString(" · ")
    }

    private fun detailMetaText(repo: Repo): String {
        val parts = ArrayList<String>()
        parts.add(
            activity.getString(
                if (repo.privateRepo) R.string.opencode_github_repo_private
                else R.string.opencode_github_repo_public,
            ),
        )
        if (repo.primaryLanguage.isNotBlank()) parts.add(repo.primaryLanguage)
        if (repo.isFork) parts.add(activity.getString(R.string.opencode_github_repo_fork))
        if (repo.isArchived) parts.add(activity.getString(R.string.opencode_github_repo_archived))
        val updated = relativeTime(repo.updatedAt)
        if (updated.isNotEmpty()) {
            parts.add(activity.getString(R.string.opencode_github_repo_updated, updated))
        }
        return parts.joinToString(" · ")
    }

    private fun relativeTime(raw: String): String {
        val instant = parseInstant(raw) ?: return ""
        val minutes = Duration.between(instant, Instant.now()).toMinutes().coerceAtLeast(0)
        return when {
            minutes < 24 * 60 -> activity.getString(R.string.opencode_github_time_today)
            minutes < 48 * 60 -> activity.getString(R.string.opencode_github_time_yesterday)
            minutes < 30L * 24 * 60 ->
                activity.getString(R.string.opencode_github_time_days, (minutes / (24 * 60)).toInt())
            else ->
                activity.getString(R.string.opencode_github_time_months, (minutes / (30L * 24 * 60)).toInt())
        }
    }

    private fun parseInstant(raw: String): Instant? {
        if (raw.isBlank()) return null
        return try {
            Instant.parse(raw)
        } catch (e: Exception) {
            try {
                java.time.OffsetDateTime.parse(raw).toInstant()
            } catch (e: Exception) {
                null
            }
        }
    }

    private fun localDirectory(repo: Repo): File? {
        val raw = storedLocalPath(repo) ?: return null
        val file = File(raw)
        return if (file.isDirectory) file else null
    }

    private fun storedLocalPath(repo: Repo): String? =
        OpenCodeSettings.repoPath(activity, repo.name)?.takeIf { it.isNotEmpty() }

    private fun repoKey(url: String): String {
        var value = url.trim()
        value = value.removePrefix("https://github.com/")
        value = value.removePrefix("http://github.com/")
        value = value.removePrefix("git@github.com:")
        value = value.trimEnd('/').removeSuffix(".git")
        return if (value.contains('/')) value else ""
    }

    /* ------------------------------------------------------------------ helpers */

    private fun openWeb(repo: Repo) {
        try {
            activity.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(repo.webUrl)))
        } catch (e: Exception) {
            snack(activity.getString(R.string.opencode_github_open_failed))
        }
    }

    private fun copyToClipboard(label: String, text: String) {
        try {
            val manager =
                activity.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
            manager?.setPrimaryClip(ClipData.newPlainText(label, text))
        } catch (e: Exception) {
            // The clipboard may be unavailable; the caller only uses it as a convenience.
        }
    }

    private fun formatDuration(millis: Long): String {
        val total = (millis / 1000L).coerceAtLeast(0)
        val minutes = total / 60
        val seconds = total % 60
        return String.format(Locale.US, "%d:%02d", minutes, seconds)
    }

    private fun failureDetail(output: String): String {
        val line = output.lineSequence()
            .map { it.trim() }
            .lastOrNull { it.isNotEmpty() }
            ?: return ""
        return if (line.length > 220) line.take(220) + "…" else line
    }

    private fun snack(message: String) {
        Snackbar.make(panel, message, Snackbar.LENGTH_LONG).show()
    }

    /** A repository plus the local-state bit the row paints; diffing keeps the list cheap. */
    private data class RepoRow(val repo: Repo, val local: Boolean)

    private inner class RepoAdapter(
        private val onOpen: (Repo) -> Unit,
        private val onMore: (Repo, View) -> Unit,
    ) : ListAdapter<RepoRow, RepoAdapter.Holder>(RepoDiff) {

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
            val view = LayoutInflater.from(parent.context)
                .inflate(R.layout.item_repo, parent, false)
            return Holder(view)
        }

        override fun onBindViewHolder(holder: Holder, position: Int) {
            holder.bind(getItem(position))
        }

        inner class Holder(view: View) : RecyclerView.ViewHolder(view) {

            private val icon: ImageView = view.findViewById(R.id.repo_icon)
            private val name: TextView = view.findViewById(R.id.repo_name)
            private val description: TextView = view.findViewById(R.id.repo_desc)
            private val meta: TextView = view.findViewById(R.id.repo_meta)
            private val chip: TextView = view.findViewById(R.id.repo_chip)
            private val more: ImageButton = view.findViewById(R.id.repo_more)

            fun bind(row: RepoRow) {
                val repo = row.repo
                val local = row.local
                name.text = repo.name
                description.text = repo.description.ifBlank {
                    activity.getString(R.string.opencode_github_repo_no_description)
                }
                meta.text = metaText(repo)
                meta.visibility = if (meta.text.isNullOrEmpty()) View.GONE else View.VISIBLE
                chip.text = chipText(repo, local)
                chip.background = ContextCompat.getDrawable(
                    activity,
                    when {
                        local -> R.drawable.bg_chip_ok
                        repo.isArchived -> R.drawable.bg_chip_warn
                        else -> R.drawable.bg_chip
                    },
                )
                chip.setTextColor(
                    when {
                        local -> ContextCompat.getColor(activity, R.color.oc_success)
                        repo.isArchived -> ContextCompat.getColor(activity, R.color.oc_warning)
                        else -> MaterialColors.getColor(
                            activity,
                            com.google.android.material.R.attr.colorOnSecondaryContainer,
                            ContextCompat.getColor(activity, R.color.oc_text),
                        )
                    },
                )
                icon.setImageResource(
                    if (repo.privateRepo) R.drawable.ic_lock else R.drawable.ic_git_repo,
                )
                itemView.setOnClickListener { onOpen(repo) }
                more.setOnClickListener { anchor -> onMore(repo, anchor) }
            }
        }
    }

    private object RepoDiff : DiffUtil.ItemCallback<RepoRow>() {

        override fun areItemsTheSame(oldItem: RepoRow, newItem: RepoRow): Boolean =
            oldItem.repo.name == newItem.repo.name

        override fun areContentsTheSame(oldItem: RepoRow, newItem: RepoRow): Boolean =
            oldItem == newItem
    }

    private companion object {
        const val WIDE_SCREEN_DP = 720
        const val MAX_INSTALL_LOG = 12_000
        const val MAX_CLONE_LOG = 8_000
    }
}
