package org.koitharu.kotatsu.reader.domain.upscale.model

enum class UpscaleMode(val id: String) {
	OFF("off"),
	FAST("fast"),
	AI("ai"),
	;

	val isEnabled: Boolean
		get() = this != OFF

	companion object {
		fun fromId(id: String?): UpscaleMode = when (id?.trim()?.lowercase()) {
			FAST.id -> FAST
			AI.id -> AI
			else -> OFF
		}
	}
}

data class UpscaleSettingsSnapshot(
	val mode: UpscaleMode = UpscaleMode.OFF,
	val upscaleHighRes: Boolean = false,
) {
	fun effectiveCacheSignature(): String = "${mode.id}|hr=$upscaleHighRes"
}

data class StripPlan(
	val index: Int,
	val y0: Int,
	val y1: Int,
	val validY0: Int,
	val validY1: Int,
	val cropTop: Int,
	val cropBottom: Int,
) {
	val decodeHeight: Int
		get() = (y1 - y0).coerceAtLeast(1)

	val validHeight: Int
		get() = (validY1 - validY0).coerceAtLeast(1)
}

data class TilePlan(
	val tx0: Int,
	val ty0: Int,
	val tx1: Int,
	val ty1: Int,
	val pad: Int,
) {
	val coreWidth: Int
		get() = (tx1 - tx0).coerceAtLeast(1)

	val coreHeight: Int
		get() = (ty1 - ty0).coerceAtLeast(1)
}

data class StripSizeResult(
	val inputWidth: Int,
	val inputScale: Float,
	val outWidth: Int,
	val outHeight: Int,
)

enum class UpscaleStage {
	IDLE,
	DOWNLOADING_MODEL,
	INITIALIZING_MODEL,
	PROCESSING,
	ERROR,
	;

	fun isModelBusy(): Boolean = this == DOWNLOADING_MODEL || this == INITIALIZING_MODEL
}

data class UpscaleRuntimeStatus(
	val stage: UpscaleStage = UpscaleStage.IDLE,
	val activeJobs: Int = 0,
	val downloadPercent: Int = 0,
	val errorMessage: String? = null,
)
