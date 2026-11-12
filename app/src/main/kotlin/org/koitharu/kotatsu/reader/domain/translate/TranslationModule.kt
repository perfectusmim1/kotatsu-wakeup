package org.koitharu.kotatsu.reader.domain.translate

import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import org.koitharu.kotatsu.reader.domain.translate.api.MangaTranslationService
import org.koitharu.kotatsu.reader.domain.translate.cache.TranslatedPageCache
import org.koitharu.kotatsu.reader.domain.translate.model.ComicTextTranslator
import org.koitharu.kotatsu.reader.domain.translate.model.LocalOcrEngine
import org.koitharu.kotatsu.reader.domain.translate.model.TranslatedPageRenderer
import org.koitharu.kotatsu.reader.domain.translate.model.TranslationCacheStore
import org.koitharu.kotatsu.reader.domain.translate.ocr.PaddleOcrEngine
import org.koitharu.kotatsu.reader.domain.translate.render.BitmapTranslationBaker
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
interface TranslationModule {

	@Binds
	@Singleton
	fun bindLocalOcrEngine(impl: PaddleOcrEngine): LocalOcrEngine

	@Binds
	@Singleton
	fun bindComicTextTranslator(impl: MangaTranslationService): ComicTextTranslator

	@Binds
	@Singleton
	fun bindTranslatedPageRenderer(impl: BitmapTranslationBaker): TranslatedPageRenderer

	@Binds
	@Singleton
	fun bindTranslationCacheStore(impl: TranslatedPageCache): TranslationCacheStore
}
