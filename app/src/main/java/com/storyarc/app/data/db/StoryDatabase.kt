package com.storyarc.app.data.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

@Database(
    version = 1,
    exportSchema = true,
    entities = [
        ProjectEntity::class, WorldEntity::class, ProjectWorldEntity::class, ViewEntity::class,
        TimelineEntity::class, ViewTimelineEntity::class, NodeEntity::class, NodeGroupEntity::class,
        PlacementEntity::class, MarkerEntity::class, CategoryEntity::class, NoteEntity::class,
        NoteLinkEntity::class, TagEntity::class, NodeTagEntity::class, MarkerTagEntity::class,
        GroupTagEntity::class, NoteTagEntity::class, ViewHiddenTagEntity::class,
    ],
)
abstract class StoryDatabase : RoomDatabase() {
    abstract fun rows(): RowDao
    abstract fun projects(): ProjectDao
    abstract fun board(): BoardDao
    abstract fun tags(): TagDao
    abstract fun worlds(): WorldDao

    companion object {
        fun create(context: Context): StoryDatabase =
            Room.databaseBuilder(context, StoryDatabase::class.java, "storyarc.db").build()
    }
}
