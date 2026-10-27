package br.com.praktor.gravadorclub

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.provider.Settings
import android.text.InputType
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainActivity : Activity() {
    private lateinit var estado: Estado
    private lateinit var codigo: EditText
    private lateinit var servidor: EditText
    private lateinit var botao: Button
    private lateinit var linha: TextView
    private val ui = Handler(Looper.getMainLooper())
    private val tic = object : Runnable {
        override fun run() { atualizar(); ui.postDelayed(this, 1000) }
    }

    private fun txt(t: String, sp: Float, bold: Boolean = false) = TextView(this).apply {
        text = t
        textSize = sp
        setTextColor(Color.BLACK)
        if (bold) setTypeface(typeface, android.graphics.Typeface.BOLD)
        setPadding(0, 16, 0, 8)
    }

    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        estado = Estado(this)
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(40, 48, 40, 48) }
        col.addView(txt("Gravador Club", 28f, true))
        col.addView(txt("Código do gravador", 20f))
        codigo = EditText(this).apply {
            setText(estado.codigo)
            hint = "cnxg_..."
            textSize = 20f
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
            setSingleLine()
        }
        col.addView(codigo)
        col.addView(txt("Servidor", 20f))
        servidor = EditText(this).apply {
            setText(estado.servidor)
            textSize = 18f
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
            setSingleLine()
        }
        col.addView(servidor)
        botao = Button(this).apply { textSize = 24f; setOnClickListener { alternar() } }
        col.addView(botao, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 220).apply { topMargin = 40; bottomMargin = 24 })
        linha = txt("", 18f)
        col.addView(linha)
        col.addView(txt("Pode apagar a tela. A notificação fica enquanto grava.", 18f, true))
        val bat = Button(this).apply { text = "Liberar bateria"; textSize = 20f; setOnClickListener { liberarBateria() } }
        col.addView(bat, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 160).apply { topMargin = 24 })
        setContentView(ScrollView(this).apply { addView(col) })
    }

    override fun onResume() { super.onResume(); ui.post(tic) }
    override fun onPause() { ui.removeCallbacks(tic); salvarCampos(); super.onPause() }

    private fun salvarCampos() {
        val c = codigo.text.toString().trim()
        if (c != estado.codigo) estado.codigo = c
        val s = servidor.text.toString().trim().trimEnd('/')
        estado.servidor = if (s.isBlank()) SERVIDOR_PADRAO else s
    }

    private fun alternar() {
        if (estado.gravando) {
            startService(Intent(this, GravadorService::class.java).setAction(GravadorService.ACAO_PARAR))
            return
        }
        salvarCampos()
        if (estado.codigo.isBlank()) { estado.erro = "falta o código do gravador"; return }
        val faltam = mutableListOf<String>()
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) faltam += Manifest.permission.RECORD_AUDIO
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) faltam += Manifest.permission.POST_NOTIFICATIONS
        if (faltam.isNotEmpty()) { requestPermissions(faltam.toTypedArray(), 1); return }
        comecar()
    }

    private fun comecar() {
        estado.erro = ""
        startForegroundService(Intent(this, GravadorService::class.java).setAction(GravadorService.ACAO_INICIAR))
    }

    override fun onRequestPermissionsResult(rc: Int, p: Array<out String>, r: IntArray) {
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) comecar()
        else estado.erro = "sem permissão do microfone"
    }

    private fun liberarBateria() {
        val pm = getSystemService(PowerManager::class.java)
        try {
            if (pm.isIgnoringBatteryOptimizations(packageName)) {
                startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
            } else {
                startActivity(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:$packageName")))
            }
        } catch (e: Exception) {
            estado.erro = "não consegui abrir a tela de bateria"
        }
    }

    private fun hora(ms: Long) = SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(ms))

    private fun atualizar() {
        val g = estado.gravando
        botao.text = if (g) "Parar" else "Começar a gravar"
        val pm = getSystemService(PowerManager::class.java)
        val sb = StringBuilder()
        sb.append(
            when {
                !g -> "Parado"
                estado.pausado -> "Pausado pelo Club"
                else -> "Gravando desde ${hora(estado.desde)}"
            }
        )
        sb.append("\nPartes enviadas: ${estado.enviadas}")
        sb.append("\nNa fila: ${estado.naFila().size}")
        val u = estado.ultimoEnvio
        val atras = (System.currentTimeMillis() - u) / 1000
        sb.append("\nÚltimo envio: " + if (u == 0L) "ainda não" else "há " + (if (atras < 90) "$atras s" else "${atras / 60} min"))
        if (estado.recusado) sb.append("\nCódigo recusado")
        if (estado.erro.isNotEmpty()) sb.append("\nÚltimo erro: ${estado.erro}")
        if (!pm.isIgnoringBatteryOptimizations(packageName)) sb.append("\nBateria: toque em Liberar bateria")
        linha.text = sb.toString()
    }
}
