package br.andrew.sap_reports.service

import br.andrew.sap_reports.definition.ReportUploadValidator
import br.andrew.sap_reports.definition.ValidacaoException
import br.andrew.sap_reports.odbc.OdbcClient
import org.springframework.stereotype.Service
import tools.jackson.databind.json.JsonMapper

/** Exclusivo do MCP de autoria. Nao grava relatorios nem consulta JDBC diretamente. */
@Service
class ExploracaoService(
    private val odbc: OdbcClient,
    private val validator: ReportUploadValidator,
    private val json: JsonMapper,
) {
    fun consultar(sql: String, params: Map<String, Any?> = emptyMap(), maxRows: Int = 100): Map<String, Any> {
        require(maxRows in 1..500) { "maxRows deve estar entre 1 e 500." }
        val validacao = validator.validarConsulta(sql, params)
        if (!validacao.valido) throw ValidacaoException(validacao)
        val resposta = odbc.explorar(sql, params, maxRows)
        // JSON sempre completo: nao cortar uma string no meio do envelope.
        // Uma unica celula pode conter um CLOB enorme, portanto ha tambem teto de caracteres.
        val linhas = mutableListOf<Map<String, Any?>>()
        var tamanho = json.writeValueAsString(resposta.columns).length
        require(tamanho <= 50_000) { "Metadados muito grandes; selecione menos colunas." }
        for (linha in resposta.rows) {
            val proximo = json.writeValueAsString(linha).length + 1
            if (tamanho + proximo > 50_000) break
            linhas += linha
            tamanho += proximo
        }
        return mapOf(
            "columns" to resposta.columns,
            "rows" to linhas,
            "rowCount" to linhas.size,
            "truncated" to (resposta.truncated || linhas.size < resposta.rows.size),
            "elapsedMs" to resposta.elapsedMs,
            "tabelas" to validacao.tabelas,
            "aviso" to "Amostra exploratoria; nao foi salvo nenhum relatorio. Use WHERE 1 = 0 para apenas metadados.",
        )
    }

    fun descrever(schema: String, tabela: String): Map<String, Any> {
        require(schema.matches(Regex("[A-Za-z_][A-Za-z0-9_]{0,127}"))) { "Schema invalido; informe o nome sem aspas." }
        require(tabela.matches(Regex("@?[A-Za-z_][A-Za-z0-9_]{0,127}"))) { "Tabela invalida; informe o nome sem aspas." }
        // Os nomes sao identificadores validados. Nao sao valores e nao podem ser binds SQL.
        return consultar("SELECT * FROM \"$schema\".\"$tabela\" WHERE 1 = 0", maxRows = 1)
    }
}
