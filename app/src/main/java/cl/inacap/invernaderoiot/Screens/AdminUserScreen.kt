package cl.inacap.invernaderoiot.Screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import androidx.navigation.NavHostController
import cl.inacap.invernaderoiot.SistemaHelper
import cl.inacap.invernaderoiot.model.InvernaderoViewModel

@Composable
fun AdminUserScreen(
    navController: NavHostController,
    viewModel: InvernaderoViewModel,
    sistemaHelper: SistemaHelper
) {
    val esAdministrador = viewModel.usuarioActual?.esAdmin == true

    LaunchedEffect(esAdministrador) {
        if (!esAdministrador) {
            navController.popBackStack()
        } else {
            viewModel.reiniciarTemporizadorInactividad(sistemaHelper)
        }
    }

    if (!esAdministrador) return

    Scaffold(
        modifier = Modifier.pointerInput(Unit) {
            awaitPointerEventScope {
                while (true) {
                    awaitPointerEvent(PointerEventPass.Initial)
                    viewModel.reiniciarTemporizadorInactividad(sistemaHelper)
                }
            }
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .padding(16.dp)
        ) {
            Text("Gestión de Usuarios (ISO 27400)", style = MaterialTheme.typography.headlineSmall)
            Text("Control de acceso y autorización de operadores", style = MaterialTheme.typography.bodyMedium)

            Spacer(modifier = Modifier.height(16.dp))

            viewModel.mensajeNotificacion?.let { notif ->
                Card(
                    colors = CardDefaults.cardColors(containerColor = Color(0xFFD4EDDA)),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        notif,
                        modifier = Modifier.padding(12.dp),
                        color = Color(0xFF155724),
                        style = MaterialTheme.typography.bodySmall
                    )
                }
                Spacer(modifier = Modifier.height(12.dp))
            }

            LazyColumn(modifier = Modifier.weight(1f)) {
                items(viewModel.usuariosRegistrados) { usuario ->
                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 4.dp)
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(16.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(usuario.correo, style = MaterialTheme.typography.titleMedium)
                                Text(
                                    if (usuario.esAdmin) "Rol: Administrador" else "Rol: Operador",
                                    style = MaterialTheme.typography.bodySmall
                                )
                                Text(
                                    if (usuario.estaAutorizado) "Estado: Autorizado" else "Estado: Pendiente",
                                    color = if (usuario.estaAutorizado) Color(0xFF2E7D32) else Color(0xFFC62828),
                                    style = MaterialTheme.typography.bodySmall
                                )
                                Text(
                                    "Hash SHA-256: ${usuario.claveHash.take(12)}...",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = Color.Gray
                                )
                            }

                            if (!usuario.esAdmin) {
                                if (!usuario.estaAutorizado) {
                                    Button(onClick = { viewModel.autorizarUsuario(usuario.correo) }) {
                                        Text("Autorizar")
                                    }
                                } else {
                                    OutlinedButton(onClick = { viewModel.revocarUsuario(usuario.correo) }) {
                                        Text("Revocar")
                                    }
                                }
                            }
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            Button(
                onClick = {
                    viewModel.reiniciarTemporizadorInactividad(sistemaHelper)
                    navController.popBackStack()
                },
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("Volver al Panel Principal")
            }
        }
    }
}