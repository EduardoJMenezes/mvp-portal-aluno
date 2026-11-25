package br.com.plataforma.comandos;

import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import br.com.plataforma.BaseDeComando;
import org.junit.jupiter.api.Test;

/** A venda montada pelo chat: valores em reais, cartão por parcela, e plano novo nasce fora do ar. */
class VendasComandosTest extends BaseDeComando {

    private static final String EXTENSIVO = """
            {"link": "extensivo-2027", "nome": "Extensivo 2027", "turmas": ["Extensivo 2027", "Intensivo 2027"],
             "matricula": 330, "matricula_parcelas": 3, "acesso_ate": "2027-11-30", "a_venda_ate": "2027-08-31",
             "a_venda": true,
             "precos": [
               {"pix": 1700, "cartao_parcelas": 12, "cartao_parcela": 159.90},
               {"a_partir_de": "2027-04-01", "pix": 1240, "cartao_parcelas": 8, "cartao_parcela": 174.90}]}""";

    private static final String TABELA_ORIGINAL = """
            [{"pix": 1700, "cartao_parcelas": 12, "cartao_parcela": 159.90},
             {"a_partir_de": "2027-04-01", "pix": 1240, "cartao_parcelas": 8, "cartao_parcela": 174.90}]""";

    @Test
    void montaOExtensivoForaDoArEDepoisPoeNoAr() throws Exception {
        comando("montar_venda", EXTENSIVO)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.criada").value(true))
                .andExpect(jsonPath("$.plano.ativo").value(false))
                .andExpect(jsonPath("$.aviso").exists())
                .andExpect(jsonPath("$.plano.matricula_centavos").value(33000))
                .andExpect(jsonPath("$.plano.precos[0].cartao_centavos").value(191880))
                .andExpect(jsonPath("$.plano.precos[1].a_partir_de").value("2027-04-01"))
                .andExpect(jsonPath("$.plano.precos[1].cartao_centavos").value(139920))
                .andExpect(jsonPath("$.plano.turmas.length()").value(2));

        // Conferido na tela, o professor manda pôr no ar: a mesma tool, pelo link.
        comando("montar_venda", "{\"link\": \"extensivo-2027\", \"a_venda\": true}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.criada").value(false))
                .andExpect(jsonPath("$.plano.ativo").value(true))
                .andExpect(jsonPath("$.plano.precos.length()").value(2));

        // Trocar a tabela de um plano que já existe, repetindo a linha "desde já" (só cartão agora).
        comando("montar_venda", """
                {"link": "extensivo-2027", "precos": [
                  {"cartao_parcelas": 12, "cartao_parcela": 159.90},
                  {"a_partir_de": "2027-04-01", "cartao_parcelas": 8, "cartao_parcela": 174.90}]}""")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.plano.precos.length()").value(2))
                .andExpect(jsonPath("$.plano.precos[0].pix_centavos").doesNotExist())
                .andExpect(jsonPath("$.plano.precos[0].cartao_centavos").value(191880));
        comando("montar_venda", "{\"link\": \"extensivo-2027\", \"precos\": " + TABELA_ORIGINAL + "}")
                .andExpect(status().isOk());

        comando("ver_vendas", "{}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.planos[0].hoje.pix_centavos").value(170000))
                .andExpect(jsonPath("$.planos[0].hoje.cartao_parcelas").value(12))
                .andExpect(jsonPath("$.planos[0].hoje.vale_ate").value("2027-03-31"))
                .andExpect(jsonPath("$.planos[0].venda_encerrada").value(false))
                .andExpect(jsonPath("$.envios_pendentes.length()").value(0));
    }

    @Test
    void valorComMaisDeDoisCentavosEAlunoSaoRecusados() throws Exception {
        comando("montar_venda", """
                {"link": "extensivo-2027", "nome": "Extensivo 2027", "turmas": ["Extensivo 2027"],
                 "precos": [{"pix": 1700.005}]}""")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail").value("'pix' é em reais com até dois centavos, ex.: 159.90 (veio 1700.005)."));
        comando("montar_venda", EXTENSIVO, ALUNO).andExpect(status().isForbidden());
        comando("ver_vendas", "{}", ALUNO).andExpect(status().isForbidden());
    }
}
