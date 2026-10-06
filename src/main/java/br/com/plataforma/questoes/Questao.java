package br.com.plataforma.questoes;

import br.com.plataforma.acervo.Video;
import br.com.plataforma.comum.Rastreavel;
import br.com.plataforma.comum.Status;
import org.hibernate.annotations.NotFound;
import org.hibernate.annotations.NotFoundAction;
import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.OneToMany;
import jakarta.persistence.OrderBy;
import jakarta.persistence.Table;
import java.util.ArrayList;
import java.util.List;
import org.hibernate.annotations.SQLRestriction;

/**
 * Questão do acervo: enunciado, alternativas (de A a D, ou até a E) e gabarito.
 *
 * <p>Entra numa prova ({@code exam_questions}) ou vira linha de aula ({@code items.questao_id}),
 * onde o aluno responde e vê o gabarito na hora. A resolução — o texto e o vídeo — é da questão,
 * e vai com ela para onde ela for.
 *
 * <p>A figura que ainda não chegou vira {@code imagemPendente}, e nem o simulado nem a linha de
 * aula publicam enquanto ela não for anexada.
 */
@Entity
@Table(name = "questions")
@SQLRestriction("removido_em IS NULL")
public class Questao extends Rastreavel {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Integer id;

    @Column(nullable = false)
    private String enunciado;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private Letra gabarito;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private Dificuldade dificuldade;

    @Column(name = "imagem_pendente", nullable = false)
    private boolean imagemPendente;

    @Column(name = "resolucao_comentada")
    private String resolucaoComentada;

    @ManyToOne
    @NotFound(action = NotFoundAction.IGNORE)
    @JoinColumn(name = "video_id")
    private Video video;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private Status status;

    @Column(name = "rascunho_id")
    private Integer rascunhoId;

    @Column(name = "criado_por_id", nullable = false)
    private Integer criadoPorId;

    @OneToMany(mappedBy = "questao", cascade = CascadeType.ALL, orphanRemoval = true)
    @OrderBy("letra")
    private List<Alternativa> alternativas = new ArrayList<>();

    @OneToMany(mappedBy = "questao", cascade = CascadeType.ALL, orphanRemoval = true)
    private List<QuestaoAssunto> assuntos = new ArrayList<>();

    /** O mínimo de uma questão; a apostila às vezes para na D. */
    public static final List<Letra> OBRIGATORIAS = List.of(Letra.A, Letra.B, Letra.C, Letra.D);

    protected Questao() {}

    Questao(String enunciado, Letra gabarito, Dificuldade dificuldade, String resolucaoComentada,
            Status status, Integer rascunhoId, Integer criadoPorId) {
        this.enunciado = enunciado;
        this.gabarito = gabarito;
        this.dificuldade = dificuldade;
        this.resolucaoComentada = resolucaoComentada;
        this.status = status;
        this.rascunhoId = rascunhoId;
        this.criadoPorId = criadoPorId;
    }

    public Integer getId() {
        return id;
    }

    public String getEnunciado() {
        return enunciado;
    }

    public Letra getGabarito() {
        return gabarito;
    }

    public Dificuldade getDificuldade() {
        return dificuldade;
    }

    public boolean isImagemPendente() {
        return imagemPendente;
    }

    public String getResolucaoComentada() {
        return resolucaoComentada;
    }

    public Video getVideo() {
        return video;
    }

    public Status getStatus() {
        return status;
    }

    public Integer getRascunhoId() {
        return rascunhoId;
    }

    public Integer getCriadoPorId() {
        return criadoPorId;
    }

    public List<Alternativa> getAlternativas() {
        return alternativas;
    }

    /** Pronta para o aluno responder: de A a D pelo menos. A E é opcional. */
    public boolean completa() {
        return OBRIGATORIAS.stream().allMatch(this::tem);
    }

    public boolean tem(Letra letra) {
        return alternativas.stream().anyMatch(a -> a.getLetra() == letra);
    }

    public List<QuestaoAssunto> getAssuntos() {
        return assuntos;
    }

    void mudarEnunciado(String enunciado) {
        this.enunciado = enunciado;
    }

    void mudarGabarito(Letra gabarito) {
        this.gabarito = gabarito;
    }

    void mudarDificuldade(Dificuldade dificuldade) {
        this.dificuldade = dificuldade;
    }

    void mudarResolucaoComentada(String texto) {
        this.resolucaoComentada = texto;
    }

    void mudarVideo(Video video) {
        this.video = video;
    }

    void marcarImagemPendente(boolean pendente) {
        this.imagemPendente = pendente;
    }

    void publicar() {
        this.status = Status.PUBLICADO;
    }

    /**
     * Atualiza no lugar, cria só a letra que falta e tira a que não veio — é assim que a E sai.
     *
     * <p>Não é {@code clear()} + {@code add()} de propósito: o Hibernate inseriria antes de
     * apagar, e o unique {@code (questao_id, letra)} estouraria no meio do flush.
     */
    void ajustarAlternativas(java.util.Map<Letra, String> novas) {
        alternativas.removeIf(a -> !novas.containsKey(a.getLetra()));
        var atuais = new java.util.EnumMap<Letra, Alternativa>(Letra.class);
        alternativas.forEach(a -> atuais.put(a.getLetra(), a));
        novas.forEach((letra, texto) -> {
            var existente = atuais.get(letra);
            if (existente != null) {
                existente.mudarTexto(texto);
            } else {
                alternativas.add(new Alternativa(this, letra, texto));
            }
        });
    }
}
