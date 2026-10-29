package br.andrew.sap_reports.definition

object GraficosRules {
    val tipos = setOf("barras", "linhas", "pizza", "burndown")
    val agregacoes = setOf("soma", "media", "minimo", "maximo", "contagem")
    val idValido = Regex("[A-Za-z][A-Za-z0-9_]{0,63}")
    const val MAX_GRAFICOS = 5
    const val MAX_CATEGORIAS = 100
    private val referencia = Regex("""\{\{\s*graficos\.([A-Za-z][A-Za-z0-9_]*)\s*\}\}""")

    fun validar(def: ReportDefinition, template: String): List<Problema> {
        val p = mutableListOf<Problema>()
        fun erro(campo: String, mensagem: String) { p += Problema(campo, Regra.SCHEMA_INVALIDO, mensagem) }
        if (def.graficos.size > MAX_GRAFICOS) erro("graficos", "Maximo de $MAX_GRAFICOS graficos por relatorio.")
        def.graficos.forEachIndexed { i, g ->
            val caminho = "graficos[$i]"
            if (!idValido.matches(g.id)) erro("$caminho.id", "Use letra inicial e ate 64 letras, digitos ou _.")
            if (def.graficos.count { it.id == g.id } > 1) erro("$caminho.id", "ID de grafico duplicado.")
            if (g.tipo !in tipos) erro("$caminho.tipo", "Use ${tipos.joinToString()}.")
            if (g.agregacao !in agregacoes) erro("$caminho.agregacao", "Use ${agregacoes.joinToString()}.")
            if (g.titulo.length > 200) erro("$caminho.titulo", "Titulo com mais de 200 caracteres.")
            if (g.largura !in 320..1600) erro("$caminho.largura", "Largura entre 320 e 1600 pixels.")
            if (g.altura !in 200..1000) erro("$caminho.altura", "Altura entre 200 e 1000 pixels.")
            if (def.colunas.none { it.campo == g.categoria }) erro("$caminho.categoria", "Categoria deve referenciar uma coluna declarada.")
            val valor = def.colunas.firstOrNull { it.campo == g.valor }
            if (valor == null) erro("$caminho.valor", "Valor deve referenciar uma coluna declarada.")
            else if (g.agregacao != "contagem" && valor.tipo !in setOf("numero", "moeda", "percentual")) {
                erro("$caminho.valor", "A agregacao exige coluna numerica.")
            }
            if (g.tipo == "burndown") {
                if (def.colunas.firstOrNull { it.campo == g.categoria }?.tipo != "data") {
                    erro("$caminho.categoria", "Burndown exige coluna do tipo data (YYYY-MM-DD).")
                }
                val ideal = def.colunas.firstOrNull { it.campo == g.valorIdeal }
                if (ideal == null || ideal.tipo !in setOf("numero", "moeda", "percentual")) {
                    erro("$caminho.valorIdeal", "Burndown exige valorIdeal referenciando uma coluna numerica.")
                }
                if (g.agregacao == "contagem") erro("$caminho.agregacao", "Burndown exige valores de trabalho restante; contagem nao e aceita.")
            } else if (g.valorIdeal != null) {
                erro("$caminho.valorIdeal", "valorIdeal e exclusivo do tipo burndown.")
            }
        }
        referencia.findAll(template).forEach {
            if (def.graficos.none { g -> g.id == it.groupValues[1] }) {
                erro("template", "Grafico '${it.groupValues[1]}' nao declarado em graficos.")
            }
        }
        return p
    }
}
