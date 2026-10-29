package br.andrew.sap_reports.render

import br.andrew.sap_reports.definition.*
import br.andrew.sap_reports.odbc.paraDecimal
import com.github.jknack.handlebars.Handlebars
import org.knowm.xchart.BitmapEncoder
import org.knowm.xchart.CategoryChartBuilder
import org.knowm.xchart.CategorySeries
import org.knowm.xchart.PieChartBuilder
import org.knowm.xchart.XYChartBuilder
import org.knowm.xchart.style.lines.SeriesLines
import org.knowm.xchart.style.markers.SeriesMarkers
import java.awt.Color
import java.math.BigDecimal
import java.math.MathContext
import java.util.Base64
import java.time.LocalDate
import java.time.ZoneOffset
import java.util.Date
import java.util.TimeZone

/** Acumula apenas categorias (limitadas), nunca todas as linhas do relatorio. */
class Graficos(private val def: ReportDefinition) {
    private class Acumulado {
        var quantidade = 0L
        var soma = BigDecimal.ZERO
        var minimo: BigDecimal? = null
        var maximo: BigDecimal? = null
        fun adicionar(valor: BigDecimal) {
            quantidade++
            soma += valor
            minimo = minimo?.min(valor) ?: valor
            maximo = maximo?.max(valor) ?: valor
        }
        fun valor(tipo: String): BigDecimal = when (tipo) {
            "contagem" -> quantidade.toBigDecimal()
            "media" -> soma.divide(quantidade.toBigDecimal(), MathContext.DECIMAL128)
            "minimo" -> minimo!!
            "maximo" -> maximo!!
            else -> soma
        }
    }

    private val categorias = def.graficos.map { linkedMapOf<String, Acumulado>() }
    private val ideais = def.graficos.map { linkedMapOf<String, Acumulado>() }

    fun somar(linha: Map<String, Any?>) {
        def.graficos.forEachIndexed { i, g ->
            if (!linha.containsKey(g.categoria) || !linha.containsKey(g.valor)) {
                falhar(i, "Colunas '${g.categoria}' e '${g.valor}' devem existir no resultado SQL.")
            }
            if (g.tipo == "burndown") {
                somarBurndown(i, g, linha)
                return@forEachIndexed
            }
            val bruto = linha[g.valor] ?: return@forEachIndexed
            val valor = if (g.agregacao == "contagem") BigDecimal.ONE else bruto.paraDecimal()
                ?: falhar(i, "Valor nao numerico na coluna '${g.valor}'.")
            val categoria = linha[g.categoria]?.toString() ?: "(Sem categoria)"
            if (categoria.length > 200) falhar(i, "Categoria com mais de 200 caracteres; resuma o rotulo no SQL.")
            val mapa = categorias[i]
            if (categoria !in mapa && mapa.size >= GraficosRules.MAX_CATEGORIAS) {
                falhar(i, "Mais de ${GraficosRules.MAX_CATEGORIAS} categorias; agregue ou filtre o SQL. Nenhuma categoria foi omitida.")
            }
            mapa.getOrPut(categoria) { Acumulado() }.adicionar(valor)
        }
    }

    internal fun dados(indice: Int): Map<String, BigDecimal> =
        categorias[indice].mapValues { it.value.valor(def.graficos[indice].agregacao) }.let {
            if (def.graficos[indice].tipo == "burndown") it.toSortedMap() else it
        }

    internal fun dadosIdeais(indice: Int): Map<String, BigDecimal> =
        ideais[indice].mapValues { it.value.valor(def.graficos[indice].agregacao) }.toSortedMap()

    private fun somarBurndown(i: Int, g: Grafico, linha: Map<String, Any?>) {
        val data = linha[g.categoria]?.toString().orEmpty()
        if (!data.matches(Regex("\\d{4}-\\d{2}-\\d{2}")) || runCatching { LocalDate.parse(data) }.isFailure) {
            falhar(i, "Burndown exige datas validas YYYY-MM-DD na coluna '${g.categoria}'.")
        }
        if (data !in ideais[i] && ideais[i].size >= GraficosRules.MAX_CATEGORIAS) {
            falhar(i, "Burndown excede ${GraficosRules.MAX_CATEGORIAS} datas; filtre o periodo no SQL.")
        }
        val ideal = linha[g.valorIdeal].paraDecimal()
            ?: falhar(i, "Burndown exige valor ideal numerico em todas as datas ('${g.valorIdeal}').")
        val real = linha[g.valor]?.let {
            it.paraDecimal() ?: falhar(i, "Valor real nao numerico na coluna '${g.valor}'.")
        }
        if (ideal.signum() < 0 || (real != null && real.signum() < 0)) {
            falhar(i, "Trabalho restante real e ideal devem ser nao negativos.")
        }
        ideais[i].getOrPut(data) { Acumulado() }.adicionar(ideal)
        // NULL e ausencia de medicao (inclusive datas futuras), nunca trabalho zerado.
        if (real != null) categorias[i].getOrPut(data) { Acumulado() }.adicionar(real)
    }

    private fun burndown(i: Int, g: Grafico): ByteArray {
        val chart = XYChartBuilder().width(g.largura).height(g.altura).title(g.titulo)
            .xAxisTitle(def.colunas.first { it.campo == g.categoria }.titulo)
            .yAxisTitle(def.colunas.first { it.campo == g.valor }.titulo).build()
        chart.styler.datePattern = "dd/MM/yyyy"
        chart.styler.timezone = TimeZone.getTimeZone("UTC")
        chart.styler.yAxisMin = 0.0
        chart.styler.chartBackgroundColor = Color.WHITE
        fun serie(nome: String, pontos: Map<String, BigDecimal>, cor: Color) {
            if (pontos.isEmpty()) return
            val datas = pontos.keys.map { Date.from(LocalDate.parse(it).atStartOfDay(ZoneOffset.UTC).toInstant()) }
            val valores = pontos.values.map { n -> n.toDouble().also {
                if (!it.isFinite()) falhar(i, "Valor fora da escala numerica do grafico.")
            } }
            chart.addSeries(nome, datas, valores).apply {
                lineColor = cor
                markerColor = cor
                marker = if (nome == "Ideal") SeriesMarkers.NONE else SeriesMarkers.CIRCLE
                if (nome == "Ideal") lineStyle = SeriesLines.DASH_DASH
            }
        }
        serie("Ideal", dadosIdeais(i), Color(110, 110, 110))
        serie("Real", dados(i), Color(37, 99, 235))
        return BitmapEncoder.getBitmapBytes(chart, BitmapEncoder.BitmapFormat.PNG)
    }

    /** PNG local. SafeString apenas sobre os bytes gerados, nunca sobre dados de usuario. */
    fun imagens(): Map<String, Handlebars.SafeString> = buildMap {
        def.graficos.forEachIndexed { i, g ->
            if (g.tipo == "burndown") {
                if (ideais[i].isNotEmpty()) {
                    put(g.id, Handlebars.SafeString("data:image/png;base64," + Base64.getEncoder().encodeToString(burndown(i, g))))
                }
                return@forEachIndexed
            }
            val dados = dados(i)
            if (dados.isEmpty()) return@forEachIndexed
            val valores = dados.values.map { valor ->
                valor.toDouble().also { if (!it.isFinite()) falhar(i, "Valor fora da escala numerica do grafico.") }
            }
            val png = if (g.tipo == "pizza") {
                if (dados.values.any { it.signum() < 0 } || dados.values.all { it.signum() == 0 }) {
                    falhar(i, "Pizza exige valores nao negativos e ao menos um valor positivo.")
                }
                val chart = PieChartBuilder().width(g.largura).height(g.altura).title(g.titulo).build()
                dados.keys.zip(valores).filter { it.second > 0 }.forEach { (nome, valor) -> chart.addSeries(nome, valor) }
                BitmapEncoder.getBitmapBytes(chart, BitmapEncoder.BitmapFormat.PNG)
            } else {
                val chart = CategoryChartBuilder().width(g.largura).height(g.altura).title(g.titulo)
                    .xAxisTitle(def.colunas.first { it.campo == g.categoria }.titulo)
                    .yAxisTitle(def.colunas.first { it.campo == g.valor }.titulo).build()
                chart.styler.isLegendVisible = false
                chart.styler.defaultSeriesRenderStyle = if (g.tipo == "linhas")
                    CategorySeries.CategorySeriesRenderStyle.Line else CategorySeries.CategorySeriesRenderStyle.Bar
                chart.addSeries(g.valor, dados.keys.toList(), valores)
                BitmapEncoder.getBitmapBytes(chart, BitmapEncoder.BitmapFormat.PNG)
            }
            put(g.id, Handlebars.SafeString("data:image/png;base64," + Base64.getEncoder().encodeToString(png)))
        }
    }

    private fun falhar(i: Int, mensagem: String): Nothing = throw ValidacaoException(
        ResultadoValidacao(listOf(Problema("graficos[$i]", Regra.SCHEMA_INVALIDO, mensagem))),
    )

    companion object {
        fun gerar(def: ReportDefinition, linhas: List<Map<String, Any?>>): Map<String, Handlebars.SafeString> =
            Graficos(def).apply { linhas.forEach(::somar) }.imagens()
    }
}
