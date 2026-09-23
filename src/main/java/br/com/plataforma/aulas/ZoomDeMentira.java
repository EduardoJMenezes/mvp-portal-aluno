package br.com.plataforma.aulas;

import br.com.plataforma.contas.Senhas;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Zoom que não existe, para a POC rodar e os testes não tocarem a rede. Sem credencial, o portal
 * funciona inteiro — agenda, entra, grava — só que a sala é de brincadeira.
 */
public class ZoomDeMentira implements Zoom {

    private long proximo = 8_000_000_001L;
    final Map<String, String> aulas = new ConcurrentHashMap<>();

    @Override
    public synchronized Sala criarAula(String titulo, Instant inicio, int minutos, String descricao, boolean gravar) {
        var id = String.valueOf(proximo++);
        aulas.put(id, titulo);
        return new Sala(id, "https://zoom.example/j/" + id, "123456");
    }

    @Override
    public void editarAula(String meetingId, String titulo, Instant inicio, int minutos) {
        exigir(meetingId);
        aulas.put(meetingId, titulo);
    }

    @Override
    public void cancelarAula(String meetingId) {
        exigir(meetingId);
        aulas.remove(meetingId);
    }

    @Override
    public String linkDeInicio(String meetingId) {
        exigir(meetingId);
        return "https://zoom.example/s/" + meetingId + "?zak=de-mentira";
    }

    @Override
    public String inscrever(String meetingId, String nome, String sobrenome, String email) {
        exigir(meetingId);
        return "https://zoom.example/j/" + meetingId + "?tk=" + Senhas.sha256(email).substring(0, 12);
    }

    @Override
    public String linkDeDownload(String downloadUrl, String downloadToken) {
        return Zoom.comToken(downloadUrl, downloadToken == null || downloadToken.isBlank() ? "de-mentira" : downloadToken);
    }

    private void exigir(String meetingId) {
        if (!aulas.containsKey(meetingId)) {
            throw new br.com.plataforma.comum.ServicoExterno(
                    "Aula %s não existe neste Zoom de mentira.".formatted(meetingId));
        }
    }
}
