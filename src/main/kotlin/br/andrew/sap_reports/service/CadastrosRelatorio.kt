package br.andrew.sap_reports.service

import br.andrew.sap_reports.config.ReportProperties
import br.andrew.sap_reports.odbc.OdbcClient
import br.andrew.sap_reports.odbc.paraDecimal
import org.springframework.stereotype.Component
import java.util.Locale

/**
 * Busca dos filtros de parâmetro dos relatórios — uso exclusivo do sap-reports.
 * Sem o corte de carteira e sem o PAC% das buscas de venda, de propósito:
 * relatório olha histórico, não o que o vendedor pode vender.
 * Tela de venda/cadastro NÃO usa esta busca; use as do sap-service.
 *
 * O `tipo` só escolhe uma entrada do registro fechado abaixo e nunca entra no
 * SQL; o termo vai sempre como parâmetro nomeado. CPF/CNPJ serve para buscar,
 * nunca volta na resposta.
 */
@Component
class CadastrosRelatorio(
    private val odbc: OdbcClient,
    private val properties: ReportProperties,
) {
    val tipos: Set<String> = REGISTRO.keys

    /** `null` = tipo desconhecido, sem consulta. */
    fun buscar(tipo: String, q: String?): List<ItemCadastro>? {
        val consulta = REGISTRO[tipo] ?: return null
        val params = mutableMapOf<String, Any?>("termo" to termo(q))
        if (consulta.exato) params["exato"] = q?.trim().orEmpty()
        return odbc.consultar(sql(consulta), params, LIMITE).rows.mapNotNull { linha ->
            val codigo = if (consulta.numerico) linha["CODIGO"].paraDecimal()?.toInt() else linha["CODIGO"]?.toString()
            codigo?.let { ItemCadastro(it, linha["NOME"]?.toString()) }
        }
    }

    internal fun sql(tipo: String): String? = REGISTRO[tipo]?.let(::sql)

    private fun sql(c: Consulta): String {
        val p = properties.schemaCadastro?.takeIf { it.isNotBlank() }?.let { "$it." } ?: ""
        val busca = c.busca.joinToString(" OR ") { "UPPER($it) LIKE :termo" }
        val onde = when {
            c.exato -> "(${c.filtro} AND ($busca)) OR ${c.codigo} = :exato"
            c.filtro != null -> "${c.filtro} AND ($busca)"
            else -> busca
        }
        // DISTINCT: o LEFT JOIN do CRD7 pode repetir o parceiro.
        return """
            SELECT DISTINCT ${c.codigo} AS "CODIGO", ${c.nome} AS "NOME"
            FROM ${c.tabela(p)}
            WHERE $onde
            ORDER BY "NOME"
        """.trimIndent()
    }

    /** Preparo igual ao `BusinessPartnersService` do sap-service: CPF/CNPJ só dígitos ganha máscara, `*` é coringa. */
    internal fun termo(q: String?): String {
        val limpo = q?.trim().orEmpty()
        if (limpo.isEmpty()) return "%"
        val preparado = when {
            limpo.all(Char::isDigit) && limpo.length == 11 -> mascara(limpo, "###.###.###-##")
            limpo.all(Char::isDigit) && limpo.length == 14 -> mascara(limpo, "##.###.###/####-##")
            else -> limpo.replace('*', '%').uppercase(Locale.ROOT)
        }
        return "%$preparado%"
    }

    private fun mascara(digitos: String, molde: String): String {
        val resto = digitos.iterator()
        return molde.map { if (it == '#') resto.next() else it }.joinToString("")
    }

    private class Consulta(
        val tabela: (prefixo: String) -> String,
        val codigo: String,
        val nome: String,
        val busca: List<String>,
        val filtro: String? = null,
        val numerico: Boolean = false,
        val exato: Boolean = false,
    )

    companion object {
        private const val LIMITE = 50

        private fun parceiro(cardType: Char) = Consulta(
            tabela = { p -> """${p}OCRD T LEFT JOIN ${p}CRD7 D ON D."CardCode" = T."CardCode" AND D."Address" = ''""" },
            codigo = """T."CardCode"""",
            nome = """T."CardName"""",
            busca = listOf("""T."CardCode"""", """T."CardName"""", """D."TaxId0"""", """D."TaxId4""""),
            filtro = """T."CardType" = '$cardType'""",
        )

        private val REGISTRO: Map<String, Consulta> = mapOf(
            "parceiro_negocio" to parceiro('C'),
            "fornecedor" to parceiro('S'),
            // Inativo no SAP B1 é frozenFor = 'Y' (validFor não serve); código exato traz até inativo.
            "item" to Consulta(
                tabela = { p -> "${p}OITM T" },
                codigo = """T."ItemCode"""",
                nome = """T."ItemName"""",
                busca = listOf("""T."ItemCode"""", """T."ItemName""""),
                filtro = """T."frozenFor" = 'N'""",
                exato = true,
            ),
            "conta_contabil" to Consulta(
                tabela = { p -> "${p}OACT T" },
                codigo = """T."AcctCode"""",
                nome = """T."AcctName"""",
                busca = listOf("""T."AcctCode"""", """T."FormatCode"""", """T."AcctName""""),
                filtro = """T."Postable" = 'Y'""",
            ),
            "centro_custo" to Consulta(
                tabela = { p -> "${p}OPRC T" },
                codigo = """T."PrcCode"""",
                nome = """T."PrcName"""",
                busca = listOf("""T."PrcCode"""", """T."PrcName""""),
            ),
            "condicao_pagamento" to Consulta(
                tabela = { p -> "${p}OCTG T" },
                codigo = """T."GroupNum"""",
                nome = """T."PymntGroup"""",
                busca = listOf("""T."PymntGroup""""),
                numerico = true,
            ),
            "grupo_item" to Consulta(
                tabela = { p -> "${p}OITB T" },
                codigo = """T."ItmsGrpCod"""",
                nome = """T."ItmsGrpNam"""",
                busca = listOf("""T."ItmsGrpNam""""),
                numerico = true,
            ),
            "deposito" to Consulta(
                tabela = { p -> "${p}OWHS T" },
                codigo = """T."WhsCode"""",
                nome = """T."WhsName"""",
                busca = listOf("""T."WhsCode"""", """T."WhsName""""),
            ),
        )
    }
}

/** `codigo` é Int em `condicao_pagamento` e `grupo_item`, String nos demais. */
data class ItemCadastro(val codigo: Any, val nome: String?)
