package com.starwindow.app.ui.nav

import android.net.Uri
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Scaffold
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
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.starwindow.app.appContainer
import com.starwindow.app.ui.calibration.CalibrationScreen
import com.starwindow.app.ui.calibration.CalibrationViewModel
import com.starwindow.app.ui.capture.CaptureScreen
import com.starwindow.app.ui.capture.CaptureViewModel
import com.starwindow.app.ui.hub.StargazingHubScreen
import com.starwindow.app.ui.hub.StargazingHubViewModel
import com.starwindow.app.ui.guide.PhotoGuideScreen
import com.starwindow.app.ui.guide.PhotoGuideViewModel
import com.starwindow.app.data.planning.PlannedPath
import com.starwindow.app.ui.planning.CalendarScreen
import com.starwindow.app.ui.planning.CalendarViewModel
import com.starwindow.app.ui.planning.PlanningScreen
import com.starwindow.app.ui.planning.PlanningViewModel
import com.starwindow.app.ui.search.ObjectDetailScreen
import com.starwindow.app.ui.search.ObjectDetailViewModel
import com.starwindow.app.ui.search.ObjectSearchScreen
import com.starwindow.app.ui.search.ObjectSearchViewModel
import com.starwindow.app.ui.theme.StarWindowColors
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
    const val HUB = "hub"
    const val GUIDE = "guide"
    const val OBJECT_DETAIL = "objects/{objectId}"
    const val CALENDAR = "calendar"
    const val PLANNING = "planning/{objectId}"

    fun windowDetail(windowId: String) = "windows/$windowId"

    /** Designations contain spaces and slashes ("NGC 292"), so they have to be encoded. */
    fun objectDetail(objectId: String) = "objects/${Uri.encode(objectId)}"

    fun planning(objectId: String) = "planning/${Uri.encode(objectId)}"
}

/**
 * Alle Bildschirme, und darunter die Leiste.
 *
 * Die Leiste steht hier und nicht in den einzelnen Bildschirmen, weil sie sonst fünfmal existieren
 * würde und irgendwann sechsmal verschieden. Sie erscheint nur auf den Zielen, die sie selbst
 * anbietet: Auf einem Objektblatt oder in der Kalibrierung wäre sie ein zweiter Ausgang neben dem
 * Zurück-Pfeil, und zwei Ausgänge aus einem Detail sind einer zu viel.
 *
 * [Scaffold] trägt das, weil es die Systemleisten-Einsätze an die Leiste weitergibt und dem Inhalt
 * darüber sagt, dass sie verbraucht sind — sonst hielte jeder Bildschirm über seinem
 * `safeDrawingPadding` noch einmal Platz für eine Navigationsleiste frei, die er gar nicht mehr
 * berührt.
 */
@Composable
fun StarWindowNavHost(
    modifier: Modifier = Modifier,
    navController: NavHostController = rememberNavController(),
) {
    val container = LocalContext.current.appContainer
    val backStackEntry by navController.currentBackStackEntryAsState()
    val route = backStackEntry?.destination?.route
    val destination = BottomDestination.entries.firstOrNull { it.route == route }

    Scaffold(
        modifier = modifier,
        containerColor = StarWindowColors.Night,
        contentWindowInsets = WindowInsets(0),
        bottomBar = {
            if (destination != null) {
                StarWindowBottomBar(
                    current = destination,
                    onSelect = { navController.switchTo(it.route) },
                )
            }
        },
    ) { padding ->
        NavHost(
            navController = navController,
            startDestination = Routes.CAPTURE,
            modifier = Modifier.padding(padding).consumeWindowInsets(padding),
        ) {
            composable(Routes.CAPTURE) {
                val viewModel: CaptureViewModel = viewModel(factory = CaptureViewModel.factory(container))
                CaptureScreen(
                    viewModel = viewModel,
                    onOpenCalibration = { navController.navigate(Routes.CALIBRATION) },
                    onOpenSearch = { navController.navigate(Routes.SEARCH) },
                    onOpenTrackedObject = { navController.navigate(Routes.objectDetail(it)) },
                )
            }

            composable(Routes.HUB) {
                val viewModel: StargazingHubViewModel =
                    viewModel(factory = StargazingHubViewModel.factory(container))
                StargazingHubScreen(
                    viewModel = viewModel,
                    onOpenObject = { navController.navigate(Routes.objectDetail(it)) },
                )
            }

            composable(Routes.GUIDE) {
                val viewModel: PhotoGuideViewModel =
                    viewModel(factory = PhotoGuideViewModel.factory(container))
                PhotoGuideScreen(
                    viewModel = viewModel,
                    onOpenObject = { navController.navigate(Routes.objectDetail(it)) },
                )
            }

            composable(Routes.WEATHER) {
                val viewModel: WeatherViewModel =
                    viewModel(factory = WeatherViewModel.factory(container))
                WeatherScreen(viewModel = viewModel)
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
}

/**
 * Von einem Leistenziel zum nächsten wechseln, statt es obendrauf zu legen.
 *
 * Ohne das wächst der Stapel mit jedem Tippen — Sucher, Wetter, Kalender, Wetter, Kalender — und
 * die Zurück-Geste muss sich durch die ganze Sitzung zurückarbeiten, bevor sie die App verlässt.
 * Der Sucher bleibt als Wurzel darunter stehen, weil er der Ort ist, an dem die App anfängt und an
 * dem sie enden soll; `saveState`/`restoreState` halten dabei fest, wie weit ein Ziel gescrollt war.
 */
private fun NavHostController.switchTo(route: String) {
    navigate(route) {
        popUpTo(Routes.CAPTURE) { saveState = true }
        launchSingleTop = true
        restoreState = true
    }
}
