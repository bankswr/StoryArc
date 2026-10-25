package com.storyarc.app.data.db

/** Dispatches a row to the matching [RowDao] method by its entity type. */
suspend fun RowDao.upsertAny(row: Any) = when (row) {
    is ProjectEntity -> upsert(row)
    is WorldEntity -> upsert(row)
    is ProjectWorldEntity -> upsert(row)
    is ViewEntity -> upsert(row)
    is TimelineEntity -> upsert(row)
    is ViewTimelineEntity -> upsert(row)
    is NodeEntity -> upsert(row)
    is NodeGroupEntity -> upsert(row)
    is PlacementEntity -> upsert(row)
    is MarkerEntity -> upsert(row)
    is CategoryEntity -> upsert(row)
    is NoteEntity -> upsert(row)
    is NoteLinkEntity -> upsert(row)
    is TagEntity -> upsert(row)
    is NodeTagEntity -> upsert(row)
    is MarkerTagEntity -> upsert(row)
    is GroupTagEntity -> upsert(row)
    is NoteTagEntity -> upsert(row)
    is ViewHiddenTagEntity -> upsert(row)
    is TrayEntity -> upsert(row)
    else -> error("not an entity: ${row::class}")
}

suspend fun RowDao.deleteAny(row: Any) = when (row) {
    is ProjectEntity -> delete(row)
    is WorldEntity -> delete(row)
    is ProjectWorldEntity -> delete(row)
    is ViewEntity -> delete(row)
    is TimelineEntity -> delete(row)
    is ViewTimelineEntity -> delete(row)
    is NodeEntity -> delete(row)
    is NodeGroupEntity -> delete(row)
    is PlacementEntity -> delete(row)
    is MarkerEntity -> delete(row)
    is CategoryEntity -> delete(row)
    is NoteEntity -> delete(row)
    is NoteLinkEntity -> delete(row)
    is TagEntity -> delete(row)
    is NodeTagEntity -> delete(row)
    is MarkerTagEntity -> delete(row)
    is GroupTagEntity -> delete(row)
    is NoteTagEntity -> delete(row)
    is ViewHiddenTagEntity -> delete(row)
    is TrayEntity -> delete(row)
    else -> error("not an entity: ${row::class}")
}

/** Primary-key identity of a row, unique across tables. */
fun rowKey(row: Any): Any = when (row) {
    is ProjectEntity -> "project" to row.id
    is WorldEntity -> "world" to row.id
    is ProjectWorldEntity -> Triple("project_world", row.projectId, row.worldId)
    is ViewEntity -> "view" to row.id
    is TimelineEntity -> "timeline" to row.id
    is ViewTimelineEntity -> Triple("view_timeline", row.viewId, row.timelineId)
    is NodeEntity -> "node" to row.id
    is NodeGroupEntity -> "node_group" to row.id
    is PlacementEntity -> Triple("placement", row.viewId, row.nodeId)
    is MarkerEntity -> "marker" to row.id
    is CategoryEntity -> "category" to row.id
    is NoteEntity -> "note" to row.id
    is NoteLinkEntity -> Triple("note_link", row.fromNoteId, row.toNoteId)
    is TagEntity -> "tag" to row.id
    is NodeTagEntity -> Triple("node_tag", row.targetId, row.tagId)
    is MarkerTagEntity -> Triple("marker_tag", row.targetId, row.tagId)
    is GroupTagEntity -> Triple("group_tag", row.targetId, row.tagId)
    is NoteTagEntity -> Triple("note_tag", row.targetId, row.tagId)
    is ViewHiddenTagEntity -> Triple("view_hidden_tag", row.viewId, row.tagId)
    is TrayEntity -> Triple("tray_item", row.viewId, row.nodeId)
    else -> error("not an entity: ${row::class}")
}
