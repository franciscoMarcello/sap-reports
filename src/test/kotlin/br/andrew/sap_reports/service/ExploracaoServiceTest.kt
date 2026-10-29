package br.andrew.sap_reports.service

import br.andrew.sap_reports.config.OdbcProperties
import br.andrew.sap_reports.config.ReportProperties
import br.andrew.sap_reports.definition.ReportUploadValidator
import br.andrew.sap_reports.definition.ValidacaoException
import br.andrew.sap_reports.odbc.OdbcClient
import com.github.tomakehurst.wiremock.WireMockServer
import com.github.tomakehurst.wiremock.client.WireMock.*
import com.github.tomakehurst.wiremock.core.WireMockConfiguration.options
import org.junit.jupiter.api.AfterEach
import tools.jackson.databind.json.JsonMapper
import kotlin.test.*

class ExploracaoServiceTest {
    private val servidor = WireMockServer(options().dynamicPort()).apply { start() }
    private val json = JsonMapper.builder().build()
    private fun service(timeout: Int = 120) = ExploracaoService(
        OdbcClient(OdbcProperties(baseUrl = servidor.baseUrl(), queryTimeoutSeconds = timeout)),
        ReportUploadValidator(ReportProperties(allowedTables = listOf("SBO.OINV", "SBO.@LOTES"))), json,
    )

    @AfterEach
    fun parar() = servidor.stop()

    private fun resposta(rows: List<Map<String, Any?>> = emptyList(), truncated: Boolean = false) {
        servidor.stubFor(post(urlEqualTo("/api/v1/query")).willReturn(okJson(json.writeValueAsString(mapOf(
            "columns" to listOf(mapOf("name" to "TOTAL", "type" to "DECIMAL", "nullable" to true)),
            "rows" to rows, "rowCount" to rows.size, "truncated" to truncated, "elapsedMs" to 4,
        )))))
    }

    @Test
    fun `descreve tabela com metadados mesmo sem registros e sem acessar SYS`() {
        resposta()
        val resultado = service().descrever("SBO", "@LOTES")
        assertEquals(emptyList<Any>(), resultado["rows"])
        assertTrue(resultado["columns"].toString().contains("DECIMAL"))
        servidor.verify(postRequestedFor(urlEqualTo("/api/v1/query"))
            .withRequestBody(matchingJsonPath("$.sql", equalTo("SELECT * FROM \"SBO\".\"@LOTES\" WHERE 1 = 0")))
            .withRequestBody(matchingJsonPath("$.timeoutSeconds", equalTo("15")))
            .withRequestBody(matchingJsonPath("$.maxRows", equalTo("1"))))
    }

    @Test
    fun `explora com binds amostra truncada e timeout menor configurado`() {
        resposta(listOf(mapOf("TOTAL" to "12.34")), true)
        val resultado = service(5).consultar("SELECT TOTAL FROM SBO.OINV WHERE ID = :id", mapOf("id" to 3), 20)
        assertEquals(true, resultado["truncated"])
        assertEquals(1, resultado["rowCount"])
        servidor.verify(postRequestedFor(urlEqualTo("/api/v1/query"))
            .withRequestBody(matchingJsonPath("$.params.id", equalTo("3")))
            .withRequestBody(matchingJsonPath("$.timeoutSeconds", equalTo("5")))
            .withRequestBody(matchingJsonPath("$.maxRows", equalTo("20"))))
    }

    @Test
    fun `recusa escrita comentarios multiplas consultas tabelas e binds invalidos antes do gateway`() {
        for (sql in listOf("DELETE FROM SBO.OINV", "SELECT * FROM SBO.OINV; DELETE FROM SBO.OINV",
            "SELECT * FROM SBO.OINV -- comentario", "SELECT * FROM SYS.USERS", "SELECT :id FROM SBO.OINV")) {
            assertFailsWith<ValidacaoException>(sql) { service().consultar(sql) }
        }
        assertFailsWith<IllegalArgumentException> { service().consultar("SELECT * FROM SBO.OINV", maxRows = 501) }
        assertFailsWith<IllegalArgumentException> { service().descrever("SBO", "OINV; DROP TABLE X") }
        servidor.verify(0, postRequestedFor(urlEqualTo("/api/v1/query")))
    }

    @Test
    fun `resposta grande retorna JSON completo metadados e sinaliza corte`() {
        resposta(listOf(mapOf("TOTAL" to "x".repeat(60_000))))
        val resultado = service().consultar("SELECT * FROM SBO.OINV")
        assertEquals(true, resultado["truncated"])
        assertEquals(0, resultado["rowCount"])
        assertTrue(json.readTree(json.writeValueAsString(resultado)).has("columns"))
    }
}
