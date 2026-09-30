package com.droiddeck.launcher.session

import com.droiddeck.launcher.gpu.LinuxVulkanDriver
import com.droiddeck.launcher.gpu.LinuxVulkanDriverManager
import com.droiddeck.launcher.gpu.MaliKbaseProbe
import com.droiddeck.launcher.gpu.MaliKbaseProfiles

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.BroadcastReceiver
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Environment
import android.os.IBinder
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.util.Log
import com.droiddeck.launcher.R
import com.droiddeck.launcher.SessionActivity
import com.droiddeck.launcher.audio.DirectAudioRelayComponent
import com.droiddeck.launcher.audio.PulseAudioComponent
import com.droiddeck.launcher.core.CpuCores
import com.droiddeck.launcher.core.DeviceReport
import com.droiddeck.launcher.core.DeviceSupport
import com.droiddeck.launcher.core.HostEnvironment
import com.droiddeck.launcher.core.MobilePerformance
import com.droiddeck.launcher.core.SessionLogCapture
import com.droiddeck.launcher.core.NetworkReport
import com.droiddeck.launcher.core.SessionPart
import com.droiddeck.launcher.core.FileUtils
import com.droiddeck.launcher.core.HostProcess
import com.droiddeck.launcher.input.FakeInputWriter
import com.droiddeck.launcher.runtime.LinuxNetworkLinkComponent
import com.droiddeck.launcher.runtime.LinuxRuntime
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Owns the running session: the proot tree, the audio daemon, the network link and the locks that
 * keep all three alive while the app is not on screen.
 *
 * The session deliberately does **not** belong to the activity. Android demotes a process the
 * moment it loses its last visible activity, and the low-memory killer then reaps the guest - so
 * leaving Big Picture to answer a message would come back to a dead Steam. A foreground service
 * holds the process at perceptible priority, a partial wake lock keeps the CPU from dropping the
 * guest's threads, and a high-performance WiFi lock keeps the radio out of power-save so a
 * backgrounded download does not throttle to nothing. All three are Bannerlator's recipe, where
 * each was added to fix a failure seen on a device.
 *
 * The activity comes and goes on top of this; see [com.droiddeck.launcher.wayland.CompositorHost].
 */
class SessionService : Service() {
    private val components = java.util.concurrent.CopyOnWriteArrayList<SessionPart>()
    private val stopLock = Any()
    private var wakeLock: PowerManager.WakeLock? = null
    private var wifiLock: WifiManager.WifiLock? = null
    private var sessionPid = -1
    /** Auxiliary PTY-backed proot shells launched from a secondary-display terminal. */
    private val auxiliaryProcesses = HashMap<Int, Long>()
    private val auxiliaryProcessesLock = Any()
    private val mainHandler = Handler(Looper.getMainLooper())
    private var suspendController: SessionSuspendController? = null
    private var suspendPolicy = SessionPrefs.SUSPEND_MANUAL
    private var activityVisible = true
    private var screenOn = true
    private var manualPauseRequested = false
    private var suspendOperationPending = false
    private var suspendAttemptFailed = false
    private var screenReceiverRegistered = false
    private val screenReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                Intent.ACTION_SCREEN_OFF -> screenOn = false
                Intent.ACTION_SCREEN_ON -> screenOn = true
                else -> return
            }
            suspendAttemptFailed = false
            updateSuspendPolicy()
        }
    }
    /** Counts sessions this service has started; a process exit from an earlier one is ignored. */
    @Volatile private var sessionGen = 0

    override fun onCreate() {
        super.onCreate()
        val power = getSystemService(Context.POWER_SERVICE) as? PowerManager
        screenOn = power?.isInteractive ?: true
        registerReceiver(screenReceiver, IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_OFF)
            addAction(Intent.ACTION_SCREEN_ON)
        })
        screenReceiverRegistered = true
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_TRACK_AUXILIARY -> {
                val pid = intent.getIntExtra(EXTRA_AUXILIARY_PID, -1)
                val started = if (pid > 1) readStat(pid)?.second else null
                if (started != null) {
                    val tracked = synchronized(auxiliaryProcessesLock) {
                        if (SessionState.running) {
                            auxiliaryProcesses[pid] = started
                            true
                        } else false
                    }
                    if (!tracked) Thread({ teardown(pid, started) }, "auxiliary-stop-$pid").start()
                }
                return START_NOT_STICKY
            }
            ACTION_STOP_AUXILIARY -> {
                val pid = intent.getIntExtra(EXTRA_AUXILIARY_PID, -1)
                if (pid > 1) {
                    val started = synchronized(auxiliaryProcessesLock) { auxiliaryProcesses.remove(pid) } ?: readStat(pid)?.second
                    if (started != null) Thread({ teardown(pid, started) }, "auxiliary-stop-$pid").start()
                }
                return START_NOT_STICKY
            }
            ACTION_AUXILIARY_EXITED -> {
                synchronized(auxiliaryProcessesLock) {
                    auxiliaryProcesses.remove(intent.getIntExtra(EXTRA_AUXILIARY_PID, -1))
                }
                return START_NOT_STICKY
            }
            ACTION_STOP -> {
                Log.i(TAG, "stop requested from the notification")
                stopSession(0)
                return START_NOT_STICKY
            }
            ACTION_ACTIVITY_VISIBLE -> {
                activityVisible = true
                suspendAttemptFailed = false
                updateSuspendPolicy()
                return START_NOT_STICKY
            }
            ACTION_ACTIVITY_HIDDEN -> {
                activityVisible = false
                suspendAttemptFailed = false
                updateSuspendPolicy()
                return START_NOT_STICKY
            }
            ACTION_SUSPEND_POLICY_CHANGED -> {
                if (!SessionState.running) return START_NOT_STICKY
                suspendPolicy = SessionPrefs.suspendPolicy(this, SessionState.mode)
                suspendAttemptFailed = false
                updateSuspendPolicy()
                return START_NOT_STICKY
            }
            ACTION_RESUME -> {
                activityVisible = true
                screenOn = (getSystemService(Context.POWER_SERVICE) as? PowerManager)?.isInteractive ?: screenOn
                manualPauseRequested = false
                suspendAttemptFailed = false
                updateSuspendPolicy()
                return START_NOT_STICKY
            }
        }
        startForeground(NOTIFICATION_ID, buildNotification())
        if (SessionState.running) return START_NOT_STICKY
        if (SessionState.stopRequested) {
            SessionState.running = true
            stopSession(0)
            return START_NOT_STICKY
        }
        val requestedMode = intent?.getStringExtra(EXTRA_MODE) ?: MODE_STEAM
        SessionState.mode = if (requestedMode == MODE_DESKTOP) {
            Log.i(TAG, "modo de escritorio heredado redirigido a Steam")
            MODE_STEAM
        } else requestedMode
        suspendPolicy = SessionPrefs.suspendPolicy(this, SessionState.mode)
        activityVisible = true
        screenOn = (getSystemService(Context.POWER_SERVICE) as? PowerManager)?.isInteractive ?: true
        manualPauseRequested = false
        suspendOperationPending = false
        suspendAttemptFailed = false
        suspendController = null
        SessionState.suspended = false
        SessionState.program = intent?.getStringExtra(EXTRA_PROGRAM)
        SessionState.programArgs = intent?.getStringArrayExtra(EXTRA_PROGRAM_ARGS)?.toList().orEmpty()
        SessionState.steamUi = intent?.getStringExtra(EXTRA_STEAM_UI)
        SessionState.steamUrl = intent?.getStringExtra(EXTRA_STEAM_URL)
        SessionState.running = true
        if (SessionState.stopRequested) {
            stopSession(0)
            return START_NOT_STICKY
        }
        // Otro cliente Steam del dispositivo puede cerrar nuestra sesión. Solo Steam necesita
        // detener esos clientes rivales antes de iniciar.
        if (SessionState.mode == MODE_STEAM) RivalClients.stopBeforeSession(this)
        SessionState.firstFrameSeen = false
        SessionState.guestPid = -1
        SessionEvents.transition(SessionPhase.STARTING_GUEST, "service.started", mapOf("mode" to SessionState.mode))
        // This session's number, claimed here and not when its process starts: the session it
        // replaces can report its own exit in the gap between the two, and that exit must not
        // be taken as this one's.
        val gen = ++sessionGen
        acquireLocks()
        Thread({
            // A tree the last session left behind (the app was killed or crashed, so its teardown
            // never ran) would hold the rootfs, the GPU and Steam's lock: nothing of ours should
            // be alive between sessions outside this process.
            OrphanReaper.reap("session starting")
            runSession(gen)
        }, "session-start").start()
        // The activity or the notification stops us; the system must not resurrect a session whose
        // guest processes are long gone.
        return START_NOT_STICKY
    }

    /**
     * Finish the session's folder after the session has stopped, then let go of it. The guest
     * script copies Steam's logs too, at a clean exit; this runs whatever killed the session,
     * which is when they matter. The collecting itself is [SessionArtifacts], shared with the
     * crash handler and the next-start sweep so a folder is finished whichever way it ends.
     */
    private fun collectSessionArtifacts(dir: File) {
        try {
            SessionArtifacts.collect(this, dir, "session stopped")
        } finally {
            // Last, so everything above is in the file it is about - and only this session's:
            // a session that replaced this one may already own the capture and the folder.
            SessionLogCapture.stopFor(dir)
            SessionPaths.release(this, dir)
        }
    }

    private fun extraEnv(): List<String> {
        val file = File(Environment.getExternalStorageDirectory(), ENV_SWITCH).takeIf { it.isFile } ?: return emptyList()
        val lines = FileUtils.readString(file)?.lines().orEmpty()
            .map { it.trim() }
            .filter { it.isNotEmpty() && !it.startsWith("#") && it.contains('=') && !it.startsWith("=") }
        if (lines.isNotEmpty()) Log.i(TAG, "extra environment from $ENV_SWITCH: $lines")
        return lines
    }

    private fun tuDebug(linuxDriverId: String): String? {
        // TU_DEBUG belongs to Turnip. Do not leak Turnip-only switches into PanVK/Mali sessions.
        val isTurnip = if (linuxDriverId.isEmpty()) {
            DeviceSupport.adreno()
        } else {
            val manager = LinuxVulkanDriverManager(this)
            val signature = (manager.getDriverName(linuxDriverId) + " " + manager.getLibraryName(linuxDriverId)).lowercase()
            signature.contains("turnip") || signature.contains("freedreno")
        }
        if (!isTurnip) return null
        val override = File(Environment.getExternalStorageDirectory(), TU_DEBUG_SWITCH)
            .takeIf { it.isFile }?.let { FileUtils.readString(it)?.trim() }
        if (!override.isNullOrEmpty()) return override
        if (SessionPrefs.tuSysmem(this)) return "sysmem"
        if (linuxDriverId.isEmpty()) return null
        val name = LinuxVulkanDriverManager(this).getDriverName(linuxDriverId).lowercase()
        return if (name.contains("710-720") || name.contains("710_720")) "sysmem" else null
    }

    // ── The session ─────────────────────────────────────────────────────────────────────────

    private fun runSession(gen: Int) {
        SessionTerminal.clear()
        try {
            LinuxRuntime.writeAccounts(this)
        } catch (e: Exception) {
            Log.e(TAG, "could not write the guest's passwd/group", e)
            stopSession(-1)
            return
        }

        val root = LinuxRuntime.rootDir(this)
        val sessionRoot = LinuxRuntime.sessionRoot(this).apply { mkdirs() }
        val runtimeDir = File(filesDir, ".wayland-rt").apply { mkdirs() }
        killStragglers()
        SessionFiles.stage(this, root)

        val sessionDir = openSessionFolder()
        val sessionLog = File(sessionDir, "session.log")

        val size = SessionState.outputSize
        val guest = ArrayList<String>()
        val steamHere = SessionState.mode == MODE_STEAM
        // Resolve Automatic exactly once per launch. This keeps resolution/FPS/memory policy in
        // sync even if the thermal state changes while the session is being assembled.
        val mobilePlan = MobilePerformance.plan(this, SessionPrefs.performanceProfile(this))
        addClientEnvironment(guest, runtimeDir, steamHere, mobilePlan)

        val pulse = startAudio(guest, sessionDir)

        guest.add("BL_WIDTH=" + size.first)
        guest.add("BL_HEIGHT=" + size.second)
        if (SessionState.hdr) {
            // The activity opened the compositor's HDR gate: gamescope offers HDR to its clients
            // and DXVK takes the HDR10 swapchain when a game asks for one.
            guest.add("BL_HDR=1")
            guest.add("DXVK_HDR=1")
            Log.i(TAG, "hdr: gamescope --hdr-enabled, DXVK_HDR=1")
        }
        guest.add("BL_PERFORMANCE_PROFILE=" + mobilePlan.id)
        guest.add("BL_FPS=" + mobilePlan.targetFps)
        guest.add("BL_REFRESH=" + Math.round(SessionState.refreshHz))
        Log.i(TAG, "mobile profile: ${mobilePlan.label}, ${mobilePlan.maxHeight}p, fps=${mobilePlan.targetFps}, reason=${mobilePlan.reason}")
        guest.add("BL_LOG=" + sessionLog.path)
        guest.add("BL_DEBUG_DIR=" + sessionDir.path)

        val fakeInputDir = File(sessionRoot, "dev/input").apply { mkdirs() }
        val controllersOn = !File(Environment.getExternalStorageDirectory(), NO_PAD_SWITCH).exists()
        if (controllersOn) addControllerEnvironment(guest, fakeInputDir)
        // The user's own games, for the runtime's shortcuts writer to put in the client's library
        // before the client starts (see frontend/AddedGames and bannerlator-steam-shortcuts).
        if (steamHere) {
            val added = com.droiddeck.launcher.frontend.AddedGames.scan(this)
            val listing = com.droiddeck.launcher.frontend.AddedGames.writeListing(this, added)
            guest.add("BL_ADDED_GAMES=" + listing.path)
            if (OfflineMode.enabled(this)) guest.add("BL_STEAM_OFFLINE=1")
            if (added.isNotEmpty()) Log.i(TAG, "added games: " + added.joinToString { "${it.name} (${it.exe.name})" })
        }
        // Canal de control de la sesión (por ejemplo, apagado limpio del cliente Steam).
        guest.add("BL_LAUNCH_DIR=" + sessionRoot.path)
        // The second library's name, for bannerlator-steam-library; the bind itself is made below.
        GameStorage.effective(this)?.let { guest.add("BL_LIBRARY_LABEL=" + it.label.replace('"', ' ')) }
        if (SessionState.mode == MODE_STEAM && SessionState.steamUi == "desktop") guest.add("BL_STEAM_UI=desktop")
        val shellGuest = ArrayList(guest).apply {
            add("SHELL=/bin/bash")
            add("TERM=xterm-256color")
            add("/bin/bash")
            add("-c")
            add("cd \"\$HOME\" && exec /bin/bash -i")
        }
        guest.add(LinuxRuntime.SESSION_SCRIPT)
        guest.add(SessionState.mode)
        if (SessionState.mode == MODE_STEAM) SessionState.steamUrl?.takeIf { it.startsWith("steam://") }?.let {
            guest.add(it)
            Log.i(TAG, "steam: handing the client $it")
        }
        // Programa auxiliar bajo gamescope. Conservamos MODE_RUN para herramientas internas o
        // pruebas, pero comparte exactamente la misma ruta gráfica y el mismo ICD que Steam.
        if (SessionState.mode == MODE_RUN) {
            val program = SessionState.program
            if (program.isNullOrEmpty()) {
                Log.e(TAG, "run mode without a program")
                stopSession(65)
                return
            }
            guest.add(program)
            guest.addAll(SessionState.programArgs)
            Log.i(TAG, "run: $program ${SessionState.programArgs.joinToString(" ")} under gamescope")
        }

        // Android has no /dev/shm; the cache stands in for it and, unlike the real thing, keeps
        // whatever a session leaves behind. The client abandons tens of megabytes of streams a run.
        FileUtils.clear(File(cacheDir, "shm"))

        val binds = sessionBinds(controllersOn, fakeInputDir)

        val command = LinuxRuntime.command(
            this, sessionRoot, runtimeDir, Environment.getExternalStorageDirectory(), binds, guest,
        )

        val hostEnv = HostEnvironment()
        hostEnv["PROOT_LOADER"] = LinuxRuntime.prootLoader(this).path
        hostEnv["PROOT_TMP_DIR"] = cacheDir.path
        // proot links against a libtalloc beside it, and Android's linker does not search an
        // executable's own directory: unnamed, the process dies before it starts and says so only
        // in `logcat -b crash`.
        // proot reads this itself, so it belongs in proot's own environment rather than the guest's.
        if (SessionPrefs.prootNoSeccomp(this)) {
            hostEnv["PROOT_NO_SECCOMP"] = "1"
            Log.i(TAG, "proot: seccomp acceleration off by request")
        }
        val prootLibs = LinuxRuntime.prootLibraryPath(this)
        if (prootLibs.isNotEmpty()) hostEnv["LD_LIBRARY_PATH"] = prootLibs

        val terminalCommand = LinuxRuntime.command(
            this, sessionRoot, runtimeDir, Environment.getExternalStorageDirectory(), binds, shellGuest,
        )
        val terminalHostEnvironment = LinkedHashMap(System.getenv())
        hostEnv.asArray().forEach { entry ->
            val separator = entry.indexOf('=')
            if (separator > 0) terminalHostEnvironment[entry.substring(0, separator)] = entry.substring(separator + 1)
        }
        if (gen == sessionGen && SessionState.running) {
            SessionTerminal.prepare(terminalCommand, terminalHostEnvironment.map { (key, value) -> "$key=$value" }.toTypedArray(), root, gen)
        }

        // Whether the client signs in to Valve or starts offline: read once, while it starts, and
        // rewritten by the client when it exits, so it is set again here at every session start.
        if (SessionState.mode == MODE_STEAM) OfflineMode.apply(this, root)

        val networkLink = LinuxNetworkLinkComponent(this, root)
        networkLink.attach(this)
        networkLink.publish()
        components.add(networkLink)
        if (gen != sessionGen || !SessionState.running) {
            Log.i(TAG, "session stopped while it was starting; not launching it")
            if (gen == sessionGen) components.clear()
            return
        }
        components.forEach { it.start() }

        val line = command.joinToString(" ") {
            it.replace("\\", "\\\\").replace(" ", "\\ ")
        }
        // One session replacing another (the desktop's Steam launchers): the old proot is killed
        // by the teardown a second after the new one has started, and its exit used to arrive
        // here as "session ended: 137" and end the NEW session. An exit belongs to the session
        // that started it.
        val pid = HostProcess.start(line, hostEnv.asArray(), root, { status ->
            if (gen != sessionGen) {
                Log.i(TAG, "an earlier session's process ended ($status); the current one carries on")
                return@start
            }
            val rawStatus = status ?: -1
            // A clean rc=0 is only a clean session after the compositor has actually presented
            // something. Steam/updaters can exit 0 during bootstrap; treating that as success made
            // SessionActivity silently sink back to the menu and hid the only useful diagnostics.
            val effectiveStatus = if (
                rawStatus == 0 &&
                SessionState.mode == MODE_STEAM &&
                !SessionState.firstFrameSeen
            ) {
                SessionEvents.fail(
                    "STEAM_EARLY_EXIT",
                    "Steam terminó antes de mostrar el primer fotograma",
                    83,
                )
                83
            } else rawStatus
            Log.i(TAG, "session ended: raw=$rawStatus effective=$effectiveStatus firstFrame=${SessionState.firstFrameSeen}")
            stopSession(effectiveStatus)
        }, null)
        Log.i(TAG, "session pid $pid, log ${sessionLog.path}")
        if (gen != sessionGen || !SessionState.running) {
            Log.i(TAG, "session stopped while its guest was starting; taking it down")
            if (gen == sessionGen) {
                components.reversed().forEach { runCatching { it.stop() } }
                components.clear()
            }
            if (pid > 1) Thread({ teardown(pid) }, "session-teardown").start()
            return
        }
        sessionPid = pid
        if (pid > 1) {
            SessionEvents.guestStarted(pid)
        } else {
            SessionEvents.record("guest.start_failed", mapOf("pid" to pid))
            SessionEvents.fail("GUEST_START_FAILED", "No se pudo iniciar el proceso del guest", pid)
            stopSession(-1)
            return
        }
        if (gen == sessionGen && SessionState.running) {
            suspendController = SessionSuspendController(
                sessionRoot = { sessionPid },
                helperRoots = { components.mapNotNull { it.suspendPid().takeIf { pid -> pid > 1 } } },
                suspendAudio = {
                    if (!pulse.setSinkSuspended(true)) Log.w(TAG, "could not suspend audio sink")
                },
                resumeAudio = {
                    if (!pulse.setSinkSuspended(false)) Log.w(TAG, "could not resume audio sink")
                },
            )
            mainHandler.post { updateSuspendPolicy() }
        }
    }

    /** The session's own log folder, opened and filled with what is known before anything starts. */
    private fun openSessionFolder(): File {
        // One folder per session, claimed by whoever started first - the activity starts the
        // compositor before this service runs - so the compositor's log lands in the same place.
        val sessionDir = SessionPaths.beginOrCurrent(this)
        val sessionLog = File(sessionDir, "session.log")
        SessionState.logFile = sessionLog
        SessionState.logDirectory = sessionDir
        SessionState.sessionId = sessionDir.name
        SessionState.eventsFile = File(sessionDir, "events.jsonl")
        SessionEvents.record("session.logs_ready", mapOf("logDir" to sessionDir.path))
        // Written first, so a session that dies in its first second still says what it ran on.
        DeviceReport.write(this, File(sessionDir, "device.txt"), SessionState.mode)
        NetworkReport.write(this, File(sessionDir, "network.txt"))
        // Everything the app decides from here on - the driver it chose, the audio line, a rival
        // client stopped, the exit status - reaches logcat and nowhere a user can get at. Mirror it.
        SessionLogCapture.start(File(sessionDir, "app.log"))
        return sessionDir
    }

    /** The guest's base environment: paths, the display, the GL/Vulkan stack and the client's switches. */
    private fun addClientEnvironment(guest: MutableList<String>, runtimeDir: File, steamHere: Boolean, mobilePlan: MobilePerformance.Plan) {
        guest.add("/usr/bin/env")
        guest.add("-i")
        guest.add("HOME=/root")
        guest.add("USER=root")
        guest.add("PATH=/usr/local/bin:/usr/bin:/bin")
        guest.add("TERM=xterm-256color")
        guest.add("LANG=C.UTF-8")
        // Without this the session is UTC: the client's clock, its logs and every timestamp in a
        // session bundle sit hours off the device's. Bannerlator carries the same line.
        guest.add("TZ=" + java.util.TimeZone.getDefault().id)
        guest.add("XDG_RUNTIME_DIR=" + runtimeDir.path)
        guest.add("XDG_SESSION_TYPE=wayland")
        guest.add("WAYLAND_DISPLAY=wayland-0")
        guest.add("GAMESCOPE_FORCE_GENERAL_QUEUE=1")
        // Steam's CEF needs GL and the rootfs ships no native GL driver: route it through Zink.
        guest.add("MESA_LOADER_DRIVER_OVERRIDE=zink")
        guest.add("GALLIUM_DRIVER=zink")
        guest.add("LIBGL_KOPPER_DRI2=true")
        guest.add("BL_GPU_FAMILY=" + DeviceSupport.family().name.lowercase())
        guest.add("BL_HOST_VULKAN=" + DeviceSupport.hostVulkanBackend().name.lowercase())
        val guestBackend = DeviceSupport.guestGpuBackend()
        guest.add("BL_GUEST_GPU=" + guestBackend.name.lowercase())
        DeviceSupport.usableDrmRenderNode()?.let { guest.add("BL_DRM_RENDER_NODE=$it") }
        if (guestBackend == DeviceSupport.GuestGpuBackend.MALI_KBASE) {
            val kbase = MaliKbaseProbe.probe()
            guest.add("BL_MALI_KBASE_FRONTEND=${kbase.frontendName}")
            guest.add("BL_MALI_KBASE_UAPI=${kbase.uapi}")
            if (kbase.productCode != 0L) guest.add("BL_MALI_PRODUCT_ID=${kbase.productCodeHex}")
            if (kbase.gpuId != 0L) guest.add("BL_MALI_GPU_ID=${kbase.gpuIdHex}")
            if (kbase.shaderCores > 0) guest.add("BL_MALI_SHADER_CORES=${kbase.shaderCores}")
            MaliKbaseProfiles.forDevice(kbase)?.let { profile ->
                guest.add("BL_MALI_PROFILE=${profile.id}")
                if (MaliKbaseProfiles.isPinnedG57(profile)) {
                    // The pinned G57/JM glibc build documents these as its safe/default path.
                    // Keep them scoped to Valhall v9 JM; never leak them into CSF drivers.
                    guest.add("PANVK_NO_AFBC=1")
                    guest.add("PANVK_ASYNC=1")
                }
            }
        }
        val runtimeIcd = LinuxRuntime.vulkanIcd(this)
        if (runtimeIcd != null) {
            guest.add("VK_ICD_FILENAMES=" + runtimeIcd.path)
        } else if (DeviceSupport.mali()) {
            // Never let the loader auto-scan Freedreno on Mali. Imported drivers may replace this
            // later, but the session diagnostics retain the exact guest ABI through BL_GUEST_GPU.
            val noDriver = "/nonexistent/droiddeck-mali-no-matching-icd.json"
            guest.add("VK_DRIVER_FILES=$noDriver")
            guest.add("VK_ICD_FILENAMES=$noDriver")
        }
        // Un ICD Vulkan glibc importado para Steam. Los programas auxiliares (MODE_RUN) comparten
        // el mismo controlador para no duplicar cachés ni mantener una segunda configuración muerta.
        val driverMode = if (SessionState.mode == MODE_RUN) MODE_STEAM else SessionPrefs.prefMode(SessionState.mode)
        val linuxDriverId = SessionPrefs.linuxDriver(this, driverMode)
        LinuxVulkanDriver.resolveIcdPath(this, linuxDriverId)
            ?.let { guest.add(LinuxVulkanDriver.ENV + "=" + it) }
        // Turnip-only debug switches. PanVK/Mali sessions deliberately receive none of these. The file in
        // Downloads holds the value verbatim ("sysmem", "sysmem,deck_emu"); with nothing there, an
        // imported driver from the A710/A720/A722 legs gets "sysmem" on its own, which is what both
        // its authors advise for those GPUs and what nothing else in the list needs.
        tuDebug(linuxDriverId)?.let { guest.add("TU_DEBUG=$it") }
        // Zink renders the client's UI (Chromium -> ANGLE -> Zink -> Turnip). Lazy descriptors is
        // the mode Zink recommends where the driver has no descriptor buffer, and what Ludashi ships
        // by default for its Zink path; a switch here because on one Fold the menus run at 14 fps.
        if (SessionPrefs.zinkLazy(this)) guest.add("ZINK_DESCRIPTORS=lazy")
        // The rest of the client-interface switches (SessionPrefs): GL marshalled off the calling
        // thread, no GL error checks, and the client run as SteamOS runs it (the script reads
        // BL_STEAMDECK; it is the one that builds the command line).
        if (SessionPrefs.glThread(this)) guest.add("mesa_glthread=true")
        if (SessionPrefs.noGlError(this)) guest.add("MESA_NO_ERROR=1")
        // On the smallest supported 4 GB tier glibc can otherwise create one malloc arena per
        // active thread/CPU and retain a surprising amount of RSS across Steam's helpers. Keep the
        // change scoped to Ahorro: image quality remains 720p while memory/process overhead drops.
        if (mobilePlan.lowMemory) {
            guest.add("MALLOC_ARENA_MAX=2")
            guest.add("BL_LOW_MEMORY=1")
            // mangoapp remains a separate process even while its overlay is hidden. On the Ahorro
            // path, keep that RAM/CPU budget for Steam and the game instead.
            guest.add("BL_MANGOAPP=0")
        }
        if (SessionState.mode == MODE_STEAM) guest.add("BL_STEAMDECK=" + (if (SessionPrefs.steamDeckMode(this)) "1" else "0"))
        if (steamHere) guest.add("BL_STEAM_CHANNEL=" + SessionPrefs.steamChannel(this))
        if (SessionState.mode == MODE_STEAM) {
            guest.add("BL_GAMESCOPE_FORCE_FULLSCREEN=" + (if (SessionPrefs.forceFullscreen(this)) "1" else "0"))
            SessionPrefs.writeForceFullscreenFlag(this)
        }
        // Proton's own gate for its xalia helper (its `proton` script reads this, and sets
        // XALIA_SUPPORTED_ONLY itself otherwise). Off by default: xalia is Valve's, and on a device
        // whose seccomp answers its syscalls normally there is no reason to take it away.
        if (SessionPrefs.noXalia(this)) guest.add("PROTON_USE_XALIA=0")
        // Anything else, for a device that cannot be reached with a debugger: Downloads/droiddeck-env
        // holds KEY=VALUE lines that go into the session's environment as written, after ours, so a
        // line here wins. Zink and Turnip tunables (ZINK_DESCRIPTORS=lazy, MESA_*), gamescope's,
        // the client's - whatever the experiment needs, without a build per attempt.
        extraEnv().forEach { guest.add(it) }
        // Core masks, Bannerlator's two (cfca3912). The client's is sent whenever the override is
        // on, even naming every core: it exists to undo the pin Steam applies to its own interface
        // renderer, and the scheduler's default is exactly what that pin takes away. A game's is
        // sent only when it is a real restriction - a game has no pin of its own to undo.
        if (steamHere && SessionPrefs.clientCpusOverride(this)) {
            guest.add("BL_CLIENT_CPUS=" + CpuCores.listOrAll(SessionPrefs.clientCpus(this)))
        }
        // The game cores also pin the desktop and a program from the rail; without a choice the
        // session script picks every core but the slowest cluster for those (program_cores).
        CpuCores.restrictionOrEmpty(SessionPrefs.gameCpus(this))
            .takeIf { it.isNotEmpty() }?.let { guest.add("BL_GAME_CPUS=$it") }
    }

    /** PulseAudio, and the DirectAudio relay when a path needs it; returns the daemon for suspend and resume. */
    private fun startAudio(guest: MutableList<String>, sessionDir: File): PulseAudioComponent {
        // PulseAudio always: the client is a native Linux program and has no other way to make a
        // sound - its menus, its music and its voice chat all go through here. DirectAudio is not
        // an alternative to it on this path: it replaces the audio driver INSIDE Wine, so it
        // changes what games do and leaves the client alone. The microphone is its own opt-in on
        // top, and the helper only opens an input stream when asked - so a user who wants game
        // sound but no recording gets exactly that, and Android's recording indicator stays off.
        val wantsDirectAudio = SessionState.mode == MODE_STEAM && SessionPrefs.directAudio(this)
        val wantsMic = SessionPrefs.micEnabled(this) &&
            checkSelfPermission(android.Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
        // Both paths sit under the app's files directory, which the session binds at its own path,
        // so the same string is valid on both sides and nothing has to be translated.
        val audioDir = File(filesDir, "directaudio").apply { mkdirs() }
        val relaySocket = File(audioDir, "relay.sock")
        val micFifo = if (wantsMic) File(audioDir, "mic.fifo") else null

        val audioLog = File(sessionDir, "audio.log")
        val pulse = PulseAudioComponent(this, micFifo?.absolutePath)
        // With DirectAudio on, the client's own sound goes through the relay too: the daemon
        // fills the relay's ring and the relay, outside proot, drives the device.
        // The client's own sound: the classic AAudio sink unless the user chose the relay.
        val clientDirectAudio = SessionState.mode == MODE_STEAM && SessionPrefs.clientDirectAudio(this)
        if (clientDirectAudio) pulse.setRelaySocket(relaySocket.absolutePath)
        pulse.setLogFile(audioLog)
        pulse.attach(this)
        guest.add("PULSE_SERVER=unix:" + pulse.socket().absolutePath)
        components.add(pulse)
        if (wantsDirectAudio || wantsMic || clientDirectAudio) {
            // After the daemon in the list, so it can wait for the pipe the daemon makes.
            val relay = DirectAudioRelayComponent(relaySocket, micFifo)
            relay.setLogFile(audioLog)
            relay.attach(this)
            components.add(relay)
        }
        if (wantsDirectAudio) {
            // Read by the Proton wrappers, which point Wine at the driver and name it in the
            // prefix. Absent, they take an early return and the game uses Proton's own audio - so
            // this variable is the whole of the selection.
            guest.add("BL_DIRECTAUDIO=/" + SessionFiles.DIRECTAUDIO_DIR)
            guest.add("BANNER_AUDIO_DIRECT_RELAY=" + relaySocket.absolutePath)
        }
        Log.i(TAG, "audio: client " + (if (clientDirectAudio) "DirectAudio" else "classic AAudio sink") + (if (wantsDirectAudio) " + DirectAudio for games" else "")
            + (if (wantsMic) " + microphone" else "") +
            (if (SessionPrefs.micEnabled(this) && !wantsMic) " (microphone wanted but RECORD_AUDIO not granted)" else ""))
        return pulse
    }

    /** The fake evdev pads: the ring files the app writes and the identity SDL and Steam see. */
    private fun addControllerEnvironment(guest: MutableList<String>, fakeInputDir: File) {
        FakeInputWriter.prepareRingSlots(fakeInputDir, 4)
        guest.add("FAKE_EVDEV_DIR=" + fakeInputDir.path)
        val rings = FakeInputWriter.getRingEnv(fakeInputDir)
        if (!rings.isNullOrEmpty()) guest.add("FAKE_EVDEV_MEMFD_PATHS=$rings")
        // SDL and Steam key their mapping database on bus+vendor+product: only a known
        // identity gets the standard layout without the user configuring the pad by hand.
        guest.add("FAKE_EVDEV_IDENTITY=xbox360")
        guest.add("FAKE_EVDEV_VIBRATION=1")
        // Steam Input's virtual-gamepad identity is for games the client starts, which are
        // meant to see that pad. Everywhere else (the desktop, a program from the rail) it
        // hides the pad: SDL ignores a Steam virtual gamepad unless it runs under Steam, so
        // every SDL emulator came up with no controller. There it is a plain Xbox 360 pad.
        if (SessionState.mode == MODE_STEAM) guest.add("FAKE_EVDEV_STEAM_VIRTUAL=1")
        guest.add("SDL_JOYSTICK_DISABLE_UDEV=1")
        guest.add("SDL_HIDAPI_JOYSTICK_DISABLE_UDEV=1")
        guest.add("SDL_JOYSTICK_HIDAPI=0")
        if (File(Environment.getExternalStorageDirectory(), PAD_LOG_SWITCH).exists()) {
            guest.add("FAKE_EVDEV_LOG=1")
        }
        SessionState.fakeInputDir = fakeInputDir
    }

    /** Lo que proot monta en el guest: controles, batería, almacenamiento y biblioteca de Steam. */
    private fun sessionBinds(controllersOn: Boolean, fakeInputDir: File): ArrayList<String> {
        val binds = ArrayList<String>()
        if (controllersOn) binds.add(fakeInputDir.path + ":/dev/input")
        // The client's battery readout (the Quick Access Menu, the top bar) reads
        // /sys/class/power_supply/BAT<n>/..., a laptop's or a Deck's naming; Android's supply is
        // called "battery" and its files differ, so the client sees no battery at all. A directory
        // of our own, written from Android's battery API every few seconds, is bound over it.
        val battery = BatteryComponent(File(filesDir, "session/sys/power_supply"))
        battery.attach(this)
        components.add(battery)
        binds.add(battery.dir.path + ":/sys/class/power_supply")
        // Rumble for the on-screen pad: the fake evdev layer sends force-feedback effects to this
        // listener, which drives the phone's vibrator (see RumbleComponent).
        if (controllersOn) components.add(RumbleComponent().also { it.attach(this) })
        // Where the device's files appear inside the session. Internal storage is bound at its own
        // path already, and every program's file dialog opens at home and lists "Computer" from
        // /proc/mounts, where a proot bind never shows - so a user saw only the runtime's own
        // tree and could not find the phone at all. The same storage is placed under home as
        // también, para que Steam y los juegos puedan acceder al almacenamiento del teléfono.
        val home = File(LinuxRuntime.rootDir(this), "root")
        File(home, "Storage").mkdirs()
        binds.add(Environment.getExternalStorageDirectory().path + ":/root/Storage")
        // A second Steam library: the storage chosen in the Steam cog, at the path the runtime's
        // bannerlator-steam-library registers with the client. Nothing bound = the script removes
        // the entry, so the client never offers a place that is not there.
        val library = GameStorage.effective(this)
        if (library != null) {
            val problem = GameStorage.prepare(library.path)
            if (problem == null) {
                File(LinuxRuntime.rootDir(this), "mnt/bannerlator-sd").mkdirs()
                binds.add("${library.path}:/mnt/bannerlator-sd")
                Log.i(TAG, "game storage: ${library.path} -> /mnt/bannerlator-sd (\"${library.label}\")")
            } else {
                Log.w(TAG, "game storage: $problem; internal only this session")
            }
        } else {
            Log.i(TAG, "game storage: internal only")
        }
        // The user's own games folder (the Steam cog's "Added games"), bound at a fixed place so
        // the shortcuts the app writes point somewhere whatever storage the folder is on.
        for (root in com.droiddeck.launcher.frontend.AddedGames.roots(this)) {
            if (root.host.isDirectory && root.host.canRead()) {
                File(LinuxRuntime.rootDir(this), root.guest.removePrefix("/")).mkdirs()
                binds.add(root.host.path + ":" + root.guest)
                Log.i(TAG, "added games: ${root.host} -> ${root.guest}")
            } else {
                Log.w(TAG, "added games: ${root.host} is not a readable folder this session")
            }
        }
        return binds
    }

    private fun teardown(prootPid: Int, expectedStartTime: Long? = null) {
        fun stillSameProcess(): Boolean = expectedStartTime == null || readStat(prootPid)?.second == expectedStartTime
        if (!stillSameProcess()) return
        val tree = descendants(prootPid)
        if (!stillSameProcess()) return
        android.os.Process.sendSignal(prootPid, 15) // SIGTERM
        if (!waitForExit(prootPid, GRACE_MS)) {
            if (!stillSameProcess()) return
            Log.w(TAG, "proot $prootPid did not exit on SIGTERM; killing it")
            android.os.Process.killProcess(prootPid)
        }
        var killed = 0
        for ((pid, started) in tree) {
            val stat = readStat(pid) ?: continue       // already gone
            if (stat.second != started) continue        // same number, different process
            android.os.Process.killProcess(pid)
            killed++
        }
        if (killed > 0) Log.i(TAG, "swept $killed process(es) proot left behind")
    }

    /** Every process under [root], as (pid, start time), from a single walk of /proc. */
    private fun descendants(root: Int): List<Pair<Int, Long>> {
        val started = HashMap<Int, Long>()
        val children = HashMap<Int, MutableList<Int>>()
        File("/proc").listFiles()?.forEach { entry ->
            val pid = entry.name.toIntOrNull() ?: return@forEach
            val stat = readStat(pid) ?: return@forEach
            started[pid] = stat.second
            children.getOrPut(stat.first) { ArrayList() }.add(pid)
        }
        val out = ArrayList<Pair<Int, Long>>()
        val queue = ArrayDeque<Int>().apply { add(root) }
        val me = android.os.Process.myPid()
        while (queue.isNotEmpty()) {
            for (kid in children[queue.removeFirst()] ?: continue) {
                if (kid <= 1 || kid == me || kid == root) continue
                val when_ = started[kid] ?: continue
                out.add(Pair(kid, when_))
                queue.add(kid)
            }
        }
        return out
    }

    /** (parent pid, start time) from /proc/pid/stat, or null when the process is gone. */
    private fun readStat(pid: Int): Pair<Int, Long>? {
        val stat = try { File("/proc/$pid/stat").readText() } catch (e: Exception) { return null }
        val close = stat.lastIndexOf(')')
        if (close < 0 || close + 2 >= stat.length) return null
        val fields = stat.substring(close + 2).trim().split(Regex("\\s+"))
        if (fields.size < 20) return null
        return try { Pair(fields[1].toInt(), fields[19].toLong()) } catch (e: NumberFormatException) { null }
    }

    private fun askSteamToExit(prootPid: Int) {
        if (!File("/proc/$prootPid").exists()) return
        val request = File(LinuxRuntime.sessionRoot(this), "steam-stop")
        try {
            request.writeText("")
        } catch (e: Exception) {
            Log.w(TAG, "could not ask the Steam client to exit", e)
            return
        }
        val started = System.currentTimeMillis()
        if (waitForExit(prootPid, STEAM_PICKUP_MS) || request.exists()) {
            request.delete()
            return
        }
        val exited = waitForExit(prootPid, STEAM_EXIT_MS)
        Log.i(TAG, "Steam client ${if (exited) "exited" else "did not exit"} after ${System.currentTimeMillis() - started} ms")
    }

    private fun waitForExit(pid: Int, timeoutMs: Long): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (!File("/proc/$pid").exists()) return true
            try { Thread.sleep(50) } catch (e: InterruptedException) { return false }
        }
        return !File("/proc/$pid").exists()
    }

    /**
     * proot's --kill-on-exit takes its tracees down, but a session that died from the inside
     * (the client asserting, Xwayland going) leaves gamescopereaper and the session script
     * behind, still holding the Wayland socket and the audio server the next session needs. They
     * are our uid, so they are ours to kill.
     */
    /**
     * Every process of ours still running a program out of the Linux runtime once proot is gone.
     * proot's --kill-on-exit and the tree sweep only reach what proot still traces; a tracee it
     * lost - an Xwayland that aborted from one thread and then looped on a syscall proot's own
     * seccomp filter, with no tracer left, answers ENOSYS - survives both, reparented to init,
     * and wrote 9 GB of one line into the session log before anything killed it.
     */
    private fun killGuestLeftovers() {
        val me = android.os.Process.myPid()
        val rootfs = try { File(filesDir, "linuxfs").canonicalPath } catch (e: Exception) { return } + "/"
        val procs = File("/proc").listFiles { f -> f.name.all { it.isDigit() } } ?: return
        var killed = 0
        for (proc in procs) {
            val pid = proc.name.toIntOrNull() ?: continue
            if (pid == me) continue
            // Another uid's exe is not ours to read; a process already gone has none.
            val exe = try { File(proc, "exe").canonicalPath } catch (e: Exception) { continue }
            if (!exe.startsWith(rootfs)) continue
            android.os.Process.killProcess(pid)
            killed++
        }
        if (killed > 0) Log.w(TAG, "killed $killed guest process(es) proot no longer tracked")
    }

    private fun killStragglers() {
        val me = android.os.Process.myPid()
        val procs = File("/proc").listFiles { f -> f.name.all { it.isDigit() } } ?: return
        var killed = 0
        for (proc in procs) {
            val pid = proc.name.toIntOrNull() ?: continue
            if (pid == me) continue
            val cmdline = try {
                File(proc, "cmdline").readBytes().toString(Charsets.UTF_8).replace('\u0000', ' ')
            } catch (e: Exception) {
                continue
            }
            if (STRAGGLERS.none { cmdline.contains(it) }) continue
            android.os.Process.killProcess(pid)
            killed++
        }
        if (killed > 0) Log.w(TAG, "killed $killed leftover process(es) of a previous session")
    }

    private fun updateSuspendPolicy() {
        if (!SessionState.running) return
        if (suspendPolicy == SessionPrefs.SUSPEND_MANUAL && (!activityVisible || !screenOn)) {
            manualPauseRequested = true
        }
        val shouldSuspend = when (suspendPolicy) {
            SessionPrefs.SUSPEND_AUTO -> !activityVisible || !screenOn
            SessionPrefs.SUSPEND_MANUAL -> manualPauseRequested
            else -> false
        }
        val controller = suspendController ?: return
        if (suspendOperationPending || suspendAttemptFailed || shouldSuspend == SessionState.suspended) return
        suspendOperationPending = true
        if (shouldSuspend) {
            controller.freeze { success ->
                mainHandler.post {
                    if (!SessionState.running) return@post
                    suspendOperationPending = false
                    if (success) {
                        SessionState.suspended = true
                        SessionEvents.transition(SessionPhase.SUSPENDED, "session.suspended")
                        releaseLocks()
                        refreshNotification()
                    } else {
                        suspendAttemptFailed = true
                        Log.w(TAG, "could not confirm that the session stopped")
                    }
                    updateSuspendPolicy()
                }
            }
        } else {
            acquireLocks()
            controller.resume { success ->
                mainHandler.post {
                    if (!SessionState.running) return@post
                    suspendOperationPending = false
                    if (success) {
                        SessionState.suspended = false
                        SessionEvents.transition(SessionEvents.resumePhase(), "session.resumed")
                        refreshNotification()
                    } else {
                        suspendAttemptFailed = true
                        Log.w(TAG, "could not confirm that the session resumed")
                    }
                    updateSuspendPolicy()
                }
            }
        }
    }

    private fun finishSessionStop(status: Int, stoppedGen: Int) {
        mainHandler.post {
            if (stoppedGen != sessionGen || SessionState.running) {
                Log.i(TAG, "session $stoppedGen finished stopping after a new one started")
                return@post
            }
            SessionState.suspended = false
            SessionState.guestPid = -1
            releaseLocks()
            if (status == 0) {
                SessionEvents.transition(SessionPhase.IDLE, "session.stopped", mapOf("status" to status))
            } else {
                val code = SessionState.failureCode ?: "GUEST_EXIT"
                val message = SessionState.failureMessage ?: "La sesión invitada terminó con el código $status"
                val failureStatus = SessionState.failureStatus ?: status
                SessionEvents.fail(code, message, failureStatus)
            }
            SessionState.notifyEnded(status)
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
        }
    }

    private fun stopSession(status: Int) {
        synchronized(stopLock) {
            if (!SessionState.running) return
            SessionState.running = false
        }
        val stoppedGen = sessionGen
        SessionState.stopRequested = false
        SessionEvents.record("guest.exited", mapOf("status" to status))
        SessionEvents.transition(SessionPhase.STOPPING, "session.stopping", mapOf("status" to status))
        suspendOperationPending = false
        // proot's --kill-on-exit takes the guest tree down only if proot gets to run it, and
        // SIGKILL never lets it. A SIGKILLed proot left its tracees alive with no tracer: every
        // seccomp-trapped syscall then failed with ENOSYS, they spun on retries at a full core
        // for over an hour, and the reaper could not even open /proc to kill them. So: SIGTERM,
        // a grace for proot's own cleanup, SIGKILL only if it will not go, then a sweep of the
        // tree it had - snapshotted first, each pid checked against its start time so a number
        // reused by a new process is never touched.
        val prootPid = sessionPid
        sessionPid = -1
        val auxiliary = synchronized(auxiliaryProcessesLock) {
            auxiliaryProcesses.toMap().also { auxiliaryProcesses.clear() }
        }
        SessionTerminal.clear(sessionGen)
        val controller = suspendController
        suspendController = null
        // On its own thread, never here: stopSession runs on the main thread (the notification's
        // Stop action arrives there), and collecting means copying the compositor's log, scrubbing
        // every Steam log line by line - 42 files on one measured run - and waiting for logcat to
        // dump the crash buffer. That was about four and a half seconds of blocked main thread,
        // and Android ANR'd the app for it: the desktop session that would not let go.
        val ended = SessionPaths.take()
        if (ended != null) Thread({ collectSessionArtifacts(ended) }, "session-collect").start()
        components.reversed().forEach {
            try {
                it.stop()
            } catch (e: Exception) {
                Log.w(TAG, "stopping ${it.javaClass.simpleName}", e)
            }
        }
        components.clear()
        FakeInputWriter.releaseAllRingSlots()
        val steamClientMayRun = SessionState.mode == MODE_STEAM
        val finishAfterTeardown: () -> Unit = {
            if (prootPid > 1 || auxiliary.isNotEmpty()) {
                Thread({
                    if (prootPid > 1 && steamClientMayRun) askSteamToExit(prootPid)
                    auxiliary.forEach { (pid, started) -> teardown(pid, started) }
                    if (prootPid > 1) teardown(prootPid)
                    killGuestLeftovers()
                    finishSessionStop(status, stoppedGen)
                }, "session-teardown").start()
            } else {
                finishSessionStop(status, stoppedGen)
            }
        }
        if (prootPid > 1) acquireLocks()
        if (controller != null) controller.closeAndResume(finishAfterTeardown) else finishAfterTeardown()
    }

    /**
     * Swiped out of recents. The activity is destroyed without any of our teardown running, so the
     * guest would survive as an orphan holding the rootfs and the GPU. Treat the swipe as "quit".
     */
    override fun onTaskRemoved(rootIntent: Intent?) {
        Log.i(TAG, "task removed - ending the session")
        stopSession(0)
        super.onTaskRemoved(rootIntent)
    }

    override fun onDestroy() {
        stopSession(0)
        if (screenReceiverRegistered) {
            unregisterReceiver(screenReceiver)
            screenReceiverRegistered = false
        }
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    // ── Keeping the process alive ───────────────────────────────────────────────────────────

    private fun acquireLocks() {
        try {
            if (wakeLock?.isHeld != true) {
                val power = getSystemService(Context.POWER_SERVICE) as? PowerManager
                wakeLock = power?.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "DroidDeck:session")?.apply {
                    setReferenceCounted(false)
                    acquire(12L * 60L * 60L * 1000L)
                }
            }
            Log.i(TAG, "wake lock held=${wakeLock?.isHeld}")
        } catch (t: Throwable) {
            Log.w(TAG, "no wake lock (${t.message}) - the session may be killed in the background")
        }
        try {
            val wifi = applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
            @Suppress("DEPRECATION") // deprecated from API 29, still honoured; targetSdk is 28
            if (wifiLock?.isHeld != true) {
                wifiLock = wifi?.createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "DroidDeck:session-wifi")
                    ?.apply {
                        setReferenceCounted(false)
                        acquire()
                    }
            }
            Log.i(TAG, "wifi lock held=${wifiLock?.isHeld}")
        } catch (t: Throwable) {
            // A partial wake lock keeps the process alive but does not stop WiFi power-save from
            // throttling a backgrounded download to nothing, which is what this lock is for.
            Log.w(TAG, "no wifi lock (${t.message}) - a backgrounded download may stall")
        }
    }

    private fun releaseLocks() {
        try {
            wakeLock?.takeIf { it.isHeld }?.release()
        } catch (t: Throwable) {
            Log.w(TAG, "releasing the wake lock", t)
        }
        wakeLock = null
        try {
            wifiLock?.takeIf { it.isHeld }?.release()
        } catch (t: Throwable) {
            Log.w(TAG, "releasing the wifi lock", t)
        }
        wifiLock = null
    }

    // ── Notification ────────────────────────────────────────────────────────────────────────

    private fun buildNotification(): Notification {
        val manager = getSystemService(NotificationManager::class.java)
        // IMPORTANCE_LOW: it must never make a sound or push a heads-up over a game.
        val channel = NotificationChannel(CHANNEL_ID, getString(R.string.session_channel),
            NotificationManager.IMPORTANCE_LOW).apply {
            description = getString(R.string.session_channel_description)
            setShowBadge(false)
            setSound(null, null)
            enableVibration(false)
        }
        manager?.createNotificationChannel(channel)

        val open = PendingIntent.getActivity(
            this, 0,
            Intent(this, SessionActivity::class.java)
                .setFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT),
            PendingIntent.FLAG_IMMUTABLE,
        )
        val stop = PendingIntent.getService(
            this, 1, Intent(this, SessionService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE,
        )
        val resume = PendingIntent.getActivity(
            this, 2,
            Intent(this, SessionActivity::class.java)
                .setAction(ACTION_RESUME)
                .setFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT),
            PendingIntent.FLAG_IMMUTABLE,
        )
        return Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_session)
            .setContentTitle(getString(R.string.app_name))
            .setContentText(getString(if (SessionState.suspended) R.string.session_notification_paused else R.string.session_notification))
            .setContentIntent(open)
            .apply {
                if (SessionState.suspended) {
                    addAction(Notification.Action.Builder(null, getString(R.string.resume_session), resume).build())
                }
            }
            .addAction(Notification.Action.Builder(null, getString(R.string.stop_session), stop).build())
            .setOngoing(true)
            .setShowWhen(false)
            .apply { if (Build.VERSION.SDK_INT >= 31) setForegroundServiceBehavior(Notification.FOREGROUND_SERVICE_IMMEDIATE) }
            .build()
    }

    private fun refreshNotification() {
        getSystemService(NotificationManager::class.java)?.notify(NOTIFICATION_ID, buildNotification())
    }

    companion object {
        private const val TAG = "SessionService"
        /** Downloads file whose contents become TU_DEBUG inside the session, e.g. "sysmem". */
        private const val TU_DEBUG_SWITCH = "Download/droiddeck-tu-debug"
        /** Downloads file of KEY=VALUE lines added to the session environment verbatim. */
        private const val ENV_SWITCH = "Download/droiddeck-env"
        private const val CHANNEL_ID = "session"
        private const val NOTIFICATION_ID = 1001
        const val ACTION_STOP = "com.droiddeck.launcher.STOP_SESSION"
        const val ACTION_RESUME = "com.droiddeck.launcher.RESUME_SESSION"
        const val ACTION_HOME_GUIDE = "com.droiddeck.launcher.HOME_GUIDE"
        const val ACTION_AGENT_START = "com.droiddeck.launcher.AGENT_START"
        private const val ACTION_SUSPEND_POLICY_CHANGED = "com.droiddeck.launcher.SUSPEND_POLICY_CHANGED"
        private const val ACTION_ACTIVITY_VISIBLE = "com.droiddeck.launcher.ACTIVITY_VISIBLE"
        private const val ACTION_ACTIVITY_HIDDEN = "com.droiddeck.launcher.ACTIVITY_HIDDEN"
        private const val ACTION_TRACK_AUXILIARY = "com.droiddeck.launcher.TRACK_AUXILIARY"
        private const val ACTION_STOP_AUXILIARY = "com.droiddeck.launcher.STOP_AUXILIARY"
        private const val ACTION_AUXILIARY_EXITED = "com.droiddeck.launcher.AUXILIARY_EXITED"
        private const val EXTRA_AUXILIARY_PID = "auxiliaryPid"
        /** Command lines that can only belong to a session of ours. */
        private val STRAGGLERS = listOf("bannerlator-session", "gamescope", "Xwayland", "steamrtarm64",
            "steamwebhelper", "linuxfs/opt/android-host/proot", "/libproot.so", "pulseaudio/libpulseaudio.so")
        /** How long proot gets to run its own cleanup before it is killed outright. */
        private const val GRACE_MS = 1200L
        private const val STEAM_PICKUP_MS = 1500L
        private const val STEAM_EXIT_MS = 10_000L
        private const val NO_PAD_SWITCH = "Download/droiddeck-no-pad"
        private const val PAD_LOG_SWITCH = "Download/droiddeck-pad-log"

        const val EXTRA_MODE = "mode"
        const val MODE_STEAM = "steam"
        /** Solo para compatibilidad con intents antiguos; se redirige siempre a MODE_STEAM. */
        const val MODE_DESKTOP = "lxqt"
        /** A program inside the runtime, fullscreen under gamescope (EXTRA_PROGRAM = its path). */
        const val MODE_RUN = "run"
        const val EXTRA_PROGRAM = "program"
        /** MODE_RUN: arguments after the program (a game to boot). */
        const val EXTRA_PROGRAM_ARGS = "programArgs"
        /** MODE_STEAM: "desktop" para la interfaz clásica del cliente; por defecto usa Big Picture. */
        const val EXTRA_STEAM_UI = "steamUi"
        const val EXTRA_STEAM_URL = "steamUrl"

        fun start(
            context: Context, mode: String = MODE_STEAM, program: String? = null,
            steamUi: String? = null, steamUrl: String? = null, programArgs: Array<String>? = null,
        ) {
            val effectiveMode = if (mode == MODE_DESKTOP) MODE_STEAM else mode
            val intent = Intent(context, SessionService::class.java).putExtra(EXTRA_MODE, effectiveMode)
            if (program != null) intent.putExtra(EXTRA_PROGRAM, program)
            if (programArgs != null) intent.putExtra(EXTRA_PROGRAM_ARGS, programArgs)
            if (steamUi != null) intent.putExtra(EXTRA_STEAM_UI, steamUi)
            if (steamUrl != null) intent.putExtra(EXTRA_STEAM_URL, steamUrl)
            if (Build.VERSION.SDK_INT >= 26) context.startForegroundService(intent)
            else context.startService(intent)
        }

        fun stop(context: Context) {
            if (!SessionState.running) {
                SessionState.stopRequested = true
                return
            }
            context.startService(Intent(context, SessionService::class.java).setAction(ACTION_STOP))
        }

        fun setActivityVisible(context: Context, visible: Boolean) {
            if (!SessionState.running) return
            val action = if (visible) ACTION_ACTIVITY_VISIBLE else ACTION_ACTIVITY_HIDDEN
            context.startService(Intent(context, SessionService::class.java).setAction(action))
        }

        fun resume(context: Context) {
            if (!SessionState.running) return
            context.startService(Intent(context, SessionService::class.java).setAction(ACTION_RESUME))
        }

        fun suspendPolicyChanged(context: Context) {
            if (!SessionState.running) return
            context.startService(Intent(context, SessionService::class.java).setAction(ACTION_SUSPEND_POLICY_CHANGED))
        }

        /** Let the session service clean up the PTY's proot tree if the control screen closes. */
        fun stopTerminalProcess(context: Context, pid: Int) {
            if (pid > 1) context.startService(
                Intent(context, SessionService::class.java)
                    .setAction(ACTION_STOP_AUXILIARY)
                    .putExtra(EXTRA_AUXILIARY_PID, pid),
            )
        }

        fun registerTerminalProcess(context: Context, pid: Int) {
            if (pid > 1) context.startService(
                Intent(context, SessionService::class.java)
                    .setAction(ACTION_TRACK_AUXILIARY)
                    .putExtra(EXTRA_AUXILIARY_PID, pid),
            )
        }

        fun terminalProcessExited(context: Context, pid: Int) {
            if (pid > 1) context.startService(
                Intent(context, SessionService::class.java)
                    .setAction(ACTION_AUXILIARY_EXITED)
                    .putExtra(EXTRA_AUXILIARY_PID, pid),
            )
        }
    }
}
