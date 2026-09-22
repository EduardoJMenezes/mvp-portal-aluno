package br.com.plataforma.portal;

import static org.springframework.http.MediaType.APPLICATION_JSON;

import br.com.plataforma.BaseDeComando;
import br.com.plataforma.contas.Senhas;
import jakarta.servlet.http.Cookie;
import java.time.Instant;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;

/**
 * Cenário do portal: os mesmos dois usuários de sempre, mas com senha de verdade e o aluno
 * matriculado no Extensivo 2027. Cada pedido entra pelo cookie de sessão, como o navegador.
 */
public abstract class BaseDoPortal extends BaseDeComando {

    protected static final String SENHA = "segredo-forte-2027";

    @Autowired protected Sessoes sessoes;
    @Autowired protected br.com.plataforma.contas.ContasServico contas;

    protected int criarUsuario(String nome, String email, String senha, String papel, boolean temporaria) {
        return jdbc.queryForObject("""
                INSERT INTO users (nome, email, senha_hash, papel, senha_temporaria)
                VALUES (?, ?, ?, ?, ?) RETURNING id""",
                Integer.class, nome, email, Senhas.hash(senha), papel, temporaria);
    }

    protected void matricular(int usuario, int turma) {
        jdbc.update("INSERT INTO enrollments (usuario_id, turma_id) VALUES (?, ?)", usuario, turma);
    }

    /** Os dois de sempre passam a ter senha e o aluno entra na turma 10. */
    protected void comSenhas() {
        jdbc.update("UPDATE users SET senha_hash = ?", Senhas.hash(SENHA));
        matricular(ALUNO, 10);
    }

    protected String jwt(int usuario) {
        return sessoes.criar(contas.buscar(usuario).orElseThrow(), Instant.now());
    }

    protected Cookie sessao(int usuario) {
        return new Cookie(FiltroDaSessao.COOKIE, jwt(usuario));
    }

    /** O construtor cru, para quem monta o pedido à mão (sem cookie, com header). */
    protected static MockHttpServletRequestBuilder get(String rota) {
        return MockMvcRequestBuilders.get(rota);
    }

    protected static MockHttpServletRequestBuilder post(String rota) {
        return MockMvcRequestBuilders.post(rota);
    }

    protected ResultActions get(String rota, int usuario) throws Exception {
        return mvc.perform(MockMvcRequestBuilders.get(rota).cookie(sessao(usuario)));
    }

    protected MockHttpServletRequestBuilder json(MockHttpServletRequestBuilder pedido, String corpo, int usuario) {
        return pedido.cookie(sessao(usuario)).contentType(APPLICATION_JSON).content(corpo);
    }

    protected ResultActions post(String rota, String corpo, int usuario) throws Exception {
        return mvc.perform(json(MockMvcRequestBuilders.post(rota), corpo, usuario));
    }

    protected ResultActions patch(String rota, String corpo, int usuario) throws Exception {
        return mvc.perform(json(MockMvcRequestBuilders.patch(rota), corpo, usuario));
    }

    protected ResultActions put(String rota, String corpo, int usuario) throws Exception {
        return mvc.perform(json(MockMvcRequestBuilders.put(rota), corpo, usuario));
    }

    protected ResultActions delete(String rota, int usuario) throws Exception {
        return mvc.perform(MockMvcRequestBuilders.delete(rota).cookie(sessao(usuario)));
    }

    /** Login de verdade, sem cookie: o que a tela de entrada faz. */
    protected ResultActions login(String email, String senha) throws Exception {
        return mvc.perform(MockMvcRequestBuilders.post("/api/login").contentType(APPLICATION_JSON)
                .content("{\"email\": \"%s\", \"senha\": \"%s\"}".formatted(email, senha)));
    }

    protected int criarSimuladoAberto(String titulo, int turma, int... questoes) {
        var id = criarSimulado(titulo, "PUBLICADO", "2020-01-01T00:00:00Z", "2099-01-01T00:00:00Z");
        jdbc.update("UPDATE exams SET duracao_minutos = 60 WHERE id = ?", id);
        jdbc.update("INSERT INTO exam_classes (simulado_id, turma_id) VALUES (?, ?)", id, turma);
        for (int i = 0; i < questoes.length; i++) {
            porNaProva(id, questoes[i], i + 1);
        }
        return id;
    }
}
