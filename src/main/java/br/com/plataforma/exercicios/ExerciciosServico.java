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
 *
 * <p>No sub-módulo que é folha de respostas, o aluno resolve no papel e lança todas as letras de
 * uma vez ({@link #enviarFolha}). Até lá, nenhuma questão dela mostra gabarito, e responder uma por
 * uma é recusado: quem visse a correção da primeira corrigiria as outras antes de enviar.
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
     * da que marcou.
     */
    public record Exercicio(
            Integer itemId, String nome, Integer questaoId, String enunciado,
            Map<Letra, String> alternativas, boolean respondida, Letra marcada, Boolean correta,
            String respondidoEm, Letra gabarito, String resolucaoComentada,
            AcessoServico.VideoDescrito resolucao, Map<Letra, String> comentarios) {}

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
        ident.exigirQueGrave("responder a questão");
        var item = exigirLinha(ident, itemId, agora);
        var questao = item.getQuestao();
        if (item.getSubmodulo() != null && item.getSubmodulo().isFolhaDeRespostas()) {
            throw new RegraDeNegocio("Esta questão é da folha de respostas de '%s': lance todas as letras juntas e envie a folha."
                    .formatted(item.getSubmodulo().getNome()));
        }

        var letra = letra(questao, alternativa, null);
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

    // --- a folha de respostas ------------------------------------------------

    /**
     * Uma linha da folha. {@code letras}: as alternativas que a questão tem (A a D, e E se houver).
     * {@code gabarito} e {@code correta} só vêm depois de a folha ser enviada (ou para o professor).
     */
    public record LinhaDaFolha(Integer itemId, String nome, List<Letra> letras, Letra marcada, Boolean correta,
            Letra gabarito) {}

    /** {@code enviada}: o aluno já lançou a folha; aí {@code acertos} conta o que ele acertou. */
    public record Folha(Integer submoduloId, String submodulo, String modulo, boolean enviada,
            String enviadaEm, Integer acertos, int total, List<LinhaDaFolha> linhas) {}

    /** A folha como o aluno a vê: em branco até enviar, corrigida depois. O professor vê o gabarito. */
    @Transactional(readOnly = true)
    public Folha folha(Identidade ident, Integer submoduloId, Instant agora) {
        var sub = exigirFolha(submoduloId);
        var linhas = linhasDaFolha(ident, sub, agora);
        if (ident.eOperador()) {
            return montarFolha(sub, linhas, Map.of(), true);
        }
        var minhas = respostasDoAluno(ident.usuarioId(), linhas.stream().map(Item::getId).toList()).stream()
                .collect(java.util.stream.Collectors.toMap(Resposta::itemId, r -> r));
        return montarFolha(sub, linhas, minhas, false);
    }

    /**
     * Grava a folha inteira de uma vez: uma letra para cada questão ainda sem resposta, nem mais nem
     * menos. Não tem segunda chance, como a questão da aula.
     */
    @Transactional
    public Folha enviarFolha(Identidade ident, Integer submoduloId, Map<Integer, String> marcadas, Instant agora) {
        if (!ident.eAluno()) {
            throw new NaoAutorizado("Somente alunos enviam a folha de respostas.");
        }
        ident.exigirQueGrave("enviar a folha de respostas");
        var sub = exigirFolha(submoduloId);
        var linhas = linhasDaFolha(ident, sub, agora);
        var ja = respostas.findByAlunoIdAndItemIdIn(ident.usuarioId(), linhas.stream().map(Item::getId).toList())
                .stream().map(RespostaDeExercicio::getItemId).collect(java.util.stream.Collectors.toSet());
        var abertas = linhas.stream().filter(i -> !ja.contains(i.getId())).toList();
        if (abertas.isEmpty()) {
            throw new RegraDeNegocio("Você já enviou esta folha de respostas: vale o primeiro envio.");
        }

        var recebidas = marcadas == null ? Map.<Integer, String>of() : marcadas;
        var faltam = abertas.stream().filter(i -> {
            var l = recebidas.get(i.getId());
            return l == null || l.isBlank();
        }).map(Item::getNome).toList();
        if (!faltam.isEmpty()) {
            throw new RegraDeNegocio("Falta marcar %s. A folha só é enviada com todas as questões marcadas."
                    .formatted(String.join(", ", faltam)));
        }
        var abertasIds = abertas.stream().map(Item::getId).collect(java.util.stream.Collectors.toSet());
        var estranhas = recebidas.keySet().stream().filter(id -> !abertasIds.contains(id)).toList();
        if (!estranhas.isEmpty()) {
            throw new RegraDeNegocio("Estas linhas não estão em aberto nesta folha: %s.".formatted(estranhas));
        }

        try {
            for (var item : abertas) {
                var questao = item.getQuestao();
                var letra = letra(questao, recebidas.get(item.getId()), item.getNome());
                respostas.save(new RespostaDeExercicio(item.getId(), ident.usuarioId(), questao.getId(), letra,
                        letra == questao.getGabarito(), agora));
            }
            respostas.flush();
        } catch (DataIntegrityViolationException doisCliques) {
            // O unique do banco é quem garante a regra quando dois envios chegam juntos.
            throw new RegraDeNegocio("Você já enviou esta folha de respostas: vale o primeiro envio.");
        }
        return folha(ident, submoduloId, agora);
    }

    private SubModulo exigirFolha(Integer submoduloId) {
        var sub = estrutura.submodulo(submoduloId)
                .orElseThrow(() -> new NaoEncontrado("Esta folha de respostas não está no seu curso."));
        if (!sub.isFolhaDeRespostas()) {
            throw new RegraDeNegocio("'%s' não é folha de respostas: as questões dele se respondem uma por uma."
                    .formatted(sub.getNome()));
        }
        return sub;
    }

    /** As questões da folha que esta pessoa vê, na ordem da tela. Para o aluno, sem nenhuma, a folha não existe. */
    private List<Item> linhasDaFolha(Identidade ident, SubModulo sub, Instant agora) {
        var deQuestao = estrutura.linhas(sub).stream().filter(i -> i.getQuestao() != null).toList();
        if (ident.eOperador()) {
            return deQuestao;
        }
        var publicadas = deQuestao.stream()
                .filter(i -> i.getStatus() == Status.PUBLICADO && i.getQuestao().getStatus() == Status.PUBLICADO)
                .toList();
        var liberadas = acesso.itensLiberados(ident, publicadas.stream().map(Item::getId).toList(), agora);
        var visiveis = publicadas.stream().filter(i -> liberadas.contains(i.getId())).toList();
        if (visiveis.isEmpty()) {
            throw new NaoEncontrado("Esta folha de respostas não está no seu curso.");
        }
        return visiveis;
    }

    private static Folha montarFolha(SubModulo sub, List<Item> linhas, Map<Integer, Resposta> minhas, boolean revelar) {
        // Enviada é tudo respondido: o envio grava a folha inteira numa transação.
        var enviada = !revelar && !linhas.isEmpty() && linhas.stream().allMatch(i -> minhas.containsKey(i.getId()));
        var mostra = revelar || enviada;
        var saida = linhas.stream().map(i -> {
            var q = i.getQuestao();
            var r = minhas.get(i.getId());
            var letras = q.getAlternativas().stream().map(a -> a.getLetra()).sorted().toList();
            return new LinhaDaFolha(i.getId(), i.getNome(), letras,
                    r == null ? null : r.marcada(),
                    mostra && r != null ? r.correta() : null,
                    mostra ? q.getGabarito() : null);
        }).toList();
        var quando = minhas.values().stream().map(Resposta::respondidoEm).max(Instant::compareTo).orElse(null);
        return new Folha(sub.getId(), sub.getNome(), sub.getModulo() == null ? null : sub.getModulo().getNome(),
                enviada, enviada && quando != null ? Relogio.iso(quando) : null,
                enviada ? (int) minhas.values().stream().filter(Resposta::correta).count() : null,
                linhas.size(), saida);
    }

    /** A letra marcada, conferida contra as alternativas da questão. {@code linha}: o nome, para o erro dizer qual. */
    private static Letra letra(br.com.plataforma.questoes.Questao questao, String alternativa, String linha) {
        var onde = linha == null ? "" : " em %s".formatted(linha);
        var texto = alternativa == null ? "" : alternativa.strip().toUpperCase(Locale.ROOT);
        Letra letra;
        try {
            letra = Letra.valueOf(texto);
        } catch (IllegalArgumentException e) {
            throw new RegraDeNegocio("Alternativa '%s'%s inválida.".formatted(alternativa, onde));
        }
        if (!questao.tem(letra)) {
            throw new RegraDeNegocio("%s não tem a alternativa %s.".formatted(
                    linha == null ? "Esta questão" : linha, letra));
        }
        return letra;
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
                revelar && q.getVideo() != null ? AcessoServico.descrever(q.getVideo(), true) : null,
                revelar ? q.comentarios() : Map.of());
    }
}
