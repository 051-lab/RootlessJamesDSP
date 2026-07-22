package me.timschneeberger.rootlessjamesdsp.service

import android.annotation.SuppressLint
import android.app.Activity
import android.app.NotificationManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.SharedPreferences
import android.content.pm.ServiceInfo
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioPlaybackCaptureConfiguration
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.Process
import android.os.SystemClock
import androidx.annotation.RequiresApi
import androidx.core.content.getSystemService
import androidx.core.math.MathUtils.clamp
import androidx.lifecycle.Observer
import androidx.lifecycle.asLiveData
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import me.timschneeberger.rootlessjamesdsp.BuildConfig
import me.timschneeberger.rootlessjamesdsp.R
import me.timschneeberger.rootlessjamesdsp.flavor.CrashlyticsImpl
import me.timschneeberger.rootlessjamesdsp.dsp.DarwinFilterPackage
import me.timschneeberger.rootlessjamesdsp.dsp.DarwinOversampling
import me.timschneeberger.rootlessjamesdsp.dsp.Pcm16Converter
import me.timschneeberger.rootlessjamesdsp.interop.JamesDspLocalEngine
import me.timschneeberger.rootlessjamesdsp.interop.PreferenceCache
import me.timschneeberger.rootlessjamesdsp.interop.ProcessorMessageHandler
import me.timschneeberger.rootlessjamesdsp.interop.sanitizeFiniteFloat
import me.timschneeberger.rootlessjamesdsp.model.IEffectSession
import me.timschneeberger.rootlessjamesdsp.model.ProcessorMessage
import me.timschneeberger.rootlessjamesdsp.model.preference.AudioEncoding
import me.timschneeberger.rootlessjamesdsp.model.room.AppBlocklistDatabase
import me.timschneeberger.rootlessjamesdsp.model.room.AppBlocklistRepository
import me.timschneeberger.rootlessjamesdsp.model.room.BlockedApp
import me.timschneeberger.rootlessjamesdsp.model.rootless.SessionRecordingPolicyEntry
import me.timschneeberger.rootlessjamesdsp.session.rootless.OnRootlessSessionChangeListener
import me.timschneeberger.rootlessjamesdsp.session.rootless.RootlessSessionDatabase
import me.timschneeberger.rootlessjamesdsp.session.rootless.RootlessSessionManager
import me.timschneeberger.rootlessjamesdsp.session.rootless.SessionRecordingPolicyManager
import me.timschneeberger.rootlessjamesdsp.utils.Constants
import me.timschneeberger.rootlessjamesdsp.utils.Constants.ACTION_PREFERENCES_UPDATED
import me.timschneeberger.rootlessjamesdsp.utils.Constants.ACTION_SAMPLE_RATE_UPDATED
import me.timschneeberger.rootlessjamesdsp.utils.Constants.ACTION_SERVICE_HARD_REBOOT_CORE
import me.timschneeberger.rootlessjamesdsp.utils.Constants.ACTION_SERVICE_RELOAD_LIVEPROG
import me.timschneeberger.rootlessjamesdsp.utils.Constants.ACTION_SERVICE_SOFT_REBOOT_CORE
import me.timschneeberger.rootlessjamesdsp.utils.Constants.ACTION_SERVICE_UPDATE_LIVEPROG_PARAMETER
import me.timschneeberger.rootlessjamesdsp.utils.extensions.CompatExtensions.getParcelableAs
import me.timschneeberger.rootlessjamesdsp.utils.extensions.ContextExtensions.registerLocalReceiver
import me.timschneeberger.rootlessjamesdsp.utils.extensions.ContextExtensions.sendLocalBroadcast
import me.timschneeberger.rootlessjamesdsp.utils.extensions.ContextExtensions.toast
import me.timschneeberger.rootlessjamesdsp.utils.extensions.ContextExtensions.unregisterLocalReceiver
import me.timschneeberger.rootlessjamesdsp.utils.extensions.PermissionExtensions.hasRecordPermission
import me.timschneeberger.rootlessjamesdsp.utils.notifications.Notifications
import me.timschneeberger.rootlessjamesdsp.utils.notifications.ServiceNotificationHelper
import me.timschneeberger.rootlessjamesdsp.utils.preferences.Preferences
import me.timschneeberger.rootlessjamesdsp.utils.sdkAbove
import org.koin.android.ext.android.inject
import timber.log.Timber
import java.io.IOException
import java.io.File
import java.util.concurrent.atomic.AtomicReference
import kotlin.math.log10
import kotlin.math.min


@RequiresApi(Build.VERSION_CODES.Q)
class RootlessAudioProcessorService : BaseAudioProcessorService() {
    // System services
    private lateinit var mediaProjectionManager: MediaProjectionManager
    private lateinit var notificationManager: NotificationManager
    private lateinit var audioManager: AudioManager

    // Media projection token
    private var mediaProjection: MediaProjection? = null
    private var mediaProjectionStartIntent: Intent? = null

    // Processing
    @Volatile private var recreateRecorderRequested = false
    @Volatile private var recorderThread: Thread? = null
    @Volatile private var activeRecorder: AudioRecord? = null
    @Volatile private var activeTrack: AudioTrack? = null
    private lateinit var engine: JamesDspLocalEngine
    private val processorMessageHandler = ProcessorMessageHandler()
    @Volatile private var darwinEngine: JamesDspLocalEngine? = null
    @Volatile private var activeDarwinConfig: DarwinConfig? = null
    private var activeDarwinHeadroomDb = 0f
    private val pendingDarwinUpdate = AtomicReference<DarwinUpdate?>(null)
    private val pendingBypass = AtomicReference<Boolean?>(null)
    @Volatile private var requestedDarwinConfig: DarwinConfig? = null
    @Volatile private var processingSampleRate = 48000
    @Volatile private var processingBufferFrames = 4096
    private var processingBypassed = false
    private val isRunning: Boolean
        get() = recorderThread?.isAlive == true

    // Session management
    private lateinit var sessionManager: RootlessSessionManager
    private var sessionLossRetryCount = 0

    // Idle detection
    @Volatile private var isProcessorIdle = false
    @Volatile private var suspendOnIdle = false

    // Exclude restricted apps flag
    private var excludeRestrictedSessions = false

    // Termination flags
    @Volatile private var isProcessorDisposing = false
    @Volatile private var isServiceDisposing = false

    // Shared preferences
    private val preferences: Preferences.App by inject()
    private val preferencesVar: Preferences.Var by inject()

    // Room databases
    private val applicationScope = CoroutineScope(SupervisorJob())
    private val blockedAppDatabase by lazy { AppBlocklistDatabase.getDatabase(this, applicationScope) }
    private val blockedAppRepository by lazy { AppBlocklistRepository(blockedAppDatabase.appBlocklistDao()) }
    private val blockedApps by lazy { blockedAppRepository.blocklist.asLiveData() }
    private val blockedAppObserver = Observer<List<BlockedApp>?> {
        Timber.d("blockedAppObserver: Database changed; ignored=${!isRunning}")
        if(isRunning)
            recreateRecorderRequested = true
    }

    @SuppressLint("ForegroundServiceType")
    override fun onCreate() {
        super.onCreate()

        // Get reference to system services
        audioManager = getSystemService<AudioManager>()!!
        mediaProjectionManager = applicationContext.getSystemService<MediaProjectionManager>()!!
        notificationManager = getSystemService<NotificationManager>()!!

        // Setup session manager
        sessionManager = RootlessSessionManager(this)
        sessionManager.sessionDatabase.setOnSessionLossListener(onSessionLossListener)
        sessionManager.sessionDatabase.setOnAppProblemListener(onAppProblemListener)
        sessionManager.sessionDatabase.registerOnSessionChangeListener(onSessionChangeListener)
        sessionManager.sessionPolicyDatabase.registerOnRestrictedSessionChangeListener(onSessionPolicyChangeListener)

        // Setup core engine
        engine = JamesDspLocalEngine(this, processorMessageHandler).apply {
            externalDarwinConvolver = true
        }

        // Setup general-purpose broadcast receiver
        val filter = IntentFilter()
        filter.addAction(ACTION_PREFERENCES_UPDATED)
        filter.addAction(ACTION_SAMPLE_RATE_UPDATED)
        filter.addAction(ACTION_SERVICE_RELOAD_LIVEPROG)
        filter.addAction(ACTION_SERVICE_UPDATE_LIVEPROG_PARAMETER)
        filter.addAction(ACTION_SERVICE_HARD_REBOOT_CORE)
        filter.addAction(ACTION_SERVICE_SOFT_REBOOT_CORE)
        filter.addAction(Constants.ACTION_PROCESSING_BYPASS)
        registerLocalReceiver(broadcastReceiver, filter)

        // Setup shared preferences
        preferences.registerOnSharedPreferenceChangeListener(preferencesListener)
        loadFromPreferences(getString(R.string.key_powersave_suspend))
        loadFromPreferences(getString(R.string.key_session_exclude_restricted))

        // Setup database observer
        blockedApps.observeForever(blockedAppObserver)

        notificationManager.cancel(Notifications.ID_SERVICE_STARTUP)

        // No need to recreate in this stage
        recreateRecorderRequested = false

        // Launch foreground service
        startForeground(
            Notifications.ID_SERVICE_STATUS,
            ServiceNotificationHelper.createServiceNotification(this, arrayOf()),
            ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
        )
    }

    override fun onStartCommand(intent: Intent, flags: Int, startId: Int): Int {

        Timber.d("onStartCommand")

        // Handle intent action
        when (intent.action) {
            null -> {
                Timber.wtf("onStartCommand: intent.action is null")
            }
            ACTION_START -> {
                Timber.d("Starting service")
            }
            ACTION_STOP -> {
                Timber.d("Stopping service")
                stopSelf()
                return START_NOT_STICKY
            }
        }

        if (isRunning) {
            return START_NOT_STICKY
        }

        // Cancel outdated notifications
        notificationManager.cancel(Notifications.ID_SERVICE_SESSION_LOSS)
        notificationManager.cancel(Notifications.ID_SERVICE_APPCOMPAT)

        // Setup media projection
        mediaProjectionStartIntent = intent.extras?.getParcelableAs(EXTRA_MEDIA_PROJECTION_DATA)

        mediaProjection = try {
            mediaProjectionManager.getMediaProjection(
                Activity.RESULT_OK,
                mediaProjectionStartIntent!!
            )
        }
        catch (ex: Exception) {
            Timber.e("Failed to acquire media projection")
            sendLocalBroadcast(Intent(Constants.ACTION_DISCARD_AUTHORIZATION))
            Timber.e(ex)
            null
        }

        mediaProjection?.registerCallback(projectionCallback, Handler(Looper.getMainLooper()))

        if (mediaProjection != null) {
            startRecording()
            sendLocalBroadcast(Intent(Constants.ACTION_SERVICE_STARTED))
        } else {
            Timber.w("Failed to capture audio")
            stopSelf()
        }

        return START_REDELIVER_INTENT
    }

    override fun onDestroy() {
        isServiceDisposing = true

        // Stop recording and release engine
        stopRecording()
        pendingDarwinUpdate.getAndSet(null)?.engine?.close()
        applicationScope.cancel()
        engine.close()

        // Stop foreground service
        stopForeground(STOP_FOREGROUND_REMOVE)

        // Notify app about service termination
        sendLocalBroadcast(Intent(Constants.ACTION_SERVICE_STOPPED))

        // Unregister database observer
        blockedApps.removeObserver(blockedAppObserver)

        // Unregister receivers and release resources
        unregisterLocalReceiver(broadcastReceiver)
        mediaProjection?.unregisterCallback(projectionCallback)
        mediaProjection?.stop()
        mediaProjection = null
        mediaProjectionStartIntent = null

        sessionManager.sessionPolicyDatabase.unregisterOnRestrictedSessionChangeListener(onSessionPolicyChangeListener)
        sessionManager.sessionDatabase.unregisterOnSessionChangeListener(onSessionChangeListener)
        sessionManager.destroy()

        preferences.unregisterOnSharedPreferenceChangeListener(preferencesListener)
        notificationManager.cancel(Notifications.ID_SERVICE_STATUS)

        stopSelf()
        super.onDestroy()
    }

    // Preferences listener
    private val preferencesListener = SharedPreferences.OnSharedPreferenceChangeListener {
            _, key ->
        loadFromPreferences(key)
    }

    // Projection termination callback
    private val projectionCallback = object: MediaProjection.Callback() {
        override fun onStop() {
            if(isServiceDisposing) {
                // Planned shutdown
                return
            }

            Timber.w("Capture permission revoked. Stopping service.")

            sendLocalBroadcast(Intent(Constants.ACTION_DISCARD_AUTHORIZATION))

            if(!preferencesVar.get<Boolean>(R.string.key_is_activity_active))
                this@RootlessAudioProcessorService.toast(getString(R.string.capture_permission_revoked_toast))

            notificationManager.cancel(Notifications.ID_SERVICE_STATUS)
            stopSelf()
        }
    }

    // General purpose broadcast receiver
    private val broadcastReceiver: BroadcastReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                ACTION_SAMPLE_RATE_UPDATED -> if (isRunning)
                    engine.syncWithPreferences(arrayOf(Constants.PREF_CONVOLVER))
                ACTION_PREFERENCES_UPDATED -> {
                    applyOutputStages(readOutputConfig())
                    var darwinChanged = false
                    readDarwinConfig().let { config ->
                        if (config != requestedDarwinConfig) {
                            darwinChanged = true
                            prepareDarwinUpdate(config)
                        }
                    }
                    if (!darwinChanged) engine.syncWithPreferences()
                }
                ACTION_SERVICE_RELOAD_LIVEPROG -> engine.syncWithPreferences(arrayOf(Constants.PREF_LIVEPROG))
                ACTION_SERVICE_UPDATE_LIVEPROG_PARAMETER -> {
                    val name = intent.getStringExtra(Constants.EXTRA_LIVEPROG_PARAMETER_NAME)
                    val value = intent.getFloatExtra(Constants.EXTRA_LIVEPROG_PARAMETER_VALUE, Float.NaN)
                    if (name.isNullOrBlank() || !value.isFinite() ||
                        !engine.manipulateEelVariable(name, value)
                    ) {
                        engine.syncWithPreferences(arrayOf(Constants.PREF_LIVEPROG))
                    }
                }
                ACTION_SERVICE_HARD_REBOOT_CORE -> restartRecording()
                ACTION_SERVICE_SOFT_REBOOT_CORE -> requestAudioRecordRecreation()
                Constants.ACTION_PROCESSING_BYPASS -> pendingBypass.set(
                    intent.getBooleanExtra(Constants.EXTRA_PROCESSING_BYPASSED, false)
                )
            }
        }
    }

    // Session loss listener
    private val onSessionLossListener = object: RootlessSessionDatabase.OnSessionLossListener {
        override fun onSessionLost(sid: Int) {
            // Push notification if enabled
            if(!preferences.get<Boolean>(R.string.key_session_loss_ignore)) {
                // Check if retry count exceeded
                if(sessionLossRetryCount < SESSION_LOSS_MAX_RETRIES) {
                    // Retry
                    sessionLossRetryCount++
                    Timber.d("Session lost. Retry count: $sessionLossRetryCount/$SESSION_LOSS_MAX_RETRIES")
                    sessionManager.pollOnce(false)
                    restartRecording()
                    return
                }
                else {
                    sessionLossRetryCount = 0
                    Timber.d("Giving up on saving session. User interaction required.")
                }

                // Request users attention
                notificationManager.cancel(Notifications.ID_SERVICE_STATUS)
                ServiceNotificationHelper.pushSessionLossNotification(this@RootlessAudioProcessorService, mediaProjectionStartIntent)
                this@RootlessAudioProcessorService.toast(getString(R.string.session_control_loss_toast), false)
                Timber.w("Terminating service due to session loss")
                stopSelf()
            }
        }
    }

    // Session change listener
    private val onSessionChangeListener = object : OnRootlessSessionChangeListener {
        override fun onSessionChanged(sessionList: HashMap<Int, IEffectSession>) {
            isProcessorIdle = sessionList.size == 0
            Timber.d("onSessionChanged: isProcessorIdle=$isProcessorIdle")

            ServiceNotificationHelper.pushServiceNotification(
                this@RootlessAudioProcessorService,
                sessionList.map { it.value }.toTypedArray()
            )
        }
    }

    // App problem listener
    private val onAppProblemListener = object : RootlessSessionDatabase.OnAppProblemListener {
        override fun onAppProblemDetected(uid: Int) {
            // Push notification if enabled
            if(!preferences.get<Boolean>(R.string.key_session_app_problem_ignore)) {
                // Request users attention
                notificationManager.cancel(Notifications.ID_SERVICE_STATUS)

                // Determine if we should redirect instantly, or push a non-intrusive notification
                if(preferencesVar.get<Boolean>(R.string.key_is_activity_active) ||
                    preferencesVar.get<Boolean>(R.string.key_is_app_compat_activity_active)) {
                    startActivity(
                        ServiceNotificationHelper.createAppTroubleshootIntent(
                            this@RootlessAudioProcessorService,
                            mediaProjectionStartIntent,
                            uid,
                            directLaunch = true
                        )
                    )
                    notificationManager.cancel(Notifications.ID_SERVICE_APPCOMPAT)
                }
                else
                    ServiceNotificationHelper.pushAppIssueNotification(this@RootlessAudioProcessorService, mediaProjectionStartIntent, uid)

                this@RootlessAudioProcessorService.toast(getString(R.string.session_app_compat_toast), false)
                Timber.w("Terminating service due to app incompatibility; redirect user to troubleshooting options")
                stopSelf()
            }
        }
    }

    // Session policy change listener
    private val onSessionPolicyChangeListener = object : SessionRecordingPolicyManager.OnSessionRecordingPolicyChangeListener {
        override fun onSessionRecordingPolicyChanged(sessionList: HashMap<String, SessionRecordingPolicyEntry>, isMinorUpdate: Boolean) {
            if(!this@RootlessAudioProcessorService.excludeRestrictedSessions) {
                Timber.d("onRestrictedSessionChanged: blocked; excludeRestrictedSessions disabled")
                return
            }

            if(!isMinorUpdate) {
                Timber.d("onRestrictedSessionChanged: major update detected; requesting soft-reboot")
                requestAudioRecordRecreation()
            }
            else {
                Timber.d("onRestrictedSessionChanged: minor update detected")
            }
        }
    }

    private fun loadFromPreferences(key: String?){
        when (key) {
            getString(R.string.key_powersave_suspend) -> {
                suspendOnIdle = preferences.get<Boolean>(R.string.key_powersave_suspend)
                Timber.d("Suspend on idle set to $suspendOnIdle")
            }
            getString(R.string.key_session_exclude_restricted) -> {
                excludeRestrictedSessions = preferences.get<Boolean>(R.string.key_session_exclude_restricted)
                Timber.d("Exclude restricted set to $excludeRestrictedSessions")

                requestAudioRecordRecreation()
            }
        }
    }

    // Request recreation of the AudioRecord object to update AudioPlaybackRecordingConfiguration
    fun requestAudioRecordRecreation() {
        if(isProcessorDisposing || isServiceDisposing) {
            Timber.e("recreateAudioRecorder: service or processor already disposing")
            return
        }

        recreateRecorderRequested = true
    }

    // Start recording thread
    @SuppressLint("BinaryOperationInTimber")
    @Synchronized
    private fun startRecording() {
        if (isServiceDisposing || recorderThread?.isAlive == true)
            return

        // Sanity check
        if (!hasRecordPermission()) {
            Timber.e("Record audio permission missing. Can't record")
            stopSelf()
            return
        }

        // Load preferences
        val encoding = AudioEncoding.fromInt(
            preferences.get<String>(R.string.key_audioformat_encoding).toIntOrNull() ?: 1
        )
        val bufferSize = preferences.get<Float>(R.string.key_audioformat_buffersize)
            .takeIf(Float::isFinite)
            ?.toInt()
            ?.coerceIn(MIN_BUFFER_SAMPLES, MAX_BUFFER_SAMPLES)
            ?.let { it - it % 2 }
            ?: resources.getInteger(R.integer.default_audioformat_buffersize)
        val bufferFrames = bufferSize / 2
        val bufferSizeBytes = when (encoding) {
            AudioEncoding.PcmFloat -> bufferSize * Float.SIZE_BYTES
            else -> bufferSize * Short.SIZE_BYTES
        }
        val encodingFormat = when (encoding) {
            AudioEncoding.PcmShort -> AudioFormat.ENCODING_PCM_16BIT
            else -> AudioFormat.ENCODING_PCM_FLOAT
        }
        val sampleRate = clamp(determineSamplingRate(), 44100, 48000)
        processingSampleRate = sampleRate
        processingBufferFrames = bufferFrames
        if(engine.sampleRate.toInt() != sampleRate) {
            Timber.d("Sampling rate changed to ${sampleRate}Hz")
            engine.sampleRate = sampleRate.toFloat()
        }
        if (!engine.reserveProcessingFrames(bufferFrames)) {
            Timber.e("Unable to reserve $bufferFrames DSP frames")
            stopSelf()
            return
        }
        pendingDarwinUpdate.getAndSet(null)?.engine?.close()
        val darwinConfig = readDarwinConfig()
        val darwinUpdate = try {
            buildDarwinUpdate(darwinConfig, sampleRate, bufferFrames)
        } catch (ex: Exception) {
            Timber.e(ex, "Unable to initialize Darwin processing")
            reportDarwinError(darwinConfig)
            DarwinUpdate(darwinConfig.copy(enabled = false), null, 0f)
        }
        activeDarwinConfig = darwinUpdate.config
        requestedDarwinConfig = darwinUpdate.config
        activeDarwinHeadroomDb = darwinUpdate.headroomDb

        darwinEngine?.close()
        darwinEngine = darwinUpdate.engine
        engine.externalDarwinProcessing = darwinEngine != null
        engine.externalDarwinHarmonics = darwinEngine != null && darwinConfig.harmonic > 0f
        applyOutputStages(readOutputConfig())
        engine.syncWithPreferences(arrayOf(
            Constants.PREF_DARWIN,
            Constants.PREF_CONVOLVER,
            Constants.PREF_TUBE,
            Constants.PREF_OUTPUT,
        ))

        Timber.i("Sample rate: $sampleRate; Encoding: ${encoding.name}; " +
                "Buffer size: $bufferSize; Buffer size (bytes): $bufferSizeBytes ; " +
                "HAL buffer size (bytes): ${determineBufferSize()}")

        // Create recorder and track
        var recorder: AudioRecord
        val track: AudioTrack
        var trackFailure: Exception? = null
        try {
            recorder = buildAudioRecord(encodingFormat, sampleRate, bufferSizeBytes)
        }
        catch(ex: Exception) {
            Timber.e("Failed to create initial audio record")
            Timber.e(ex)
            stopSelf()
            return
        }
        track = (try {
            buildAudioTrack(
                encodingFormat,
                sampleRate,
                bufferSizeBytes
            )
        } catch (ex: Exception) {
            trackFailure = ex
            if (darwinEngine == null) null else {
            Timber.w(ex, "Audio track initialization failed with Darwin enabled; retrying without Darwin")
            darwinEngine?.close()
            darwinEngine = null
            activeDarwinHeadroomDb = 0f
            engine.externalDarwinProcessing = false
            engine.externalDarwinHarmonics = false
            applyOutputStages(readOutputConfig())
            engine.syncWithPreferences(arrayOf(
                Constants.PREF_DARWIN,
                Constants.PREF_CONVOLVER,
                Constants.PREF_TUBE,
                Constants.PREF_OUTPUT,
            ))
            try {
                buildAudioTrack(encodingFormat, sampleRate, bufferSizeBytes)
            } catch (retry: Exception) {
                ex.addSuppressed(retry)
                null
            }
            }
        }) ?: run {
            Timber.e(trackFailure, "Failed to create audio track")
            recorder.release()
            stopSelf()
            return
        }

        activeRecorder = recorder
        activeTrack = track

        // TODO Move all audio-related code to C++
        val thread = Thread {
            try {
                Process.setThreadPriority(Process.THREAD_PRIORITY_AUDIO)
                ServiceNotificationHelper.pushServiceNotification(applicationContext, arrayOf())

                val floatBuffer = FloatArray(bufferSize)
                val floatOutBuffer = FloatArray(bufferSize)
                val shortBuffer = ShortArray(bufferSize)
                val shortOutBuffer = ShortArray(bufferSize)
                val darwinFloatOutBuffer = FloatArray(bufferSize)
                val pcm16Converter = Pcm16Converter()
                val fadeFrames = min(bufferFrames, sampleRate / 50) // 20 ms
                var fadeInNextBuffer = false
                var processingLoad = 0f
                var lastStatusUpdate = 0L
                while (!isProcessorDisposing) {
                    if(recreateRecorderRequested) {
                        recreateRecorderRequested = false
                        Timber.d("Recreating recorder without stopping thread...")

                        // Suspend track, release recorder
                        activeRecorder = null
                        runCatching { recorder.stop() }
                        runCatching { track.stop() }
                        recorder.release()

                        if (isProcessorDisposing)
                            break
                        if (mediaProjection == null) {
                            Timber.e("Media projection handle is null, stopping service")
                            stopSelf()
                            return@Thread
                        }

                        // Recreate recorder with new AudioPlaybackRecordingConfiguration
                        recorder = buildAudioRecord(encodingFormat, sampleRate, bufferSizeBytes)
                        activeRecorder = recorder
                        Timber.d("Recorder recreated")
                        if (isProcessorDisposing)
                            break
                    }

                    // Suspend core while idle
                    if(isProcessorIdle && suspendOnIdle)
                    {
                        if(recorder.state == AudioRecord.STATE_INITIALIZED &&
                            recorder.recordingState == AudioRecord.RECORDSTATE_RECORDING)
                            recorder.stop()
                        if(track.state == AudioTrack.STATE_INITIALIZED &&
                            track.playState != AudioTrack.PLAYSTATE_STOPPED)
                            track.stop()

                        try {
                            Thread.sleep(50)
                        }
                        catch(e: InterruptedException) {
                            break
                        }
                        continue
                    }

                    // Resume recorder if suspended
                    if(recorder.recordingState == AudioRecord.RECORDSTATE_STOPPED) {
                        recorder.startRecording()
                    }
                    // Resume track if suspended
                    if(track.playState != AudioTrack.PLAYSTATE_PLAYING) {
                        track.play()
                    }

                    val readCount = if(encoding == AudioEncoding.PcmShort)
                        recorder.read(shortBuffer, 0, shortBuffer.size, AudioRecord.READ_BLOCKING)
                    else
                        recorder.read(floatBuffer, 0, floatBuffer.size, AudioRecord.READ_BLOCKING)
                    if (readCount <= 0) {
                        if (isProcessorDisposing) break
                        throw IOException("AudioRecord.read failed: $readCount")
                    }
                    val sampleCount = readCount - readCount % 2
                    if (sampleCount == 0)
                        continue

                    val fadingIn = fadeInNextBuffer
                    val darwinUpdate = if (!fadingIn) pendingDarwinUpdate.getAndSet(null) else null
                    val bypassUpdate = if (!fadingIn)
                        pendingBypass.getAndSet(null)?.takeIf { it != processingBypassed }
                    else null
                    val fadingOut = darwinUpdate != null || bypassUpdate != null
                    val processingStart = System.nanoTime()

                    if(encoding == AudioEncoding.PcmShort) {
                        if (processingBypassed) {
                            shortBuffer.copyInto(shortOutBuffer, endIndex = sampleCount)
                            if (fadingIn) DarwinOversampling.fadeHead(shortOutBuffer, fadeFrames, sampleCount)
                            if (fadingOut) DarwinOversampling.fadeTail(shortOutBuffer, fadeFrames, sampleCount)
                        } else {
                            pcm16Converter.decode(shortBuffer, floatBuffer, sampleCount)
                            engine.processFloat(floatBuffer, floatOutBuffer, 0, sampleCount)
                            darwinEngine?.let { darwin ->
                                darwin.processFloat(floatOutBuffer, darwinFloatOutBuffer, 0, sampleCount)
                                darwinFloatOutBuffer.copyInto(floatOutBuffer, endIndex = sampleCount)
                            }
                            if (fadingIn) DarwinOversampling.fadeHead(floatOutBuffer, fadeFrames, sampleCount)
                            if (fadingOut) DarwinOversampling.fadeTail(floatOutBuffer, fadeFrames, sampleCount)
                            pcm16Converter.encode(floatOutBuffer, shortOutBuffer, sampleCount)
                        }
                    }
                    else {
                        if (processingBypassed) floatBuffer.copyInto(floatOutBuffer, endIndex = sampleCount) else {
                            engine.processFloat(floatBuffer, floatOutBuffer, 0, sampleCount)
                            darwinEngine?.let { darwin ->
                                darwin.processFloat(floatOutBuffer, darwinFloatOutBuffer, 0, sampleCount)
                                darwinFloatOutBuffer.copyInto(floatOutBuffer, endIndex = sampleCount)
                            }
                        }
                        if (fadingIn) DarwinOversampling.fadeHead(floatOutBuffer, fadeFrames, sampleCount)
                        if (fadingOut) DarwinOversampling.fadeTail(floatOutBuffer, fadeFrames, sampleCount)
                    }

                    val processingElapsedNanos = System.nanoTime() - processingStart
                    if (encoding == AudioEncoding.PcmShort)
                        writeFully(track, shortOutBuffer, sampleCount)
                    else
                        writeFully(track, floatOutBuffer, sampleCount)

                    val bufferDurationNanos = sampleCount / 2 * 1_000_000_000.0 / sampleRate
                    processingLoad = updateProcessingLoad(processingLoad, processingElapsedNanos, bufferDurationNanos)
                    if (isProcessorDisposing) {
                        darwinUpdate?.engine?.close()
                        break
                    }

                    if (fadingOut) {
                        darwinUpdate?.let(::commitDarwinUpdate)
                        bypassUpdate?.let { processingBypassed = it }
                        fadeInNextBuffer = true
                    } else if (fadingIn) {
                        fadeInNextBuffer = false
                    }

                    SystemClock.elapsedRealtime().let { now ->
                        if (now - lastStatusUpdate >= STATUS_UPDATE_INTERVAL_MS) {
                            lastStatusUpdate = now
                            reportProcessingStatus(track, processingLoad, sampleRate)
                        }
                    }
                }
            } catch (e: IOException) {
                Timber.w(e)
                if (!isProcessorDisposing)
                    stopSelf()
            } catch (e: Exception) {
                Timber.e("Exception in recorderThread raised")
                Timber.e(e)
                stopSelf()
            } finally {
                // Clean up recorder and track
                activeRecorder = null
                activeTrack = null
                runCatching {
                    if(recorder.state == AudioRecord.STATE_INITIALIZED &&
                        recorder.recordingState == AudioRecord.RECORDSTATE_RECORDING) recorder.stop()
                }
                runCatching {
                    if(track.state == AudioTrack.STATE_INITIALIZED &&
                        track.playState != AudioTrack.PLAYSTATE_STOPPED) track.stop()
                }
                recorder.release()
                track.release()
                darwinEngine?.close()
                darwinEngine = null
                if (recorderThread === Thread.currentThread())
                    recorderThread = null
            }
        }
        recorderThread = thread
        thread.start()
    }

    // Terminate recording thread
    @Synchronized
    fun stopRecording() {
        val thread = recorderThread ?: return
        isProcessorDisposing = true
        runCatching {
            activeRecorder?.takeIf { it.recordingState == AudioRecord.RECORDSTATE_RECORDING }?.stop()
        }
        runCatching {
            activeTrack?.takeIf { it.playState != AudioTrack.PLAYSTATE_STOPPED }?.stop()
        }
        thread.interrupt()

        if (thread !== Thread.currentThread()) {
            var interrupted = false
            while (thread.isAlive) {
                try {
                    thread.join()
                } catch (_: InterruptedException) {
                    interrupted = true
                }
            }
            if (interrupted)
                Thread.currentThread().interrupt()
        }
        if (recorderThread === thread)
            recorderThread = null
    }

    private fun writeFully(track: AudioTrack, buffer: ShortArray, size: Int) {
        var offset = 0
        while (offset < size && !isProcessorDisposing) {
            val written = track.write(buffer, offset, size - offset, AudioTrack.WRITE_BLOCKING)
            if (written <= 0)
                throw IOException("AudioTrack.write failed: $written")
            offset += written
        }
    }

    private fun writeFully(track: AudioTrack, buffer: FloatArray, size: Int) {
        var offset = 0
        while (offset < size && !isProcessorDisposing) {
            val written = track.write(buffer, offset, size - offset, AudioTrack.WRITE_BLOCKING)
            if (written <= 0)
                throw IOException("AudioTrack.write failed: $written")
            offset += written
        }
    }

    // Hard restart recording thread
    @Synchronized
    fun restartRecording() {
        if(isProcessorDisposing || isServiceDisposing) {
            Timber.e("restartRecording: service or processor already disposing")
            return
        }

        stopRecording()
        isProcessorDisposing = false
        recreateRecorderRequested = false
        startRecording()
    }

    private fun buildAudioTrack(encoding: Int, sampleRate: Int, bufferSizeBytes: Int): AudioTrack {
        val attributesBuilder = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_UNKNOWN)
            .setContentType(AudioAttributes.CONTENT_TYPE_UNKNOWN)
            .setFlags(0)

        sdkAbove(Build.VERSION_CODES.Q) {
            attributesBuilder.setAllowedCapturePolicy(AudioAttributes.ALLOW_CAPTURE_BY_NONE)
        }

        val format = AudioFormat.Builder()
            .setChannelMask(AudioFormat.CHANNEL_OUT_STEREO)
            .setEncoding(encoding)
            .setSampleRate(sampleRate)
            .build()

        val frameSizeInBytes: Int = if (encoding == AudioFormat.ENCODING_PCM_16BIT) {
            2 /* channels */ * 2 /* bytes */
        } else {
            2 /* channels */ * 4 /* bytes */
        }

        val bufferSize = if (((bufferSizeBytes % frameSizeInBytes) != 0 || bufferSizeBytes < 1)) {
            Timber.e("Invalid audio buffer size $bufferSizeBytes")
            128 * (bufferSizeBytes / 128)
        }
        else bufferSizeBytes

        Timber.d("Using buffer size $bufferSize")

        return AudioTrack.Builder()
            .setAudioFormat(format)
            .setTransferMode(AudioTrack.MODE_STREAM)
            .setAudioAttributes(attributesBuilder.build())
            .setBufferSizeInBytes(bufferSize)
            .build()
    }

    @SuppressLint("MissingPermission")
    private fun buildAudioRecord(encoding: Int, sampleRate: Int, bufferSizeBytes: Int): AudioRecord {
        if (!hasRecordPermission()) {
            Timber.e("buildAudioRecord: RECORD_AUDIO not granted")
            throw RuntimeException("RECORD_AUDIO not granted")
        }

        val format = AudioFormat.Builder()
            .setEncoding(encoding)
            .setSampleRate(sampleRate)
            .setChannelMask(AudioFormat.CHANNEL_IN_STEREO)
            .build()

        val configBuilder = AudioPlaybackCaptureConfiguration.Builder(mediaProjection!!)
            .addMatchingUsage(AudioAttributes.USAGE_MEDIA)
            .addMatchingUsage(AudioAttributes.USAGE_GAME)
            .addMatchingUsage(AudioAttributes.USAGE_UNKNOWN)

        val excluded = (if(excludeRestrictedSessions)
            sessionManager.sessionPolicyDatabase.getRestrictedUids().toList()
        else {
            sessionManager.pollOnce(false)
            emptyList()
        }).toMutableList()

        blockedApps.value?.map { it.uid }?.let {
            excluded += it
        }
        excluded += Process.myUid()

        excluded.forEach { configBuilder.excludeUid(it) }
        sessionManager.sessionDatabase.setExcludedUids(excluded.toTypedArray())
        sessionManager.pollOnce(false)

        Timber.d("buildAudioRecord: Excluded UIDs: ${excluded.joinToString("; ")}")

        return AudioRecord.Builder()
            .setAudioFormat(format)
            .setBufferSizeInBytes(bufferSizeBytes)
            .setAudioPlaybackCaptureConfig(configBuilder.build())
            .build()
    }

    // Determine HAL sampling rate
    private fun determineSamplingRate(): Int {
        val sampleRateStr: String? = audioManager.getProperty(AudioManager.PROPERTY_OUTPUT_SAMPLE_RATE)
        val srate = sampleRateStr?.toIntOrNull()?.takeIf { it > 0 } ?: 48000
        Timber.i("Real HAL sampling rate is $srate")
        return srate
    }

    private fun prepareDarwinUpdate(config: DarwinConfig) {
        requestedDarwinConfig = config
        pendingDarwinUpdate.getAndSet(null)?.engine?.let { stale ->
            applicationScope.launch(Dispatchers.IO) { stale.close() }
        }
        applicationScope.launch(Dispatchers.IO) {
            val update = try {
                buildDarwinUpdate(config, processingSampleRate, processingBufferFrames)
            } catch (ex: Exception) {
                Timber.e(ex, "Invalid Darwin update; keeping the active filter")
                if (config == requestedDarwinConfig) {
                    requestedDarwinConfig = activeDarwinConfig
                    reportDarwinError(config)
                }
                return@launch
            }

            if (isServiceDisposing || config != requestedDarwinConfig || config != readDarwinConfig()) {
                update.engine?.close()
                return@launch
            }
            pendingDarwinUpdate.getAndSet(update)?.engine?.close()
        }
    }

    private fun buildDarwinUpdate(config: DarwinConfig, sampleRate: Int, bufferFrames: Int): DarwinUpdate {
        if (!config.enabled) return DarwinUpdate(config, null, 0f)

        val impulse = DarwinFilterPackage.read(
            File(me.timschneeberger.rootlessjamesdsp.preference.FileLibraryPreference.createFullPathCompat(this, config.path)),
            config.filter
        )
        val harmonicAmount = DarwinOversampling.harmonicAmount(config.harmonic)
        val gain = if (config.autoHeadroom) {
            DarwinOversampling.headroomScale(impulse.samples) *
                DarwinOversampling.harmonicHeadroomScale(harmonicAmount)
        } else 1f
        val outputConfig = readOutputConfig()
        val replacement = JamesDspLocalEngine(this, reportSampleRate = false)
        try {
            replacement.sampleRate = sampleRate.toFloat()
            check(replacement.reserveProcessingFrames(bufferFrames))
            check(replacement.setOutputControl(
                outputConfig.threshold,
                outputConfig.release,
                outputConfig.postGain,
            ))
            check(replacement.setOutputLimiterEnabled(true))
            check(replacement.setVacuumTube(config.harmonic > 0f, 0f))
            check(replacement.setVacuumTubeHarmonicGain(harmonicAmount))
            check(replacement.setConvolverCoefficients(
                DarwinOversampling.applyGain(impulse.samples, gain),
                impulse.crc
            ))
        } catch (ex: Exception) {
            replacement.close()
            throw ex
        }
        return DarwinUpdate(config, replacement, if (gain < 1f) -20f * log10(gain) else 0f)
    }

    private fun commitDarwinUpdate(update: DarwinUpdate) {
        if (update.config != requestedDarwinConfig) {
            update.engine?.let { stale ->
                applicationScope.launch(Dispatchers.IO) { stale.close() }
            }
            return
        }
        val previous = darwinEngine
        if (update.engine != null) {
            if (!engine.disableConvolver())
                Timber.w("Unable to disable the main convolver before Darwin handoff")
            if (update.config.harmonic > 0f && !engine.setVacuumTube(false, 0f))
                Timber.w("Unable to disable the main tube stage before Darwin handoff")
        }
        darwinEngine = update.engine
        activeDarwinConfig = update.config
        activeDarwinHeadroomDb = update.headroomDb
        engine.externalDarwinProcessing = update.engine != null
        engine.externalDarwinHarmonics = update.engine != null && update.config.harmonic > 0f
        applyOutputStages(readOutputConfig())
        engine.syncWithPreferences(arrayOf(
            Constants.PREF_DARWIN,
            Constants.PREF_CONVOLVER,
            Constants.PREF_TUBE,
            Constants.PREF_OUTPUT,
        ))
        previous?.let { retired ->
            applicationScope.launch(Dispatchers.IO) { retired.close() }
        }
    }

    private fun reportDarwinError(config: DarwinConfig) {
        val file = File(me.timschneeberger.rootlessjamesdsp.preference.FileLibraryPreference.createFullPathCompat(this, config.path))
        processorMessageHandler.onConvolverParseError(
            if (file.isFile) ProcessorMessage.ConvolverErrorCode.DarwinCorrupted
            else ProcessorMessage.ConvolverErrorCode.DarwinMissing
        )
    }

    private fun updateProcessingLoad(previous: Float, elapsedNanos: Long, bufferDurationNanos: Double): Float {
        val current = (elapsedNanos / bufferDurationNanos * 100.0).toFloat()
        return if (previous == 0f) current else previous * 0.9f + current * 0.1f
    }

    private fun reportProcessingStatus(track: AudioTrack, processingLoad: Float, sampleRate: Int) {
        val limiterReduction = if (processingBypassed) 0f else
            darwinEngine?.getLimiterGainReduction() ?: engine.getLimiterGainReduction()
        sendLocalBroadcast(Intent(Constants.ACTION_PROCESSING_STATUS).apply {
            putExtra(Constants.EXTRA_SAMPLE_RATE, sampleRate)
            putExtra(Constants.EXTRA_PROCESSING_LOAD, processingLoad)
            putExtra(Constants.EXTRA_UNDERRUN_COUNT, track.underrunCount)
            putExtra(Constants.EXTRA_LIMITER_REDUCTION, limiterReduction)
            putExtra(Constants.EXTRA_DARWIN_HEADROOM, activeDarwinHeadroomDb)
            putExtra(Constants.EXTRA_PROCESSING_BYPASSED, processingBypassed)
        })
    }

    private fun readDarwinConfig(): DarwinConfig {
        val prefs = PreferenceCache.getPreferences(this, Constants.PREF_DARWIN)
        return DarwinConfig(
            prefs.getBoolean(getString(R.string.key_darwin_enable), false),
            prefs.getString(getString(R.string.key_darwin_file), "").orEmpty(),
            prefs.getString(getString(R.string.key_darwin_filter), "").orEmpty(),
            sanitizeFiniteFloat(
                prefs.getFloat(getString(R.string.key_darwin_harmonic), 0f),
                0f,
                0f..100f,
            ),
            prefs.getBoolean(getString(R.string.key_darwin_auto_headroom), true)
        )
    }

    private fun readOutputConfig(): OutputConfig {
        val prefs = PreferenceCache.getPreferences(this, Constants.PREF_OUTPUT)
        return OutputConfig(
            sanitizeFiniteFloat(
                prefs.getFloat(getString(R.string.key_limiter_threshold), -0.1f),
                -0.1f,
                -60f..-0.1f,
            ),
            sanitizeFiniteFloat(
                prefs.getFloat(getString(R.string.key_limiter_release), 60f),
                60f,
                1.5f..500f,
            ),
            sanitizeFiniteFloat(
                prefs.getFloat(getString(R.string.key_output_postgain), 0f),
                0f,
                -15f..15f,
            ),
        )
    }

    private fun applyOutputStages(config: OutputConfig) {
        val finalDarwinEngine = darwinEngine
        val mainConfigured = engine.setOutputControl(
            config.threshold,
            config.release,
            if (finalDarwinEngine == null) config.postGain else 0f,
        ) and engine.setOutputLimiterEnabled(finalDarwinEngine == null)
        val darwinConfigured = finalDarwinEngine?.let {
            it.setOutputControl(config.threshold, config.release, config.postGain) and
                it.setOutputLimiterEnabled(true)
        } ?: true
        if (!mainConfigured || !darwinConfigured)
            Timber.w("Unable to configure the final output stage")
    }

    private data class DarwinConfig(
        val enabled: Boolean,
        val path: String,
        val filter: String,
        val harmonic: Float,
        val autoHeadroom: Boolean,
    )

    private data class DarwinUpdate(
        val config: DarwinConfig,
        val engine: JamesDspLocalEngine?,
        val headroomDb: Float,
    )

    private data class OutputConfig(
        val threshold: Float,
        val release: Float,
        val postGain: Float,
    )

    // Determine HAL buffer size
    private fun determineBufferSize(): Int {
        val framesPerBuffer: String? = audioManager.getProperty(AudioManager.PROPERTY_OUTPUT_FRAMES_PER_BUFFER)
        return framesPerBuffer?.toIntOrNull()?.takeIf { it > 0 } ?: 256
    }

    companion object {
        const val SESSION_LOSS_MAX_RETRIES = 1
        const val STATUS_UPDATE_INTERVAL_MS = 1000L
        const val MIN_BUFFER_SAMPLES = 128
        const val MAX_BUFFER_SAMPLES = 16384

        const val ACTION_START = BuildConfig.APPLICATION_ID + ".rootless.service.START"
        const val ACTION_STOP = BuildConfig.APPLICATION_ID + ".rootless.service.STOP"
        const val EXTRA_MEDIA_PROJECTION_DATA = "mediaProjectionData"
        const val EXTRA_APP_UID = "uid"
        const val EXTRA_APP_COMPAT_INTERNAL_CALL = "appCompatInternalCall"

        fun start(context: Context, data: Intent?) {
            try {
                context.startForegroundService(ServiceNotificationHelper.createStartIntent(context, data))
            }
            catch(ex: Exception) {
                CrashlyticsImpl.recordException(ex)
            }
        }

        fun stop(context: Context) {
            try {
                context.startForegroundService(ServiceNotificationHelper.createStopIntent(context))
            }
            catch(ex: Exception) {
                CrashlyticsImpl.recordException(ex)
            }
        }
    }
}
