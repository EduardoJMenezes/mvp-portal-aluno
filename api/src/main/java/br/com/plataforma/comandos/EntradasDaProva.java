package br.com.plataforma.comandos;

import br.com.plataforma.comum.RegraDeNegocio;
import br.com.plataforma.questoes.QuestoesServico;
import br.com.plataforma.simulados.MontagemDaProva;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

/**
 * Traduz a lista heterogênea que o MCP manda na forma da prova.
 *
 * <p>Cada entrada é o id de uma questão (número ou texto) ou a questão nova inteira. Essa
 * ambiguidade é da <b>borda</b>: o domínio recebe tipos separados.
 */
@Component
class EntradasDaProva {

    private final ObjectMapper json;

    EntradasDaProva(ObjectMapper json) {
        this.json = json;
    }

    List<MontagemDaProva.Entrada> traduzir(List<Object> cruas) {
        if (cruas == null) {
            return null;
        }
        var entradas = new ArrayList<MontagemDaProva.Entrada>();
        for (var crua : cruas) {
            if (crua instanceof Map<?, ?> mapa && mapa.containsKey("enunciado")) {
                entradas.add(new MontagemDaProva.Nova(
                        json.convertValue(mapa, QuestoesServico.DadosDaQuestaoNova.class)));
            } else if (crua instanceof Map<?, ?> mapa && mapa.containsKey("questao_id")) {
                entradas.add(new MontagemDaProva.PorId(String.valueOf(mapa.get("questao_id"))));
            } else if (crua == null) {
                throw new RegraDeNegocio(
                        "Questão vazia na lista. Use o id de uma questão do acervo ou a questão inteira.");
            } else {
                entradas.add(new MontagemDaProva.PorId(String.valueOf(crua)));
            }
        }
        return entradas;
    }

    Map<Integer, QuestoesServico.DadosDoVideo> resolucoes(Map<String, Object> cruas) {
        if (cruas == null || cruas.isEmpty()) {
            return Map.of();
        }
        var casadas = new java.util.LinkedHashMap<Integer, QuestoesServico.DadosDoVideo>();
        cruas.forEach((numero, dados) -> {
            try {
                casadas.put(Integer.valueOf(numero.strip()),
                        json.convertValue(dados, QuestoesServico.DadosDoVideo.class));
            } catch (NumberFormatException e) {
                throw new RegraDeNegocio(
                        "Número '%s' inválido em resolucoes; use o número da questão na prova."
                                .formatted(numero));
            }
        });
        return casadas;
    }
}
