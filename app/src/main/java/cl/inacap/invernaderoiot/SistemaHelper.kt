package cl.inacap.invernaderoiot

import android.Manifest
import android.app.AlarmManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import java.security.MessageDigest

class SistemaHelper(private val context: Context) {

    companion object {
        const val CHANNEL_ID = "canal_invernadero_ot_v2"
        private const val PREFS_NAME = "sesion_segura_iot"

        fun hashSHA256(texto: String): String {
            val bytes = MessageDigest.getInstance("SHA-256").digest(texto.toByteArray())
            return bytes.joinToString("") { "%02x".format(it) }
        }
    }

    private val connectivityManager =
        context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
    private var networkCallback: ConnectivityManager.NetworkCallback? = null

    init {
        crearCanalNotificaciones()
    }

    private fun crearCanalNotificaciones() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val canal = NotificationChannel(
                CHANNEL_ID,
                "Alertas Críticas Invernadero OT",
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "Notificaciones de pérdida de señal, sesión y ciclos OT"
                enableVibration(true)
            }
            val manager = context.getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(canal)
        }
    }

    fun enviarNotificacionExterna(titulo: String, mensaje: String, id: Int = 1001) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val permiso = ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.POST_NOTIFICATIONS
            )
            if (permiso != PackageManager.PERMISSION_GRANTED) return
        }

        val abrirAppIntent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val contentPendingIntent = PendingIntent.getActivity(
            context,
            id,
            abrirAppIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notificacion = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle(titulo)
            .setContentText(mensaje)
            .setStyle(NotificationCompat.BigTextStyle().bigText(mensaje))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setDefaults(NotificationCompat.DEFAULT_ALL)
            .setContentIntent(contentPendingIntent)
            .setAutoCancel(true)
            .build()

        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.notify(id, notificacion)
    }

    // --- CICLO MANUAL PERSISTENTE (FOREGROUND SERVICE + ALARMA SEGURA) ---

    fun programarAlarmaFinCiclo(finMillis: Long, minutos: Int) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit()
            .putLong("fin_ciclo_millis", finMillis)
            .putInt("minutos_ciclo", minutos)
            .apply()

        val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val receiverIntent = Intent(context, CicloManualReceiver::class.java)
        val pendingIntent = PendingIntent.getBroadcast(
            context,
            4001,
            receiverIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val showIntent = PendingIntent.getActivity(
            context,
            4002,
            Intent(context, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                if (alarmManager.canScheduleExactAlarms()) {
                    alarmManager.setAlarmClock(
                        AlarmManager.AlarmClockInfo(finMillis, showIntent),
                        pendingIntent
                    )
                } else {
                    alarmManager.setAndAllowWhileIdle(
                        AlarmManager.RTC_WAKEUP,
                        finMillis,
                        pendingIntent
                    )
                }
            } else {
                alarmManager.setAlarmClock(
                    AlarmManager.AlarmClockInfo(finMillis, showIntent),
                    pendingIntent
                )
            }
        } catch (_: SecurityException) {
            alarmManager.setAndAllowWhileIdle(
                AlarmManager.RTC_WAKEUP,
                finMillis,
                pendingIntent
            )
        }

        val serviceIntent = Intent(context, CicloManualService::class.java).apply {
            action = CicloManualService.ACTION_INICIAR
        }
        try {
            ContextCompat.startForegroundService(context, serviceIntent)
        } catch (_: Exception) {
        }
    }

    fun cancelarAlarmaFinCiclo() {
        limpiarCicloManual()

        val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val intent = Intent(context, CicloManualReceiver::class.java)
        val pendingIntent = PendingIntent.getBroadcast(
            context,
            4001,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        try {
            alarmManager.cancel(pendingIntent)
        } catch (_: Exception) {
        }

        val stopServiceIntent = Intent(context, CicloManualService::class.java).apply {
            action = CicloManualService.ACTION_DETENER
        }
        try {
            context.startService(stopServiceIntent)
        } catch (_: Exception) {
        }
    }

    fun obtenerFinCicloMillis(): Long =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getLong("fin_ciclo_millis", 0L)

    fun obtenerMinutosCicloActivo(): Int =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getInt("minutos_ciclo", 1)

    fun limpiarCicloManual() {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .remove("fin_ciclo_millis")
            .remove("minutos_ciclo")
            .apply()
    }

    fun marcarCerrarAuthAlFinalizar(pendiente: Boolean) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putBoolean("cerrar_auth_pendiente", pendiente)
            .apply()
    }

    fun debeCerrarAuthAlFinalizar(): Boolean =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getBoolean("cerrar_auth_pendiente", false)

    // --- MONITOREO DE CONECTIVIDAD ---

    fun iniciarMonitoreoRed(onCambioConexion: (Boolean) -> Unit) {
        onCambioConexion(tieneInternetActivo())

        val request = NetworkRequest.Builder()
            .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .build()

        networkCallback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                onCambioConexion(true)
            }

            override fun onLost(network: Network) {
                val sigueConectado = tieneInternetActivo()
                onCambioConexion(sigueConectado)
                if (!sigueConectado) {
                    enviarNotificacionExterna(
                        titulo = "Señal Perdida - Invernadero OT",
                        mensaje = "Sin enlace de red. Controles remotos bloqueados; sistema operando en Modo Automático.",
                        id = 2001
                    )
                }
            }
        }
        networkCallback?.let { connectivityManager.registerNetworkCallback(request, it) }
    }

    fun detenerMonitoreoRed() {
        networkCallback?.let {
            try {
                connectivityManager.unregisterNetworkCallback(it)
            } catch (_: Exception) {
            }
        }
    }

    fun tieneInternetActivo(): Boolean {
        val red = connectivityManager.activeNetwork ?: return false
        val capacidades = connectivityManager.getNetworkCapabilities(red) ?: return false
        return capacidades.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
    }

    // --- PERSISTENCIA DE SESIÓN ---

    fun guardarSesion(correo: String, esAdmin: Boolean) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val token = hashSHA256("$correo:${System.currentTimeMillis()}")
        prefs.edit()
            .putString("correo", correo)
            .putBoolean("esAdmin", esAdmin)
            .putString("token_sesion", token)
            .apply()
    }

    fun limpiarSesion() {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit()
            .remove("correo")
            .remove("esAdmin")
            .remove("token_sesion")
            .apply()
    }
}