package com.storyarc.app.ui

import androidx.compose.runtime.Composable
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.toRoute
import com.storyarc.app.ui.board.BoardScreen
import com.storyarc.app.ui.project.ProjectHomeScreen
import com.storyarc.app.ui.projects.ProjectsScreen
import com.storyarc.app.ui.settings.SettingsScreen
import com.storyarc.app.ui.tags.TagsScreen
import com.storyarc.app.ui.world.NoteScreen
import com.storyarc.app.ui.world.WorldScreen
import com.storyarc.app.ui.timelines.TimelinesScreen
import kotlinx.serialization.Serializable

@Serializable object ProjectsRoute
@Serializable object SettingsRoute
@Serializable data class ProjectRoute(val projectId: String)
@Serializable data class BoardRoute(val projectId: String, val viewId: String)
@Serializable data class TimelinesRoute(val projectId: String, val viewId: String)
@Serializable data class TagsRoute(val projectId: String)
@Serializable data class WorldRoute(val projectId: String, val worldId: String)
@Serializable data class NoteRoute(val projectId: String, val noteId: String)

@Composable
fun AppNav() {
    val nav = rememberNavController()
    NavHost(nav, startDestination = ProjectsRoute) {
        composable<ProjectsRoute> {
            ProjectsScreen(onOpen = { nav.navigate(ProjectRoute(it)) }, onSettings = { nav.navigate(SettingsRoute) })
        }
        composable<SettingsRoute> {
            SettingsScreen(onBack = { nav.popBackStack() })
        }
        composable<ProjectRoute> { entry ->
            val r = entry.toRoute<ProjectRoute>()
            ProjectHomeScreen(
                r.projectId,
                onBack = { nav.popBackStack() },
                onOpenView = { nav.navigate(BoardRoute(r.projectId, it)) },
                onTags = { nav.navigate(TagsRoute(r.projectId)) },
                onOpenWorld = { nav.navigate(WorldRoute(r.projectId, it)) },
            )
        }
        composable<BoardRoute> { entry ->
            val r = entry.toRoute<BoardRoute>()
            BoardScreen(
                r.projectId,
                r.viewId,
                onBack = { nav.popBackStack() },
                onTimelines = { nav.navigate(TimelinesRoute(r.projectId, r.viewId)) },
                onSwitchView = { nav.navigate(BoardRoute(r.projectId, it)) { popUpTo<BoardRoute> { inclusive = true } } },
                onOpenNote = { nav.navigate(NoteRoute(r.projectId, it)) },
            )
        }
        composable<TagsRoute> { entry ->
            val r = entry.toRoute<TagsRoute>()
            TagsScreen(r.projectId, onBack = { nav.popBackStack() }, onOpenNote = { nav.navigate(NoteRoute(r.projectId, it)) })
        }
        composable<WorldRoute> { entry ->
            val r = entry.toRoute<WorldRoute>()
            WorldScreen(r.projectId, r.worldId, onBack = { nav.popBackStack() }, onOpenNote = { nav.navigate(NoteRoute(r.projectId, it)) })
        }
        composable<NoteRoute> { entry ->
            val r = entry.toRoute<NoteRoute>()
            NoteScreen(r.projectId, r.noteId, onBack = { nav.popBackStack() }, onOpenNote = { nav.navigate(NoteRoute(r.projectId, it)) })
        }
        composable<TimelinesRoute> { entry ->
            val r = entry.toRoute<TimelinesRoute>()
            TimelinesScreen(r.projectId, r.viewId, onBack = { nav.popBackStack() })
        }
    }
}
