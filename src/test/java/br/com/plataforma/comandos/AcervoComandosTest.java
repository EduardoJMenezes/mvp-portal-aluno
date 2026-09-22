package br.com.plataforma.comandos;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import br.com.plataforma.BaseDeComando;
import org.junit.jupiter.api.Test;

/** O que a importação do Vimeo grava: itens em rascunho, com a etiqueta no vídeo. */
class AcervoComandosTest extends BaseDeComando {

    private void montarK01() throws Exception {
        comando("criar_modulo", """
                {"turma": "Extensivo 2027", "nome": "K01"}""").andExpect(status().isOk());
    }

    @Test
    void criaUmItemPorVideoEmRascunho() throws Exception {
        montarK01();
        comando("cadastrar_assunto", """
                {"nome": "Estequiometria", "subassuntos": ["Pureza e rendimento"]}""")
                .andExpect(status().isOk());

        comando("importar_videos_como_itens", """
                {"turma": "Extensivo 2027", "modulo": "K01", "submodulo": "Aulas",
                 "videos": [
                   {"vimeo_id": "111", "titulo": "Aula 1 — cadeias", "embed_url": "https://player/111?h=abc",
                    "assunto": "Estequiometria", "subassunto": "Pureza"},
                   {"vimeo_id": "222", "titulo": "Aula 2 — isomeria", "nome": "Aula 2"}]}""")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.rascunho.tipo").value("ITENS"))
                .andExpect(jsonPath("$.rascunho.status").value("RASCUNHO"))
                .andExpect(jsonPath("$.rascunho.resumo")
                        .value("2 vídeo(s) para Extensivo 2027 / K01 › Aulas"))
                .andExpect(jsonPath("$.rascunho.itens[0].nome").value("Aula 1 — cadeias"))
                .andExpect(jsonPath("$.rascunho.itens[0].video.vimeo_id").value("111"))
                .andExpect(jsonPath("$.rascunho.itens[0].assuntos[0].subassunto")
                        .value("Pureza e rendimento"))
                // sem `nome`, vale o título; com `nome`, vale ele
                .andExpect(jsonPath("$.rascunho.itens[1].nome").value("Aula 2"))
                .andExpect(jsonPath("$.erros").isEmpty())
                .andExpect(jsonPath("$.aviso").value(
                        "Nada foi publicado. O rascunho precisa da aprovação do professor."));

        // Nasce em rascunho: nenhum aluno vê.
        assertThat(contar("items WHERE status = 'RASCUNHO'")).isEqualTo(2);
        assertThat(contar("items WHERE status = 'PUBLICADO'")).isZero();
    }

    /** Reimportar não duplica o vídeo: vimeo_id é identidade externa. */
    @Test
    void oMesmoVideoEmOutroSubmoduloReusaALinhaDoAcervo() throws Exception {
        montarK01();
        var corpo = """
                {"turma": "Extensivo 2027", "modulo": "K01", "submodulo": "%s",
                 "videos": [{"vimeo_id": "111", "titulo": "Aula 1"}]}""";

        comando("importar_videos_como_itens", corpo.formatted("Aulas")).andExpect(status().isOk());
        comando("importar_videos_como_itens", corpo.formatted("Questões da apostila"))
                .andExpect(status().isOk());

        assertThat(contar("videos")).isEqualTo(1);
        assertThat(contar("items")).isEqualTo(2);
    }

    @Test
    void oMesmoVideoDuasVezesNoMesmoSubmoduloViraErroENaoItem() throws Exception {
        montarK01();

        comando("importar_videos_como_itens", """
                {"turma": "Extensivo 2027", "modulo": "K01", "submodulo": "Aulas",
                 "videos": [{"vimeo_id": "111", "titulo": "Aula 1"},
                            {"vimeo_id": "111", "titulo": "Aula 1 de novo"}]}""")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.erros[0]").value("111: 'Aulas' já tem este vídeo."));

        assertThat(contar("items")).isEqualTo(1);
    }

    @Test
    void nenhumItemCriadoDerrubaTudo() throws Exception {
        montarK01();

        comando("importar_videos_como_itens", """
                {"turma": "Extensivo 2027", "modulo": "K01", "submodulo": "Aulas",
                 "videos": [{"vimeo_id": "", "titulo": "Sem id"}]}""")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail").value(org.hamcrest.Matchers.startsWith(
                        "Nenhum item pôde ser criado.")));

        assertThat(contar("drafts")).isZero();
        assertThat(contar("items")).isZero();
    }

    @Test
    void submoduloQueNaoExisteListaOsQueHa() throws Exception {
        montarK01();

        comando("importar_videos_como_itens", """
                {"turma": "Extensivo 2027", "modulo": "K01", "submodulo": "Revisão",
                 "videos": [{"vimeo_id": "111", "titulo": "Aula 1"}]}""")
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.detail").value(
                        "Sub-módulo 'Revisão' não existe em 'K01'. Há: 'Aulas', 'Questões da apostila'."));
    }
}
