package com.storyarc.app.data.db

import android.content.Context
import androidx.room.AutoMigration
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

@Database(
    version = StoryDatabase.SCHEMA_VERSION,
    exportSchema = true,
    // 2: tray_item table; node_group.timelineId nullable (tray groups).
    // 3: placement.span, tray_item.span, timeline.isScenes, scene_text table.
    autoMigrations = [AutoMigration(from = 1, to = 2), AutoMigration(from = 2, to = 3)],
    entities = [
        ProjectEntity::class, WorldEntity::class, ProjectWorldEntity::class, ViewEntity::class,
        TimelineEntity::class, ViewTimelineEntity::class, NodeEntity::class, NodeGroupEntity::class,
        PlacementEntity::class, MarkerEntity::class, CategoryEntity::class, NoteEntity::class,
        NoteLinkEntity::class, TagEntity::class, NodeTagEntity::class, MarkerTagEntity::class,
        GroupTagEntity::class, NoteTagEntity::class, ViewHiddenTagEntity::class, TrayEntity::class,
        SceneTextEntity::class,
    ],
)
abstract class StoryDatabase : RoomDatabase() {
    abstract fun rows(): RowDao
    abstract fun projects(): ProjectDao
    abstract fun board(): BoardDao
    abstract fun tags(): TagDao
    abstract fun worlds(): WorldDao
    abstract fun backup(): BackupDao

    companion object {
        const val SCHEMA_VERSION = 3

        fun create(context: Context): StoryDatabase =
            Room.databaseBuilder(context, StoryDatabase::class.java, "storyarc.db").build()
    }
}
