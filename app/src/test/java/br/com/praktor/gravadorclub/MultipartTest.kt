package br.com.praktor.gravadorclub

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MultipartTest {
    private val audio = byteArrayOf(0, 1, 2, 3, 127, -1, -2)

    private fun corpo(): String {
        val b = Multipart.montar("XYZ", Multipart.camposDaParte(1793139300L, 60), "parte.m4a", "audio/mp4", audio)
        return String(b, Charsets.ISO_8859_1)
    }

    @Test fun inicioEmEhIsoUtcSemMilissegundos() {
        assertEquals("2026-10-27T22:15:00Z", Multipart.iso(1793139300L))
        assertEquals("1970-01-01T00:00:00Z", Multipart.iso(0))
        assertTrue(Regex("""^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}Z$""").matches(Multipart.iso(System.currentTimeMillis() / 1000)))
    }

    @Test fun camposDeTextoVemAntesDoArquivoNaOrdemCerta() {
        val s = corpo()
        val nomes = Regex("name=\"([a-z_]+)\"").findAll(s).map { it.groupValues[1] }.toList()
        assertEquals(listOf("inicio_em", "dur_s", "mime", "aparelho", "arquivo"), nomes)
        assertTrue(s.indexOf("name=\"aparelho\"") < s.indexOf("name=\"arquivo\""))
    }

    @Test fun valoresDosCampos() {
        val s = corpo()
        assertTrue(s.contains("name=\"inicio_em\"\r\n\r\n2026-10-27T22:15:00Z\r\n"))
        assertTrue(s.contains("name=\"dur_s\"\r\n\r\n60\r\n"))
        assertTrue(s.contains("name=\"mime\"\r\n\r\naudio/mp4\r\n"))
        assertTrue(s.contains("name=\"aparelho\"\r\n\r\n$APARELHO\r\n"))
        assertTrue(s.contains("name=\"arquivo\"; filename=\"parte.m4a\"\r\nContent-Type: audio/mp4\r\n\r\n"))
    }

    @Test fun fechamentoEBytesDoArquivoIntactos() {
        val b = Multipart.montar("XYZ", Multipart.camposDaParte(0, 5), "parte.m4a", "audio/mp4", audio)
        val s = String(b, Charsets.ISO_8859_1)
        assertTrue(s.startsWith("--XYZ\r\n"))
        assertTrue(s.endsWith("\r\n--XYZ--\r\n"))
        val i = s.indexOf("\r\n\r\n", s.indexOf("name=\"arquivo\"")) + 4
        assertEquals(audio.toList(), b.slice(i until i + audio.size))
    }

    @Test fun duracaoFicaEntre1e300() {
        assertEquals("1", Multipart.camposDaParte(0, 0)[1].second)
        assertEquals("300", Multipart.camposDaParte(0, 999)[1].second)
    }
}
