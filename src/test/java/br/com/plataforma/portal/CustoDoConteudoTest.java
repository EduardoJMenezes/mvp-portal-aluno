package br.com.plataforma.portal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import jakarta.persistence.EntityManagerFactory;
import org.hibernate.SessionFactory;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * A árvore do curso é a rota mais pedida do portal: cada aba a repete de minuto em minuto. O
 * número de idas ao banco não pode crescer com o curso — foi assim que ela chegou a centenas de
 * consultas por pedido, uma por módulo, por sub-módulo e por linha.
 */
class CustoDoConteudoTest extends BaseDoPortal {

    @Autowired private EntityManagerFactory fabrica;

    @BeforeEach
    void cenario() {
        comSenhas();
    }

    /** Um módulo com tudo o que uma linha pode ser: vídeo, PDF, aula só de uma turma e questão. */
    private void criarModulo(int n) throws Exception {
        var nome = "K%02d".formatted(n);
        comando("criar_modulo", "{\"turma\": \"Extensivo 2027\", \"nome\": \"%s\"}".formatted(nome))
                .andExpect(status().isOk());
        var modulo = idDo("modules", nome);
        var aulas = jdbc.queryForObject(
                "SELECT id FROM submodules WHERE nome = 'Aulas' AND modulo_id = ?", Integer.class, modulo);
        var apostila = jdbc.queryForObject(
                "SELECT id FROM submodules WHERE nome = 'Questões da apostila' AND modulo_id = ?", Integer.class, modulo);

        for (int i = 1; i <= 4; i++) {
            criarItem(aulas, criarVideo("%d%04d".formatted(n, i), "Aula " + i), "Aula " + i, i, "PUBLICADO");
        }
        var soDoExtensivo = criarItem(aulas, criarVideo("%d9999".formatted(n), "Plantão"), "Plantão", 5, "PUBLICADO");
        jdbc.update("INSERT INTO item_classes (item_id, turma_id) VALUES (?, 10)", soDoExtensivo);

        var material = jdbc.queryForObject("""
                INSERT INTO materials (titulo, tipo, tamanho, status, criado_por_id, conteudo)
                VALUES (?, 'application/pdf', 4, 'PUBLICADO', ?, decode('25504446', 'hex')) RETURNING id""",
                Integer.class, "Lista " + nome, ADMIN);
        jdbc.update("""
                INSERT INTO items (submodulo_id, material_id, nome, ordem, status)
                VALUES (?, ?, 'Lista', 6, 'PUBLICADO')""", aulas, material);

        for (int i = 1; i <= 2; i++) {
            var questao = criarQuestao("Enunciado %s.%d".formatted(nome, i), "A", "MEDIA", "PUBLICADO");
            jdbc.update("""
                    INSERT INTO items (submodulo_id, questao_id, nome, ordem, status)
                    VALUES (?, ?, ?, ?, 'PUBLICADO')""", apostila, questao, "Q%02d".formatted(i), i);
        }
    }

    private long consultasDoConteudo() throws Exception {
        var estatisticas = fabrica.unwrap(SessionFactory.class).getStatistics();
        estatisticas.setStatisticsEnabled(true);
        estatisticas.clear();
        get("/api/aluno/conteudo", ALUNO).andExpect(status().isOk());
        return estatisticas.getPrepareStatementCount();
    }

    @Test
    void oCursoQuadruplicaEAsConsultasNao() throws Exception {
        criarModulo(1);
        criarModulo(2);
        var comDois = consultasDoConteudo();

        for (int n = 3; n <= 8; n++) {
            criarModulo(n);
        }
        var comOito = consultasDoConteudo();

        get("/api/aluno/conteudo", ALUNO)
                .andExpect(jsonPath("$[0].modulos.length()").value(8))
                .andExpect(jsonPath("$[0].modulos[7].submodulos[0].itens.length()").value(6))
                .andExpect(jsonPath("$[0].modulos[7].submodulos[1].itens.length()").value(2));
        assertThat(comOito).as("consultas com 8 módulos (com 2 foram %d)", comDois).isEqualTo(comDois);
    }
}
