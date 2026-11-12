package org.koitharu.kotatsu.reader.ui.translate

import android.content.Context
import android.content.DialogInterface
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.Filter
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.isVisible
import androidx.core.view.updatePadding
import androidx.core.widget.doAfterTextChanged
import androidx.fragment.app.FragmentManager
import androidx.transition.TransitionManager
import com.google.android.material.snackbar.Snackbar
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.launch
import org.koitharu.kotatsu.R
import org.koitharu.kotatsu.core.prefs.AppSettings
import org.koitharu.kotatsu.core.ui.sheet.BaseAdaptiveSheet
import org.koitharu.kotatsu.core.util.FileSize
import org.koitharu.kotatsu.core.util.ext.consume
import org.koitharu.kotatsu.core.util.ext.observe
import org.koitharu.kotatsu.core.util.ext.viewLifecycleScope
import org.koitharu.kotatsu.databinding.SheetTranslationSettingsBinding
import org.koitharu.kotatsu.reader.domain.translate.ReaderTranslationCoordinator
import org.koitharu.kotatsu.reader.domain.translate.api.TranslationLanguages
import org.koitharu.kotatsu.reader.domain.translate.model.TranslationProvider
import java.util.Locale
import javax.inject.Inject

@AndroidEntryPoint
class TranslationSettingsSheet : BaseAdaptiveSheet<SheetTranslationSettingsBinding>() {

	@Inject
	lateinit var settings: AppSettings

	@Inject
	lateinit var coordinator: ReaderTranslationCoordinator

	private var promptAutoEnableOnDismiss = false
	private var userTouchedEnabledSwitch = false
	private var isUpdatingSwitchProgrammatically = false

	override fun onCreate(savedInstanceState: Bundle?) {
		super.onCreate(savedInstanceState)
		promptAutoEnableOnDismiss = arguments?.getBoolean(ARG_PROMPT_AUTO_ENABLE, false) == true
	}

	override fun onCreateViewBinding(
		inflater: LayoutInflater,
		container: ViewGroup?,
	): SheetTranslationSettingsBinding = SheetTranslationSettingsBinding.inflate(inflater, container, false)

	override fun onViewBindingCreated(
		binding: SheetTranslationSettingsBinding,
		savedInstanceState: Bundle?,
	) {
		super.onViewBindingCreated(binding, savedInstanceState)
		setupProviderControls(binding)
		setupApiKeysField(binding)
		setupSourceLanguageDropdown(binding)
		setupTargetLanguageDropdown(binding)
		setupSwitches(binding)
		setupActions(binding)

		if (savedInstanceState == null && promptAutoEnableOnDismiss) {
			binding.inputLayoutApiKeys.error = getString(R.string.translation_api_key_required)
			binding.editTextApiKeys.requestFocus()
		}

		coordinator.isEnabled.observe(viewLifecycleOwner) { enabled ->
			isUpdatingSwitchProgrammatically = true
			binding.switchTranslationEnabled.isChecked = enabled
			isUpdatingSwitchProgrammatically = false
		}

		coordinator.manualDownloadProgress.observe(viewLifecycleOwner) { percent ->
			updateOcrModelUi(binding, percent)
		}
	}

	override fun onApplyWindowInsets(v: View, insets: WindowInsetsCompat): WindowInsetsCompat {
		val typeMask = WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.ime()
		viewBinding?.scrollView?.updatePadding(
			bottom = insets.getInsets(typeMask).bottom,
		)
		return insets.consume(v, typeMask, bottom = true)
	}

	override fun onDismiss(dialog: DialogInterface) {
		super.onDismiss(dialog)
		if (promptAutoEnableOnDismiss && !userTouchedEnabledSwitch) {
			val provider = settings.translationProvider
			if (provider != TranslationProvider.GEMINI || settings.translationApiKeys.isNotEmpty()) {
				coordinator.setEnabled(true, fromUserTap = false)
			}
		}
	}

	private fun setupProviderControls(binding: SheetTranslationSettingsBinding) {
		val currentProvider = settings.translationProvider
		binding.toggleGroupProvider.check(
			if (currentProvider == TranslationProvider.GEMINI) {
				R.id.button_provider_gemini
			} else {
				R.id.button_provider_google
			},
		)
		updateProviderVisibility(binding, currentProvider, animate = false)

		binding.toggleGroupProvider.addOnButtonCheckedListener { _, checkedId, isChecked ->
			if (!isChecked) return@addOnButtonCheckedListener
			val nextProvider = when (checkedId) {
				R.id.button_provider_gemini -> TranslationProvider.GEMINI
				R.id.button_provider_google -> TranslationProvider.GOOGLE
				else -> return@addOnButtonCheckedListener
			}
			settings.translationProvider = nextProvider
			updateProviderVisibility(binding, nextProvider, animate = true)
		}
	}

	private fun updateProviderVisibility(
		binding: SheetTranslationSettingsBinding,
		provider: TranslationProvider,
		animate: Boolean,
	) {
		val showGeminiFields = provider == TranslationProvider.GEMINI
		if (animate && binding.inputLayoutApiKeys.isVisible != showGeminiFields) {
			TransitionManager.beginDelayedTransition(binding.layoutContent)
		}
		binding.inputLayoutApiKeys.isVisible = showGeminiFields
		if (showGeminiFields) {
			updateApiKeyHelper(binding)
		}
	}

	private fun setupApiKeysField(binding: SheetTranslationSettingsBinding) {
		binding.editTextApiKeys.setText(settings.translationApiKeysRaw)
		updateApiKeyHelper(binding)
		binding.editTextApiKeys.doAfterTextChanged { editable ->
			settings.translationApiKeysRaw = editable?.toString().orEmpty()
			val keyCount = updateApiKeyHelper(binding)
			if (keyCount > 0) {
				binding.inputLayoutApiKeys.error = null
			}
		}
	}

	private fun updateApiKeyHelper(binding: SheetTranslationSettingsBinding): Int {
		val count = settings.translationApiKeys.size
		binding.inputLayoutApiKeys.helperText = if (count > 0) {
			resources.getQuantityString(R.plurals.translation_api_keys_count, count, count)
		} else {
			getString(R.string.translation_api_keys_empty)
		}
		return count
	}

	private fun setupSourceLanguageDropdown(binding: SheetTranslationSettingsBinding) {
		val sources = TranslationLanguages.sourceLanguages
		val locale = Locale.getDefault()
		val labels = sources.map { TranslationLanguages.getSourceDisplayName(it, locale) }
		val adapter = StaticDropdownAdapter(requireContext(), labels)
		binding.editSourceLang.setAdapter(adapter)

		val currentIndex = sources.indexOf(settings.translationSourceLang).coerceAtLeast(0)
		binding.editSourceLang.setText(labels[currentIndex], false)
		refreshOcrModelStatus(binding)

		binding.editSourceLang.setOnItemClickListener { _, _, position, _ ->
			val selected = sources.getOrNull(position) ?: return@setOnItemClickListener
			settings.translationSourceLang = selected
			refreshOcrModelStatus(binding)
		}
	}

	private fun setupTargetLanguageDropdown(binding: SheetTranslationSettingsBinding) {
		val targets = TranslationLanguages.targetLanguages
		val locale = Locale.getDefault()
		val labels = targets.map { it.displayName(locale) }
		val adapter = StaticDropdownAdapter(requireContext(), labels)
		binding.editTargetLang.setAdapter(adapter)

		val currentCode = settings.translationTargetLang
		val currentIndex = targets.indexOfFirst { it.code.equals(currentCode, ignoreCase = true) }
			.coerceAtLeast(0)
		binding.editTargetLang.setText(labels[currentIndex], false)

		binding.editTargetLang.setOnItemClickListener { _, _, position, _ ->
			val selected = targets.getOrNull(position) ?: return@setOnItemClickListener
			settings.translationTargetLang = selected.code
		}
	}

	private fun setupSwitches(binding: SheetTranslationSettingsBinding) {
		isUpdatingSwitchProgrammatically = true
		binding.switchTranslationEnabled.isChecked = coordinator.isEnabled.value
		isUpdatingSwitchProgrammatically = false

		binding.switchTranslationEnabled.setOnCheckedChangeListener { button, isChecked ->
			if (isUpdatingSwitchProgrammatically) return@setOnCheckedChangeListener
			userTouchedEnabledSwitch = true
			if (isChecked) {
				if (settings.translationProvider == TranslationProvider.GEMINI && settings.translationApiKeys.isEmpty()) {
					button.isChecked = false
					binding.inputLayoutApiKeys.error = getString(R.string.translation_api_key_required)
					binding.editTextApiKeys.requestFocus()
					return@setOnCheckedChangeListener
				}
				coordinator.setEnabled(true, fromUserTap = false)
			} else {
				coordinator.setEnabled(false, fromUserTap = false)
			}
		}

		binding.switchAutoTranslate.isChecked = settings.isTranslationAutoEnabled
		binding.switchAutoTranslate.setOnCheckedChangeListener { _, isChecked ->
			settings.isTranslationAutoEnabled = isChecked
		}

		binding.switchSkipSfx.isChecked = settings.isTranslationSkipPureSfx
		binding.switchSkipSfx.setOnCheckedChangeListener { _, isChecked ->
			settings.isTranslationSkipPureSfx = isChecked
		}

		binding.switchFilterPromo.isChecked = settings.isTranslationFilterPromo
		binding.switchFilterPromo.setOnCheckedChangeListener { _, isChecked ->
			settings.isTranslationFilterPromo = isChecked
		}
	}

	private fun setupActions(binding: SheetTranslationSettingsBinding) {
		binding.buttonDownloadOcr.setOnClickListener {
			coordinator.downloadOcrModel(settings.translationSourceLang)
		}

		binding.buttonClearCache.setOnClickListener {
			viewLifecycleScope.launch {
				val freedBytes = coordinator.clearCache()
				val message = if (freedBytes > 0L) {
					getString(
						R.string.translation_cache_cleared_size,
						FileSize.BYTES.format(requireContext(), freedBytes),
					)
				} else {
					getString(R.string.translation_cache_cleared)
				}
				Snackbar.make(binding.root, message, Snackbar.LENGTH_SHORT).show()
			}
		}
	}

	private fun refreshOcrModelStatus(binding: SheetTranslationSettingsBinding) {
		val progress = coordinator.manualDownloadProgress.value
		if (progress != null) {
			updateOcrModelUi(binding, progress)
			return
		}
		viewLifecycleScope.launch {
			val source = settings.translationSourceLang
			val ready = coordinator.isOcrModelReady(source)
			if (viewBinding == null || settings.translationSourceLang != source) return@launch
			binding.layoutOcrModelAction.isVisible = !ready
			binding.progressOcrDownload.isVisible = false
			binding.buttonDownloadOcr.isVisible = !ready
			binding.buttonDownloadOcr.isEnabled = true
			binding.inputLayoutSourceLang.helperText = getString(
				if (ready) R.string.translation_ocr_ready else R.string.translation_ocr_not_downloaded,
			)
		}
	}

	private fun updateOcrModelUi(
		binding: SheetTranslationSettingsBinding,
		downloadPercent: Int?,
	) {
		if (downloadPercent == null) {
			refreshOcrModelStatus(binding)
			return
		}
		binding.layoutOcrModelAction.isVisible = true
		binding.progressOcrDownload.isVisible = true
		binding.progressOcrDownload.isIndeterminate = downloadPercent <= 0
		if (downloadPercent > 0) {
			binding.progressOcrDownload.setProgressCompat(downloadPercent, true)
		}
		binding.buttonDownloadOcr.isVisible = false
		binding.inputLayoutSourceLang.helperText = getString(
			R.string.translation_ocr_downloading,
			downloadPercent.coerceIn(0, 100),
		)
	}

	private class StaticDropdownAdapter(
		context: Context,
		private val items: List<String>,
	) : ArrayAdapter<String>(context, android.R.layout.simple_list_item_1, items) {

		private val noOpFilter = object : Filter() {
			override fun performFiltering(constraint: CharSequence?): FilterResults = FilterResults().apply {
				values = items
				count = items.size
			}

			override fun publishResults(constraint: CharSequence?, results: FilterResults?) {
				notifyDataSetChanged()
			}
		}

		override fun getFilter(): Filter = noOpFilter
	}

	companion object {
		private const val TAG = "TranslationSettingsSheet"
		private const val ARG_PROMPT_AUTO_ENABLE = "prompt_auto_enable"

		fun show(
			fm: FragmentManager,
			highlightMissingKey: Boolean = false,
		) {
			(fm.findFragmentByTag(TAG) as? TranslationSettingsSheet)?.dismissAllowingStateLoss()
			TranslationSettingsSheet().apply {
				if (highlightMissingKey) {
					arguments = Bundle(1).apply {
						putBoolean(ARG_PROMPT_AUTO_ENABLE, true)
					}
				}
			}.show(fm, TAG)
		}
	}
}
