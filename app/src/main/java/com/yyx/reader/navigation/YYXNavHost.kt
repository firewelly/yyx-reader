package com.yyx.reader.navigation

import androidx.compose.runtime.Composable
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.yyx.reader.feature.bookdetail.DetailScreen
import com.yyx.reader.feature.library.LibraryScreen
import com.yyx.reader.feature.reader.ReaderScreen
import com.yyx.reader.feature.settings.SettingsScreen

@Composable
fun YYXNavHost() {
    val navController = rememberNavController()
    NavHost(navController = navController, startDestination = "library") {
        composable("library") {
            LibraryScreen(
                onOpenBook = { id -> navController.navigate("book/$id") },
                onOpenSettings = { navController.navigate("settings") },
            )
        }
        composable(
            route = "book/{bookId}",
            arguments = listOf(navArgument("bookId") { type = NavType.LongType }),
        ) {
            DetailScreen(
                onBack = { navController.popBackStack() },
                onRead = { bookId, chapterIdx ->
                    val arg = chapterIdx?.takeIf { it >= 0 }?.let { "?chapterIdx=$it" } ?: ""
                    navController.navigate("reader/$bookId$arg")
                },
            )
        }
        composable(
            route = "reader/{bookId}?chapterIdx={chapterIdx}",
            arguments = listOf(
                navArgument("bookId") { type = NavType.LongType },
                navArgument("chapterIdx") { type = NavType.IntType; defaultValue = -1 },
            ),
        ) {
            ReaderScreen(
                onBack = { navController.popBackStack() },
            )
        }
        composable("settings") {
            SettingsScreen(onBack = { navController.popBackStack() })
        }
    }
}
