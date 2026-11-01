package br.com.plataforma.exercicios;

import br.com.plataforma.acervo.AcessoServico;
import br.com.plataforma.comum.Identidade;
import br.com.plataforma.comum.NaoAutorizado;
import br.com.plataforma.comum.NaoEncontrado;
import br.com.plataforma.comum.RegraDeNegocio;
import br.com.plataforma.comum.Relogio;
import br.com.plataforma.comum.Status;
import br.com.plataforma.estrutura.EstruturaServico;
import br.com.plataforma.estrutura.Item;
import br.com.plataforma.estrutura.SubModulo;
import br.com.plataforma.questoes.Letra;
import java.time.Instant;
import java.util.Collection;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * A questão que o aluno responde dentro da aula.
 *
 * <p>Diferente do simulado, o gabarito sai na hora — mas só depois da resposta, e quem segura é o
 * backend: antes de marcar, gabarito, resolução comentada e vídeo de resolução nem saem daqui.
 * Vale a primeira resposta; a segunda é recusada.
 *
 * <p>No sub-módulo com gabarito no fim (as questões da apostila, que o aluno já resolveu no papel),
 * ele responde uma por uma do mesmo jeito, mas a correção — acerto, gabarito, resolução — fica
 * guardada até ele responder a última questão de lá. Aí sai a de todas, de uma vez. Quem visse a
 * correção da primeira corrigiria as outras antes de marcar.
 */
@Service
public class ExerciciosServico {

    private final RespostaDeExercicioRepositorio respostas;
    private final EstruturaServico estrutura;
    private final AcessoServico acesso;

    public ExerciciosServico(RespostaDeExercicioRepositorio respostas, EstruturaServico estrutura,
            AcessoServico acesso) {
        this.respostas = respostas;
        this.estrutura = estrutura;
        this.acesso = acesso;
    }

    /**
     * Do gabarito em diante, os campos só vêm preenchidos depois da resposta (ou para o professor).
     * {@code comentarios}: por letra, por que cada alternativa está certa ou errada — o aluno lê o
     * da que marcou. {@code gabaritoNoFim}: a correção espera a última questão do sub-módulo, e
     * {@code faltam} diz quantas ainda faltam (zero: já saiu).
     */
    public record Exercicio(
            Integer itemId, String nome, Integer questaoId, String enunciado,
            Map<Letra, String> alternativas, boolean respondida, Letra marcada, Boolean correta,
            String respondidoEm, Letra gabarito, String resolucaoComentada,
            AcessoServico.VideoDescrito resolucao, Map<Letra, String> comentarios,
            boolean gabaritoNoFim, Integer faltam) {}

    /** A questão da linha. O professor vê tudo, como prévia; o aluno, o que a resposta dele liberou. */
    @Transactional(readOnly = true)
    public Exercicio abrir(Identidade ident, Integer itemId, Instant agora) {
        var item = exigirLinha(ident, itemId, agora);
        if (ident.eOperador()) {
            return montar(item, null, true, null);
        }
        var resposta = respostas.findByItemIdAndAlunoId(itemId, ident.usuarioId()).orElse(null);
        var faltam = faltam(ident, item, agora);
        return montar(item, resposta, resposta != null && (faltam == null || faltam == 0), faltam);
    }

    /** Grava a resposta e devolve a correção — ou, com gabarito no fim, quantas ainda faltam. Não tem segunda chance. */
    @Transactional
    public Exercicio responder(Identidade ident, Integer itemId, String alternativa, Instant agora) {
        if (!ident.eAluno()) {
            throw new NaoAutorizado("Somente alunos respondem as questões da aula.");
        }
        ident.exigirQueGrave("responder a questão");
        var item = exigirLinha(ident, itemId, agora);
        var questao = item.getQuestao();

        var texto = alternativa == null ? "" : alternativa.strip().toUpperCase(Locale.ROOT);
        Letra letra;
        try {
            letra = Letra.valueOf(texto);
        } catch (IllegalArgumentException e) {
            throw new RegraDeNegocio("Alternativa '%s' inválida.".formatted(alternativa));
        }
        if (!questao.tem(letra)) {
            throw new RegraDeNegocio("Esta questão não tem a alternativa %s.".formatted(letra));
        }
        if (respostas.findByItemIdAndAlunoId(itemId, ident.usuarioId()).isPresent()) {
            throw jaRespondida();
        }

        RespostaDeExercicio resposta;
        try {
            resposta = respostas.saveAndFlush(new RespostaDeExercicio(itemId, ident.usuarioId(),
                    questao.getId(), letra, letra == questao.getGabarito(), agora));
        } catch (DataIntegrityViolationException doisCliques) {
            // O unique do banco é quem garante a regra quando dois pedidos chegam juntos.
            throw jaRespondida();
        }
        var faltam = faltam(ident, item, agora);
        return montar(item, resposta, faltam == null || faltam == 0, faltam);
    }

    // --- o gabarito no fim -------------------------------------------------------

    /**
     * Quantas questões do sub-módulo desta linha o aluno ainda não respondeu, se ele tem gabarito no
     * fim; {@code null} se não tem (a correção sai na hora).
     */
    private Integer faltam(Identidade ident, Item item, Instant agora) {
        var sub = item.getSubmodulo();
        if (sub == null || !sub.isGabaritoNoFim()) {
            return null;
        }
        var linhas = linhasVisiveis(ident, sub, agora).stream().map(Item::getId).toList();
        var feitas = respostas.findByAlunoIdAndItemIdIn(ident.usuarioId(), linhas).size();
        return Math.max(0, linhas.size() - feitas);
    }

    /**
     * As linhas já respondidas por este aluno cuja correção ainda está guardada: as dos sub-módulos com
     * gabarito no fim em que ele não chegou à última. É o que as outras telas do aluno (a árvore do
     * curso, o desempenho por assunto) não podem mostrar como certo ou errado.
     */
    @Transactional(readOnly = true)
    public Set<Integer> comCorrecaoGuardada(Identidade ident, Instant agora) {
        var saida = new HashSet<Integer>();
        if (!ident.eAluno()) {
            return saida;
        }
        for (var sub : estrutura.comGabaritoNoFim()) {
            var linhas = linhasVisiveis(ident, sub, agora).stream().map(Item::getId).toList();
            if (linhas.isEmpty()) {
                continue;
            }
            var feitas = respostas.findByAlunoIdAndItemIdIn(ident.usuarioId(), linhas).stream()
                    .map(RespostaDeExercicio::getItemId).toList();
            if (feitas.size() < linhas.size()) {
                saida.addAll(feitas);
            }
        }
        return saida;
    }

    /** As linhas de questão do sub-módulo que esta pessoa vê, na ordem da tela. */
    private List<Item> linhasVisiveis(Identidade ident, SubModulo sub, Instant agora) {
        var deQuestao = estrutura.linhas(sub).stream().filter(i -> i.getQuestao() != null).toList();
        if (ident.eOperador()) {
            return deQuestao;
        }
        var publicadas = deQuestao.stream()
                .filter(i -> i.getStatus() == Status.PUBLICADO && i.getQuestao().getStatus() == Status.PUBLICADO)
                .toList();
        var liberadas = acesso.itensLiberados(ident, publicadas.stream().map(Item::getId).toList(), agora);
        return publicadas.stream().filter(i -> liberadas.contains(i.getId())).toList();
    }

    /** {@code correta}: nula enquanto a correção está guardada (gabarito no fim). */
    public record Feita(Boolean correta) {}

    /** Em lote, para a árvore do curso marcar o que o aluno já respondeu. */
    @Transactional(readOnly = true)
    public Map<Integer, Feita> feitasPeloAluno(Identidade ident, Collection<Integer> itens, Instant agora) {
        var saida = new LinkedHashMap<Integer, Feita>();
        if (!itens.isEmpty()) {
            var guardadas = comCorrecaoGuardada(ident, agora);
            respostas.findByAlunoIdAndItemIdIn(ident.usuarioId(), itens).forEach(r -> saida.put(r.getItemId(),
                    new Feita(guardadas.contains(r.getItemId()) ? null : r.isCorreta())));
        }
        return saida;
    }

    public record Resposta(Integer alunoId, Integer itemId, boolean correta, Instant respondidoEm, Letra marcada) {}

    /** As respostas de um aluno nestes itens, com a data: é a linha do tempo dele no curso. */
    @Transactional(readOnly = true)
    public List<Resposta> respostasDoAluno(Integer alunoId, Collection<Integer> itens) {
        return itens.isEmpty() ? List.of() : respostas.findByAlunoIdAndItemIdIn(alunoId, itens).stream()
                .map(r -> new Resposta(r.getAlunoId(), r.getItemId(), r.isCorreta(), r.getRespondidoEm(), r.getAlternativaMarcada())).toList();
    }

    /** As respostas de todos os alunos nestes itens: é o que o progresso da turma conta. */
    @Transactional(readOnly = true)
    public List<Resposta> respostasNosItens(Collection<Integer> itens) {
        return itens.isEmpty() ? List.of() : respostas.findByItemIdIn(itens).stream()
                .map(r -> new Resposta(r.getAlunoId(), r.getItemId(), r.isCorreta(), r.getRespondidoEm(), r.getAlternativaMarcada())).toList();
    }

    private static RegraDeNegocio jaRespondida() {
        return new RegraDeNegocio("Você já respondeu esta questão: vale a primeira resposta.");
    }

    /** A linha, se for de questão e esta pessoa puder vê-la. Para o aluno, o resto não existe. */
    private Item exigirLinha(Identidade ident, Integer itemId, Instant agora) {
        var item = estrutura.item(itemId).filter(i -> i.getQuestao() != null).orElse(null);
        var visivel = item != null && (ident.eOperador()
                || (item.getQuestao().getStatus() == Status.PUBLICADO
                        && acesso.itensLiberados(ident, List.of(itemId), agora).contains(itemId)));
        if (!visivel) {
            throw new NaoEncontrado("Esta questão não está no seu curso.");
        }
        return item;
    }

    private static Exercicio montar(Item item, RespostaDeExercicio resposta, boolean revelar, Integer faltam) {
        var q = item.getQuestao();
        var alternativas = new LinkedHashMap<Letra, String>();
        q.getAlternativas().forEach(a -> alternativas.put(a.getLetra(), a.getTexto()));
        return new Exercicio(item.getId(), item.getNome(), q.getId(), q.getEnunciado(), alternativas,
                resposta != null,
                resposta == null ? null : resposta.getAlternativaMarcada(),
                resposta == null || !revelar ? null : resposta.isCorreta(),
                resposta == null ? null : Relogio.iso(resposta.getRespondidoEm()),
                revelar ? q.getGabarito() : null,
                revelar ? q.getResolucaoComentada() : null,
                // Quem respondeu ganhou a resolução: não passa de novo pela pergunta "pode assistir?".
                revelar && q.getVideo() != null ? AcessoServico.descrever(q.getVideo(), true) : null,
                revelar ? q.comentarios() : Map.of(),
                faltam != null, faltam);
    }
}
