package org.koitharu.kotatsu.reader.ui.upscale

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.isVisible
import androidx.core.view.updatePadding
import androidx.fragment.app.FragmentManager
import com.google.android.material.snackbar.Snackbar
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.launch
import org.koitharu.kotatsu.R
import org.koitharu.kotatsu.core.prefs.AppSettings
import org.koitharu.kotatsu.core.ui.sheet.BaseAdaptiveSheet
import org.koitharu.kotatsu.core.util.FileSize
import org.koitharu.kotatsu.core.util.ext.consume
import org.koitharu.kotatsu.core.util.ext.isLowRamDevice
import org.koitharu.kotatsu.core.util.ext.observe
import org.koitharu.kotatsu.core.util.ext.viewLifecycleScope
import org.koitharu.kotatsu.databinding.SheetUpscaleSettingsBinding
import org.koitharu.kotatsu.reader.domain.upscale.ReaderUpscaleCoordinator
import org.koitharu.kotatsu.reader.domain.upscale.model.UpscaleMode
import org.koitharu.kotatsu.reader.domain.upscale.model.UpscaleRuntimeStatus
import org.koitharu.kotatsu.reader.domain.upscale.model.UpscaleStage
import javax.inject.Inject

@AndroidEntryPoint
class UpscaleSettingsSheet : BaseAdaptiveSheet<SheetUpscaleSettingsBinding>() {

	@Inject
	lateinit var settings: AppSettings

	@Inject
	lateinit var coordinator: ReaderUpscaleCoordinator

	private var isUpdatingProgrammatically = false

	override fun onCreateViewBinding(
		inflater: LayoutInflater,
		container: ViewGroup?,
	): SheetUpscaleSettingsBinding = SheetUpscaleSettingsBinding.inflate(inflater, container, false)

	override fun onViewBindingCreated(
		binding: SheetUpscaleSettingsBinding,
		savedInstanceState: Bundle?,
	) {
		super.onViewBindingCreated(binding, savedInstanceState)
		setupModeToggle(binding)
		setupSwitches(binding)
		setupActions(binding)

		coordinator.settingsSnapshot.observe(viewLifecycleOwner) { snap ->
			bindModeUi(binding, snap.mode)
			isUpdatingProgrammatically = true
			binding.switchUpscaleHighRes.isChecked = snap.upscaleHighRes
			binding.switchUpscaleCompare.isEnabled = snap.mode.isEnabled
			if (!snap.mode.isEnabled) {
				binding.switchUpscaleCompare.isChecked = false
			}
			isUpdatingProgrammatically = false
		}

		coordinator.compareEnabled.observe(viewLifecycleOwner) { compare ->
			isUpdatingProgrammatically = true
			binding.switchUpscaleCompare.isChecked = compare && settings.upscaleMode.isEnabled
			isUpdatingProgrammatically = false
		}

		coordinator.runtimeStatus.observe(viewLifecycleOwner) { status ->
			bindRuntimeStatus(binding, status)
		}

		coordinator.manualDownloadProgress.observe(viewLifecycleOwner) {
			bindRuntimeStatus(binding, coordinator.runtimeStatus.value)
		}
	}

	override fun onApplyWindowInsets(v: View, insets: WindowInsetsCompat): WindowInsetsCompat {
		val typeMask = WindowInsetsCompat.Type.systemBars()
		viewBinding?.scrollView?.updatePadding(
			bottom = insets.getInsets(typeMask).bottom,
		)
		return insets.consume(v, typeMask, bottom = true)
	}

	private fun setupModeToggle(binding: SheetUpscaleSettingsBinding) {
		bindModeUi(binding, settings.upscaleMode)
		binding.toggleGroupUpscaleMode.addOnButtonCheckedListener { _, checkedId, isChecked ->
			if (!isChecked || isUpdatingProgrammatically) return@addOnButtonCheckedListener
			val nextMode = when (checkedId) {
				R.id.button_upscale_fast -> UpscaleMode.FAST
				R.id.button_upscale_ai -> UpscaleMode.AI
				else -> UpscaleMode.OFF
			}
			settings.upscaleMode = nextMode
			if (nextMode == UpscaleMode.AI && !coordinator.isAiModelReady()) {
				coordinator.downloadAiModel()
			}
			bindModeUi(binding, nextMode)
		}
	}

	private fun bindModeUi(binding: SheetUpscaleSettingsBinding, mode: UpscaleMode) {
		val targetButtonId = when (mode) {
			UpscaleMode.OFF -> R.id.button_upscale_off
			UpscaleMode.FAST -> R.id.button_upscale_fast
			UpscaleMode.AI -> R.id.button_upscale_ai
		}
		if (binding.toggleGroupUpscaleMode.checkedButtonId != targetButtonId) {
			isUpdatingProgrammatically = true
			binding.toggleGroupUpscaleMode.check(targetButtonId)
			isUpdatingProgrammatically = false
		}

		binding.textViewUpscaleModeHint.setText(
			when (mode) {
				UpscaleMode.OFF -> R.string.upscale_hint_off
				UpscaleMode.FAST -> R.string.upscale_hint_fast
				UpscaleMode.AI -> R.string.upscale_hint_ai
			},
		)

		val isLowRam = binding.root.context.isLowRamDevice()
		binding.textViewUpscaleDeviceHint.isVisible = isLowRam
		if (isLowRam) {
			binding.textViewUpscaleDeviceHint.setText(
				if (mode == UpscaleMode.AI) {
					R.string.upscale_low_ram_ai_hint
				} else {
					R.string.upscale_low_ram_hint
				},
			)
		}

		bindRuntimeStatus(binding, coordinator.runtimeStatus.value)
	}

	private fun bindRuntimeStatus(
		binding: SheetUpscaleSettingsBinding,
		status: UpscaleRuntimeStatus,
	) {
		val mode = settings.upscaleMode
		binding.textViewUpscaleProcessing.isVisible = mode.isEnabled && status.activeJobs > 0

		val showModelBox = mode == UpscaleMode.AI || status.stage.isModelBusy()
		binding.layoutUpscaleModelStatus.isVisible = showModelBox
		if (!showModelBox) return

		val manualPercent = coordinator.manualDownloadProgress.value
		val isDownloading = manualPercent != null || status.stage == UpscaleStage.DOWNLOADING_MODEL
		val isInitializing = status.stage == UpscaleStage.INITIALIZING_MODEL
		val ready = coordinator.isAiModelReady()

		when {
			isDownloading -> {
				val pct = (manualPercent ?: status.downloadPercent).coerceIn(0, 100)
				binding.textViewUpscaleModelStatus.text = getString(R.string.upscale_model_downloading, pct)
				binding.progressUpscaleDownload.isVisible = true
				binding.progressUpscaleDownload.isIndeterminate = pct <= 0
				if (pct > 0) {
					binding.progressUpscaleDownload.setProgressCompat(pct, true)
				}
				binding.buttonDownloadUpscaleModel.isVisible = false
			}

			isInitializing -> {
				binding.textViewUpscaleModelStatus.setText(R.string.upscale_model_initializing)
				binding.progressUpscaleDownload.isVisible = true
				binding.progressUpscaleDownload.isIndeterminate = true
				binding.buttonDownloadUpscaleModel.isVisible = false
			}

			status.stage == UpscaleStage.ERROR && !status.errorMessage.isNullOrBlank() -> {
				binding.textViewUpscaleModelStatus.text = getString(
					R.string.upscale_error_generic,
					status.errorMessage,
				)
				binding.progressUpscaleDownload.isVisible = false
				binding.buttonDownloadUpscaleModel.isVisible = !ready
			}

			ready -> {
				binding.textViewUpscaleModelStatus.setText(R.string.upscale_model_ready)
				binding.progressUpscaleDownload.isVisible = false
				binding.buttonDownloadUpscaleModel.isVisible = false
			}

			else -> {
				binding.textViewUpscaleModelStatus.setText(R.string.upscale_model_not_downloaded)
				binding.progressUpscaleDownload.isVisible = false
				binding.buttonDownloadUpscaleModel.isVisible = true
			}
		}
	}

	private fun setupSwitches(binding: SheetUpscaleSettingsBinding) {
		isUpdatingProgrammatically = true
		binding.switchUpscaleCompare.isEnabled = settings.upscaleMode.isEnabled
		binding.switchUpscaleCompare.isChecked =
			settings.upscaleMode.isEnabled && coordinator.compareEnabled.value
		binding.switchUpscaleHighRes.isChecked = settings.isUpscaleHighResEnabled
		isUpdatingProgrammatically = false

		binding.switchUpscaleCompare.setOnCheckedChangeListener { _, isChecked ->
			if (isUpdatingProgrammatically) return@setOnCheckedChangeListener
			coordinator.setCompareEnabled(isChecked)
		}

		binding.switchUpscaleHighRes.setOnCheckedChangeListener { _, isChecked ->
			if (isUpdatingProgrammatically) return@setOnCheckedChangeListener
			settings.isUpscaleHighResEnabled = isChecked
		}
	}

	private fun setupActions(binding: SheetUpscaleSettingsBinding) {
		binding.buttonDownloadUpscaleModel.setOnClickListener {
			coordinator.downloadAiModel()
		}

		binding.buttonClearUpscaleCache.setOnClickListener {
			viewLifecycleScope.launch {
				val freedBytes = coordinator.clearCache()
				val message = if (freedBytes > 0L) {
					getString(
						R.string.upscale_cache_cleared_size,
						FileSize.BYTES.format(requireContext(), freedBytes),
					)
				} else {
					getString(R.string.upscale_cache_cleared)
				}
				Snackbar.make(binding.root, message, Snackbar.LENGTH_SHORT).show()
			}
		}
	}

	companion object {
		private const val TAG = "UpscaleSettingsSheet"

		fun show(fm: FragmentManager) {
			(fm.findFragmentByTag(TAG) as? UpscaleSettingsSheet)?.dismissAllowingStateLoss()
			UpscaleSettingsSheet().show(fm, TAG)
		}
	}
}
