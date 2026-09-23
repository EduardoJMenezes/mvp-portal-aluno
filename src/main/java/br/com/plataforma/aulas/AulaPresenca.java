package br.com.plataforma.aulas;

import java.time.Instant;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/**
 * O link pessoal do aluno naquela aula. Guardado porque o Zoom só deixa inscrever o mesmo e-mail
 * três vezes por dia na mesma reunião: pedir de novo a cada clique queimaria a cota.
 */
@Entity
@Table(name = "live_class_attendance")
class AulaPresenca {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Integer id;

    @Column(name = "aula_id", nullable = false)
    private Integer aulaId;

    @Column(name = "usuario_id", nullable = false)
    private Integer usuarioId;

    @Column(name = "join_url", nullable = false)
    private String joinUrl;

    @Column(name = "entrou_em")
    private Instant entrouEm;

    @Column(name = "saiu_em")
    private Instant saiuEm;

    protected AulaPresenca() {}

    AulaPresenca(Integer aulaId, Integer usuarioId, String joinUrl) {
        this.aulaId = aulaId;
        this.usuarioId = usuarioId;
        this.joinUrl = joinUrl;
    }

    String getJoinUrl() {
        return joinUrl;
    }

    Integer getUsuarioId() {
        return usuarioId;
    }

    Instant getEntrouEm() {
        return entrouEm;
    }

    Instant getSaiuEm() {
        return saiuEm;
    }

    /** A primeira entrada fica; a saída é a mais recente — quem cai e volta conta uma vez só. */
    void entrou(Instant quando) {
        if (entrouEm == null || quando.isBefore(entrouEm)) {
            entrouEm = quando;
        }
    }

    void saiu(Instant quando) {
        if (saiuEm == null || quando.isAfter(saiuEm)) {
            saiuEm = quando;
        }
    }
}
