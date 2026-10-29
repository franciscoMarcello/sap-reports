package br.andrew.sap_reports.render

import br.andrew.sap_reports.config.ReportProperties
import br.andrew.sap_reports.definition.*
import br.andrew.sap_reports.odbc.QueryResponse
import org.apache.pdfbox.Loader
import org.apache.pdfbox.pdmodel.graphics.image.PDImageXObject
import java.math.BigDecimal
import java.util.Base64
import javax.imageio.ImageIO
import kotlin.test.*

class GraficosTest {
    private val def = ReportDefinition(
        nome = "Vendas", papeis = listOf("admin"), consulta = Consulta("SELECT 1"),
        colunas = listOf(Coluna("FILIAL", "Filial"), Coluna("TOTAL", "Total", "moeda")),
        graficos = listOf(Grafico("vendas", titulo = "Vendas", categoria = "FILIAL", valor = "TOTAL")),
    )
    private val linhas = listOf(
        mapOf("FILIAL" to "Norte", "TOTAL" to "0.10"),
        mapOf("FILIAL" to "Sul", "TOTAL" to "1.20"),
        mapOf("FILIAL" to "Norte", "TOTAL" to "0.20"),
        mapOf("FILIAL" to "Norte", "TOTAL" to null),
    )

    private val burndown = def.copy(
        colunas = listOf(Coluna("DIA", "Data", "data"), Coluna("RESTANTE", "Trabalho restante", "numero"),
            Coluna("IDEAL", "Ideal", "numero")),
        graficos = listOf(Grafico("sprint", "burndown", "Burndown — exemplo", "DIA", "RESTANTE", valorIdeal = "IDEAL")),
    )
    private val sprint = listOf(
        mapOf("DIA" to "2026-10-29", "RESTANTE" to "32", "IDEAL" to "30"),
        mapOf("DIA" to "2026-10-27", "RESTANTE" to "40", "IDEAL" to "40"),
        mapOf("DIA" to "2026-11-04", "RESTANTE" to null, "IDEAL" to "0"),
        mapOf("DIA" to "2026-10-31", "RESTANTE" to "24", "IDEAL" to "20"),
        mapOf("DIA" to "2026-11-02", "RESTANTE" to null, "IDEAL" to "10"),
    )

    @Test
    fun `burndown ordena datas e preserva ideal futuro sem inventar trabalho realizado`() {
        val g = Graficos(burndown).apply { sprint.forEach(::somar) }
        assertEquals(listOf("2026-10-27", "2026-10-29", "2026-10-31"), g.dados(0).keys.toList())
        assertEquals(listOf("2026-10-27", "2026-10-29", "2026-10-31", "2026-11-02", "2026-11-04"), g.dadosIdeais(0).keys.toList())
        assertEquals(BigDecimal.ZERO, g.dadosIdeais(0)["2026-11-04"])
        assertTrue(GraficosRules.validar(burndown, "{{graficos.sprint}}").isEmpty())
        val png = Base64.getDecoder().decode(g.imagens().getValue("sprint").toString().substringAfter(','))
        val imagem = ImageIO.read(png.inputStream())
        assertEquals(800, imagem.width)
        // Artefato de demonstracao gerado com os mesmos dados ficticios do teste.
        val destino = java.nio.file.Path.of("build/reports/burndown-preview.png")
        java.nio.file.Files.createDirectories(destino.parent)
        java.nio.file.Files.write(destino, png)
        val html = HtmlRenderer().renderizar("""<img src="{{graficos.sprint}}" />""",
            burndown, QueryResponse(emptyList(), sprint, sprint.size, false, 0), emptyMap())
        Loader.loadPDF(PdfRenderer().renderizar(html)).use { pdf ->
            assertTrue(pdf.pages.any { page -> page.resources.xObjectNames.any { page.resources.getXObject(it) is PDImageXObject } })
        }
    }

    @Test
    fun `burndown no YAML valida a coluna ideal e exige referencia numerica`() {
        val yaml = """
            nome: Sprint
            papeis: [admin]
            consulta: {sql: 'SELECT 1'}
            colunas:
              - {campo: DIA, titulo: Data, tipo: data}
              - {campo: RESTANTE, titulo: Restante, tipo: numero}
              - {campo: IDEAL, titulo: Ideal, tipo: numero}
            graficos:
              - {id: sprint, tipo: burndown, categoria: DIA, valor: RESTANTE, valorIdeal: IDEAL}
        """.trimIndent()
        val validator = ReportUploadValidator(ReportProperties())
        val template = """<img src="{{graficos.sprint}}" />"""
        assertTrue(validator.validar(yaml, template).valido)
        assertFalse(validator.validar(yaml.replace(", valorIdeal: IDEAL", ""), template).valido)
        assertFalse(validator.validar(yaml.replace("valorIdeal: IDEAL", "valorIdeal: DIA"), template).valido)
        assertFalse(validator.validar(yaml.replace("tipo: burndown", "tipo: linhas"), template).valido)
    }

    @Test
    fun `burndown exige datas ideal numerico e valores nao negativos`() {
        for (alteracao in listOf(mapOf("DIA" to "2026-02-30"), mapOf("DIA" to null),
            mapOf("IDEAL" to null), mapOf("IDEAL" to -1), mapOf("RESTANTE" to -1), mapOf("RESTANTE" to "erro"))) {
            assertFailsWith<ValidacaoException> { Graficos.gerar(burndown, listOf(sprint[0] + alteracao)) }
        }
        val semIdeal = burndown.copy(graficos = listOf(burndown.graficos[0].copy(valorIdeal = null)))
        assertTrue(GraficosRules.validar(semIdeal, "").any { it.caminho.endsWith("valorIdeal") })
        val contagem = burndown.copy(graficos = listOf(burndown.graficos[0].copy(agregacao = "contagem")))
        assertTrue(GraficosRules.validar(contagem, "").any { it.caminho.endsWith("agregacao") })
        val dataTexto = burndown.copy(colunas = burndown.colunas.map { if (it.campo == "DIA") it.copy(tipo = "texto") else it })
        assertTrue(GraficosRules.validar(dataTexto, "").any { it.caminho.endsWith("categoria") })
        assertTrue(Graficos.gerar(burndown, emptyList()).isEmpty())
        for (restante in listOf(null, "0")) {
            assertTrue(Graficos.gerar(burndown, listOf(mapOf("DIA" to "2026-10-27", "RESTANTE" to restante, "IDEAL" to "0"))).containsKey("sprint"))
        }
    }

    @Test
    fun `agrega dados brutos com decimal exato antes de gerar imagem`() {
        val graficos = Graficos(def).apply { linhas.forEach(::somar) }
        assertEquals(listOf("Norte", "Sul"), graficos.dados(0).keys.toList())
        assertEquals(BigDecimal("0.30"), graficos.dados(0)["Norte"])
        for ((tipo, esperado) in mapOf("media" to "0.15", "minimo" to "0.10", "maximo" to "0.20", "contagem" to "2")) {
            val outro = Graficos(def.copy(graficos = listOf(def.graficos[0].copy(agregacao = tipo))))
            linhas.forEach(outro::somar)
            assertEquals(0, BigDecimal(esperado).compareTo(outro.dados(0).getValue("Norte")))
        }
    }

    @Test
    fun `barras linhas e pizza geram PNG e chegam como imagem ao PDF`() {
        for (tipo in GraficosRules.tipos - "burndown") {
            val d = def.copy(graficos = listOf(def.graficos[0].copy(tipo = tipo)))
            val uri = Graficos.gerar(d, linhas).getValue("vendas").toString()
            val img = ImageIO.read(Base64.getDecoder().decode(uri.substringAfter(',' )).inputStream())
            assertEquals(800, img.width)
            assertEquals(400, img.height)
            val html = HtmlRenderer().renderizar(
                """{{#if graficos.vendas}}<img src="{{graficos.vendas}}" alt="Vendas" />{{/if}}""",
                d, QueryResponse(emptyList(), linhas, linhas.size, false, 1), emptyMap(),
            )
            assertTrue("data:image/png;base64," in html)
            Loader.loadPDF(PdfRenderer().renderizar(html)).use { pdf ->
                assertTrue(pdf.pages.any { page -> page.resources.xObjectNames.any {
                    page.resources.getXObject(it) is PDImageXObject
                } }, "O grafico deve estar embutido no PDF: $tipo")
            }
        }
    }

    @Test
    fun `sem dados nao produz imagem e nao aceita numeros invalidos ou pizza negativa`() {
        assertTrue(Graficos.gerar(def, emptyList()).isEmpty())
        assertFailsWith<ValidacaoException> { Graficos.gerar(def, listOf(mapOf("FILIAL" to "A", "TOTAL" to "R$ 10"))) }
        assertFailsWith<ValidacaoException> { Graficos.gerar(def, listOf(mapOf("TOTAL" to 1))) }
        val pizza = def.copy(graficos = listOf(def.graficos[0].copy(tipo = "pizza")))
        for (valor in listOf(-1, 0)) {
            assertFailsWith<ValidacaoException> { Graficos.gerar(pizza, listOf(mapOf("FILIAL" to "A", "TOTAL" to valor))) }
        }
        assertFailsWith<ValidacaoException> {
            Graficos.gerar(def, (1..101).map { mapOf("FILIAL" to it, "TOTAL" to 1) })
        }
    }

    @Test
    fun `valida configuracao referencias e limites sem liberar recursos arbitrarios`() {
        val validator = ReportUploadValidator(ReportProperties())
        val yaml = """
            nome: Vendas
            papeis: [admin]
            consulta: {sql: 'SELECT 1'}
            colunas:
              - {campo: FILIAL, titulo: Filial}
              - {campo: TOTAL, titulo: Total, tipo: moeda}
            graficos:
              - {id: vendas, categoria: FILIAL, valor: TOTAL}
        """.trimIndent()
        val template = """<img src="{{graficos.vendas}}" />"""
        assertTrue(validator.validar(yaml, template).valido)
        assertFalse(validator.validar(yaml, """<img src="{{graficos.inexistente}}" />""").valido)
        assertFalse(validator.validar(yaml, """<img src="{{params.url}}" />""").valido)
        assertFalse(validator.validar(yaml.replace("valor: TOTAL", "valor: FILIAL"), template).valido)
        assertFalse(validator.validar(yaml.replace("id: vendas", "id: vendas, largura: 99999"), template).valido)
        assertFalse(validator.validar(yaml.replace("id: vendas", "id: vendas, tipo: script"), template).valido)
        assertFalse(validator.validar(yaml, "<script>alert(1)</script>").valido)
    }
}
