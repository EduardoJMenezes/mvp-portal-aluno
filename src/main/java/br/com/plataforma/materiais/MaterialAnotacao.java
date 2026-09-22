package br.com.plataforma.materiais;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.Map;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * O que um aluno riscou numa página — só dele, nem o professor lê.
 *
 * <p>Uma linha por página: salvar é gravar a página que mudou. Os traços vão em coordenadas
 * relativas (0 a 1), para zoom e rotação não mexerem no dado.
 */
@Entity
@Table(name = "material_annotations")
class MaterialAnotacao {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Integer id;

    @Column(name = "material_id", nullable = false)
    private Integer materialId;

    @Column(name = "usuario_id", nullable = false)
    private Integer usuarioId;

    @Column(nullable = false)
    private Integer pagina;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(nullable = false)
    private Map<String, Object> dados;

    @Column(name = "criado_em", insertable = false, updatable = false)
    private Instant criadoEm;

    @Column(name = "atualizado_em")
    private Instant atualizadoEm;

    protected MaterialAnotacao() {}

    MaterialAnotacao(Integer materialId, Integer usuarioId, Integer pagina) {
        this.materialId = materialId;
        this.usuarioId = usuarioId;
        this.pagina = pagina;
        this.dados = Map.of();
    }

    Integer getPagina() {
        return pagina;
    }

    Map<String, Object> getDados() {
        return dados;
    }

    void gravar(Map<String, Object> dados, Instant agora) {
        this.dados = dados;
        this.atualizadoEm = agora;
    }
}
