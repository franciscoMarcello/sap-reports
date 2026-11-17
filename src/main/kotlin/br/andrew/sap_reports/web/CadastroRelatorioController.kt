package br.andrew.sap_reports.web

import br.andrew.sap_reports.config.ReportProperties
import br.andrew.sap_reports.security.UsuarioAutenticado
import br.andrew.sap_reports.service.CadastrosRelatorio
import br.andrew.sap_reports.service.ItemCadastro
import br.andrew.sap_reports.service.ReportService
import org.springframework.http.HttpStatus
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.server.ResponseStatusException
import java.security.Principal

/** Uso exclusivo dos filtros de parâmetro de relatório; telas de venda/cadastro usam as buscas do sap-service. */
@RestController
@RequestMapping("/api/v1/relatorios/cadastros")
class CadastroRelatorioController(
    private val cadastros: CadastrosRelatorio,
    private val reports: ReportService,
    private val properties: ReportProperties,
) {
    @GetMapping("/{tipo}")
    fun buscar(@PathVariable tipo: String, @RequestParam(required = false) q: String?, principal: Principal): PaginaCadastro {
        // Quem não enxerga nenhum relatório não tem filtro para preencher; o autor preenche o do preview do rascunho.
        val papeis = (principal as UsuarioAutenticado).papeis
        if (properties.authorRole !in papeis && reports.listarPublicados(papeis).isEmpty()) {
            throw ResponseStatusException(HttpStatus.FORBIDDEN, "Usuario sem relatorio publicado.")
        }
        val itens = cadastros.buscar(tipo, q)
            ?: throw ResponseStatusException(HttpStatus.NOT_FOUND, "Tipo de cadastro desconhecido.")
        return PaginaCadastro(itens)
    }
}

/** Formato `Page` que o `app-search` do front lê; a busca não pagina. */
data class PaginaCadastro(val content: List<ItemCadastro>, val nextLink: String = "")
