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
import br.com.plataforma.questoes.Letra;
import java.time.Instant;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * A questão que o aluno responde dentro da aula.
 *
 * <p>Diferente do simulado, o gabarito sai na hora — mas só depois da resposta, e quem segura é o
 * backend: antes de marcar, gabarito, resolução comentada e vídeo de resolução nem saem daqui.
 * Vale a primeira resposta; a segunda é recusada.
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

    /** Os quatro últimos campos só vêm preenchidos depois da resposta (ou para o professor). */
    public record Exercicio(
            Integer itemId, String nome, Integer questaoId, String enunciado,
            Map<Letra, String> alternativas, boolean respondida, Letra marcada, Boolean correta,
            String respondidoEm, Letra gabarito, String resolucaoComentada,
            AcessoServico.VideoDescrito resolucao) {}

    /** A questão da linha. O professor vê tudo, como prévia; o aluno, o que a resposta dele liberou. */
    @Transactional(readOnly = true)
    public Exercicio abrir(Identidade ident, Integer itemId, Instant agora) {
        var item = exigirLinha(ident, itemId, agora);
        if (ident.eOperador()) {
            return montar(item, null, true);
        }
        var resposta = respostas.findByItemIdAndAlunoId(itemId, ident.usuarioId()).orElse(null);
        return montar(item, resposta, resposta != null);
    }

    /** Grava a resposta e devolve a correção. Não tem segunda chance. */
    @Transactional
    public Exercicio responder(Identidade ident, Integer itemId, String alternativa, Instant agora) {
        if (!ident.eAluno()) {
            throw new NaoAutorizado("Somente alunos respondem as questões da aula.");
        }
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
        return montar(item, resposta, true);
    }

    public record Feita(boolean correta) {}

    /** Em lote, para a árvore do curso marcar o que o aluno já respondeu. */
    @Transactional(readOnly = true)
    public Map<Integer, Feita> feitasPeloAluno(Integer alunoId, Collection<Integer> itens) {
        var saida = new LinkedHashMap<Integer, Feita>();
        if (!itens.isEmpty()) {
            respostas.findByAlunoIdAndItemIdIn(alunoId, itens)
                    .forEach(r -> saida.put(r.getItemId(), new Feita(r.isCorreta())));
        }
        return saida;
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

    private static Exercicio montar(Item item, RespostaDeExercicio resposta, boolean revelar) {
        var q = item.getQuestao();
        var alternativas = new LinkedHashMap<Letra, String>();
        q.getAlternativas().forEach(a -> alternativas.put(a.getLetra(), a.getTexto()));
        return new Exercicio(item.getId(), item.getNome(), q.getId(), q.getEnunciado(), alternativas,
                resposta != null,
                resposta == null ? null : resposta.getAlternativaMarcada(),
                resposta == null ? null : resposta.isCorreta(),
                resposta == null ? null : Relogio.iso(resposta.getRespondidoEm()),
                revelar ? q.getGabarito() : null,
                revelar ? q.getResolucaoComentada() : null,
                // Quem respondeu ganhou a resolução: não passa de novo pela pergunta "pode assistir?".
                revelar && q.getVideo() != null ? AcessoServico.descrever(q.getVideo(), true) : null);
    }
}
