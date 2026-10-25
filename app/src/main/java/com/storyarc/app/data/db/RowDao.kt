package com.storyarc.app.data.db

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Upsert

/**
 * Row-level writes for every entity type, used by change sets. Upsert (never REPLACE) so
 * updating a row never triggers cascading deletes.
 */
@Dao
interface RowDao {
    @Upsert suspend fun upsert(row: ProjectEntity)
    @Delete suspend fun delete(row: ProjectEntity)
    @Upsert suspend fun upsert(row: WorldEntity)
    @Delete suspend fun delete(row: WorldEntity)
    @Upsert suspend fun upsert(row: ProjectWorldEntity)
    @Delete suspend fun delete(row: ProjectWorldEntity)
    @Upsert suspend fun upsert(row: ViewEntity)
    @Delete suspend fun delete(row: ViewEntity)
    @Upsert suspend fun upsert(row: TimelineEntity)
    @Delete suspend fun delete(row: TimelineEntity)
    @Upsert suspend fun upsert(row: ViewTimelineEntity)
    @Delete suspend fun delete(row: ViewTimelineEntity)
    @Upsert suspend fun upsert(row: NodeEntity)
    @Delete suspend fun delete(row: NodeEntity)
    @Upsert suspend fun upsert(row: NodeGroupEntity)
    @Delete suspend fun delete(row: NodeGroupEntity)
    @Upsert suspend fun upsert(row: PlacementEntity)
    @Delete suspend fun delete(row: PlacementEntity)
    @Upsert suspend fun upsert(row: MarkerEntity)
    @Delete suspend fun delete(row: MarkerEntity)
    @Upsert suspend fun upsert(row: CategoryEntity)
    @Delete suspend fun delete(row: CategoryEntity)
    @Upsert suspend fun upsert(row: NoteEntity)
    @Delete suspend fun delete(row: NoteEntity)
    @Upsert suspend fun upsert(row: NoteLinkEntity)
    @Delete suspend fun delete(row: NoteLinkEntity)
    @Upsert suspend fun upsert(row: TagEntity)
    @Delete suspend fun delete(row: TagEntity)
    @Upsert suspend fun upsert(row: NodeTagEntity)
    @Delete suspend fun delete(row: NodeTagEntity)
    @Upsert suspend fun upsert(row: MarkerTagEntity)
    @Delete suspend fun delete(row: MarkerTagEntity)
    @Upsert suspend fun upsert(row: GroupTagEntity)
    @Delete suspend fun delete(row: GroupTagEntity)
    @Upsert suspend fun upsert(row: NoteTagEntity)
    @Delete suspend fun delete(row: NoteTagEntity)
    @Upsert suspend fun upsert(row: ViewHiddenTagEntity)
    @Delete suspend fun delete(row: ViewHiddenTagEntity)
    @Upsert suspend fun upsert(row: TrayEntity)
    @Delete suspend fun delete(row: TrayEntity)
}
