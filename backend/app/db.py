"""Engine e sessão do SQLAlchemy."""

from collections.abc import Iterator

from sqlalchemy import create_engine
from sqlalchemy.orm import Session, sessionmaker

from app.config import get_settings

_settings = get_settings()

engine = create_engine(
    _settings.database_url,
    pool_pre_ping=True,
    future=True,
    # O padrão do SQLAlchemy (5 + 10) é para um script, não para um portal com
    # centenas de alunos: com ele, o pico de entrada da aula estoura a fila e
    # cada pedido espera 30 s por uma conexão antes de virar erro. Medido em
    # produção — ver docs/CARGA.md. O Postgres do Railway aceita 500 conexões;
    # 40 por processo deixa folga mesmo com oito processos.
    pool_size=20,
    max_overflow=20,
    # E quando a fila estourar mesmo assim, é melhor recusar rápido do que
    # pendurar o aluno meio minuto: 503 na hora, com a página ainda viva.
    pool_timeout=5,
    # Banco inacessível precisa virar erro em segundos (503 no portal), não uma
    # requisição pendurada até o TCP desistir.
    connect_args={"connect_timeout": 10},
)
SessionLocal = sessionmaker(bind=engine, autoflush=False, expire_on_commit=False)


def get_db() -> Iterator[Session]:
    """Dependency do FastAPI: uma sessão por request."""
    db = SessionLocal()
    try:
        yield db
    finally:
        db.close()
