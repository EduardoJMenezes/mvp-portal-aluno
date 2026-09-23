package br.com.plataforma.aulas;

import br.com.plataforma.comum.Canal;
import br.com.plataforma.comum.Identidade;
import br.com.plataforma.contas.ContasServico;
import br.com.plataforma.estrutura.EstruturaServico;
import br.com.plataforma.rascunhos.RascunhosServico;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * O que fazer com cada aviso do Zoom, já com a assinatura conferida.
 *
 * <p>A conta é dividida com outra plataforma, e o webhook escuta a conta toda: a primeira coisa é
 * achar a reunião na nossa tabela. Não achou, não é nossa — nada se baixa, nada se apaga.
 *
 * <p>A gravação chega como <b>rascunho</b> no sub-módulo escolhido ao agendar: o professor aprova
 * em Admin › Rascunhos, como qualquer conteúdo. Sem sub-módulo, ela só sobe ao Vimeo e fica no
 * acervo, para entrar no curso quando ele quiser.
 */
@Service
public class EventosDoZoom {

    private static final Logger log = LoggerFactory.getLogger(EventosDoZoom.class);
    private static final DateTimeFormatter DIA = DateTimeFormatter.ofPattern("dd/MM/yyyy")
            .withZone(ZoneId.of("America/Sao_Paulo"));
    /** O arquivo que mostra a tela e quem fala; os outros MP4 são só câmera ou só tela. */
    private static final List<String> PREFERIDOS = List.of(
            "shared_screen_with_speaker_view", "shared_screen_with_gallery_view", "active_speaker",
            "gallery_view", "shared_screen");

    private final AulaRepositorio aulas;
    private final AulaPresencaRepositorio presencas;
    private final Zoom zoom;
    private final EnvioAoVimeo vimeo;
    private final RascunhosServico rascunhos;
    private final EstruturaServico estrutura;
    private final ContasServico contas;
    private final TransactionTemplate tx;

    public EventosDoZoom(AulaRepositorio aulas, AulaPresencaRepositorio presencas, Zoom zoom, EnvioAoVimeo vimeo,
            RascunhosServico rascunhos, EstruturaServico estrutura, ContasServico contas, TransactionTemplate tx) {
        this.aulas = aulas;
        this.presencas = presencas;
        this.zoom = zoom;
        this.vimeo = vimeo;
        this.rascunhos = rascunhos;
        this.estrutura = estrutura;
        this.contas = contas;
        this.tx = tx;
    }

    public void tratar(Map<?, ?> evento) {
        var tipo = texto(evento.get("event"));
        var objeto = mapa(mapa(evento.get("payload")).get("object"));
        var aula = aulas.findFirstByZoomMeetingId(texto(objeto.get("id")));
        if (aula.isEmpty()) {
            log.info("aviso {} de reunião que não é nossa: ignorado", tipo);
            return;
        }
        switch (tipo) {
            case "recording.completed" -> gravacaoPronta(aula.get(), objeto, texto(evento.get("download_token")));
            case "meeting.participant_joined" -> presenca(aula.get(), mapa(objeto.get("participant")), true);
            case "meeting.participant_left" -> presenca(aula.get(), mapa(objeto.get("participant")), false);
            default -> log.info("aviso {} da aula {}: nada a fazer", tipo, aula.get().getId());
        }
    }

    // --- gravação ------------------------------------------------------------

    // ponytail: a primeira gravação da reunião vence; aula reaberta no mesmo id gera outra, que fica
    // só no Zoom. Guardar várias pede uma tabela de gravações.
    private void gravacaoPronta(Aula aula, Map<?, ?> objeto, String downloadToken) {
        var arquivo = escolherArquivo(objeto);
        if (arquivo == null) {
            log.warn("gravação da aula {} sem MP4 pronto: nada a subir", aula.getId());
            return;
        }
        if (Boolean.FALSE.equals(tx.execute(s -> aulas.reservarGravacao(aula.getId()) == 1))) {
            log.info("gravação da aula {} já subiu ou está subindo: aviso repetido ignorado", aula.getId());
            return;
        }
        EnvioAoVimeo.Enviado enviado;
        try {
            enviado = vimeo.enviar("Aula ao vivo — %s — %s".formatted(aula.getTitulo(), DIA.format(aula.getInicioEm())),
                    aula.getDescricao() == null ? "" : aula.getDescricao(),
                    zoom.linkDeDownload(texto(arquivo.get("download_url")), downloadToken));
        } catch (RuntimeException e) {
            // Solta a trava: o Zoom reenvia o aviso, e aí a gravação tenta de novo.
            tx.executeWithoutResult(s -> aulas.liberarGravacao(aula.getId()));
            throw e;
        }
        tx.executeWithoutResult(s -> {
            var itemId = propor(aula, enviado);
            aulas.findById(aula.getId()).orElseThrow().gravacaoChegou(enviado.vimeoId(), itemId);
        });
        log.info("gravação da aula {} no Vimeo: {}", aula.getId(), enviado.vimeoId());
    }

    private static Map<?, ?> escolherArquivo(Map<?, ?> objeto) {
        var arquivos = objeto.get("recording_files") instanceof List<?> l ? l : List.of();
        return arquivos.stream().map(EventosDoZoom::mapa)
                .filter(a -> "MP4".equalsIgnoreCase(texto(a.get("file_type"))))
                .filter(a -> !texto(a.get("download_url")).isEmpty())
                .min(Comparator.comparingInt((Map<?, ?> a) -> {
                    var i = PREFERIDOS.indexOf(texto(a.get("recording_type")));
                    return i < 0 ? PREFERIDOS.size() : i;
                }))
                .orElse(null);
    }

    /** O item em rascunho, no nome de quem agendou a aula. Sem destino vivo, não há item. */
    private Integer propor(Aula aula, EnvioAoVimeo.Enviado enviado) {
        var sub = estrutura.submodulo(aula.getSubmoduloId()).orElse(null);
        if (sub == null || sub.getModulo() == null) {
            return null;
        }
        var dono = contas.buscar(aula.getCriadoPorId()).orElse(null);
        if (dono == null || !dono.getPapel().eOperador()) {
            log.warn("aula {}: quem agendou não é mais operador; a gravação fica só no acervo", aula.getId());
            return null;
        }
        var ident = new Identidade(dono.getId(), dono.getNome(), dono.getEmail(), dono.getPapel(), Canal.ZOOM);
        var modulo = sub.getModulo();
        var importados = rascunhos.importarVideosComoItens(ident, modulo.getTurma(), modulo.getNome(), sub.getNome(),
                List.of(new RascunhosServico.VideoParaImportar(enviado.vimeoId(), aula.getTitulo(), aula.getTitulo(),
                        enviado.url(), enviado.embedUrl(), null, null, null, null, null)));
        return estrutura.itensDoRascunho(importados.rascunho().getId(), false).getFirst().getId();
    }

    // --- presença ------------------------------------------------------------

    /** Só quem entrou pelo link do portal tem linha aqui; o professor e convidados não contam. */
    private void presenca(Aula aula, Map<?, ?> participante, boolean entrando) {
        var email = texto(participante.get("email"));
        var quando = texto(participante.get(entrando ? "join_time" : "leave_time"));
        if (email.isEmpty() || quando.isEmpty()) {
            return;
        }
        tx.executeWithoutResult(s -> presencas.daAulaPorEmail(aula.getId(), email).ifPresent(p -> {
            var instante = Instant.parse(quando);
            if (entrando) {
                p.entrou(instante);
            } else {
                p.saiu(instante);
            }
        }));
    }

    private static Map<?, ?> mapa(Object v) {
        return v instanceof Map<?, ?> m ? m : Map.of();
    }

    private static String texto(Object v) {
        return v == null ? "" : String.valueOf(v);
    }
}
