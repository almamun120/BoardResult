package com.boardresult.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.List
import androidx.compose.material.icons.filled.Send
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import com.boardresult.data.GradingScheme
import com.boardresult.data.Subject

sealed interface Screen {
    data object Home : Screen
    data object Results : Screen
    data object Setup : Screen
    data object Sms : Screen
    data object Settings : Screen
    data object Boards : Screen
    data object Institutions : Screen
    data object Teachers : Screen
    data object ExamTypes : Screen
    data object Schemes : Screen
    data object Groups : Screen
    data object Subjects : Screen
    data class Bands(val scheme: GradingScheme) : Screen
    data class SubjectDetail(val subject: Subject) : Screen
}

private fun titleOf(s: Screen) = when (s) {
    Screen.Home -> "Board Result"
    Screen.Results -> "Results (publish)"
    Screen.Setup -> "Setup"
    Screen.Sms -> "SMS server"
    Screen.Settings -> "Settings"
    Screen.Boards -> "Boards"
    Screen.Institutions -> "Institutions"
    Screen.Teachers -> "Teachers"
    Screen.ExamTypes -> "Exam types"
    Screen.Schemes -> "Grading schemes"
    Screen.Groups -> "Groups"
    Screen.Subjects -> "Subjects"
    is Screen.Bands -> "Grade bands: ${s.scheme.name}"
    is Screen.SubjectDetail -> "Subject ${s.subject.code}"
}

private class Tab(val screen: Screen, val label: String, val icon: ImageVector)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AdminRoot() {
    var stack by remember { mutableStateOf<List<Screen>>(listOf(Screen.Home)) }
    val cur = stack.last()
    fun go(s: Screen) { stack = stack + s }
    BackHandler(enabled = stack.size > 1) { stack = stack.dropLast(1) }

    val tabs = listOf(
        Tab(Screen.Home, "Home", Icons.Default.Home),
        Tab(Screen.Results, "Results", Icons.Default.Check),
        Tab(Screen.Setup, "Setup", Icons.Default.Build),
        Tab(Screen.Sms, "SMS", Icons.Default.Send),
        Tab(Screen.Settings, "Settings", Icons.Default.Settings)
    )

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(titleOf(cur)) },
                navigationIcon = {
                    if (stack.size > 1) IconButton(onClick = { stack = stack.dropLast(1) }) { Icon(Icons.Default.ArrowBack, "Back") }
                }
            )
        },
        bottomBar = {
            if (stack.size == 1) NavigationBar {
                tabs.forEach { t ->
                    NavigationBarItem(selected = cur == t.screen, onClick = { stack = listOf(t.screen) },
                        icon = { Icon(t.icon, t.label) }, label = { Text(t.label) })
                }
            }
        }
    ) { pad ->
        Box(Modifier.padding(pad)) {
            when (cur) {
                Screen.Home -> HomeScreen()
                Screen.Results -> ResultsScreen()
                Screen.Setup -> SetupScreen { go(it) }
                Screen.Sms -> SmsScreen()
                Screen.Settings -> SettingsScreen()
                Screen.Boards -> BoardsScreen()
                Screen.Institutions -> InstitutionsScreen()
                Screen.Teachers -> TeachersScreen()
                Screen.ExamTypes -> ExamTypesScreen()
                Screen.Schemes -> SchemesScreen { go(Screen.Bands(it)) }
                Screen.Groups -> GroupsScreen()
                Screen.Subjects -> SubjectsScreen { go(Screen.SubjectDetail(it)) }
                is Screen.Bands -> BandsScreen(cur.scheme)
                is Screen.SubjectDetail -> SubjectDetailScreen(cur.subject) { stack = stack.dropLast(1) }
            }
        }
    }
}
