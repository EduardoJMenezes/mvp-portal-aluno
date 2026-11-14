package br.com.plataforma.vendas;

import br.com.plataforma.catalogo.CatalogoServico;
import br.com.plataforma.catalogo.Turma;
import br.com.plataforma.comum.Identidade;
import br.com.plataforma.comum.NaoEncontrado;
import br.com.plataforma.comum.RegraDeNegocio;
import br.com.plataforma.comum.Status;
import br.com.plataforma.contas.ContasServico;
import br.com.plataforma.contas.Senhas;
import br.com.plataforma.contas.Usuario;
import br.com.plataforma.estrutura.EstruturaServico;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Base64;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * A venda inteira: o plano que o professor monta, o formulário público, o checkout do Asaas, o
 * aviso de pago e o fim do acesso.
 *
 * <p>O acesso é matrícula: pago, o aluno entra nas turmas do plano como se o professor o tivesse
 * matriculado. A matrícula guarda o pedido que a deu, e só essa sai quando o acesso acaba — a feita à
 * mão, nunca.
 */
@Service
public class VendasServico {

    private static final Logger log = LoggerFactory.getLogger(VendasServico.class);
    static final ZoneId BRASILIA = ZoneId.of("America/Sao_Paulo");
    /** Quanto a mensalidade pode atrasar antes de o acesso cair. O Asaas tenta cobrar de novo nesse meio. */
    static final Duration TOLERANCIA_DE_ATRASO = Duration.ofDays(5);
    private static final Pattern LINK = Pattern.compile("^[a-z0-9]+(-[a-z0-9]+)*$");
    private static final Pattern EMAIL = Pattern.compile("^[^@\\s]+@[^@\\s]+\\.[^@\\s]+$");
    private static final SecureRandom SORTEIO = new SecureRandom();
    /**
     * A versão dos termos de matrícula que a página /termos mostra. Mudou o texto lá
     * ({@code frontend/app/termos/page.tsx}), muda aqui: o pedido guarda qual versão o aluno aceitou.
     */
    public static final String TERMOS_VERSAO = "2026-11-12";

    private final PlanoRepositorio planos;
    private final PedidoRepositorio pedidos;
    private final CatalogoServico catalogo;
    private final EstruturaServico estrutura;
    private final ContasServico contas;
    private final Asaas asaas;

    public VendasServico(PlanoRepositorio planos, PedidoRepositorio pedidos, CatalogoServico catalogo,
            EstruturaServico estrutura, ContasServico contas, Asaas asaas) {
        this.planos = planos;
        this.pedidos = pedidos;
        this.catalogo = catalogo;
        this.estrutura = estrutura;
        this.contas = contas;
        this.asaas = asaas;
    }

    // --- planos (professor) --------------------------------------------------

    /** Uma linha da tabela de preços do pagamento único. {@code aPartirDe} vazio: desde já. */
    public record PrecoDoPlano(LocalDate aPartirDe, Integer pixCentavos, Integer cartaoCentavos, Integer cartaoParcelas) {}

    /**
     * Na edição, o que não veio fica como está. {@code mensalidades} e {@code matriculaCentavos}: 0 tira.
     * {@code aVendaAte}: "" tira o prazo. {@code precos}: a tabela inteira do pagamento único — sem
     * ela, {@code precoCentavos} vale no Pix e no cartão.
     */
    public record DadosDoPlano(String nome, String link, String tipo, Integer precoCentavos, Integer parcelasMax,
            Integer mensalidades, LocalDate acessoAte, List<String> turmas, Boolean ativo, Integer matriculaCentavos,
            Integer matriculaParcelasMax, String aVendaAte, List<PrecoDoPlano> precos) {}

    public record PlanoNaLista(Integer planoId, String nome, String link, Plano.Tipo tipo, Integer precoCentavos,
            Integer parcelasMax, Integer mensalidades, LocalDate acessoAte, boolean ativo, List<String> turmas,
            long alunosComAcesso, Integer matriculaCentavos, Integer matriculaParcelasMax, LocalDate aVendaAte,
            List<PrecoDoPlano> precos) {}

    @Transactional(readOnly = true)
    public List<PlanoNaLista> listarPlanos(Identidade ident) {
        ident.exigirOperador();
        return planos.findAllByOrderByAtivoDescNomeAsc().stream().map(this::naLista).toList();
    }

    private PlanoNaLista naLista(Plano p) {
        return new PlanoNaLista(p.getId(), p.getNome(), p.getLink(), p.getTipo(), p.getPrecoCentavos(),
                p.getParcelasMax(), p.getMensalidades(), p.getAcessoAte(), p.isAtivo(), p.getTurmas().stream().map(Turma::getNome).toList(),
                pedidos.comAcesso(p), p.getMatriculaCentavos(), p.getMatriculaParcelasMax(), p.getAVendaAte(),
                precos(p));
    }

    private static List<PrecoDoPlano> precos(Plano p) {
        return p.getPrecos().stream().map(l -> new PrecoDoPlano(l.getAPartirDe(), l.getPixCentavos(),
                l.getCartaoCentavos(), l.getCartaoParcelas())).toList();
    }

    @Transactional
    public PlanoNaLista criarPlano(Identidade ident, DadosDoPlano d, Instant agora) {
        ident.exigirOperador();
        var plano = new Plano("", "", Plano.Tipo.MENSAL, 500, 1, null, List.of());
        aplicar(plano, d, true, agora);
        return salvar(plano);
    }

    @Transactional
    public PlanoNaLista editarPlano(Identidade ident, Integer planoId, DadosDoPlano d, Instant agora) {
        ident.exigirOperador();
        var plano = planos.findById(planoId).orElseThrow(() -> new NaoEncontrado("Plano %d não existe.".formatted(planoId)));
        aplicar(plano, d, false, agora);
        return salvar(plano);
    }

    public record VendaMontada(boolean criada, PlanoNaLista plano, String aviso) {}

    /**
     * O plano montado pelo Claude do professor, achado pelo link: existe, edita; não existe, cria.
     * Criado por aqui nasce FORA DO AR, mesmo que peçam o contrário: o professor confere a tabela na
     * tela de Vendas e só depois manda pôr no ar, numa segunda chamada. Preço errado cobra o aluno.
     */
    @Transactional
    public VendaMontada montarVenda(Identidade ident, String link, DadosDoPlano d, Instant agora) {
        ident.exigirOperador();
        var chave = link == null ? "" : link.strip().toLowerCase(Locale.ROOT);
        var existente = planos.findFirstByLinkIgnoreCase(chave);
        if (existente.isPresent()) {
            return new VendaMontada(false, editarPlano(ident, existente.get().getId(), d, agora), null);
        }
        var pediuNoAr = Boolean.TRUE.equals(d.ativo());
        var foraDoAr = new DadosDoPlano(d.nome(), chave, d.tipo() == null ? "UNICO" : d.tipo(), d.precoCentavos(), d.parcelasMax(), d.mensalidades(),
                d.acessoAte(), d.turmas(), false, d.matriculaCentavos(), d.matriculaParcelasMax(), d.aVendaAte(), d.precos());
        var criado = criarPlano(ident, foraDoAr, agora);
        return new VendaMontada(true, criado, pediuNoAr
                ? "O plano nasceu fora do ar, como todo plano criado pelo chat. Confira na tela de Vendas e peça para pôr no ar."
                : null);
    }

    public record PrecoDeHoje(Integer pixCentavos, Integer cartaoCentavos, Integer cartaoParcelas, LocalDate valeAte) {}

    public record PlanoNoResumo(PlanoNaLista plano, PrecoDeHoje hoje, boolean vendaEncerrada) {}

    public record EnvioPendente(Integer pedidoId, String nome, String plano, String endereco, String celular,
            boolean falta1, boolean falta2) {}

    public record ResumoDasVendas(List<PlanoNoResumo> planos, Map<String, Long> pedidos, long matriculaPagaSemCurso,
            List<EnvioPendente> enviosPendentes) {}

    /** Tudo o que o professor pergunta sobre a venda: planos com o preço de hoje, pedidos e envios. */
    @Transactional(readOnly = true)
    public ResumoDasVendas resumo(Identidade ident) {
        ident.exigirOperador();
        var hoje = LocalDate.now(BRASILIA);
        var listaDePlanos = planos.findAllByOrderByAtivoDescNomeAsc().stream().map(p -> {
            var dia = p.getTipo() == Plano.Tipo.UNICO ? p.precoDoDia(hoje).orElse(null) : null;
            var preco = dia == null ? null : new PrecoDeHoje(dia.getPixCentavos(), dia.getCartaoCentavos(),
                    dia.getCartaoParcelas(), p.precoValeAte(hoje));
            return new PlanoNoResumo(naLista(p), preco, p.vendaEncerrada(hoje));
        }).toList();
        var todos = pedidos.findTop200ByOrderByIdDesc();
        var porStatus = new java.util.TreeMap<String, Long>();
        todos.forEach(p -> porStatus.merge(p.getStatus().name(), 1L, Long::sum));
        var semCurso = todos.stream().filter(Pedido::faltaPagarOCurso).count();
        var envios = todos.stream()
                .filter(p -> p.isAcessoLiberado() && p.getRua() != null && (p.getEnvio1Em() == null || p.getEnvio2Em() == null))
                .map(p -> new EnvioPendente(p.getId(), p.getNome(), p.getPlano().getNome(), enderecoPorExtenso(p),
                        p.getCelular(), p.getEnvio1Em() == null, p.getEnvio2Em() == null))
                .toList();
        return new ResumoDasVendas(listaDePlanos, porStatus, semCurso, envios);
    }

    private PlanoNaLista salvar(Plano plano) {
        try {
            return naLista(planos.saveAndFlush(plano));
        } catch (DataIntegrityViolationException e) {
            throw new RegraDeNegocio("Já existe um plano com o link '%s'. Escolha outro.".formatted(plano.getLink()));
        }
    }

    /** Na criação tudo é obrigatório; na edição, o que não veio fica como está. */
    private void aplicar(Plano p, DadosDoPlano d, boolean novo, Instant agora) {
        var nome = escolher(d.nome(), p.getNome(), novo);
        if (nome == null || nome.isBlank() || nome.strip().length() > 120) {
            throw new RegraDeNegocio("O plano precisa de um nome de até 120 letras, ex.: 'Extensivo 2026 — mensal'.");
        }
        var link = escolher(d.link(), p.getLink(), novo);
        link = link == null ? "" : link.strip().toLowerCase(Locale.ROOT);
        if (link.length() < 3 || link.length() > 60 || !LINK.matcher(link).matches()) {
            throw new RegraDeNegocio("O link usa só letras minúsculas, números e hífen, de 3 a 60 caracteres, "
                    + "ex.: 'extensivo-2026'.");
        }
        Plano.Tipo tipo;
        try {
            tipo = d.tipo() == null ? (novo ? null : p.getTipo()) : Plano.Tipo.valueOf(d.tipo().strip().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            tipo = null;
        }
        if (tipo == null) {
            throw new RegraDeNegocio("Tipo do plano: MENSAL (assinatura no cartão) ou UNICO (Pix ou cartão parcelado).");
        }
        var unico = tipo == Plano.Tipo.UNICO;
        var linhas = unico && d.precos() != null ? tabelaDePrecos(d.precos()) : null;
        Integer preco;
        int parcelas;
        if (linhas != null) {
            // A primeira linha é o preço de referência do plano (lista, ordenação).
            preco = linhas.getFirst().pixCentavos() != null ? linhas.getFirst().pixCentavos() : linhas.getFirst().cartaoCentavos();
            parcelas = linhas.getFirst().cartaoParcelas();
        } else {
            preco = d.precoCentavos() == null ? (novo ? null : p.getPrecoCentavos()) : d.precoCentavos();
            if (preco == null || preco < 500) {
                throw new RegraDeNegocio("O preço mínimo que o Asaas cobra é R$ 5,00.");
            }
            parcelas = tipo == Plano.Tipo.MENSAL ? 1
                    : d.parcelasMax() != null ? d.parcelasMax() : (novo ? 1 : p.getParcelasMax());
            if (parcelas < 1 || parcelas > 12) {
                throw new RegraDeNegocio("Parcelas: de 1 a 12.");
            }
            // Sem tabela, o preço único vale no Pix e no cartão — como antes da tabela existir.
            if (unico && (novo || p.getTipo() != Plano.Tipo.UNICO || d.precoCentavos() != null || d.parcelasMax() != null)) {
                linhas = List.of(new Plano.LinhaDePreco(null, preco, preco, parcelas));
            }
        }
        Integer matricula = !unico ? null
                : d.matriculaCentavos() != null ? (d.matriculaCentavos() == 0 ? null : d.matriculaCentavos())
                : novo ? null : p.getMatriculaCentavos();
        if (matricula != null && matricula < 500) {
            throw new RegraDeNegocio("A matrícula mínima que o Asaas cobra é R$ 5,00 (ou sem matrícula).");
        }
        var parcelasDaMatricula = d.matriculaParcelasMax() != null ? d.matriculaParcelasMax()
                : novo ? 1 : p.getMatriculaParcelasMax();
        if (parcelasDaMatricula < 1 || parcelasDaMatricula > 12) {
            throw new RegraDeNegocio("Parcelas da matrícula: de 1 a 12.");
        }
        LocalDate aVendaAte;
        try {
            aVendaAte = d.aVendaAte() == null ? (novo ? null : p.getAVendaAte())
                    : d.aVendaAte().isBlank() ? null : LocalDate.parse(d.aVendaAte().strip());
        } catch (java.time.format.DateTimeParseException e) {
            throw new RegraDeNegocio("'À venda até' é uma data, ex.: 2027-08-31.");
        }
        Integer mensalidades = tipo != Plano.Tipo.MENSAL ? null
                : d.mensalidades() != null ? (d.mensalidades() == 0 ? null : d.mensalidades())
                : novo ? null : p.getMensalidades();
        if (mensalidades != null && (mensalidades < 2 || mensalidades > 24)) {
            throw new RegraDeNegocio("Número de mensalidades: de 2 a 24 (ou sem limite, até o aluno cancelar).");
        }
        var acessoAte = tipo == Plano.Tipo.MENSAL ? null : d.acessoAte() != null ? d.acessoAte() : (novo ? null : p.getAcessoAte());
        if (acessoAte != null && acessoAte.isBefore(LocalDate.now(BRASILIA))) {
            throw new RegraDeNegocio("O 'acesso até' já passou.");
        }
        var turmas = d.turmas() == null ? (novo ? List.<Turma>of() : p.getTurmas()) : catalogo.resolverTurmas(d.turmas());
        if (turmas.isEmpty()) {
            throw new RegraDeNegocio("Escolha ao menos uma turma: é o que o aluno passa a acessar.");
        }
        var ativo = d.ativo() == null ? (novo || p.isAtivo()) : d.ativo();
        p.mudar(nome.strip(), link, tipo, preco, parcelas, mensalidades, acessoAte, List.copyOf(turmas), ativo, novo ? null : agora);
        p.mudarMatricula(matricula, parcelasDaMatricula);
        p.mudarVendaAte(aVendaAte);
        if (!unico) {
            p.mudarPrecos(List.of());
        } else if (linhas != null) {
            p.mudarPrecos(linhas);
        }
    }

    /** A tabela do pagamento único: uma linha "desde já" e, se quiser, outras a partir de uma data. */
    private static List<Plano.LinhaDePreco> tabelaDePrecos(List<PrecoDoPlano> recebidas) {
        if (recebidas.isEmpty() || recebidas.size() > 24) {
            throw new RegraDeNegocio("A tabela de preços tem de 1 a 24 linhas.");
        }
        var linhas = recebidas.stream()
                .map(l -> new Plano.LinhaDePreco(l.aPartirDe(), l.pixCentavos(), l.cartaoCentavos(),
                        l.cartaoParcelas() == null ? 1 : l.cartaoParcelas()))
                .sorted(java.util.Comparator.comparing(Plano.LinhaDePreco::aPartirDe, Plano.PELA_DATA))
                .toList();
        if (linhas.getFirst().aPartirDe() != null) {
            throw new RegraDeNegocio("A primeira linha de preço vale desde já: deixe a data dela vazia.");
        }
        var datas = new java.util.HashSet<LocalDate>();
        for (var l : linhas) {
            var quando = l.aPartirDe() == null ? "desde já" : "a partir de " + l.aPartirDe();
            if (!datas.add(l.aPartirDe() == null ? LocalDate.MIN : l.aPartirDe())) {
                throw new RegraDeNegocio("Duas linhas de preço com a mesma data (%s).".formatted(quando));
            }
            if (l.pixCentavos() == null && l.cartaoCentavos() == null) {
                throw new RegraDeNegocio("A linha %s precisa do preço no Pix, no cartão ou dos dois.".formatted(quando));
            }
            if ((l.pixCentavos() != null && l.pixCentavos() < 500) || (l.cartaoCentavos() != null && l.cartaoCentavos() < 500)) {
                throw new RegraDeNegocio("O preço mínimo que o Asaas cobra é R$ 5,00 (linha %s).".formatted(quando));
            }
            if (l.cartaoParcelas() < 1 || l.cartaoParcelas() > 12) {
                throw new RegraDeNegocio("Parcelas no cartão: de 1 a 12 (linha %s).".formatted(quando));
            }
        }
        return linhas;
    }

    private static String escolher(String novo, String atual, boolean criando) {
        return novo != null ? novo : (criando ? null : atual);
    }

    public record PedidoNaLista(Integer pedidoId, Instant criadoEm, String nome, String email, String plano,
            Integer valorCentavos, Pedido.Status status, Instant pagoEm, Instant acessoAte, boolean acessoLiberado,
            Pedido.Forma forma, Integer cursoCentavos, Integer cartaoParcelas, Integer matriculaCentavos,
            Instant matriculaPagaEm, String celular, String endereco, String termosVersao, Instant envio1Em,
            Instant envio2Em) {}

    @Transactional(readOnly = true)
    public List<PedidoNaLista> listarPedidos(Identidade ident) {
        ident.exigirOperador();
        return pedidos.findTop200ByOrderByIdDesc().stream().map(VendasServico::naLista).toList();
    }

    private static PedidoNaLista naLista(Pedido p) {
        return new PedidoNaLista(p.getId(), p.getCriadoEm(), p.getNome(), p.getEmail(), p.getPlano().getNome(),
                p.getValorCentavos(), p.getStatus(), p.getPagoEm(), p.getAcessoAte(), p.isAcessoLiberado(), p.getForma(),
                p.getCursoCentavos(), p.getCartaoParcelas(), p.getMatriculaCentavos(), p.getMatriculaPagaEm(),
                p.getCelular(), enderecoPorExtenso(p), p.getTermosVersao(), p.getEnvio1Em(), p.getEnvio2Em());
    }

    /** "Rua X, 100, apto 2 — Centro, Recife/PE — CEP 53030-260", para a etiqueta do envio. */
    private static String enderecoPorExtenso(Pedido p) {
        if (p.getRua() == null) {
            return null;
        }
        var cep = p.getCep() == null || p.getCep().length() != 8 ? p.getCep()
                : p.getCep().substring(0, 5) + "-" + p.getCep().substring(5);
        return "%s, %s%s — %s, %s/%s — CEP %s".formatted(p.getRua(), p.getNumero(),
                p.getComplemento() == null ? "" : ", " + p.getComplemento(), p.getBairro(), p.getCidade(), p.getUf(), cep);
    }

    /** O professor marca que o 1º ou o 2º envio do material saiu (ou desmarca). */
    @Transactional
    public PedidoNaLista marcarEnvio(Identidade ident, Integer pedidoId, Integer qual, Boolean enviado, Instant agora) {
        ident.exigirOperador();
        if (qual == null || (qual != 1 && qual != 2) || enviado == null) {
            throw new RegraDeNegocio("Diga qual envio (1 ou 2) e se saiu (enviado: true ou false).");
        }
        var p = pedidos.findById(pedidoId).orElseThrow(() -> new NaoEncontrado("Pedido %d não existe.".formatted(pedidoId)));
        p.envio(qual, enviado, agora);
        return naLista(p);
    }

    // --- a página pública ----------------------------------------------------

    public record TurmaNoPlano(String nome, int modulos, int aulas) {}

    /**
     * O plano como o aluno vê hoje. No pagamento único, {@code pixCentavos}, {@code cartaoCentavos} e
     * {@code cartaoParcelas} são a linha da tabela que vale hoje, e {@code precoValeAte} é até quando.
     */
    public record PlanoPublico(String nome, String link, Plano.Tipo tipo, Integer precoCentavos, Integer parcelasMax,
            Integer mensalidades, LocalDate acessoAte, List<TurmaNoPlano> turmas, Integer matriculaCentavos,
            Integer matriculaParcelasMax, Integer pixCentavos, Integer cartaoCentavos, Integer cartaoParcelas,
            LocalDate precoValeAte, LocalDate aVendaAte, String termosVersao) {}

    @Transactional(readOnly = true)
    public PlanoPublico planoPublico(String link) {
        var p = aVenda(link);
        var hoje = LocalDate.now(BRASILIA);
        var dia = p.getTipo() == Plano.Tipo.UNICO ? p.precoDoDia(hoje).orElse(null) : null;
        return new PlanoPublico(p.getNome(), p.getLink(), p.getTipo(), p.getPrecoCentavos(), p.getParcelasMax(),
                p.getMensalidades(), p.getAcessoAte(), p.getTurmas().stream().map(t -> new TurmaNoPlano(t.getNome(),
                        estrutura.contarModulos(t), estrutura.contarItensDaTurma(t, Status.PUBLICADO))).toList(),
                p.getMatriculaCentavos(), p.getMatriculaParcelasMax(),
                dia == null ? null : dia.getPixCentavos(), dia == null ? null : dia.getCartaoCentavos(),
                dia == null ? null : dia.getCartaoParcelas(), dia == null ? null : p.precoValeAte(hoje), p.getAVendaAte(),
                TERMOS_VERSAO);
    }

    private Plano aVenda(String link) {
        var plano = planos.findFirstByLinkIgnoreCase(link == null ? "" : link.strip())
                .filter(Plano::isAtivo)
                .orElseThrow(() -> new NaoEncontrado("Este plano não está à venda."));
        if (plano.vendaEncerrada(LocalDate.now(BRASILIA))) {
            throw new NaoEncontrado("As matrículas deste plano estão encerradas.");
        }
        return plano;
    }

    /**
     * {@code forma}: no pagamento único, PIX ou CARTAO (vazio vale se o plano só tem uma). {@code
     * aceiteTermos}: o aluno marcou que leu e aceita os termos de matrícula — sem isso, não há compra.
     */
    public record DadosDaCompra(String nome, String email, String cpf, String celular, String cep, String numero,
            String complemento, String forma, Boolean aceiteTermos) {}

    public record CompraIniciada(String checkout) {}

    /**
     * Grava o pedido e abre o checkout no Asaas, já com os dados do aluno. A volta do checkout traz
     * um token só deste pedido — é ele que deixa a página de volta criar a senha da conta nova.
     *
     * <p>No Pix, curso e matrícula vão num pagamento só. No cartão, a matrícula vem primeiro (Pix ou
     * cartão, com as parcelas dela) e o curso depois, pela página de volta: o Asaas não parcela duas
     * coisas de jeitos diferentes no mesmo pagamento.
     */
    @Transactional
    public CompraIniciada comprar(String link, DadosDaCompra d, String ip, String base, Instant agora) {
        var plano = aVenda(link);
        var nome = d.nome() == null ? "" : d.nome().strip().replaceAll("\\s+", " ");
        if (nome.length() < 5 || !nome.contains(" ") || nome.length() > 200) {
            throw new RegraDeNegocio("Informe o nome completo.");
        }
        var email = d.email() == null ? "" : d.email().strip().toLowerCase(Locale.ROOT);
        if (!EMAIL.matcher(email).matches() || email.length() > 200) {
            throw new RegraDeNegocio("E-mail inválido.");
        }
        var cpf = d.cpf() == null ? "" : d.cpf().replaceAll("\\D", "");
        if (!cpfValido(cpf)) {
            throw new RegraDeNegocio("CPF inválido.");
        }
        var celular = d.celular() == null ? "" : d.celular().replaceAll("\\D", "");
        if (!celular.isEmpty() && (celular.length() < 10 || celular.length() > 11)) {
            throw new RegraDeNegocio("Celular inválido: use DDD e número, ex.: (81) 99999-9999.");
        }
        var cep = d.cep() == null ? "" : d.cep().replaceAll("\\D", "");
        if (cep.length() != 8) {
            throw new RegraDeNegocio("CEP inválido: são 8 números, ex.: 53030-260.");
        }
        var numero = d.numero() == null ? "" : d.numero().strip();
        if (numero.isEmpty() || numero.length() > 10) {
            throw new RegraDeNegocio("Informe o número do endereço (ou S/N).");
        }
        var complemento = d.complemento() == null || d.complemento().isBlank() ? null : d.complemento().strip();
        if (complemento != null && complemento.length() > 60) {
            throw new RegraDeNegocio("O complemento tem até 60 letras, ex.: 'apto 302, bloco B'.");
        }
        var valores = valores(plano, d.forma(), LocalDate.now(BRASILIA));
        if (!Boolean.TRUE.equals(d.aceiteTermos())) {
            throw new RegraDeNegocio("Para seguir, leia e aceite os termos de matrícula.");
        }
        contas.limitar("compra:" + ip, 10, agora);

        var achado = asaas.endereco(cep);
        var endereco = new Pedido.Endereco(cep, achado.rua(), numero, complemento, achado.bairro(), achado.cidade(),
                achado.uf());
        var token = novoToken();
        var pedido = pedidos.save(new Pedido(plano, Senhas.sha256(token), nome, email, cpf,
                celular.isEmpty() ? null : celular, endereco, valores, new Pedido.Aceite(TERMOS_VERSAO, agora, ip)));
        var sucesso = base + "/assinar/pronto/?pedido=" + token;
        var volta = base + "/assinar/" + plano.getLink();
        var comprador = comprador(pedido);
        var curso = itemDoCurso(plano, valores.cursoCentavos());
        var matricula = valores.matriculaCentavos() == null ? null
                : new Asaas.Item("Matrícula", "Matrícula — " + plano.getNome(), valores.matriculaCentavos());
        var referencia = "pedido-" + pedido.getId();
        Asaas.Checkout checkout;
        switch (valores.forma()) {
            case MENSAL -> {
                checkout = asaas.criarCheckout(new Asaas.NovoCheckout(List.of(curso), Asaas.Forma.MENSAL, 1,
                        plano.getMensalidades(), comprador, referencia, sucesso, volta));
                pedido.checkoutCriado(checkout.id());
            }
            case PIX -> {
                var itens = matricula == null ? List.of(curso) : List.of(matricula, curso);
                checkout = asaas.criarCheckout(new Asaas.NovoCheckout(itens, Asaas.Forma.PIX, 1, null, comprador,
                        referencia, sucesso, volta));
                pedido.checkoutCriado(checkout.id());
            }
            default -> {
                if (matricula != null) {
                    checkout = asaas.criarCheckout(new Asaas.NovoCheckout(List.of(matricula), Asaas.Forma.PIX_OU_CARTAO,
                            plano.getMatriculaParcelasMax(), null, comprador, referencia + "-matricula", sucesso, volta));
                    pedido.matriculaCheckoutCriado(checkout.id());
                } else {
                    checkout = asaas.criarCheckout(new Asaas.NovoCheckout(List.of(curso), Asaas.Forma.CARTAO,
                            valores.cartaoParcelas(), null, comprador, referencia, sucesso, volta));
                    pedido.checkoutCriado(checkout.id());
                }
            }
        }
        return new CompraIniciada(checkout.link());
    }

    /**
     * A segunda etapa do cartão: matrícula paga, agora o curso parcelado. O valor é o do pedido — o
     * do dia em que o aluno começou —, mesmo que a tabela tenha virado no meio.
     */
    @Transactional
    public CompraIniciada pagarCurso(String token, String base, Instant agora) {
        var p = peloToken(token);
        if (!p.faltaPagarOCurso()) {
            throw new RegraDeNegocio(p.getForma() == Pedido.Forma.CARTAO && p.getMatriculaPagaEm() == null
                    && p.getStatus() == Pedido.Status.AGUARDANDO
                    ? "A matrícula ainda não foi confirmada. Aguarde um instante."
                    : "Este pedido não tem curso a pagar.");
        }
        contas.limitar("curso:" + p.getId(), 10, agora);
        var volta = base + "/assinar/pronto/?pedido=" + token;
        var checkout = asaas.criarCheckout(new Asaas.NovoCheckout(List.of(itemDoCurso(p.getPlano(), p.getCursoCentavos())),
                Asaas.Forma.CARTAO, p.getCartaoParcelas(), null, comprador(p), "pedido-" + p.getId(), volta, volta));
        p.checkoutCriado(checkout.id());
        return new CompraIniciada(checkout.link());
    }

    /** Forma e valores do pedido, pela tabela do dia. */
    private static Pedido.Valores valores(Plano plano, String forma, LocalDate hoje) {
        if (plano.getTipo() == Plano.Tipo.MENSAL) {
            return new Pedido.Valores(Pedido.Forma.MENSAL, plano.getPrecoCentavos(), 1, null);
        }
        var dia = plano.precoDoDia(hoje).orElseThrow(() -> new RegraDeNegocio("Este plano ainda não tem preço para hoje."));
        var escolhida = forma == null || forma.isBlank() ? null : forma.strip().toUpperCase(Locale.ROOT);
        if (escolhida == null) {
            if (dia.getPixCentavos() != null && dia.getCartaoCentavos() != null) {
                throw new RegraDeNegocio("Escolha como pagar: Pix à vista ou cartão.");
            }
            escolhida = dia.getPixCentavos() != null ? "PIX" : "CARTAO";
        }
        return switch (escolhida) {
            case "PIX" -> {
                if (dia.getPixCentavos() == null) {
                    throw new RegraDeNegocio("Este plano não aceita Pix: escolha o cartão.");
                }
                yield new Pedido.Valores(Pedido.Forma.PIX, dia.getPixCentavos(), 1, plano.getMatriculaCentavos());
            }
            case "CARTAO" -> {
                if (dia.getCartaoCentavos() == null) {
                    throw new RegraDeNegocio("Este plano não aceita cartão: escolha o Pix.");
                }
                yield new Pedido.Valores(Pedido.Forma.CARTAO, dia.getCartaoCentavos(), dia.getCartaoParcelas(),
                        plano.getMatriculaCentavos());
            }
            default -> throw new RegraDeNegocio("Forma de pagamento: PIX ou CARTAO.");
        };
    }

    private static Asaas.Item itemDoCurso(Plano plano, int centavos) {
        return new Asaas.Item(plano.getNome(),
                "Acesso a " + String.join(", ", plano.getTurmas().stream().map(Turma::getNome).toList()), centavos);
    }

    private static Asaas.Comprador comprador(Pedido p) {
        return new Asaas.Comprador(p.getNome(), p.getEmail(), p.getCpf(), p.getCelular(), p.getCep(), p.getNumero(),
                p.getRua(), p.getComplemento(), p.getBairro());
    }

    public record Situacao(Pedido.Status status, String plano, String email, boolean podeCriarSenha,
            boolean jaTinhaConta, Pedido.Forma forma, boolean matriculaPaga, boolean faltaPagarCurso,
            Integer cursoCentavos, Integer cartaoParcelas) {}

    /** O que a página de volta mostra. Quem tem o token é quem comprou: é o endereço de volta dele. */
    @Transactional(readOnly = true)
    public Situacao situacao(String token) {
        var p = peloToken(token);
        return new Situacao(p.getStatus(), p.getPlano().getNome(), p.getEmail(),
                p.isAcessoLiberado() && p.isContaNova() && !p.isSenhaDefinida(),
                p.isAcessoLiberado() && !p.isContaNova(), p.getForma(), p.getMatriculaPagaEm() != null,
                p.faltaPagarOCurso(), p.getCursoCentavos(), p.getCartaoParcelas());
    }

    /**
     * A primeira senha da conta que nasceu nesta compra. Conta que já existia nunca passa por aqui:
     * comprar com o e-mail de alguém não pode virar jeito de entrar na conta dele.
     */
    @Transactional
    public Usuario criarSenha(String token, String senha, Instant agora) {
        var p = peloToken(token);
        if (!p.isAcessoLiberado()) {
            throw new RegraDeNegocio("O pagamento ainda não foi confirmado.");
        }
        if (!p.isContaNova() || p.isSenhaDefinida()) {
            throw new RegraDeNegocio("Esta conta já tem senha: entre com seu e-mail e senha.");
        }
        var usuario = contas.definirPrimeiraSenha(p.getUsuarioId(), senha, agora);
        p.senhaFoiDefinida();
        return usuario;
    }

    private Pedido peloToken(String token) {
        return pedidos.findFirstByTokenHash(Senhas.sha256(token == null ? "" : token))
                .orElseThrow(() -> new NaoEncontrado("Pedido não encontrado."));
    }

    // --- o que o Asaas avisa -------------------------------------------------

    /**
     * Um aviso do Asaas, já autenticado. Ele entrega pelo menos uma vez — pode repetir —, então
     * cada efeito aqui aguenta chegar de novo.
     */
    @Transactional
    public void tratarAviso(Map<?, ?> aviso, Instant agora) {
        var evento = texto(aviso.get("event"));
        var checkout = mapa(aviso.get("checkout"));
        var cobranca = mapa(aviso.get("payment"));
        var assinatura = mapa(aviso.get("subscription"));
        switch (evento) {
            case "CHECKOUT_PAID" -> {
                var id = texto(checkout.get("id"));
                var cliente = texto(checkout.get("customer"));
                pedidos.findFirstByAsaasCheckoutId(id).ifPresentOrElse(p -> {
                    // No Pix a matrícula veio junto com o curso.
                    if (p.getMatriculaCentavos() != null) {
                        p.matriculaPaga(cliente, agora);
                    }
                    liberar(p, cliente, idDe(checkout.get("subscription")), agora);
                }, () -> pedidos.findFirstByMatriculaCheckoutId(id).ifPresentOrElse(
                        // Só a matrícula: o acesso espera o curso, que a página de volta oferece em seguida.
                        p -> p.matriculaPaga(cliente, agora),
                        () -> log.info("checkout {} pago, sem pedido nosso: ignorado", id)));
            }
            // Expirou o checkout do curso depois da matrícula paga: o pedido segue, e a página de volta abre outro.
            case "CHECKOUT_EXPIRED", "CHECKOUT_CANCELED" -> pedidos.findFirstByAsaasCheckoutId(texto(checkout.get("id")))
                    .or(() -> pedidos.findFirstByMatriculaCheckoutId(texto(checkout.get("id"))))
                    .filter(p -> p.getStatus() == Pedido.Status.AGUARDANDO && p.getMatriculaPagaEm() == null)
                    .ifPresent(p -> p.expirou(agora));
            case "PAYMENT_CONFIRMED", "PAYMENT_RECEIVED" -> daAssinatura(cobranca)
                    // Mensalidade em dia de novo: some o prazo, e o acesso volta se tinha caído.
                    .ifPresent(p -> liberar(p, texto(cobranca.get("customer")), null, agora));
            case "PAYMENT_OVERDUE" -> daAssinatura(cobranca)
                    .filter(p -> p.isAcessoLiberado() && p.getAcessoAte() == null)
                    .ifPresent(p -> p.vence(Pedido.Status.ATRASADO, agora.plus(TOLERANCIA_DE_ATRASO), agora));
            case "PAYMENT_REFUNDED", "PAYMENT_CHARGEBACK_REQUESTED" -> daCobranca(cobranca)
                    .ifPresent(p -> revogar(p, Pedido.Status.REEMBOLSADO, agora));
            case "SUBSCRIPTION_DELETED", "SUBSCRIPTION_INACTIVATED" -> pedidos
                    .findFirstByAsaasAssinaturaIdOrderByIdDesc(texto(assinatura.get("id")))
                    .filter(Pedido::isAcessoLiberado)
                    // Cancelou: o mês que já pagou continua valendo.
                    .ifPresent(p -> p.vence(Pedido.Status.CANCELADO, fimDoPeriodo(assinatura, agora), agora));
            default -> log.info("aviso {} do Asaas: nada a fazer", evento);
        }
    }

    /**
     * O pedido da mensalidade. Se o aviso do checkout não trouxe a assinatura, a primeira cobrança
     * dela a amarra ao pedido pago daquele cliente — sem isso, as renovações não achariam ninguém.
     */
    private java.util.Optional<Pedido> daAssinatura(Map<?, ?> cobranca) {
        var id = texto(cobranca.get("subscription"));
        if (id.isEmpty()) {
            return java.util.Optional.empty();
        }
        var achado = pedidos.findFirstByAsaasAssinaturaIdOrderByIdDesc(id);
        var cliente = texto(cobranca.get("customer"));
        if (achado.isPresent() || cliente.isEmpty()) {
            return achado;
        }
        return pedidos.findFirstByAsaasClienteIdAndAsaasAssinaturaIdIsNullOrderByIdDesc(cliente)
                .filter(p -> p.getPlano().getTipo() == Plano.Tipo.MENSAL)
                .map(p -> {
                    p.ligarAssinatura(id);
                    return p;
                });
    }

    /** Estorno de mensalidade tem assinatura; de pagamento único, só o cliente. */
    private java.util.Optional<Pedido> daCobranca(Map<?, ?> cobranca) {
        var peloPlano = daAssinatura(cobranca);
        if (peloPlano.isPresent()) {
            return peloPlano;
        }
        var cliente = texto(cobranca.get("customer"));
        if (cliente.isEmpty()) {
            return java.util.Optional.empty();
        }
        // Pago; ou só a matrícula paga, à espera do curso — devolvida, o pedido acaba ali.
        return pedidos.findFirstByAsaasClienteIdAndStatusOrderByIdDesc(cliente, Pedido.Status.PAGO)
                .or(() -> pedidos.findFirstByAsaasClienteIdAndStatusOrderByIdDesc(cliente, Pedido.Status.AGUARDANDO)
                        .filter(p -> p.getMatriculaPagaEm() != null));
    }

    private void liberar(Pedido p, String clienteId, String assinaturaId, Instant agora) {
        if (p.getStatus() == Pedido.Status.REEMBOLSADO) {
            return;
        }
        var comprador = p.getUsuarioId() == null ? contas.contaDoComprador(p.getNome(), p.getEmail()) : null;
        var usuarioId = comprador != null ? comprador.usuario().getId() : p.getUsuarioId();
        var plano = p.getPlano();
        var acessoAte = plano.getTipo() == Plano.Tipo.UNICO && plano.getAcessoAte() != null
                ? plano.getAcessoAte().plusDays(1).atStartOfDay(BRASILIA).toInstant() : null;
        p.pago(usuarioId, comprador != null && comprador.contaNova(), clienteId, assinaturaId, acessoAte, agora);
        plano.getTurmas().forEach(t -> contas.matricularPeloPedido(usuarioId, t, p.getId()));
        log.info("pedido {} pago: acesso liberado ao usuário {}", p.getId(), usuarioId);
    }

    private void revogar(Pedido p, Pedido.Status status, Instant agora) {
        var saiu = p.isAcessoLiberado() ? contas.desmatricularDoPedido(p.getId()) : 0;
        p.acessoRevogado(status, agora);
        log.info("pedido {} {}: {} matrícula(s) retirada(s)", p.getId(), status, saiu);
    }

    /** Roda de tempos em tempos (ver {@link RotinaDasVendas}): tira o acesso que venceu. */
    @Transactional
    public int revogarVencidos(Instant agora) {
        var vencidos = pedidos.vencidos(agora);
        vencidos.forEach(p -> revogar(p, p.getStatus(), agora));
        return vencidos.size();
    }

    private static Instant fimDoPeriodo(Map<?, ?> assinatura, Instant agora) {
        var proxima = texto(assinatura.get("nextDueDate"));
        try {
            var fim = LocalDate.parse(proxima).atStartOfDay(BRASILIA).toInstant();
            return fim.isAfter(agora) ? fim : agora;
        } catch (RuntimeException e) {
            return agora;
        }
    }

    // --- apoio ---------------------------------------------------------------

    static boolean cpfValido(String cpf) {
        if (cpf.length() != 11 || cpf.chars().distinct().count() == 1) {
            return false;
        }
        for (var posicao = 9; posicao <= 10; posicao++) {
            var soma = 0;
            for (var i = 0; i < posicao; i++) {
                soma += (cpf.charAt(i) - '0') * (posicao + 1 - i);
            }
            var digito = (soma * 10) % 11 % 10;
            if (digito != cpf.charAt(posicao) - '0') {
                return false;
            }
        }
        return true;
    }

    private static String novoToken() {
        var bytes = new byte[32];
        SORTEIO.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    private static String idDe(Object v) {
        return v instanceof Map<?, ?> m ? texto(m.get("id")) : texto(v);
    }

    private static Map<?, ?> mapa(Object v) {
        return v instanceof Map<?, ?> m ? m : Map.of();
    }

    private static String texto(Object v) {
        return v == null ? "" : String.valueOf(v);
    }
}
