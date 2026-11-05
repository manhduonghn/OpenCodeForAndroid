package com.opencode.android

import android.Manifest
import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.res.ColorStateList
import android.content.res.Configuration
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import android.provider.Settings
import android.text.Spannable
import android.text.SpannableString
import android.text.SpannableStringBuilder
import android.text.style.ForegroundColorSpan
import android.text.style.RelativeSizeSpan
import android.util.Base64
import android.util.Log
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.HttpAuthHandler
import android.webkit.JsResult
import android.webkit.RenderProcessGoneDetail
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import android.webkit.WebViewDatabase
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.color.MaterialColors
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.materialswitch.MaterialSwitch
import com.google.android.material.snackbar.Snackbar
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.nio.charset.StandardCharsets
import java.util.concurrent.Executors

/**
 * Minimal WebView shell for the OpenCode server. The actual OpenCode server runs as a foreground
 * service on 127.0.0.1:49374 and this activity renders it.
 *
 * The first start installs the Linux toolchain and OpenCode, which takes several minutes on a
 * phone. The activity shows the progress of [OpenCodeBootstrap] instead of an empty screen and
 * only loads the web UI once the server really accepts connections.
 *
 * Opening `/` alone is not enough: the OpenCode web app is a client that asks for a server
 * address and password. The app mints a short-lived code with `POST /api/pair`, redeems it for a
 * session token and loads `/?auth_token=<base64(user:token)>`, exactly the link format
 * `opencode pair` prints. The token is also stored as the session cookie, so reloads stay
 * authenticated.
 *
 * The WebView itself lives in [OpenCodeWebViewModel]: a rotation (or any other configuration
 * change) recreates this activity, but not the web app, so the page, the scroll position and the
 * open session survive.
 *
 * The settings panel groups everything that does not belong to the chat itself: the session that
 * is open, updates, the wake lock, shared storage, the downloaded files and GitHub.
 */
class OpenCodeWebActivity : AppCompatActivity(), OpenCodeBootstrap.InstallListener,
    OpenCodeWebViewModel.Ui {

    private val viewModel: OpenCodeWebViewModel by viewModels()

    private lateinit var root: View
    private lateinit var toolbar: MaterialToolbar

    private lateinit var webView: WebView
    private lateinit var webContainer: FrameLayout
    private lateinit var statusPanel: View
    private lateinit var bootCard: View
    private lateinit var statusStage: TextView
    private lateinit var statusTitle: TextView
    private lateinit var statusDetail: TextView
    private lateinit var statusProgress: ProgressBar
    private lateinit var statusRetry: Button
    private lateinit var statusDetailsToggle: Button
    private lateinit var statusCopy: Button
    private lateinit var statusLog: TextView
    private lateinit var bootStepIcons: List<ImageView>
    private lateinit var bootStepStates: List<TextView>

    /** Which boot step the native panel is showing, and the step that failed (if any). */
    private var bootStage = BootStage.ENVIRONMENT
    private var bootFailure: BootStage? = null

    /** Connection state painted into the toolbar subtitle. */
    private var connectionState = ConnectionState.STARTING
    private var subtitlePainted = false

    private lateinit var settingsPanel: View
    private lateinit var filesScreen: FilesScreen
    /** GitHub controls live in a sheet; the view stays inflated so the settings summary can drive it. */
    private lateinit var githubSheetView: View
    private var githubSheet: BottomSheetDialog? = null
    private lateinit var updatesCurrent: TextView
    private lateinit var updatesStatus: TextView
    private lateinit var updatesCheck: Button
    private lateinit var updatesUpdate: Button
    private lateinit var updatesAuto: MaterialSwitch
    private lateinit var wakeLockSwitch: MaterialSwitch
    private lateinit var wakeLockStatus: TextView
    private lateinit var batteryOptimizationButton: Button
    private lateinit var bootStartSwitch: MaterialSwitch
    private lateinit var watchdogSwitch: MaterialSwitch
    private lateinit var keepAliveHelpButton: Button
    private lateinit var storageStatus: TextView
    private lateinit var storageGrant: Button
    private lateinit var storagePick: Button
    private lateinit var storagePaths: LinearLayout
    private lateinit var githubProgress: ProgressBar
    private lateinit var githubChip: TextView
    private lateinit var githubStatus: TextView
    private lateinit var githubSheetStatus: TextView
    private lateinit var githubInstallStatus: TextView
    private lateinit var githubInstall: Button
    private lateinit var githubLogin: Button
    private lateinit var githubLogout: Button
    private lateinit var githubUser: TextView
    private lateinit var githubCode: TextView
    private lateinit var githubCopy: Button
    private lateinit var githubLink: Button
    private lateinit var githubHint: TextView
    private lateinit var githubCloneSection: View
    private lateinit var githubCloneUrl: EditText
    private lateinit var githubClone: Button
    private lateinit var githubCloneStatus: TextView
    private lateinit var githubRepoSection: View
    private lateinit var githubRepoStatus: TextView
    private lateinit var githubRepoList: LinearLayout
    private lateinit var infoAppVersion: TextView
    private lateinit var infoOpencodeVersion: TextView

    private val handler = Handler(Looper.getMainLooper())

    /**
     * The reachability check opens a socket to 127.0.0.1. Doing that on the main thread throws
     * [android.os.NetworkOnMainThreadException], so the probe runs here and the result is posted
     * back to the UI thread.
     */
    private val probeExecutor = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "opencode-web-probe").apply { isDaemon = true }
    }

    /** Network and process work of the settings panel, never the UI thread. */
    private val backgroundExecutor = Executors.newCachedThreadPool { runnable ->
        Thread(runnable, "opencode-settings").apply { isDaemon = true }
    }

    private var probeInFlight = false
    private var destroyed = false
    private var waitingForInstall = false
    private var lastServiceStart = 0L

    /**
     * Failed probes since the last successful load. The server needs about half a second to open
     * its port after a cold start, so the first retries are much shorter than the interval used
     * while an installation is still running.
     */
    private var probeFailures = 0

    /** How often the pairing code was requested for the current server start. */
    private var pairAttempts = 0

    /**
     * True once the web UI is on screen. It is stored in the ViewModel, so a rotation does not
     * start the pairing and the load of the page again.
     */
    private var pageLoaded: Boolean
        get() = viewModel.pageLoaded
        set(value) {
            viewModel.pageLoaded = value
        }

    /** HTTP Basic password of the local OpenCode server, see [OpenCodeBootstrap.getServerPassword]. */
    private var serverPassword: String
        get() = viewModel.serverPassword
        set(value) {
            viewModel.serverPassword = value
        }

    /** Number of failed loads of the web UI, the automatic retry stops after a few of them. */
    private var loadFailures = 0

    /** Session that has to be opened after the start page finished loading. */
    private var pendingSessionId: String?
        get() = viewModel.pendingSessionId
        set(value) {
            viewModel.pendingSessionId = value
        }

    private var updateChecking = false
    private var storageLinksInFlight = false
    private var githubRefreshing = false
    private var githubReposLoading = false
    private var githubReposLoaded = false
    private var githubInstalling = false
    private var githubLoginInFlight = false
    private var githubLoggingOut = false
    private var githubCloning = false
    private var githubUserCode = ""
    private var githubVerificationUrl = ""
    private val githubCloneLog = StringBuilder()

    /** Guards the listeners while the switches are filled from the preferences. */
    private var suppressSettingsCallbacks = false

    private val storagePermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { _ ->
        if (destroyed) return@registerForActivityResult
        renderStorage()
        if (::filesScreen.isInitialized) filesScreen.onStoragePermissionResult()
    }

    /**
     * The system file picker of an `<input type="file">` of the web app. The callback of the
     * WebView lives in the ViewModel, so a rotation while the picker is open keeps working.
     */
    private val fileChooserLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) { result ->
        viewModel.onFileChooserResult(
            if (result.resultCode == Activity.RESULT_OK) fileChooserUris(result.data) else null,
        )
    }

    private val poll: Runnable = object : Runnable {
        override fun run() {
            if (destroyed || probeInFlight || probeExecutor.isShutdown) return
            probeInFlight = true
            probeExecutor.execute {
                val reachable = serverReachable()
                val sessionToken = if (reachable) requestSessionToken() else null
                handler.post {
                    probeInFlight = false
                    if (destroyed) return@post
                    if (reachable) {
                        probeFailures = 0
                        if (sessionToken == null && pairAttempts < 6) {
                            // The server accepted the connection but not the pairing request yet,
                            // retry briefly before falling back to the bare web app.
                            pairAttempts++
                            handler.postDelayed(poll, 500)
                            return@post
                        }
                        loadUi(sessionToken)
                        return@post
                    }
                    updateProgress()
                    if (OpenCodeBootstrap.getInstallState().failed) return@post
                    // A cold start opens the port within ~0.5 s: retry fast for the first three
                    // seconds, then fall back to the slow interval so a long installation does
                    // not wake the app every 150 ms.
                    val delay = if (probeFailures < 20) {
                        probeFailures++
                        150L
                    } else {
                        700L
                    }
                    handler.postDelayed(poll, delay)
                }
            }
        }
    }

    /**
     * The web UI was loaded but never reported a successful API call. A cold server or a broken
     * page must not leave the launcher on an endless spinner, so fall back to the retry loop.
     */
    private val bootTimeout: Runnable = object : Runnable {
        override fun run() {
            if (destroyed || !pageLoaded || viewModel.webReady) return
            Log.w(TAG, "The web UI did not become ready in time, reloading the launcher")
            failUi(
                getString(R.string.opencode_ui_unavailable),
                getString(R.string.opencode_ui_boot_timeout),
            )
        }
    }

    /**
     * After the transport guard has exhausted its retries, keep the native reconnect screen up
     * for a moment. A successful retry cancels this task; otherwise the page is paired again.
     */
    private val transportWatchdog: Runnable = object : Runnable {
        override fun run() {
            if (destroyed || !pageLoaded) return
            Log.i(TAG, "The web UI lost the server transport, pairing again")
            reconnectToRestartedServer()
        }
    }

    /**
     * Fallback for WebViews without document-start scripts: probe the same API the web app calls
     * and reveal the page only after the server answers it.
     */
    private val readinessFallback: Runnable = object : Runnable {
        override fun run() {
            if (destroyed || !pageLoaded || viewModel.webReady) return
            val secret = serverPassword
            if (secret.isEmpty()) return
            backgroundExecutor.execute {
                val ready = OpenCodeApi.sessionList(PORT, secret, 2500) != null
                handler.post {
                    if (destroyed || !pageLoaded || viewModel.webReady) return@post
                    if (ready) {
                        viewModel.webReady = true
                        showWebUi()
                    }
                }
            }
        }
    }

    /**
     * Keep the page paired with the server while the activity is visible.
     *
     * The server can be replaced behind the back of the UI at any time: the heartbeat or the
     * notification's Restart action, a crash, the phantom process killer. The page then holds the
     * cookie and the pairing token of the old server and only receives 401s, so it has to be
     * paired again instead of showing a dead chat until the next manual reload.
     */
    private val generationWatch: Runnable = object : Runnable {
        override fun run() {
            if (destroyed) return
            if (pageLoaded && !OpenCodeSettings.stoppedByUser(this@OpenCodeWebActivity)) {
                val generation = OpenCodeService.serverGeneration()
                if (generation != 0 && generation != viewModel.pairedServerGeneration) {
                    Log.i(TAG, "The server was replaced while the UI was open, pairing again")
                    reconnectToRestartedServer()
                }
            }
            handler.postDelayed(this, GENERATION_CHECK_MS)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        setContentView(R.layout.activity_main)
        bindViews()
        applyEdgeToEdge()
        applyContentWidths()
        bindSettings()
        applySystemBarAppearance()
        showBootStage(BootStage.ENVIRONMENT)
        updateStatusSubtitle(ConnectionState.STARTING)

        // The WebView belongs to the ViewModel, so after a rotation it is the same instance with
        // the same page: it only has to be moved from the view hierarchy of the previous activity
        // into this one. Nothing is reloaded.
        webView = viewModel.webView(this)
        (webView.parent as? ViewGroup)?.removeView(webView)
        webContainer.addView(
            webView,
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT,
            ),
        )

        configureWebView()
        applyWebTheme()

        // Pre-populate the credentials of the realm the server announces
        // (WWW-Authenticate: Basic realm="Secure Area"), so the WebView answers the very first
        // challenge of the page and of its fetch/XHR calls without a system dialog.
        serverPassword = OpenCodeBootstrap.getServerPassword(this)
        try {
            @Suppress("DEPRECATION")
            WebViewDatabase.getInstance(this).setHttpAuthUsernamePassword(
                "127.0.0.1", "Secure Area", OpenCodeBootstrap.SERVER_USER, serverPassword,
            )
        } catch (e: Exception) {
            // The callback below is the primary mechanism, this is only a head start.
        }

        statusRetry.setOnClickListener { retryInstall() }
        statusDetailsToggle.setOnClickListener { toggleBootDetails() }
        statusCopy.setOnClickListener { copyBootError() }
        statusDetailsToggle.visibility = View.GONE
        statusCopy.visibility = View.GONE

        // Restore the progress of an installation that was interrupted by a process death.
        OpenCodeBootstrap.restoreState()
        OpenCodeBootstrap.addInstallListener(this)

        // The notification of the foreground service opens the session it was showing. The id is
        // kept in the ViewModel until the start page finished loading, so a rotation during the
        // load does not lose it.
        val requestedSession = consumeSessionIntent(intent)
        if (requestedSession != null) pendingSessionId = requestedSession

        // A rotation only re-attaches the WebView that the ViewModel kept, the UI stays where it
        // was and the poll (which pairs again and reloads the page) is not started at all. A
        // rotation while the page is still booting keeps the native boot screen until the web app
        // reports its first successful API call.
        val pageAlive = viewModel.pageLoaded && viewModel.webReady && !viewModel.transportDown
        if (pageAlive) {
            statusPanel.visibility = View.GONE
            webView.visibility = View.VISIBLE
            handler.removeCallbacks(bootTimeout)
            updateStatusSubtitle(ConnectionState.RUNNING)
        } else {
            statusPanel.visibility = View.VISIBLE
            // INVISIBLE, not GONE: the WebView must keep its layout and keep running its scripts
            // while the native boot panel covers it. GONE can make some WebView versions defer
            // the first layout/paint and with it the very API call the boot gate waits for.
            webView.visibility = View.INVISIBLE
            if (viewModel.pageLoaded) {
                showBootPanel(
                    getString(R.string.opencode_loading_ui),
                    getString(R.string.opencode_loading_ui_detail),
                )
                showBootStage(BootStage.UI)
                updateStatusSubtitle(ConnectionState.RECONNECTING)
                handler.removeCallbacks(bootTimeout)
                handler.postDelayed(bootTimeout, BOOT_TIMEOUT_MS)
                if (!viewModel.bootGuardInstalled) {
                    handler.removeCallbacks(readinessFallback)
                    handler.postDelayed(readinessFallback, 500)
                }
            } else {
                showBootStage(BootStage.ENVIRONMENT)
                updateStatusSubtitle(ConnectionState.STARTING)
            }
        }

        // Make sure the OpenCode server is running before the WebView talks to it. Opening the
        // activity is an explicit start and overrides a previous Stop.
        OpenCodeSettings.setStoppedByUser(this, false)
        startServerService()
        // The watchdog only needs to be scheduled once, ensureScheduled is idempotent.
        OpenCodeWatchdog.ensureScheduled(this)
        probeFailures = 0
        if (!viewModel.pageLoaded) {
            handler.post(poll)
        } else if (pageAlive) {
            if (requestedSession != null) {
                openSession(requestedSession)
            } else {
                viewModel.installSessionWatcher()
            }
        } else {
            // The page exists in the retained WebView and is still booting. The boot bridge (or
            // the boot timeout) takes over; starting the pairing poll here would load it twice.
            handler.removeCallbacks(poll)
        }
    }

    private fun bindViews() {
        root = findViewById(R.id.root)
        toolbar = findViewById(R.id.toolbar)
        webContainer = findViewById(R.id.web_container)
        statusPanel = findViewById(R.id.status_panel)
        bootCard = findViewById(R.id.boot_card)
        statusStage = findViewById(R.id.status_stage)
        statusTitle = findViewById(R.id.status_title)
        statusDetail = findViewById(R.id.status_detail)
        statusProgress = findViewById(R.id.status_progress)
        statusRetry = findViewById(R.id.status_retry)
        statusDetailsToggle = findViewById(R.id.status_details_toggle)
        statusCopy = findViewById(R.id.status_copy)
        statusLog = findViewById(R.id.status_log)
        bootStepIcons = listOf(
            findViewById(R.id.boot_step1_icon),
            findViewById(R.id.boot_step2_icon),
            findViewById(R.id.boot_step3_icon),
        )
        bootStepStates = listOf(
            findViewById(R.id.boot_step1_state),
            findViewById(R.id.boot_step2_state),
            findViewById(R.id.boot_step3_state),
        )

        settingsPanel = findViewById(R.id.settings_panel)
        // GitHub details are a progressive-disclosure sheet opened from the Settings card.
        githubSheetView = layoutInflater.inflate(R.layout.sheet_github, null, false)
        updatesCurrent = findViewById(R.id.updates_current)
        updatesStatus = findViewById(R.id.updates_status)
        updatesCheck = findViewById(R.id.updates_check)
        updatesUpdate = findViewById(R.id.updates_update)
        updatesAuto = findViewById(R.id.updates_auto)
        wakeLockSwitch = findViewById(R.id.wakelock_switch)
        wakeLockStatus = findViewById(R.id.wakelock_status)
        batteryOptimizationButton = findViewById(R.id.battery_optimization_button)
        bootStartSwitch = findViewById(R.id.boot_start_switch)
        watchdogSwitch = findViewById(R.id.watchdog_switch)
        keepAliveHelpButton = findViewById(R.id.keepalive_help_button)
        storageStatus = findViewById(R.id.storage_status)
        storageGrant = findViewById(R.id.storage_grant)
        storagePick = findViewById(R.id.storage_pick)
        storagePaths = findViewById(R.id.storage_paths)
        githubProgress = findViewById(R.id.github_progress)
        githubChip = findViewById(R.id.github_chip)
        githubStatus = findViewById(R.id.github_status)
        githubSheetStatus = githubSheetView.findViewById(R.id.github_sheet_status)
        githubInstallStatus = githubSheetView.findViewById(R.id.github_install_status)
        githubInstall = githubSheetView.findViewById(R.id.github_install)
        githubLogin = githubSheetView.findViewById(R.id.github_login)
        githubLogout = githubSheetView.findViewById(R.id.github_logout)
        githubUser = githubSheetView.findViewById(R.id.github_user)
        githubCode = githubSheetView.findViewById(R.id.github_code)
        githubCopy = githubSheetView.findViewById(R.id.github_copy)
        githubLink = githubSheetView.findViewById(R.id.github_link)
        githubHint = githubSheetView.findViewById(R.id.github_hint)
        githubCloneSection = githubSheetView.findViewById(R.id.github_clone_section)
        githubCloneUrl = githubSheetView.findViewById(R.id.github_clone_url)
        githubClone = githubSheetView.findViewById(R.id.github_clone)
        githubCloneStatus = githubSheetView.findViewById(R.id.github_clone_status)
        githubRepoSection = githubSheetView.findViewById(R.id.github_repo_section)
        githubRepoStatus = githubSheetView.findViewById(R.id.github_repo_status)
        githubRepoList = githubSheetView.findViewById(R.id.github_repo_list)
        infoAppVersion = findViewById(R.id.info_app_version)
        infoOpencodeVersion = findViewById(R.id.info_opencode_version)
    }

    private fun bindSettings() {
        toolbar.setOnMenuItemClickListener { item ->
            when (item.itemId) {
                R.id.action_refresh -> hardRefreshWebUi()
                R.id.action_settings -> openSettings()
                R.id.action_files -> showFiles()
                R.id.action_restart -> confirmRestartServer()
                R.id.action_stop -> confirmStopServer()
                R.id.action_copy_address -> copyServerAddress()
                R.id.action_about -> showAbout()
                else -> return@setOnMenuItemClickListener false
            }
            true
        }
        findViewById<View>(R.id.settings_close).setOnClickListener { closeSettings() }
        findViewById<View>(R.id.files_manage).setOnClickListener { showFiles() }
        findViewById<View>(R.id.github_manage).setOnClickListener { openGithubSheet() }
        githubSheetView.findViewById<View>(R.id.github_sheet_close).setOnClickListener {
            githubSheet?.dismiss()
        }
        filesScreen = FilesScreen(
            this,
            { directory -> openProject(directory) },
            { requestStorageAccess() },
        )
        findViewById<View>(R.id.settings_restart).setOnClickListener { confirmRestartServer() }
        findViewById<View>(R.id.settings_stop).setOnClickListener { confirmStopServer() }
        findViewById<View>(R.id.info_about).setOnClickListener { showAbout() }

        updatesCheck.setOnClickListener { checkForUpdates() }
        updatesUpdate.setOnClickListener { startUpdate() }
        updatesAuto.setOnCheckedChangeListener { _, checked ->
            if (suppressSettingsCallbacks) return@setOnCheckedChangeListener
            OpenCodeSettings.setAutoUpdateCheck(this, checked)
        }
        wakeLockSwitch.setOnCheckedChangeListener { _, checked ->
            if (suppressSettingsCallbacks) return@setOnCheckedChangeListener
            OpenCodeService.setWakeLockEnabled(this, checked)
            wakeLockStatus.text = getString(
                if (checked) R.string.opencode_battery_wakelock_on
                else R.string.opencode_battery_wakelock_off,
            )
        }
        batteryOptimizationButton.setOnClickListener { requestIgnoreBatteryOptimizations() }
        bootStartSwitch.setOnCheckedChangeListener { _, checked ->
            if (suppressSettingsCallbacks) return@setOnCheckedChangeListener
            OpenCodeSettings.setBootStartEnabled(this, checked)
        }
        watchdogSwitch.setOnCheckedChangeListener { _, checked ->
            if (suppressSettingsCallbacks) return@setOnCheckedChangeListener
            OpenCodeWatchdog.setEnabled(this, checked)
        }
        keepAliveHelpButton.setOnClickListener { showKeepAliveHelp() }

        storageGrant.setOnClickListener { requestStorageAccess() }
        storagePick.setOnClickListener { pickProjectDirectory() }

        githubInstall.setOnClickListener { startGithubInstall() }
        githubLogin.setOnClickListener { startGithubLogin() }
        githubLogout.setOnClickListener { startGithubLogout() }
        githubCopy.setOnClickListener { copyGithubCode() }
        githubLink.setOnClickListener { openGithubVerification() }
        githubClone.setOnClickListener { startGithubClone() }

        onBackPressedDispatcher.addCallback(
            this,
            object : OnBackPressedCallback(true) {
                override fun handleOnBackPressed() {
                    if (::filesScreen.isInitialized && filesScreen.isVisible) {
                        filesScreen.goBack()
                    } else if (settingsPanel.visibility == View.VISIBLE) {
                        closeSettings()
                    } else {
                        isEnabled = false
                        onBackPressedDispatcher.onBackPressed()
                    }
                }
            },
        )
    }

    private fun openSettings() {
        OverlayMotion.reveal(settingsPanel)
        renderSettings()
        refreshGitHub()
    }

    private fun closeSettings() {
        githubSheet?.dismiss()
        OverlayMotion.conceal(settingsPanel)
    }

    /** Show the GitHub controls without leaving Settings, above the retained WebView. */
    private fun openGithubSheet() {
        if (githubSheet?.isShowing == true) return
        (githubSheetView.parent as? ViewGroup)?.removeView(githubSheetView)
        val sheet = BottomSheetDialog(this)
        sheet.setContentView(githubSheetView)
        sheet.setOnDismissListener { githubSheet = null }
        githubSheet = sheet
        sheet.show()
        refreshGitHub()
    }

    private fun showFiles() {
        filesScreen.show()
    }

    /* ------------------------------------------------------------------ app shell */

    /**
     * Draw behind the system bars and pad the root by the actual insets.
     *
     * The activity is edge-to-edge (Android 15 enforces it anyway), so the toolbar, the boot card
     * and the WebView all live inside one surface. Padding the root by the system bars keeps the
     * toolbar below the status bar and the chat input above the navigation bar, and the IME inset
     * shrinks the WebView instead of covering its input when the keyboard opens.
     */
    private fun applyEdgeToEdge() {
        ViewCompat.setOnApplyWindowInsetsListener(root) { view, insets ->
            val bars = insets.getInsets(
                WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout(),
            )
            val ime = insets.getInsets(WindowInsetsCompat.Type.ime())
            view.setPadding(bars.left, bars.top, bars.right, maxOf(bars.bottom, ime.bottom))
            applyContentWidths()
            WindowInsetsCompat.CONSUMED
        }
        ViewCompat.requestApplyInsets(root)
    }

    /**
     * Keep long forms readable on a tablet or in landscape: the boot card never grows past
     * 480dp and the settings/files content past 640dp. Both stay centered instead of stretching
     * across the whole window.
     */
    private fun applyContentWidths() {
        val usable = (resources.displayMetrics.widthPixels -
            root.paddingLeft - root.paddingRight).coerceAtLeast(0)
        if (usable <= 0) return
        val margins = 2 * resources.getDimensionPixelSize(R.dimen.screen_margin)
        val bootWidth = minOf(
            (usable - margins).coerceAtLeast(0),
            resources.getDimensionPixelSize(R.dimen.boot_card_max_width),
        )
        applyWidth(bootCard, bootWidth)
        // The settings and files content bring their own screen margins, so only the card limit
        // applies here. Without it a tablet would stretch every row across the whole screen.
        val contentWidth = minOf(usable, resources.getDimensionPixelSize(R.dimen.content_max_width))
        applyWidth(findViewById(R.id.settings_content), contentWidth)
        applyWidth(findViewById(R.id.files_content), contentWidth)
    }

    /** Keep long forms readable on tablets and in landscape instead of stretching them edge to edge. */
    private fun applyWidth(view: View, width: Int) {
        when (val params = view.layoutParams) {
            is FrameLayout.LayoutParams -> {
                if (params.width == width && params.gravity == Gravity.CENTER_HORIZONTAL) return
                params.width = width
                params.gravity = Gravity.CENTER_HORIZONTAL
                view.layoutParams = params
            }
            is LinearLayout.LayoutParams -> {
                if (params.width == width && params.gravity == Gravity.CENTER_HORIZONTAL) return
                params.width = width
                params.gravity = Gravity.CENTER_HORIZONTAL
                view.layoutParams = params
            }
            else -> {
                if (params.width == width) return
                params.width = width
                view.layoutParams = params
            }
        }
    }

    /**
     * Rotation, multi-window and keyboard changes are handled here instead of recreating the
     * activity, so the retained WebView is never detached and the chat keeps its scroll position.
     */
    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        applySystemBarAppearance()
        applyContentWidths()
        applyWebTheme()
        updateStatusSubtitle(connectionState, force = true)
        ViewCompat.requestApplyInsets(root)
    }

    /* ------------------------------------------------------------------ boot stepper */

    /** Move the boot panel to [stage] and repaint the three step rows. */
    private fun showBootStage(stage: BootStage) {
        bootStage = stage
        statusStage.setText(
            when (stage) {
                BootStage.ENVIRONMENT -> R.string.opencode_stage_runtime
                BootStage.SERVER -> R.string.opencode_stage_server
                BootStage.UI -> R.string.opencode_stage_ui
            },
        )
        renderBootSteps()
    }

    private fun renderBootSteps() {
        val failure = bootFailure
        for (index in 0 until bootStepIcons.size) {
            val state = when {
                failure != null && index == failure.index -> StepState.ERROR
                failure != null && index > failure.index -> StepState.WAITING
                index < bootStage.index -> StepState.DONE
                index == bootStage.index -> StepState.ACTIVE
                else -> StepState.WAITING
            }
            val icon = when (state) {
                StepState.WAITING -> R.drawable.ic_step_pending
                StepState.ACTIVE -> R.drawable.ic_step_active
                StepState.DONE -> R.drawable.ic_step_done
                StepState.ERROR -> R.drawable.ic_step_error
            }
            val color = when (state) {
                StepState.WAITING -> MaterialColors.getColor(
                    this,
                    com.google.android.material.R.attr.colorOnSurfaceVariant,
                    ContextCompat.getColor(this, R.color.oc_on_surface_variant),
                )
                StepState.ACTIVE -> MaterialColors.getColor(
                    this,
                    androidx.appcompat.R.attr.colorPrimary,
                    ContextCompat.getColor(this, R.color.oc_primary),
                )
                StepState.DONE -> ContextCompat.getColor(this, R.color.oc_success)
                StepState.ERROR -> MaterialColors.getColor(
                    this,
                    androidx.appcompat.R.attr.colorError,
                    ContextCompat.getColor(this, R.color.oc_error),
                )
            }
            val label = when (state) {
                StepState.WAITING -> R.string.opencode_boot_state_waiting
                StepState.ACTIVE -> R.string.opencode_boot_state_active
                StepState.DONE -> R.string.opencode_boot_state_done
                StepState.ERROR -> R.string.opencode_boot_state_error
            }
            bootStepIcons[index].setImageResource(icon)
            bootStepIcons[index].imageTintList = ColorStateList.valueOf(color)
            bootStepStates[index].setText(label)
            bootStepStates[index].setTextColor(color)
        }
    }

    /** The semantic status dot and the connection label of the toolbar subtitle. */
    private fun updateStatusSubtitle(state: ConnectionState, force: Boolean = false) {
        if (!force && subtitlePainted && connectionState == state) return
        connectionState = state
        subtitlePainted = true
        val dotColor = when (state) {
            ConnectionState.RUNNING -> ContextCompat.getColor(this, R.color.oc_success)
            ConnectionState.STARTING,
            ConnectionState.RECONNECTING,
            -> ContextCompat.getColor(this, R.color.oc_warning)
            ConnectionState.OFFLINE,
            ConnectionState.STOPPED,
            -> MaterialColors.getColor(
                this,
                androidx.appcompat.R.attr.colorError,
                ContextCompat.getColor(this, R.color.oc_error),
            )
        }
        val label = getString(
            when (state) {
                ConnectionState.RUNNING -> R.string.opencode_connection_running
                ConnectionState.STARTING -> R.string.opencode_connection_starting
                ConnectionState.RECONNECTING -> R.string.opencode_connection_reconnecting
                ConnectionState.OFFLINE -> R.string.opencode_connection_offline
                ConnectionState.STOPPED -> R.string.opencode_connection_stopped
            },
        )
        val text = getString(R.string.opencode_status_line, label, PORT)
        val dot = SpannableString("●")
        dot.setSpan(
            ForegroundColorSpan(dotColor),
            0,
            dot.length,
            Spannable.SPAN_EXCLUSIVE_EXCLUSIVE,
        )
        dot.setSpan(RelativeSizeSpan(0.85f), 0, dot.length, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
        toolbar.subtitle = SpannableStringBuilder(dot).append(' ').append(text)
    }

    private fun toggleBootDetails() {
        val expanded = statusLog.visibility == View.VISIBLE
        statusLog.visibility = if (expanded) View.GONE else View.VISIBLE
        statusDetailsToggle.setText(
            if (expanded) R.string.opencode_boot_details_show
            else R.string.opencode_boot_details_hide,
        )
    }

    private fun copyBootError() {
        val text = statusLog.text?.toString()?.ifBlank { null }
            ?: statusDetail.text?.toString().orEmpty()
        if (text.isEmpty()) return
        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
        clipboard?.setPrimaryClip(
            ClipData.newPlainText(getString(R.string.opencode_boot_copy_error), text),
        )
        showSnack(getString(R.string.opencode_boot_error_copied))
    }

    private fun showSnack(
        message: String,
        duration: Int = Snackbar.LENGTH_LONG,
        actionText: String? = null,
        action: (() -> Unit)? = null,
    ) {
        if (destroyed) return
        val bar = Snackbar.make(root, message, duration)
        if (actionText != null && action != null) bar.setAction(actionText) { action() }
        bar.show()
    }

    /* ------------------------------------------------------------------ server actions */

    private fun copyServerAddress() {
        val address = "127.0.0.1:$PORT"
        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
        clipboard?.setPrimaryClip(
            ClipData.newPlainText(getString(R.string.opencode_copy_address), address),
        )
        showSnack(getString(R.string.opencode_address_copied), Snackbar.LENGTH_SHORT)
    }

    private fun showAbout() {
        val installed = OpenCodeBootstrap.getInstalledVersion()
            .ifEmpty { getString(R.string.opencode_about_version_unknown) }
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.opencode_about)
            .setMessage(
                getString(R.string.opencode_about_message, BuildConfig.VERSION_NAME, installed),
            )
            .setPositiveButton(android.R.string.ok, null)
            .show()
    }

    private fun confirmRestartServer() {
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.opencode_restart_title)
            .setMessage(R.string.opencode_restart_message)
            .setPositiveButton(R.string.opencode_restart_action) { _, _ -> restartServer() }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun restartServer() {
        OpenCodeSettings.setStoppedByUser(this, false)
        val sessionId = OpenCodeWebViewModel.sessionIdOf(webView.url)
            ?: OpenCodeSettings.lastSessionId(this)
        pageLoaded = false
        viewModel.webReady = false
        viewModel.transportDown = false
        waitingForInstall = false
        pairAttempts = 0
        loadFailures = 0
        probeFailures = 0
        pendingSessionId = sessionId
        serverPassword = OpenCodeBootstrap.getServerPassword(this)
        bootFailure = null
        webView.visibility = View.GONE
        statusPanel.visibility = View.VISIBLE
        showBootStage(BootStage.SERVER)
        showBootPanel(
            getString(R.string.opencode_restarting),
            getString(R.string.opencode_reconnecting),
        )
        updateStatusSubtitle(ConnectionState.STARTING)
        handler.removeCallbacks(bootTimeout)
        handler.removeCallbacks(transportWatchdog)
        handler.removeCallbacks(readinessFallback)
        handler.removeCallbacks(poll)
        try {
            val intent = Intent(this, OpenCodeService::class.java)
                .putExtra("action", OpenCodeService.ACTION_RESTART)
            startForegroundService(intent)
        } catch (e: Exception) {
            Log.w(TAG, "Could not restart the server", e)
        }
        // Give the service a moment to replace the process before pairing again.
        handler.postDelayed(poll, 800)
    }

    private fun confirmStopServer() {
        val dialog = MaterialAlertDialogBuilder(this)
            .setTitle(R.string.opencode_stop_title)
            .setMessage(R.string.opencode_stop_message)
            .setPositiveButton(R.string.opencode_stop_action) { _, _ -> stopServer() }
            .setNegativeButton(android.R.string.cancel, null)
            .create()
        dialog.setOnShowListener {
            dialog.getButton(androidx.appcompat.app.AlertDialog.BUTTON_POSITIVE)?.setTextColor(
                MaterialColors.getColor(
                    this,
                    androidx.appcompat.R.attr.colorError,
                    ContextCompat.getColor(this, R.color.oc_error),
                ),
            )
        }
        dialog.show()
    }

    private fun stopServer() {
        OpenCodeSettings.setStoppedByUser(this, true)
        try {
            val intent = Intent(this, OpenCodeService::class.java)
                .putExtra("action", OpenCodeService.ACTION_STOP)
            startForegroundService(intent)
        } catch (e: Exception) {
            Log.w(TAG, "Could not stop the server", e)
        }
        pageLoaded = false
        viewModel.webReady = false
        viewModel.transportDown = false
        waitingForInstall = false
        pairAttempts = 0
        loadFailures = 0
        pendingSessionId = null
        bootFailure = null
        webView.visibility = View.GONE
        statusPanel.visibility = View.VISIBLE
        showBootStage(BootStage.SERVER)
        showBootPanel(
            getString(R.string.opencode_notification_stopped),
            getString(R.string.opencode_notification_stopped_text),
        )
        statusProgress.visibility = View.GONE
        statusRetry.visibility = View.VISIBLE
        updateStatusSubtitle(ConnectionState.STOPPED)
        handler.removeCallbacks(bootTimeout)
        handler.removeCallbacks(transportWatchdog)
        handler.removeCallbacks(readinessFallback)
        handler.removeCallbacks(poll)
    }

    private fun renderSettings() {
        suppressSettingsCallbacks = true
        updatesAuto.isChecked = OpenCodeSettings.autoUpdateCheck(this)
        wakeLockSwitch.isChecked = OpenCodeSettings.wakeLockEnabled(this)
        bootStartSwitch.isChecked = OpenCodeSettings.bootStartEnabled(this)
        watchdogSwitch.isChecked = OpenCodeSettings.watchdogEnabled(this)
        suppressSettingsCallbacks = false

        val installed = OpenCodeBootstrap.getInstalledVersion().ifEmpty { "-" }
        infoAppVersion.text = BuildConfig.VERSION_NAME
        infoOpencodeVersion.text = OpenCodeBootstrap.getInstalledVersion()
            .ifEmpty { getString(R.string.opencode_about_version_unknown) }
        updatesCurrent.text = getString(R.string.opencode_updates_current, installed)
        updatesUpdate.visibility = View.GONE
        updatesStatus.text = ""

        wakeLockStatus.text = getString(
            if (OpenCodeSettings.wakeLockEnabled(this)) R.string.opencode_battery_wakelock_on
            else R.string.opencode_battery_wakelock_off,
        )
        renderBatteryOptimization()
        renderStorage()
    }

    private fun listMessage(message: String): View {
        val view = TextView(this)
        view.text = message
        view.setTextColor(MaterialColors.getColor(this, com.google.android.material.R.attr.colorOutline, getColor(R.color.oc_text_faint)))
        view.setTextAppearance(R.style.TextAppearance_OpenCode_BodySmall)
        view.setPadding(dp(2), dp(8), dp(2), dp(4))
        return view
    }

    /* ------------------------------------------------------------------ updates */

    private fun checkForUpdates() {
        if (updateChecking) return
        updateChecking = true
        updatesCheck.isEnabled = false
        updatesUpdate.visibility = View.GONE
        updatesStatus.text = getString(R.string.opencode_updates_checking)
        val installed = OpenCodeBootstrap.getInstalledVersion()
        backgroundExecutor.execute {
            val latest = OpenCodeApi.latestVersion()
            handler.post {
                if (destroyed) return@post
                updateChecking = false
                updatesCheck.isEnabled = true
                updatesCurrent.text = getString(
                    R.string.opencode_updates_current,
                    installed.ifEmpty { "-" },
                )
                if (latest == null) {
                    updatesStatus.text = getString(R.string.opencode_updates_failed)
                } else if (latest == installed) {
                    updatesStatus.text = getString(R.string.opencode_updates_uptodate)
                } else {
                    updatesStatus.text = getString(R.string.opencode_updates_available, latest)
                    updatesUpdate.visibility = View.VISIBLE
                }
            }
        }
    }

    private fun startUpdate() {
        updatesUpdate.visibility = View.GONE
        updatesStatus.text = getString(R.string.opencode_updates_installing)
        try {
            OpenCodeBootstrap.requestUpdate(this)
        } catch (e: Exception) {
            Log.w(TAG, "Could not start the update", e)
            updatesStatus.text = getString(R.string.opencode_updates_failed)
        }
    }

    /* ------------------------------------------------------------------ battery and wake lock */

    private fun renderBatteryOptimization() {
        val ignoring = isIgnoringBatteryOptimizations()
        batteryOptimizationButton.text = getString(
            if (ignoring) R.string.opencode_battery_optimization_ok
            else R.string.opencode_battery_optimization,
        )
    }

    private fun isIgnoringBatteryOptimizations(): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return true
        val manager = getSystemService(PowerManager::class.java) ?: return false
        return manager.isIgnoringBatteryOptimizations(packageName)
    }

    private fun requestIgnoreBatteryOptimizations() {
        if (isIgnoringBatteryOptimizations()) {
            renderBatteryOptimization()
            return
        }
        val packageUri = Uri.parse("package:$packageName")
        val direct = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, packageUri)
        try {
            startActivity(direct)
        } catch (e: Exception) {
            try {
                startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
            } catch (e2: Exception) {
                showSnack(getString(R.string.opencode_storage_permission_failed))
            }
        }
    }

    /** Explain the OEM settings and the phantom process killer that a foreground service cannot beat. */
    private fun showKeepAliveHelp() {
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.opencode_keepalive_help_title)
            .setMessage(R.string.opencode_keepalive_help_message)
            .setPositiveButton(R.string.opencode_ok, null)
            .setNeutralButton(R.string.opencode_battery_optimization) { _, _ ->
                requestIgnoreBatteryOptimizations()
            }
            .show()
    }

    /* ------------------------------------------------------------------ shared storage */

    private fun renderStorage() {
        val legacy = OpenCodeStorage.hasLegacyPermission(this)
        val allFiles = OpenCodeStorage.hasAllFilesAccess()
        val canReadPrimary = legacy || allFiles
        val canReadEverything = allFiles || (legacy && Build.VERSION.SDK_INT < Build.VERSION_CODES.R)

        storageStatus.text = getString(
            when {
                canReadEverything -> R.string.opencode_storage_granted
                canReadPrimary -> R.string.opencode_storage_primary_only
                else -> R.string.opencode_storage_not_granted
            },
        )
        storageGrant.visibility = if (canReadEverything) View.GONE else View.VISIBLE
        if (!canReadPrimary) {
            storagePaths.removeAllViews()
            return
        }
        setupStorageLinks()
    }

    private fun requestStorageAccess() {
        val legacy = OpenCodeStorage.hasLegacyPermission(this)
        if (!legacy) {
            storagePermissionLauncher.launch(
                arrayOf(
                    Manifest.permission.READ_EXTERNAL_STORAGE,
                    Manifest.permission.WRITE_EXTERNAL_STORAGE,
                ),
            )
            return
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && !OpenCodeStorage.hasAllFilesAccess()) {
            val packageUri = Uri.parse("package:$packageName")
            try {
                startActivity(Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION, packageUri))
            } catch (e: Exception) {
                try {
                    startActivity(Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION))
                } catch (e2: Exception) {
                    showSnack(getString(R.string.opencode_storage_permission_failed))
                }
            }
            return
        }
        renderStorage()
    }

    private fun setupStorageLinks() {
        if (storageLinksInFlight) return
        storageLinksInFlight = true
        backgroundExecutor.execute {
            val linked = OpenCodeStorage.ensureLinks(this)
            val volumes = OpenCodeStorage.volumeRoots(this)
            handler.post {
                if (destroyed) return@post
                storageLinksInFlight = false
                storagePaths.removeAllViews()
                if (!linked) {
                    storagePaths.addView(
                        listMessage(getString(R.string.opencode_storage_links_missing)),
                    )
                    return@post
                }
                var secondary = 0
                for (volume in volumes) {
                    val text = if (OpenCodeStorage.isPrimaryRoot(volume)) {
                        getString(
                            R.string.opencode_storage_shared_path,
                            volume.absolutePath,
                        )
                    } else {
                        secondary++
                        getString(
                            R.string.opencode_storage_sd_path,
                            secondary,
                            volume.absolutePath,
                        )
                    }
                    val row = TextView(this)
                    row.text = text
                    row.setTextColor(MaterialColors.getColor(this, com.google.android.material.R.attr.colorOutline, getColor(R.color.oc_text_faint)))
                    row.setTextAppearance(R.style.TextAppearance_OpenCode_MonoSmall)
                    row.setPadding(0, dp(2), 0, dp(2))
                    storagePaths.addView(row)
                }
                if (secondary == 0) {
                    storagePaths.addView(listMessage(getString(R.string.opencode_storage_no_sd)))
                }
            }
        }
    }

    private fun pickProjectDirectory() {
        val start = OpenCodeSettings.lastProjectDir(this)?.let { File(it) }
        DirectoryPickerSheet(this) { directory ->
            OpenCodeSettings.setLastProjectDir(this, directory.absolutePath)
            openProject(directory)
        }.show(start)
    }

    /** Create a session in [directory] so the project appears in the web UI, then show it. */
    private fun openProject(directory: File) {
        if (::filesScreen.isInitialized) filesScreen.hide()
        showSnack(
            getString(R.string.opencode_picker_opening, directory.absolutePath),
            Snackbar.LENGTH_SHORT,
        )
        val secret = OpenCodeBootstrap.getServerPassword(this)
        backgroundExecutor.execute {
            val status = OpenCodeApi.createSession(PORT, secret, directory.absolutePath)
            handler.post {
                if (destroyed) return@post
                if (status in 200..299) {
                    closeSettings()
                    if (pageLoaded) {
                        webView.loadUrl("$baseUrl/")
                    } else {
                        pendingSessionId = null
                    }
                } else {
                    copyPath(directory.absolutePath)
                    showSnack(
                        getString(R.string.opencode_picker_open_failed, directory.absolutePath),
                        actionText = getString(R.string.opencode_files_copy_path),
                        action = { copyPath(directory.absolutePath) },
                    )
                }
            }
        }
    }

    private fun copyPath(path: String) {
        try {
            val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
            clipboard?.setPrimaryClip(ClipData.newPlainText("path", path))
        } catch (e: Exception) {
            // The clipboard may be unavailable; the caller shows the important message.
        }
    }

    /* ------------------------------------------------------------------ GitHub */

    private fun refreshGitHub() {
        if (githubRefreshing) return
        githubRefreshing = true
        githubProgress.visibility = View.VISIBLE
        backgroundExecutor.execute {
            val installed = OpenCodeGitHub.bothAvailable()
            val needsReauth = installed && OpenCodeGitHub.needsScopeUpgrade()
            val loggedIn = installed && OpenCodeGitHub.isLoggedIn()
            val user = if (loggedIn) OpenCodeGitHub.currentUser() else null
            handler.post {
                if (destroyed) return@post
                githubRefreshing = false
                githubProgress.visibility = View.GONE
                applyGithubState(installed, loggedIn, user, needsReauth)
            }
        }
    }

    private fun applyGithubState(
        installed: Boolean,
        loggedIn: Boolean,
        user: String?,
        needsReauth: Boolean,
    ) {
        githubInstall.visibility = if (installed) View.GONE else View.VISIBLE
        githubInstallStatus.visibility = if (githubInstalling) View.VISIBLE else View.GONE

        if (!installed) {
            githubChip.text = getString(R.string.opencode_settings_github_not_connected)
            setGithubStatus(getString(R.string.opencode_settings_github_missing))
            githubLogin.visibility = View.GONE
            githubLogout.visibility = View.GONE
            githubUser.visibility = View.GONE
            githubCloneSection.visibility = View.GONE
            githubRepoSection.visibility = View.GONE
            return
        }

        setGithubStatus(
            if (needsReauth) {
                getString(R.string.opencode_settings_github_reauth)
            } else {
                getString(R.string.opencode_settings_github_ready)
            },
        )
        if (!loggedIn) {
            githubChip.text = getString(R.string.opencode_settings_github_not_connected)
            githubUser.visibility = View.GONE
            githubLogin.visibility = View.VISIBLE
            githubLogout.visibility = View.GONE
            githubCloneSection.visibility = View.VISIBLE
            githubRepoSection.visibility = View.GONE
            githubReposLoaded = false
            githubRepoList.removeAllViews()
            return
        }

        val login = user ?: "GitHub"
        githubChip.text = getString(R.string.opencode_settings_github_connected, login)
        githubUser.text = getString(R.string.opencode_settings_github_account, login)
        githubUser.visibility = View.VISIBLE
        githubLogin.visibility = View.GONE
        githubLogout.visibility = View.VISIBLE
        githubCloneSection.visibility = View.VISIBLE
        githubRepoSection.visibility = View.VISIBLE
        if (!githubReposLoaded) refreshGithubRepos(force = false)
    }

    /** Keep the Settings summary and the open GitHub sheet in sync. */
    private fun setGithubStatus(text: CharSequence) {
        githubStatus.text = text
        githubSheetStatus.text = text
    }

    private fun startGithubInstall() {
        if (githubInstalling) return
        if (OpenCodeGitHub.bothAvailable()) {
            refreshGitHub()
            return
        }
        githubInstalling = true
        githubProgress.visibility = View.VISIBLE
        githubInstall.visibility = View.GONE
        githubInstallStatus.visibility = View.VISIBLE
        githubInstallStatus.text = getString(R.string.opencode_settings_github_installing)
        backgroundExecutor.execute {
            val result = OpenCodeGitHub.install(this) { message ->
                handler.post {
                    if (destroyed) return@post
                    githubInstallStatus.visibility = View.VISIBLE
                    githubInstallStatus.text = message
                }
            }
            handler.post {
                if (destroyed) return@post
                githubInstalling = false
                githubProgress.visibility = View.GONE
                if (result.ok) {
                    githubInstallStatus.text = getString(R.string.opencode_settings_github_installed)
                    githubInstallStatus.visibility = View.VISIBLE
                    refreshGitHub()
                } else {
                    githubInstallStatus.text = getString(
                        R.string.opencode_settings_github_install_failed,
                        result.output.trim().ifBlank { "exit ${result.exitCode}" },
                    )
                    githubInstallStatus.visibility = View.VISIBLE
                    githubInstall.visibility = View.VISIBLE
                }
            }
        }
    }

    private fun startGithubLogin() {
        if (githubLoginInFlight) return
        if (!OpenCodeGitHub.bothAvailable()) {
            setGithubStatus(getString(R.string.opencode_settings_github_missing))
            return
        }
        githubLoginInFlight = true
        githubProgress.visibility = View.VISIBLE
        setGithubStatus(getString(R.string.opencode_settings_github_logging_in))
        githubCode.visibility = View.GONE
        githubCopy.visibility = View.GONE
        githubLink.visibility = View.GONE
        githubHint.visibility = View.GONE
        githubUserCode = ""
        githubVerificationUrl = ""
        backgroundExecutor.execute {
            try {
                val device = OpenCodeGitHub.requestDeviceCode()
                handler.post {
                    if (destroyed) return@post
                    githubUserCode = device.userCode
                    githubVerificationUrl = device.verificationUri
                    githubCode.text = device.userCode
                    githubCode.visibility = View.VISIBLE
                    githubCopy.visibility = View.VISIBLE
                    githubLink.visibility = View.VISIBLE
                    githubHint.visibility = View.VISIBLE
                    setGithubStatus(getString(R.string.opencode_settings_github_waiting))
                }
                OpenCodeGitHub.completeLogin(device)
                handler.post {
                    if (destroyed) return@post
                    githubLoginInFlight = false
                    githubProgress.visibility = View.GONE
                    githubCode.visibility = View.GONE
                    githubCopy.visibility = View.GONE
                    githubLink.visibility = View.GONE
                    githubHint.visibility = View.GONE
                    setGithubStatus(getString(R.string.opencode_settings_github_login_ok))
                    githubUserCode = ""
                    githubVerificationUrl = ""
                    githubReposLoaded = false
                    refreshGitHub()
                    restartServerForGitHub()
                }
            } catch (e: Exception) {
                handler.post {
                    if (destroyed) return@post
                    githubLoginInFlight = false
                    githubProgress.visibility = View.GONE
                    setGithubStatus(
                        getString(
                            R.string.opencode_settings_github_login_failed,
                            e.message ?: e.toString(),
                        ),
                    )
                }
            }
        }
    }

    private fun startGithubLogout() {
        if (githubLoggingOut) return
        githubLoggingOut = true
        githubProgress.visibility = View.VISIBLE
        backgroundExecutor.execute {
            OpenCodeGitHub.logout()
            handler.post {
                if (destroyed) return@post
                githubLoggingOut = false
                githubProgress.visibility = View.GONE
                githubUserCode = ""
                githubVerificationUrl = ""
                githubReposLoaded = false
                githubRepoList.removeAllViews()
                githubRepoStatus.text = ""
                refreshGitHub()
                restartServerForGitHub()
            }
        }
    }

    private fun copyGithubCode() {
        if (githubUserCode.isEmpty()) return
        try {
            val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
            clipboard?.setPrimaryClip(ClipData.newPlainText("code", githubUserCode))
            showSnack(
                getString(R.string.opencode_settings_github_copy_done),
                Snackbar.LENGTH_SHORT,
            )
        } catch (e: Exception) {
            Log.w(TAG, "Could not copy the GitHub code", e)
        }
    }

    private fun openGithubVerification() {
        if (githubVerificationUrl.isEmpty()) return
        try {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(githubVerificationUrl)))
        } catch (e: Exception) {
            showSnack(getString(R.string.opencode_settings_github_open_failed))
        }
    }

    private fun refreshGithubRepos(force: Boolean) {
        if (!OpenCodeGitHub.bothAvailable() || githubReposLoading) return
        if (!force && githubReposLoaded) return
        githubReposLoading = true
        githubRepoStatus.text = getString(R.string.opencode_settings_github_repos_loading)
        backgroundExecutor.execute {
            val result = OpenCodeGitHub.listRepos()
            val repos = if (result.ok) OpenCodeGitHub.parseRepos(result.output) else emptyList()
            handler.post {
                if (destroyed) return@post
                githubReposLoading = false
                if (!result.ok) {
                    githubRepoStatus.text = getString(
                        R.string.opencode_settings_github_repos_failed,
                        result.output.trim().take(180).ifEmpty { "exit ${result.exitCode}" },
                    )
                    return@post
                }
                githubReposLoaded = true
                githubRepoStatus.text =
                    getString(R.string.opencode_settings_github_repos_count, repos.size)
                githubRepoList.removeAllViews()
                if (repos.isEmpty()) {
                    githubRepoList.addView(
                        listMessage(getString(R.string.opencode_settings_github_repos_empty)),
                    )
                    return@post
                }
                for (repo in repos) githubRepoList.addView(repoRow(repo))
            }
        }
    }

    private fun repoRow(repo: OpenCodeGitHub.Repo): View {
        val row = TextView(this)
        val privacy = getString(
            if (repo.privateRepo) R.string.opencode_settings_github_private
            else R.string.opencode_settings_github_public,
        )
        row.text = repo.name + " · " + privacy +
            if (repo.description.isEmpty()) "" else "\n" + repo.description
        row.setTextColor(MaterialColors.getColor(this, com.google.android.material.R.attr.colorOnSurfaceVariant, getColor(R.color.oc_text_muted)))
        row.setTextAppearance(R.style.TextAppearance_OpenCode_BodySmall)
        row.setLineSpacing(dp(2).toFloat(), 1f)
        row.setPadding(dp(10), dp(8), dp(10), dp(8))
        row.background = getDrawable(R.drawable.bg_input)
        row.isClickable = true
        row.setOnClickListener {
            githubCloneUrl.setText(repo.url)
            githubCloneUrl.setSelection(githubCloneUrl.text.length)
            githubCloneStatus.visibility = View.GONE
        }
        val params = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT,
        )
        params.topMargin = dp(6)
        row.layoutParams = params
        return row
    }

    private fun startGithubClone() {
        if (githubCloning) return
        val raw = githubCloneUrl.text.toString().trim()
        if (raw.isEmpty()) {
            githubCloneStatus.visibility = View.VISIBLE
            githubCloneStatus.text = getString(R.string.opencode_settings_github_clone_need_url)
            return
        }
        githubCloning = true
        githubCloneLog.setLength(0)
        githubProgress.visibility = View.VISIBLE
        githubClone.isEnabled = false
        githubCloneStatus.visibility = View.VISIBLE
        githubCloneStatus.text = getString(R.string.opencode_settings_github_cloning)
        backgroundExecutor.execute {
            val result = OpenCodeGitHub.clone(raw) { line ->
                handler.post {
                    if (!destroyed) appendCloneLog(line)
                }
            }
            handler.post {
                if (destroyed) return@post
                githubCloning = false
                githubProgress.visibility = View.GONE
                githubClone.isEnabled = true
                if (result.ok) {
                    appendCloneLog(getString(R.string.opencode_settings_github_clone_ok))
                    registerClonedProject(raw)
                } else {
                    val detail = result.output.trim().takeLast(600)
                    appendCloneLog(getString(R.string.opencode_settings_github_clone_failed))
                    if (detail.isNotEmpty()) appendCloneLog(detail)
                }
            }
        }
    }

    private fun appendCloneLog(line: String) {
        if (githubCloneStatus.text.toString() == getString(R.string.opencode_settings_github_cloning)) {
            githubCloneLog.setLength(0)
        }
        githubCloneLog.append(line).append('\n')
        val lines = githubCloneLog.toString().trimEnd('\n').lines()
        if (lines.size > 12) {
            githubCloneLog.setLength(0)
            githubCloneLog.append(lines.takeLast(12).joinToString("\n")).append('\n')
        }
        githubCloneStatus.visibility = View.VISIBLE
        githubCloneStatus.text = githubCloneLog.toString().trimEnd('\n')
    }

    /** Make the directory of a fresh clone known to the server as a project and refresh the UI. */
    private fun registerClonedProject(rawUrl: String) {
        val name = rawUrl.trimEnd('/').substringAfterLast('/').removeSuffix(".git")
        if (name.isEmpty()) return
        val directory = File(OpenCodeBootstrap.HOME, name)
        val secret = OpenCodeBootstrap.getServerPassword(this)
        backgroundExecutor.execute {
            val status = OpenCodeApi.createSession(PORT, secret, directory.absolutePath)
            handler.post {
                if (destroyed) return@post
            }
        }
    }

    private fun restartServerForGitHub() {
        setGithubStatus(getString(R.string.opencode_settings_github_syncing))
        try {
            val intent = Intent(this, OpenCodeService::class.java)
                .putExtra("action", OpenCodeService.ACTION_RESTART)
            startForegroundService(intent)
        } catch (e: Exception) {
            Log.w(TAG, "Could not restart the server after the GitHub login", e)
        }
    }

    /* ------------------------------------------------------------------ web view */

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        // Bringing the app to the front is an explicit start.
        OpenCodeSettings.setStoppedByUser(this, false)
        OpenCodeWatchdog.ensureScheduled(this)
        startServerService()
        // The notification of a running session opens exactly that session. The activity is
        // `singleTop`, so the WebView that already exists is reused instead of starting a second
        // one that would talk to the server with a second pairing.
        val requestedSession = consumeSessionIntent(intent)
        if (!pageLoaded) {
            if (requestedSession != null) pendingSessionId = requestedSession
            handler.removeCallbacks(poll)
            handler.post(poll)
        } else if (requestedSession != null) {
            openSession(requestedSession)
        }
    }

    override fun onStart() {
        super.onStart()
        // JavaScript dialogs of the web app are shown by the activity of the moment.
        viewModel.ui = this
        // The boot/transport callbacks run on a background WebView thread and may have happened
        // while no activity was attached (screen off, rotation). Re-apply their state here.
        if (viewModel.pageLoaded) {
            if (viewModel.transportDown) {
                onWebTransportLost("")
            } else if (viewModel.webReady) {
                showWebUi()
            }
        }
    }

    override fun onStop() {
        viewModel.ui = null
        // While the activity is gone the notification keeps the server alive; the page is checked
        // and woken up again in onResume, so the periodic check is not needed here.
        handler.removeCallbacks(generationWatch)
        super.onStop()
    }

    override fun onResume() {
        super.onResume()
        // The screen was off: the WebView of the ViewModel is the same instance, the system (or a
        // long stop) can leave its timers paused, which makes the web UI look frozen.
        viewModel.resumeWebView()
        // A server that was replaced while the app was in the background invalidated the cookie
        // and the pairing token of the page. Pair again instead of showing a page that only gets
        // 401s from the API.
        val generation = OpenCodeService.serverGeneration()
        if (pageLoaded && generation != 0 && generation != viewModel.pairedServerGeneration) {
            reconnectToRestartedServer()
        } else if (pageLoaded) {
            if (!viewModel.webReady) {
                // A rotation or a short trip to the background must not leave the launcher stuck
                // without a deadline while the page is still booting.
                handler.removeCallbacks(bootTimeout)
                handler.postDelayed(bootTimeout, BOOT_TIMEOUT_MS)
            }
            // The server is fine. Android may have dropped the event streams of the page while it
            // was in the background without the page noticing, so make the single page app run its
            // own reconnect path and fetch what it missed.
            viewModel.nudgeWebApp()
        }
        handler.removeCallbacks(generationWatch)
        handler.postDelayed(generationWatch, GENERATION_CHECK_MS)
        if (::settingsPanel.isInitialized && settingsPanel.visibility == View.VISIBLE) {
            renderBatteryOptimization()
            renderStorage()
        }
        if (::filesScreen.isInitialized && filesScreen.isVisible) {
            filesScreen.onStoragePermissionResult()
        }
        offerBatteryOptimizationOnce()
    }

    /**
     * Open a chat session of the server in the WebView.
     *
     * Called for the session in the notification. If the page is not loaded yet the id waits in
     * [pendingSessionId] and [WebViewClient.onPageFinished] opens it on top of the start page.
     */
    private fun openSession(id: String) {
        val sessionId = id.trim()
        if (!sessionId.startsWith("ses_")) return
        OpenCodeSettings.setLastSessionId(this, sessionId)
        OpenCodeSessionTracker.setActiveSession(sessionId)
        if (settingsPanel.visibility == View.VISIBLE) closeSettings()
        if (::filesScreen.isInitialized && filesScreen.isVisible) filesScreen.hide()
        if (!pageLoaded) {
            pendingSessionId = sessionId
            return
        }
        if (OpenCodeWebViewModel.sessionIdOf(webView.url) == sessionId) return
        webView.loadUrl("$baseUrl/session/$sessionId")
    }

    /**
     * Read and clear the session id of a notification PendingIntent, so a rotation or a second
     * resume of the same intent does not reopen a session the user already left.
     */
    private fun consumeSessionIntent(intent: Intent?): String? {
        val id = intent?.getStringExtra(EXTRA_SESSION_ID)?.trim()
            ?.takeIf { it.startsWith("ses_") }
        intent?.removeExtra(EXTRA_SESSION_ID)
        return id
    }

    /**
     * Pair the retained WebView with the server that replaced the old one, keeping the session
     * the user had open. The cookie of the previous server is dropped, a new token is minted.
     */
    private fun reconnectToRestartedServer() {
        if (destroyed) return
        Log.i(TAG, "The server was replaced while the app was away, pairing again")
        val sessionId = OpenCodeWebViewModel.sessionIdOf(webView.url)
            ?: OpenCodeSettings.lastSessionId(this)
        pageLoaded = false
        viewModel.webReady = false
        pairAttempts = 0
        loadFailures = 0
        pendingSessionId = sessionId
        serverPassword = OpenCodeBootstrap.getServerPassword(this)
        try {
            CookieManager.getInstance().apply {
                removeAllCookies(null)
                flush()
            }
        } catch (e: Exception) {
            Log.w(TAG, "Could not drop the cookies of the previous server", e)
        }
        webView.visibility = View.GONE
        statusPanel.visibility = View.VISIBLE
        showBootStage(BootStage.UI)
        showBootPanel(
            getString(R.string.opencode_status_starting),
            getString(R.string.opencode_reconnecting),
        )
        updateStatusSubtitle(ConnectionState.RECONNECTING)
        handler.removeCallbacks(bootTimeout)
        handler.removeCallbacks(transportWatchdog)
        handler.removeCallbacks(readinessFallback)
        handler.removeCallbacks(poll)
        handler.post(poll)
    }

    /**
     * Ask once for the battery optimization exemption. Without it Doze can stop the server while
     * the screen is off, which is exactly the case this app is used in. The prompt is recorded
     * before it is shown, so a rotation or a second launch does not ask again; the button in the
     * settings screen remains for users who choose "later".
     */
    private fun offerBatteryOptimizationOnce() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return
        if (OpenCodeSettings.batteryPromptShown(this)) return
        OpenCodeSettings.setBatteryPromptShown(this, true)
        if (isIgnoringBatteryOptimizations()) return
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.opencode_battery_prompt_title)
            .setMessage(R.string.opencode_battery_prompt_message)
            .setPositiveButton(R.string.opencode_battery_prompt_allow) { _, _ ->
                requestIgnoreBatteryOptimizations()
            }
            .setNegativeButton(R.string.opencode_battery_prompt_later, null)
            .show()
    }

    override fun onJsAlert(view: WebView, message: String?, result: JsResult) {
        showJsDialog(message, result, confirm = false)
    }

    override fun onJsConfirm(view: WebView, message: String?, result: JsResult) {
        showJsDialog(message, result, confirm = true)
    }

    /**
     * Open the system file manager for the attach button of the composer. A WebView does not show
     * a picker by itself: without this callback the `<input type="file">` of the web app is a
     * no-op on Android.
     */
    override fun onShowFileChooser(params: WebChromeClient.FileChooserParams?) {
        if (isFinishing || isDestroyed) {
            viewModel.onFileChooserResult(null)
            return
        }
        val intent = try {
            params?.createIntent() ?: Intent(Intent.ACTION_GET_CONTENT).apply {
                addCategory(Intent.CATEGORY_OPENABLE)
                type = "*/*"
                putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true)
            }
        } catch (e: Exception) {
            Log.w(TAG, "Could not build the file picker intent", e)
            viewModel.onFileChooserResult(null)
            showSnack(getString(R.string.opencode_file_picker_unavailable))
            return
        }
        try {
            fileChooserLauncher.launch(intent)
        } catch (e: ActivityNotFoundException) {
            Log.w(TAG, "No file manager is installed", e)
            viewModel.onFileChooserResult(null)
            showSnack(getString(R.string.opencode_file_picker_unavailable))
        }
    }

    /** Turn the result of [fileChooserLauncher] into the list the WebView expects. */
    private fun fileChooserUris(data: Intent?): Array<Uri>? {
        if (data == null) return null
        // Multiple files arrive as ClipData; a single file may only be in data. The WebView
        // implementation of FileChooserParams.parseResult ignores ClipData, so parse it here.
        val clip = data.clipData
        if (clip != null) {
            val uris = (0 until clip.itemCount).mapNotNull { clip.getItemAt(it).uri }
            if (uris.isNotEmpty()) return uris.toTypedArray()
        }
        return data.data?.let { arrayOf(it) }
    }

    private fun showJsDialog(message: String?, result: JsResult, confirm: Boolean) {
        if (isFinishing || isDestroyed) {
            result.cancel()
            return
        }
        val builder = MaterialAlertDialogBuilder(this)
            .setMessage(message)
            .setOnCancelListener { result.cancel() }
            .setPositiveButton(android.R.string.ok) { _, _ -> result.confirm() }
        if (confirm) {
            builder.setNegativeButton(android.R.string.cancel) { _, _ -> result.cancel() }
        }
        builder.show()
    }

    /**
     * Light or dark icons on the system bars, matching the Material You surfaces.
     *
     * `android:windowLightNavigationBar` only exists from API 27 while minSdk is 26, but the
     * flag behind it is already available there, so the appearance is applied through the insets
     * controller instead of relying on the theme attribute alone.
     */
    private fun applySystemBarAppearance() {
        val mode = resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK
        val light = mode != Configuration.UI_MODE_NIGHT_YES
        WindowCompat.getInsetsController(window, window.decorView).apply {
            isAppearanceLightStatusBars = light
            isAppearanceLightNavigationBars = light
        }
    }

    /**
     * Paint the WebView and the web UI with the Material You palette of this activity.
     *
     * The same stylesheet is registered as a document-start script (first paint) and evaluated on
     * the retained document (rotation, dark-mode change). The native surface color is set behind
     * the WebView as well, so the area that is not painted yet matches instead of flashing white.
     */
    private fun applyWebTheme() {
        val theme = OpenCodeWebTheme.resolve(this)
        webView.setBackgroundColor(theme.surface)
        viewModel.installWebTheme(theme.script)
    }

    private fun configureWebView() {
        webView.webViewClient = object : WebViewClient() {
            override fun onReceivedHttpAuthRequest(
                view: WebView?,
                handler: HttpAuthHandler?,
                host: String?,
                realm: String?,
            ) {
                // The OpenCode 2.x server protects its API with HTTP Basic auth.
                handler?.proceed(OpenCodeBootstrap.SERVER_USER, serverPassword)
            }

            override fun onPageFinished(view: WebView?, url: String?) {
                // The page rendered, so the counter of failed loads starts again.
                if (pageLoaded) loadFailures = 0
                if (url != null && url.startsWith(baseUrl)) {
                    reportSession(url)
                    viewModel.installSessionWatcher()
                    // A page that started loading before the stylesheet was registered would
                    // keep the stock colors; make sure the live document is themed.
                    viewModel.applyWebTheme()
                    if (pageLoaded && !viewModel.webReady && !viewModel.bootGuardInstalled) {
                        // Older WebViews do not support document-start scripts. Probe the API the
                        // web app calls and reveal the page only after the server answers it.
                        handler.removeCallbacks(readinessFallback)
                        handler.postDelayed(readinessFallback, 1200)
                    }
                    // A re-pair after a server restart keeps the chat the user was reading: the
                    // start page was loaded with the fresh token, open the session on top of it.
                    val pending = pendingSessionId
                    if (pending != null && OpenCodeWebViewModel.sessionIdOf(url) == null) {
                        pendingSessionId = null
                        webView.loadUrl("$baseUrl/session/$pending")
                    }
                }
            }

            override fun doUpdateVisitedHistory(view: WebView?, url: String?, isReload: Boolean) {
                super.doUpdateVisitedHistory(view, url, isReload)
                if (url != null && url.startsWith(baseUrl)) {
                    reportSession(url)
                }
            }

            override fun onReceivedError(
                view: WebView?,
                request: WebResourceRequest?,
                error: WebResourceError?,
            ) {
                if (request?.isForMainFrame != true) return
                // The page itself could not be loaded. The server may have died or the port is
                // not answerable any more: drop back to the progress screen and let the poll
                // restart the service and pair again.
                Log.w(TAG, "The OpenCode web UI could not be loaded: ${error?.errorCode} ${error?.description}")
                failUi(
                    getString(R.string.opencode_ui_unavailable),
                    getString(
                        R.string.opencode_ui_unavailable_detail,
                        error?.errorCode ?: 0,
                        error?.description ?: "",
                    ),
                )
            }

            override fun onRenderProcessGone(
                view: WebView?,
                detail: RenderProcessGoneDetail?,
            ): Boolean {
                // Android can kill the renderer of a backgrounded WebView under memory pressure.
                // The old view then shows an empty surface, which looks like a dead session. Build
                // a new WebView, keep the session the user was reading and pair the page with the
                // running server again instead of letting the system crash the app.
                if (view !== webView) return true
                recoverWebView()
                return true
            }
        }

        webView.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            // The attach button of the composer reads the files returned by the system picker as
            // content URIs, so content access has to stay enabled. Local file URLs are not needed:
            // the UI is served over loopback.
            allowFileAccess = false
            allowContentAccess = true
        }
        // The Pairing redirect stores the session cookie of the server here.
        CookieManager.getInstance().setAcceptCookie(true)
    }

    /**
     * Build a new WebView after the renderer of the old one was killed.
     *
     * The session itself lives in the server, so it is not lost: only the page has to be loaded
     * again. The id is taken from the last route or from the persisted last session, and the
     * pairing with the running server is repeated.
     */
    private fun recoverWebView() {
        if (destroyed) return
        Log.w(TAG, "The WebView renderer was killed, creating a new WebView")
        val sessionId = OpenCodeWebViewModel.sessionIdOf(webView.url)
            ?: OpenCodeSettings.lastSessionId(this)
        webView = viewModel.replaceWebView(this)
        webContainer.removeAllViews()
        webContainer.addView(
            webView,
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT,
            ),
        )
        configureWebView()
        applyWebTheme()
        pageLoaded = false
        viewModel.webReady = false
        viewModel.transportDown = false
        waitingForInstall = false
        pairAttempts = 0
        loadFailures = 0
        pendingSessionId = sessionId
        serverPassword = OpenCodeBootstrap.getServerPassword(this)
        webView.visibility = View.GONE
        statusPanel.visibility = View.VISIBLE
        showBootStage(BootStage.UI)
        showBootPanel(
            getString(R.string.opencode_status_starting),
            getString(R.string.opencode_reconnecting),
        )
        updateStatusSubtitle(ConnectionState.RECONNECTING)
        handler.removeCallbacks(bootTimeout)
        handler.removeCallbacks(transportWatchdog)
        handler.removeCallbacks(readinessFallback)
        handler.removeCallbacks(poll)
        handler.post(poll)
    }

    /** Report the route to the notification tracker and remember it across a process death. */
    private fun reportSession(url: String?) {
        viewModel.reportSessionFromUrl(url)
        OpenCodeWebViewModel.sessionIdOf(url)?.let { OpenCodeSettings.setLastSessionId(this, it) }
    }

    // ------------------------------------------------------------------ install state

    override fun onOpenCodeInstallStateChanged(state: OpenCodeInstallState?) {
        if (destroyed || state == null) return
        applyState(state)
    }

    private fun applyState(state: OpenCodeInstallState) {
        if (pageLoaded) return

        if (state.failed) {
            waitingForInstall = false
            statusPanel.visibility = View.VISIBLE
            bootFailure = BootStage.ENVIRONMENT
            showBootStage(BootStage.ENVIRONMENT)
            val message = state.error.ifEmpty { state.message }
            showBootPanel(
                getString(R.string.opencode_install_failed),
                message,
                log = message,
            )
            statusProgress.isIndeterminate = false
            statusProgress.progress = state.overallProgress()
            statusRetry.visibility = View.VISIBLE
            updateStatusSubtitle(ConnectionState.OFFLINE)
            return
        }

        if (state.done) {
            bootFailure = null
            showBootStage(BootStage.SERVER)
            showBootPanel(
                getString(R.string.opencode_starting),
                getString(R.string.opencode_install_done_detail),
            )
            updateStatusSubtitle(ConnectionState.STARTING)
            return
        }

        if (state.running) {
            waitingForInstall = true
            statusPanel.visibility = View.VISIBLE
            bootFailure = null
            showBootStage(BootStage.ENVIRONMENT)
            showBootPanel(
                getString(R.string.opencode_installing),
                listOf(state.message, state.detail).filter { it.isNotEmpty() }.joinToString(" "),
            )
            statusProgress.isIndeterminate = false
            statusProgress.progress = state.overallProgress()
            updateStatusSubtitle(ConnectionState.STARTING)
        }
    }

    /** Show the last known progress, it is useful before the first listener callback arrives. */
    private fun updateProgress() {
        if (pageLoaded || waitingForInstall) return
        if (OpenCodeSettings.stoppedByUser(this)) {
            // The user pressed Stop in the notification while the activity was still alive.
            handler.removeCallbacks(poll)
            waitingForInstall = false
            webView.visibility = View.GONE
            statusPanel.visibility = View.VISIBLE
            bootFailure = null
            showBootStage(BootStage.SERVER)
            showBootPanel(
                getString(R.string.opencode_notification_stopped),
                getString(R.string.opencode_notification_stopped_text),
            )
            statusProgress.visibility = View.GONE
            statusRetry.visibility = View.VISIBLE
            updateStatusSubtitle(ConnectionState.STOPPED)
            return
        }
        val state = OpenCodeBootstrap.getInstallState()
        applyState(state)
        if (state.failed) return

        if (OpenCodeBootstrap.isRuntimeComplete()) {
            // Installed, but the server is not answering. Ask the service to start it again (it
            // may have been killed while the app was in the background).
            bootFailure = null
            showBootStage(BootStage.SERVER)
            updateStatusSubtitle(ConnectionState.STARTING)
            val now = SystemClock.uptimeMillis()
            if (now - lastServiceStart > 3000) {
                lastServiceStart = now
                probeFailures = 0
                startServerService()
            }
            return
        }

        if (!OpenCodeBootstrap.isInstallRunning()) {
            // No installer is running and the runtime is not complete (eg. the process was killed
            // right after the install started). Start it again instead of waiting forever.
            waitingForInstall = true
            OpenCodeBootstrap.setupIfNeeded(this, null)
        }
    }

    /**
     * The UI could not be loaded: show the reason and let [poll] try again. The service is
     * restarted by that loop when it is no longer reachable, and the page is paired again.
     */
    private fun failUi(title: String, detail: String) {
        if (destroyed) return
        pageLoaded = false
        viewModel.webReady = false
        viewModel.transportDown = false
        waitingForInstall = false
        pairAttempts = 0
        webView.visibility = View.GONE
        statusPanel.visibility = View.VISIBLE
        bootFailure = BootStage.UI
        showBootStage(BootStage.UI)
        showBootPanel(title, detail, log = detail)
        updateStatusSubtitle(ConnectionState.OFFLINE)
        handler.removeCallbacks(bootTimeout)
        handler.removeCallbacks(transportWatchdog)
        handler.removeCallbacks(readinessFallback)
        handler.removeCallbacks(poll)
        loadFailures++
        if (loadFailures > 3) {
            // Stop the automatic loop: the user can retry by hand, otherwise a broken server
            // would reload the page every two seconds forever.
            val retryDetail = "$detail\n" + getString(R.string.opencode_retry_hint)
            statusDetail.text = retryDetail
            statusLog.text = retryDetail
            statusProgress.visibility = View.GONE
            statusRetry.visibility = View.VISIBLE
            return
        }
        handler.postDelayed(poll, 1500)
    }

    /** Start the foreground service that hosts the server, ignoring background restrictions. */
    private fun startServerService() {
        // Never override an explicit Stop from the notification. A real user action (opening the
        // activity, Retry, Restart) clears the flag before asking for a start.
        if (OpenCodeSettings.stoppedByUser(this)) return
        try {
            val intent = Intent(this, OpenCodeService::class.java)
            intent.putExtra(EXTRA_PORT, PORT)
            startForegroundService(intent)
        } catch (e: Exception) {
            // The system may refuse to start a foreground service from the background, the poll
            // retries.
        }
    }

    private fun retryInstall() {
        OpenCodeSettings.setStoppedByUser(this, false)
        bootFailure = null
        statusRetry.visibility = View.GONE
        showBootStage(BootStage.ENVIRONMENT)
        showBootPanel(
            getString(R.string.opencode_preparing),
            getString(R.string.opencode_preparing_detail),
        )
        updateStatusSubtitle(ConnectionState.STARTING)
        viewModel.webReady = false
        handler.removeCallbacks(bootTimeout)
        handler.removeCallbacks(transportWatchdog)
        handler.removeCallbacks(readinessFallback)
        // Retry is also the way back from an explicit Stop, when the runtime is already installed.
        waitingForInstall = !OpenCodeBootstrap.isRuntimeComplete()
        if (waitingForInstall) {
            OpenCodeBootstrap.setupIfNeeded(this, null)
        } else {
            startServerService()
        }
        pairAttempts = 0
        loadFailures = 0
        probeFailures = 0
        handler.removeCallbacks(poll)
        handler.post(poll)
    }

    private fun hardRefreshWebUi() {
        if (destroyed) return

        showSnack(getString(R.string.opencode_webui_refreshing), Snackbar.LENGTH_SHORT)

        try {
            val cookieManager = CookieManager.getInstance()
            cookieManager.removeAllCookies(null)
            cookieManager.flush()
            WebViewDatabase.getInstance(this).clearHttpAuthUsernamePassword()
        } catch (e: Exception) {
            Log.w(TAG, "hardRefreshWebUi: failed to clear cookies/auth", e)
        }

        try {
            webView.stopLoading()
            webView.clearCache(true)
            webView.clearFormData()
            webView.clearHistory()
            webView.clearSslPreferences()
        } catch (e: Exception) {
            Log.w(TAG, "hardRefreshWebUi: failed to clear webview", e)
        }

        pageLoaded = false
        viewModel.webReady = false
        viewModel.transportDown = false
        pairAttempts = 0
        pendingSessionId = null
        loadFailures = 0
        serverPassword = OpenCodeBootstrap.getServerPassword(this)
        bootFailure = null

        handler.removeCallbacks(bootTimeout)
        handler.removeCallbacks(transportWatchdog)
        handler.removeCallbacks(readinessFallback)
        webView.visibility = View.GONE
        statusPanel.visibility = View.VISIBLE
        showBootStage(BootStage.UI)
        showBootPanel(
            getString(R.string.opencode_status_starting),
            getString(R.string.opencode_status_waiting),
        )
        updateStatusSubtitle(ConnectionState.STARTING)
        statusRetry.visibility = View.GONE

        handler.removeCallbacks(poll)
        handler.post(poll)
    }

    /** The server accepts connections, load the paired web UI behind the native boot screen. */
    private fun loadUi(sessionToken: String?) {
        if (pageLoaded) return
        pageLoaded = true
        viewModel.webReady = false
        viewModel.transportDown = false
        viewModel.pairedServerGeneration = OpenCodeService.serverGeneration()
        waitingForInstall = false
        // Do not reveal the WebView yet. The web app fetches its session list immediately after
        // mounting; if that request fails it paints its own "Transport: Failed to fetch" error.
        // The boot bridge hides this panel only after the first successful API call.
        showBootPanel(
            getString(R.string.opencode_loading_ui),
            getString(R.string.opencode_loading_ui_detail),
        )
        showBootStage(BootStage.UI)
        updateStatusSubtitle(ConnectionState.RECONNECTING)
        statusRetry.visibility = View.GONE
        // Keep the view in the hierarchy and laid out; the opaque boot panel covers it.
        webView.visibility = View.INVISIBLE
        handler.removeCallbacks(bootTimeout)
        handler.postDelayed(bootTimeout, BOOT_TIMEOUT_MS)

        var target = "$baseUrl/"
        // Prefer the session token of the pairing code. The real password is an acceptable
        // fallback, it is the same credential 'opencode pair' puts into its links.
        val paired = !sessionToken.isNullOrEmpty()
        val secret = if (paired) sessionToken else serverPassword
        if (!secret.isNullOrEmpty()) {
            // 'opencode pair' prints links of the form /?auth_token=<base64(user:password)>,
            // the web app reads it, strips it from the address bar and authenticates its API
            // calls with it.
            //
            // The web app decodes the parameter with a base64url decoder (it replaces '-' and
            // '_' back to '+' and '/'), so the URL-safe alphabet is the format it expects. A
            // standard '+' would additionally be turned into a space by URLSearchParams.
            val credentials = Base64.encodeToString(
                "${OpenCodeBootstrap.SERVER_USER}:$secret".toByteArray(StandardCharsets.UTF_8),
                Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING,
            )
            target += "?auth_token=$credentials"

            // The session token is also the value of the session cookie, so a reload of the
            // WebView keeps working after the app removed the token from the URL.
            try {
                CookieManager.getInstance().apply {
                    setAcceptCookie(true)
                    setCookie(
                        "http://127.0.0.1:$PORT",
                        if (paired) {
                            "opencode_session_$PORT=$secret; Path=/; Max-Age=2592000"
                        } else {
                            // A token of an earlier server must not survive: the API calls of the
                            // UI would carry a cookie that the current server rejects with 401.
                            "opencode_session_$PORT=; Path=/; Max-Age=0"
                        },
                    )
                    flush()
                }
            } catch (e: Exception) {
                Log.w(TAG, "Could not store the session cookie", e)
            }
        }
        Log.i(TAG, "Loading the paired OpenCode web UI")
        webView.loadUrl(target)
        // The page routes to the session in onPageFinished, once the start page is ready.
    }

    /**
     * Show one of the native boot/reconnect states over the (hidden) WebView.
     *
     * [log] is the expandable technical detail behind the "Xem chi tiết" action; normal boot
     * transitions leave it empty so a stale error is not shown next to a healthy start.
     */
    private fun showBootPanel(title: String, detail: String, log: String? = null) {
        statusPanel.visibility = View.VISIBLE
        statusProgress.visibility = View.VISIBLE
        statusProgress.isIndeterminate = true
        statusTitle.text = title
        statusDetail.text = detail
        statusRetry.visibility = View.GONE
        if (log.isNullOrBlank()) {
            statusLog.text = ""
            statusLog.visibility = View.GONE
            statusDetailsToggle.visibility = View.GONE
            statusCopy.visibility = View.GONE
            statusDetailsToggle.setText(R.string.opencode_boot_details_show)
        } else {
            statusLog.text = log
            statusLog.visibility = View.GONE
            statusDetailsToggle.visibility = View.VISIBLE
            statusCopy.visibility = View.VISIBLE
            statusDetailsToggle.setText(R.string.opencode_boot_details_show)
        }
    }

    /** Reveal the web UI after its first successful API call, retiring the native boot screen. */
    private fun showWebUi() {
        if (destroyed || !pageLoaded) return
        handler.removeCallbacks(bootTimeout)
        handler.removeCallbacks(transportWatchdog)
        handler.removeCallbacks(readinessFallback)
        loadFailures = 0
        bootFailure = null
        statusPanel.visibility = View.GONE
        webView.visibility = View.VISIBLE
        updateStatusSubtitle(ConnectionState.RUNNING)
    }

    /**
     * Called by the injected transport guard once the web app has completed an API call. This is
     * the moment the SolidJS UI is actually usable, not the `load` event of the page.
     */
    override fun onWebBootReady() {
        if (destroyed || !pageLoaded) return
        Log.i(TAG, "The web UI completed its first API call, showing it")
        showWebUi()
    }

    /**
     * The transport guard exhausted its retries. Keep the native screen on top so the web app's
     * error boundary does not paint a stack trace, then pair again when the server is back.
     */
    override fun onWebTransportLost(detail: String) {
        if (destroyed || !pageLoaded) return
        Log.w(TAG, "The web UI lost the server transport: $detail")
        showBootPanel(
            getString(R.string.opencode_status_starting),
            getString(R.string.opencode_reconnecting_detail),
        )
        showBootStage(BootStage.UI)
        updateStatusSubtitle(ConnectionState.RECONNECTING)
        handler.removeCallbacks(transportWatchdog)
        handler.postDelayed(transportWatchdog, TRANSPORT_RECOVERY_MS)
    }

    /** A later request succeeded, hide the reconnect screen without reloading the page. */
    override fun onWebTransportRestored() {
        if (destroyed || !pageLoaded) return
        handler.removeCallbacks(transportWatchdog)
        if (viewModel.webReady) showWebUi()
    }

    /**
     * The server rejected the credential of the page. Retrying cannot fix that: the page has to
     * be paired with the current server again.
     */
    override fun onWebAuthFailed() {
        if (destroyed || !pageLoaded) return
        Log.i(TAG, "The web UI received a 401/403, pairing again")
        handler.removeCallbacks(transportWatchdog)
        handler.postDelayed(transportWatchdog, AUTH_RECOVERY_MS)
    }

    /**
     * Mint a single-use pairing code and redeem it for a session token. The web app cannot use
     * the Basic auth of the API directly, it needs the token in its `auth_token` link.
     */
    private fun requestSessionToken(): String? {
        val code = requestPairCode() ?: return null
        val body = try {
            request("$baseUrl$PAIR_PATH$code", "application/json")
        } catch (e: Exception) {
            Log.w(TAG, "Could not redeem the pairing code", e)
            return null
        } ?: return null

        val token = JSONObject(body).optString("token", "")
        if (token.isEmpty()) return null
        // Make sure the token is actually accepted before the UI starts with it. A token of a
        // server that has been replaced in the meantime only produces 401s and the UI then shows
        // its "server unreachable" panel.
        if (!serverAccepts(token)) {
            Log.w(TAG, "The server rejected the freshly minted pairing token, using the password.")
            return null
        }
        return token
    }

    /** A short-lived pairing code, or null when the server does not answer. */
    private fun requestPairCode(): String? {
        val body = try {
            request("$baseUrl/api/pair", accept = null, post = true)
        } catch (e: Exception) {
            Log.w(TAG, "Could not create a pairing code", e)
            return null
        } ?: return null

        val code = JSONObject(body).optString("code", "")
        return code.ifEmpty { null }
    }

    /**
     * A small GET/POST helper for the loopback API. Returns the body of a 200 answer and null
     * otherwise, the callers only need the JSON document.
     */
    private fun request(
        url: String,
        accept: String?,
        post: Boolean = false,
        secret: String = serverPassword,
    ): String? {
        val connection = URL(url).openConnection() as HttpURLConnection
        try {
            connection.connectTimeout = 2000
            connection.readTimeout = 3000
            connection.useCaches = false
            if (post) {
                connection.requestMethod = "POST"
                connection.doOutput = true
                connection.setFixedLengthStreamingMode(0)
                connection.setRequestProperty(
                    "Authorization",
                    OpenCodeHttp.basicAuth(secret),
                )
            }
            if (accept != null) connection.setRequestProperty("Accept", accept)
            if (connection.responseCode != HttpURLConnection.HTTP_OK) {
                Log.w(TAG, "The server answered ${connection.responseCode} for $url")
                return null
            }
            return connection.inputStream.bufferedReader(StandardCharsets.UTF_8).use { it.readText() }
        } finally {
            connection.disconnect()
        }
    }

    private fun serverReachable(): Boolean = OpenCodeHttp.isPortOpen(PORT, 1000)

    /**
     * Check that the server answers `/api/info` with the given secret (a session token or the
     * server password) as HTTP Basic credentials, exactly like the web UI does.
     */
    private fun serverAccepts(secret: String): Boolean {
        if (secret.isEmpty()) return false
        val connection = try {
            URL("$baseUrl/api/info").openConnection() as HttpURLConnection
        } catch (e: Exception) {
            return false
        }
        try {
            connection.connectTimeout = 2000
            connection.readTimeout = 3000
            connection.useCaches = false
            connection.setRequestProperty(
                "Authorization",
                OpenCodeHttp.basicAuth(secret),
            )
            return connection.responseCode == HttpURLConnection.HTTP_OK
        } catch (e: Exception) {
            Log.w(TAG, "Could not verify the server credentials", e)
            return false
        } finally {
            connection.disconnect()
        }
    }

    override fun onDestroy() {
        destroyed = true
        if (::filesScreen.isInitialized) filesScreen.release()
        githubSheet?.dismiss()
        githubSheet = null
        probeExecutor.shutdownNow()
        backgroundExecutor.shutdown()
        handler.removeCallbacksAndMessages(null)
        OpenCodeBootstrap.removeInstallListener(this)
        viewModel.ui = null
        // The WebView is owned by the ViewModel: here it is only detached from the hierarchy of
        // this activity, and only when it is really attached to it (a configuration change can
        // hand it to the next instance first). When the activity is finished for good, the
        // ViewModel destroys it (onCleared).
        if (webView.parent === webContainer) webContainer.removeView(webView)
        super.onDestroy()
    }

    private enum class BootStage(val index: Int) {
        ENVIRONMENT(0),
        SERVER(1),
        UI(2),
    }

    private enum class StepState {
        WAITING,
        ACTIVE,
        DONE,
        ERROR,
    }

    private enum class ConnectionState {
        RUNNING,
        STARTING,
        RECONNECTING,
        OFFLINE,
        STOPPED,
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density + 0.5f).toInt()

    private val baseUrl: String
        get() = "http://127.0.0.1:$PORT"

    companion object {
        private const val TAG = "OpenCodeWebActivity"

        const val PORT = OpenCodeService.DEFAULT_PORT

        /** Extra accepted by [OpenCodeService] to know the port it should serve. */
        const val EXTRA_PORT = "port"

        /**
         * Session that the notification wants to open. The foreground service puts it on the
         * content intent, so tapping the notification continues exactly the chat it reports.
         */
        const val EXTRA_SESSION_ID = "session_id"

        /** Endpoint that redeems a pairing code for a session token. */
        private const val PAIR_PATH = "/auth/connect/"

        /** How often the activity checks that the page is paired with the current server. */
        private const val GENERATION_CHECK_MS = 3000L

        /** How long the web app may take to complete its first API call before the boot retries. */
        private const val BOOT_TIMEOUT_MS = 15_000L

        /** Grace period before the page is paired again after the transport guard gave up. */
        private const val TRANSPORT_RECOVERY_MS = 1500L

        /** A 401/403 cannot recover by itself, re-pair almost immediately. */
        private const val AUTH_RECOVERY_MS = 250L
    }
}
