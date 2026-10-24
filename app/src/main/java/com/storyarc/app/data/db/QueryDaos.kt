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
                  (SELECT COUNT(*) FROM node_tag) + (SELECT COUNT(*) FROM tag)"""
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
