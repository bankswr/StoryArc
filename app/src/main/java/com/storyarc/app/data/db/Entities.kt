package com.storyarc.app.data.db

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.ForeignKey.Companion.CASCADE
import androidx.room.Index
import androidx.room.PrimaryKey
import kotlinx.serialization.Serializable

// Foreign keys are deferred so a change set can write rows in any order inside one transaction.
// CASCADE is used only for ownership (project, world, view, and rows owned by a node, timeline,
// marker, group or tag); commands still delete those dependents explicitly so undo can restore
// them, and cascades only do real work in non-undoable bulk deletes such as removing a project.
// Entities are also @Serializable: backups store these rows directly, versioned by the backup
// manifest's format version.
// References between peers (group parent, a placement's group, note category, tag link) have no
// action, so an incomplete change set fails at commit instead of silently rewriting rows.

@Serializable
@Entity(tableName = "project")
data class ProjectEntity(
    @PrimaryKey val id: String,
    val name: String,
    val createdAt: Long,
    val updatedAt: Long,
)

@Serializable
@Entity(tableName = "world")
data class WorldEntity(
    @PrimaryKey val id: String,
    val name: String,
)

@Serializable
@Entity(
    tableName = "project_world",
    primaryKeys = ["projectId", "worldId"],
    indices = [Index("worldId")],
    foreignKeys = [
        ForeignKey(ProjectEntity::class, ["id"], ["projectId"], onDelete = CASCADE, deferred = true),
        ForeignKey(WorldEntity::class, ["id"], ["worldId"], onDelete = CASCADE, deferred = true),
    ],
)
data class ProjectWorldEntity(val projectId: String, val worldId: String)

@Serializable
@Entity(
    tableName = "board_view",
    indices = [Index("projectId")],
    foreignKeys = [ForeignKey(ProjectEntity::class, ["id"], ["projectId"], onDelete = CASCADE, deferred = true)],
)
data class ViewEntity(
    @PrimaryKey val id: String,
    val projectId: String,
    val name: String,
    val ordinal: Int,
)

@Serializable
@Entity(
    tableName = "timeline",
    indices = [Index("projectId")],
    foreignKeys = [ForeignKey(ProjectEntity::class, ["id"], ["projectId"], onDelete = CASCADE, deferred = true)],
)
data class TimelineEntity(
    @PrimaryKey val id: String,
    val projectId: String,
    val name: String,
    val color: Int? = null,
)

@Serializable
@Entity(
    tableName = "view_timeline",
    primaryKeys = ["viewId", "timelineId"],
    indices = [Index("timelineId")],
    foreignKeys = [
        ForeignKey(ViewEntity::class, ["id"], ["viewId"], onDelete = CASCADE, deferred = true),
        ForeignKey(TimelineEntity::class, ["id"], ["timelineId"], onDelete = CASCADE, deferred = true),
    ],
)
data class ViewTimelineEntity(val viewId: String, val timelineId: String, val ordinal: Int)

@Serializable
@Entity(
    tableName = "node",
    indices = [Index("projectId")],
    foreignKeys = [ForeignKey(ProjectEntity::class, ["id"], ["projectId"], onDelete = CASCADE, deferred = true)],
)
data class NodeEntity(
    @PrimaryKey val id: String,
    val projectId: String,
    val text: String,
    val color: Int? = null,
)

@Serializable
@Entity(
    tableName = "node_group",
    indices = [Index("viewId"), Index("timelineId"), Index("parentId")],
    foreignKeys = [
        ForeignKey(ViewEntity::class, ["id"], ["viewId"], onDelete = CASCADE, deferred = true),
        ForeignKey(TimelineEntity::class, ["id"], ["timelineId"], onDelete = CASCADE, deferred = true),
        ForeignKey(NodeGroupEntity::class, ["id"], ["parentId"], deferred = true),
    ],
)
data class NodeGroupEntity(
    @PrimaryKey val id: String,
    val viewId: String,
    val timelineId: String,
    val parentId: String?,
    val label: String = "",
    val color: Int? = null,
)

/** A node's position in one view. [groupId] is the node's innermost group, if any. */
@Serializable
@Entity(
    tableName = "placement",
    primaryKeys = ["viewId", "nodeId"],
    indices = [Index("nodeId"), Index("timelineId"), Index("groupId")],
    foreignKeys = [
        ForeignKey(ViewEntity::class, ["id"], ["viewId"], onDelete = CASCADE, deferred = true),
        ForeignKey(NodeEntity::class, ["id"], ["nodeId"], onDelete = CASCADE, deferred = true),
        ForeignKey(TimelineEntity::class, ["id"], ["timelineId"], onDelete = CASCADE, deferred = true),
        ForeignKey(NodeGroupEntity::class, ["id"], ["groupId"], deferred = true),
    ],
)
data class PlacementEntity(
    val viewId: String,
    val nodeId: String,
    val timelineId: String,
    val slot: Int,
    val groupId: String?,
)

@Serializable
@Entity(
    tableName = "marker",
    indices = [Index("viewId")],
    foreignKeys = [ForeignKey(ViewEntity::class, ["id"], ["viewId"], onDelete = CASCADE, deferred = true)],
)
data class MarkerEntity(
    @PrimaryKey val id: String,
    val viewId: String,
    val slot: Int,
    val label: String,
    val color: Int? = null,
)

@Serializable
@Entity(
    tableName = "category",
    indices = [Index("worldId"), Index("parentId")],
    foreignKeys = [
        ForeignKey(WorldEntity::class, ["id"], ["worldId"], onDelete = CASCADE, deferred = true),
        ForeignKey(CategoryEntity::class, ["id"], ["parentId"], deferred = true),
    ],
)
data class CategoryEntity(
    @PrimaryKey val id: String,
    val worldId: String,
    val parentId: String?,
    val name: String,
    val ordinal: Int,
)

@Serializable
@Entity(
    tableName = "note",
    indices = [Index("worldId"), Index("categoryId")],
    foreignKeys = [
        ForeignKey(WorldEntity::class, ["id"], ["worldId"], onDelete = CASCADE, deferred = true),
        ForeignKey(CategoryEntity::class, ["id"], ["categoryId"], deferred = true),
    ],
)
data class NoteEntity(
    @PrimaryKey val id: String,
    val worldId: String,
    val categoryId: String?,
    val title: String,
    val body: String,
)

@Serializable
@Entity(
    tableName = "note_link",
    primaryKeys = ["fromNoteId", "toNoteId"],
    indices = [Index("toNoteId")],
    foreignKeys = [
        ForeignKey(NoteEntity::class, ["id"], ["fromNoteId"], onDelete = CASCADE, deferred = true),
        ForeignKey(NoteEntity::class, ["id"], ["toNoteId"], onDelete = CASCADE, deferred = true),
    ],
)
data class NoteLinkEntity(val fromNoteId: String, val toNoteId: String)

/** Owned by exactly one of [projectId] or [worldId]. */
@Serializable
@Entity(
    tableName = "tag",
    indices = [Index("projectId"), Index("worldId"), Index("linkedNoteId")],
    foreignKeys = [
        ForeignKey(ProjectEntity::class, ["id"], ["projectId"], onDelete = CASCADE, deferred = true),
        ForeignKey(WorldEntity::class, ["id"], ["worldId"], onDelete = CASCADE, deferred = true),
        ForeignKey(NoteEntity::class, ["id"], ["linkedNoteId"], deferred = true),
    ],
)
data class TagEntity(
    @PrimaryKey val id: String,
    val projectId: String?,
    val worldId: String?,
    val name: String,
    val color: Int? = null,
    val linkedNoteId: String? = null,
)

/** Tag assignments; ordinal 0 is the item's primary tag. */
@Serializable
@Entity(
    tableName = "node_tag",
    primaryKeys = ["targetId", "tagId"],
    indices = [Index("tagId")],
    foreignKeys = [
        ForeignKey(NodeEntity::class, ["id"], ["targetId"], onDelete = CASCADE, deferred = true),
        ForeignKey(TagEntity::class, ["id"], ["tagId"], onDelete = CASCADE, deferred = true),
    ],
)
data class NodeTagEntity(val targetId: String, val tagId: String, val ordinal: Int)

@Serializable
@Entity(
    tableName = "marker_tag",
    primaryKeys = ["targetId", "tagId"],
    indices = [Index("tagId")],
    foreignKeys = [
        ForeignKey(MarkerEntity::class, ["id"], ["targetId"], onDelete = CASCADE, deferred = true),
        ForeignKey(TagEntity::class, ["id"], ["tagId"], onDelete = CASCADE, deferred = true),
    ],
)
data class MarkerTagEntity(val targetId: String, val tagId: String, val ordinal: Int)

@Serializable
@Entity(
    tableName = "group_tag",
    primaryKeys = ["targetId", "tagId"],
    indices = [Index("tagId")],
    foreignKeys = [
        ForeignKey(NodeGroupEntity::class, ["id"], ["targetId"], onDelete = CASCADE, deferred = true),
        ForeignKey(TagEntity::class, ["id"], ["tagId"], onDelete = CASCADE, deferred = true),
    ],
)
data class GroupTagEntity(val targetId: String, val tagId: String, val ordinal: Int)

@Serializable
@Entity(
    tableName = "note_tag",
    primaryKeys = ["targetId", "tagId"],
    indices = [Index("tagId")],
    foreignKeys = [
        ForeignKey(NoteEntity::class, ["id"], ["targetId"], onDelete = CASCADE, deferred = true),
        ForeignKey(TagEntity::class, ["id"], ["tagId"], onDelete = CASCADE, deferred = true),
    ],
)
data class NoteTagEntity(val targetId: String, val tagId: String, val ordinal: Int)

@Serializable
@Entity(
    tableName = "view_hidden_tag",
    primaryKeys = ["viewId", "tagId"],
    indices = [Index("tagId")],
    foreignKeys = [
        ForeignKey(ViewEntity::class, ["id"], ["viewId"], onDelete = CASCADE, deferred = true),
        ForeignKey(TagEntity::class, ["id"], ["tagId"], onDelete = CASCADE, deferred = true),
    ],
)
data class ViewHiddenTagEntity(val viewId: String, val tagId: String)
