package br.com.plataforma.menu;

import br.com.plataforma.aulas.AulasServico;
import br.com.plataforma.catalogo.Turma;
import br.com.plataforma.comum.Categoria;
import br.com.plataforma.comum.Identidade;
import br.com.plataforma.comum.RegraDeNegocio;
import br.com.plataforma.contas.ContasServico;
import br.com.plataforma.menu.BotaoDoMenu.Funcionalidade;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * O menu do aluno, montado pelo professor para cada turma (decisão 0009 do cofre). Turma sem menu
 * montado fica com o de sempre. Início e Desempenho não entram aqui: são fixos, na tela.
 */
@Service
public class MenuServico {

    public static final int MAXIMO_DE_BOTOES = 12;
    public static final int MAXIMO_DO_ROTULO = 40;

    public record Botao(String rotulo, Funcionalidade funcionalidade, String categoria) {

        /** Valida e normaliza o que veio de fora: nome, feature (em qualquer caixa) e categoria. */
        public static Botao de(String rotulo, String funcionalidade, String categoria) {
            var nome = rotulo == null ? "" : rotulo.strip();
            if (nome.isEmpty()) {
                throw new RegraDeNegocio("Todo botão precisa de um nome, ex.: 'Simulados Rodmelo'.");
            }
            if (nome.length() > MAXIMO_DO_ROTULO) {
                throw new RegraDeNegocio("'%s' é longo demais para um botão: até %d letras."
                        .formatted(nome, MAXIMO_DO_ROTULO));
            }
            var feature = Arrays.stream(Funcionalidade.values())
                    .filter(f -> funcionalidade != null && f.name().equals(funcionalidade.strip().toUpperCase(Locale.ROOT)))
                    .findFirst()
                    .orElseThrow(() -> new RegraDeNegocio(("'%s' leva a quê? Use uma destas: CURSO (aulas gravadas), "
                            + "AULAS (aulas ao vivo), SIMULADOS ou MATERIAIS.").formatted(nome)));
            return new Botao(nome, feature, Categoria.limpar(categoria));
        }
    }

    /** {@code padrao}: a turma ainda não montou o menu, e vale o de sempre. */
    public record Menu(Integer turmaId, String turma, boolean padrao, List<Botao> botoes) {}

    /** O menu de antes da 0009, para a turma que não montou o seu. */
    public static final List<Botao> PADRAO = List.of(
            new Botao("Curso", Funcionalidade.CURSO, null),
            new Botao("Simulados", Funcionalidade.SIMULADOS, null),
            new Botao("Aulas ao vivo", Funcionalidade.AULAS, null),
            new Botao("Materiais", Funcionalidade.MATERIAIS, null));

    private final BotaoDoMenuRepositorio botoes;
    private final ContasServico contas;
    private final AulasServico aulas;

    public MenuServico(BotaoDoMenuRepositorio botoes, ContasServico contas, AulasServico aulas) {
        this.botoes = botoes;
        this.contas = contas;
        this.aulas = aulas;
    }

    private static Botao botao(BotaoDoMenu b) {
        return new Botao(b.getRotulo(), b.getFuncionalidade(), b.getCategoria());
    }

    @Transactional(readOnly = true)
    public Menu daTurma(Identidade ident, Turma turma) {
        ident.exigirOperador();
        var montado = botoes.findByTurmaIdOrderByOrdemAsc(turma.getId()).stream().map(MenuServico::botao).toList();
        return new Menu(turma.getId(), turma.getNome(), montado.isEmpty(), montado.isEmpty() ? PADRAO : montado);
    }

    /** Troca o menu inteiro, na ordem da lista. Lista vazia devolve a turma ao menu de sempre. */
    @Transactional
    public Menu definir(Identidade ident, Turma turma, List<Botao> novos) {
        ident.exigirOperador();
        if (novos.size() > MAXIMO_DE_BOTOES) {
            throw new RegraDeNegocio("Menu com %d botões não cabe na tela: até %d."
                    .formatted(novos.size(), MAXIMO_DE_BOTOES));
        }
        botoes.apagarDaTurma(turma.getId());
        for (int i = 0; i < novos.size(); i++) {
            var b = novos.get(i);
            botoes.save(new BotaoDoMenu(turma.getId(), b.rotulo(), b.funcionalidade(), b.categoria(), i + 1));
        }
        return daTurma(ident, turma);
    }

    /** O menu de uma turma vira o da outra. Copiar o menu de sempre é voltar ao de sempre. */
    @Transactional
    public Menu copiar(Identidade ident, Turma de, Turma para) {
        var origem = daTurma(ident, de);
        return definir(ident, para, origem.padrao() ? List.of() : origem.botoes());
    }

    // --- o aluno ----------------------------------------------------------------

    /** {@code aoVivo}: "AGORA" com aula no ar dentro do destino; "EM_BREVE" com a sala já aberta. */
    public record BotaoDoAluno(String rotulo, Funcionalidade funcionalidade, String categoria, String aoVivo) {}

    /** Os botões das turmas do aluno, com o aviso de ao vivo de cada um. */
    @Transactional(readOnly = true)
    public List<BotaoDoAluno> doAluno(Identidade ident, Instant agora) {
        var turmas = ident.eOperador() ? List.<Integer>of() : contas.turmasDoAluno(ident.usuarioId());
        var montados = turmas.isEmpty() ? List.<Botao>of()
                : botoes.findByTurmaIdInOrderByTurmaIdAscOrdemAsc(turmas).stream().map(MenuServico::botao)
                        .distinct().toList();
        var noAr = aulas.noAr(ident, agora);
        var saida = new ArrayList<BotaoDoAluno>();
        for (var b : montados.isEmpty() ? PADRAO : montados) {
            saida.add(new BotaoDoAluno(b.rotulo(), b.funcionalidade(), b.categoria(), aoVivo(b, noAr)));
        }
        return saida;
    }

    /** A aula acende o botão da feature dela e, se tiver capítulo, o botão do curso que o mostra. */
    static String aoVivo(Botao b, List<AulasServico.NoAr> noAr) {
        var casam = noAr.stream().filter(a -> switch (b.funcionalidade()) {
            case AULAS -> Categoria.casa(b.categoria(), a.categoria());
            case CURSO -> a.temCapitulo() && Categoria.casa(b.categoria(), a.categoriaDoCapitulo());
            default -> false;
        }).toList();
        if (casam.stream().anyMatch(AulasServico.NoAr::agora)) {
            return "AGORA";
        }
        return casam.isEmpty() ? null : "EM_BREVE";
    }
}
