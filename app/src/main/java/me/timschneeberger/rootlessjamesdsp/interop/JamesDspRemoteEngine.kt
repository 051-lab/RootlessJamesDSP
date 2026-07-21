package me.timschneeberger.rootlessjamesdsp.interop

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.audiofx.AudioEffect
import android.media.audiofx.AudioEffectHidden
import me.timschneeberger.rootlessjamesdsp.MainApplication
import me.timschneeberger.rootlessjamesdsp.interop.structure.EelVmVariable
import me.timschneeberger.rootlessjamesdsp.utils.Constants
import me.timschneeberger.rootlessjamesdsp.utils.extensions.AudioEffectExtensions.getParameterInt
import me.timschneeberger.rootlessjamesdsp.utils.extensions.AudioEffectExtensions.setParameter
import me.timschneeberger.rootlessjamesdsp.utils.extensions.AudioEffectExtensions.setParameterCharBuffer
import me.timschneeberger.rootlessjamesdsp.utils.extensions.AudioEffectExtensions.setParameterFloatArray
import me.timschneeberger.rootlessjamesdsp.utils.extensions.AudioEffectExtensions.setParameterImpulseResponseBuffer
import me.timschneeberger.rootlessjamesdsp.utils.extensions.ContextExtensions.registerLocalReceiver
import me.timschneeberger.rootlessjamesdsp.utils.extensions.ContextExtensions.showAlert
import me.timschneeberger.rootlessjamesdsp.utils.extensions.ContextExtensions.toast
import me.timschneeberger.rootlessjamesdsp.utils.extensions.ContextExtensions.unregisterLocalReceiver
import me.timschneeberger.rootlessjamesdsp.utils.extensions.crc
import me.timschneeberger.rootlessjamesdsp.utils.extensions.toShort
import timber.log.Timber
import java.util.UUID
import kotlin.math.roundToInt

class JamesDspRemoteEngine(
    context: Context,
    val sessionId: Int,
    val priority: Int,
    callbacks: JamesDspWrapper.JamesDspCallbacks? = null,
) : JamesDspBaseEngine(context, callbacks) {

    private val effectLock = Any()

    private val broadcastReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                Constants.ACTION_SAMPLE_RATE_UPDATED -> syncWithPreferences(arrayOf(Constants.PREF_CONVOLVER))
                Constants.ACTION_PREFERENCES_UPDATED -> syncWithPreferences()
                Constants.ACTION_SERVICE_RELOAD_LIVEPROG -> syncWithPreferences(arrayOf(Constants.PREF_LIVEPROG))
                Constants.ACTION_SERVICE_UPDATE_LIVEPROG_PARAMETER -> syncWithPreferences(arrayOf(Constants.PREF_LIVEPROG))
                Constants.ACTION_SERVICE_HARD_REBOOT_CORE -> rebootEngine()
                Constants.ACTION_SERVICE_SOFT_REBOOT_CORE -> { clearCache(); syncWithPreferences() }
            }
        }
    }

    private var effect: AudioEffectHidden? = createEffect()

    private fun <T> withEffect(fallback: T, action: (AudioEffectHidden) -> T): T =
        synchronized(effectLock) {
            val current = effect ?: return@synchronized fallback
            try {
                action(current)
            } catch (ex: IllegalStateException) {
                Timber.e(ex, "AudioEffect is in an invalid state")
                fallback
            }
        }

    override var enabled: Boolean
        set(value) { withEffect(Unit) { it.enabled = value } }
        get() = withEffect(false) { it.enabled }

    override var sampleRate: Float
        get() {
            super.sampleRate = withEffect(-0f) { it.getParameterInt(20001)?.toFloat() ?: -0f }
            return super.sampleRate
        }
        set(_){}

    init {
        syncWithPreferences()

        val filter = IntentFilter()
        filter.addAction(Constants.ACTION_PREFERENCES_UPDATED)
        filter.addAction(Constants.ACTION_SAMPLE_RATE_UPDATED)
        filter.addAction(Constants.ACTION_SERVICE_RELOAD_LIVEPROG)
        filter.addAction(Constants.ACTION_SERVICE_UPDATE_LIVEPROG_PARAMETER)
        filter.addAction(Constants.ACTION_SERVICE_HARD_REBOOT_CORE)
        filter.addAction(Constants.ACTION_SERVICE_SOFT_REBOOT_CORE)
        context.registerLocalReceiver(broadcastReceiver, filter)
    }

    private fun createEffect(): AudioEffectHidden {
        return try {
            AudioEffectHidden(EFFECT_TYPE_CUSTOM, EFFECT_JAMESDSP, priority, sessionId)
        } catch (e: Exception) {
            Timber.e("Failed to create JamesDSP effect")
            Timber.e(e)
            throw IllegalStateException(e)
        }
    }

    private fun checkEngine() {
        synchronized(effectLock) {
            val current = effect ?: return
            val currentPid = current.getParameterInt(20002) ?: -1
            val currentSampleRate = current.getParameterInt(20001) ?: -1
            if (currentPid <= 0) {
                Timber.e("PID ($currentPid) for session $sessionId invalid. Engine probably crashed or detached.")
                context.toast("Engine crashed. Rebooting JamesDSP.", false)
                rebootEngineLocked()
            } else if (currentSampleRate <= 0) {
                Timber.e("Sampling rate ($currentSampleRate) for session $sessionId invalid. Engine crashed.")
                context.toast("Abnormal sampling rate. Rebooting JamesDSP.", false)
                rebootEngineLocked()
            }
        }
    }

    private fun rebootEngine() {
        synchronized(effectLock) {
            rebootEngineLocked()
        }
    }

    private fun rebootEngineLocked() {
        try {
            effect?.release()
            effect = createEffect()
        }
        catch (ex: IllegalStateException) {
            Timber.e("Failed to re-instantiate JamesDSP effect")
            Timber.e(ex.cause)
            effect = null
        }
    }

    override fun syncWithPreferences(forceUpdateNamespaces: Array<String>?) {
        if (synchronized(effectLock) { effect == null }) {
            Timber.d("Rejecting update due to disposed engine")
            return
        }

        checkEngine()
        super.syncWithPreferences(forceUpdateNamespaces)
    }

    override fun close() {
        context.unregisterLocalReceiver(broadcastReceiver)
        super.close()
        synchronized(effectLock) {
            effect?.release()
            effect = null
        }
    }

    override fun setOutputControl(threshold: Float, release: Float, postGain: Float): Boolean {
        return withEffect(false) { current ->
            current.setParameterFloatArray(
                1500,
                floatArrayOf(threshold, release, postGain)
            ) == AudioEffect.SUCCESS
        }
    }

    override fun setCompanderInternal(
        enable: Boolean,
        timeConstant: Float,
        granularity: Int,
        tfTransforms: Int,
        bands: DoubleArray
    ): Boolean {
        return withEffect(false) { current ->
            (current.setParameterFloatArray(
                115,
                floatArrayOf(timeConstant, granularity.toFloat(), tfTransforms.toFloat()) + bands.map { it.toFloat() }
            ) == AudioEffect.SUCCESS) and
                (current.setParameter(1200, enable.toShort()) == AudioEffect.SUCCESS)
        }
    }

    override fun setReverb(enable: Boolean, preset: Int): Boolean {
        return withEffect(false) { current ->
            var ret = true
            if (enable)
                ret = current.setParameter(128, preset.toShort()) == AudioEffect.SUCCESS
            ret and (current.setParameter(1203, enable.toShort()) == AudioEffect.SUCCESS)
        }
    }

    override fun setCrossfeed(enable: Boolean, mode: Int): Boolean {
        return withEffect(false) { current ->
            var ret = true
            if (enable)
                ret = current.setParameter(188, mode.toShort()) == AudioEffect.SUCCESS
            ret and (current.setParameter(1208, enable.toShort()) == AudioEffect.SUCCESS)
        }
    }

    override fun setCrossfeedCustom(enable: Boolean, fcut: Int, feed: Int): Boolean {
        throw UnsupportedOperationException()
    }

    override fun setBassBoost(enable: Boolean, maxGain: Float): Boolean {
        return withEffect(false) { current ->
            var ret = true
            if (enable)
                ret = current.setParameter(112, maxGain.roundToInt().toShort()) == AudioEffect.SUCCESS
            ret and (current.setParameter(1201, enable.toShort()) == AudioEffect.SUCCESS)
        }
    }

    override fun setStereoEnhancement(enable: Boolean, level: Float): Boolean {
        return withEffect(false) { current ->
            var ret = true
            if (enable)
                ret = current.setParameter(137, level.roundToInt().toShort()) == AudioEffect.SUCCESS
            ret and (current.setParameter(1204, enable.toShort()) == AudioEffect.SUCCESS)
        }
    }

    override fun setVacuumTube(enable: Boolean, level: Float): Boolean {
        return withEffect(false) { current ->
            var ret = true
            if (enable)
                ret = current.setParameter(150, (level * 1000).roundToInt().toShort()) == AudioEffect.SUCCESS
            ret and (current.setParameter(1206, enable.toShort()) == AudioEffect.SUCCESS)
        }
    }

    override fun setMultiEqualizerInternal(
        enable: Boolean,
        filterType: Int,
        interpolationMode: Int,
        bands: DoubleArray,
    ): Boolean {
        return withEffect(false) { current ->
            var ret = true

            if (enable) {
                val properties = floatArrayOf(
                    filterType.toFloat(),
                    if(interpolationMode == 1) 1.0f else -1.0f
                ) + bands.map { it.toFloat() }
                ret = current.setParameterFloatArray(116, properties) == AudioEffect.SUCCESS
            }

            ret and (current.setParameter(1202, enable.toShort()) == AudioEffect.SUCCESS)
        }
    }

    override fun setVdcInternal(enable: Boolean, vdc: String): Boolean {
        return withEffect(false) { current ->
            val prevCrc = current.getParameterInt(30001) ?: -1
            val currentCrc = vdc.crc()

            Timber.i("VDC hash before: $prevCrc, current: $currentCrc")
            if (prevCrc != currentCrc && enable) {
                if (current.setParameterCharBuffer(12001, 10009, vdc) != AudioEffect.SUCCESS)
                    return@withEffect false
                if (current.setParameter(25001, currentCrc) != AudioEffect.SUCCESS)
                    return@withEffect false
            }

            current.setParameter(1212, enable.toShort()) == AudioEffect.SUCCESS
        }
    }

    override fun setConvolverInternal(
        enable: Boolean,
        impulseResponse: FloatArray,
        irChannels: Int,
        irFrames: Int,
        irCrc: Int
    ): Boolean {
        return withEffect(false) { current ->
            val prevCrc = current.getParameterInt(30003) ?: -1

            Timber.i("Convolver hash before: $prevCrc, current: $irCrc")
            if (prevCrc != irCrc && enable) {
                if (current.setParameterImpulseResponseBuffer(
                        12000,
                        10004,
                        impulseResponse,
                        irChannels
                    ) != AudioEffect.SUCCESS
                ) return@withEffect false
                if (current.setParameter(25003, irCrc) != AudioEffect.SUCCESS)
                    return@withEffect false
            }

            current.setParameter(1205, enable.toShort()) == AudioEffect.SUCCESS
        }
    }

    override fun setGraphicEqInternal(enable: Boolean, bands: String): Boolean {
        return withEffect(false) { current ->
            val prevCrc = current.getParameterInt(30000) ?: -1
            val currentCrc = bands.crc()

            Timber.i("GraphicEQ hash before: $prevCrc, current: $currentCrc")
            if (prevCrc != currentCrc && enable) {
                if (current.setParameterCharBuffer(12001, 10006, bands) != AudioEffect.SUCCESS)
                    return@withEffect false
                if (current.setParameter(25000, currentCrc) != AudioEffect.SUCCESS)
                    return@withEffect false
            }

            current.setParameter(1210, enable.toShort()) == AudioEffect.SUCCESS
        }
    }

    override fun setLiveprogInternal(enable: Boolean, name: String, script: String): Boolean {
        return withEffect(false) { current ->
            val prevCrc = current.getParameterInt(30002) ?: -1
            val currentCrc = script.crc()

            Timber.i("Liveprog hash before: $prevCrc, current: $currentCrc")
            if (prevCrc != currentCrc && enable) {
                if (current.setParameterCharBuffer(12001, 10010, script) != AudioEffect.SUCCESS)
                    return@withEffect false
                if (current.setParameter(25002, currentCrc) != AudioEffect.SUCCESS)
                    return@withEffect false
            }

            current.setParameter(1213, enable.toShort()) == AudioEffect.SUCCESS
        }
    }

    // Feature support
    override fun supportsEelVmAccess(): Boolean { return false }
    override fun supportsCustomCrossfeed(): Boolean { return false }

    // EEL VM utilities (unavailable)
    override fun enumerateEelVariables(): ArrayList<EelVmVariable> { return arrayListOf() }
    override fun manipulateEelVariable(name: String, value: Float): Boolean { return false }
    override fun freezeLiveprogExecution(freeze: Boolean) {}

    // Status
    val pid: Int
        get() = withEffect(-1) { it.getParameterInt(20002) ?: -1 }
    val isPidValid: Boolean
        get() = pid > 0
    val isSampleRateAbnormal: Boolean
        get() = sampleRate <= 0
    val paramCommitCount: Int
        get() = withEffect(-1) { it.getParameterInt(19998) ?: -1 }
    val isPresetInitialized: Boolean
        get() = paramCommitCount > 0
    val bufferLength: Int
        get() = withEffect(-1) { it.getParameterInt(19999) ?: -1 }
    val allocatedBlockLength: Int
        get() = withEffect(-1) { it.getParameterInt(20000) ?: -1 }
    val graphicEqHash: Int
        get() = withEffect(-1) { it.getParameterInt(30000) ?: -1 }
    val ddcHash: Int
        get() = withEffect(-1) { it.getParameterInt(30001) ?: -1 }
    val liveprogHash: Int
        get() = withEffect(-1) { it.getParameterInt(30002) ?: -1 }
    val convolverHash: Int
        get() = withEffect(-1) { it.getParameterInt(30003) ?: -1 }

    enum class PluginState {
        Unavailable,
        Available,
        Unsupported
    }

    companion object {
        private val EFFECT_TYPE_CUSTOM = UUID.fromString("f98765f4-c321-5de6-9a45-123459495ab2")
        private val EFFECT_JAMESDSP = UUID.fromString("f27317f4-c984-4de6-9a90-545759495bf2")

        fun isPluginInstalled(): PluginState {
            return try {
                AudioEffect
                    .queryEffects()
                    .orEmpty()
                    .firstOrNull { it.uuid == EFFECT_JAMESDSP }
                    ?.run {
                        if(name.contains("v3")) PluginState.Unsupported else PluginState.Available
                    } ?: PluginState.Unavailable
            } catch (e: Exception) {
                Timber.e("isPluginInstalled: exception raised")
                Timber.e(e)
                MainApplication.instance.showAlert(
                    "Error while checking audio effect status",
                    "Unexpected error while checking whether JamesDSP's audio effect library is installed. \n\n" +
                            "Error: $e",
                )
                PluginState.Unavailable
            }
        }
    }
}
