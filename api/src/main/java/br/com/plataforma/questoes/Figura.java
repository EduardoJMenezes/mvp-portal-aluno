package br.com.plataforma.questoes;

import org.hibernate.annotations.NotFound;
import org.hibernate.annotations.NotFoundAction;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;

/**
 * Figura de uma questão: no enunciado, numa alternativa ou na resolução.
 *
 * <p>O texto aponta para ela no lugar exato onde aparece — {@code ![](figura:123)}.
 * {@code parte} decide quando o aluno pode vê-la: a da resolução só depois que o simulado fecha.
 *
 * <p>A coluna {@code conteudo} <b>não</b> é mapeada de propósito: são os bytes da imagem, e
 * carregá-los junto da questão traria a imagem inteira em toda listagem. Quem os quer pede pela
 * consulta nativa do repositório.
 */
@Entity
@Table(name = "images")
public class Figura {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Integer id;

    @Column(nullable = false)
    private String tipo;

    private String nome;

    @ManyToOne
    @NotFound(action = NotFoundAction.IGNORE)
    @JoinColumn(name = "questao_id")
    private Questao questao;

    private String parte;

    protected Figura() {}

    public Integer getId() {
        return id;
    }

    public String getTipo() {
        return tipo;
    }

    public String getNome() {
        return nome;
    }

    public String getParte() {
        return parte;
    }

    public Questao getQuestao() {
        return questao;
    }

    /** Liga a figura à questão onde ela aparece. Só a primeira ligação vale. */
    public void ligarA(Questao questao, String parte) {
        if (this.questao == null) {
            this.questao = questao;
            this.parte = parte;
        }
    }
}
