package com.storyarc.app.data.db

import android.database.sqlite.SQLiteDatabase
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.storyarc.app.data.repo.StoryRepository
import com.storyarc.app.data.repo.ViewRows
import com.storyarc.board.Item
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * Builds an old-version database from its exported schema (Robolectric can't read the schemas
 * as test assets, which MigrationTestHelper needs), then opens it with the current
 * [StoryDatabase]. Room runs the migration and fails the open if the result doesn't match the
 * current schema.
 */
@RunWith(AndroidJUnit4::class)
class MigrationTest {
    private fun createVersion(file: File, version: Int, rows: List<String>) {
        val schema = Json.parseToJsonElement(File("schemas/${StoryDatabase::class.qualifiedName}/$version.json").readText())
            .jsonObject.getValue("database").jsonObject
        SQLiteDatabase.openOrCreateDatabase(file, null).use { db ->
            for (entity in schema.getValue("entities").jsonArray) {
                val e = entity.jsonObject
                val table = e.getValue("tableName").jsonPrimitive.content
                db.execSQL(e.getValue("createSql").jsonPrimitive.content.replace("\${TABLE_NAME}", table))
                e["indices"]?.jsonArray?.forEach { i ->
                    db.execSQL(i.jsonObject.getValue("createSql").jsonPrimitive.content.replace("\${TABLE_NAME}", table))
                }
            }
            schema.getValue("setupQueries").jsonArray.forEach { db.execSQL(it.jsonPrimitive.content) }
            db.version = version
            rows.forEach(db::execSQL)
        }
    }

    private val version1Rows = listOf(
        "INSERT INTO project VALUES ('p', 'Saga', 1, 1)",
        "INSERT INTO board_view VALUES ('v', 'p', 'Main', 0)",
        "INSERT INTO timeline VALUES ('t', 'p', 'Mara', NULL)",
        "INSERT INTO view_timeline VALUES ('v', 't', 0)",
        "INSERT INTO node VALUES ('n', 'p', 'Leaves', NULL)",
        "INSERT INTO node_group VALUES ('g', 'v', 't', NULL, 'Voyage', NULL)",
        "INSERT INTO placement VALUES ('v', 'n', 't', 0, 'g')",
    )

    @Test
    fun version2GainsSpansScenesAndSceneText() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val file = context.getDatabasePath("migration-test-2.db").apply { parentFile?.mkdirs(); delete() }
        createVersion(file, 2, version1Rows + listOf(
            "INSERT INTO node VALUES ('n2', 'p', 'Loose', NULL)",
            "INSERT INTO tray_item VALUES ('v', 'n2', 0, 0, NULL)",
        ))
        val db = Room.databaseBuilder(context, StoryDatabase::class.java, file.absolutePath).allowMainThreadQueries().build()
        try {
            val repo = StoryRepository(db)
            assertEquals(1, repo.board.placements("v").single().span)
            assertEquals(1, repo.board.tray("v").single().span)
            assertEquals(false, repo.projects.timeline("t")!!.isScenes)
            // Spans work on migrated rows.
            repo.resize("v", "n", 3)
            assertEquals(3, repo.board.placements("v").single().span)
            db.rows().upsert(SceneTextEntity("n", "It was a dark night."))
            assertEquals("[]", db.backup().sceneTexts("p").single().styles)
            assertEquals(0, db.openHelper.readableDatabase.query("PRAGMA foreign_key_check").use { it.count })
        } finally {
            db.close()
        }
    }

    @Test
    fun version1DataSurvivesAndTrayWorks() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val file = context.getDatabasePath("migration-test.db").apply { parentFile?.mkdirs(); delete() }
        createVersion(file, 1, version1Rows)
        val db = Room.databaseBuilder(context, StoryDatabase::class.java, file.absolutePath).allowMainThreadQueries().build()
        try {
            val repo = StoryRepository(db)
            val rows = ViewRows.load(repo.board, "v")
            assertEquals(listOf("n"), rows.toBoard().nodesOf("g"))
            assertEquals("Voyage", rows.groups.single().label)
            // A group can now leave the board for the tray.
            repo.moveToTray("v", Item.GroupItem("g"))
            assertNull(repo.board.groups("v").single().timelineId)
            assertEquals(listOf("n"), repo.board.tray("v").map { it.nodeId })
            assertEquals(0, db.openHelper.readableDatabase.query("PRAGMA foreign_key_check").use { it.count })
        } finally {
            db.close()
        }
    }
}
