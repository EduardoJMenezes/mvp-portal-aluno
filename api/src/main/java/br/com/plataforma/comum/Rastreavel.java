package br.com.plataforma.comum;

import jakarta.persistence.Column;
import jakarta.persistence.MappedSuperclass;
import java.time.Instant;

/**
 * Quem mexeu por último, quando, e se está removido.
 *
 * <p>Editar e remover são operações diretas (não nascem como rascunho), então é esta linha que
 * guarda o rastro — no lugar de uma tabela de auditoria.
 *
 * <p>Nada é apagado: remover é preencher {@code removido_em}. Quem filtra o removido é o
 * {@code @SQLRestriction} de cada entidade, não quem escreve a consulta.
 */
@MappedSuperclass
public abstract class Rastreavel {

    @Column(name = "alterado_por_id")
    private Integer alteradoPorId;

    @Column(name = "alterado_em")
    private Instant alteradoEm;

    @Column(name = "removido_em")
    private Instant removidoEm;

    /** Carimba quem alterou e quando. */
    public void tocar(Identidade ident) {
        alteradoPorId = ident.usuarioId();
        alteradoEm = Instant.now();
    }

    /**
     * Remoção lógica, com rastro. Não emite DELETE.
     *
     * @return se removeu agora — o que já estava removido não conta, para ser idempotente
     */
    public boolean remover(Identidade ident) {
        if (removidoEm != null) {
            return false;
        }
        removidoEm = Instant.now();
        tocar(ident);
        return true;
    }

    /** O outro lado da remoção lógica — e a razão de ela existir: um engano é um desfazer. */
    public boolean restaurar(Identidade ident) {
        if (removidoEm == null) {
            return false;
        }
        removidoEm = null;
        tocar(ident);
        return true;
    }

    public boolean isRemovido() {
        return removidoEm != null;
    }

    public Integer getAlteradoPorId() {
        return alteradoPorId;
    }

    public Instant getAlteradoEm() {
        return alteradoEm;
    }

    public Instant getRemovidoEm() {
        return removidoEm;
    }
}
