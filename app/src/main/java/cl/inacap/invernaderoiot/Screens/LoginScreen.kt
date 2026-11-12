package cl.inacap.invernaderoiot.Screens

import android.Manifest
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.navigation.NavHostController
import cl.inacap.invernaderoiot.SistemaHelper
import cl.inacap.invernaderoiot.model.InvernaderoViewModel

@Composable
fun LoginScreen(
    navController: NavHostController,
    viewModel: InvernaderoViewModel,
    sistemaHelper: SistemaHelper
) {
    var correo by rememberSaveable { mutableStateOf("") }
    var clave by rememberSaveable { mutableStateOf("") }
    val cicloEnCurso = sistemaHelper.obtenerFinCicloMillis() > System.currentTimeMillis()

    val launcherPermisoNotificaciones = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { _ -> }

    LaunchedEffect(Unit) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            launcherPermisoNotificaciones.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    Scaffold { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .padding(24.dp),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Icon(
                imageVector = Icons.Default.Lock,
                contentDescription = "Seguridad ISO 27400",
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(48.dp)
            )

            Spacer(modifier = Modifier.height(8.dp))
            Text("Invernadero IoT", style = MaterialTheme.typography.headlineMedium)
            Text("Acceso Seguro OT", style = MaterialTheme.typography.bodyMedium)

            Spacer(modifier = Modifier.height(24.dp))

            OutlinedTextField(
                value = correo,
                onValueChange = { correo = it },
                label = { Text("Correo Electrónico") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                enabled = !viewModel.cuentaBloqueada && !viewModel.cargandoLogin
            )

            Spacer(modifier = Modifier.height(12.dp))

            OutlinedTextField(
                value = clave,
                onValueChange = { clave = it },
                label = { Text("Contraseña") },
                visualTransformation = PasswordVisualTransformation(),
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                enabled = !viewModel.cuentaBloqueada && !viewModel.cargandoLogin
            )

            Spacer(modifier = Modifier.height(16.dp))

            if (cicloEnCurso) {
                Card(colors = CardDefaults.cardColors(containerColor = Color(0xFFD1ECF1))) {
                    Text(
                        "Proceso OT en curso: Ciclo manual activo en segundo plano (${sistemaHelper.obtenerMinutosCicloActivo()} min).",
                        color = Color(0xFF0C5460),
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(12.dp)
                    )
                }
                Spacer(modifier = Modifier.height(8.dp))
            }

            if (viewModel.cuentaBloqueada) {
                Card(colors = CardDefaults.cardColors(containerColor = Color(0xFFF8D7DA))) {
                    Text(
                        "Cuenta bloqueada por 30s tras 3 intentos fallidos (ISO 27400).",
                        color = Color(0xFF721C24),
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(12.dp)
                    )
                }
                Spacer(modifier = Modifier.height(8.dp))
            }

            if (viewModel.intentosFallidos > 0 && !viewModel.cuentaBloqueada) {
                Text(
                    "Credenciales incorrectas (${viewModel.intentosFallidos}/3 intentos).",
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall
                )
                Spacer(modifier = Modifier.height(8.dp))
            }

            viewModel.mensajeNotificacion?.let {
                Card(colors = CardDefaults.cardColors(containerColor = Color(0xFFFFF3CD))) {
                    Text(
                        it,
                        color = Color(0xFF856404),
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(12.dp)
                    )
                }
                Spacer(modifier = Modifier.height(8.dp))
            }

            Button(
                onClick = {
                    viewModel.iniciarSesion(correo, clave, sistemaHelper) {
                        navController.navigate("control") {
                            popUpTo("login") { inclusive = true }
                        }
                    }
                },
                modifier = Modifier.fillMaxWidth(),
                enabled = !viewModel.cuentaBloqueada && !viewModel.cargandoLogin &&
                        correo.isNotBlank() && clave.isNotBlank()
            ) {
                if (viewModel.cargandoLogin) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(20.dp),
                        color = Color.White,
                        strokeWidth = 2.dp
                    )
                } else {
                    Text("Iniciar Sesión")
                }
            }
        }
    }
}