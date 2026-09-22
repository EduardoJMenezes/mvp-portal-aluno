package br.com.plataforma.contas;

import static java.util.stream.Collectors.joining;

import br.com.plataforma.catalogo.Turma;
import br.com.plataforma.comum.CredenciaisInvalidas;
import br.com.plataforma.comum.Identidade;
import br.com.plataforma.comum.MuitasTentativas;
import br.com.plataforma.comum.NaoAutorizado;
import br.com.plataforma.comum.NaoEncontrado;
import br.com.plataforma.comum.Papel;
import br.com.plataforma.comum.RegraDeNegocio;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Contas: entrar, trocar a senha, alunos da turma e tokens do MCP.
 *
 * <p>As regras de segurança moram aqui, e não na tela:
 *
 * <ul>
 *   <li><b>O login não diz se o e-mail existe.</b> "E-mail ou senha incorretos" nos dois casos,
 *       com o mesmo custo de bcrypt — senão o tempo de resposta contaria.
 *   <li><b>Tentativa demais trava</b>, por conta e por IP, numa janela deslizante.
 *   <li><b>Senha que outra pessoa definiu é temporária.</b> Até o aluno trocar, a sessão só serve
 *       para trocar a senha (quem barra é o filtro do portal, para toda rota).
 *   <li><b>Trocar a senha derruba as sessões antigas</b> ({@code senha_alterada_em}).
 *   <li><b>Senha e token só nascem pelo navegador.</b> Um agente com token do MCP não cria conta,
 *       não redefine senha nem emite outro token para si.
 * </ul>
 */
@Service
public class ContasServico {

    public static final Duration JANELA = Duration.ofMinutes(15);
    public static final int FALHAS_POR_CONTA = 5;
    public static final int FALHAS_POR_IP = 20;
    public static final int SENHA_MINIMA = 10;
    /** bcrypt só lê os primeiros 72 bytes; acima disso a biblioteca recusa. */
    public static final int SENHA_MAXIMA_BYTES = 72;
    public static final List<String> DOMINIOS_DEMO = List.of("@escola.demo", "@aluno.demo");

    private static final Set<String> OBVIAS = Set.of(
            "0123456789", "1234567890", "12345678910", "0987654321", "1111111111",
            "qwertyuiop", "asdfghjkl1", "senha12345", "senha123456", "minhasenha",
            "password12", "password123", "abcdefghij", "abc1234567", "demo123456");

    /** Hash de uma senha que ninguém tem: e-mail inexistente custa o mesmo bcrypt. */
    private static final String HASH_DE_NINGUEM = Senhas.hash(Senhas.novoTokenMcp());

    private final UsuarioRepositorio usuarios;
    private final TokenRepositorio tokens;
    private final MatriculaRepositorio matriculas;
    private final TentativaDeLoginRepositorio tentativas;

    public ContasServico(UsuarioRepositorio usuarios, TokenRepositorio tokens,
            MatriculaRepositorio matriculas, TentativaDeLoginRepositorio tentativas) {
        this.usuarios = usuarios;
        this.tokens = tokens;
        this.matriculas = matriculas;
        this.tentativas = tentativas;
    }

    /** Lido a cada pedido: o papel vem do banco, nunca de um cabeçalho ou de um cookie. */
    public Optional<Usuario> buscar(Integer id) {
        return usuarios.findById(id);
    }

    /**
     * O primeiro e-mail da lista que corresponde a um <b>operador</b> cadastrado.
     *
     * <p>Sem entrada aqui, o login do GitHub não abre sessão nenhuma: o GitHub diz quem entrou, e
     * é esta tabela que diz se essa pessoa opera.
     */
    @Transactional(readOnly = true)
    public Optional<Usuario> operadorPorEmail(List<String> emails) {
        for (var email : emails) {
            var achado = usuarios.findFirstByEmailIgnoreCase(email.strip());
            if (achado.isPresent() && achado.get().getPapel().eOperador()) {
                return achado;
            }
        }
        return Optional.empty();
    }

    /**
     * O operador por trás de um token Bearer opaco do MCP.
     *
     * <p>Recebe o token <b>em claro</b> e guarda só o hash: é o que mantém a promessa de que um
     * dump do banco não devolve credencial usável. Aluno com token válido não passa — barrar aqui
     * impede que a credencial dele sequer abra sessão no MCP.
     */
    @Transactional
    public Optional<Usuario> operadorPorToken(String token, Instant agora) {
        return tokens.findByTokenHashAndRevogadoFalse(Senhas.sha256(token))
                .filter(t -> t.getUsuario().getPapel().eOperador())
                .map(t -> {
                    t.usadoAgora(agora);
                    return t.getUsuario();
                });
    }

    // --- segregação ----------------------------------------------------------

    @Transactional(readOnly = true)
    public List<Integer> turmasDoAluno(Integer usuarioId) {
        return matriculas.turmasDe(usuarioId);
    }

    /** Operador vê qualquer turma; aluno só as suas. É a única porta para conteúdo de turma. */
    @Transactional(readOnly = true)
    public void exigirAcessoATurma(Identidade ident, Turma turma) {
        if (ident.eOperador()) {
            return;
        }
        if (!turmasDoAluno(ident.usuarioId()).contains(turma.getId())) {
            throw new NaoAutorizado("%s não está matriculado em %s e não pode ver este conteúdo."
                    .formatted(ident.nome(), turma.getNome()));
        }
    }

    // --- resolver o aluno ----------------------------------------------------

    @Transactional(readOnly = true)
    public Usuario resolverAluno(String referencia) {
        var alunos = usuarios.findByPapelOrderByNomeAsc(Papel.ALUNO);

        var texto = referencia == null ? "" : referencia.strip().toLowerCase(Locale.ROOT);
        if (!texto.isEmpty() && texto.length() <= 9 && texto.chars().allMatch(Character::isDigit)) {
            var id = Integer.parseInt(texto);
            var porId = alunos.stream().filter(a -> a.getId().equals(id)).findFirst();
            if (porId.isPresent()) {
                return porId.get();
            }
        }

        var exatos = alunos.stream()
                .filter(a -> a.getNome().toLowerCase(Locale.ROOT).equals(texto)
                        || a.getEmail().toLowerCase(Locale.ROOT).equals(texto))
                .toList();
        if (!exatos.isEmpty()) {
            return exatos.getFirst();
        }
        var parciais = alunos.stream()
                .filter(a -> a.getNome().toLowerCase(Locale.ROOT).contains(texto)).toList();
        if (parciais.size() == 1) {
            return parciais.getFirst();
        }
        if (parciais.size() > 1) {
            throw new NaoEncontrado("'%s' corresponde a mais de um aluno: %s."
                    .formatted(referencia, parciais.stream().map(Usuario::getNome).collect(joining(", "))));
        }
        var disponiveis = alunos.isEmpty() ? "(nenhum)"
                : alunos.stream().map(Usuario::getNome).collect(joining(", "));
        throw new NaoEncontrado(
                "Aluno '%s' não encontrado. Alunos: %s.".formatted(referencia, disponiveis));
    }

    // --- entrar --------------------------------------------------------------

    /** Confere e-mail e senha. Mesmo erro e mesmo custo, exista a conta ou não. */
    @Transactional(noRollbackFor = CredenciaisInvalidas.class) // a falha fica gravada: é ela que trava
    public Usuario entrar(String email, String senha, String ip, Instant agora) {
        var conta = email == null ? "" : email.strip().toLowerCase(Locale.ROOT);

        var espera = Math.max(espera(conta, FALHAS_POR_CONTA, agora), espera(ip, FALHAS_POR_IP, agora));
        if (espera > 0) {
            throw new MuitasTentativas(espera);
        }

        var usuario = usuarios.findFirstByEmailIgnoreCase(conta).orElse(null);
        var confere = Senhas.confere(senha, usuario == null ? HASH_DE_NINGUEM : usuario.getSenhaHash());
        if (usuario == null || !confere) {
            registrarFalha(List.of(conta, ip), agora);
            throw new CredenciaisInvalidas();
        }

        tentativas.apagarChave(conta);
        return usuario;
    }

    /** Segundos até a chave poder tentar de novo; 0 quando está livre. */
    private int espera(String chave, int limite, Instant agora) {
        var linha = tentativas.contagem(chave, agora.minus(JANELA)).getFirst();
        var quantas = ((Number) linha[0]).longValue();
        var maisAntiga = (Instant) linha[1];
        if (quantas < limite || maisAntiga == null) {
            return 0;
        }
        return (int) Math.max(1, Duration.between(agora, maisAntiga.plus(JANELA)).toSeconds());
    }

    private void registrarFalha(List<String> chaves, Instant agora) {
        // A limpeza vai junto: a tabela nunca passa das falhas da última janela.
        tentativas.apagarAntesDe(agora.minus(JANELA));
        chaves.forEach(chave -> tentativas.save(new TentativaDeLogin(chave, agora)));
    }

    public record Perfil(
            Integer id, String nome, String email, Papel papel, List<String> turmas, boolean trocarSenha) {}

    /** O que a tela sabe de quem entrou. Nada de hash, nada de token. */
    @Transactional(readOnly = true)
    public Perfil perfil(Usuario u) {
        return new Perfil(u.getId(), u.getNome(), u.getEmail(), u.getPapel(),
                matriculas.nomesDasTurmasDe(u.getId()), u.isSenhaTemporaria());
    }

    // --- senha ---------------------------------------------------------------

    /** Tamanho e o óbvio. Sem regra de maiúscula e símbolo: comprimento protege mais. */
    static void validarNovaSenha(String senha, Usuario usuario) {
        if (senha == null || senha.length() < SENHA_MINIMA) {
            throw new RegraDeNegocio("A senha precisa de pelo menos %d caracteres.".formatted(SENHA_MINIMA));
        }
        if (senha.getBytes(StandardCharsets.UTF_8).length > SENHA_MAXIMA_BYTES) {
            throw new RegraDeNegocio("A senha pode ter no máximo 72 bytes (cerca de 70 letras).");
        }
        var minuscula = senha.toLowerCase(Locale.ROOT);
        var local = usuario.getEmail().split("@")[0].toLowerCase(Locale.ROOT);
        var nomes = java.util.Arrays.stream(usuario.getNome().toLowerCase(Locale.ROOT).split("\\s+"))
                .filter(p -> p.length() >= 4).toList();
        var poucosCaracteres = minuscula.chars().distinct().count() <= 2;
        if (OBVIAS.contains(minuscula) || poucosCaracteres
                || (local.length() >= 4 && minuscula.contains(local))
                || nomes.stream().anyMatch(minuscula::contains)) {
            throw new RegraDeNegocio("Essa senha é fácil de adivinhar. Evite o próprio nome, o e-mail e "
                    + "sequências como 1234567890.");
        }
    }

    @Transactional
    public Usuario trocarSenha(Identidade ident, String senhaAtual, String nova, Instant agora) {
        var usuario = usuarios.findById(ident.usuarioId())
                .orElseThrow(() -> new NaoEncontrado("Esta conta não existe mais."));
        if (!Senhas.confere(senhaAtual, usuario.getSenhaHash())) {
            throw new RegraDeNegocio("A senha atual não confere.");
        }
        if (senhaAtual.equals(nova)) {
            throw new RegraDeNegocio("A nova senha precisa ser diferente da atual.");
        }
        validarNovaSenha(nova, usuario);
        usuario.definirSenha(Senhas.hash(nova), false, agora);
        return usuario;
    }

    // --- alunos da turma -----------------------------------------------------

    public record AlunoNaLista(
            Integer id, String nome, String email, boolean senhaTemporaria, String criadoEm) {}

    private static AlunoNaLista aluno(Usuario u) {
        return new AlunoNaLista(u.getId(), u.getNome(), u.getEmail(), u.isSenhaTemporaria(),
                u.getCriadoEm() == null ? null : u.getCriadoEm().toString());
    }

    public record AlunosDaTurma(Integer turmaId, String turma, List<AlunoNaLista> alunos) {}

    @Transactional(readOnly = true)
    public AlunosDaTurma alunosDaTurma(Identidade ident, Turma turma) {
        ident.exigirOperador();
        return new AlunosDaTurma(turma.getId(), turma.getNome(),
                matriculas.alunosDa(turma).stream().map(ContasServico::aluno).toList());
    }

    public record Matriculado(String turma, AlunoNaLista aluno, String senhaTemporaria) {}

    /** Matricula pelo e-mail. Aluno novo nasce com senha temporária, que só aparece aqui. */
    @Transactional
    public Matriculado matricular(Identidade ident, Turma turma, String nome, String email) {
        ident.exigirOperador();
        ident.exigirHumanoNoPortal("Cadastrar aluno");
        var conta = email == null ? "" : email.strip().toLowerCase(Locale.ROOT);

        var usuario = usuarios.findFirstByEmailIgnoreCase(conta).orElse(null);
        String senha = null;
        if (usuario == null) {
            var limpo = nome == null ? "" : nome.strip();
            if (limpo.isEmpty()) {
                throw new RegraDeNegocio("Informe o nome do aluno: é o que aparece no ranking e no portal.");
            }
            senha = Senhas.temporaria();
            usuario = usuarios.save(new Usuario(limpo, conta, Senhas.hash(senha), Papel.ALUNO, true));
        } else if (usuario.getPapel() != Papel.ALUNO) {
            throw new RegraDeNegocio("%s é a conta de um %s, não de um aluno.".formatted(conta, usuario.getPapel()));
        }

        if (matriculas.findByUsuarioIdAndTurma(usuario.getId(), turma).isPresent()) {
            throw new RegraDeNegocio("%s já está em %s.".formatted(usuario.getNome(), turma.getNome()));
        }
        matriculas.save(new Matricula(usuario.getId(), turma));
        return new Matriculado(turma.getNome(), aluno(usuario), senha);
    }

    public record Desmatriculado(String turma, String aluno, boolean removido) {}

    /**
     * Tira da turma. A matrícula é vínculo, não conteúdo: sai de verdade, e o que o aluno já fez
     * (tentativas, respostas) continua no histórico.
     */
    @Transactional
    public Desmatriculado desmatricular(Identidade ident, Turma turma, String alunoRef) {
        ident.exigirOperador();
        ident.exigirHumanoNoPortal("Tirar aluno da turma");
        var usuario = resolverAluno(alunoRef);
        var matricula = matriculas.findByUsuarioIdAndTurma(usuario.getId(), turma)
                .orElseThrow(() -> new NaoEncontrado(
                        "%s não está em %s.".formatted(usuario.getNome(), turma.getNome())));
        matriculas.delete(matricula);
        return new Desmatriculado(turma.getNome(), usuario.getNome(), true);
    }

    public record SenhaRedefinida(AlunoNaLista aluno, String senhaTemporaria) {}

    /** Nova senha temporária para um aluno. As sessões dele caem na hora. */
    @Transactional
    public SenhaRedefinida redefinirSenha(Identidade ident, String alunoRef, Instant agora) {
        ident.exigirOperador();
        ident.exigirHumanoNoPortal("Redefinir senha de aluno");
        var usuario = resolverAluno(alunoRef);
        var senha = Senhas.temporaria();
        usuario.definirSenha(Senhas.hash(senha), true, agora);
        return new SenhaRedefinida(aluno(usuario), senha);
    }

    // --- tokens do MCP -------------------------------------------------------

    public record TokenNaLista(
            Integer id, String nome, String criadoEm, String ultimoUsoEm, boolean revogado) {}

    public record TokenEmitido(
            Integer id, String nome, String criadoEm, String ultimoUsoEm, boolean revogado,
            String token) {}

    private static TokenNaLista token(TokenDeAcesso t) {
        return new TokenNaLista(t.getId(), t.getNome(),
                t.getCriadoEm() == null ? null : t.getCriadoEm().toString(),
                t.getUltimoUsoEm() == null ? null : t.getUltimoUsoEm().toString(), t.isRevogado());
    }

    /** Os tokens de quem pergunta — só os dele, e nunca o valor. */
    @Transactional(readOnly = true)
    public List<TokenNaLista> tokensDoOperador(Identidade ident) {
        ident.exigirOperador();
        var dono = usuarios.getReferenceById(ident.usuarioId());
        return tokens.findByUsuarioOrderByCriadoEmDescIdDesc(dono).stream().map(ContasServico::token).toList();
    }

    /** O valor em claro sai uma vez, nesta resposta; o banco guarda só o hash. */
    @Transactional
    public TokenEmitido emitirToken(Identidade ident, String nome) {
        ident.exigirOperador();
        ident.exigirHumanoNoPortal("Emitir token do MCP");
        var valor = Senhas.novoTokenMcp();
        var limpo = nome == null || nome.isBlank() ? "Claude" : nome.strip();
        var dono = usuarios.getReferenceById(ident.usuarioId());
        var t = tokens.saveAndFlush(new TokenDeAcesso(dono,
                limpo.substring(0, Math.min(limpo.length(), 120)), Senhas.sha256(valor)));
        var lido = token(tokens.findById(t.getId()).orElseThrow());
        return new TokenEmitido(lido.id(), lido.nome(), lido.criadoEm(), lido.ultimoUsoEm(),
                lido.revogado(), valor);
    }

    @Transactional
    public TokenNaLista revogarToken(Identidade ident, Integer tokenId) {
        ident.exigirOperador();
        ident.exigirHumanoNoPortal("Revogar token do MCP");
        var t = tokens.findById(tokenId)
                .filter(x -> x.getUsuario().getId().equals(ident.usuarioId()))
                .orElseThrow(() -> new NaoEncontrado("Token não encontrado entre os seus."));
        t.revogar();
        return token(t);
    }

    // --- modo demonstração ---------------------------------------------------

    public record ContaDemo(String nome, String email, Papel papel, List<String> turmas) {}

    @Transactional(readOnly = true)
    public List<ContaDemo> contasDemo() {
        var lista = new ArrayList<Usuario>();
        DOMINIOS_DEMO.forEach(d -> lista.addAll(usuarios.findByEmailEndingWith(d)));
        lista.sort(Comparator.comparing((Usuario u) -> u.getPapel().name()).thenComparing(Usuario::getNome));
        return lista.stream()
                .map(u -> new ContaDemo(u.getNome(), u.getEmail(), u.getPapel(),
                        matriculas.nomesDasTurmasDe(u.getId())))
                .toList();
    }

    /** Só as contas de exemplo do seed. Quem decide se o modo está ligado é a borda. */
    @Transactional(readOnly = true)
    public Usuario entrarComoDemo(String email) {
        var conta = email == null ? "" : email.strip().toLowerCase(Locale.ROOT);
        if (DOMINIOS_DEMO.stream().noneMatch(conta::endsWith)) {
            throw new NaoAutorizado("Só as contas de demonstração entram sem senha.");
        }
        return usuarios.findFirstByEmailIgnoreCase(conta)
                .orElseThrow(() -> new NaoEncontrado("Essa conta de demonstração não existe neste banco."));
    }
}
