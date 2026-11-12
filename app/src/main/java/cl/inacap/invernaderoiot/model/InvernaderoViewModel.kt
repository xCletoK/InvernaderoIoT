package cl.inacap.invernaderoiot.model

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import cl.inacap.invernaderoiot.SistemaHelper
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.database.DataSnapshot
import com.google.firebase.database.DatabaseError
import com.google.firebase.database.FirebaseDatabase
import com.google.firebase.database.ValueEventListener
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

sealed class EstadoConexion {
    object EnLinea : EstadoConexion()
    object SinSenal : EstadoConexion()
    object WatchdogOT : EstadoConexion()
}

data class Usuario(
    val correo: String,
    val claveHash: String = "",
    val esAdmin: Boolean = false,
    val estaAutorizado: Boolean = false
)

data class EventoAuditoria(
    val hora: String = "",
    val usuario: String = "",
    val accion: String = "",
    val sincronizado: Boolean = true
)

class InvernaderoViewModel : ViewModel() {

    companion object {
        private var persistenciaConfigurada = false
    }

    private val auth: FirebaseAuth = FirebaseAuth.getInstance()
    private val db: FirebaseDatabase = FirebaseDatabase.getInstance().apply {
        if (!persistenciaConfigurada) {
            try {
                setPersistenceEnabled(true)
                persistenciaConfigurada = true
            } catch (_: Exception) {
            }
        }
    }
    private val rootRef = db.getReference("invernadero")

    // Telemetría en tiempo real
    var temperaturaCelsius by mutableStateOf(24.5f)
    var humedadRelativa by mutableStateOf(65.0f)

    // Actuadores y modo OT
    var esModoAutomatico by mutableStateOf(true)
    var bombaAguaActiva by mutableStateOf(false)
    var ventiladorActivo by mutableStateOf(false)

    // Reloj 2: Temporizador de ejecución manual persistente
    var duracionManualMinutos by mutableStateOf(15)
    var segundosRestantesManual by mutableStateOf(0)
    var finCicloManualMillis by mutableStateOf(0L)

    // Conectividad y seguridad ISO 27400
    var estadoConexion by mutableStateOf<EstadoConexion>(EstadoConexion.EnLinea)
    var usuarioActual by mutableStateOf<Usuario?>(null)
    var cargandoLogin by mutableStateOf(false)
    var intentosFallidos by mutableStateOf(0)
    var cuentaBloqueada by mutableStateOf(false)
    var mensajeNotificacion by mutableStateOf<String?>(null)

    val usuariosRegistrados = mutableStateListOf<Usuario>()
    val historialEventos = mutableStateListOf<EventoAuditoria>()

    private var temporizadorInactividadJob: Job? = null
    private var temporizadorActuadorJob: Job? = null
    private var escuchandoNube = false
    private var ultimoHelper: SistemaHelper? = null

    private fun correoAKey(correo: String): String =
        correo.trim().lowercase().replace(".", "_").replace("@", "_")

    // --- 1. AUTENTICACIÓN REAL CON FIREBASE AUTH ---

    fun iniciarSesion(
        correo: String,
        clave: String,
        helper: SistemaHelper,
        onExito: () -> Unit
    ) {
        if (cuentaBloqueada || cargandoLogin) return

        ultimoHelper = helper
        val correoLimpio = correo.trim().lowercase()
        cargandoLogin = true
        mensajeNotificacion = null

        auth.signInWithEmailAndPassword(correoLimpio, clave)
            .addOnSuccessListener {
                inicializarDatosBaseSiNoExisten {
                    verificarAutorizacionYEntrar(correoLimpio, clave, helper, onExito)
                }
            }
            .addOnFailureListener {
                cargandoLogin = false
                intentosFallidos++
                if (intentosFallidos >= 3) {
                    cuentaBloqueada = true
                    bloquearCuentaTemporizada()
                }
            }
    }

    private fun inicializarDatosBaseSiNoExisten(onListo: () -> Unit) {
        rootRef.child("usuarios").get().addOnSuccessListener { snapshot ->
            if (!snapshot.exists()) {
                val usuariosIniciales = mapOf(
                    correoAKey("admin@huerto.cl") to mapOf(
                        "correo" to "admin@huerto.cl",
                        "esAdmin" to true,
                        "estaAutorizado" to true
                    ),
                    correoAKey("operador@huerto.cl") to mapOf(
                        "correo" to "operador@huerto.cl",
                        "esAdmin" to false,
                        "estaAutorizado" to false
                    )
                )
                rootRef.child("usuarios").setValue(usuariosIniciales)
                rootRef.child("telemetria").setValue(
                    mapOf("temperatura" to 24.5, "humedad" to 65.0)
                )
                rootRef.child("actuadores").setValue(
                    mapOf(
                        "modoAutomatico" to true,
                        "bomba" to false,
                        "ventilador" to false,
                        "finCicloMillis" to 0L
                    )
                )
            }
            onListo()
        }.addOnFailureListener {
            onListo()
        }
    }

    private fun verificarAutorizacionYEntrar(
        correo: String,
        clave: String,
        helper: SistemaHelper,
        onExito: () -> Unit
    ) {
        val key = correoAKey(correo)
        rootRef.child("usuarios").child(key).get()
            .addOnSuccessListener { snap ->
                cargandoLogin = false

                if (!snap.exists() && correo != "admin@huerto.cl") {
                    rootRef.child("usuarios").child(key).setValue(
                        mapOf(
                            "correo" to correo,
                            "esAdmin" to false,
                            "estaAutorizado" to false
                        )
                    )
                    auth.signOut()
                    mensajeNotificacion = "Solicitud enviada. Cuenta pendiente de autorización por el Administrador."
                    return@addOnSuccessListener
                }

                val esAdmin = snap.child("esAdmin").getValue(Boolean::class.java) ?: (correo == "admin@huerto.cl")
                val autorizado = snap.child("estaAutorizado").getValue(Boolean::class.java) ?: esAdmin

                if (!autorizado) {
                    auth.signOut()
                    mensajeNotificacion = "Cuenta pendiente de autorización por el Administrador."
                    return@addOnSuccessListener
                }

                helper.marcarCerrarAuthAlFinalizar(false)
                usuarioActual = Usuario(
                    correo = correo,
                    claveHash = SistemaHelper.hashSHA256(clave),
                    esAdmin = esAdmin,
                    estaAutorizado = true
                )
                intentosFallidos = 0
                helper.guardarSesion(correo, esAdmin)
                iniciarSincronizacionTiempoReal(helper)
                registrarEvento(correo, "Inicio de sesión verificado con token Firebase.")
                reiniciarTemporizadorInactividad(helper)
                onExito()
            }
            .addOnFailureListener {
                cargandoLogin = false
                mensajeNotificacion = "Error verificando permisos en la nube."
            }
    }

    private fun bloquearCuentaTemporizada() {
        viewModelScope.launch {
            delay(30000)
            cuentaBloqueada = false
            intentosFallidos = 0
        }
    }

    // --- 2. SINCRONIZACIÓN BIDIRECCIONAL EN TIEMPO REAL ---

    private fun iniciarSincronizacionTiempoReal(helper: SistemaHelper) {
        ultimoHelper = helper
        if (escuchandoNube) return
        escuchandoNube = true

        rootRef.child("telemetria").addValueEventListener(object : ValueEventListener {
            override fun onDataChange(snapshot: DataSnapshot) {
                val temp = (snapshot.child("temperatura").value as? Number)?.toFloat() ?: return
                val hum = (snapshot.child("humedad").value as? Number)?.toFloat() ?: return
                if (!temp.isNaN() && !hum.isNaN() && temp in -10f..60f && hum in 0f..100f) {
                    temperaturaCelsius = temp
                    humedadRelativa = hum
                    if (esModoAutomatico) evaluarReglasAutomaticasLocales()
                }
            }

            override fun onCancelled(error: DatabaseError) {}
        })

        rootRef.child("actuadores").addValueEventListener(object : ValueEventListener {
            override fun onDataChange(snapshot: DataSnapshot) {
                val autoRemoto = snapshot.child("modoAutomatico").getValue(Boolean::class.java) ?: true
                val bombaRemota = snapshot.child("bomba").getValue(Boolean::class.java) ?: false
                val ventRemoto = snapshot.child("ventilador").getValue(Boolean::class.java) ?: false
                val finRemoto = (snapshot.child("finCicloMillis").value as? Number)?.toLong() ?: 0L

                esModoAutomatico = autoRemoto
                bombaAguaActiva = bombaRemota
                ventiladorActivo = ventRemoto

                if (finRemoto > System.currentTimeMillis() && !autoRemoto && (bombaRemota || ventRemoto)) {
                    finCicloManualMillis = finRemoto
                    iniciarContadorVisual()
                } else if (autoRemoto) {
                    // Solo cancela localmente si otro dispositivo activó Modo Automático explícitamente
                    temporizadorActuadorJob?.cancel()
                    finCicloManualMillis = 0L
                    segundosRestantesManual = 0
                    ultimoHelper?.cancelarAlarmaFinCiclo()
                }
            }

            override fun onCancelled(error: DatabaseError) {}
        })

        rootRef.child("usuarios").addValueEventListener(object : ValueEventListener {
            override fun onDataChange(snapshot: DataSnapshot) {
                usuariosRegistrados.clear()
                for (child in snapshot.children) {
                    val correo = child.child("correo").getValue(String::class.java) ?: continue
                    val esAdmin = child.child("esAdmin").getValue(Boolean::class.java) ?: false
                    val autorizado = child.child("estaAutorizado").getValue(Boolean::class.java) ?: false
                    usuariosRegistrados.add(
                        Usuario(
                            correo = correo,
                            claveHash = SistemaHelper.hashSHA256(correo),
                            esAdmin = esAdmin,
                            estaAutorizado = autorizado
                        )
                    )
                    if (usuarioActual?.correo == correo && !autorizado) {
                        usuarioActual = null
                        auth.signOut()
                        mensajeNotificacion = "Su acceso fue revocado por el Administrador."
                    }
                }
            }

            override fun onCancelled(error: DatabaseError) {}
        })

        rootRef.child("auditoria").limitToLast(30).addValueEventListener(object : ValueEventListener {
            override fun onDataChange(snapshot: DataSnapshot) {
                historialEventos.clear()
                val listaTemp = mutableListOf<EventoAuditoria>()
                for (child in snapshot.children) {
                    val evento = child.getValue(EventoAuditoria::class.java)
                    if (evento != null) listaTemp.add(evento)
                }
                historialEventos.addAll(listaTemp.reversed())
            }

            override fun onCancelled(error: DatabaseError) {}
        })
    }

    // --- 3. GESTIÓN DE SESIÓN Y RELOJES SEPARADOS ---

    fun reiniciarTemporizadorInactividad(helper: SistemaHelper? = null) {
        if (helper != null) ultimoHelper = helper
        if (usuarioActual == null) return

        val helperActivo = helper ?: ultimoHelper
        temporizadorInactividadJob?.cancel()
        temporizadorInactividadJob = viewModelScope.launch {
            delay(60_000)
            cerrarSesionPorInactividad(helperActivo)
        }
    }

    fun cerrarSesionPorInactividad(helper: SistemaHelper? = null) {
        val helperActivo = helper ?: ultimoHelper
        val correoPrevio = usuarioActual?.correo ?: return
        usuarioActual = null
        helperActivo?.limpiarSesion()

        val cicloActivo = finCicloManualMillis > System.currentTimeMillis() ||
                (helperActivo?.obtenerFinCicloMillis() ?: 0L) > System.currentTimeMillis()

        if (cicloActivo) {
            helperActivo?.marcarCerrarAuthAlFinalizar(true)
            mensajeNotificacion = "Sesión cerrada por inactividad (60s). El actuador manual programado continúa activo."
            registrarEvento(correoPrevio, "Cierre de UI por inactividad (Ciclo manual vigente).")
            helperActivo?.enviarNotificacionExterna(
                titulo = "Sesión Cerrada por Inactividad",
                mensaje = "Por seguridad ISO 27400 se cerró la sesión de $correoPrevio. El ciclo manual sigue activo.",
                id = 3001
            )
        } else {
            if (!esModoAutomatico) {
                esModoAutomatico = true
                bombaAguaActiva = false
                ventiladorActivo = false
                sincronizarActuadoresNube()
            }
            auth.signOut()
            mensajeNotificacion = "Sesión expirada por inactividad (60s). Modo Automático (Fail-Safe) activo."
            registrarEvento(correoPrevio, "Sesión expirada -> Fail-Safe en Modo Automático.")
            helperActivo?.enviarNotificacionExterna(
                titulo = "Sesión Cerrada por Inactividad",
                mensaje = "Se cerró la sesión de $correoPrevio tras 60s sin actividad. Sistema en Modo Automático.",
                id = 3001
            )
        }
    }

    fun cerrarSesionManual(helper: SistemaHelper) {
        ultimoHelper = helper
        val correoPrevio = usuarioActual?.correo ?: "Usuario"
        temporizadorInactividadJob?.cancel()
        registrarEvento(correoPrevio, "Cierre de sesión manual.")
        usuarioActual = null
        helper.limpiarSesion()

        val cicloActivo = finCicloManualMillis > System.currentTimeMillis() ||
                helper.obtenerFinCicloMillis() > System.currentTimeMillis()

        if (cicloActivo) {
            helper.marcarCerrarAuthAlFinalizar(true)
            mensajeNotificacion = "Sesión cerrada. El ciclo manual programado continúa activo hasta finalizar."
        } else {
            mensajeNotificacion = null
            auth.signOut()
        }
    }

    // --- 4. CONECTIVIDAD Y RESILIENCIA OFFLINE ---

    fun actualizarEstadoRed(conectado: Boolean, helper: SistemaHelper?) {
        if (helper != null) ultimoHelper = helper
        val cicloActivo = finCicloManualMillis > System.currentTimeMillis() ||
                (helper?.obtenerFinCicloMillis() ?: 0L) > System.currentTimeMillis()

        if (!conectado) {
            estadoConexion = EstadoConexion.SinSenal
            if (!esModoAutomatico && !cicloActivo) {
                esModoAutomatico = true
                bombaAguaActiva = false
                ventiladorActivo = false
            }
            mensajeNotificacion = "ALERTA: Sin conexión de red. Controles bloqueados (Modo Fail-Safe)."
            registrarEvento("Red OT", "Pérdida de conectividad detectada. Respaldo automático activo.")
        } else if (estadoConexion is EstadoConexion.SinSenal) {
            estadoConexion = EstadoConexion.EnLinea
            mensajeNotificacion = "Conexión restablecida. Registros offline sincronizados con la nube."
            registrarEvento("Red OT", "Reconexión exitosa y sincronización con Firebase.")
            (helper ?: ultimoHelper)?.enviarNotificacionExterna(
                titulo = "Conexión Restablecida",
                mensaje = "Enlace con el invernadero recuperado. Datos offline sincronizados.",
                id = 2002
            )
        }
    }

    // --- 5. TELEMETRÍA, LEDS Y ACTUADORES ---

    fun actualizarTelemetria(nuevaTemp: Float, nuevaHumedad: Float) {
        if (nuevaTemp.isNaN() || nuevaHumedad.isNaN() ||
            nuevaTemp !in -10f..60f || nuevaHumedad !in 0f..100f
        ) {
            registrarEvento("Filtro ISO 27400", "Trama anómala descartada (T=$nuevaTemp, H=$nuevaHumedad).")
            return
        }
        temperaturaCelsius = nuevaTemp
        humedadRelativa = nuevaHumedad
        rootRef.child("telemetria").setValue(
            mapOf("temperatura" to nuevaTemp, "humedad" to nuevaHumedad)
        )
        if (esModoAutomatico) {
            evaluarReglasAutomaticasLocales()
            sincronizarActuadoresNube()
        }
    }

    private fun evaluarReglasAutomaticasLocales() {
        ventiladorActivo = temperaturaCelsius > 28.0f
        bombaAguaActiva = humedadRelativa < 40.0f
    }

    private fun sincronizarActuadoresNube() {
        rootRef.child("actuadores").setValue(
            mapOf(
                "modoAutomatico" to esModoAutomatico,
                "bomba" to bombaAguaActiva,
                "ventilador" to ventiladorActivo,
                "finCicloMillis" to finCicloManualMillis
            )
        )
    }

    fun obtenerEstadoLedTermico(): String = when {
        temperaturaCelsius < 15.0f -> "FRIO"
        temperaturaCelsius <= 28.0f -> "NORMAL"
        else -> "CALOR"
    }

    fun cambiarModoOperacion(automatico: Boolean, helper: SistemaHelper? = null) {
        if (estadoConexion !is EstadoConexion.EnLinea) return
        val helperActivo = helper ?: ultimoHelper
        esModoAutomatico = automatico
        if (automatico) {
            detenerTemporizadorManual(helperActivo)
            evaluarReglasAutomaticasLocales()
            mensajeNotificacion = "Sistema conmutado a MODO AUTOMÁTICO."
            helperActivo?.enviarNotificacionExterna(
                "Modo Automático Activado",
                "El invernadero gestiona riego y ventilación por umbrales."
            )
        } else {
            mensajeNotificacion = "Sistema en MODO MANUAL (Control del Operador)."
        }
        sincronizarActuadoresNube()
        registrarEvento(usuarioActual?.correo ?: "Operador", "Cambio a modo ${if (automatico) "Automático" else "Manual"}.")
        reiniciarTemporizadorInactividad(helperActivo)
    }

    fun actualizarDuracionManual(minutos: Int) {
        duracionManualMinutos = minutos.coerceIn(1, 120)
        reiniciarTemporizadorInactividad()
    }

    fun alternarBombaAgua(encender: Boolean, helper: SistemaHelper? = null) {
        if (esModoAutomatico || estadoConexion !is EstadoConexion.EnLinea) return
        val helperActivo = helper ?: ultimoHelper
        bombaAguaActiva = encender
        gestionarRelojActuadores(helperActivo)
        sincronizarActuadoresNube()
        registrarEvento(
            usuarioActual?.correo ?: "Operador",
            "Bomba de agua ${if (encender) "ENCENDIDA ($duracionManualMinutos min)" else "APAGADA"}."
        )
        reiniciarTemporizadorInactividad(helperActivo)
    }

    fun alternarVentilador(encender: Boolean, helper: SistemaHelper? = null) {
        if (esModoAutomatico || estadoConexion !is EstadoConexion.EnLinea) return
        val helperActivo = helper ?: ultimoHelper
        ventiladorActivo = encender
        gestionarRelojActuadores(helperActivo)
        sincronizarActuadoresNube()
        registrarEvento(
            usuarioActual?.correo ?: "Operador",
            "Ventilador ${if (encender) "ENCENDIDO ($duracionManualMinutos min)" else "APAGADO"}."
        )
        reiniciarTemporizadorInactividad(helperActivo)
    }

    private fun gestionarRelojActuadores(helper: SistemaHelper?) {
        if (!bombaAguaActiva && !ventiladorActivo) {
            detenerTemporizadorManual(helper)
            return
        }
        if (finCicloManualMillis > System.currentTimeMillis()) return

        finCicloManualMillis = System.currentTimeMillis() + (duracionManualMinutos * 60_000L)
        helper?.marcarCerrarAuthAlFinalizar(false)
        helper?.programarAlarmaFinCiclo(finCicloManualMillis, duracionManualMinutos)
        iniciarContadorVisual()
    }

    private fun iniciarContadorVisual() {
        if (temporizadorActuadorJob?.isActive == true) return
        temporizadorActuadorJob = viewModelScope.launch {
            while (finCicloManualMillis > System.currentTimeMillis()) {
                val restante = ((finCicloManualMillis - System.currentTimeMillis()) / 1000L).toInt()
                segundosRestantesManual = restante.coerceAtLeast(0)
                delay(1000)
            }
            segundosRestantesManual = 0
            if (finCicloManualMillis != 0L) {
                finCicloManualMillis = 0L
                mensajeNotificacion = "Tiempo manual finalizado. Retorno automático a Fail-Safe."
            }
        }
    }

    private fun detenerTemporizadorManual(helper: SistemaHelper? = null) {
        temporizadorActuadorJob?.cancel()
        finCicloManualMillis = 0L
        segundosRestantesManual = 0
        (helper ?: ultimoHelper)?.cancelarAlarmaFinCiclo()
    }

    // --- 6. GESTIÓN DE USUARIOS EN LA NUBE (ADMIN) ---

    fun autorizarUsuario(correo: String) {
        if (usuarioActual?.esAdmin != true) return
        rootRef.child("usuarios").child(correoAKey(correo)).child("estaAutorizado").setValue(true)
        mensajeNotificacion = "Usuario $correo autorizado en la nube."
        registrarEvento(usuarioActual?.correo ?: "Admin", "Autorizó acceso a $correo.")
        reiniciarTemporizadorInactividad()
    }

    fun revocarUsuario(correo: String) {
        if (usuarioActual?.esAdmin != true) return
        rootRef.child("usuarios").child(correoAKey(correo)).child("estaAutorizado").setValue(false)
        mensajeNotificacion = "Acceso revocado a $correo."
        registrarEvento(usuarioActual?.correo ?: "Admin", "Revocó acceso a $correo.")
        reiniciarTemporizadorInactividad()
    }

    private fun registrarEvento(actor: String, accion: String) {
        val horaActual = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date())
        val enLinea = estadoConexion is EstadoConexion.EnLinea
        val evento = EventoAuditoria(horaActual, actor, accion, sincronizado = enLinea)
        historialEventos.add(0, evento)
        if (auth.currentUser != null) {
            rootRef.child("auditoria").push().setValue(evento.copy(sincronizado = true))
        }
    }
}