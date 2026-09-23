package br.com.plataforma;

import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

/**
 * Cenário e atalhos de todo teste de comando.
 *
 * <p>As anotações são idênticas em todas as subclasses de propósito: assim o Spring reaproveita o
 * mesmo contexto, e o contêiner do Postgres sobe uma vez só para a suíte inteira.
 */
@SpringBootTest(properties = {
        "plataforma.token-de-servico=" + BaseDeComando.TOKEN,
        "portal.cookie-seguro=false",
        "portal.modo-demo=true",
        "portal.mcp-base-url=https://mcp.teste",
        "zoom.webhook-secret=" + BaseDeComando.SEGREDO_DO_ZOOM,
        "zoom.webhook-sincrono=true"})
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
public abstract class BaseDeComando {

    public static final String TOKEN = "token-de-servico-so-para-teste-com-32+";
    public static final String SEGREDO_DO_ZOOM = "segredo-do-webhook-de-teste";
    protected static final int ADMIN = 1;
    protected static final int ALUNO = 2;

    @Autowired protected MockMvc mvc;
    @Autowired protected JdbcTemplate jdbc;

    @BeforeEach
    void cenario() {
        jdbc.execute("TRUNCATE users, classes, videos, subjects, questions, exams, drafts, imports, images, login_attempts RESTART IDENTITY CASCADE");
        jdbc.update("""
                INSERT INTO users (id, nome, email, senha_hash, papel) VALUES
                  (1, 'Professora Ana', 'ana@teste.invalid', 'x', 'ADMIN'),
                  (2, 'Aluno Bruno', 'bruno@teste.invalid', 'x', 'ALUNO')""");
        jdbc.update("""
                INSERT INTO classes (id, nome, ano) VALUES
                  (10, 'Extensivo 2027', 2027),
                  (11, 'Intensivo 2027', 2027)""");
        // Os ids acima são fixos; o que a aplicação criar depois não pode colidir com eles.
        jdbc.execute("SELECT setval('users_id_seq', 100), setval('classes_id_seq', 100)");
    }

    protected ResultActions comando(String rota, String corpo, int operador) throws Exception {
        return mvc.perform(post("/comandos/" + rota)
                .contentType(APPLICATION_JSON)
                .content(corpo)
                .header("X-Servico", TOKEN)
                .header("X-Operador", operador));
    }

    protected ResultActions comando(String rota, String corpo) throws Exception {
        return comando(rota, corpo, ADMIN);
    }

    /** A porta interna: só o token de serviço — quem se identifica é a credencial no corpo. */
    protected ResultActions interno(String rota, String corpo) throws Exception {
        return mvc.perform(post("/interno/" + rota)
                .contentType(APPLICATION_JSON)
                .content(corpo)
                .header("X-Servico", TOKEN));
    }

    /** Vídeo direto no banco: criar vídeo é da importação do Vimeo, não da estrutura. */
    protected int criarVideo(String vimeoId, String titulo) {
        return jdbc.queryForObject(
                "INSERT INTO videos (vimeo_id, titulo) VALUES (?, ?) RETURNING id",
                Integer.class, vimeoId, titulo);
    }

    protected int criarItem(int submoduloId, int videoId, String nome, int ordem, String status) {
        return jdbc.queryForObject("""
                INSERT INTO items (submodulo_id, video_id, nome, ordem, status)
                VALUES (?, ?, ?, ?, ?) RETURNING id""",
                Integer.class, submoduloId, videoId, nome, ordem, status);
    }

    protected int idDo(String tabela, String nome) {
        return jdbc.queryForObject(
                "SELECT id FROM " + tabela + " WHERE nome = ? AND removido_em IS NULL", Integer.class, nome);
    }

    /** Questão com as cinco alternativas. Criar questão é do rascunho, não desta onda. */
    protected int criarQuestao(String enunciado, String gabarito, String dificuldade, String status) {
        var id = jdbc.queryForObject("""
                INSERT INTO questions (enunciado, gabarito, dificuldade, status, criado_por_id)
                VALUES (?, ?, ?, ?, ?) RETURNING id""",
                Integer.class, enunciado, gabarito, dificuldade, status, ADMIN);
        for (var letra : new String[] {"A", "B", "C", "D", "E"}) {
            jdbc.update("INSERT INTO question_options (questao_id, letra, texto) VALUES (?, ?, ?)",
                    id, letra, "alternativa " + letra);
        }
        return id;
    }

    protected int criarSimulado(String titulo, String status, String abreEm, String fechaEm) {
        return jdbc.queryForObject("""
                INSERT INTO exams (titulo, status, abre_em, fecha_em, criado_por_id)
                VALUES (?, ?, ?::timestamptz, ?::timestamptz, ?) RETURNING id""",
                Integer.class, titulo, status, abreEm, fechaEm, ADMIN);
    }

    protected void porNaProva(int simulado, int questao, int ordem) {
        jdbc.update("INSERT INTO exam_questions (simulado_id, questao_id, ordem) VALUES (?, ?, ?)",
                simulado, questao, ordem);
    }

    protected void responder(int simulado, int aluno, int questao, boolean correta) {
        var tentativa = jdbc.queryForObject("""
                INSERT INTO exam_attempts (simulado_id, aluno_id, finalizado_em)
                VALUES (?, ?, now())
                ON CONFLICT DO NOTHING RETURNING id""", Integer.class, simulado, aluno);
        jdbc.update("""
                INSERT INTO exam_answers (tentativa_id, questao_id, alternativa_marcada, correta)
                VALUES (?, ?, 'A', ?)""", tentativa, questao, correta);
    }

    protected int contar(String sql, Object... args) {
        return jdbc.queryForObject("SELECT count(*) FROM " + sql, Integer.class, args);
    }
}
