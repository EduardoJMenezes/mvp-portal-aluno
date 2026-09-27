package br.com.plataforma.menu;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/**
 * Um botão do menu do aluno: uma feature da plataforma, recortada por uma categoria (decisão 0009
 * do cofre). "Simulados Rodmelo" é {@code SIMULADOS} com categoria "Rodmelo"; sem categoria, o
 * botão mostra a feature inteira.
 */
@Entity
@Table(name = "menu_buttons")
public class BotaoDoMenu {

    /** As features que viram botão. Início e Desempenho ficam fixos, fora do menu montado. */
    public enum Funcionalidade { CURSO, AULAS, SIMULADOS, MATERIAIS, AGENDA }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Integer id;

    @Column(name = "turma_id", nullable = false)
    private Integer turmaId;

    @Column(nullable = false)
    private String rotulo;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private Funcionalidade funcionalidade;

    private String categoria;

    @Column(nullable = false)
    private Integer ordem;

    protected BotaoDoMenu() {}

    BotaoDoMenu(Integer turmaId, String rotulo, Funcionalidade funcionalidade, String categoria, int ordem) {
        this.turmaId = turmaId;
        this.rotulo = rotulo;
        this.funcionalidade = funcionalidade;
        this.categoria = categoria;
        this.ordem = ordem;
    }

    public Integer getTurmaId() {
        return turmaId;
    }

    public String getRotulo() {
        return rotulo;
    }

    public Funcionalidade getFuncionalidade() {
        return funcionalidade;
    }

    public String getCategoria() {
        return categoria;
    }
}
