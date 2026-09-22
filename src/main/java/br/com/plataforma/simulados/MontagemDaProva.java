package br.com.plataforma.simulados;

import br.com.plataforma.comum.ErroDominio;
import br.com.plataforma.comum.Identidade;
import br.com.plataforma.comum.NaoEncontrado;
import br.com.plataforma.comum.RegraDeNegocio;
import br.com.plataforma.questoes.Questao;
import br.com.plataforma.questoes.QuestoesServico;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * As questões da prova, na ordem, a partir do que o comando recebeu.
 *
 * <p>Cada entrada é o id de uma questão — publicada no acervo, ou uma das que já estão no
 * simulado — ou a questão nova inteira, que nasce no rascunho. Sem rascunho (simulado já
 * publicado), questão nova não entra: conteúdo novo só chega ao aluno por um rascunho aprovado.
 */
@Service
public class MontagemDaProva {

    private final QuestoesServico questoes;

    public MontagemDaProva(QuestoesServico questoes) {
        this.questoes = questoes;
    }

    public sealed interface Entrada permits PorId, Nova {}

    public record PorId(String questaoId) implements Entrada {}

    public record Nova(QuestoesServico.DadosDaQuestaoNova dados) implements Entrada {}

    @Transactional
    public List<Questao> montar(Identidade ident, List<Entrada> entradas, Integer rascunhoId,
            Map<Integer, Questao> atuais, Map<Integer, QuestoesServico.DadosDoVideo> resolucoes) {
        var prova = new ArrayList<Questao>();

        for (int i = 0; i < entradas.size(); i++) {
            var ordem = i + 1;
            try {
                var questao = switch (entradas.get(i)) {
                    case Nova nova -> {
                        if (rascunhoId == null) {
                            throw new RegraDeNegocio(
                                    "questão nova só entra em simulado ainda em rascunho. Cadastre com "
                                            + "criar_questao_rascunho, publique e use o id dela.");
                        }
                        var numero = nova.dados().numero() == null ? ordem : nova.dados().numero();
                        yield questoes.criarNova(ident, rascunhoId, nova.dados(),
                                resolucoes == null ? null : resolucoes.get(numero));
                    }
                    case PorId porId -> daProvaOuDoAcervo(porId.questaoId(), atuais);
                };

                for (var ja : prova) {
                    if (ja.getId().equals(questao.getId())) {
                        throw new RegraDeNegocio(
                                "a questão %d já entrou antes nesta prova.".formatted(questao.getId()));
                    }
                }
                prova.add(questao);
            } catch (ErroDominio e) {
                // Numa prova de 15, o erro sem a posição não diz qual corrigir.
                throw comPosicao(e, ordem);
            }
        }
        return prova;
    }

    private Questao daProvaOuDoAcervo(String referencia, Map<Integer, Questao> atuais) {
        var texto = referencia == null ? "" : referencia.strip();
        if (atuais != null && texto.length() <= 9 && !texto.isEmpty()
                && texto.chars().allMatch(Character::isDigit)) {
            var jaEsta = atuais.get(Integer.parseInt(texto));
            if (jaEsta != null) {
                return jaEsta;
            }
        }
        return questoes.questaoPublicada(texto);
    }

    /** Mantém o tipo do erro — o status HTTP dele depende disso — e põe a posição na frente. */
    private static ErroDominio comPosicao(ErroDominio e, int ordem) {
        var mensagem = "Questão %d da prova: %s".formatted(ordem, e.getMessage());
        return switch (e) {
            case NaoEncontrado ignored -> new NaoEncontrado(mensagem);
            case br.com.plataforma.comum.NaoAutorizado ignored ->
                    new br.com.plataforma.comum.NaoAutorizado(mensagem);
            case RegraDeNegocio ignored -> new RegraDeNegocio(mensagem);
            // Login não acontece dentro de uma prova: os dois só existem para o switch fechar.
            case br.com.plataforma.comum.CredenciaisInvalidas ignored -> e;
            case br.com.plataforma.comum.MuitasTentativas ignored -> e;
        };
    }
}
