package com.starwindow.app.ui.nav

import android.net.Uri
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import kotlinx.coroutines.launch
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.starwindow.app.appContainer
import com.starwindow.app.ui.calibration.CalibrationScreen
import com.starwindow.app.ui.calibration.CalibrationViewModel
import com.starwindow.app.ui.capture.CaptureScreen
import com.starwindow.app.ui.capture.CaptureViewModel
import com.starwindow.app.data.planning.PlannedPath
import com.starwindow.app.ui.planning.CalendarScreen
import com.starwindow.app.ui.planning.CalendarViewModel
import com.starwindow.app.ui.planning.PlanningScreen
import com.starwindow.app.ui.planning.PlanningViewModel
import com.starwindow.app.ui.search.ObjectDetailScreen
import com.starwindow.app.ui.search.ObjectDetailViewModel
import com.starwindow.app.ui.search.ObjectSearchScreen
import com.starwindow.app.ui.search.ObjectSearchViewModel
import com.starwindow.app.ui.weather.WeatherScreen
import com.starwindow.app.ui.weather.WeatherViewModel
import com.starwindow.app.ui.windows.WindowDetailScreen
import com.starwindow.app.ui.windows.WindowDetailViewModel
import com.starwindow.app.ui.windows.WindowListScreen
import com.starwindow.app.ui.windows.WindowListViewModel

object Routes {
    const val CAPTURE = "capture"
    const val CALIBRATION = "calibration"
    const val WINDOW_LIST = "windows"
    const val WINDOW_DETAIL = "windows/{windowId}"
    const val SEARCH = "search"
    const val WEATHER = "weather"
    const val OBJECT_DETAIL = "objects/{objectId}"
    const val CALENDAR = "calendar"
    const val PLANNING = "planning/{objectId}"

    fun windowDetail(windowId: String) = "windows/$windowId"

    /** Designations contain spaces and slashes ("NGC 292"), so they have to be encoded. */
    fun objectDetail(objectId: String) = "objects/${Uri.encode(objectId)}"

    fun planning(objectId: String) = "planning/${Uri.encode(objectId)}"
}

@Composable
fun StarWindowNavHost(
    modifier: Modifier = Modifier,
    navController: NavHostController = rememberNavController(),
) {
    val container = LocalContext.current.appContainer

    NavHost(
        navController = navController,
        startDestination = Routes.CAPTURE,
        modifier = modifier,
    ) {
        composable(Routes.CAPTURE) {
            val viewModel: CaptureViewModel = viewModel(factory = CaptureViewModel.factory(container))
            CaptureScreen(
                viewModel = viewModel,
                onOpenWindows = { navController.navigate(Routes.WINDOW_LIST) },
                onOpenCalibration = { navController.navigate(Routes.CALIBRATION) },
                onOpenSearch = { navController.navigate(Routes.SEARCH) },
                onOpenWeather = { navController.navigate(Routes.WEATHER) },
                onOpenCalendar = { navController.navigate(Routes.CALENDAR) },
                onOpenTrackedObject = { navController.navigate(Routes.objectDetail(it)) },
            )
        }

        composable(Routes.WEATHER) {
            val viewModel: WeatherViewModel =
                viewModel(factory = WeatherViewModel.factory(container))
            WeatherScreen(
                viewModel = viewModel,
                onBack = { navController.popBackStack() },
            )
        }

        composable(Routes.SEARCH) {
            val viewModel: ObjectSearchViewModel =
                viewModel(factory = ObjectSearchViewModel.factory(container))
            ObjectSearchScreen(
                viewModel = viewModel,
                onOpenObject = { navController.navigate(Routes.objectDetail(it)) },
                onBack = { navController.popBackStack() },
            )
        }

        composable(
            route = Routes.OBJECT_DETAIL,
            arguments = listOf(navArgument("objectId") { type = NavType.StringType }),
        ) { entry ->
            val objectId = entry.arguments?.getString("objectId").orEmpty()
            val viewModel: ObjectDetailViewModel = viewModel(
                factory = ObjectDetailViewModel.factory(container, objectId),
                key = objectId,
            )
            ObjectDetailScreen(
                viewModel = viewModel,
                onBack = { navController.popBackStack() },
                // "Track" means "show it to me in the sky", so it goes all the way back to the
                // viewfinder rather than leaving the search stack sitting underneath.
                onStartTracking = {
                    navController.popBackStack(Routes.CAPTURE, inclusive = false)
                },
                onOpenPlanning = { navController.navigate(Routes.planning(objectId)) },
            )
        }

        composable(Routes.CALENDAR) {
            val viewModel: CalendarViewModel =
                viewModel(factory = CalendarViewModel.factory(container))
            val scope = rememberCoroutineScope()
            CalendarScreen(
                viewModel = viewModel,
                onOpenObject = { navController.navigate(Routes.objectDetail(it)) },
                // "Pfad zeigen" resolves the planned night into a tracking target carrying the
                // night's time span, then goes all the way back to the viewfinder — the same route
                // "Track" takes, because it answers the same question with more of an answer.
                onShowPath = { session ->
                    scope.launch {
                        PlannedPath.track(container, session)
                        navController.popBackStack(Routes.CAPTURE, inclusive = false)
                    }
                },
                onBack = { navController.popBackStack() },
            )
        }

        composable(
            route = Routes.PLANNING,
            arguments = listOf(navArgument("objectId") { type = NavType.StringType }),
        ) { entry ->
            val objectId = entry.arguments?.getString("objectId").orEmpty()
            val viewModel: PlanningViewModel = viewModel(
                factory = PlanningViewModel.factory(container, objectId),
                key = objectId,
            )
            PlanningScreen(
                viewModel = viewModel,
                onBack = { navController.popBackStack() },
            )
        }

        composable(Routes.CALIBRATION) {
            val viewModel: CalibrationViewModel =
                viewModel(factory = CalibrationViewModel.factory(container))
            val settings by container.settingsStore.settings.collectAsStateWithLifecycle()
            CalibrationScreen(
                viewModel = viewModel,
                settings = settings,
                onBack = { navController.popBackStack() },
            )
        }

        composable(Routes.WINDOW_LIST) {
            val viewModel: WindowListViewModel =
                viewModel(factory = WindowListViewModel.factory(container))
            WindowListScreen(
                viewModel = viewModel,
                onOpenWindow = { navController.navigate(Routes.windowDetail(it)) },
                onBack = { navController.popBackStack() },
                // Pointing at a window means "show it to me in the sky", so it goes all the way
                // back to the viewfinder — the same route "Track" takes for an object.
                onTrackWindow = { navController.popBackStack(Routes.CAPTURE, inclusive = false) },
            )
        }

        composable(
            route = Routes.WINDOW_DETAIL,
            arguments = listOf(navArgument("windowId") { type = NavType.StringType }),
        ) { entry ->
            val windowId = entry.arguments?.getString("windowId").orEmpty()
            val viewModel: WindowDetailViewModel = viewModel(
                factory = WindowDetailViewModel.factory(container, windowId),
                key = windowId,
            )
            WindowDetailScreen(
                viewModel = viewModel,
                onBack = { navController.popBackStack() },
                onTrackWindow = { navController.popBackStack(Routes.CAPTURE, inclusive = false) },
            )
        }
    }
}
