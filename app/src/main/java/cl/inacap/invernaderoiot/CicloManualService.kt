package cl.inacap.invernaderoiot

import android.app.Notification
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.Locale

class CicloManualService : Service() {

    companion object {
        const val ACTION_INICIAR = "cl.inacap.invernaderoiot.ACTION_INICIAR_CICLO"
        const val ACTION_DETENER = "cl.inacap.invernaderoiot.ACTION_DETENER_CICLO"
        const val NOTIF_EN_CURSO_ID = 4000
    }

    private val serviceScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var contadorJob: Job? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val helper = SistemaHelper(applicationContext)
        val finMillis = helper.obtenerFinCicloMillis()
        val segundosIniciales = ((finMillis - System.currentTimeMillis()) / 1000L).toInt().coerceAtLeast(1)

        // Android exige llamar a startForeground() siempre al iniciar con startForegroundService()
        promoverAPrimerPlano(construirNotificacionEnCurso(segundosIniciales))

        if (intent?.action == ACTION_DETENER || finMillis <= System.currentTimeMillis()) {
            contadorJob?.cancel()
            ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
            stopSelf()
            return START_NOT_STICKY
        }

        contadorJob?.cancel()
        contadorJob = serviceScope.launch {
            while (isActive) {
                val restanteMs = finMillis - System.currentTimeMillis()
                if (restanteMs <= 0L) break

                val segRestantes = (restanteMs / 1000L).toInt().coerceAtLeast(1)
                val manager = getSystemService(Context.NOTIFICATION_SERVICE) as android.app.NotificationManager
                manager.notify(NOTIF_EN_CURSO_ID, construirNotificacionEnCurso(segRestantes))
                delay(1000L)
            }

            if (isActive) {
                CicloManualReceiver.finalizarCicloYNotificar(applicationContext)
                ServiceCompat.stopForeground(this@CicloManualService, ServiceCompat.STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
        }

        return START_STICKY
    }

    private fun promoverAPrimerPlano(notificacion: Notification) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ServiceCompat.startForeground(
                this,
                NOTIF_EN_CURSO_ID,
                notificacion,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
            )
        } else {
            startForeground(NOTIF_EN_CURSO_ID, notificacion)
        }
    }

    private fun construirNotificacionEnCurso(segundosRestantes: Int): Notification {
        val min = segundosRestantes / 60
        val seg = segundosRestantes % 60
        val tiempoTexto = String.format(Locale.US, "%02d:%02d", min, seg)

        return NotificationCompat.Builder(this, SistemaHelper.CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_popup_sync)
            .setContentTitle("Invernadero OT · Ciclo Manual Activo")
            .setContentText("Actuador operando · Apagado automático en $tiempoTexto")
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    override fun onDestroy() {
        contadorJob?.cancel()
        serviceScope.cancel()
        super.onDestroy()
    }
}