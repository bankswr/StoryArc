package com.storyarc.app.data.db

import androidx.room.Dao
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface ProjectDao {
    @Query("SELECT * FROM project ORDER BY updatedAt DESC")
    fun observeProjects(): Flow<List<ProjectEntity>>

    @Query("SELECT * FROM project WHERE id = :id")
    fun observeProject(id: String): Flow<ProjectEntity?>

    @Query("SELECT * FROM project WHERE id = :id")
    suspend fun project(id: String): ProjectEntity?

    @Query("DELETE FROM project WHERE id = :id")
    suspend fun deleteProject(id: String)

    /** Worlds linked to [projectId] and no other project. */
    @Query("SELECT worldId FROM project_world WHERE projectId = :projectId AND worldId NOT IN (SELECT worldId FROM project_world WHERE projectId != :projectId)")
    suspend fun exclusiveWorlds(projectId: String): List<String>

    @Query("DELETE FROM world WHERE id IN (:ids)")
    suspend fun deleteWorlds(ids: List<String>)

    @Query("SELECT * FROM board_view WHERE projectId = :projectId ORDER BY ordinal")
    fun observeViews(projectId: String): Flow<List<ViewEntity>>

    @Query("SELECT * FROM board_view WHERE projectId = :projectId ORDER BY ordinal")
    suspend fun views(projectId: String): List<ViewEntity>

    @Query("SELECT * FROM board_view WHERE id = :id")
    suspend fun view(id: String): ViewEntity?

    @Query("SELECT * FROM timeline WHERE projectId = :projectId ORDER BY name")
    fun observeTimelines(projectId: String): Flow<List<TimelineEntity>>

    @Query("SELECT * FROM timeline WHERE id = :id")
    suspend fun timeline(id: String): TimelineEntity?

    @Query("SELECT * FROM node WHERE id = :id")
    suspend fun node(id: String): NodeEntity?

    @Query("SELECT w.* FROM world w JOIN project_world pw ON pw.worldId = w.id WHERE pw.projectId = :projectId ORDER BY w.name")
    fun observeWorlds(projectId: String): Flow<List<WorldEntity>>
}

/** Rows that make up one view's layout. */
@Dao
interface BoardDao {
    @Query("SELECT * FROM view_timeline WHERE viewId = :viewId ORDER BY ordinal")
    suspend fun viewTimelines(viewId: String): List<ViewTimelineEntity>

    @Query("SELECT * FROM placement WHERE viewId = :viewId")
    suspend fun placements(viewId: String): List<PlacementEntity>

    @Query("SELECT * FROM marker WHERE viewId = :viewId")
    suspend fun markers(viewId: String): List<MarkerEntity>

    @Query("SELECT * FROM node_group WHERE viewId = :viewId")
    suspend fun groups(viewId: String): List<NodeGroupEntity>

    @Query("SELECT * FROM placement WHERE nodeId = :nodeId")
    suspend fun placementsOfNode(nodeId: String): List<PlacementEntity>

    @Query("SELECT * FROM view_timeline WHERE timelineId = :timelineId")
    suspend fun viewTimelinesOf(timelineId: String): List<ViewTimelineEntity>

    @Query("SELECT * FROM node_tag WHERE targetId = :nodeId")
    suspend fun nodeTags(nodeId: String): List<NodeTagEntity>

    @Query("SELECT * FROM marker_tag WHERE targetId IN (:markerIds)")
    suspend fun markerTags(markerIds: List<String>): List<MarkerTagEntity>

    @Query("SELECT * FROM group_tag WHERE targetId IN (:groupIds)")
    suspend fun groupTags(groupIds: List<String>): List<GroupTagEntity>

    @Query("SELECT * FROM view_hidden_tag WHERE viewId = :viewId")
    suspend fun hiddenTags(viewId: String): List<ViewHiddenTagEntity>

    /** Emits whenever any layout or content row of the view changes; the payload is unused. */
    @Query(
        """SELECT (SELECT COUNT(*) FROM placement WHERE viewId = :viewId) +
                  (SELECT COUNT(*) FROM marker WHERE viewId = :viewId) +
                  (SELECT COUNT(*) FROM node_group WHERE viewId = :viewId) +
                  (SELECT COUNT(*) FROM view_timeline WHERE viewId = :viewId) +
                  (SELECT COUNT(*) FROM timeline) + (SELECT COUNT(*) FROM node) +
                  (SELECT COUNT(*) FROM node_tag) + (SELECT COUNT(*) FROM tag) +
                  (SELECT COUNT(*) FROM marker_tag) + (SELECT COUNT(*) FROM group_tag) +
                  (SELECT COUNT(*) FROM view_hidden_tag) + (SELECT COUNT(*) FROM board_view)"""
    )
    fun observeViewChanges(viewId: String): Flow<Int>

    @Query("SELECT n.* FROM node n JOIN placement p ON p.nodeId = n.id WHERE p.viewId = :viewId")
    suspend fun nodesInView(viewId: String): List<NodeEntity>

    @Query("SELECT t.* FROM timeline t JOIN view_timeline vt ON vt.timelineId = t.id WHERE vt.viewId = :viewId")
    suspend fun timelinesInView(viewId: String): List<TimelineEntity>

    @Query("SELECT t.* FROM timeline t JOIN view_timeline vt ON vt.timelineId = t.id WHERE vt.viewId = :viewId ORDER BY vt.ordinal")
    fun observeTimelinesInView(viewId: String): Flow<List<TimelineEntity>>

    @Query("SELECT timelineId, COUNT(*) AS count FROM placement WHERE viewId = :viewId GROUP BY timelineId")
    fun observeNodeCounts(viewId: String): Flow<List<TimelineCount>>
}

data class TimelineCount(val timelineId: String, val count: Int)

@Dao
interface TagDao {
    /** Tags a project can use: its own plus those of every world library it links. */
    @Query(
        """SELECT * FROM tag WHERE projectId = :projectId
           OR worldId IN (SELECT worldId FROM project_world WHERE projectId = :projectId) ORDER BY name COLLATE NOCASE"""
    )
    fun observeProjectTags(projectId: String): Flow<List<TagEntity>>

    @Query(
        """SELECT * FROM tag WHERE projectId = :projectId
           OR worldId IN (SELECT worldId FROM project_world WHERE projectId = :projectId)"""
    )
    suspend fun projectTags(projectId: String): List<TagEntity>

    @Query("SELECT * FROM tag WHERE id = :id")
    suspend fun tag(id: String): TagEntity?

    @Query("SELECT nt.* FROM node_tag nt JOIN placement p ON p.nodeId = nt.targetId WHERE p.viewId = :viewId")
    suspend fun nodeTagsInView(viewId: String): List<NodeTagEntity>

    @Query("SELECT mt.* FROM marker_tag mt JOIN marker m ON m.id = mt.targetId WHERE m.viewId = :viewId")
    suspend fun markerTagsInView(viewId: String): List<MarkerTagEntity>

    @Query("SELECT gt.* FROM group_tag gt JOIN node_group g ON g.id = gt.targetId WHERE g.viewId = :viewId")
    suspend fun groupTagsInView(viewId: String): List<GroupTagEntity>

    @Query("SELECT * FROM node_tag WHERE targetId = :id ORDER BY ordinal") suspend fun nodeTags(id: String): List<NodeTagEntity>
    @Query("SELECT * FROM marker_tag WHERE targetId = :id ORDER BY ordinal") suspend fun markerTags(id: String): List<MarkerTagEntity>
    @Query("SELECT * FROM group_tag WHERE targetId = :id ORDER BY ordinal") suspend fun groupTags(id: String): List<GroupTagEntity>
    @Query("SELECT * FROM note_tag WHERE targetId = :id ORDER BY ordinal") suspend fun noteTags(id: String): List<NoteTagEntity>

    @Query("SELECT * FROM node_tag WHERE tagId = :tagId") suspend fun nodeTagsOf(tagId: String): List<NodeTagEntity>
    @Query("SELECT * FROM marker_tag WHERE tagId = :tagId") suspend fun markerTagsOf(tagId: String): List<MarkerTagEntity>
    @Query("SELECT * FROM group_tag WHERE tagId = :tagId") suspend fun groupTagsOf(tagId: String): List<GroupTagEntity>
    @Query("SELECT * FROM note_tag WHERE tagId = :tagId") suspend fun noteTagsOf(tagId: String): List<NoteTagEntity>
    @Query("SELECT * FROM view_hidden_tag WHERE tagId = :tagId") suspend fun hiddenOf(tagId: String): List<ViewHiddenTagEntity>

    @Query("SELECT * FROM view_hidden_tag WHERE viewId = :viewId")
    fun observeHidden(viewId: String): Flow<List<ViewHiddenTagEntity>>
}

@Dao
interface WorldDao {
    @Query("SELECT * FROM world ORDER BY name COLLATE NOCASE")
    fun observeAllWorlds(): Flow<List<WorldEntity>>

    @Query("SELECT * FROM world WHERE id = :id")
    fun observeWorld(id: String): Flow<WorldEntity?>

    @Query("SELECT * FROM world WHERE id = :id")
    suspend fun world(id: String): WorldEntity?

    @Query("SELECT * FROM project_world WHERE worldId = :worldId")
    suspend fun links(worldId: String): List<ProjectWorldEntity>

    @Query("SELECT * FROM category WHERE worldId = :worldId ORDER BY ordinal")
    fun observeCategories(worldId: String): Flow<List<CategoryEntity>>

    @Query("SELECT * FROM category WHERE worldId = :worldId ORDER BY ordinal")
    suspend fun categories(worldId: String): List<CategoryEntity>

    @Query("SELECT * FROM note WHERE worldId = :worldId ORDER BY title COLLATE NOCASE")
    fun observeNotes(worldId: String): Flow<List<NoteEntity>>

    @Query("SELECT * FROM note WHERE worldId = :worldId")
    suspend fun notes(worldId: String): List<NoteEntity>

    @Query("SELECT * FROM note WHERE id = :id")
    fun observeNote(id: String): Flow<NoteEntity?>

    @Query("SELECT * FROM note WHERE id = :id")
    suspend fun note(id: String): NoteEntity?

    @Query("SELECT n.* FROM note n JOIN note_link l ON l.toNoteId = n.id WHERE l.fromNoteId = :noteId ORDER BY n.title COLLATE NOCASE")
    fun observeLinksFrom(noteId: String): Flow<List<NoteEntity>>

    @Query("SELECT n.* FROM note n JOIN note_link l ON l.fromNoteId = n.id WHERE l.toNoteId = :noteId ORDER BY n.title COLLATE NOCASE")
    fun observeLinksTo(noteId: String): Flow<List<NoteEntity>>

    @Query("SELECT * FROM note_link WHERE fromNoteId = :noteId OR toNoteId = :noteId")
    suspend fun linksOf(noteId: String): List<NoteLinkEntity>

    @Query("SELECT * FROM tag WHERE linkedNoteId = :noteId")
    fun observeTagsLinking(noteId: String): Flow<List<TagEntity>>

    @Query("SELECT * FROM tag WHERE linkedNoteId IN (:noteIds)")
    suspend fun tagsLinking(noteIds: List<String>): List<TagEntity>

    @Query("SELECT * FROM note_tag WHERE targetId = :noteId ORDER BY ordinal")
    fun observeNoteTags(noteId: String): Flow<List<NoteTagEntity>>

    @Query("SELECT * FROM note_tag WHERE targetId IN (:noteIds)")
    suspend fun noteTagsOf(noteIds: List<String>): List<NoteTagEntity>

    /** Notes of every world a project links, for choosing a tag's reference. */
    @Query("SELECT n.* FROM note n JOIN project_world pw ON pw.worldId = n.worldId WHERE pw.projectId = :projectId ORDER BY n.title COLLATE NOCASE")
    fun observeProjectNotes(projectId: String): Flow<List<NoteEntity>>

    @Query("UPDATE tag SET linkedNoteId = NULL WHERE linkedNoteId IN (SELECT id FROM note WHERE worldId = :worldId)")
    suspend fun unlinkTagsFromWorldNotes(worldId: String)

    @Query("DELETE FROM world WHERE id = :worldId")
    suspend fun deleteWorld(worldId: String)
}

/** Whole-project and whole-world reads for backups. */
@Dao
interface BackupDao {
    @Query("SELECT * FROM project") suspend fun projects(): List<ProjectEntity>
    @Query("SELECT * FROM project WHERE id = :id") suspend fun project(id: String): ProjectEntity?
    @Query("SELECT * FROM project_world WHERE projectId = :p") suspend fun projectWorlds(p: String): List<ProjectWorldEntity>
    @Query("SELECT * FROM board_view WHERE projectId = :p") suspend fun views(p: String): List<ViewEntity>
    @Query("SELECT * FROM timeline WHERE projectId = :p") suspend fun timelines(p: String): List<TimelineEntity>
    @Query("SELECT * FROM node WHERE projectId = :p") suspend fun nodes(p: String): List<NodeEntity>

    @Query("SELECT vt.* FROM view_timeline vt JOIN board_view v ON v.id = vt.viewId WHERE v.projectId = :p")
    suspend fun viewTimelines(p: String): List<ViewTimelineEntity>

    @Query("SELECT g.* FROM node_group g JOIN board_view v ON v.id = g.viewId WHERE v.projectId = :p")
    suspend fun groups(p: String): List<NodeGroupEntity>

    @Query("SELECT pl.* FROM placement pl JOIN board_view v ON v.id = pl.viewId WHERE v.projectId = :p")
    suspend fun placements(p: String): List<PlacementEntity>

    @Query("SELECT m.* FROM marker m JOIN board_view v ON v.id = m.viewId WHERE v.projectId = :p")
    suspend fun markers(p: String): List<MarkerEntity>

    @Query("SELECT * FROM tag WHERE projectId = :p") suspend fun projectTags(p: String): List<TagEntity>

    @Query("SELECT nt.* FROM node_tag nt JOIN node n ON n.id = nt.targetId WHERE n.projectId = :p")
    suspend fun nodeTags(p: String): List<NodeTagEntity>

    @Query("SELECT mt.* FROM marker_tag mt JOIN marker m ON m.id = mt.targetId JOIN board_view v ON v.id = m.viewId WHERE v.projectId = :p")
    suspend fun markerTags(p: String): List<MarkerTagEntity>

    @Query("SELECT gt.* FROM group_tag gt JOIN node_group g ON g.id = gt.targetId JOIN board_view v ON v.id = g.viewId WHERE v.projectId = :p")
    suspend fun groupTags(p: String): List<GroupTagEntity>

    @Query("SELECT h.* FROM view_hidden_tag h JOIN board_view v ON v.id = h.viewId WHERE v.projectId = :p")
    suspend fun hiddenTags(p: String): List<ViewHiddenTagEntity>

    @Query("SELECT * FROM world") suspend fun worlds(): List<WorldEntity>
    @Query("SELECT * FROM world WHERE id = :id") suspend fun world(id: String): WorldEntity?
    @Query("SELECT * FROM category WHERE worldId = :w") suspend fun categories(w: String): List<CategoryEntity>
    @Query("SELECT * FROM note WHERE worldId = :w") suspend fun notes(w: String): List<NoteEntity>

    @Query("SELECT l.* FROM note_link l JOIN note n ON n.id = l.fromNoteId WHERE n.worldId = :w")
    suspend fun noteLinks(w: String): List<NoteLinkEntity>

    @Query("SELECT * FROM tag WHERE worldId = :w") suspend fun worldTags(w: String): List<TagEntity>

    @Query("SELECT nt.* FROM note_tag nt JOIN note n ON n.id = nt.targetId WHERE n.worldId = :w")
    suspend fun noteTags(w: String): List<NoteTagEntity>

    @Query("SELECT id FROM tag") suspend fun allTagIds(): List<String>
    @Query("SELECT id FROM note") suspend fun allNoteIds(): List<String>
}
