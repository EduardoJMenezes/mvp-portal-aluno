package br.com.plataforma.catalogo;

import static java.util.stream.Collectors.joining;

import br.com.plataforma.comum.Identidade;
import br.com.plataforma.comum.NaoEncontrado;
import br.com.plataforma.comum.Status;
import br.com.plataforma.estrutura.EstruturaServico;
import java.util.List;
import java.util.Locale;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class CatalogoServico {

    private final TurmaRepositorio turmas;
    private final EstruturaServico estrutura;

    public CatalogoServico(TurmaRepositorio turmas, EstruturaServico estrutura) {
        this.turmas = turmas;
        this.estrutura = estrutura;
    }

    /**
     * Acha a turma pelo id, pelo nome exato ou por um pedaço único do nome.
     *
     * <p>Aceita nome porque quem chama é um modelo repetindo o que o professor disse
     * ("Extensivo 2027"), não um sistema com ids. Quando não acha, lista as que existem: a
     * mensagem é o que o modelo lê para se corrigir.
     */
    @Transactional(readOnly = true)
    public Turma resolverTurma(String referencia) {
        var todas = turmas.findAllByOrderByNomeAsc();
        var texto = referencia == null ? "" : referencia.strip().toLowerCase(Locale.ROOT);

        if (!texto.isEmpty() && texto.length() <= 18 && texto.chars().allMatch(Character::isDigit)) {
            var id = Long.parseLong(texto);
            for (var t : todas) {
                if (t.getId() == id) {
                    return t;
                }
            }
        }

        var exatas = todas.stream().filter(t -> minusculo(t).equals(texto)).toList();
        if (!exatas.isEmpty()) {
            return exatas.getFirst();
        }

        var parciais = todas.stream().filter(t -> minusculo(t).contains(texto)).toList();
        if (parciais.size() == 1) {
            return parciais.getFirst();
        }
        if (parciais.size() > 1) {
            throw new NaoEncontrado(
                    "'%s' corresponde a mais de uma turma: %s.".formatted(referencia, nomes(parciais)));
        }

        var disponiveis = todas.isEmpty() ? "(nenhuma cadastrada)" : nomes(todas);
        throw new NaoEncontrado("Turma '%s' não existe. Turmas: %s.".formatted(referencia, disponiveis));
    }

    /** Várias turmas de uma vez, sem repetir: "Extensivo, Extensivo" é uma turma só. */
    @Transactional(readOnly = true)
    public List<Turma> resolverTurmas(List<String> referencias) {
        var achadas = new java.util.LinkedHashMap<Integer, Turma>();
        for (var ref : referencias) {
            var t = resolverTurma(ref);
            achadas.putIfAbsent(t.getId(), t);
        }
        return List.copyOf(achadas.values());
    }

    public record TurmaNaLista(
            Integer id, String nome, Integer ano, int alunos, int modulos,
            int itensPublicados, Integer itensEmRascunho) {}

    @Transactional(readOnly = true)
    public List<TurmaNaLista> listarTurmas(Identidade ident) {
        return turmas.findAllByOrderByAnoAscNomeAsc().stream()
                .map(t -> new TurmaNaLista(
                        t.getId(), t.getNome(), t.getAno(),
                        turmas.contarAlunos(t.getId()),
                        estrutura.contarModulos(t),
                        estrutura.contarItensDaTurma(t, Status.PUBLICADO),
                        ident.eOperador() ? estrutura.contarItensDaTurma(t, Status.RASCUNHO) : null))
                .toList();
    }

    /** A árvore módulo › sub-módulo › item, de uma turma ou de todas. */
    @Transactional(readOnly = true)
    public List<EstruturaServico.ModuloNaArvore> listarModulos(Identidade ident, String turma) {
        var alvos = turma != null
                ? List.of(resolverTurma(turma))
                : turmas.findAllByOrderByAnoAscNomeAsc();

        return alvos.stream()
                .flatMap(t -> estrutura.arvoreDaTurma(t, ident.eAluno()).stream())
                .toList();
    }

    private static String minusculo(Turma t) {
        return t.getNome().toLowerCase(Locale.ROOT);
    }

    private static String nomes(List<Turma> lista) {
        return lista.stream().map(Turma::getNome).collect(joining(", "));
    }
}
