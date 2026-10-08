package br.com.plataforma.progresso;

import br.com.plataforma.acervo.AcessoServico;
import br.com.plataforma.catalogo.Turma;
import br.com.plataforma.comum.Identidade;
import br.com.plataforma.comum.NaoAutorizado;
import br.com.plataforma.comum.NaoEncontrado;
import br.com.plataforma.comum.RegraDeNegocio;
import br.com.plataforma.comum.Relogio;
import br.com.plataforma.contas.ContasServico;
import br.com.plataforma.contas.Usuario;
import br.com.plataforma.estrutura.EstruturaServico;
import br.com.plataforma.estrutura.Item;
import br.com.plataforma.exercicios.ExerciciosServico;
import jakarta.persistence.EntityManager;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Aula assistida: o que cada aluno já viu do curso.
 *
 * <p>Três jeitos de um item ficar concluído, um por tipo: o <b>vídeo</b>, quando o player avisa que
 * o aluno passou de 90% (ou quando ele marca); o <b>PDF</b>, quando ele marca — o portal marca ao
 * abrir; a <b>questão</b>, quando ele responde, e essa não se marca nem desmarca à mão. O que conta
 * no total de cada aluno é o que a turma dele vê hoje: item publicado, da turma e já liberado.
 */
@Service
public class ProgressoServico {

    /** A partir daqui o vídeo conta como assistido: a despedida e os créditos quase ninguém vê. */
    static final double FRACAO_QUE_CONCLUI = 0.9;

    /** Teto de sanidade para o que o player manda: nenhuma aula tem um dia inteiro. */
    private static final int UM_DIA = 24 * 60 * 60;

    private final ProgressoRepositorio progressos;
    private final EstruturaServico estrutura;
    private final AcessoServico acesso;
    private final ExerciciosServico exercicios;
    private final ContasServico contas;
    private final EntityManager em;

    public ProgressoServico(ProgressoRepositorio progressos, EstruturaServico estrutura, AcessoServico acesso,
            ExerciciosServico exercicios, ContasServico contas, EntityManager em) {
        this.progressos = progressos;
        this.estrutura = estrutura;
        this.acesso = acesso;
        this.exercicios = exercicios;
        this.contas = contas;
        this.em = em;
    }

    // --- o aluno -----------------------------------------------------------------

    /** {@code posicaoSegundos}: de onde o vídeo continua na próxima vez. */
    public record Estado(Integer itemId, boolean concluido, int posicaoSegundos) {

        static Estado de(Integer itemId, Progresso p) {
            return p == null ? new Estado(itemId, false, 0) : new Estado(itemId, p.concluido(), p.getPosicaoSegundos());
        }
    }

    @Transactional(readOnly = true)
    public Estado ver(Identidade ident, Integer itemId, Instant agora) {
        exigirItem(ident, itemId, agora);
        return Estado.de(itemId, progressos.findByItemIdAndAlunoId(itemId, ident.usuarioId()).orElse(null));
    }

    /** O aviso do player: onde o aluno está. Perto do fim, o vídeo se conclui sozinho. */
    @Transactional
    public Estado registrar(Identidade ident, Integer itemId, Integer posicao, Integer duracao, Instant agora) {
        var item = exigirItem(ident, itemId, agora);
        if (item.getVideo() == null) {
            throw new RegraDeNegocio("'%s' não é um vídeo: não tem posição para guardar.".formatted(item.getNome()));
        }
        if (posicao == null || duracao == null || posicao < 0 || duracao <= 0 || duracao > UM_DIA) {
            throw new RegraDeNegocio("Informe a posição e a duração do vídeo, em segundos.");
        }
        var limiar = (int) Math.ceil(duracao * FRACAO_QUE_CONCLUI);
        progressos.registrar(itemId, ident.usuarioId(), Math.min(posicao, duracao), duracao, limiar, agora);
        return Estado.de(itemId, progressos.findByItemIdAndAlunoId(itemId, ident.usuarioId()).orElse(null));
    }

    /** O aluno marca ou desmarca à mão. Questão não: ela se conclui respondendo. */
    @Transactional
    public Estado marcar(Identidade ident, Integer itemId, boolean concluido, Instant agora) {
        var item = exigirItem(ident, itemId, agora);
        if (item.getQuestao() != null) {
            throw new RegraDeNegocio("'%s' é uma questão: ela fica concluída quando você responde.".formatted(item.getNome()));
        }
        if (concluido) {
            progressos.concluir(itemId, ident.usuarioId(), agora);
        } else {
            progressos.desmarcar(itemId, ident.usuarioId(), agora);
        }
        return Estado.de(itemId, progressos.findByItemIdAndAlunoId(itemId, ident.usuarioId()).orElse(null));
    }

    /** Em lote, para a árvore do curso: quais destes itens o aluno já concluiu (questão não entra). */
    @Transactional(readOnly = true)
    public Set<Integer> concluidosPeloAluno(Integer alunoId, Collection<Integer> itens) {
        var saida = new LinkedHashSet<Integer>();
        if (!itens.isEmpty()) {
            progressos.findByAlunoIdAndItemIdIn(alunoId, itens).stream()
                    .filter(Progresso::concluido).forEach(p -> saida.add(p.getItemId()));
        }
        return saida;
    }

    /** O item, se este aluno o vê no curso dele: publicado, da turma e já liberado pela agenda. */
    private Item exigirItem(Identidade ident, Integer itemId, Instant agora) {
        if (!ident.eAluno()) {
            throw new NaoAutorizado("Somente alunos registram o que assistiram.");
        }
        var item = estrutura.item(itemId).orElse(null);
        if (item == null || !acesso.itensLiberados(ident, List.of(itemId), agora).contains(itemId)) {
            throw new NaoEncontrado("Este item não está no seu curso.");
        }
        return item;
    }

    // --- o professor -------------------------------------------------------------

    /** {@code ultimaAtividade}: a última vez que o aluno assistiu, marcou ou respondeu algo desta turma. */
    public record AlunoNaTurma(Integer id, String nome, String email, int concluidos, int total, String ultimaAtividade) {}

    public record ProgressoDaTurma(Integer turmaId, String turma, int total, List<AlunoNaTurma> alunos) {}

    /** Quanto cada aluno da turma já fez do que ela vê hoje. */
    @Transactional(readOnly = true)
    public ProgressoDaTurma daTurma(Identidade ident, Turma turma, Instant agora) {
        ident.exigirOperador();
        var itens = itensDaTurma(turma, agora).stream().map(EstruturaServico.ItemNaArvore::id)
                .collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new));
        var feitos = new HashMap<Integer, Set<Integer>>();
        var ultima = new HashMap<Integer, Instant>();
        feito(itens, feitos, ultima);

        var alunos = contas.alunosDaTurma(ident, turma).alunos().stream()
                .map(a -> new AlunoNaTurma(a.id(), a.nome(), a.email(), feitos.getOrDefault(a.id(), Set.of()).size(),
                        itens.size(), Relogio.iso(ultima.get(a.id()))))
                .toList();
        return new ProgressoDaTurma(turma.getId(), turma.getNome(), itens.size(), alunos);
    }

    /** {@code tipo}: VIDEO, PDF ou QUESTAO. {@code correta} só na questão respondida. */
    public record ItemDoAluno(
            Integer id, String nome, String tipo, boolean concluido, String concluidoEm,
            Integer posicaoSegundos, Integer duracaoSegundos, Boolean correta) {}

    public record SubModuloDoAluno(String nome, List<ItemDoAluno> itens) {}

    public record ModuloDoAluno(Integer id, String nome, int concluidos, int total, List<SubModuloDoAluno> submodulos) {}

    public record TurmaDoAluno(Integer turmaId, String turma, int concluidos, int total, List<ModuloDoAluno> modulos) {}

    public record ProgressoDoAluno(Integer alunoId, String aluno, String ultimaAtividade, List<TurmaDoAluno> turmas) {}

    /** O curso de um aluno, item por item, em cada turma em que ele está. */
    @Transactional(readOnly = true)
    public ProgressoDoAluno doAluno(Identidade ident, String alunoRef, Instant agora) {
        ident.exigirOperador();
        Usuario aluno = contas.resolverAluno(alunoRef);
        var turmas = em.createQuery(
                        "select t from Matricula m join m.turma t where m.usuarioId = :usuario and t.removidoEm is null order by t.nome",
                        Turma.class)
                .setParameter("usuario", aluno.getId()).getResultList();

        Instant ultima = null;
        var saida = new ArrayList<TurmaDoAluno>();
        for (var turma : turmas) {
            var arvore = estrutura.arvoreDaTurma(turma, true, false, agora);
            var ids = arvore.stream().flatMap(m -> m.submodulos().stream()).flatMap(s -> s.itens().stream())
                    .map(EstruturaServico.ItemNaArvore::id).toList();
            var vistos = new HashMap<Integer, Progresso>();
            if (!ids.isEmpty()) {
                progressos.findByAlunoIdAndItemIdIn(aluno.getId(), ids).forEach(p -> vistos.put(p.getItemId(), p));
            }
            var respostas = new HashMap<Integer, ExerciciosServico.Resposta>();
            exercicios.respostasDoAluno(aluno.getId(), ids).forEach(r -> respostas.put(r.itemId(), r));
            for (var p : vistos.values()) {
                ultima = maisRecente(ultima, p.getVistoEm());
            }
            for (var r : respostas.values()) {
                ultima = maisRecente(ultima, r.respondidoEm());
            }

            var modulos = new ArrayList<ModuloDoAluno>();
            int feitosNaTurma = 0;
            for (var m : arvore) {
                var subs = new ArrayList<SubModuloDoAluno>();
                int feitosNoModulo = 0;
                int total = 0;
                for (var s : m.submodulos()) {
                    var linhas = new ArrayList<ItemDoAluno>();
                    for (var i : s.itens()) {
                        var linha = descrever(i, vistos.get(i.id()), respostas.get(i.id()));
                        feitosNoModulo += linha.concluido() ? 1 : 0;
                        total++;
                        linhas.add(linha);
                    }
                    subs.add(new SubModuloDoAluno(s.nome(), linhas));
                }
                feitosNaTurma += feitosNoModulo;
                modulos.add(new ModuloDoAluno(m.id(), m.nome(), feitosNoModulo, total, subs));
            }
            saida.add(new TurmaDoAluno(turma.getId(), turma.getNome(), feitosNaTurma, ids.size(), modulos));
        }
        return new ProgressoDoAluno(aluno.getId(), aluno.getNome(), Relogio.iso(ultima), saida);
    }

    private static ItemDoAluno descrever(EstruturaServico.ItemNaArvore i, Progresso visto, ExerciciosServico.Resposta resposta) {
        if (i.questao() != null) {
            return new ItemDoAluno(i.id(), i.nome(), "QUESTAO", resposta != null,
                    resposta == null ? null : Relogio.iso(resposta.respondidoEm()), null, null,
                    resposta == null ? null : resposta.correta());
        }
        return new ItemDoAluno(i.id(), i.nome(), i.videoId() != null ? "VIDEO" : "PDF", visto != null && visto.concluido(),
                visto == null ? null : Relogio.iso(visto.getConcluidoEm()),
                visto == null || i.videoId() == null ? null : visto.getPosicaoSegundos(),
                visto == null ? null : visto.getDuracaoSegundos(), null);
    }

    /** Os itens que o aluno desta turma vê hoje: é o denominador do progresso. */
    private List<EstruturaServico.ItemNaArvore> itensDaTurma(Turma turma, Instant agora) {
        return estrutura.arvoreDaTurma(turma, true, false, agora).stream()
                .flatMap(m -> m.submodulos().stream()).flatMap(s -> s.itens().stream()).toList();
    }

    /** Por aluno: os itens concluídos (assistidos, marcados ou respondidos) e a última atividade. */
    private void feito(Collection<Integer> itens, Map<Integer, Set<Integer>> feitos, Map<Integer, Instant> ultima) {
        if (itens.isEmpty()) {
            return;
        }
        for (var p : progressos.findByItemIdIn(itens)) {
            if (p.concluido()) {
                feitos.computeIfAbsent(p.getAlunoId(), a -> new LinkedHashSet<>()).add(p.getItemId());
            }
            ultima.merge(p.getAlunoId(), p.getVistoEm(), ProgressoServico::maisRecente);
        }
        for (var r : exercicios.respostasNosItens(itens)) {
            feitos.computeIfAbsent(r.alunoId(), a -> new LinkedHashSet<>()).add(r.itemId());
            ultima.merge(r.alunoId(), r.respondidoEm(), ProgressoServico::maisRecente);
        }
    }

    private static Instant maisRecente(Instant a, Instant b) {
        return a == null || (b != null && b.isAfter(a)) ? b : a;
    }
}
