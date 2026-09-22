package br.com.plataforma.aulas;

import br.com.plataforma.catalogo.Turma;
import br.com.plataforma.comum.Identidade;
import br.com.plataforma.comum.NaoAutorizado;
import br.com.plataforma.comum.NaoEncontrado;
import br.com.plataforma.comum.RegraDeNegocio;
import br.com.plataforma.comum.Status;
import br.com.plataforma.contas.ContasServico;
import br.com.plataforma.contas.Pessoa;
import br.com.plataforma.contas.Usuario;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Aulas ao vivo: quem alcança é o backend que decide (§11); o link de entrar é pessoal e nasce no
 * clique; e só mexemos no que é nosso — toda chamada ao Zoom leva o id que gravamos ao publicar.
 */
@Service
public class AulasServico {

    /** A porta abre antes de a aula começar e fecha um tempo depois do fim. */
    public static final Duration ABRE_ANTES = Duration.ofMinutes(15);
    public static final Duration FECHA_DEPOIS = Duration.ofMinutes(30);
    public static final int DURACAO_MAXIMA = 8 * 60;

    public enum Estado { RASCUNHO, AGENDADA, ABERTA, ENCERRADA }

    private final AulaRepositorio aulas;
    private final AulaPresencaRepositorio presencas;
    private final ContasServico contas;
    private final Zoom zoom;

    public AulasServico(AulaRepositorio aulas, AulaPresencaRepositorio presencas, ContasServico contas,
            Zoom zoom) {
        this.aulas = aulas;
        this.presencas = presencas;
        this.contas = contas;
        this.zoom = zoom;
    }

    @Transactional(readOnly = true)
    public Aula exigir(String referencia) {
        var texto = referencia == null ? "" : referencia.strip();
        var achada = !texto.isEmpty() && texto.length() <= 9 && texto.chars().allMatch(Character::isDigit)
                ? aulas.findById(Integer.parseInt(texto)) : java.util.Optional.<Aula>empty();
        return achada.orElseThrow(() -> new NaoEncontrado("Aula '%s' não existe.".formatted(referencia)));
    }

    private boolean alcanca(Identidade ident, Aula a) {
        if (a.getStatus() != Status.PUBLICADO) {
            return false;
        }
        if (a.getAlunos().stream().anyMatch(x -> x.getId().equals(ident.usuarioId()))) {
            return true;
        }
        var minhas = contas.turmasDoAluno(ident.usuarioId());
        return a.getTurmas().stream().anyMatch(t -> minhas.contains(t.getId()));
    }

    private void exigirAcesso(Identidade ident, Aula a) {
        if (ident.eOperador() || alcanca(ident, a)) {
            return;
        }
        throw new NaoAutorizado("'%s' não está liberada para %s.".formatted(a.getTitulo(), ident.nome()));
    }

    private static Instant fim(Aula a) {
        return a.getInicioEm().plus(Duration.ofMinutes(a.getMinutos()));
    }

    public static Estado estado(Aula a, Instant agora) {
        if (a.getStatus() != Status.PUBLICADO) {
            return Estado.RASCUNHO;
        }
        if (agora.isAfter(fim(a).plus(FECHA_DEPOIS))) {
            return Estado.ENCERRADA;
        }
        if (!agora.isBefore(a.getInicioEm().minus(ABRE_ANTES))) {
            return Estado.ABERTA;
        }
        return Estado.AGENDADA;
    }

    public record Resumo(
            Integer aulaId, String titulo, String descricao, String inicioEm, Integer minutos, Status status,
            Estado estado, String abreEm, boolean grava, boolean temSala, List<String> turmas,
            List<Pessoa> alunos, Integer gravacaoItemId) {}

    private static Resumo resumo(Aula a, Instant agora) {
        return new Resumo(a.getId(), a.getTitulo(), a.getDescricao(), a.getInicioEm().toString(), a.getMinutos(),
                a.getStatus(), estado(a, agora), a.getInicioEm().minus(ABRE_ANTES).toString(), a.isGravar(),
                a.getZoomMeetingId() != null, a.getTurmas().stream().map(Turma::getNome).toList(),
                a.getAlunos().stream().map(Pessoa::de).toList(), a.getGravacaoItemId());
    }

    // --- leitura -------------------------------------------------------------

    /** Operador vê todas, inclusive rascunho; aluno, só o que o alcança. */
    @Transactional(readOnly = true)
    public List<Resumo> listar(Identidade ident, Instant agora) {
        return aulas.findAllByOrderByInicioEmDesc().stream()
                .filter(a -> ident.eOperador() || alcanca(ident, a))
                .map(a -> resumo(a, agora)).toList();
    }

    // --- o aluno entrando ----------------------------------------------------

    public record Entrada(Integer aulaId, String titulo, String url) {}

    /**
     * Confere o acesso e a hora, e devolve o link <b>daquele</b> aluno. A inscrição no Zoom acontece
     * aqui, no clique, e só uma vez por pessoa.
     */
    @Transactional
    public Entrada entrar(Identidade ident, String referencia, Instant agora) {
        var a = exigir(referencia);
        exigirAcesso(ident, a);
        if (a.getStatus() != Status.PUBLICADO || a.getZoomMeetingId() == null) {
            throw new RegraDeNegocio("'%s' ainda não foi aberta pelo professor.".formatted(a.getTitulo()));
        }
        var estado = estado(a, agora);
        if (estado == Estado.AGENDADA) {
            throw new RegraDeNegocio("A sala abre 15 minutos antes, às %s."
                    .formatted(a.getInicioEm().minus(ABRE_ANTES).toString()));
        }
        if (estado == Estado.ENCERRADA) {
            throw new RegraDeNegocio("'%s' já terminou.".formatted(a.getTitulo()));
        }
        var presenca = presencas.findByAulaIdAndUsuarioId(a.getId(), ident.usuarioId()).orElseGet(() -> {
            var nomeInteiro = ident.nome() == null || ident.nome().isBlank() ? "Aluno" : ident.nome().strip();
            var corte = nomeInteiro.indexOf(' ');
            var nome = corte < 0 ? nomeInteiro : nomeInteiro.substring(0, corte);
            var sobrenome = corte < 0 ? "." : nomeInteiro.substring(corte + 1).strip();
            var link = zoom.inscrever(a.getZoomMeetingId(), nome, sobrenome, ident.email());
            return presencas.save(new AulaPresenca(a.getId(), ident.usuarioId(), link));
        });
        return new Entrada(a.getId(), a.getTitulo(), presenca.getJoinUrl());
    }

    public record LinkDoProfessor(Integer aulaId, String url) {}

    /** O link de iniciar, buscado na hora — o do Zoom expira em duas horas. */
    @Transactional(readOnly = true)
    public LinkDoProfessor linkDoProfessor(Identidade ident, String referencia) {
        ident.exigirOperador();
        var a = exigir(referencia);
        if (a.getZoomMeetingId() == null) {
            throw new RegraDeNegocio("'%s' ainda não tem sala: publique a aula primeiro.".formatted(a.getTitulo()));
        }
        return new LinkDoProfessor(a.getId(), zoom.linkDeInicio(a.getZoomMeetingId()));
    }

    // --- o professor montando ------------------------------------------------

    public record Dados(
            String titulo, Instant inicioEm, Integer minutos, String descricao, Boolean gravar,
            List<Turma> turmas, List<String> alunos, Integer submoduloId, Boolean publicarGravacao,
            String status) {}

    /** Nasce em rascunho, <b>sem sala no Zoom</b>: a sala só é criada ao publicar. */
    @Transactional
    public Resumo criar(Identidade ident, Dados d, Instant agora) {
        ident.exigirOperador();
        var titulo = d.titulo() == null ? "" : d.titulo().strip();
        if (titulo.isEmpty()) {
            throw new RegraDeNegocio("A aula precisa de um título.");
        }
        var minutos = d.minutos() == null ? 60 : d.minutos();
        validarHorario(d.inicioEm(), minutos);
        var descricao = d.descricao() == null || d.descricao().isBlank() ? null : d.descricao().strip();
        var a = aulas.save(new Aula(titulo, descricao, d.inicioEm(), minutos,
                d.gravar() == null || d.gravar(), d.submoduloId(),
                d.publicarGravacao() == null || d.publicarGravacao(), ident.usuarioId()));
        enderecar(a, d.turmas(), d.alunos());
        return resumo(a, agora);
    }

    /** Título, horário, quem alcança, e abrir ou fechar a sala. */
    @Transactional
    public Resumo editar(Identidade ident, String referencia, Dados d, Instant agora) {
        ident.exigirOperador();
        var a = exigir(referencia);
        if (d.titulo() != null) {
            if (d.titulo().isBlank()) {
                throw new RegraDeNegocio("A aula precisa de um título.");
            }
            a.mudarTitulo(d.titulo().strip());
        }
        a.mudarHorario(d.inicioEm(), d.minutos());
        a.mudarGravacao(d.gravar(), d.submoduloId(), d.publicarGravacao());
        validarHorario(a.getInicioEm(), a.getMinutos());
        if (d.turmas() != null || d.alunos() != null) {
            enderecar(a, d.turmas(), d.alunos());
        }
        if (d.status() != null) {
            mudarStatus(a, d.status().strip().toUpperCase(Locale.ROOT), agora);
        } else if (a.getZoomMeetingId() != null) {
            // Sala já aberta: o Zoom precisa saber do novo horário.
            zoom.editarAula(a.getZoomMeetingId(), a.getTitulo(), a.getInicioEm(), a.getMinutos());
        }
        a.tocar(ident);
        return resumo(a, agora);
    }

    public record Removida(Integer aulaId, String titulo, boolean reversivel) {}

    /** Some do portal e a sala do Zoom é desmarcada — a nossa, pelo id nosso. */
    @Transactional
    public Removida remover(Identidade ident, String referencia) {
        ident.exigirOperador();
        var a = exigir(referencia);
        if (a.getZoomMeetingId() != null) {
            zoom.cancelarAula(a.getZoomMeetingId());
            a.fecharSala();
        }
        a.remover(ident);
        return new Removida(a.getId(), a.getTitulo(), true);
    }

    // --- apoio ---------------------------------------------------------------

    private static void validarHorario(Instant inicioEm, int minutos) {
        if (inicioEm == null) {
            throw new RegraDeNegocio("O horário da aula precisa de fuso.");
        }
        if (minutos < 5 || minutos > DURACAO_MAXIMA) {
            throw new RegraDeNegocio("Duração fora do razoável: de 5 a %d minutos.".formatted(DURACAO_MAXIMA));
        }
    }

    private void enderecar(Aula a, List<Turma> turmas, List<String> alunos) {
        if (turmas != null) {
            var unicas = new LinkedHashMap<Integer, Turma>();
            turmas.forEach(t -> unicas.putIfAbsent(t.getId(), t));
            a.getTurmas().clear();
            a.getTurmas().addAll(unicas.values());
        }
        if (alunos != null) {
            var unicos = new LinkedHashMap<Integer, Usuario>();
            alunos.forEach(ref -> {
                var u = contas.resolverAluno(ref);
                unicos.putIfAbsent(u.getId(), u);
            });
            a.getAlunos().clear();
            a.getAlunos().addAll(unicos.values());
        }
        aulas.saveAndFlush(a);
    }

    /** Publicar abre a sala no Zoom; tirar do ar desmarca. */
    private void mudarStatus(Aula a, String alvo, Instant agora) {
        if (!alvo.equals("RASCUNHO") && !alvo.equals("PUBLICADO")) {
            throw new RegraDeNegocio("Status da aula: RASCUNHO ou PUBLICADO.");
        }
        if (alvo.equals("PUBLICADO") && a.getStatus() != Status.PUBLICADO) {
            if (a.getTurmas().isEmpty() && a.getAlunos().isEmpty()) {
                throw new RegraDeNegocio(("'%s' não alcança ninguém: escolha uma turma ou um aluno antes "
                        + "de publicar.").formatted(a.getTitulo()));
            }
            var sala = a.getZoomMeetingId() != null ? null
                    : zoom.criarAula(a.getTitulo(), a.getInicioEm(), a.getMinutos(),
                            a.getDescricao() == null ? "" : a.getDescricao(), a.isGravar());
            if (sala != null) {
                a.abrirSala(sala.id(), sala.joinUrl(), agora);
            } else {
                a.mudarStatus(Status.PUBLICADO);
            }
        }
        if (alvo.equals("RASCUNHO")) {
            if (a.getZoomMeetingId() != null) {
                zoom.cancelarAula(a.getZoomMeetingId());
                a.fecharSala();
                // Link pessoal de sala que não existe mais é lixo que enganaria o aluno.
                presencas.apagarDaAula(a.getId());
            }
            a.mudarStatus(Status.RASCUNHO);
        }
    }
}
