package br.com.praktor.gravadorclub

import android.content.Context
import android.content.SharedPreferences
import java.io.File

const val SERVIDOR_PADRAO = "https://api.clubnexo.praktor.com.br"

/** Tudo que a tela e o serviço dividem, em SharedPreferences (o serviço pode morrer e voltar). */
class Estado(ctx: Context) {
    private val p: SharedPreferences = ctx.applicationContext.getSharedPreferences("gravador", Context.MODE_PRIVATE)
    private val dir = ctx.applicationContext.filesDir

    var codigo: String
        get() = p.getString("codigo", "") ?: ""
        set(v) { p.edit().putString("codigo", v.trim()).putBoolean("recusado", false).apply() }
    var servidor: String
        get() = (p.getString("servidor", SERVIDOR_PADRAO) ?: SERVIDOR_PADRAO).trim().trimEnd('/')
        set(v) { p.edit().putString("servidor", v.trim().trimEnd('/')).apply() }
    var gravando: Boolean
        get() = p.getBoolean("gravando", false)
        set(v) { p.edit().putBoolean("gravando", v).apply() }
    var desde: Long
        get() = p.getLong("desde", 0)
        set(v) { p.edit().putLong("desde", v).apply() }
    var enviadas: Int
        get() = p.getInt("enviadas", 0)
        set(v) { p.edit().putInt("enviadas", v).apply() }
    var ultimoEnvio: Long
        get() = p.getLong("ultimoEnvio", 0)
        set(v) { p.edit().putLong("ultimoEnvio", v).apply() }
    var erro: String
        get() = p.getString("erro", "") ?: ""
        set(v) { p.edit().putString("erro", v).apply() }
    var recusado: Boolean
        get() = p.getBoolean("recusado", false)
        set(v) { p.edit().putBoolean("recusado", v).apply() }
    var pausado: Boolean
        get() = p.getBoolean("pausado", false)
        set(v) { p.edit().putBoolean("pausado", v).apply() }

    fun pastaFila() = File(dir, "fila").also { it.mkdirs() }
    fun pastaGravando() = File(dir, "gravando").also { it.mkdirs() }
    fun naFila(): List<File> = (pastaFila().listFiles { f -> f.name.endsWith(".m4a") } ?: emptyArray()).sortedBy { it.name.substringBefore('_').toLongOrNull() ?: 0L }
}
