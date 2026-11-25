package org.koitharu.kotatsu.core.db.migrations

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

class Migration29To30 : Migration(29, 30) {

	override fun migrate(db: SupportSQLiteDatabase) {
		db.execSQL(
			"""
			CREATE TABLE IF NOT EXISTS `bookmarks_new` (
				`manga_id` INTEGER NOT NULL,
				`page_id` INTEGER NOT NULL,
				`chapter_id` INTEGER NOT NULL,
				`page` INTEGER NOT NULL,
				`scroll` INTEGER NOT NULL,
				`image` TEXT NOT NULL,
				`created_at` INTEGER NOT NULL,
				`percent` REAL NOT NULL,
				`chapter_number` REAL NOT NULL DEFAULT -1,
				`chapter_volume` INTEGER NOT NULL DEFAULT 0,
				`chapter_title` TEXT,
				`chapter_url` TEXT,
				`chapter_branch` TEXT,
				`chapter_index` INTEGER NOT NULL DEFAULT -1,
				`chapters_count` INTEGER NOT NULL DEFAULT 0,
				PRIMARY KEY(`manga_id`, `page_id`),
				FOREIGN KEY(`manga_id`) REFERENCES `manga`(`manga_id`) ON UPDATE NO ACTION ON DELETE RESTRICT
			)
			""".trimIndent(),
		)
		db.execSQL(
			"""
			INSERT INTO `bookmarks_new` (
				`manga_id`,
				`page_id`,
				`chapter_id`,
				`page`,
				`scroll`,
				`image`,
				`created_at`,
				`percent`,
				`chapter_number`,
				`chapter_volume`,
				`chapter_title`,
				`chapter_url`,
				`chapter_branch`,
				`chapter_index`,
				`chapters_count`
			)
			SELECT
				b.`manga_id`,
				b.`page_id`,
				b.`chapter_id`,
				b.`page`,
				b.`scroll`,
				b.`image`,
				b.`created_at`,
				b.`percent`,
				COALESCE(c.`number`, -1),
				COALESCE(c.`volume`, 0),
				NULLIF(c.`name`, ''),
				NULLIF(c.`url`, ''),
				c.`branch`,
				CASE
					WHEN c.`chapter_id` IS NOT NULL THEN (
						SELECT COUNT(*)
						FROM `chapters` c2
						WHERE c2.`manga_id` = b.`manga_id`
							AND c2.`branch` IS c.`branch`
							AND c2.`index` < c.`index`
					)
					ELSE -1
				END,
				CASE
					WHEN c.`chapter_id` IS NOT NULL THEN (
						SELECT COUNT(*)
						FROM `chapters` c2
						WHERE c2.`manga_id` = b.`manga_id`
							AND c2.`branch` IS c.`branch`
					)
					ELSE 0
				END
			FROM `bookmarks` b
			LEFT JOIN `chapters` c
				ON c.`manga_id` = b.`manga_id` AND c.`chapter_id` = b.`chapter_id`
			""".trimIndent(),
		)
		db.execSQL("DROP TABLE `bookmarks`")
		db.execSQL("ALTER TABLE `bookmarks_new` RENAME TO `bookmarks`")
		db.execSQL("CREATE INDEX IF NOT EXISTS `index_bookmarks_manga_id` ON `bookmarks` (`manga_id`)")
		db.execSQL("CREATE INDEX IF NOT EXISTS `index_bookmarks_page_id` ON `bookmarks` (`page_id`)")
	}
}
