package br.com.plataforma.vendas;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

interface PlanoRepositorio extends JpaRepository<Plano, Integer> {

    List<Plano> findAllByOrderByAtivoDescNomeAsc();

    Optional<Plano> findFirstByLinkIgnoreCase(String link);
}

interface PedidoRepositorio extends JpaRepository<Pedido, Integer> {

    Optional<Pedido> findFirstByTokenHash(String tokenHash);

    Optional<Pedido> findFirstByAsaasCheckoutId(String checkoutId);

    Optional<Pedido> findFirstByAsaasAssinaturaIdOrderByIdDesc(String assinaturaId);

    /** Pagamento único não tem assinatura: o cliente do Asaas leva ao pedido pago mais recente. */
    Optional<Pedido> findFirstByAsaasClienteIdAndStatusOrderByIdDesc(String clienteId, Pedido.Status status);

    Optional<Pedido> findFirstByAsaasClienteIdAndAsaasAssinaturaIdIsNullOrderByIdDesc(String clienteId);

    List<Pedido> findTop200ByOrderByIdDesc();

    @Query("select count(p) from Pedido p where p.plano = :plano and p.acessoLiberado = true")
    long comAcesso(Plano plano);

    @Query("select p from Pedido p where p.acessoLiberado = true and p.acessoAte is not null and p.acessoAte < :agora")
    List<Pedido> vencidos(Instant agora);
}
