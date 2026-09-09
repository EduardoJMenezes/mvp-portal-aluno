"""Modelo de dados da POC (seção 18 do MVP).

Duas decisões que valem explicação:

1. `Rascunho` é uma entidade de primeira classe. Tudo que a IA cria nasce
   apontando para um rascunho, e a publicação é uma transição desse rascunho —
   não de cada linha solta. É o que faz `publicar_rascunho(id)` ser uma
   operação atômica e auditável.

2. Questão e vídeo são reaproveitáveis entre anos: quem amarra uma questão a
   uma turma/capítulo é `TurmaQuestao`, não a própria questão. A organização
   anual pertence à plataforma, não às pastas do Vimeo.
"""

from __future__ import annotations

from datetime import datetime

from sqlalchemy import (
    Boolean,
    CheckConstraint,
    DateTime,
    ForeignKey,
    Integer,
    String,
    Text,
    UniqueConstraint,
    func,
)
from sqlalchemy.orm import DeclarativeBase, Mapped, mapped_column, relationship


class Base(DeclarativeBase):
    pass


# --- vocabulário fechado do domínio -----------------------------------------


class Papel:
    ADMIN = "ADMIN"
    GERENCIADOR = "GERENCIADOR"
    ALUNO = "ALUNO"

    TODOS = (ADMIN, GERENCIADOR, ALUNO)
    # Quem pode operar o MCP administrativo (seção 4).
    OPERADORES = (ADMIN, GERENCIADOR)


class Status:
    RASCUNHO = "RASCUNHO"
    PUBLICADO = "PUBLICADO"


class TipoRascunho:
    QUESTOES = "QUESTOES"
    SIMULADO = "SIMULADO"


class Dificuldade:
    FACIL = "FACIL"
    MEDIA = "MEDIA"
    DIFICIL = "DIFICIL"

    TODAS = (FACIL, MEDIA, DIFICIL)


LETRAS = ("A", "B", "C", "D", "E")


def _agora() -> Mapped[datetime]:
    return mapped_column(DateTime(timezone=True), server_default=func.now(), nullable=False)


# --- pessoas e turmas --------------------------------------------------------


class Usuario(Base):
    __tablename__ = "users"

    id: Mapped[int] = mapped_column(primary_key=True)
    nome: Mapped[str] = mapped_column(String(120), nullable=False)
    email: Mapped[str] = mapped_column(String(180), unique=True, nullable=False)
    senha_hash: Mapped[str] = mapped_column(String(200), nullable=False)
    papel: Mapped[str] = mapped_column(String(20), nullable=False)
    criado_em: Mapped[datetime] = _agora()

    __table_args__ = (
        CheckConstraint("papel in ('ADMIN','GERENCIADOR','ALUNO')", name="ck_users_papel"),
    )

    matriculas: Mapped[list[Matricula]] = relationship(back_populates="aluno")

    @property
    def opera_mcp(self) -> bool:
        return self.papel in Papel.OPERADORES


class Turma(Base):
    __tablename__ = "classes"

    id: Mapped[int] = mapped_column(primary_key=True)
    nome: Mapped[str] = mapped_column(String(120), unique=True, nullable=False)
    ano: Mapped[int] = mapped_column(Integer, nullable=False)

    matriculas: Mapped[list[Matricula]] = relationship(back_populates="turma")


class Matricula(Base):
    __tablename__ = "enrollments"

    id: Mapped[int] = mapped_column(primary_key=True)
    usuario_id: Mapped[int] = mapped_column(ForeignKey("users.id"), nullable=False)
    turma_id: Mapped[int] = mapped_column(ForeignKey("classes.id"), nullable=False)
    criado_em: Mapped[datetime] = _agora()

    __table_args__ = (UniqueConstraint("usuario_id", "turma_id", name="uq_matricula"),)

    aluno: Mapped[Usuario] = relationship(back_populates="matriculas")
    turma: Mapped[Turma] = relationship(back_populates="matriculas")


class Capitulo(Base):
    __tablename__ = "chapters"

    id: Mapped[int] = mapped_column(primary_key=True)
    nome: Mapped[str] = mapped_column(String(120), unique=True, nullable=False)


# --- acervo ------------------------------------------------------------------


class Video(Base):
    """Espelho local do mínimo necessário para exibir/relacionar um vídeo.

    O vídeo continua morando no Vimeo; aqui guardamos só o id e os metadados
    que a plataforma precisa (seção 3).
    """

    __tablename__ = "videos"

    id: Mapped[int] = mapped_column(primary_key=True)
    vimeo_id: Mapped[str] = mapped_column(String(60), unique=True, nullable=False)
    titulo: Mapped[str] = mapped_column(String(300), nullable=False)
    url: Mapped[str | None] = mapped_column(String(400))
    # Guardada como o Vimeo devolveu: para vídeo unlisted ela traz o hash de
    # privacidade, sem o qual o player recusa tocar.
    embed_url: Mapped[str | None] = mapped_column(String(400))
    thumbnail_url: Mapped[str | None] = mapped_column(String(400))
    duracao_segundos: Mapped[int | None] = mapped_column(Integer)
    pasta_vimeo: Mapped[str | None] = mapped_column(String(200))
    criado_em: Mapped[datetime] = _agora()


class Rascunho(Base):
    __tablename__ = "drafts"

    id: Mapped[int] = mapped_column(primary_key=True)
    tipo: Mapped[str] = mapped_column(String(20), nullable=False)
    turma_id: Mapped[int | None] = mapped_column(ForeignKey("classes.id"))
    capitulo_id: Mapped[int | None] = mapped_column(ForeignKey("chapters.id"))
    resumo: Mapped[str] = mapped_column(Text, nullable=False)
    origem: Mapped[str] = mapped_column(String(40), nullable=False, default="MCP")
    status: Mapped[str] = mapped_column(String(20), nullable=False, default=Status.RASCUNHO)

    criado_por_id: Mapped[int] = mapped_column(ForeignKey("users.id"), nullable=False)
    criado_em: Mapped[datetime] = _agora()

    # Quem carimbou a aprovação humana, e por qual canal. Sem estes três
    # campos preenchidos nada sai de RASCUNHO — ver services/publicacao.py.
    aprovado_por_id: Mapped[int | None] = mapped_column(ForeignKey("users.id"))
    aprovado_em: Mapped[datetime | None] = mapped_column(DateTime(timezone=True))
    aprovado_via: Mapped[str | None] = mapped_column(String(40))
    publicado_em: Mapped[datetime | None] = mapped_column(DateTime(timezone=True))

    __table_args__ = (
        CheckConstraint("tipo in ('QUESTOES','SIMULADO')", name="ck_drafts_tipo"),
        CheckConstraint("status in ('RASCUNHO','PUBLICADO')", name="ck_drafts_status"),
    )

    turma: Mapped[Turma | None] = relationship()
    capitulo: Mapped[Capitulo | None] = relationship()
    criado_por: Mapped[Usuario] = relationship(foreign_keys=[criado_por_id])
    aprovado_por: Mapped[Usuario | None] = relationship(foreign_keys=[aprovado_por_id])


class Questao(Base):
    __tablename__ = "questions"

    id: Mapped[int] = mapped_column(primary_key=True)
    enunciado: Mapped[str] = mapped_column(Text, nullable=False)
    gabarito: Mapped[str] = mapped_column(String(1), nullable=False)
    dificuldade: Mapped[str] = mapped_column(String(10), nullable=False, default=Dificuldade.MEDIA)
    video_id: Mapped[int | None] = mapped_column(ForeignKey("videos.id"))
    status: Mapped[str] = mapped_column(String(20), nullable=False, default=Status.RASCUNHO)
    rascunho_id: Mapped[int | None] = mapped_column(ForeignKey("drafts.id"))
    criado_por_id: Mapped[int] = mapped_column(ForeignKey("users.id"), nullable=False)
    criado_em: Mapped[datetime] = _agora()

    __table_args__ = (
        CheckConstraint("gabarito in ('A','B','C','D','E')", name="ck_questions_gabarito"),
        CheckConstraint("status in ('RASCUNHO','PUBLICADO')", name="ck_questions_status"),
        CheckConstraint(
            "dificuldade in ('FACIL','MEDIA','DIFICIL')", name="ck_questions_dificuldade"
        ),
    )

    alternativas: Mapped[list[Alternativa]] = relationship(
        back_populates="questao", cascade="all, delete-orphan", order_by="Alternativa.letra"
    )
    classificacoes: Mapped[list[Classificacao]] = relationship(
        back_populates="questao", cascade="all, delete-orphan"
    )
    video: Mapped[Video | None] = relationship()


class Alternativa(Base):
    __tablename__ = "question_options"

    id: Mapped[int] = mapped_column(primary_key=True)
    questao_id: Mapped[int] = mapped_column(ForeignKey("questions.id"), nullable=False)
    letra: Mapped[str] = mapped_column(String(1), nullable=False)
    texto: Mapped[str] = mapped_column(Text, nullable=False)

    __table_args__ = (
        UniqueConstraint("questao_id", "letra", name="uq_alternativa"),
        CheckConstraint("letra in ('A','B','C','D','E')", name="ck_options_letra"),
    )

    questao: Mapped[Questao] = relationship(back_populates="alternativas")


class Classificacao(Base):
    """Tópico/subtópico da questão. Tabela à parte porque a taxonomia final
    ainda não existe (seção 12) e vai crescer sem mexer em `questions`."""

    __tablename__ = "question_classifications"

    id: Mapped[int] = mapped_column(primary_key=True)
    questao_id: Mapped[int] = mapped_column(ForeignKey("questions.id"), nullable=False)
    topico: Mapped[str] = mapped_column(String(120), nullable=False)
    subtopico: Mapped[str | None] = mapped_column(String(120))

    questao: Mapped[Questao] = relationship(back_populates="classificacoes")


class TurmaQuestao(Base):
    """Disponibilização de uma questão para uma turma, dentro de um capítulo.

    É esta linha — e não a questão — que o aluno enxerga. A mesma questão pode
    estar no capítulo 2 de 2026 e no capítulo 4 de 2027.
    """

    __tablename__ = "class_questions"

    id: Mapped[int] = mapped_column(primary_key=True)
    turma_id: Mapped[int] = mapped_column(ForeignKey("classes.id"), nullable=False)
    capitulo_id: Mapped[int] = mapped_column(ForeignKey("chapters.id"), nullable=False)
    questao_id: Mapped[int] = mapped_column(ForeignKey("questions.id"), nullable=False)
    numero: Mapped[int] = mapped_column(Integer, nullable=False)
    status: Mapped[str] = mapped_column(String(20), nullable=False, default=Status.RASCUNHO)
    rascunho_id: Mapped[int | None] = mapped_column(ForeignKey("drafts.id"))
    criado_em: Mapped[datetime] = _agora()

    __table_args__ = (
        UniqueConstraint("turma_id", "capitulo_id", "questao_id", name="uq_turma_questao"),
        CheckConstraint("status in ('RASCUNHO','PUBLICADO')", name="ck_class_questions_status"),
    )

    turma: Mapped[Turma] = relationship()
    capitulo: Mapped[Capitulo] = relationship()
    questao: Mapped[Questao] = relationship()


# --- simulados ---------------------------------------------------------------


class Simulado(Base):
    __tablename__ = "exams"

    id: Mapped[int] = mapped_column(primary_key=True)
    titulo: Mapped[str] = mapped_column(String(200), nullable=False)
    turma_id: Mapped[int] = mapped_column(ForeignKey("classes.id"), nullable=False)
    status: Mapped[str] = mapped_column(String(20), nullable=False, default=Status.RASCUNHO)
    rascunho_id: Mapped[int | None] = mapped_column(ForeignKey("drafts.id"))
    criado_por_id: Mapped[int] = mapped_column(ForeignKey("users.id"), nullable=False)
    criado_em: Mapped[datetime] = _agora()
    publicado_em: Mapped[datetime | None] = mapped_column(DateTime(timezone=True))

    __table_args__ = (CheckConstraint("status in ('RASCUNHO','PUBLICADO')", name="ck_exams_status"),)

    turma: Mapped[Turma] = relationship()
    questoes: Mapped[list[SimuladoQuestao]] = relationship(
        back_populates="simulado", cascade="all, delete-orphan", order_by="SimuladoQuestao.ordem"
    )


class SimuladoQuestao(Base):
    __tablename__ = "exam_questions"

    id: Mapped[int] = mapped_column(primary_key=True)
    simulado_id: Mapped[int] = mapped_column(ForeignKey("exams.id"), nullable=False)
    questao_id: Mapped[int] = mapped_column(ForeignKey("questions.id"), nullable=False)
    ordem: Mapped[int] = mapped_column(Integer, nullable=False)

    __table_args__ = (UniqueConstraint("simulado_id", "questao_id", name="uq_simulado_questao"),)

    simulado: Mapped[Simulado] = relationship(back_populates="questoes")
    questao: Mapped[Questao] = relationship()


class Tentativa(Base):
    __tablename__ = "exam_attempts"

    id: Mapped[int] = mapped_column(primary_key=True)
    simulado_id: Mapped[int] = mapped_column(ForeignKey("exams.id"), nullable=False)
    aluno_id: Mapped[int] = mapped_column(ForeignKey("users.id"), nullable=False)
    iniciado_em: Mapped[datetime] = _agora()
    finalizado_em: Mapped[datetime | None] = mapped_column(DateTime(timezone=True))

    __table_args__ = (UniqueConstraint("simulado_id", "aluno_id", name="uq_tentativa"),)

    simulado: Mapped[Simulado] = relationship()
    aluno: Mapped[Usuario] = relationship()
    respostas: Mapped[list[Resposta]] = relationship(
        back_populates="tentativa", cascade="all, delete-orphan"
    )


class Resposta(Base):
    __tablename__ = "exam_answers"

    id: Mapped[int] = mapped_column(primary_key=True)
    tentativa_id: Mapped[int] = mapped_column(ForeignKey("exam_attempts.id"), nullable=False)
    questao_id: Mapped[int] = mapped_column(ForeignKey("questions.id"), nullable=False)
    alternativa_marcada: Mapped[str] = mapped_column(String(1), nullable=False)
    correta: Mapped[bool] = mapped_column(Boolean, nullable=False)
    respondido_em: Mapped[datetime] = _agora()

    __table_args__ = (
        UniqueConstraint("tentativa_id", "questao_id", name="uq_resposta"),
        CheckConstraint("alternativa_marcada in ('A','B','C','D','E')", name="ck_answers_letra"),
    )

    tentativa: Mapped[Tentativa] = relationship(back_populates="respostas")
    questao: Mapped[Questao] = relationship()


# --- credencial do MCP -------------------------------------------------------


class TokenMCP(Base):
    """Token Bearer que identifica quem está do outro lado do MCP.

    Guardamos só o hash: o valor em claro existe uma vez, na criação. Cada
    token pertence a um usuário, e é o papel desse usuário que decide o que a
    sessão pode fazer — o MCP não tem permissão própria.
    """

    __tablename__ = "api_tokens"

    id: Mapped[int] = mapped_column(primary_key=True)
    usuario_id: Mapped[int] = mapped_column(ForeignKey("users.id"), nullable=False)
    nome: Mapped[str] = mapped_column(String(120), nullable=False)
    token_hash: Mapped[str] = mapped_column(String(64), unique=True, nullable=False)
    criado_em: Mapped[datetime] = _agora()
    ultimo_uso_em: Mapped[datetime | None] = mapped_column(DateTime(timezone=True))
    revogado: Mapped[bool] = mapped_column(Boolean, nullable=False, default=False)

    usuario: Mapped[Usuario] = relationship()
