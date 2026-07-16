package me.timschneeberger.rootlessjamesdsp.interop

import android.content.Context
import android.content.Intent
import me.timschneeberger.rootlessjamesdsp.interop.structure.EelVmVariable
import me.timschneeberger.rootlessjamesdsp.utils.Constants
import me.timschneeberger.rootlessjamesdsp.utils.extensions.ContextExtensions.sendLocalBroadcast
import timber.log.Timber

class JamesDspLocalEngine(
    context: Context,
    callbacks: JamesDspWrapper.JamesDspCallbacks? = null,
    private val reportSampleRate: Boolean = true,
) : JamesDspBaseEngine(context, callbacks, reportSampleRate) {
    private var handle: JamesDspHandle = JamesDspWrapper.alloc(callbacks ?: DummyCallbacks())

    private inline fun <T> withHandle(fallback: T, block: (JamesDspHandle) -> T): T =
        synchronized(this) { if (handle == 0L) fallback else block(handle) }

    override var sampleRate: Float
        set(value) {
            val applied = withHandle(false) {
                super.sampleRate = value
                JamesDspWrapper.setSamplingRate(it, value, false)
                true
            }
            if (applied && reportSampleRate)
                context.sendLocalBroadcast(Intent(Constants.ACTION_SAMPLE_RATE_UPDATED))
        }
        get() = super.sampleRate
    override var enabled: Boolean = true

    fun reserveProcessingFrames(frames: Int): Boolean =
        frames > 0 && withHandle(false) { JamesDspWrapper.setBlockSize(it, frames) }

    init {
        if(BenchmarkManager.hasBenchmarksCached())
            BenchmarkManager.loadBenchmarksFromCache()
    }

    override fun close() {
        val oldHandle = synchronized(this) {
            if (handle == 0L) return
            super.close()
            handle.also { handle = 0L }
        }
        JamesDspWrapper.free(oldHandle)
        Timber.d("Handle $oldHandle has been freed")
    }

    // Processing
    fun processInt16(input: ShortArray, output: ShortArray, offset: Int = -1, length: Int = -1)
    {
        synchronized(this) {
            if(!enabled || handle == 0L) {
                if(offset < 0 && length < 0) {
                    input.copyInto(output)
                }
                else {
                    input.copyInto(output, 0, offset, offset + length)
                }
            }
            else {
                JamesDspWrapper.processInt16(handle, input, output, offset, length)
            }
        }
    }

    fun processInt32(input: IntArray, output: IntArray, offset: Int = -1, length: Int = -1)
    {
        synchronized(this) {
            if(!enabled || handle == 0L) {
                if(offset < 0 && length < 0) {
                    input.copyInto(output)
                }
                else {
                    input.copyInto(output, 0, offset, offset + length)
                }
            }
            else {
                JamesDspWrapper.processInt32(handle, input, output, offset, length)
            }
        }
    }

    fun processFloat(input: FloatArray, output: FloatArray, offset: Int = -1, length: Int = -1)
    {
        synchronized(this) {
            if(!enabled || handle == 0L) {
                if(offset < 0 && length < 0) {
                    input.copyInto(output)
                }
                else {
                    input.copyInto(output, 0, offset, offset + length)
                }
            }
            else {
                JamesDspWrapper.processFloat(handle, input, output, offset, length)
            }
        }
    }

    // Effect config
    override fun setOutputControl(threshold: Float, release: Float, postGain: Float): Boolean {
        return withHandle(false) {
            JamesDspWrapper.setLimiter(it, threshold, release) and JamesDspWrapper.setPostGain(it, postGain)
        }
    }

    override fun setOutputLimiterEnabled(enabled: Boolean): Boolean =
        withHandle(false) { JamesDspWrapper.setLimiterEnabled(it, enabled) }

    override fun setReverb(enable: Boolean, preset: Int): Boolean
    {
        return withHandle(false) { JamesDspWrapper.setReverb(it, enable, preset) }
    }

    override fun setCrossfeed(enable: Boolean, mode: Int): Boolean
    {
        return withHandle(false) { JamesDspWrapper.setCrossfeed(it, enable, mode, 0, 0) }
    }

    override fun setCrossfeedCustom(enable: Boolean, fcut: Int, feed: Int): Boolean
    {
        return withHandle(false) { JamesDspWrapper.setCrossfeed(it, enable, 99, fcut, feed) }
    }

    override fun setBassBoost(enable: Boolean, maxGain: Float): Boolean
    {
        return withHandle(false) { JamesDspWrapper.setBassBoost(it, enable, maxGain) }
    }

    override fun setStereoEnhancement(enable: Boolean, level: Float): Boolean
    {
        return withHandle(false) { JamesDspWrapper.setStereoEnhancement(it, enable, level) }
    }

    override fun setVacuumTube(enable: Boolean, level: Float): Boolean
    {
        return withHandle(false) { JamesDspWrapper.setVacuumTube(it, enable, level) }
    }

    fun getLimiterGainReduction(): Float = withHandle(0f, JamesDspWrapper::getLimiterGainReduction)

    fun setVacuumTubeHarmonicGain(amount: Float): Boolean =
        withHandle(false) { JamesDspWrapper.setVacuumTubeHarmonicGain(it, amount) }

    override fun setMultiEqualizerInternal(
        enable: Boolean,
        filterType: Int,
        interpolationMode: Int,
        bands: DoubleArray
    ): Boolean {
        return withHandle(false) {
            JamesDspWrapper.setMultiEqualizer(it, enable, filterType, interpolationMode, bands)
        }
    }

    override fun setCompanderInternal(
        enable: Boolean,
        timeConstant: Float,
        granularity: Int,
        tfTransforms: Int,
        bands: DoubleArray
    ): Boolean {
        return withHandle(false) {
            JamesDspWrapper.setCompander(it, enable, timeConstant, granularity, tfTransforms, bands)
        }
    }

    override fun setVdcInternal(enable: Boolean, vdc: String): Boolean {
        return withHandle(false) { JamesDspWrapper.setVdc(it, enable, vdc) }
    }

    override fun setConvolverInternal(
        enable: Boolean,
        impulseResponse: FloatArray,
        irChannels: Int,
        irFrames: Int,
        irCrc: Int
    ): Boolean {
        return withHandle(false) {
            JamesDspWrapper.setConvolver(it, enable, impulseResponse, irChannels, irFrames)
        }
    }

    override fun setGraphicEqInternal(enable: Boolean, bands: String): Boolean {
        return withHandle(false) { JamesDspWrapper.setGraphicEq(it, enable, bands) }
    }

    override fun setLiveprogInternal(enable: Boolean, name: String, script: String): Boolean {
        return withHandle(false) { JamesDspWrapper.setLiveprog(it, enable, name, script) }
    }

    // Feature support
    override fun supportsEelVmAccess(): Boolean { return true }
    override fun supportsCustomCrossfeed(): Boolean { return true }

    // EEL VM utilities
    override fun enumerateEelVariables(): ArrayList<EelVmVariable>
    {
        return withHandle(arrayListOf(), JamesDspWrapper::enumerateEelVariables)
    }

    override fun manipulateEelVariable(name: String, value: Float): Boolean
    {
        return withHandle(false) { JamesDspWrapper.manipulateEelVariable(it, name, value) }
    }

    override fun freezeLiveprogExecution(freeze: Boolean)
    {
        withHandle(Unit) { JamesDspWrapper.freezeLiveprogExecution(it, freeze) }
    }
}
