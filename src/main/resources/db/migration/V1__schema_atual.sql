-- Schema da plataforma como está hoje — gerado, não escrito à mão.
--
-- Origem: o migracoes.py do Python aplicado num Postgres 18 vazio, depois
-- `pg_dump --schema-only --no-owner --no-privileges`. Tirei só o que o Flyway
-- não digere: os meta-comandos restrict/unrestrict do psql e o set_config
-- que zera o search_path.
--
-- Em produção esta versão NÃO roda: o banco já existe, e o
-- `baseline-on-migrate` o marca como V1 sem executar nada. Ela serve para
-- banco novo — teste e máquina local. Antes de o Java apontar para produção,
-- compare com um pg_dump de lá: se divergir, é produção que manda.

--
--

--
-- Name: api_tokens; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.api_tokens (
    id integer NOT NULL,
    usuario_id integer NOT NULL,
    nome character varying(120) NOT NULL,
    token_hash character varying(64) NOT NULL,
    criado_em timestamp with time zone DEFAULT now() NOT NULL,
    ultimo_uso_em timestamp with time zone,
    revogado boolean NOT NULL
);

--
-- Name: api_tokens_id_seq; Type: SEQUENCE; Schema: public; Owner: -
--

CREATE SEQUENCE public.api_tokens_id_seq
    AS integer
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;

--
-- Name: api_tokens_id_seq; Type: SEQUENCE OWNED BY; Schema: public; Owner: -
--

ALTER SEQUENCE public.api_tokens_id_seq OWNED BY public.api_tokens.id;

--
-- Name: classes; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.classes (
    id integer NOT NULL,
    nome character varying(120) NOT NULL,
    ano integer NOT NULL,
    alterado_por_id integer,
    alterado_em timestamp with time zone,
    removido_em timestamp with time zone
);

--
-- Name: classes_id_seq; Type: SEQUENCE; Schema: public; Owner: -
--

CREATE SEQUENCE public.classes_id_seq
    AS integer
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;

--
-- Name: classes_id_seq; Type: SEQUENCE OWNED BY; Schema: public; Owner: -
--

ALTER SEQUENCE public.classes_id_seq OWNED BY public.classes.id;

--
-- Name: drafts; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.drafts (
    id integer NOT NULL,
    tipo character varying(20) NOT NULL,
    turma_id integer,
    submodulo_id integer,
    resumo text NOT NULL,
    origem character varying(40) NOT NULL,
    status character varying(20) NOT NULL,
    criado_por_id integer NOT NULL,
    criado_em timestamp with time zone DEFAULT now() NOT NULL,
    aprovado_por_id integer,
    aprovado_em timestamp with time zone,
    aprovado_via character varying(40),
    publicado_em timestamp with time zone,
    CONSTRAINT ck_drafts_status CHECK (((status)::text = ANY ((ARRAY['RASCUNHO'::character varying, 'PUBLICADO'::character varying])::text[]))),
    CONSTRAINT ck_drafts_tipo CHECK (((tipo)::text = ANY ((ARRAY['ITENS'::character varying, 'QUESTOES'::character varying, 'SIMULADO'::character varying])::text[])))
);

--
-- Name: drafts_id_seq; Type: SEQUENCE; Schema: public; Owner: -
--

CREATE SEQUENCE public.drafts_id_seq
    AS integer
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;

--
-- Name: drafts_id_seq; Type: SEQUENCE OWNED BY; Schema: public; Owner: -
--

ALTER SEQUENCE public.drafts_id_seq OWNED BY public.drafts.id;

--
-- Name: enrollments; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.enrollments (
    id integer NOT NULL,
    usuario_id integer NOT NULL,
    turma_id integer NOT NULL,
    criado_em timestamp with time zone DEFAULT now() NOT NULL
);

--
-- Name: enrollments_id_seq; Type: SEQUENCE; Schema: public; Owner: -
--

CREATE SEQUENCE public.enrollments_id_seq
    AS integer
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;

--
-- Name: enrollments_id_seq; Type: SEQUENCE OWNED BY; Schema: public; Owner: -
--

ALTER SEQUENCE public.enrollments_id_seq OWNED BY public.enrollments.id;

--
-- Name: exam_answers; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.exam_answers (
    id integer NOT NULL,
    tentativa_id integer NOT NULL,
    questao_id integer NOT NULL,
    alternativa_marcada character varying(1) NOT NULL,
    correta boolean NOT NULL,
    respondido_em timestamp with time zone DEFAULT now() NOT NULL,
    CONSTRAINT ck_answers_alternativa CHECK (((alternativa_marcada)::text = ANY ((ARRAY['A'::character varying, 'B'::character varying, 'C'::character varying, 'D'::character varying, 'E'::character varying])::text[])))
);

--
-- Name: exam_answers_id_seq; Type: SEQUENCE; Schema: public; Owner: -
--

CREATE SEQUENCE public.exam_answers_id_seq
    AS integer
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;

--
-- Name: exam_answers_id_seq; Type: SEQUENCE OWNED BY; Schema: public; Owner: -
--

ALTER SEQUENCE public.exam_answers_id_seq OWNED BY public.exam_answers.id;

--
-- Name: exam_attempts; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.exam_attempts (
    id integer NOT NULL,
    simulado_id integer NOT NULL,
    aluno_id integer NOT NULL,
    iniciado_em timestamp with time zone DEFAULT now() NOT NULL,
    prazo_em timestamp with time zone,
    finalizado_em timestamp with time zone,
    entregue_automaticamente boolean DEFAULT false NOT NULL
);

--
-- Name: exam_attempts_id_seq; Type: SEQUENCE; Schema: public; Owner: -
--

CREATE SEQUENCE public.exam_attempts_id_seq
    AS integer
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;

--
-- Name: exam_attempts_id_seq; Type: SEQUENCE OWNED BY; Schema: public; Owner: -
--

ALTER SEQUENCE public.exam_attempts_id_seq OWNED BY public.exam_attempts.id;

--
-- Name: exam_classes; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.exam_classes (
    id integer NOT NULL,
    simulado_id integer NOT NULL,
    turma_id integer NOT NULL
);

--
-- Name: exam_classes_id_seq; Type: SEQUENCE; Schema: public; Owner: -
--

CREATE SEQUENCE public.exam_classes_id_seq
    AS integer
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;

--
-- Name: exam_classes_id_seq; Type: SEQUENCE OWNED BY; Schema: public; Owner: -
--

ALTER SEQUENCE public.exam_classes_id_seq OWNED BY public.exam_classes.id;

--
-- Name: exam_questions; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.exam_questions (
    id integer NOT NULL,
    simulado_id integer NOT NULL,
    questao_id integer NOT NULL,
    ordem integer NOT NULL
);

--
-- Name: exam_questions_id_seq; Type: SEQUENCE; Schema: public; Owner: -
--

CREATE SEQUENCE public.exam_questions_id_seq
    AS integer
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;

--
-- Name: exam_questions_id_seq; Type: SEQUENCE OWNED BY; Schema: public; Owner: -
--

ALTER SEQUENCE public.exam_questions_id_seq OWNED BY public.exam_questions.id;

--
-- Name: exams; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.exams (
    id integer NOT NULL,
    titulo character varying(200) NOT NULL,
    abre_em timestamp with time zone,
    fecha_em timestamp with time zone,
    duracao_minutos integer,
    status character varying(20) NOT NULL,
    rascunho_id integer,
    criado_por_id integer NOT NULL,
    criado_em timestamp with time zone DEFAULT now() NOT NULL,
    publicado_em timestamp with time zone,
    alterado_por_id integer,
    alterado_em timestamp with time zone,
    removido_em timestamp with time zone,
    CONSTRAINT ck_exams_status CHECK (((status)::text = ANY ((ARRAY['RASCUNHO'::character varying, 'PUBLICADO'::character varying])::text[])))
);

--
-- Name: exams_id_seq; Type: SEQUENCE; Schema: public; Owner: -
--

CREATE SEQUENCE public.exams_id_seq
    AS integer
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;

--
-- Name: exams_id_seq; Type: SEQUENCE OWNED BY; Schema: public; Owner: -
--

ALTER SEQUENCE public.exams_id_seq OWNED BY public.exams.id;

--
-- Name: images; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.images (
    id integer NOT NULL,
    conteudo bytea NOT NULL,
    tipo character varying(60) NOT NULL,
    nome character varying(200),
    questao_id integer,
    parte character varying(20),
    criado_em timestamp with time zone DEFAULT now() NOT NULL
);

--
-- Name: images_id_seq; Type: SEQUENCE; Schema: public; Owner: -
--

CREATE SEQUENCE public.images_id_seq
    AS integer
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;

--
-- Name: images_id_seq; Type: SEQUENCE OWNED BY; Schema: public; Owner: -
--

ALTER SEQUENCE public.images_id_seq OWNED BY public.images.id;

--
-- Name: imports; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.imports (
    id integer NOT NULL,
    criado_por_id integer NOT NULL,
    token_hash character varying(64) NOT NULL,
    expira_em timestamp with time zone NOT NULL,
    status character varying(20) NOT NULL,
    parametros json NOT NULL,
    arquivo_nome character varying(200),
    arquivo bytea,
    blocos json,
    relatorio json,
    rascunho_id integer,
    criado_em timestamp with time zone DEFAULT now() NOT NULL,
    recebido_em timestamp with time zone
);

--
-- Name: imports_id_seq; Type: SEQUENCE; Schema: public; Owner: -
--

CREATE SEQUENCE public.imports_id_seq
    AS integer
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;

--
-- Name: imports_id_seq; Type: SEQUENCE OWNED BY; Schema: public; Owner: -
--

ALTER SEQUENCE public.imports_id_seq OWNED BY public.imports.id;

--
-- Name: items; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.items (
    id integer NOT NULL,
    submodulo_id integer NOT NULL,
    video_id integer NOT NULL,
    nome character varying(300) NOT NULL,
    ordem integer NOT NULL,
    status character varying(20) NOT NULL,
    rascunho_id integer,
    criado_em timestamp with time zone DEFAULT now() NOT NULL,
    alterado_por_id integer,
    alterado_em timestamp with time zone,
    removido_em timestamp with time zone,
    CONSTRAINT ck_items_status CHECK (((status)::text = ANY ((ARRAY['RASCUNHO'::character varying, 'PUBLICADO'::character varying])::text[])))
);

--
-- Name: items_id_seq; Type: SEQUENCE; Schema: public; Owner: -
--

CREATE SEQUENCE public.items_id_seq
    AS integer
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;

--
-- Name: items_id_seq; Type: SEQUENCE OWNED BY; Schema: public; Owner: -
--

ALTER SEQUENCE public.items_id_seq OWNED BY public.items.id;

--
-- Name: live_class_attendance; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.live_class_attendance (
    id integer NOT NULL,
    aula_id integer NOT NULL,
    usuario_id integer NOT NULL,
    join_url character varying(500) NOT NULL,
    criado_em timestamp with time zone DEFAULT now() NOT NULL,
    entrou_em timestamp with time zone,
    saiu_em timestamp with time zone
);

--
-- Name: live_class_attendance_id_seq; Type: SEQUENCE; Schema: public; Owner: -
--

CREATE SEQUENCE public.live_class_attendance_id_seq
    AS integer
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;

--
-- Name: live_class_attendance_id_seq; Type: SEQUENCE OWNED BY; Schema: public; Owner: -
--

ALTER SEQUENCE public.live_class_attendance_id_seq OWNED BY public.live_class_attendance.id;

--
-- Name: live_class_classes; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.live_class_classes (
    id integer NOT NULL,
    aula_id integer NOT NULL,
    turma_id integer NOT NULL
);

--
-- Name: live_class_classes_id_seq; Type: SEQUENCE; Schema: public; Owner: -
--

CREATE SEQUENCE public.live_class_classes_id_seq
    AS integer
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;

--
-- Name: live_class_classes_id_seq; Type: SEQUENCE OWNED BY; Schema: public; Owner: -
--

ALTER SEQUENCE public.live_class_classes_id_seq OWNED BY public.live_class_classes.id;

--
-- Name: live_class_students; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.live_class_students (
    id integer NOT NULL,
    aula_id integer NOT NULL,
    usuario_id integer NOT NULL,
    criado_em timestamp with time zone DEFAULT now() NOT NULL
);

--
-- Name: live_class_students_id_seq; Type: SEQUENCE; Schema: public; Owner: -
--

CREATE SEQUENCE public.live_class_students_id_seq
    AS integer
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;

--
-- Name: live_class_students_id_seq; Type: SEQUENCE OWNED BY; Schema: public; Owner: -
--

ALTER SEQUENCE public.live_class_students_id_seq OWNED BY public.live_class_students.id;

--
-- Name: live_classes; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.live_classes (
    id integer NOT NULL,
    titulo character varying(200) NOT NULL,
    descricao text,
    inicio_em timestamp with time zone NOT NULL,
    minutos integer NOT NULL,
    status character varying(20) NOT NULL,
    gravar boolean NOT NULL,
    zoom_meeting_id character varying(40),
    zoom_join_url character varying(500),
    submodulo_id integer,
    publicar_gravacao boolean NOT NULL,
    gravacao_item_id integer,
    criado_por_id integer NOT NULL,
    criado_em timestamp with time zone DEFAULT now() NOT NULL,
    publicado_em timestamp with time zone,
    alterado_por_id integer,
    alterado_em timestamp with time zone,
    removido_em timestamp with time zone,
    CONSTRAINT ck_live_classes_status CHECK (((status)::text = ANY ((ARRAY['RASCUNHO'::character varying, 'PUBLICADO'::character varying])::text[])))
);

--
-- Name: live_classes_id_seq; Type: SEQUENCE; Schema: public; Owner: -
--

CREATE SEQUENCE public.live_classes_id_seq
    AS integer
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;

--
-- Name: live_classes_id_seq; Type: SEQUENCE OWNED BY; Schema: public; Owner: -
--

ALTER SEQUENCE public.live_classes_id_seq OWNED BY public.live_classes.id;

--
-- Name: login_attempts; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.login_attempts (
    id integer NOT NULL,
    chave character varying(180) NOT NULL,
    criado_em timestamp with time zone DEFAULT now() NOT NULL
);

--
-- Name: login_attempts_id_seq; Type: SEQUENCE; Schema: public; Owner: -
--

CREATE SEQUENCE public.login_attempts_id_seq
    AS integer
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;

--
-- Name: login_attempts_id_seq; Type: SEQUENCE OWNED BY; Schema: public; Owner: -
--

ALTER SEQUENCE public.login_attempts_id_seq OWNED BY public.login_attempts.id;

--
-- Name: material_annotations; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.material_annotations (
    id integer NOT NULL,
    material_id integer NOT NULL,
    usuario_id integer NOT NULL,
    pagina integer NOT NULL,
    dados json NOT NULL,
    criado_em timestamp with time zone DEFAULT now() NOT NULL,
    atualizado_em timestamp with time zone
);

--
-- Name: material_annotations_id_seq; Type: SEQUENCE; Schema: public; Owner: -
--

CREATE SEQUENCE public.material_annotations_id_seq
    AS integer
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;

--
-- Name: material_annotations_id_seq; Type: SEQUENCE OWNED BY; Schema: public; Owner: -
--

ALTER SEQUENCE public.material_annotations_id_seq OWNED BY public.material_annotations.id;

--
-- Name: material_classes; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.material_classes (
    id integer NOT NULL,
    material_id integer NOT NULL,
    turma_id integer NOT NULL
);

--
-- Name: material_classes_id_seq; Type: SEQUENCE; Schema: public; Owner: -
--

CREATE SEQUENCE public.material_classes_id_seq
    AS integer
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;

--
-- Name: material_classes_id_seq; Type: SEQUENCE OWNED BY; Schema: public; Owner: -
--

ALTER SEQUENCE public.material_classes_id_seq OWNED BY public.material_classes.id;

--
-- Name: material_students; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.material_students (
    id integer NOT NULL,
    material_id integer NOT NULL,
    usuario_id integer NOT NULL,
    criado_em timestamp with time zone DEFAULT now() NOT NULL
);

--
-- Name: material_students_id_seq; Type: SEQUENCE; Schema: public; Owner: -
--

CREATE SEQUENCE public.material_students_id_seq
    AS integer
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;

--
-- Name: material_students_id_seq; Type: SEQUENCE OWNED BY; Schema: public; Owner: -
--

ALTER SEQUENCE public.material_students_id_seq OWNED BY public.material_students.id;

--
-- Name: materials; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.materials (
    id integer NOT NULL,
    titulo character varying(200) NOT NULL,
    arquivo_nome character varying(200),
    tipo character varying(60) NOT NULL,
    tamanho integer NOT NULL,
    status character varying(20) NOT NULL,
    criado_por_id integer NOT NULL,
    criado_em timestamp with time zone DEFAULT now() NOT NULL,
    publicado_em timestamp with time zone,
    conteudo bytea NOT NULL,
    alterado_por_id integer,
    alterado_em timestamp with time zone,
    removido_em timestamp with time zone,
    CONSTRAINT ck_materials_status CHECK (((status)::text = ANY ((ARRAY['RASCUNHO'::character varying, 'PUBLICADO'::character varying])::text[])))
);
ALTER TABLE ONLY public.materials ALTER COLUMN conteudo SET STORAGE EXTERNAL;

--
-- Name: materials_id_seq; Type: SEQUENCE; Schema: public; Owner: -
--

CREATE SEQUENCE public.materials_id_seq
    AS integer
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;

--
-- Name: materials_id_seq; Type: SEQUENCE OWNED BY; Schema: public; Owner: -
--

ALTER SEQUENCE public.materials_id_seq OWNED BY public.materials.id;

--
-- Name: modules; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.modules (
    id integer NOT NULL,
    turma_id integer NOT NULL,
    nome character varying(160) NOT NULL,
    ordem integer NOT NULL,
    criado_em timestamp with time zone DEFAULT now() NOT NULL,
    alterado_por_id integer,
    alterado_em timestamp with time zone,
    removido_em timestamp with time zone
);

--
-- Name: modules_id_seq; Type: SEQUENCE; Schema: public; Owner: -
--

CREATE SEQUENCE public.modules_id_seq
    AS integer
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;

--
-- Name: modules_id_seq; Type: SEQUENCE OWNED BY; Schema: public; Owner: -
--

ALTER SEQUENCE public.modules_id_seq OWNED BY public.modules.id;

--
-- Name: question_options; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.question_options (
    id integer NOT NULL,
    questao_id integer NOT NULL,
    letra character varying(1) NOT NULL,
    texto text NOT NULL,
    CONSTRAINT ck_options_letra CHECK (((letra)::text = ANY ((ARRAY['A'::character varying, 'B'::character varying, 'C'::character varying, 'D'::character varying, 'E'::character varying])::text[])))
);

--
-- Name: question_options_id_seq; Type: SEQUENCE; Schema: public; Owner: -
--

CREATE SEQUENCE public.question_options_id_seq
    AS integer
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;

--
-- Name: question_options_id_seq; Type: SEQUENCE OWNED BY; Schema: public; Owner: -
--

ALTER SEQUENCE public.question_options_id_seq OWNED BY public.question_options.id;

--
-- Name: question_subjects; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.question_subjects (
    id integer NOT NULL,
    questao_id integer NOT NULL,
    assunto_id integer NOT NULL,
    subassunto_id integer
);

--
-- Name: question_subjects_id_seq; Type: SEQUENCE; Schema: public; Owner: -
--

CREATE SEQUENCE public.question_subjects_id_seq
    AS integer
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;

--
-- Name: question_subjects_id_seq; Type: SEQUENCE OWNED BY; Schema: public; Owner: -
--

ALTER SEQUENCE public.question_subjects_id_seq OWNED BY public.question_subjects.id;

--
-- Name: questions; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.questions (
    id integer NOT NULL,
    enunciado text NOT NULL,
    gabarito character varying(1) NOT NULL,
    dificuldade character varying(10) NOT NULL,
    imagem_pendente boolean DEFAULT false NOT NULL,
    resolucao_comentada text,
    video_id integer,
    status character varying(20) NOT NULL,
    rascunho_id integer,
    criado_por_id integer NOT NULL,
    criado_em timestamp with time zone DEFAULT now() NOT NULL,
    alterado_por_id integer,
    alterado_em timestamp with time zone,
    removido_em timestamp with time zone,
    CONSTRAINT ck_questions_dificuldade CHECK (((dificuldade)::text = ANY ((ARRAY['FACIL'::character varying, 'MEDIA'::character varying, 'DIFICIL'::character varying])::text[]))),
    CONSTRAINT ck_questions_gabarito CHECK (((gabarito)::text = ANY ((ARRAY['A'::character varying, 'B'::character varying, 'C'::character varying, 'D'::character varying, 'E'::character varying])::text[]))),
    CONSTRAINT ck_questions_status CHECK (((status)::text = ANY ((ARRAY['RASCUNHO'::character varying, 'PUBLICADO'::character varying])::text[])))
);

--
-- Name: questions_id_seq; Type: SEQUENCE; Schema: public; Owner: -
--

CREATE SEQUENCE public.questions_id_seq
    AS integer
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;

--
-- Name: questions_id_seq; Type: SEQUENCE OWNED BY; Schema: public; Owner: -
--

ALTER SEQUENCE public.questions_id_seq OWNED BY public.questions.id;

--
-- Name: subjects; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.subjects (
    id integer NOT NULL,
    nome character varying(120) NOT NULL,
    criado_em timestamp with time zone DEFAULT now() NOT NULL,
    alterado_por_id integer,
    alterado_em timestamp with time zone,
    removido_em timestamp with time zone
);

--
-- Name: subjects_id_seq; Type: SEQUENCE; Schema: public; Owner: -
--

CREATE SEQUENCE public.subjects_id_seq
    AS integer
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;

--
-- Name: subjects_id_seq; Type: SEQUENCE OWNED BY; Schema: public; Owner: -
--

ALTER SEQUENCE public.subjects_id_seq OWNED BY public.subjects.id;

--
-- Name: submodules; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.submodules (
    id integer NOT NULL,
    modulo_id integer NOT NULL,
    nome character varying(160) NOT NULL,
    tipo character varying(20) NOT NULL,
    ordem integer NOT NULL,
    criado_em timestamp with time zone DEFAULT now() NOT NULL,
    alterado_por_id integer,
    alterado_em timestamp with time zone,
    removido_em timestamp with time zone,
    CONSTRAINT ck_submodules_tipo CHECK (((tipo)::text = 'VIDEO'::text))
);

--
-- Name: submodules_id_seq; Type: SEQUENCE; Schema: public; Owner: -
--

CREATE SEQUENCE public.submodules_id_seq
    AS integer
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;

--
-- Name: submodules_id_seq; Type: SEQUENCE OWNED BY; Schema: public; Owner: -
--

ALTER SEQUENCE public.submodules_id_seq OWNED BY public.submodules.id;

--
-- Name: subtopics; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.subtopics (
    id integer NOT NULL,
    assunto_id integer NOT NULL,
    nome character varying(120) NOT NULL,
    criado_em timestamp with time zone DEFAULT now() NOT NULL,
    alterado_por_id integer,
    alterado_em timestamp with time zone,
    removido_em timestamp with time zone
);

--
-- Name: subtopics_id_seq; Type: SEQUENCE; Schema: public; Owner: -
--

CREATE SEQUENCE public.subtopics_id_seq
    AS integer
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;

--
-- Name: subtopics_id_seq; Type: SEQUENCE OWNED BY; Schema: public; Owner: -
--

ALTER SEQUENCE public.subtopics_id_seq OWNED BY public.subtopics.id;

--
-- Name: users; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.users (
    id integer NOT NULL,
    nome character varying(120) NOT NULL,
    email character varying(180) NOT NULL,
    senha_hash character varying(200) NOT NULL,
    papel character varying(20) NOT NULL,
    criado_em timestamp with time zone DEFAULT now() NOT NULL,
    senha_temporaria boolean DEFAULT false NOT NULL,
    senha_alterada_em timestamp with time zone,
    CONSTRAINT ck_users_papel CHECK (((papel)::text = ANY ((ARRAY['ADMIN'::character varying, 'GERENCIADOR'::character varying, 'ALUNO'::character varying])::text[])))
);

--
-- Name: users_id_seq; Type: SEQUENCE; Schema: public; Owner: -
--

CREATE SEQUENCE public.users_id_seq
    AS integer
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;

--
-- Name: users_id_seq; Type: SEQUENCE OWNED BY; Schema: public; Owner: -
--

ALTER SEQUENCE public.users_id_seq OWNED BY public.users.id;

--
-- Name: video_subjects; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.video_subjects (
    id integer NOT NULL,
    video_id integer NOT NULL,
    assunto_id integer NOT NULL,
    subassunto_id integer
);

--
-- Name: video_subjects_id_seq; Type: SEQUENCE; Schema: public; Owner: -
--

CREATE SEQUENCE public.video_subjects_id_seq
    AS integer
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;

--
-- Name: video_subjects_id_seq; Type: SEQUENCE OWNED BY; Schema: public; Owner: -
--

ALTER SEQUENCE public.video_subjects_id_seq OWNED BY public.video_subjects.id;

--
-- Name: videos; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.videos (
    id integer NOT NULL,
    vimeo_id character varying(60) NOT NULL,
    titulo character varying(300) NOT NULL,
    url character varying(400),
    embed_url character varying(400),
    thumbnail_url character varying(400),
    duracao_segundos integer,
    pasta_vimeo character varying(200),
    criado_em timestamp with time zone DEFAULT now() NOT NULL,
    alterado_por_id integer,
    alterado_em timestamp with time zone,
    removido_em timestamp with time zone
);

--
-- Name: videos_id_seq; Type: SEQUENCE; Schema: public; Owner: -
--

CREATE SEQUENCE public.videos_id_seq
    AS integer
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;

--
-- Name: videos_id_seq; Type: SEQUENCE OWNED BY; Schema: public; Owner: -
--

ALTER SEQUENCE public.videos_id_seq OWNED BY public.videos.id;

--
-- Name: api_tokens id; Type: DEFAULT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.api_tokens ALTER COLUMN id SET DEFAULT nextval('public.api_tokens_id_seq'::regclass);

--
-- Name: classes id; Type: DEFAULT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.classes ALTER COLUMN id SET DEFAULT nextval('public.classes_id_seq'::regclass);

--
-- Name: drafts id; Type: DEFAULT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.drafts ALTER COLUMN id SET DEFAULT nextval('public.drafts_id_seq'::regclass);

--
-- Name: enrollments id; Type: DEFAULT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.enrollments ALTER COLUMN id SET DEFAULT nextval('public.enrollments_id_seq'::regclass);

--
-- Name: exam_answers id; Type: DEFAULT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.exam_answers ALTER COLUMN id SET DEFAULT nextval('public.exam_answers_id_seq'::regclass);

--
-- Name: exam_attempts id; Type: DEFAULT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.exam_attempts ALTER COLUMN id SET DEFAULT nextval('public.exam_attempts_id_seq'::regclass);

--
-- Name: exam_classes id; Type: DEFAULT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.exam_classes ALTER COLUMN id SET DEFAULT nextval('public.exam_classes_id_seq'::regclass);

--
-- Name: exam_questions id; Type: DEFAULT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.exam_questions ALTER COLUMN id SET DEFAULT nextval('public.exam_questions_id_seq'::regclass);

--
-- Name: exams id; Type: DEFAULT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.exams ALTER COLUMN id SET DEFAULT nextval('public.exams_id_seq'::regclass);

--
-- Name: images id; Type: DEFAULT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.images ALTER COLUMN id SET DEFAULT nextval('public.images_id_seq'::regclass);

--
-- Name: imports id; Type: DEFAULT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.imports ALTER COLUMN id SET DEFAULT nextval('public.imports_id_seq'::regclass);

--
-- Name: items id; Type: DEFAULT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.items ALTER COLUMN id SET DEFAULT nextval('public.items_id_seq'::regclass);

--
-- Name: live_class_attendance id; Type: DEFAULT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.live_class_attendance ALTER COLUMN id SET DEFAULT nextval('public.live_class_attendance_id_seq'::regclass);

--
-- Name: live_class_classes id; Type: DEFAULT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.live_class_classes ALTER COLUMN id SET DEFAULT nextval('public.live_class_classes_id_seq'::regclass);

--
-- Name: live_class_students id; Type: DEFAULT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.live_class_students ALTER COLUMN id SET DEFAULT nextval('public.live_class_students_id_seq'::regclass);

--
-- Name: live_classes id; Type: DEFAULT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.live_classes ALTER COLUMN id SET DEFAULT nextval('public.live_classes_id_seq'::regclass);

--
-- Name: login_attempts id; Type: DEFAULT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.login_attempts ALTER COLUMN id SET DEFAULT nextval('public.login_attempts_id_seq'::regclass);

--
-- Name: material_annotations id; Type: DEFAULT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.material_annotations ALTER COLUMN id SET DEFAULT nextval('public.material_annotations_id_seq'::regclass);

--
-- Name: material_classes id; Type: DEFAULT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.material_classes ALTER COLUMN id SET DEFAULT nextval('public.material_classes_id_seq'::regclass);

--
-- Name: material_students id; Type: DEFAULT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.material_students ALTER COLUMN id SET DEFAULT nextval('public.material_students_id_seq'::regclass);

--
-- Name: materials id; Type: DEFAULT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.materials ALTER COLUMN id SET DEFAULT nextval('public.materials_id_seq'::regclass);

--
-- Name: modules id; Type: DEFAULT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.modules ALTER COLUMN id SET DEFAULT nextval('public.modules_id_seq'::regclass);

--
-- Name: question_options id; Type: DEFAULT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.question_options ALTER COLUMN id SET DEFAULT nextval('public.question_options_id_seq'::regclass);

--
-- Name: question_subjects id; Type: DEFAULT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.question_subjects ALTER COLUMN id SET DEFAULT nextval('public.question_subjects_id_seq'::regclass);

--
-- Name: questions id; Type: DEFAULT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.questions ALTER COLUMN id SET DEFAULT nextval('public.questions_id_seq'::regclass);

--
-- Name: subjects id; Type: DEFAULT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.subjects ALTER COLUMN id SET DEFAULT nextval('public.subjects_id_seq'::regclass);

--
-- Name: submodules id; Type: DEFAULT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.submodules ALTER COLUMN id SET DEFAULT nextval('public.submodules_id_seq'::regclass);

--
-- Name: subtopics id; Type: DEFAULT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.subtopics ALTER COLUMN id SET DEFAULT nextval('public.subtopics_id_seq'::regclass);

--
-- Name: users id; Type: DEFAULT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.users ALTER COLUMN id SET DEFAULT nextval('public.users_id_seq'::regclass);

--
-- Name: video_subjects id; Type: DEFAULT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.video_subjects ALTER COLUMN id SET DEFAULT nextval('public.video_subjects_id_seq'::regclass);

--
-- Name: videos id; Type: DEFAULT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.videos ALTER COLUMN id SET DEFAULT nextval('public.videos_id_seq'::regclass);

--
-- Name: api_tokens api_tokens_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.api_tokens
    ADD CONSTRAINT api_tokens_pkey PRIMARY KEY (id);

--
-- Name: api_tokens api_tokens_token_hash_key; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.api_tokens
    ADD CONSTRAINT api_tokens_token_hash_key UNIQUE (token_hash);

--
-- Name: classes classes_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.classes
    ADD CONSTRAINT classes_pkey PRIMARY KEY (id);

--
-- Name: drafts drafts_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.drafts
    ADD CONSTRAINT drafts_pkey PRIMARY KEY (id);

--
-- Name: enrollments enrollments_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.enrollments
    ADD CONSTRAINT enrollments_pkey PRIMARY KEY (id);

--
-- Name: exam_answers exam_answers_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.exam_answers
    ADD CONSTRAINT exam_answers_pkey PRIMARY KEY (id);

--
-- Name: exam_attempts exam_attempts_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.exam_attempts
    ADD CONSTRAINT exam_attempts_pkey PRIMARY KEY (id);

--
-- Name: exam_classes exam_classes_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.exam_classes
    ADD CONSTRAINT exam_classes_pkey PRIMARY KEY (id);

--
-- Name: exam_questions exam_questions_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.exam_questions
    ADD CONSTRAINT exam_questions_pkey PRIMARY KEY (id);

--
-- Name: exams exams_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.exams
    ADD CONSTRAINT exams_pkey PRIMARY KEY (id);

--
-- Name: images images_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.images
    ADD CONSTRAINT images_pkey PRIMARY KEY (id);

--
-- Name: imports imports_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.imports
    ADD CONSTRAINT imports_pkey PRIMARY KEY (id);

--
-- Name: imports imports_token_hash_key; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.imports
    ADD CONSTRAINT imports_token_hash_key UNIQUE (token_hash);

--
-- Name: items items_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.items
    ADD CONSTRAINT items_pkey PRIMARY KEY (id);

--
-- Name: live_class_attendance live_class_attendance_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.live_class_attendance
    ADD CONSTRAINT live_class_attendance_pkey PRIMARY KEY (id);

--
-- Name: live_class_classes live_class_classes_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.live_class_classes
    ADD CONSTRAINT live_class_classes_pkey PRIMARY KEY (id);

--
-- Name: live_class_students live_class_students_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.live_class_students
    ADD CONSTRAINT live_class_students_pkey PRIMARY KEY (id);

--
-- Name: live_classes live_classes_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.live_classes
    ADD CONSTRAINT live_classes_pkey PRIMARY KEY (id);

--
-- Name: login_attempts login_attempts_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.login_attempts
    ADD CONSTRAINT login_attempts_pkey PRIMARY KEY (id);

--
-- Name: material_annotations material_annotations_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.material_annotations
    ADD CONSTRAINT material_annotations_pkey PRIMARY KEY (id);

--
-- Name: material_classes material_classes_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.material_classes
    ADD CONSTRAINT material_classes_pkey PRIMARY KEY (id);

--
-- Name: material_students material_students_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.material_students
    ADD CONSTRAINT material_students_pkey PRIMARY KEY (id);

--
-- Name: materials materials_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.materials
    ADD CONSTRAINT materials_pkey PRIMARY KEY (id);

--
-- Name: modules modules_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.modules
    ADD CONSTRAINT modules_pkey PRIMARY KEY (id);

--
-- Name: question_options question_options_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.question_options
    ADD CONSTRAINT question_options_pkey PRIMARY KEY (id);

--
-- Name: question_subjects question_subjects_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.question_subjects
    ADD CONSTRAINT question_subjects_pkey PRIMARY KEY (id);

--
-- Name: questions questions_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.questions
    ADD CONSTRAINT questions_pkey PRIMARY KEY (id);

--
-- Name: subjects subjects_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.subjects
    ADD CONSTRAINT subjects_pkey PRIMARY KEY (id);

--
-- Name: submodules submodules_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.submodules
    ADD CONSTRAINT submodules_pkey PRIMARY KEY (id);

--
-- Name: subtopics subtopics_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.subtopics
    ADD CONSTRAINT subtopics_pkey PRIMARY KEY (id);

--
-- Name: question_options uq_alternativa; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.question_options
    ADD CONSTRAINT uq_alternativa UNIQUE (questao_id, letra);

--
-- Name: live_class_students uq_aula_aluno; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.live_class_students
    ADD CONSTRAINT uq_aula_aluno UNIQUE (aula_id, usuario_id);

--
-- Name: live_class_attendance uq_aula_presenca; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.live_class_attendance
    ADD CONSTRAINT uq_aula_presenca UNIQUE (aula_id, usuario_id);

--
-- Name: live_class_classes uq_aula_turma; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.live_class_classes
    ADD CONSTRAINT uq_aula_turma UNIQUE (aula_id, turma_id);

--
-- Name: material_students uq_material_aluno; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.material_students
    ADD CONSTRAINT uq_material_aluno UNIQUE (material_id, usuario_id);

--
-- Name: material_annotations uq_material_anotacao; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.material_annotations
    ADD CONSTRAINT uq_material_anotacao UNIQUE (material_id, usuario_id, pagina);

--
-- Name: material_classes uq_material_turma; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.material_classes
    ADD CONSTRAINT uq_material_turma UNIQUE (material_id, turma_id);

--
-- Name: enrollments uq_matricula; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.enrollments
    ADD CONSTRAINT uq_matricula UNIQUE (usuario_id, turma_id);

--
-- Name: question_subjects uq_questao_assunto; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.question_subjects
    ADD CONSTRAINT uq_questao_assunto UNIQUE (questao_id, assunto_id, subassunto_id);

--
-- Name: exam_answers uq_resposta; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.exam_answers
    ADD CONSTRAINT uq_resposta UNIQUE (tentativa_id, questao_id);

--
-- Name: exam_questions uq_simulado_questao; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.exam_questions
    ADD CONSTRAINT uq_simulado_questao UNIQUE (simulado_id, questao_id);

--
-- Name: exam_classes uq_simulado_turma; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.exam_classes
    ADD CONSTRAINT uq_simulado_turma UNIQUE (simulado_id, turma_id);

--
-- Name: exam_attempts uq_tentativa; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.exam_attempts
    ADD CONSTRAINT uq_tentativa UNIQUE (simulado_id, aluno_id);

--
-- Name: video_subjects uq_video_assunto; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.video_subjects
    ADD CONSTRAINT uq_video_assunto UNIQUE (video_id, assunto_id, subassunto_id);

--
-- Name: users users_email_key; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.users
    ADD CONSTRAINT users_email_key UNIQUE (email);

--
-- Name: users users_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.users
    ADD CONSTRAINT users_pkey PRIMARY KEY (id);

--
-- Name: video_subjects video_subjects_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.video_subjects
    ADD CONSTRAINT video_subjects_pkey PRIMARY KEY (id);

--
-- Name: videos videos_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.videos
    ADD CONSTRAINT videos_pkey PRIMARY KEY (id);

--
-- Name: videos videos_vimeo_id_key; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.videos
    ADD CONSTRAINT videos_vimeo_id_key UNIQUE (vimeo_id);

--
-- Name: ix_live_classes_zoom_meeting_id; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX ix_live_classes_zoom_meeting_id ON public.live_classes USING btree (zoom_meeting_id);

--
-- Name: ix_login_attempts_chave; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX ix_login_attempts_chave ON public.login_attempts USING btree (chave);

--
-- Name: uq_assunto_nome; Type: INDEX; Schema: public; Owner: -
--

CREATE UNIQUE INDEX uq_assunto_nome ON public.subjects USING btree (nome) WHERE (removido_em IS NULL);

--
-- Name: uq_item_video; Type: INDEX; Schema: public; Owner: -
--

CREATE UNIQUE INDEX uq_item_video ON public.items USING btree (submodulo_id, video_id) WHERE (removido_em IS NULL);

--
-- Name: uq_modulo_nome; Type: INDEX; Schema: public; Owner: -
--

CREATE UNIQUE INDEX uq_modulo_nome ON public.modules USING btree (turma_id, nome) WHERE (removido_em IS NULL);

--
-- Name: uq_subassunto_nome; Type: INDEX; Schema: public; Owner: -
--

CREATE UNIQUE INDEX uq_subassunto_nome ON public.subtopics USING btree (assunto_id, nome) WHERE (removido_em IS NULL);

--
-- Name: uq_submodulo_nome; Type: INDEX; Schema: public; Owner: -
--

CREATE UNIQUE INDEX uq_submodulo_nome ON public.submodules USING btree (modulo_id, nome) WHERE (removido_em IS NULL);

--
-- Name: uq_turma_nome; Type: INDEX; Schema: public; Owner: -
--

CREATE UNIQUE INDEX uq_turma_nome ON public.classes USING btree (nome) WHERE (removido_em IS NULL);

--
-- Name: api_tokens api_tokens_usuario_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.api_tokens
    ADD CONSTRAINT api_tokens_usuario_id_fkey FOREIGN KEY (usuario_id) REFERENCES public.users(id);

--
-- Name: classes classes_alterado_por_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.classes
    ADD CONSTRAINT classes_alterado_por_id_fkey FOREIGN KEY (alterado_por_id) REFERENCES public.users(id);

--
-- Name: drafts drafts_aprovado_por_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.drafts
    ADD CONSTRAINT drafts_aprovado_por_id_fkey FOREIGN KEY (aprovado_por_id) REFERENCES public.users(id);

--
-- Name: drafts drafts_criado_por_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.drafts
    ADD CONSTRAINT drafts_criado_por_id_fkey FOREIGN KEY (criado_por_id) REFERENCES public.users(id);

--
-- Name: drafts drafts_submodulo_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.drafts
    ADD CONSTRAINT drafts_submodulo_id_fkey FOREIGN KEY (submodulo_id) REFERENCES public.submodules(id);

--
-- Name: drafts drafts_turma_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.drafts
    ADD CONSTRAINT drafts_turma_id_fkey FOREIGN KEY (turma_id) REFERENCES public.classes(id);

--
-- Name: enrollments enrollments_turma_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.enrollments
    ADD CONSTRAINT enrollments_turma_id_fkey FOREIGN KEY (turma_id) REFERENCES public.classes(id);

--
-- Name: enrollments enrollments_usuario_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.enrollments
    ADD CONSTRAINT enrollments_usuario_id_fkey FOREIGN KEY (usuario_id) REFERENCES public.users(id);

--
-- Name: exam_answers exam_answers_questao_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.exam_answers
    ADD CONSTRAINT exam_answers_questao_id_fkey FOREIGN KEY (questao_id) REFERENCES public.questions(id);

--
-- Name: exam_answers exam_answers_tentativa_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.exam_answers
    ADD CONSTRAINT exam_answers_tentativa_id_fkey FOREIGN KEY (tentativa_id) REFERENCES public.exam_attempts(id);

--
-- Name: exam_attempts exam_attempts_aluno_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.exam_attempts
    ADD CONSTRAINT exam_attempts_aluno_id_fkey FOREIGN KEY (aluno_id) REFERENCES public.users(id);

--
-- Name: exam_attempts exam_attempts_simulado_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.exam_attempts
    ADD CONSTRAINT exam_attempts_simulado_id_fkey FOREIGN KEY (simulado_id) REFERENCES public.exams(id);

--
-- Name: exam_classes exam_classes_simulado_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.exam_classes
    ADD CONSTRAINT exam_classes_simulado_id_fkey FOREIGN KEY (simulado_id) REFERENCES public.exams(id);

--
-- Name: exam_classes exam_classes_turma_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.exam_classes
    ADD CONSTRAINT exam_classes_turma_id_fkey FOREIGN KEY (turma_id) REFERENCES public.classes(id);

--
-- Name: exam_questions exam_questions_questao_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.exam_questions
    ADD CONSTRAINT exam_questions_questao_id_fkey FOREIGN KEY (questao_id) REFERENCES public.questions(id);

--
-- Name: exam_questions exam_questions_simulado_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.exam_questions
    ADD CONSTRAINT exam_questions_simulado_id_fkey FOREIGN KEY (simulado_id) REFERENCES public.exams(id);

--
-- Name: exams exams_alterado_por_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.exams
    ADD CONSTRAINT exams_alterado_por_id_fkey FOREIGN KEY (alterado_por_id) REFERENCES public.users(id);

--
-- Name: exams exams_criado_por_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.exams
    ADD CONSTRAINT exams_criado_por_id_fkey FOREIGN KEY (criado_por_id) REFERENCES public.users(id);

--
-- Name: exams exams_rascunho_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.exams
    ADD CONSTRAINT exams_rascunho_id_fkey FOREIGN KEY (rascunho_id) REFERENCES public.drafts(id);

--
-- Name: images images_questao_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.images
    ADD CONSTRAINT images_questao_id_fkey FOREIGN KEY (questao_id) REFERENCES public.questions(id);

--
-- Name: imports imports_criado_por_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.imports
    ADD CONSTRAINT imports_criado_por_id_fkey FOREIGN KEY (criado_por_id) REFERENCES public.users(id);

--
-- Name: imports imports_rascunho_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.imports
    ADD CONSTRAINT imports_rascunho_id_fkey FOREIGN KEY (rascunho_id) REFERENCES public.drafts(id);

--
-- Name: items items_alterado_por_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.items
    ADD CONSTRAINT items_alterado_por_id_fkey FOREIGN KEY (alterado_por_id) REFERENCES public.users(id);

--
-- Name: items items_rascunho_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.items
    ADD CONSTRAINT items_rascunho_id_fkey FOREIGN KEY (rascunho_id) REFERENCES public.drafts(id);

--
-- Name: items items_submodulo_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.items
    ADD CONSTRAINT items_submodulo_id_fkey FOREIGN KEY (submodulo_id) REFERENCES public.submodules(id);

--
-- Name: items items_video_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.items
    ADD CONSTRAINT items_video_id_fkey FOREIGN KEY (video_id) REFERENCES public.videos(id);

--
-- Name: live_class_attendance live_class_attendance_aula_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.live_class_attendance
    ADD CONSTRAINT live_class_attendance_aula_id_fkey FOREIGN KEY (aula_id) REFERENCES public.live_classes(id);

--
-- Name: live_class_attendance live_class_attendance_usuario_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.live_class_attendance
    ADD CONSTRAINT live_class_attendance_usuario_id_fkey FOREIGN KEY (usuario_id) REFERENCES public.users(id);

--
-- Name: live_class_classes live_class_classes_aula_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.live_class_classes
    ADD CONSTRAINT live_class_classes_aula_id_fkey FOREIGN KEY (aula_id) REFERENCES public.live_classes(id);

--
-- Name: live_class_classes live_class_classes_turma_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.live_class_classes
    ADD CONSTRAINT live_class_classes_turma_id_fkey FOREIGN KEY (turma_id) REFERENCES public.classes(id);

--
-- Name: live_class_students live_class_students_aula_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.live_class_students
    ADD CONSTRAINT live_class_students_aula_id_fkey FOREIGN KEY (aula_id) REFERENCES public.live_classes(id);

--
-- Name: live_class_students live_class_students_usuario_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.live_class_students
    ADD CONSTRAINT live_class_students_usuario_id_fkey FOREIGN KEY (usuario_id) REFERENCES public.users(id);

--
-- Name: live_classes live_classes_alterado_por_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.live_classes
    ADD CONSTRAINT live_classes_alterado_por_id_fkey FOREIGN KEY (alterado_por_id) REFERENCES public.users(id);

--
-- Name: live_classes live_classes_criado_por_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.live_classes
    ADD CONSTRAINT live_classes_criado_por_id_fkey FOREIGN KEY (criado_por_id) REFERENCES public.users(id);

--
-- Name: live_classes live_classes_gravacao_item_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.live_classes
    ADD CONSTRAINT live_classes_gravacao_item_id_fkey FOREIGN KEY (gravacao_item_id) REFERENCES public.items(id);

--
-- Name: live_classes live_classes_submodulo_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.live_classes
    ADD CONSTRAINT live_classes_submodulo_id_fkey FOREIGN KEY (submodulo_id) REFERENCES public.submodules(id);

--
-- Name: material_annotations material_annotations_material_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.material_annotations
    ADD CONSTRAINT material_annotations_material_id_fkey FOREIGN KEY (material_id) REFERENCES public.materials(id);

--
-- Name: material_annotations material_annotations_usuario_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.material_annotations
    ADD CONSTRAINT material_annotations_usuario_id_fkey FOREIGN KEY (usuario_id) REFERENCES public.users(id);

--
-- Name: material_classes material_classes_material_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.material_classes
    ADD CONSTRAINT material_classes_material_id_fkey FOREIGN KEY (material_id) REFERENCES public.materials(id);

--
-- Name: material_classes material_classes_turma_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.material_classes
    ADD CONSTRAINT material_classes_turma_id_fkey FOREIGN KEY (turma_id) REFERENCES public.classes(id);

--
-- Name: material_students material_students_material_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.material_students
    ADD CONSTRAINT material_students_material_id_fkey FOREIGN KEY (material_id) REFERENCES public.materials(id);

--
-- Name: material_students material_students_usuario_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.material_students
    ADD CONSTRAINT material_students_usuario_id_fkey FOREIGN KEY (usuario_id) REFERENCES public.users(id);

--
-- Name: materials materials_alterado_por_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.materials
    ADD CONSTRAINT materials_alterado_por_id_fkey FOREIGN KEY (alterado_por_id) REFERENCES public.users(id);

--
-- Name: materials materials_criado_por_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.materials
    ADD CONSTRAINT materials_criado_por_id_fkey FOREIGN KEY (criado_por_id) REFERENCES public.users(id);

--
-- Name: modules modules_alterado_por_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.modules
    ADD CONSTRAINT modules_alterado_por_id_fkey FOREIGN KEY (alterado_por_id) REFERENCES public.users(id);

--
-- Name: modules modules_turma_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.modules
    ADD CONSTRAINT modules_turma_id_fkey FOREIGN KEY (turma_id) REFERENCES public.classes(id);

--
-- Name: question_options question_options_questao_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.question_options
    ADD CONSTRAINT question_options_questao_id_fkey FOREIGN KEY (questao_id) REFERENCES public.questions(id);

--
-- Name: question_subjects question_subjects_assunto_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.question_subjects
    ADD CONSTRAINT question_subjects_assunto_id_fkey FOREIGN KEY (assunto_id) REFERENCES public.subjects(id);

--
-- Name: question_subjects question_subjects_questao_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.question_subjects
    ADD CONSTRAINT question_subjects_questao_id_fkey FOREIGN KEY (questao_id) REFERENCES public.questions(id);

--
-- Name: question_subjects question_subjects_subassunto_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.question_subjects
    ADD CONSTRAINT question_subjects_subassunto_id_fkey FOREIGN KEY (subassunto_id) REFERENCES public.subtopics(id);

--
-- Name: questions questions_alterado_por_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.questions
    ADD CONSTRAINT questions_alterado_por_id_fkey FOREIGN KEY (alterado_por_id) REFERENCES public.users(id);

--
-- Name: questions questions_criado_por_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.questions
    ADD CONSTRAINT questions_criado_por_id_fkey FOREIGN KEY (criado_por_id) REFERENCES public.users(id);

--
-- Name: questions questions_rascunho_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.questions
    ADD CONSTRAINT questions_rascunho_id_fkey FOREIGN KEY (rascunho_id) REFERENCES public.drafts(id);

--
-- Name: questions questions_video_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.questions
    ADD CONSTRAINT questions_video_id_fkey FOREIGN KEY (video_id) REFERENCES public.videos(id);

--
-- Name: subjects subjects_alterado_por_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.subjects
    ADD CONSTRAINT subjects_alterado_por_id_fkey FOREIGN KEY (alterado_por_id) REFERENCES public.users(id);

--
-- Name: submodules submodules_alterado_por_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.submodules
    ADD CONSTRAINT submodules_alterado_por_id_fkey FOREIGN KEY (alterado_por_id) REFERENCES public.users(id);

--
-- Name: submodules submodules_modulo_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.submodules
    ADD CONSTRAINT submodules_modulo_id_fkey FOREIGN KEY (modulo_id) REFERENCES public.modules(id);

--
-- Name: subtopics subtopics_alterado_por_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.subtopics
    ADD CONSTRAINT subtopics_alterado_por_id_fkey FOREIGN KEY (alterado_por_id) REFERENCES public.users(id);

--
-- Name: subtopics subtopics_assunto_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.subtopics
    ADD CONSTRAINT subtopics_assunto_id_fkey FOREIGN KEY (assunto_id) REFERENCES public.subjects(id);

--
-- Name: video_subjects video_subjects_assunto_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.video_subjects
    ADD CONSTRAINT video_subjects_assunto_id_fkey FOREIGN KEY (assunto_id) REFERENCES public.subjects(id);

--
-- Name: video_subjects video_subjects_subassunto_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.video_subjects
    ADD CONSTRAINT video_subjects_subassunto_id_fkey FOREIGN KEY (subassunto_id) REFERENCES public.subtopics(id);

--
-- Name: video_subjects video_subjects_video_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.video_subjects
    ADD CONSTRAINT video_subjects_video_id_fkey FOREIGN KEY (video_id) REFERENCES public.videos(id);

--
-- Name: videos videos_alterado_por_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.videos
    ADD CONSTRAINT videos_alterado_por_id_fkey FOREIGN KEY (alterado_por_id) REFERENCES public.users(id);

--
--
