package br.com.praktor.gravadorclub

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.MediaRecorder
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.os.PowerManager
import android.os.SystemClock
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

class GravadorService : Service() {
    companion object {
        const val ACAO_INICIAR = "br.com.praktor.gravadorclub.INICIAR"
        const val ACAO_PARAR = "br.com.praktor.gravadorclub.PARAR"
        const val PARTE_MS = 60_000L
        const val BATIDA_MS = 60_000L
        const val RECONFERIR_MS = 300_000L
        const val CANAL = "gravacao"
        const val ID_NOTIF = 7
        const val MIC_RETRY_MS = 10_000L
    }

    private enum class Resultado { OK, TENTAR_DEPOIS, RECUSADA, CODIGO_RECUSADO }

    private lateinit var estado: Estado
    private lateinit var th: HandlerThread
    private lateinit var h: Handler
    private var wake: PowerManager.WakeLock? = null
    private var rec: MediaRecorder? = null
    private var arqAtual: File? = null
    private var inicioAtualSeg = 0L
    private var inicioAtualReal = 0L
    private var emForeground = false
    @Volatile private var vivo = true
    private var sender: Thread? = null

    override fun onBind(i: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        estado = Estado(this)
        th = HandlerThread("gravador").also { it.start() }
        h = Handler(th.looper)
        // sobras de uma gravação interrompida: m4a sem final não toca, joga fora
        estado.pastaGravando().listFiles()?.forEach { it.delete() }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val acao = intent?.action
        if (acao == ACAO_PARAR) {
            if (estado.gravando) {
                estado.gravando = false
                h.post { pararGravacao(); atualizarNotificacao() }
            } else {
                h.post { encerrar() }
            }
            return START_STICKY
        }
        if (acao == ACAO_INICIAR) {
            estado.gravando = true
            estado.desde = System.currentTimeMillis()
            estado.erro = ""
        } else if (!estado.gravando && estado.naFila().isEmpty()) {
            // reinício automático sem nada para fazer
            stopSelf()
            return START_NOT_STICKY
        }
        entrarEmPrimeiroPlano()
        pegarWakeLock()
        iniciarEnvio()
        if (estado.gravando && !estado.pausado) h.post { if (rec == null) comecarParte() }
        return START_STICKY
    }

    // ───────── primeiro plano ─────────
    private fun entrarEmPrimeiroPlano() {
        val nm = getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= 26) nm.createNotificationChannel(NotificationChannel(CANAL, "Gravação", NotificationManager.IMPORTANCE_LOW))
        val n = notificacao()
        try {
            if (Build.VERSION.SDK_INT >= 29) startForeground(ID_NOTIF, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE) else startForeground(ID_NOTIF, n)
            emForeground = true
        } catch (e: Exception) {
            estado.erro = "Android não deixou gravar em segundo plano: abra o app e toque em Começar"
            estado.gravando = false
            stopSelf()
        }
    }

    @Suppress("DEPRECATION")
    private fun notificacao(): Notification {
        val pi = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val parar = PendingIntent.getService(this, 1, Intent(this, GravadorService::class.java).setAction(ACAO_PARAR), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val titulo = if (estado.gravando) (if (estado.pausado) "Gravador Club pausado pelo Club" else "Gravador Club gravando") else "Gravador Club enviando a fila"
        return Notification.Builder(this, CANAL)
            .setContentTitle(titulo)
            .setContentText("Pode apagar a tela.")
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setOngoing(true)
            .setContentIntent(pi)
            .addAction(android.R.drawable.ic_media_pause, "Parar", parar)
            .build()
    }

    private fun atualizarNotificacao() {
        if (emForeground) getSystemService(NotificationManager::class.java).notify(ID_NOTIF, notificacao())
    }

    private fun pegarWakeLock() {
        if (wake?.isHeld == true) return
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        wake = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "gravadorclub:gravando").also { it.setReferenceCounted(false); it.acquire() }
    }

    // ───────── gravação (sempre na thread h) ─────────
    private fun comecarParte() {
        if (!estado.gravando || estado.pausado || rec != null) return
        val agoraSeg = System.currentTimeMillis() / 1000
        val arq = File(estado.pastaGravando(), "$agoraSeg.m4a.tmp")
        @Suppress("DEPRECATION")
        val r = if (Build.VERSION.SDK_INT >= 31) MediaRecorder(this) else MediaRecorder()
        try {
            r.setAudioSource(MediaRecorder.AudioSource.MIC)
            r.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
            r.setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
            r.setAudioSamplingRate(16000)
            r.setAudioChannels(1)
            r.setAudioEncodingBitRate(32000)
            r.setOutputFile(arq.absolutePath)
            r.setOnErrorListener { _, _, _ -> h.post { fecharParte(true); tentarDeNovo() } }
            r.prepare()
            r.start()
        } catch (e: Exception) {
            try { r.release() } catch (_: Exception) {}
            arq.delete()
            estado.erro = "Microfone indisponível, tentando de novo em 10 s"
            tentarDeNovo()
            return
        }
        rec = r
        arqAtual = arq
        inicioAtualSeg = agoraSeg
        inicioAtualReal = SystemClock.elapsedRealtime()
        h.postDelayed({ girar() }, PARTE_MS)
    }

    private fun tentarDeNovo() {
        h.postDelayed({ if (estado.gravando && !estado.pausado && rec == null) comecarParte() }, MIC_RETRY_MS)
    }

    /** Fecha a parte atual e a move para a fila. Retorna se virou arquivo. */
    private fun fecharParte(descartar: Boolean = false): Boolean {
        val r = rec ?: return false
        val arq = arqAtual
        rec = null
        arqAtual = null
        h.removeCallbacksAndMessages(null)
        var ok = true
        try { r.stop() } catch (e: RuntimeException) { ok = false }
        try { r.release() } catch (_: Exception) {}
        if (arq == null) return false
        val dur = ((SystemClock.elapsedRealtime() - inicioAtualReal) / 1000).toInt()
        if (!ok || descartar || dur < 1 || arq.length() < 200) { arq.delete(); return false }
        val destino = File(estado.pastaFila(), "${inicioAtualSeg}_${dur.coerceIn(1, 300)}.m4a")
        if (!arq.renameTo(destino)) { arq.delete(); return false }
        return true
    }

    private fun girar() {
        if (rec == null) return
        fecharParte()
        comecarParte()
    }

    private fun pararGravacao() {
        fecharParte()
        if (estado.naFila().isEmpty()) encerrar()
    }

    private fun encerrar() {
        estado.gravando = false
        fecharParte()
        vivo = false
        sender?.interrupt()
        wake?.takeIf { it.isHeld }?.release()
        if (emForeground) stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    // ───────── envio (thread própria) ─────────
    private fun iniciarEnvio() {
        if (sender?.isAlive == true) return
        vivo = true
        sender = Thread({ lacoDeEnvio() }, "envio").also { it.start() }
    }

    private fun lacoDeEnvio() {
        var espera = 5_000L
        var ultimaBatida = -BATIDA_MS
        var ultimaReconferida = 0L
        while (vivo) {
            try {
                if (estado.codigo.isBlank()) { Thread.sleep(3_000); continue }
                if (estado.recusado) {
                    // um 401 não trava para sempre: a cada 5 min confere o código de novo e, se voltou a valer, segue a fila
                    val t = SystemClock.elapsedRealtime()
                    if (ultimaReconferida == 0L || t - ultimaReconferida >= RECONFERIR_MS) { ultimaReconferida = t; baterPonto() }
                    if (estado.recusado) { Thread.sleep(3_000); continue }
                }
                val agora = SystemClock.elapsedRealtime()
                if (agora - ultimaBatida >= BATIDA_MS) { ultimaBatida = agora; baterPonto() }
                val prox = estado.naFila().firstOrNull()
                if (prox == null) {
                    if (!estado.gravando) h.post { if (!estado.gravando && estado.naFila().isEmpty()) encerrar() }
                    Thread.sleep(2_000)
                    continue
                }
                when (enviar(prox)) {
                    Resultado.OK -> espera = 5_000L
                    Resultado.TENTAR_DEPOIS -> { Thread.sleep(espera); espera = (espera * 2).coerceAtMost(60_000L) }
                    Resultado.RECUSADA -> {}
                    Resultado.CODIGO_RECUSADO -> { estado.recusado = true; estado.erro = "código recusado" }
                }
            } catch (e: InterruptedException) {
                return
            } catch (e: Exception) {
                estado.erro = (e.message ?: e.javaClass.simpleName).take(80)
                try { Thread.sleep(5_000) } catch (_: InterruptedException) { return }
            }
        }
    }

    private fun enviar(f: File): Resultado {
        val nome = f.nameWithoutExtension.split('_')
        val ini = nome.getOrNull(0)?.toLongOrNull()
        val dur = nome.getOrNull(1)?.toIntOrNull()
        if (ini == null || dur == null) { f.renameTo(File(f.path + ".recusada")); return Resultado.RECUSADA }
        val boundary = "----GravadorClub" + System.currentTimeMillis()
        val corpo = Multipart.montar(boundary, Multipart.camposDaParte(ini, dur), "parte.m4a", "audio/mp4", f.readBytes())
        val c = abrir("/publico/gravador/parte")
        try {
            c.setRequestProperty("Content-Type", Multipart.tipo(boundary))
            c.setFixedLengthStreamingMode(corpo.size)
            c.outputStream.use { it.write(corpo) }
            val cod = c.responseCode
            when {
                cod in 200..299 -> {
                    lerResposta(c)?.let { aplicarPausa(it) }
                    f.delete()
                    estado.enviadas = estado.enviadas + 1
                    estado.ultimoEnvio = System.currentTimeMillis()
                    estado.erro = ""
                    return Resultado.OK
                }
                cod == 401 -> return Resultado.CODIGO_RECUSADO
                cod == 400 || cod == 413 || cod == 422 -> {
                    f.renameTo(File(f.path + ".recusada"))
                    estado.erro = "parte recusada pelo servidor ($cod)"
                    return Resultado.RECUSADA
                }
                else -> { estado.erro = "servidor respondeu $cod"; return Resultado.TENTAR_DEPOIS }
            }
        } catch (e: java.io.IOException) {
            estado.erro = "sem rede: " + (e.message ?: "falha").take(60)
            return Resultado.TENTAR_DEPOIS
        } finally {
            c.disconnect()
        }
    }

    private fun baterPonto() {
        val c = abrir("/publico/gravador/batida")
        try {
            val corpo = "{\"aparelho\":\"$APARELHO\"}".toByteArray()
            c.setRequestProperty("Content-Type", "application/json")
            c.setFixedLengthStreamingMode(corpo.size)
            c.outputStream.use { it.write(corpo) }
            val cod = c.responseCode
            if (cod in 200..299) {
                if (estado.recusado) { estado.recusado = false; estado.erro = "" }
                lerResposta(c)?.let { aplicarPausa(it) }
            }
            else if (cod == 401) { estado.recusado = true; estado.erro = "código recusado" }
        } catch (_: java.io.IOException) {
        } finally {
            c.disconnect()
        }
    }

    private fun lerResposta(c: HttpURLConnection): String? =
        try { c.inputStream.bufferedReader().use { it.readText() } } catch (_: Exception) { null }

    /** O servidor diz "pausado": true/false. Para ou volta a gravar. */
    private fun aplicarPausa(json: String) {
        val m = Regex("\"pausado\"\\s*:\\s*(true|false)").find(json) ?: return
        val p = m.groupValues[1] == "true"
        if (p == estado.pausado) return
        estado.pausado = p
        h.post {
            if (p) fecharParte() else if (estado.gravando) comecarParte()
            atualizarNotificacao()
        }
    }

    private fun abrir(caminho: String): HttpURLConnection {
        val c = URL(estado.servidor + caminho).openConnection() as HttpURLConnection
        c.requestMethod = "POST"
        c.doOutput = true
        c.connectTimeout = 20_000
        c.readTimeout = 60_000
        c.setRequestProperty("Authorization", "Bearer " + estado.codigo)
        return c
    }

    override fun onDestroy() {
        vivo = false
        sender?.interrupt()
        try { fecharParte() } catch (_: Exception) {}
        wake?.takeIf { it.isHeld }?.release()
        th.quitSafely()
        super.onDestroy()
    }
}
