package cl.inacap.invernaderoiot

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.lifecycle.viewmodel.compose.viewModel
import cl.inacap.invernaderoiot.model.InvernaderoViewModel

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val sistemaHelper = SistemaHelper(applicationContext)

        setContent {
            val viewModel: InvernaderoViewModel = viewModel()

            DisposableEffect(Unit) {
                sistemaHelper.iniciarMonitoreoRed { conectado ->
                    viewModel.actualizarEstadoRed(conectado, sistemaHelper)
                }
                onDispose {
                    sistemaHelper.detenerMonitoreoRed()
                }
            }

            MaterialTheme(
                colorScheme = lightColorScheme(
                    primary = Color(0xFF1B5E20),
                    background = Color.White,
                    surface = Color.White,
                    onBackground = Color.Black,
                    onSurface = Color.Black
                )
            ) {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = Color.White
                ) {
                    NavegacionApp(viewModel = viewModel, sistemaHelper = sistemaHelper)
                }
            }
        }
    }
}