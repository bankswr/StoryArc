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
import com.storyarc.app.ui.timelines.TimelinesScreen
import kotlinx.serialization.Serializable

@Serializable object ProjectsRoute
@Serializable object SettingsRoute
@Serializable data class ProjectRoute(val projectId: String)
@Serializable data class BoardRoute(val projectId: String, val viewId: String)
@Serializable data class TimelinesRoute(val projectId: String, val viewId: String)

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
            ProjectHomeScreen(r.projectId, onBack = { nav.popBackStack() }, onOpenView = { nav.navigate(BoardRoute(r.projectId, it)) })
        }
        composable<BoardRoute> { entry ->
            val r = entry.toRoute<BoardRoute>()
            BoardScreen(
                r.projectId,
                r.viewId,
                onBack = { nav.popBackStack() },
                onTimelines = { nav.navigate(TimelinesRoute(r.projectId, r.viewId)) },
                onSwitchView = { nav.navigate(BoardRoute(r.projectId, it)) { popUpTo<BoardRoute> { inclusive = true } } },
            )
        }
        composable<TimelinesRoute> { entry ->
            val r = entry.toRoute<TimelinesRoute>()
            TimelinesScreen(r.projectId, r.viewId, onBack = { nav.popBackStack() })
        }
    }
}
