package me.timschneeberger.rootlessjamesdsp.fragment

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.res.Configuration
import android.os.Bundle
import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.core.content.ContextCompat
import androidx.preference.Preference
import androidx.preference.PreferenceFragmentCompat
import androidx.preference.PreferenceScreen
import androidx.recyclerview.widget.RecyclerView
import me.timschneeberger.rootlessjamesdsp.MainApplication
import me.timschneeberger.rootlessjamesdsp.R
import me.timschneeberger.rootlessjamesdsp.adapter.RoundedRipplePreferenceGroupAdapter
import me.timschneeberger.rootlessjamesdsp.preference.DropDownPreference
import me.timschneeberger.rootlessjamesdsp.preference.MaterialSwitchPreference
import me.timschneeberger.rootlessjamesdsp.interop.PreferenceCache
import me.timschneeberger.rootlessjamesdsp.utils.Constants
import me.timschneeberger.rootlessjamesdsp.utils.RoutingObserver
import me.timschneeberger.rootlessjamesdsp.utils.extensions.ContextExtensions.registerLocalReceiver
import me.timschneeberger.rootlessjamesdsp.utils.extensions.ContextExtensions.sendLocalBroadcast
import me.timschneeberger.rootlessjamesdsp.utils.extensions.ContextExtensions.showChoiceAlert
import me.timschneeberger.rootlessjamesdsp.utils.extensions.ContextExtensions.showMultipleChoiceAlert
import me.timschneeberger.rootlessjamesdsp.utils.extensions.ContextExtensions.toast
import me.timschneeberger.rootlessjamesdsp.utils.extensions.ContextExtensions.unregisterLocalReceiver
import org.koin.android.ext.android.inject

class DeviceProfilesCardFragment : PreferenceFragmentCompat(), RoutingObserver.RoutingChangedCallback {
    private val routingObserver: RoutingObserver by inject()
    private val profileActive get() = findPreference<DropDownPreference>(getString(R.string.key_profile_active))
    private val processingStatus get() = findPreference<Preference>(getString(R.string.key_processing_status))
    private val processingBypass get() = findPreference<MaterialSwitchPreference>(getString(R.string.key_processing_bypass))
    private var processorRunning = false
    private var sampleRate = 0
    private var processingLoad = 0f
    private var underruns = 0
    private var limiterReduction = 0f
    private var darwinHeadroom = 0f
    private var bypassed = false
    private val app
        get() = requireActivity().application as MainApplication

    override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
        addPreferencesFromResource(R.xml.device_profile_preferences)
        profileActive?.onMenuItemClick = {
            when(it) {
                0 -> openCopyDialog()
                1 -> openDeleteDialog()
            }
        }
        processingBypass?.setOnPreferenceChangeListener { _, value ->
            requireContext().sendLocalBroadcast(Intent(Constants.ACTION_PROCESSING_BYPASS).apply {
                putExtra(Constants.EXTRA_PROCESSING_BYPASSED, value as Boolean)
            })
            true
        }

        routingObserver.registerOnRoutingChangeListener(this)
        requireContext().registerLocalReceiver(receiver, IntentFilter().apply {
            addAction(Constants.ACTION_PREFERENCES_UPDATED)
            addAction(Constants.ACTION_PRESET_LOADED)
            addAction(Constants.ACTION_PROCESSING_STATUS)
            addAction(Constants.ACTION_REPORT_SAMPLE_RATE)
            addAction(Constants.ACTION_SERVICE_STARTED)
            addAction(Constants.ACTION_SERVICE_STOPPED)
        })
        sampleRate = app.engineSampleRate.toInt()
        processorRunning = sampleRate > 0
        updateProcessingStatus()
    }

    override fun onDestroy() {
        requireContext().unregisterLocalReceiver(receiver)
        routingObserver.unregisterOnRoutingChangeListener(this)
        super.onDestroy()
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        onRoutingDeviceChanged(routingObserver.currentDevice)
        super.onConfigurationChanged(newConfig)
    }

    override fun onRoutingDeviceChanged(device: RoutingObserver.Device?) {
        profileActive?.summary = device?.name ?: getString(R.string.unknown_error)
        profileActive?.icon = context?.let { ctx ->
            ContextCompat.getDrawable(ctx, when(device?.group) {
                RoutingObserver.DeviceGroup.ANALOG -> R.drawable.ic_twotone_headphones_24dp
                RoutingObserver.DeviceGroup.BLUETOOTH -> R.drawable.ic_twotone_bluetooth_24dp
                RoutingObserver.DeviceGroup.HDMI -> R.drawable.ic_twotone_settings_input_hdmi_24dp
                RoutingObserver.DeviceGroup.SPEAKER -> R.drawable.ic_twotone_speaker_24dp
                RoutingObserver.DeviceGroup.USB -> R.drawable.ic_twotone_usb_24dp
                else -> R.drawable.ic_twotone_device_unknown_24dp
            })
        }
    }

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                Constants.ACTION_PROCESSING_STATUS -> {
                    processorRunning = true
                    sampleRate = intent.getIntExtra(Constants.EXTRA_SAMPLE_RATE, sampleRate)
                    processingLoad = intent.getFloatExtra(Constants.EXTRA_PROCESSING_LOAD, 0f)
                    underruns = intent.getIntExtra(Constants.EXTRA_UNDERRUN_COUNT, 0)
                    limiterReduction = intent.getFloatExtra(Constants.EXTRA_LIMITER_REDUCTION, 0f)
                    darwinHeadroom = intent.getFloatExtra(Constants.EXTRA_DARWIN_HEADROOM, 0f)
                    bypassed = intent.getBooleanExtra(Constants.EXTRA_PROCESSING_BYPASSED, false)
                }
                Constants.ACTION_REPORT_SAMPLE_RATE -> {
                    sampleRate = intent.getFloatExtra(Constants.EXTRA_SAMPLE_RATE, 0f).toInt()
                    processorRunning = sampleRate > 0
                }
                Constants.ACTION_SERVICE_STARTED -> processorRunning = true
                Constants.ACTION_SERVICE_STOPPED -> {
                    processorRunning = false
                    bypassed = false
                }
            }
            updateProcessingStatus()
        }
    }

    private fun updateProcessingStatus() {
        processingBypass?.isEnabled = processorRunning
        processingBypass?.isChecked = bypassed
        processingStatus?.summary = when {
            !processorRunning -> getString(R.string.processing_status_stopped)
            bypassed -> getString(R.string.processing_status_bypassed)
            else -> {
                val effects = activeEffects().ifEmpty { listOf(getString(R.string.processing_status_none)) }
                val details = mutableListOf<String>()
                if (sampleRate > 0) details += getString(
                    R.string.processing_status_runtime,
                    sampleRate / 1000,
                    processingLoad,
                    if (underruns == 0) getString(R.string.processing_status_stable)
                    else getString(R.string.processing_status_underruns, underruns)
                )
                if (limiterReduction >= 0.1f)
                    details += getString(R.string.processing_status_limiter, limiterReduction)
                if (darwinHeadroom >= 0.1f)
                    details += getString(R.string.processing_status_headroom, darwinHeadroom)
                (listOf(effects.joinToString(" · ")) + details).joinToString("\n")
            }
        }
    }

    private fun activeEffects(): List<String> {
        fun enabled(namespace: String, key: Int) = PreferenceCache.getPreferences(requireContext(), namespace)
            .getBoolean(getString(key), false)

        return listOfNotNull(
            getString(R.string.compander_enable).takeIf { enabled(Constants.PREF_COMPANDER, R.string.key_compander_enable) },
            getString(R.string.bass_enable).takeIf { enabled(Constants.PREF_BASS, R.string.key_bass_enable) },
            getString(R.string.eq_enable).takeIf { enabled(Constants.PREF_EQ, R.string.key_eq_enable) },
            getString(R.string.peq_enable).takeIf { enabled(Constants.PREF_PEQ, R.string.key_peq_enable) },
            getString(R.string.geq_enable).takeIf { enabled(Constants.PREF_GEQ, R.string.key_geq_enable) },
            getString(R.string.ddc_enable).takeIf { enabled(Constants.PREF_DDC, R.string.key_ddc_enable) },
            getString(R.string.darwin_enable).takeIf { enabled(Constants.PREF_DARWIN, R.string.key_darwin_enable) },
            getString(R.string.convolver_enable).takeIf { enabled(Constants.PREF_CONVOLVER, R.string.key_convolver_enable) },
            getString(R.string.liveprog_enable).takeIf { enabled(Constants.PREF_LIVEPROG, R.string.key_liveprog_enable) },
            getString(R.string.tube_enable).takeIf { enabled(Constants.PREF_TUBE, R.string.key_tube_enable) },
            getString(R.string.stereowide_enable).takeIf { enabled(Constants.PREF_STEREOWIDE, R.string.key_stereowide_enable) },
            getString(R.string.crossfeed_enable).takeIf { enabled(Constants.PREF_CROSSFEED, R.string.key_crossfeed_enable) },
            getString(R.string.reverb_enable).takeIf { enabled(Constants.PREF_REVERB, R.string.key_reverb_enable) },
        )
    }

    override fun onCreateRecyclerView(
        inflater: LayoutInflater,
        parent: ViewGroup,
        savedInstanceState: Bundle?,
    ): RecyclerView {
        return super.onCreateRecyclerView(inflater, parent, savedInstanceState).apply {
            itemAnimator = null // Fix to prevent RecyclerView crash if group is toggled rapidly
            isNestedScrollingEnabled = false
        }
    }

    override fun onCreateAdapter(preferenceScreen: PreferenceScreen): RecyclerView.Adapter<*> {
        return RoundedRipplePreferenceGroupAdapter(preferenceScreen)
    }

    private fun openCopyDialog() {
        val profiles = app.profileManager.allProfiles
        if(profiles.size <= 1) {
            context?.toast(R.string.device_profile_manage_copy_select_no_target)
            return
        }

        context?.showChoiceAlert(
            profiles.map { it.name }.toTypedArray(),
            R.string.device_profile_manage_copy_select,
            R.string.copy
        ) { srcIndex ->
            val srcProfile = profiles[srcIndex]
            val remaining = profiles.filter { it.id != srcProfile.id }

            context?.showMultipleChoiceAlert(
                remaining.map { it.name }.toTypedArray(),
                remaining.toTypedArray(),
                R.string.device_profile_manage_paste_select,
                R.string.paste
            ) { destProfiles ->
                 app.profileManager.copy(srcProfile, destProfiles.toTypedArray())
            }
        }
    }

    private fun openDeleteDialog() {
        app.profileManager.allProfiles.let { profiles ->
            context?.showMultipleChoiceAlert(
                profiles.map { it.name }.toTypedArray(),
                profiles,
                R.string.device_profile_manage_delete,
                R.string.delete
            ) {
                app.profileManager.delete(it.toTypedArray())
            }
        }
    }

    companion object {
        fun newInstance(): DeviceProfilesCardFragment {
            return DeviceProfilesCardFragment()
        }
    }
}
