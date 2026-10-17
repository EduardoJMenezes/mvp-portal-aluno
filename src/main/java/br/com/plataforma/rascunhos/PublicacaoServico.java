package br.com.plataforma.rascunhos;

import static java.util.stream.Collectors.joining;

import br.com.plataforma.comum.AprovacaoNecessaria;
import br.com.plataforma.comum.Canal;
import br.com.plataforma.comum.Identidade;
import br.com.plataforma.comum.NaoEncontrado;
import br.com.plataforma.comum.RegraDeNegocio;
import br.com.plataforma.comum.Relogio;
import br.com.plataforma.comum.Status;
import br.com.plataforma.contas.ContasServico;
import br.com.plataforma.estrutura.EstruturaServico;
import br.com.plataforma.estrutura.Item;
import br.com.plataforma.questoes.QuestoesServico;
import br.com.plataforma.simulados.SimuladosServico;
import java.time.Instant;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * A regra que sustenta a POC: <b>a IA propõe, o humano aprova, o backend publica.</b>
 *
 * <p>Questão e simulado não chegam ao aluno sem aprovação humana gravada em
 * {@code drafts.aprovado_por_id}. Não é checagem no cliente: é estado no banco, conferido a cada
 * publicação.
 *
 * <p><b>A exceção é a montagem do curso</b> (17/10/2026): vídeo e linha de aula que o Claude põe
 * num sub-módulo saem publicados na hora, e o professor confere pelo link do módulo, com a conta
 * de administrador. A linha que não pode ir para a aula fica em rascunho, com o motivo. Ver
 * {@link #publicarLinhas}.
 */
@Service
public class PublicacaoServico {

    private final RascunhosServico rascunhos;
    private final ContasServico contas;
    private final EstruturaServico estrutura;
    private final QuestoesServico questoes;
    private final SimuladosServico simulados;

    public PublicacaoServico(RascunhosServico rascunhos, ContasServico contas,
            EstruturaServico estrutura, QuestoesServico questoes, SimuladosServico simulados) {
        this.rascunhos = rascunhos;
        this.contas = contas;
        this.estrutura = estrutura;
        this.questoes = questoes;
        this.simulados = simulados;
    }

    /**
     * Aprovação feita por um professor logado no portal.
     *
     * <p>{@code exigirHumanoNoPortal} é o que impede um agente com credencial de MCP de aprovar o
     * que ele mesmo propôs.
     */
    @Transactional
    public Rascunho aprovarPeloPortal(Identidade ident, Integer rascunhoId) {
        ident.exigirOperador();
        ident.exigirHumanoNoPortal("Aprovar um rascunho");

        var r = rascunhos.exigir(rascunhoId);
        if (r.getStatus() == Status.PUBLICADO) {
            throw new RegraDeNegocio("Rascunho %d já foi publicado.".formatted(rascunhoId));
        }
        marcarAprovado(ident, r, ViaAprovacao.PORTAL);
        return r;
    }

    /**
     * O que o professor monta no portal já sai publicado: quem cria é o humano que aprovaria.
     *
     * <p>Não é atalho por fora da regra. A proposta nasce, é aprovada por ele e publicada no mesmo
     * ato, e a aprovação fica gravada como qualquer outra — só não há uma segunda tela para
     * clicar. O canal MCP não passa daqui ({@link #aprovarPeloPortal}).
     */
    @Transactional
    public Publicacao publicarPeloPortal(Identidade ident, Integer rascunhoId, Instant agora) {
        aprovarPeloPortal(ident, rascunhoId);
        return publicar(ident, rascunhoId, null, agora);
    }

    /**
     * Grava a aprovação obtida pela confirmação do cliente MCP.
     *
     * <p>Chamada só depois de o usuário aceitar o formulário — nunca por uma tool. O modelo não
     * tem como invocar isto: o que ele controla é o pedido de publicação, e o aceite vem do
     * cliente.
     */
    @Transactional
    public void registrarConfirmacaoDoClienteMcp(Identidade ident, Integer rascunhoId) {
        marcarAprovado(ident, rascunhos.exigir(rascunhoId), ViaAprovacao.ELICITATION_MCP);
    }

    /**
     * Publica o simulado em rascunho, a pedido do professor que o conferiu na página do próprio
     * simulado: é a aprovação e a publicação do rascunho dele, sem a tela de Rascunhos no meio.
     * O que barra é o mesmo de sempre ({@code pendenciasParaPublicar}).
     */
    @Transactional
    public Publicacao publicarSimulado(Identidade ident, String referencia, Instant agora) {
        ident.exigirOperador();
        ident.exigirHumanoNoPortal("Publicar um simulado");

        var s = simulados.resolver(referencia);
        if (s.getStatus() == Status.PUBLICADO) {
            throw new RegraDeNegocio("'%s' já está publicado.".formatted(s.getTitulo()));
        }
        if (s.getRascunhoId() == null) {
            throw new RegraDeNegocio(
                    "'%s' não nasceu de uma proposta e não tem como ser publicado por aqui.".formatted(s.getTitulo()));
        }
        return publicarPeloPortal(ident, s.getRascunhoId(), agora);
    }

    /**
     * Publica <b>uma</b> questão em rascunho, a pedido do professor que a conferiu no Banco de
     * questões.
     *
     * <p>É o caminho da questão avulsa que o Claude escreve (de um print, por exemplo): ela nasce
     * num rascunho só dela, e publicar a questão aprova e fecha esse rascunho, com a aprovação
     * gravada como em qualquer outro. Num rascunho com mais de uma coisa pendente, só esta questão
     * sai: o rascunho continua aberto <b>e sem aprovação</b>, para o "sim" dado a uma questão não
     * virar autorização de publicar as outras pelo chat.
     *
     * <p>Questão de simulado em rascunho ou de linha de aula em rascunho não sai sozinha: publicada
     * sem a prova ou sem a linha, ela ficaria no acervo dizendo estar num lugar que o aluno não vê.
     */
    @Transactional
    public void publicarQuestao(Identidade ident, String referencia, Instant agora) {
        ident.exigirOperador();
        ident.exigirHumanoNoPortal("Publicar uma questão");

        var q = questoes.resolver(referencia);
        if (q.getStatus() == Status.PUBLICADO) {
            throw new RegraDeNegocio("A questão %d já está publicada.".formatted(q.getId()));
        }
        if (!q.completa()) {
            throw new RegraDeNegocio(
                    "A questão %d ainda não pode ser publicada: faltam alternativas de A a D ou o gabarito."
                            .formatted(q.getId()));
        }
        if (q.isImagemPendente()) {
            throw new RegraDeNegocio(
                    ("A questão %d ainda está com imagem pendente. Insira a figura no texto, ou "
                            + "desmarque \"imagem pendente\" se ela não tem figura.").formatted(q.getId()));
        }

        var r = q.getRascunhoId() == null ? null : rascunhos.exigir(q.getRascunhoId());
        if (r == null) {
            questoes.publicar(q);
            return;
        }
        var simulado = rascunhos.simuladoDoRascunho(r.getId());
        if (simulado != null && simulado.getStatus() != Status.PUBLICADO) {
            throw new RegraDeNegocio(
                    "A questão %d é do simulado '%s', que está em rascunho: ela é publicada junto com ele."
                            .formatted(q.getId(), simulado.getTitulo()));
        }
        var pendentes = estrutura.itensDoRascunho(r.getId(), true);
        var linha = pendentes.stream()
                .filter(i -> i.getQuestao() != null && i.getQuestao().getId().equals(q.getId())).findFirst();
        if (linha.isPresent()) {
            throw new RegraDeNegocio(
                    "A questão %d é a linha '%s' de uma aula que está em rascunho: ela é publicada junto com a linha."
                            .formatted(q.getId(), linha.get().getNome()));
        }

        questoes.publicar(q);
        var sobrou = !pendentes.isEmpty() || rascunhos.questoesDoRascunho(r.getId()).stream()
                .anyMatch(outra -> outra.getStatus() != Status.PUBLICADO);
        if (!sobrou && r.getStatus() != Status.PUBLICADO) {
            marcarAprovado(ident, r, ViaAprovacao.PORTAL);
            r.publicar(agora);
        }
    }

    /** A linha que ficou em rascunho, e por quê. */
    public record LinhaRetida(Integer itemId, String nome, String motivo) {}

    public record Montagem(int publicadas, List<LinhaRetida> retidas) {}

    /**
     * Publica as linhas de aula pendentes deste rascunho, sem pedir aprovação: é a montagem do
     * curso. Só rascunho de vídeos e linhas de aula passa por aqui.
     */
    @Transactional
    public Montagem publicarMontagem(Identidade ident, Integer rascunhoId, Instant agora) {
        var r = rascunhos.exigir(rascunhoId);
        if (r.getTipo() != TipoRascunho.ITENS) {
            throw new RegraDeNegocio(
                    "O rascunho %d não é de vídeos ou linhas de aula: ele é publicado onde mora.".formatted(rascunhoId));
        }
        return publicarLinhas(ident, estrutura.itensDoRascunho(r.getId(), true), agora);
    }

    /**
     * Publica linhas de aula em rascunho. É a porta da montagem do curso — o Claude pondo vídeos e
     * questões num sub-módulo, ou o professor clicando "Publicar" no editor do curso.
     *
     * <p>A linha de questão sem as alternativas A-D ou com figura pendente não vai para a aula:
     * fica em rascunho e volta em {@code retidas}, sem segurar as outras. A questão nova que nasceu
     * no rascunho da linha sai junto com ela. Quando o rascunho não tem mais nada pendente, ele é
     * fechado, com quem estava operando gravado.
     */
    @Transactional
    public Montagem publicarLinhas(Identidade ident, List<Item> linhas, Instant agora) {
        ident.exigirOperador();
        var publicadas = 0;
        var retidas = new java.util.ArrayList<LinhaRetida>();
        var tocados = new java.util.LinkedHashSet<Integer>();
        for (var item : linhas) {
            if (item.getStatus() == Status.PUBLICADO) {
                continue;
            }
            // Conferido antes, e não pela exceção de publicarItem: exceção que atravessa um
            // @Transactional marca a transação inteira para desfazer, e as outras linhas iriam junto.
            var motivo = porQueNaoVaiParaAAula(item);
            if (motivo != null) {
                retidas.add(new LinhaRetida(item.getId(), item.getNome(), motivo));
                continue;
            }
            estrutura.publicarItem(ident, item);
            var questao = item.getQuestao();
            if (questao != null && questao.getStatus() == Status.RASCUNHO
                    && item.getRascunhoId() != null && item.getRascunhoId().equals(questao.getRascunhoId())) {
                questoes.publicar(questao);
            }
            if (item.getRascunhoId() != null) {
                tocados.add(item.getRascunhoId());
            }
            publicadas++;
        }
        tocados.forEach(id -> fecharSeAcabou(ident, rascunhos.exigir(id), agora));
        return new Montagem(publicadas, retidas);
    }

    private static String porQueNaoVaiParaAAula(Item item) {
        var questao = item.getQuestao();
        if (questao == null) {
            return null;
        }
        if (!questao.completa()) {
            return "a questão está sem as alternativas A-D ou sem o gabarito";
        }
        if (questao.isImagemPendente()) {
            return "a questão está com figura pendente";
        }
        return null;
    }

    private void fecharSeAcabou(Identidade ident, Rascunho r, Instant agora) {
        if (r.getStatus() == Status.PUBLICADO || !estrutura.itensDoRascunho(r.getId(), true).isEmpty()
                || rascunhos.questoesDoRascunho(r.getId()).stream().anyMatch(q -> q.getStatus() != Status.PUBLICADO)) {
            return;
        }
        if (!r.temAprovacaoHumana()) {
            marcarAprovado(ident, r, ident.canal() == Canal.PORTAL ? ViaAprovacao.PORTAL : ViaAprovacao.MONTAGEM_DO_CURSO);
        }
        r.publicar(agora);
    }

    /** O recado da montagem para quem chamou: o que saiu e o que ficou, linha por linha. */
    public static String recado(Montagem m, String turma) {
        var texto = m.publicadas() == 0
                ? "Nada foi publicado."
                : "Publicado: %d linha(s) já aparecem para os alunos%s.".formatted(m.publicadas(),
                        turma == null ? " das turmas do módulo" : " de " + turma);
        if (!m.retidas().isEmpty()) {
            texto += " Ficaram em rascunho: " + m.retidas().stream()
                    .map(l -> "'%s' (%s)".formatted(l.nome(), l.motivo())).collect(joining("; "))
                    + ". Acerte a questão e publique de novo.";
        }
        return texto;
    }

    private void marcarAprovado(Identidade ident, Rascunho r, ViaAprovacao via) {
        var quem = contas.buscar(ident.usuarioId())
                .orElseThrow(() -> new NaoEncontrado("Esta conta não existe mais."));
        r.aprovar(quem, via, Instant.now());
    }

    /**
     * O texto que o humano lê antes de decidir. <b>Sempre gerado pelo backend.</b>
     *
     * <p>Não é detalhe de apresentação: se o modelo redigisse o resumo, ele escolheria o que
     * contar sobre a própria proposta — e a aprovação deixaria de ser informada.
     */
    @Transactional(readOnly = true)
    public String resumoParaConfirmacao(Identidade ident, Integer rascunhoId, Instant agora) {
        var d = rascunhos.detalhar(ident, rascunhoId, agora);
        var linhas = new java.util.ArrayList<String>();
        linhas.add(d.resumo());
        if (d.turma() != null) {
            linhas.add("Turma: " + d.turma());
        }
        if (d.modulo() != null) {
            linhas.add(("Módulo: %s › %s".formatted(d.modulo(),
                    d.submodulo() == null ? "" : d.submodulo())).replaceAll(" ›$", ""));
        }

        d.itens().forEach(item -> linhas.add("  %2d. %s%s".formatted(item.ordem(),
                QuestoesServico.resumo(item.nome(), 70),
                item.status() == Status.PUBLICADO ? "  (já publicado)" : "")));

        d.questoes().forEach(q -> linhas.add("  %s%s".formatted(
                QuestoesServico.resumo(q.enunciado(), 70),
                q.completa() ? "" : "  (sem alternativas A-D)")));

        var simulado = d.simulado();
        if (simulado != null) {
            linhas.add("Turmas: " + String.join(", ", simulado.turmas()));
            linhas.add("Abre %s, fecha %s, %s min de prova".formatted(
                    simulado.abreEm() == null ? "(sem data)" : simulado.abreEm(),
                    simulado.fechaEm() == null ? "(sem data)" : simulado.fechaEm(),
                    simulado.duracaoMinutos() == null ? "?" : simulado.duracaoMinutos()));
            simulado.questoes().forEach(q -> linhas.add("  %d. %s".formatted(
                    q.ordem(), QuestoesServico.resumo(q.enunciado(), 70))));
            if (!simulado.avisos().isEmpty()) {
                linhas.add("");
                linhas.addAll(simulado.avisos());
            }
        }

        linhas.add("");
        linhas.add("Publicar torna este conteúdo visível para os alunos da turma.");
        return String.join("\n", linhas);
    }

    public record Descartado(Integer rascunhoId, boolean descartado) {}

    /**
     * Joga fora uma proposta que o professor não quis.
     *
     * <p>Aqui o DELETE é físico, e de propósito: conteúdo publicado tem remoção lógica porque
     * alguém pode precisar dele de volta, mas um rascunho recusado nunca existiu para ninguém.
     */
    @Transactional
    public Descartado descartar(Identidade ident, Integer rascunhoId) {
        ident.exigirOperador();
        ident.exigirHumanoNoPortal("Descartar um rascunho");
        var r = rascunhos.exigir(rascunhoId);
        if (r.getStatus() == Status.PUBLICADO) {
            throw new RegraDeNegocio("Rascunho já publicado não pode ser descartado.");
        }
        var publicados = estrutura.itensDoRascunho(r.getId(), false).stream()
                .filter(i -> i.getStatus() == Status.PUBLICADO).count();
        if (publicados > 0) {
            throw new RegraDeNegocio(("Este rascunho já teve %d item(ns) publicado(s) e não pode ser "
                    + "descartado. Remova os itens que não quiser mais — a remoção é reversível.")
                    .formatted(publicados));
        }
        rascunhos.apagar(r);
        return new Descartado(rascunhoId, true);
    }

    public record Publicacao(
            Integer rascunhoId, boolean publicado, String turma, int itensPublicados,
            int itensAindaPendentes, int questoesPublicadas, int questoesSemAlternativas,
            String simuladoPublicado, String aprovadoPor, String aprovadoVia, String mensagem) {}

    /**
     * Publica um rascunho <b>já aprovado</b> por um humano — ou de vídeos e linhas de aula, que é
     * montagem do curso e não espera aprovação ({@link #publicarLinhas}).
     *
     * <p>Com {@code itensIds}, libera só aqueles itens e deixa o rascunho aberto para o resto.
     * Sem, publica tudo que está pendente nele.
     *
     * @throws AprovacaoNecessaria se ninguém aprovou — é este erro que a borda MCP usa como
     *     gatilho para pedir a confirmação ao usuário
     */
    @Transactional
    public Publicacao publicar(Identidade ident, Integer rascunhoId, List<Integer> itensIds,
            Instant agora) {
        ident.exigirOperador();
        var r = rascunhos.exigir(rascunhoId);

        if (r.getStatus() == Status.PUBLICADO) {
            throw new RegraDeNegocio(
                    "Rascunho %d já foi publicado por inteiro.".formatted(rascunhoId));
        }
        if (r.getTipo() != TipoRascunho.ITENS && !r.temAprovacaoHumana()) {
            throw new AprovacaoNecessaria(
                    ("O rascunho %d não tem aprovação humana registrada e não pode ser publicado. "
                            + "Peça a confirmação do professor, ou que ele publique pelo portal: o "
                            + "simulado na página dele, a questão no Banco de questões.").formatted(rascunhoId));
        }

        var pendentes = estrutura.itensDoRascunho(r.getId(), true);
        var daProposta = rascunhos.questoesDoRascunho(r.getId());
        var simulado = rascunhos.simuladoDoRascunho(r.getId());

        if (pendentes.isEmpty() && daProposta.isEmpty() && simulado == null) {
            throw new RegraDeNegocio(
                    "Rascunho %d está vazio; não há o que publicar.".formatted(rascunhoId));
        }

        List<Item> escolhidos;
        if (itensIds != null) {
            escolhidos = pendentes.stream().filter(i -> itensIds.contains(i.getId())).toList();
            var desconhecidos = itensIds.stream()
                    .filter(id -> escolhidos.stream().noneMatch(i -> i.getId().equals(id)))
                    .sorted().toList();
            if (!desconhecidos.isEmpty()) {
                var disponiveis = pendentes.isEmpty() ? "nenhum" : pendentes.stream()
                        .map(i -> "%d (%s)".formatted(i.getId(), i.getNome())).collect(joining(", "));
                throw new RegraDeNegocio(
                        "Itens %s não estão pendentes neste rascunho. Pendentes: %s."
                                .formatted(desconhecidos, disponiveis));
            }
        } else {
            escolhidos = pendentes;
        }

        if (r.getTipo() == TipoRascunho.ITENS) {
            var m = publicarLinhas(ident, escolhidos, agora);
            var turma = r.getTurma() == null ? null : r.getTurma().getNome();
            return new Publicacao(r.getId(), r.getStatus() == Status.PUBLICADO, turma, m.publicadas(),
                    estrutura.itensDoRascunho(r.getId(), true).size(), 0, 0, null,
                    r.getAprovadoPor() == null ? null : r.getAprovadoPor().getNome(),
                    r.getAprovadoVia() == null ? null : r.getAprovadoVia().name(), recado(m, turma));
        }

        var publicouResto = itensIds == null;

        // Antes de mudar qualquer status: prova sem agenda ou com figura faltando não chega ao aluno.
        if (simulado != null && publicouResto) {
            var pendencias = simulados.pendenciasParaPublicar(simulado, agora);
            if (!pendencias.isEmpty()) {
                throw new RegraDeNegocio(
                        ("'%s' ainda não pode ser publicado: %s. Peça ao Claude para acertar isso e "
                                + "publique de novo.")
                                .formatted(simulado.getTitulo(), String.join("; ", pendencias)));
            }
        }

        escolhidos.forEach(item -> {
            estrutura.publicarItem(ident, item);
            // A linha publicada sem a questão dela não mostraria nada: as duas vão juntas, mesmo
            // quando o professor libera só alguns itens.
            var questao = item.getQuestao();
            if (questao != null && questao.getStatus() == Status.RASCUNHO
                    && r.getId().equals(questao.getRascunhoId())) {
                questoes.publicar(questao);
            }
        });

        // Questão e simulado continuam sendo tudo ou nada: uma prova pela metade não é uma prova.
        if (publicouResto) {
            daProposta.forEach(questoes::publicar);
            if (simulado != null) {
                simulados.publicar(simulado, agora);
            }
        }

        var sobraram = pendentes.stream()
                .filter(i -> escolhidos.stream().noneMatch(e -> e.getId().equals(i.getId())))
                .toList();
        if (sobraram.isEmpty() && publicouResto) {
            r.publicar(agora);
        }

        var incompletas = (int) daProposta.stream()
                .filter(q -> !q.completa()).count();
        var turma = r.getTurma() == null ? null : r.getTurma().getNome();

        String mensagem;
        if (simulado != null && publicouResto) {
            mensagem = "Publicado. '%s' abre em %s e fecha em %s para %s.".formatted(
                    simulado.getTitulo(), Relogio.emBrasilia(simulado.getAbreEm()),
                    Relogio.emBrasilia(simulado.getFechaEm()),
                    simulado.getTurmas().stream().map(t -> t.getNome()).collect(joining(", ")));
        } else {
            mensagem = "Publicado. O conteúdo já aparece para os alunos de %s."
                    .formatted(turma == null ? "sua turma" : turma)
                    + (sobraram.isEmpty() ? ""
                            : " Sobraram %d item(ns) neste rascunho.".formatted(sobraram.size()));
        }

        return new Publicacao(r.getId(), r.getStatus() == Status.PUBLICADO, turma,
                escolhidos.size(), sobraram.size(),
                publicouResto ? daProposta.size() : 0,
                publicouResto ? incompletas : 0,
                simulado != null && publicouResto ? simulado.getTitulo() : null,
                r.getAprovadoPor().getNome(), r.getAprovadoVia().name(), mensagem);
    }
}
