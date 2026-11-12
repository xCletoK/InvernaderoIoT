package cl.inacap.invernaderoiot

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.database.FirebaseDatabase
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class CicloManualReceiver : BroadcastReceiver() {

    companion object {
        @Synchronized
        fun finalizarCicloYNotificar(
            context: Context,
            pendingResult: PendingResult? = null
        ) {
            val appCtx = context.applicationContext
            val helper = SistemaHelper(appCtx)

            // Evita duplicados si el Service y el Receiver coinciden en el mismo milisegundo
            if (helper.obtenerFinCicloMillis() == 0L) {
                pendingResult?.finish()
                return
            }

            val minutos = helper.obtenerMinutosCicloActivo()
            helper.cancelarAlarmaFinCiclo()

            // 1. DISPARO INMEDIATO de la notificación externa (antes de esperar respuesta de red)
            helper.enviarNotificacionExterna(
                titulo = "Ciclo Manual Finalizado",
                mensaje = "Se cumplieron los $minutos min programados. Actuadores apagados y retorno a Modo Automático.",
                id = 4001
            )

            // 2. Apagar actuadores y retornar a Modo Automático en Firebase
            val auth = FirebaseAuth.getInstance()
            val rootRef = FirebaseDatabase.getInstance().getReference("invernadero")

            rootRef.child("actuadores").setValue(
                mapOf(
                    "modoAutomatico" to true,
                    "bomba" to false,
                    "ventilador" to false,
                    "finCicloMillis" to 0L
                )
            ).addOnCompleteListener {
                if (auth.currentUser != null) {
                    val hora = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date())
                    rootRef.child("auditoria").push().setValue(
                        mapOf(
                            "hora" to hora,
                            "usuario" to "Temporizador OT",
                            "accion" to "Fin de ciclo manual ($minutos min) -> Retorno a Modo Automático.",
                            "sincronizado" to true
                        )
                    ).addOnCompleteListener {
                        if (helper.debeCerrarAuthAlFinalizar()) {
                            helper.marcarCerrarAuthAlFinalizar(false)
                            auth.signOut()
                        }
                        pendingResult?.finish()
                    }
                } else {
                    pendingResult?.finish()
                }
            }
        }
    }

    override fun onReceive(context: Context, intent: Intent?) {
        val pendingResult = goAsync()
        finalizarCicloYNotificar(context, pendingResult)
    }
}