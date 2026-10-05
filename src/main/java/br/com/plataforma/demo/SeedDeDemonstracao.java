package br.com.plataforma.demo;

import br.com.plataforma.comum.Papel;
import br.com.plataforma.contas.Senhas;
import br.com.plataforma.vimeo.VimeoDemo;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * O mundo da demonstração (§7 da especificação, distribuído como o docs/DEMO.md descreve) num
 * banco vazio.
 *
 * <p>Liga-se com {@code SEED_DEMO=true} e só age se não houver nenhum usuário nem turma: num banco
 * com gente de verdade ele não escreve uma linha, e não existe modo de apagar para recomeçar —
 * recomeçar é criar outro banco.
 *
 * <p>Grava direto nas tabelas, como os cenários de teste, e o conteúdo já nasce publicado: é dado
 * de partida, não proposta de IA esperando aprovação. As contas não têm senha que alguém conheça —
 * entram pelo {@code MODO_DEMO}.
 */
@Component
public class SeedDeDemonstracao implements ApplicationRunner {

    public static final String TURMA_2026 = "Extensivo 2026";
    public static final String TURMA_2027 = "Extensivo 2027";
    public static final String SIMULADO_ENCERRADO = "Simulado diagnóstico — Atomística";
    public static final String SIMULADO_ABERTO = "Simulado 1 — Cinética";

    private static final Logger log = LoggerFactory.getLogger(SeedDeDemonstracao.class);
    private static final String QUESTOES_DA_APOSTILA = "Questões da apostila";

    /** Questão de simulado. {@code vimeoId} é o vídeo do acervo de demonstração que a resolve. */
    private record QuestaoDemo(
            String assunto, String dificuldade, String enunciado, List<String> alternativas,
            String gabarito, String resolucao, String vimeoId) {}

    private static final List<QuestaoDemo> QUESTOES = List.of(
            new QuestaoDemo("Atomística", "FACIL",
                    "No experimento de Rutherford, a maioria das partículas alfa atravessou a lâmina de "
                            + "ouro sem sofrer desvio. Essa observação indica que:",
                    List.of("o átomo é uma esfera maciça e indivisível.",
                            "a maior parte do átomo é espaço vazio.",
                            "os elétrons estão incrustados em uma massa positiva.",
                            "o núcleo ocupa quase todo o volume do átomo.",
                            "os elétrons giram em órbitas de energia quantizada."),
                    "B", "Se quase todas as partículas passam direto, quase todo o átomo é vazio; as "
                            + "poucas que desviam encontraram o núcleo, pequeno e denso.",
                    "910000101"),
            new QuestaoDemo("Atomística", "FACIL",
                    "Qual é a distribuição eletrônica, em subníveis, do átomo de sódio (Z = 11) no "
                            + "estado fundamental?",
                    List.of("1s² 2s² 2p⁶ 3s¹", "1s² 2s² 2p⁵ 3s²", "1s² 2s² 2p⁶ 3p¹", "1s² 2s² 2p⁷",
                            "1s² 2s¹ 2p⁶ 3s²"),
                    "A", "Onze elétrons, na ordem do diagrama de Pauling: 2 no 1s, 2 no 2s, 6 no 2p e o "
                            + "último no 3s.",
                    "910000102"),
            new QuestaoDemo("Atomística", "MEDIA",
                    "Os átomos ¹⁴C (Z = 6) e ¹⁴N (Z = 7) são classificados como:",
                    List.of("isótopos.", "isótonos.", "isóbaros.", "isoeletrônicos.", "alótropos."),
                    "C", "Têm o mesmo número de massa (14) e números atômicos diferentes: isóbaros. Os "
                            + "nêutrons diferem (8 e 7), então não são isótonos.",
                    "910000103"),
            new QuestaoDemo("Atomística", "MEDIA",
                    "Quantos elétrons possui o íon Ca²⁺ (Z = 20)?",
                    List.of("16", "18", "20", "22", "40"),
                    "B", "O átomo neutro tem 20 elétrons; a carga 2+ indica que perdeu dois: 18.",
                    null),
            new QuestaoDemo("Atomística", "DIFICIL",
                    "Um átomo neutro tem número de massa 56 e 30 nêutrons. Quantos elétrons há na sua "
                            + "camada de valência?",
                    List.of("8", "6", "14", "2", "26"),
                    "D", "Z = 56 − 30 = 26. A distribuição termina em 4s² 3d⁶: a camada mais externa é a "
                            + "quarta, com 2 elétrons.",
                    null),

            new QuestaoDemo("Estequiometria", "FACIL",
                    "Na combustão completa do metano, a CH₄ + b O₂ → c CO₂ + d H₂O, os menores "
                            + "coeficientes inteiros a, b, c e d são, respectivamente:",
                    List.of("1, 1, 1 e 2.", "1, 2, 1 e 2.", "2, 3, 2 e 4.", "1, 2, 2 e 1.", "2, 4, 1 e 2."),
                    "B", "Um carbono pede 1 CO₂; quatro hidrogênios pedem 2 H₂O; os quatro oxigênios dos "
                            + "produtos pedem 2 O₂.",
                    "920000201"),
            new QuestaoDemo("Estequiometria", "FACIL",
                    "Qual é a massa de 2 mol de água (H₂O)? Dados: H = 1 g/mol; O = 16 g/mol.",
                    List.of("36 g", "18 g", "20 g", "34 g", "40 g"),
                    "A", "A massa molar da água é 2·1 + 16 = 18 g/mol; dois mols têm 36 g.",
                    "920000202"),
            new QuestaoDemo("Estequiometria", "MEDIA",
                    "Misturam-se 4 g de H₂ e 16 g de O₂ para formar água (2 H₂ + O₂ → 2 H₂O). O "
                            + "reagente limitante e a massa de água formada são, respectivamente: "
                            + "(H = 1 g/mol; O = 16 g/mol)",
                    List.of("H₂ e 18 g.", "H₂ e 36 g.", "O₂ e 18 g.", "O₂ e 36 g.", "O₂ e 20 g."),
                    "C", "São 2 mol de H₂ e 0,5 mol de O₂. Os 2 mol de H₂ pediriam 1 mol de O₂: falta "
                            + "oxigênio. Com 0,5 mol de O₂ forma-se 1 mol de água, 18 g.",
                    "920000203"),
            new QuestaoDemo("Estequiometria", "MEDIA",
                    "A decomposição de 100 g de CaCO₃ (CaCO₃ → CaO + CO₂) produziu 42 g de CaO. O "
                            + "rendimento da reação foi de: (Ca = 40 g/mol; C = 12 g/mol; O = 16 g/mol)",
                    List.of("42%", "56%", "60%", "75%", "84%"),
                    "D", "100 g de CaCO₃ é 1 mol, que daria 1 mol de CaO, 56 g. Obteve-se 42 g: "
                            + "42 ÷ 56 = 75%.",
                    "920000204"),
            new QuestaoDemo("Estequiometria", "DIFICIL",
                    "Uma amostra de 200 g de calcário com 80% de pureza em CaCO₃ é decomposta "
                            + "completamente (CaCO₃ → CaO + CO₂). A massa de CO₂ liberada é: "
                            + "(Ca = 40 g/mol; C = 12 g/mol; O = 16 g/mol)",
                    List.of("35,2 g", "44,0 g", "56,0 g", "88,0 g", "70,4 g"),
                    "E", "Só 80% da amostra reage: 160 g de CaCO₃, 1,6 mol. Cada mol libera 1 mol de "
                            + "CO₂ (44 g): 1,6 · 44 = 70,4 g.",
                    "920000205"),

            new QuestaoDemo("Cinética", "FACIL",
                    "Na reação N₂ + 3 H₂ → 2 NH₃, a concentração de NH₃ passou de 0 para 0,6 mol/L em "
                            + "3 minutos. A velocidade média de formação da amônia nesse intervalo, em "
                            + "mol/(L·min), foi de:",
                    List.of("0,1", "0,2", "0,3", "0,6", "1,8"),
                    "B", "Velocidade média é a variação da concentração pelo tempo: 0,6 ÷ 3 = 0,2.",
                    "930000301"),
            new QuestaoDemo("Cinética", "FACIL",
                    "Um sólido reage com uma solução aquosa. Qual das alterações abaixo NÃO aumenta a "
                            + "velocidade dessa reação?",
                    List.of("Aumentar a temperatura.", "Triturar o sólido.",
                            "Aumentar a concentração da solução.", "Adicionar um catalisador.",
                            "Usar o sólido em um único bloco em vez de em pó."),
                    "E", "Um bloco só expõe menos superfície de contato que o pó: menos colisões "
                            + "efetivas, reação mais lenta.",
                    "930000302"),
            new QuestaoDemo("Cinética", "MEDIA",
                    "Um catalisador aumenta a velocidade de uma reação porque:",
                    List.of("aumenta a energia cinética média das moléculas.",
                            "desloca o equilíbrio no sentido dos produtos.",
                            "oferece um caminho com menor energia de ativação.",
                            "aumenta a variação de entalpia da reação.",
                            "é consumido, fornecendo energia aos reagentes."),
                    "C", "O catalisador muda o mecanismo para um de menor energia de ativação; não "
                            + "altera o ΔH nem o equilíbrio, e sai intacto no fim.",
                    null),
            new QuestaoDemo("Cinética", "MEDIA",
                    "Para a reação elementar 2 NO + O₂ → 2 NO₂, a lei de velocidade é v = k[NO]²[O₂]. "
                            + "Dobrando a concentração de NO e mantendo a de O₂, a velocidade:",
                    List.of("não se altera.", "dobra.", "triplica.", "quadruplica.",
                            "cai pela metade."),
                    "D", "A velocidade depende do quadrado de [NO]: 2² = 4 vezes.",
                    null),
            new QuestaoDemo("Cinética", "DIFICIL",
                    "Em um diagrama de energia, os reagentes estão em 20 kJ, o complexo ativado em "
                            + "95 kJ e os produtos em 50 kJ. A energia de ativação da reação direta e o "
                            + "ΔH são, respectivamente:",
                    List.of("75 kJ e +30 kJ.", "75 kJ e −30 kJ.", "45 kJ e +30 kJ.", "95 kJ e +50 kJ.",
                            "45 kJ e −30 kJ."),
                    "A", "Energia de ativação: do reagente ao topo, 95 − 20 = 75 kJ. ΔH: produtos menos "
                            + "reagentes, 50 − 20 = +30 kJ (endotérmica).",
                    "930000303"));

    private final JdbcTemplate jdbc;
    private final boolean ligado;
    private final boolean modoDemo;

    public SeedDeDemonstracao(JdbcTemplate jdbc,
            @Value("${plataforma.semear-demo:false}") boolean ligado,
            @Value("${portal.modo-demo:false}") boolean modoDemo) {
        this.jdbc = jdbc;
        this.ligado = ligado;
        this.modoDemo = modoDemo;
    }

    @Override
    @Transactional
    public void run(ApplicationArguments args) {
        if (ligado) {
            semear(Instant.now());
        }
    }

    /** Grava a demonstração se o banco estiver vazio. Devolve se gravou. */
    @Transactional
    public boolean semear(Instant agora) {
        if (contar("users") > 0 || contar("classes") > 0) {
            log.info("SEED_DEMO: o banco já tem usuários ou turmas; nada foi gravado.");
            return false;
        }

        var professor = usuario("Professor Demo", "professor@escola.demo", Papel.ADMIN);
        usuario("Gerenciador Demo", "gerenciador@escola.demo", Papel.GERENCIADOR);
        var joao = usuario("João", "joao@aluno.demo", Papel.ALUNO);
        var maria = usuario("Maria", "maria@aluno.demo", Papel.ALUNO);
        var pedro = usuario("Pedro", "pedro@aluno.demo", Papel.ALUNO);

        var t2026 = turma(TURMA_2026);
        var t2027 = turma(TURMA_2027);
        matricular(joao, t2027);
        matricular(maria, t2027);
        matricular(pedro, t2026);

        var assuntos = new LinkedHashMap<String, Integer>();
        var videos = new LinkedHashMap<String, Integer>();
        var porPasta = new LinkedHashMap<String, List<Integer>>();
        var acervo = new VimeoDemo();
        for (var pasta : acervo.listarPastas()) {
            var assunto = inserir("INSERT INTO subjects (nome) VALUES (?) RETURNING id", pasta.nome());
            assuntos.put(pasta.nome(), assunto);
            var daPasta = new ArrayList<Integer>();
            for (var v : acervo.listarVideosDaPasta(pasta.id())) {
                var id = inserir("""
                        INSERT INTO videos (vimeo_id, titulo, url, embed_url, thumbnail_url,
                                            duracao_segundos, pasta_vimeo)
                        VALUES (?, ?, ?, ?, ?, ?, ?) RETURNING id""",
                        v.id(), v.titulo(), v.url(), v.embedUrl(), v.thumbnailUrl(), v.duracaoSegundos(),
                        v.pasta());
                jdbc.update("INSERT INTO video_subjects (video_id, assunto_id) VALUES (?, ?)", id, assunto);
                videos.put(v.id(), id);
                daPasta.add(id);
            }
            porPasta.put(pasta.nome(), daPasta);
        }

        // Atomística é o mesmo módulo nas duas turmas: o reaproveitamento entre anos (§18).
        modulo("Atomística", 1, professor, porPasta.get("Atomística"), t2026, t2027);
        modulo("Estequiometria", 2, professor, porPasta.get("Estequiometria"), t2027);
        modulo("Cinética", 3, professor, porPasta.get("Cinética"), t2026);
        // Vazio de propósito: é o buraco que o roteiro preenche ao vivo, pelo Claude.
        modulo("Cinética", 4, professor, List.of(), t2027);

        var questoes = new LinkedHashMap<String, List<Integer>>();
        for (var q : QUESTOES) {
            questoes.computeIfAbsent(q.assunto(), a -> new ArrayList<>())
                    .add(questao(q, professor, assuntos, videos));
        }

        var dia = Duration.ofDays(1);
        var encerrado = simulado(SIMULADO_ENCERRADO, professor, agora.minus(dia.multipliedBy(14)),
                agora.minus(dia.multipliedBy(7)), questoes.get("Atomística"), t2027);
        var inicio = agora.minus(dia.multipliedBy(10));
        // João erra só a última; Maria erra duas e deixa a última em branco.
        prova(encerrado, joao, inicio, questoes.get("Atomística"), List.of("B", "A", "C", "B", "A"));
        prova(encerrado, maria, inicio.plus(Duration.ofHours(3)), questoes.get("Atomística"),
                List.of("B", "A", "A", "C"));

        simulado(SIMULADO_ABERTO, professor, agora.minus(dia), agora.plus(dia.multipliedBy(30)),
                questoes.get("Cinética"), t2026, t2027);

        log.info("SEED_DEMO: demonstração gravada — 5 contas, 2 turmas, {} vídeos, {} questões e 2 "
                + "simulados. As contas @escola.demo e @aluno.demo entram pela tela inicial.",
                videos.size(), QUESTOES.size());
        if (!modoDemo) {
            log.warn("SEED_DEMO: MODO_DEMO está desligado, e as contas da demonstração não têm senha "
                    + "conhecida. Ligue MODO_DEMO=true para entrar nelas.");
        }
        return true;
    }

    private int contar(String tabela) {
        return jdbc.queryForObject("SELECT count(*) FROM " + tabela, Integer.class);
    }

    private int inserir(String sql, Object... valores) {
        return jdbc.queryForObject(sql, Integer.class, valores);
    }

    /** Senha sorteada e jogada fora: ninguém entra por ela. */
    private int usuario(String nome, String email, Papel papel) {
        return inserir("INSERT INTO users (nome, email, senha_hash, papel) VALUES (?, ?, ?, ?) RETURNING id",
                nome, email, Senhas.hash(UUID.randomUUID().toString()), papel.name());
    }

    private int turma(String nome) {
        return inserir("INSERT INTO classes (nome) VALUES (?) RETURNING id", nome);
    }

    private void matricular(int usuario, int turma) {
        jdbc.update("INSERT INTO enrollments (usuario_id, turma_id) VALUES (?, ?)", usuario, turma);
    }

    /** O módulo com os dois sub-módulos de sempre; os vídeos entram, publicados, nas questões da apostila. */
    private void modulo(String nome, int ordem, int autor, List<Integer> videos, int... turmas) {
        var modulo = inserir("INSERT INTO modules (nome, ordem, alterado_por_id) VALUES (?, ?, ?) RETURNING id",
                nome, ordem, autor);
        for (var turma : turmas) {
            jdbc.update("INSERT INTO module_classes (modulo_id, turma_id) VALUES (?, ?)", modulo, turma);
        }
        jdbc.update("INSERT INTO submodules (modulo_id, nome, tipo, ordem) VALUES (?, 'Aulas', 'VIDEO', 1)", modulo);
        var apostila = inserir("""
                INSERT INTO submodules (modulo_id, nome, tipo, ordem) VALUES (?, ?, 'VIDEO', 2) RETURNING id""",
                modulo, QUESTOES_DA_APOSTILA);
        for (int i = 0; i < videos.size(); i++) {
            jdbc.update("""
                    INSERT INTO items (submodulo_id, video_id, nome, ordem, status, alterado_por_id)
                    SELECT ?, id, titulo, ?, 'PUBLICADO', ? FROM videos WHERE id = ?""",
                    apostila, i + 1, autor, videos.get(i));
        }
    }

    private int questao(QuestaoDemo q, int autor, Map<String, Integer> assuntos, Map<String, Integer> videos) {
        var id = inserir("""
                INSERT INTO questions (enunciado, gabarito, dificuldade, resolucao_comentada, video_id,
                                       status, criado_por_id)
                VALUES (?, ?, ?, ?, ?, 'PUBLICADO', ?) RETURNING id""",
                q.enunciado(), q.gabarito(), q.dificuldade(), q.resolucao(),
                q.vimeoId() == null ? null : videos.get(q.vimeoId()), autor);
        for (int i = 0; i < q.alternativas().size(); i++) {
            jdbc.update("INSERT INTO question_options (questao_id, letra, texto) VALUES (?, ?, ?)",
                    id, String.valueOf((char) ('A' + i)), q.alternativas().get(i));
        }
        jdbc.update("INSERT INTO question_subjects (questao_id, assunto_id) VALUES (?, ?)",
                id, assuntos.get(q.assunto()));
        return id;
    }

    private int simulado(String titulo, int autor, Instant abre, Instant fecha, List<Integer> questoes,
            int... turmas) {
        var id = inserir("""
                INSERT INTO exams (titulo, abre_em, fecha_em, duracao_minutos, status, criado_por_id,
                                   publicado_em)
                VALUES (?, ?, ?, 60, 'PUBLICADO', ?, ?) RETURNING id""",
                titulo, Timestamp.from(abre), Timestamp.from(fecha), autor,
                Timestamp.from(abre.minus(Duration.ofDays(1))));
        for (var turma : turmas) {
            jdbc.update("INSERT INTO exam_classes (simulado_id, turma_id) VALUES (?, ?)", id, turma);
        }
        for (int i = 0; i < questoes.size(); i++) {
            jdbc.update("INSERT INTO exam_questions (simulado_id, questao_id, ordem) VALUES (?, ?, ?)",
                    id, questoes.get(i), i + 1);
        }
        return id;
    }

    /** A prova entregue em 40 minutos. Lista de respostas mais curta que a prova deixa o resto em branco. */
    private void prova(int simulado, int aluno, Instant inicio, List<Integer> questoes, List<String> marcadas) {
        var entrega = inicio.plus(Duration.ofMinutes(40));
        var tentativa = inserir("""
                INSERT INTO exam_attempts (simulado_id, aluno_id, iniciado_em, prazo_em, finalizado_em)
                VALUES (?, ?, ?, ?, ?) RETURNING id""",
                simulado, aluno, Timestamp.from(inicio), Timestamp.from(inicio.plus(Duration.ofMinutes(60))),
                Timestamp.from(entrega));
        for (int i = 0; i < marcadas.size(); i++) {
            jdbc.update("""
                    INSERT INTO exam_answers (tentativa_id, questao_id, alternativa_marcada, correta, respondido_em)
                    SELECT ?, id, ?, gabarito = ?, ? FROM questions WHERE id = ?""",
                    tentativa, marcadas.get(i), marcadas.get(i), Timestamp.from(entrega), questoes.get(i));
        }
    }
}
