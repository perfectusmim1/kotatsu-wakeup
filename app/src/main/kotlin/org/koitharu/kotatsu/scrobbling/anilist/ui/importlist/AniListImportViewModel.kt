package org.koitharu.kotatsu.scrobbling.anilist.ui.importlist

import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import org.koitharu.kotatsu.R
import org.koitharu.kotatsu.core.model.FavouriteCategory
import org.koitharu.kotatsu.core.ui.BaseViewModel
import org.koitharu.kotatsu.core.util.ext.MutableEventFlow
import org.koitharu.kotatsu.core.util.ext.call
import org.koitharu.kotatsu.favourites.domain.FavouritesRepository
import org.koitharu.kotatsu.scrobbling.anilist.domain.AniListImportProgress
import org.koitharu.kotatsu.scrobbling.anilist.domain.AniListImportTarget
import org.koitharu.kotatsu.scrobbling.anilist.domain.AniListImportUseCase
import org.koitharu.kotatsu.scrobbling.anilist.domain.NoEnabledSourcesException
import org.koitharu.kotatsu.scrobbling.common.domain.model.ScrobblingStatus
import javax.inject.Inject

@HiltViewModel
class AniListImportViewModel @Inject constructor(
	private val importUseCase: AniListImportUseCase,
	favouritesRepository: FavouritesRepository,
) : BaseViewModel() {

	enum class ImportMode {
		BY_STATUS,
		SINGLE_CATEGORY,
	}

	data class UiState(
		val mode: ImportMode = ImportMode.BY_STATUS,
		val categories: List<FavouriteCategory> = emptyList(),
		val selectedCategoryId: Long? = null,
		val isNewCategorySelected: Boolean = false,
		val isRunning: Boolean = false,
		val progress: AniListImportProgress? = null,
		val inlineErrorRes: Int? = null,
	)

	private val _uiState = MutableStateFlow(UiState())
	val uiState: StateFlow<UiState> = _uiState.asStateFlow()

	val onImportError = MutableEventFlow<Throwable>()

	private var importJob: Job? = null

	init {
		favouritesRepository.observeCategories()
			.onEach { categories ->
				_uiState.update { state ->
					val currentValid = state.selectedCategoryId != null &&
						categories.any { it.id == state.selectedCategoryId }
					when {
						categories.isEmpty() -> state.copy(
							categories = categories,
							selectedCategoryId = null,
							isNewCategorySelected = true,
						)

						state.isNewCategorySelected -> state.copy(
							categories = categories,
							selectedCategoryId = null,
						)

						currentValid -> state.copy(categories = categories)

						else -> state.copy(
							categories = categories,
							selectedCategoryId = categories.first().id,
							isNewCategorySelected = false,
						)
					}
				}
			}
			.launchIn(viewModelScope)
	}

	fun setMode(mode: ImportMode) {
		if (_uiState.value.isRunning) return
		_uiState.update { it.copy(mode = mode) }
	}

	fun selectExistingCategory(categoryId: Long) {
		if (_uiState.value.isRunning) return
		_uiState.update {
			it.copy(selectedCategoryId = categoryId, isNewCategorySelected = false)
		}
	}

	fun selectNewCategory() {
		if (_uiState.value.isRunning) return
		_uiState.update {
			it.copy(selectedCategoryId = null, isNewCategorySelected = true)
		}
	}

	fun startImport(
		newCategoryName: String,
		defaultNewCategoryName: String,
		statusTitles: Map<ScrobblingStatus, String>,
	) {
		if (importJob?.isActive == true) return
		val snapshot = _uiState.value
		val target = when (snapshot.mode) {
			ImportMode.BY_STATUS -> AniListImportTarget.ByStatus(statusTitles)
			ImportMode.SINGLE_CATEGORY -> {
				val catId = snapshot.selectedCategoryId
				if (snapshot.isNewCategorySelected || catId == null) {
					AniListImportTarget.NewCategory(
						title = newCategoryName.trim().ifEmpty { defaultNewCategoryName },
					)
				} else {
					AniListImportTarget.SingleCategory(catId)
				}
			}
		}

		importJob = viewModelScope.launch(Dispatchers.Default) {
			_uiState.update { it.copy(isRunning = true, inlineErrorRes = null) }
			try {
				importUseCase(target).collect { progress ->
					_uiState.update { state ->
						state.copy(
							isRunning = !progress.isCompleted,
							progress = progress,
						)
					}
				}
			} catch (e: CancellationException) {
				throw e
			} catch (_: NoEnabledSourcesException) {
				_uiState.update {
					it.copy(
						isRunning = false,
						inlineErrorRes = R.string.anilist_import_error_no_sources,
					)
				}
			} catch (e: Throwable) {
				_uiState.update { it.copy(isRunning = false) }
				onImportError.call(e)
			} finally {
				_uiState.update { it.copy(isRunning = false) }
			}
		}
	}

	fun cancelImport() {
		importJob?.cancel()
		importJob = null
		_uiState.update { it.copy(isRunning = false) }
	}
}
