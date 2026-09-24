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

    public record DadosDoPlano(String nome, String link, String tipo, Integer precoCentavos, Integer parcelasMax,
            LocalDate acessoAte, List<String> turmas, Boolean ativo) {}

    public record PlanoNaLista(Integer planoId, String nome, String link, Plano.Tipo tipo, Integer precoCentavos,
            Integer parcelasMax, LocalDate acessoAte, boolean ativo, List<String> turmas, long alunosComAcesso) {}

    @Transactional(readOnly = true)
    public List<PlanoNaLista> listarPlanos(Identidade ident) {
        ident.exigirOperador();
        return planos.findAllByOrderByAtivoDescNomeAsc().stream().map(this::naLista).toList();
    }

    private PlanoNaLista naLista(Plano p) {
        return new PlanoNaLista(p.getId(), p.getNome(), p.getLink(), p.getTipo(), p.getPrecoCentavos(),
                p.getParcelasMax(), p.getAcessoAte(), p.isAtivo(), p.getTurmas().stream().map(Turma::getNome).toList(),
                pedidos.comAcesso(p));
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
        var preco = d.precoCentavos() == null ? (novo ? null : p.getPrecoCentavos()) : d.precoCentavos();
        if (preco == null || preco < 500) {
            throw new RegraDeNegocio("O preço mínimo que o Asaas cobra é R$ 5,00.");
        }
        var parcelas = tipo == Plano.Tipo.MENSAL ? 1
                : d.parcelasMax() != null ? d.parcelasMax() : (novo ? 1 : p.getParcelasMax());
        if (parcelas < 1 || parcelas > 12) {
            throw new RegraDeNegocio("Parcelas: de 1 a 12.");
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
        p.mudar(nome.strip(), link, tipo, preco, parcelas, acessoAte, List.copyOf(turmas), ativo, novo ? null : agora);
    }

    private static String escolher(String novo, String atual, boolean criando) {
        return novo != null ? novo : (criando ? null : atual);
    }

    public record PedidoNaLista(Integer pedidoId, Instant criadoEm, String nome, String email, String plano,
            Integer valorCentavos, Pedido.Status status, Instant pagoEm, Instant acessoAte, boolean acessoLiberado) {}

    @Transactional(readOnly = true)
    public List<PedidoNaLista> listarPedidos(Identidade ident) {
        ident.exigirOperador();
        return pedidos.findTop200ByOrderByIdDesc().stream()
                .map(p -> new PedidoNaLista(p.getId(), p.getCriadoEm(), p.getNome(), p.getEmail(), p.getPlano().getNome(),
                        p.getValorCentavos(), p.getStatus(), p.getPagoEm(), p.getAcessoAte(), p.isAcessoLiberado()))
                .toList();
    }

    // --- a página pública ----------------------------------------------------

    public record TurmaNoPlano(String nome, int modulos, int aulas) {}

    public record PlanoPublico(String nome, String link, Plano.Tipo tipo, Integer precoCentavos, Integer parcelasMax,
            LocalDate acessoAte, List<TurmaNoPlano> turmas) {}

    @Transactional(readOnly = true)
    public PlanoPublico planoPublico(String link) {
        var p = aVenda(link);
        return new PlanoPublico(p.getNome(), p.getLink(), p.getTipo(), p.getPrecoCentavos(), p.getParcelasMax(),
                p.getAcessoAte(), p.getTurmas().stream().map(t -> new TurmaNoPlano(t.getNome(), estrutura.contarModulos(t),
                        estrutura.contarItensDaTurma(t, Status.PUBLICADO))).toList());
    }

    private Plano aVenda(String link) {
        return planos.findFirstByLinkIgnoreCase(link == null ? "" : link.strip())
                .filter(Plano::isAtivo)
                .orElseThrow(() -> new NaoEncontrado("Este plano não está à venda."));
    }

    public record DadosDaCompra(String nome, String email, String cpf, String celular, String cep, String numero) {}

    public record CompraIniciada(String checkout) {}

    /**
     * Grava o pedido e abre o checkout no Asaas, já com os dados do aluno. A volta do checkout traz
     * um token só deste pedido — é ele que deixa a página de volta criar a senha da conta nova.
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
        contas.limitar("compra:" + ip, 10, agora);

        var token = novoToken();
        var pedido = pedidos.save(new Pedido(plano, Senhas.sha256(token), nome, email, cpf,
                celular.isEmpty() ? null : celular, cep, numero));
        var checkout = asaas.criarCheckout(new Asaas.NovoCheckout(plano.getNome(),
                "Acesso a " + String.join(", ", plano.getTurmas().stream().map(Turma::getNome).toList()),
                plano.getPrecoCentavos(), plano.getTipo(), plano.getParcelasMax(),
                new Asaas.Comprador(nome, email, cpf, celular.isEmpty() ? null : celular, cep, numero),
                "pedido-" + pedido.getId(),
                base + "/assinar/pronto/?pedido=" + token,
                base + "/assinar/" + plano.getLink()));
        pedido.checkoutCriado(checkout.id());
        return new CompraIniciada(checkout.link());
    }

    public record Situacao(Pedido.Status status, String plano, String email, boolean podeCriarSenha,
            boolean jaTinhaConta) {}

    /** O que a página de volta mostra. Quem tem o token é quem comprou: é o endereço de volta dele. */
    @Transactional(readOnly = true)
    public Situacao situacao(String token) {
        var p = peloToken(token);
        return new Situacao(p.getStatus(), p.getPlano().getNome(), p.getEmail(),
                p.isAcessoLiberado() && p.isContaNova() && !p.isSenhaDefinida(),
                p.isAcessoLiberado() && !p.isContaNova());
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
            case "CHECKOUT_PAID" -> pedidos.findFirstByAsaasCheckoutId(texto(checkout.get("id"))).ifPresentOrElse(
                    p -> liberar(p, texto(checkout.get("customer")), idDe(checkout.get("subscription")), agora),
                    () -> log.info("checkout {} pago, sem pedido nosso: ignorado", texto(checkout.get("id"))));
            case "CHECKOUT_EXPIRED", "CHECKOUT_CANCELED" -> pedidos.findFirstByAsaasCheckoutId(texto(checkout.get("id")))
                    .filter(p -> p.getStatus() == Pedido.Status.AGUARDANDO).ifPresent(p -> p.expirou(agora));
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
        return cliente.isEmpty() ? java.util.Optional.empty()
                : pedidos.findFirstByAsaasClienteIdAndStatusOrderByIdDesc(cliente, Pedido.Status.PAGO);
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
