package cl.inacap.invernaderoiot

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import cl.inacap.invernaderoiot.Screens.AdminUserScreen
import cl.inacap.invernaderoiot.Screens.LoginScreen
import cl.inacap.invernaderoiot.Screens.MainControlScreen
import cl.inacap.invernaderoiot.model.InvernaderoViewModel

@Composable
fun NavegacionApp(viewModel: InvernaderoViewModel, sistemaHelper: SistemaHelper) {
    val navController = rememberNavController()

    LaunchedEffect(viewModel.usuarioActual) {
        if (viewModel.usuarioActual == null) {
            navController.navigate("login") {
                popUpTo(0) { inclusive = true }
            }
        }
    }

    NavHost(navController = navController, startDestination = "login") {
        composable("login") {
            LoginScreen(
                navController = navController,
                viewModel = viewModel,
                sistemaHelper = sistemaHelper
            )
        }
        composable("control") {
            MainControlScreen(
                navController = navController,
                viewModel = viewModel,
                sistemaHelper = sistemaHelper
            )
        }
        composable("admin") {
            AdminUserScreen(
                navController = navController,
                viewModel = viewModel,
                sistemaHelper = sistemaHelper
            )
        }
    }
}