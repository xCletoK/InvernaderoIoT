package cl.inacap.invernaderoiot.Screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material.icons.filled.Wifi
import androidx.compose.material.icons.filled.WifiOff
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import androidx.navigation.NavHostController
import cl.inacap.invernaderoiot.SistemaHelper
import cl.inacap.invernaderoiot.model.EstadoConexion
import cl.inacap.invernaderoiot.model.InvernaderoViewModel
import java.util.Locale

@Composable
fun MainControlScreen(
    navController: NavHostController,
    viewModel: InvernaderoViewModel,
    sistemaHelper: SistemaHelper
) {
    var escalaSeleccionada by rememberSaveable { mutableStateOf("°C") }
    val enLinea = viewModel.estadoConexion is EstadoConexion.EnLinea

    val tempDisplay = when (escalaSeleccionada) {
        "°F" -> (viewModel.temperaturaCelsius * 9f / 5f) + 32f
        "K" -> viewModel.temperaturaCelsius + 273.15f
        else -> viewModel.temperaturaCelsius
    }

    val estadoLed = viewModel.obtenerEstadoLedTermico()

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
                .verticalScroll(rememberScrollState())
                .padding(16.dp)
        ) {
            // Encabezado y usuario activo
            Text("Monitoreo y Actuadores OT", style = MaterialTheme.typography.titleLarge)
            Text(
                "Operador: ${viewModel.usuarioActual?.correo ?: "Desconocido"} (${if (viewModel.usuarioActual?.esAdmin == true) "Admin" else "Operador"})",
                style = MaterialTheme.typography.bodySmall
            )

            Spacer(modifier = Modifier.height(8.dp))

            // Indicador de estado de conectividad (Sealed Class)
            val (colorRed, textoRed, iconoRed) = when (viewModel.estadoConexion) {
                is EstadoConexion.EnLinea -> Triple(
                    Color(0xFFD4EDDA),
                    "En Línea · Enlace Nube/OT Activo",
                    Icons.Default.Wifi
                )
                is EstadoConexion.SinSenal -> Triple(
                    Color(0xFFF8D7DA),
                    "Sin Señal · Controles Bloqueados (Fail-Safe)",
                    Icons.Default.WifiOff
                )
                is EstadoConexion.WatchdogOT -> Triple(
                    Color(0xFFFFF3CD),
                    "Watchdog OT · Control Autónomo Local",
                    Icons.Default.Warning
                )
            }

            Card(
                colors = CardDefaults.cardColors(containerColor = colorRed),
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(
                    modifier = Modifier.padding(10.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(imageVector = iconoRed, contentDescription = null)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(textoRed, style = MaterialTheme.typography.bodySmall)
                }
            }

            Spacer(modifier = Modifier.height(10.dp))

            // Banner de notificación interna
            viewModel.mensajeNotificacion?.let { notif ->
                Card(
                    colors = CardDefaults.cardColors(containerColor = Color(0xFFFFF3CD)),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        notif,
                        modifier = Modifier.padding(10.dp),
                        color = Color(0xFF856404),
                        style = MaterialTheme.typography.bodySmall
                    )
                }
                Spacer(modifier = Modifier.height(10.dp))
            }

            // Tarjeta 1: Telemetría, LEDs Térmicos y Escalas
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text("Telemetría en Tiempo Real", style = MaterialTheme.typography.titleMedium)
                    Spacer(modifier = Modifier.height(8.dp))

                    Text(
                        "Temperatura: ${String.format(Locale.US, "%.1f", tempDisplay)} $escalaSeleccionada",
                        style = MaterialTheme.typography.bodyLarge
                    )
                    Text(
                        "Humedad Relativa: ${String.format(Locale.US, "%.1f", viewModel.humedadRelativa)}%",
                        style = MaterialTheme.typography.bodyLarge
                    )

                    Spacer(modifier = Modifier.height(12.dp))

                    // 3 LEDs indicadores de estado térmico (Azul, Verde, Rojo)
                    Text("Señalización Térmica OT:", style = MaterialTheme.typography.bodySmall)
                    Spacer(modifier = Modifier.height(6.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceEvenly
                    ) {
                        LedIndicador(
                            etiqueta = "Frío (<15°C)",
                            colorActivo = Color(0xFF1976D2),
                            encendido = estadoLed == "FRIO"
                        )
                        LedIndicador(
                            etiqueta = "Normal",
                            colorActivo = Color(0xFF2E7D32),
                            encendido = estadoLed == "NORMAL"
                        )
                        LedIndicador(
                            etiqueta = "Calor (>28°C)",
                            colorActivo = Color(0xFFD32F2F),
                            encendido = estadoLed == "CALOR"
                        )
                    }

                    Spacer(modifier = Modifier.height(8.dp))

                    // Selector dinámico de unidades (°C, °F, K)
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        listOf("°C", "°F", "K").forEach { escala ->
                            RadioButton(
                                selected = (escalaSeleccionada == escala),
                                onClick = {
                                    escalaSeleccionada = escala
                                    viewModel.reiniciarTemporizadorInactividad(sistemaHelper)
                                }
                            )
                            Text(escala)
                            Spacer(modifier = Modifier.width(8.dp))
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            // Tarjeta 2: Modo de Operación y Control Manual Temporizado
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text("Modo Automático (Fail-Safe)", style = MaterialTheme.typography.titleSmall)
                        Switch(
                            checked = viewModel.esModoAutomatico,
                            enabled = enLinea,
                            onCheckedChange = { viewModel.cambiarModoOperacion(it, sistemaHelper) }
                        )
                    }

                    if (!viewModel.esModoAutomatico) {
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            "Duración ciclo manual: ${viewModel.duracionManualMinutos} min",
                            style = MaterialTheme.typography.bodySmall
                        )
                        Slider(
                            value = viewModel.duracionManualMinutos.toFloat(),
                            onValueChange = { viewModel.actualizarDuracionManual(it.toInt()) },
                            valueRange = 1f..60f,
                            enabled = enLinea && viewModel.segundosRestantesManual == 0
                        )
                    }

                    if (viewModel.segundosRestantesManual > 0) {
                        val min = viewModel.segundosRestantesManual / 60
                        val seg = viewModel.segundosRestantesManual % 60
                        Text(
                            "Temporizador activo: ${min}m ${seg}s para retorno automático",
                            color = MaterialTheme.colorScheme.primary,
                            style = MaterialTheme.typography.bodySmall
                        )
                    }

                    Spacer(modifier = Modifier.height(8.dp))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text("Bomba de Agua (Riego)")
                        Switch(
                            checked = viewModel.bombaAguaActiva,
                            enabled = !viewModel.esModoAutomatico && enLinea,
                            onCheckedChange = { viewModel.alternarBombaAgua(it, sistemaHelper) }
                        )
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text("Ventilador (Extracción)")
                        Switch(
                            checked = viewModel.ventiladorActivo,
                            enabled = !viewModel.esModoAutomatico && enLinea,
                            onCheckedChange = { viewModel.alternarVentilador(it, sistemaHelper) }
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            // Tarjeta 3: Simulador de Sensores (Para pruebas antes de conectar ESP8266)
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text("Simulador Sensores (Pruebas sin Hardware)", style = MaterialTheme.typography.titleSmall)
                    Spacer(modifier = Modifier.height(4.dp))

                    Text("Simular Temp: ${String.format(Locale.US, "%.1f", viewModel.temperaturaCelsius)} °C", style = MaterialTheme.typography.bodySmall)
                    Slider(
                        value = viewModel.temperaturaCelsius,
                        onValueChange = {
                            viewModel.actualizarTelemetria(it, viewModel.humedadRelativa)
                            viewModel.reiniciarTemporizadorInactividad(sistemaHelper)
                        },
                        valueRange = 5f..40f
                    )

                    Text("Simular Humedad: ${String.format(Locale.US, "%.0f", viewModel.humedadRelativa)} %", style = MaterialTheme.typography.bodySmall)
                    Slider(
                        value = viewModel.humedadRelativa,
                        onValueChange = {
                            viewModel.actualizarTelemetria(viewModel.temperaturaCelsius, it)
                            viewModel.reiniciarTemporizadorInactividad(sistemaHelper)
                        },
                        valueRange = 10f..95f
                    )

                    OutlinedButton(
                        onClick = {
                            viewModel.actualizarTelemetria(-99f, 150f)
                            viewModel.reiniciarTemporizadorInactividad(sistemaHelper)
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("Inyectar Trama Corrupta (Probar Filtro ISO)")
                    }
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            // Tarjeta 4: Historial de Auditoría en Tiempo Real
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text("Auditoría y Eventos (${viewModel.historialEventos.size})", style = MaterialTheme.typography.titleSmall)
                    Spacer(modifier = Modifier.height(8.dp))

                    LazyColumn(modifier = Modifier.height(160.dp)) {
                        items(viewModel.historialEventos) { evento ->
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 4.dp),
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Text(
                                    "[${evento.hora}] ${evento.usuario}: ${evento.accion}",
                                    style = MaterialTheme.typography.bodySmall,
                                    modifier = Modifier.weight(1f)
                                )
                                if (!evento.sincronizado) {
                                    Text(
                                        "Offline",
                                        color = Color(0xFFC62828),
                                        style = MaterialTheme.typography.labelSmall
                                    )
                                }
                            }
                            HorizontalDivider()
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            if (viewModel.usuarioActual?.esAdmin == true) {
                Button(
                    onClick = {
                        viewModel.reiniciarTemporizadorInactividad(sistemaHelper)
                        navController.navigate("admin")
                    },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("Panel Administrador (Gestionar Cuentas)")
                }
                Spacer(modifier = Modifier.height(8.dp))
            }

            Button(
                onClick = {
                    viewModel.cerrarSesionManual(sistemaHelper)
                },
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFC62828)),
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("Cerrar Sesión")
            }
        }
    }
}

@Composable
private fun LedIndicador(etiqueta: String, colorActivo: Color, encendido: Boolean) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(
            modifier = Modifier
                .size(18.dp)
                .clip(CircleShape)
                .background(if (encendido) colorActivo else Color.LightGray)
        )
        Spacer(modifier = Modifier.width(6.dp))
        Text(etiqueta, style = MaterialTheme.typography.bodySmall)
    }
}