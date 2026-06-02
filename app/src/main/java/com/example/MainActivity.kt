package com.example

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.example.ui.LibraryScreen
import com.example.ui.ReaderScreen
import com.example.ui.SettingsScreen
import com.example.ui.MainScreen
import com.example.ui.BookDetailScreen
import com.example.ui.SourceManagementScreen
import com.example.ui.theme.MyApplicationTheme
import com.example.viewmodel.LibraryViewModel
import com.example.viewmodel.LibraryViewModelFactory
import com.example.viewmodel.ReaderViewModel
import com.example.viewmodel.ReaderViewModelFactory

class MainActivity : ComponentActivity() {
  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    enableEdgeToEdge()
    setContent {
      val application = application as EReaderApplication
      val libraryViewModel: LibraryViewModel = viewModel(
          factory = LibraryViewModelFactory(application.bookRepository, application.settingsRepository)
      )
      val themeMode by libraryViewModel.themeMode.collectAsStateWithLifecycle()
      
      val isDarkTheme = when (themeMode) {
          1 -> false
          2 -> true
          else -> isSystemInDarkTheme()
      }

      MyApplicationTheme(darkTheme = isDarkTheme) {
        val navController = rememberNavController()
        
        NavHost(navController = navController, startDestination = "main") {
            composable("main") {
                MainScreen(
                    libraryViewModel = libraryViewModel,
                    onBookClick = { bookId -> 
                        navController.navigate("reader/$bookId")
                    },
                    onSettingsClick = {
                        navController.navigate("settings")
                    },
                    onExploreBookClick = { sourceId, bookUrl ->
                        navController.navigate(
                            "book_detail?sourceId=${java.net.URLEncoder.encode(sourceId, "UTF-8")}" +
                                "&url=${java.net.URLEncoder.encode(bookUrl, "UTF-8")}"
                        )
                    },
                    onManageSourcesClick = {
                        navController.navigate("source_manage")
                    }
                )
            }
            composable("source_manage") {
                SourceManagementScreen(onBack = { navController.popBackStack() })
            }
            composable("reader/{bookId}") { backStackEntry ->
                val bookId = backStackEntry.arguments?.getString("bookId")?.toIntOrNull() ?: return@composable
                val readerViewModel: ReaderViewModel = viewModel(
                    factory = ReaderViewModelFactory(
                        application,
                        bookId,
                        application.bookRepository,
                        application.database.readerDao(),
                        application.settingsRepository,
                        application.translationOrchestrator,
                        application.onlineLibraryService
                    )
                )
                ReaderScreen(
                    viewModel = readerViewModel,
                    onBack = { navController.popBackStack() }
                )
            }
            composable("settings") {
                SettingsScreen(onBack = { navController.popBackStack() })
            }
            composable("book_detail?sourceId={sourceId}&url={url}") { backStackEntry ->
                val sourceId = backStackEntry.arguments?.getString("sourceId") ?: ""
                val url = backStackEntry.arguments?.getString("url") ?: ""
                BookDetailScreen(
                    sourceId = java.net.URLDecoder.decode(sourceId, "UTF-8"),
                    bookUrl = java.net.URLDecoder.decode(url, "UTF-8"),
                    onBack = { navController.popBackStack() },
                    onOpenReader = { bookId -> 
                        navController.navigate("reader/$bookId")
                    }
                )
            }
        }
      }
    }
  }
}
