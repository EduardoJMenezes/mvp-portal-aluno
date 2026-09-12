"""Banco de teste próprio, recriado a cada sessão.

Roda contra Postgres de verdade (mesmo servidor, outro database): as regras
que estamos testando incluem constraints e transações, que um SQLite em
memória não reproduz igual.
"""

import os

os.environ.setdefault("DATABASE_URL", "postgresql+psycopg:///plataforma_mvp_test")

import pytest
from sqlalchemy import create_engine
from sqlalchemy.exc import OperationalError
from sqlalchemy.orm import sessionmaker

from app.config import get_settings
from app.identidade import Canal, Identidade
from app.models import (
    Alternativa,
    Base,
    Capitulo,
    Classificacao,
    Dificuldade,
    Matricula,
    Papel,
    Questao,
    Status,
    Turma,
    TurmaQuestao,
    Usuario,
)
from app.security import hash_senha

get_settings.cache_clear()
engine = create_engine(get_settings().database_url, future=True)
Sessao = sessionmaker(bind=engine, autoflush=False, expire_on_commit=False)


# Sem autouse de propósito: quem precisa de banco pede `db`, que depende desta
# fixture. Assim a metade da suíte que não toca o Postgres — Vimeo, leitura de
# nomes, config, montagem do servidor — roda em máquina sem banco.
@pytest.fixture(scope="session")
def schema():
    try:
        with engine.connect():
            pass
    except OperationalError as e:  # noqa: F841
        pytest.skip(
            f"Postgres de teste indisponível em {engine.url}: suba o servidor e crie o "
            "banco (createdb plataforma_mvp_test) para rodar a parte que depende dele.",
            allow_module_level=True,
        )

    Base.metadata.drop_all(engine)
    Base.metadata.create_all(engine)
    yield
    Base.metadata.drop_all(engine)


@pytest.fixture
def db(schema):
    with Sessao() as sessao:
        yield sessao
        sessao.rollback()
    # Cada teste começa do zero: as regras aqui dependem de estado (publicado
    # ou não, respondido ou não), então herdar dados de outro teste esconderia
    # erro em vez de revelar.
    with Sessao() as limpeza:
        for tabela in reversed(Base.metadata.sorted_tables):
            limpeza.execute(tabela.delete())
        limpeza.commit()


@pytest.fixture
def mundo(db):
    """Duas turmas, três alunos, um professor e questões publicadas."""
    professor = Usuario(nome="Helena", email="h@x.demo", senha_hash=hash_senha("x"),
                        papel=Papel.ADMIN)
    joao = Usuario(nome="João", email="joao@x.demo", senha_hash=hash_senha("x"), papel=Papel.ALUNO)
    pedro = Usuario(nome="Pedro", email="pedro@x.demo", senha_hash=hash_senha("x"), papel=Papel.ALUNO)
    db.add_all([professor, joao, pedro])

    t2027 = Turma(nome="Extensivo 2027", ano=2027)
    t2026 = Turma(nome="Extensivo 2026", ano=2026)
    esteq = Capitulo(nome="Estequiometria")
    atom = Capitulo(nome="Atomística")
    db.add_all([t2027, t2026, esteq, atom])
    db.flush()

    db.add_all([
        Matricula(usuario_id=joao.id, turma_id=t2027.id),
        Matricula(usuario_id=pedro.id, turma_id=t2026.id),
    ])

    for numero in (1, 2, 3):
        questao = Questao(enunciado=f"Enunciado {numero}", gabarito="B",
                          dificuldade=Dificuldade.MEDIA, status=Status.PUBLICADO,
                          criado_por_id=professor.id)
        db.add(questao)
        db.flush()
        for letra in "ABCDE":
            db.add(Alternativa(questao_id=questao.id, letra=letra, texto=f"alt {letra}"))
        db.add(Classificacao(questao_id=questao.id, topico="Estequiometria",
                             subtopico=f"Sub {numero}"))
        db.add(TurmaQuestao(turma_id=t2027.id, capitulo_id=esteq.id, questao_id=questao.id,
                            numero=numero, status=Status.PUBLICADO))
    db.commit()

    return {
        "professor": Identidade(professor.id, "Helena", "h@x.demo", Papel.ADMIN, Canal.PORTAL),
        "professor_mcp": Identidade(professor.id, "Helena", "h@x.demo", Papel.ADMIN, Canal.MCP),
        "joao": Identidade(joao.id, "João", "joao@x.demo", Papel.ALUNO, Canal.PORTAL),
        "pedro": Identidade(pedro.id, "Pedro", "pedro@x.demo", Papel.ALUNO, Canal.PORTAL),
        "turma_2027": t2027,
        "turma_2026": t2026,
        "capitulo": esteq,
        "atomistica": atom,
    }
