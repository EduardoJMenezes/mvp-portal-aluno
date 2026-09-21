package br.com.plataforma.rascunhos;

import static java.util.stream.Collectors.joining;

import br.com.plataforma.comum.AprovacaoNecessaria;
import br.com.plataforma.comum.Identidade;
import br.com.plataforma.comum.NaoEncontrado;
import br.com.plataforma.comum.RegraDeNegocio;
import br.com.plataforma.comum.Relogio;
import br.com.plataforma.comum.Status;
import br.com.plataforma.contas.ContasServico;
import br.com.plataforma.estrutura.EstruturaServico;
import br.com.plataforma.estrutura.Item;
import br.com.plataforma.questoes.Letra;
import br.com.plataforma.questoes.QuestoesServico;
import br.com.plataforma.simulados.SimuladosServico;
import java.time.Instant;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * A regra que sustenta a POC: <b>a IA propõe, o humano aprova, o backend publica.</b>
 *
 * <p>Nenhum caminho daqui leva conteúdo ao aluno sem aprovação humana gravada em
 * {@code drafts.aprovado_por_id}. Não é checagem no cliente: é estado no banco, conferido a cada
 * publicação.
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
                q.completa() ? "" : "  (sem alternativas A-E)")));

        var simulado = d.simulado();
        if (simulado != null) {
            linhas.add("Turmas: " + String.join(", ", simulado.turmas()));
            linhas.add("Abre %s, fecha %s, %s min de prova".formatted(
                    simulado.abreEm() == null ? "(sem data)" : simulado.abreEm(),
                    simulado.fechaEm() == null ? "(sem data)" : simulado.fechaEm(),
                    simulado.duracaoMinutos() == null ? "?" : simulado.duracaoMinutos()));
            simulado.questoes().forEach(q -> linhas.add("  %d. %s".formatted(
                    q.ordem(), QuestoesServico.resumo(q.enunciado(), 70))));
        }

        linhas.add("");
        linhas.add("Publicar torna este conteúdo visível para os alunos da turma.");
        return String.join("\n", linhas);
    }

    public record Publicacao(
            Integer rascunhoId, boolean publicado, String turma, int itensPublicados,
            int itensAindaPendentes, int questoesPublicadas, int questoesSemAlternativas,
            String simuladoPublicado, String aprovadoPor, String aprovadoVia, String mensagem) {}

    /**
     * Publica um rascunho <b>já aprovado</b> por um humano.
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
        if (!r.temAprovacaoHumana()) {
            throw new AprovacaoNecessaria(
                    ("O rascunho %d não tem aprovação humana registrada e não pode ser publicado. "
                            + "Peça a confirmação do professor, ou aprove pelo portal em "
                            + "Admin > Rascunhos > #%d.").formatted(rascunhoId, rascunhoId));
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

        escolhidos.forEach(item -> estrutura.publicarItem(ident, item));

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
                .filter(q -> q.getAlternativas().size() < Letra.values().length).count();
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
