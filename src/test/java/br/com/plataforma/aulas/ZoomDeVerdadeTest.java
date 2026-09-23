package br.com.plataforma.aulas;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import br.com.plataforma.comum.ServicoExterno;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

/**
 * O cliente contra o Zoom de verdade — desligado, a menos que {@code ZOOM_TESTE_REAL=sim}. Cria UMA
 * reunião "[TESTE PORTAL]", mexe nela e a apaga pelo id. A conta é dividida com outra plataforma:
 * rode longe de terça 17h e quarta 19h, com as credenciais do Railway injetadas:
 *
 * <pre>railway run --service app -- bash -c 'unset DATABASE_URL; ZOOM_TESTE_REAL=sim ./mvnw -q test -Dtest=ZoomDeVerdadeTest'</pre>
 *
 * <p>Com {@code VIMEO_TESTE_REAL=sim}, também manda o Vimeo buscar um vídeo público curto por link —
 * o mesmo pedido que a gravação faz. Esse cria um vídeo "[TESTE PORTAL] apagar" na conta, que fica
 * para o professor apagar.
 */
class ZoomDeVerdadeTest {

    @Test
    @EnabledIfEnvironmentVariable(named = "ZOOM_TESTE_REAL", matches = "sim")
    void criaInscreveDoisAlunosComLinksDiferentesEApaga() {
        var zoom = new ZoomReal(System.getenv("ZOOM_ACCOUNT_ID"), System.getenv("ZOOM_CLIENT_ID"),
                System.getenv("ZOOM_CLIENT_SECRET"), System.getenv("ZOOM_HOST"), ZoomReal.BASE);
        var inicio = Instant.now().plus(10, ChronoUnit.MINUTES);
        var sala = zoom.criarAula("[TESTE PORTAL] apagar — teste automático", inicio, 10,
                "Criada e apagada pelo teste do portal.", true);
        try {
            assertThat(sala.id()).matches("\\d+");
            var ana = zoom.inscrever(sala.id(), "Aluna", "Teste Um", "aluna.teste1.portal@example.com");
            var bia = zoom.inscrever(sala.id(), "Aluna", "Teste Dois", "aluna.teste2.portal@example.com");
            assertThat(ana).contains(sala.id()).isNotEqualTo(bia);
            assertThat(zoom.linkDeInicio(sala.id())).startsWith("https://");
            zoom.editarAula(sala.id(), "[TESTE PORTAL] apagar — horário mudado", inicio.plus(5, ChronoUnit.MINUTES), 10);
        } finally {
            zoom.cancelarAula(sala.id());
        }
        // Apagada de fato: o Zoom não acha mais a reunião.
        assertThatThrownBy(() -> zoom.linkDeInicio(sala.id())).isInstanceOf(ServicoExterno.class);
    }

    @Test
    @EnabledIfEnvironmentVariable(named = "VIMEO_TESTE_REAL", matches = "sim")
    void oVimeoBuscaOVideoPeloLink() {
        var vimeo = new EnvioAoVimeo.Real(System.getenv("VIMEO_ACCESS_TOKEN"), "https://api.vimeo.com");
        var enviado = vimeo.enviar("[TESTE PORTAL] apagar — envio por link", "Teste do cano da gravação.",
                "https://www.w3schools.com/html/mov_bbb.mp4");
        System.out.println("VIDEO_DE_TESTE=" + enviado.vimeoId());
        assertThat(enviado.vimeoId()).matches("\\d+");
        assertThat(enviado.embedUrl()).startsWith("https://player.vimeo.com/video/" + enviado.vimeoId());
    }
}
