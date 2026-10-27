package br.com.praktor.gravadorclub

import java.io.ByteArrayOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/** Valor que o app manda em `aparelho`. O servidor de produção hoje aceita 'navegador' ou 'raspberry'; troque para "android" quando ele passar a aceitar. */
const val APARELHO = "raspberry"

/** Montador de multipart/form-data escrito à mão. Os campos de texto SEMPRE vêm antes do arquivo (o servidor só lê o que vem antes). */
object Multipart {
    const val CRLF = "\r\n"

    fun tipo(boundary: String) = "multipart/form-data; boundary=$boundary"

    /** `inicio_em` em ISO 8601 UTC, sem milissegundos: 2026-10-27T22:15:00Z */
    fun iso(epochSeg: Long): String {
        val f = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US)
        f.timeZone = TimeZone.getTimeZone("UTC")
        return f.format(Date(epochSeg * 1000))
    }

    /** Campos de texto na ordem dada, depois o arquivo (campo `arquivo`). */
    fun montar(boundary: String, campos: List<Pair<String, String>>, nomeArquivo: String, mime: String, bytes: ByteArray): ByteArray {
        val o = ByteArrayOutputStream(bytes.size + 1024)
        for ((nome, valor) in campos) {
            o.write("--$boundary$CRLF".toByteArray())
            o.write("Content-Disposition: form-data; name=\"$nome\"$CRLF$CRLF".toByteArray())
            o.write(valor.toByteArray(Charsets.UTF_8))
            o.write(CRLF.toByteArray())
        }
        o.write("--$boundary$CRLF".toByteArray())
        o.write("Content-Disposition: form-data; name=\"arquivo\"; filename=\"$nomeArquivo\"$CRLF".toByteArray())
        o.write("Content-Type: $mime$CRLF$CRLF".toByteArray())
        o.write(bytes)
        o.write(CRLF.toByteArray())
        o.write("--$boundary--$CRLF".toByteArray())
        return o.toByteArray()
    }

    /** Os campos de uma parte, na ordem exigida pelo servidor. */
    fun camposDaParte(inicioEpochSeg: Long, durSeg: Int): List<Pair<String, String>> = listOf(
        "inicio_em" to iso(inicioEpochSeg),
        "dur_s" to durSeg.coerceIn(1, 300).toString(),
        "mime" to "audio/mp4",
        "aparelho" to APARELHO,
    )
}
