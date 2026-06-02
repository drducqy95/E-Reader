package com.example.ui

import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Explore
import androidx.compose.material.icons.automirrored.filled.LibraryBooks
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.example.viewmodel.LibraryViewModel

@Composable
fun MainScreen(
    libraryViewModel: LibraryViewModel,
    onBookClick: (Int) -> Unit,
    onSettingsClick: () -> Unit,
    onExploreBookClick: (String, String) -> Unit,
    onManageSourcesClick: () -> Unit
) {
    val bottomNavController = rememberNavController()
    
    Scaffold(
        bottomBar = {
            NavigationBar(
                containerColor = MaterialTheme.colorScheme.surface,
                contentColor = MaterialTheme.colorScheme.onSurface
            ) {
                val navBackStackEntry by bottomNavController.currentBackStackEntryAsState()
                val currentRoute = navBackStackEntry?.destination?.route
                
                NavigationBarItem(
                    icon = { Icon(Icons.AutoMirrored.Filled.LibraryBooks, contentDescription = "Thư viện") },
                    label = { Text("Thư viện") },
                    selected = currentRoute == "library_tab",
                    onClick = {
                        if (currentRoute != "library_tab") {
                            bottomNavController.navigate("library_tab") {
                                popUpTo(bottomNavController.graph.startDestinationId) {
                                    saveState = true
                                }
                                launchSingleTop = true
                                restoreState = true
                            }
                        }
                    }
                )
                NavigationBarItem(
                    icon = { Icon(Icons.Default.Explore, contentDescription = "Khám phá") },
                    label = { Text("Khám phá") },
                    selected = currentRoute == "explore_tab",
                    onClick = {
                        if (currentRoute != "explore_tab") {
                            bottomNavController.navigate("explore_tab") {
                                popUpTo(bottomNavController.graph.startDestinationId) {
                                    saveState = true
                                }
                                launchSingleTop = true
                                restoreState = true
                            }
                        }
                    }
                )
            }
        }
    ) { padding ->
        NavHost(
            navController = bottomNavController,
            startDestination = "library_tab",
            modifier = Modifier.padding(padding)
        ) {
            composable("library_tab") {
                LibraryScreen(
                    viewModel = libraryViewModel,
                    onBookClick = onBookClick,
                    onSettingsClick = onSettingsClick
                )
            }
            composable("explore_tab") {
                ExploreScreen(
                    onBookClick = onExploreBookClick,
                    onManageSourcesClick = onManageSourcesClick
                )
            }
        }
    }
}
