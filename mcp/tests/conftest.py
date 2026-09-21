"""Banco de teste próprio, recriado a cada sessão.

Roda contra Postgres de verdade (mesmo servidor, outro database): as regras
que estamos testando incluem constraints e transações, que um SQLite em
memória não reproduz igual.
"""

import os

os.environ.setdefault("DATABASE_URL", "postgresql+psycopg:///plataforma_mvp_test")
# O TestClient fala http: um cookie Secure não voltaria nas chamadas seguintes.
os.environ.setdefault("SESSAO_COOKIE_SEGURO", "false")

import pytest
from sqlalchemy import create_engine
from sqlalchemy.exc import OperationalError
from sqlalchemy.orm import sessionmaker

from app.config import get_settings
from app.identidade import Canal, Identidade
from app.models import (
    Alternativa,
    Assunto,
    Base,
    Dificuldade,
    Item,
    Matricula,
    Modulo,
    Papel,
    Questao,
    QuestaoAssunto,
    Status,
    SubAssunto,
    SubModulo,
    TipoSubModulo,
    Turma,
    Usuario,
    Video,
    VideoAssunto,
)
from app.security import hash_senha

get_settings.cache_clear()
engine = create_engine(get_settings().database_url, future=True)
Sessao = sessionmaker(bind=engine, autoflush=False, expire_on_commit=False)

# A suíte apaga e recria todas as tabelas. Rodar com o DATABASE_URL de produção
# (dentro do container, por exemplo) destruiria o curso real — então só roda
# contra um banco cujo nome termina em _test.
if not (engine.url.database or "").endswith("_test"):
    raise RuntimeError(
        f"Os testes recriam o banco inteiro e o DATABASE_URL aponta para "
        f"'{engine.url.database}'. Use um banco *_test."
    )


# Sem autouse de propósito: quem precisa de banco pede `db`, que depende desta
# fixture. Assim a metade da suíte que não toca o Postgres — Vimeo, leitura de
# nomes, config, montagem do servidor — roda em máquina sem banco.
@pytest.fixture(scope="session")
def schema():
    try:
        with engine.connect():
            pass
    except OperationalError as e:  # noqa: F841
        if os.environ.get("CI"):
            raise  # na CI, pular a metade que depende do banco passaria em silêncio
        pytest.skip(
            f"Postgres de teste indisponível em {engine.url}: suba o servidor e crie o "
            "banco (createdb plataforma_mvp_test) para rodar a parte que depende dele.",
            allow_module_level=True,
        )

    Base.metadata.drop_all(engine)
    Base.metadata.create_all(engine)
    yield
    Base.metadata.drop_all(engine)


@pytest.fixture(scope="session")
def api_java(schema):
    """A API em Java no ar, contra o mesmo banco — o outro lado da ponte.

    Desde que as tools passaram a falar HTTP, testá-las sem este processo é
    testar o `httpx`. Sobe depois do `schema` porque o Hibernate confere as
    tabelas na partida, e cai antes dele, porque o teardown as derruba.

    Sem `API_JAR`, os testes que dependem dela pulam: dá para mexer no
    adaptador sem ter um Java instalado. Na CI a variável existe, e aí pular
    passaria em silêncio — por isso lá vira erro.
    """
    import pathlib
    import subprocess
    import tempfile
    import time
    import urllib.error
    import urllib.parse
    import urllib.request

    jar = os.environ.get("API_JAR")
    if not jar or not os.path.exists(jar):
        if os.environ.get("CI"):
            raise RuntimeError(f"API_JAR não aponta para o jar da API: {jar!r}")
        pytest.skip("Sem API_JAR: rode `./mvnw -q package -DskipTests` em api/ e aponte a variável.")

    base = get_settings().api_base_url.rstrip("/")
    # A porta sai da própria URL: sem isso o Java insiste na 8080 e morre quando
    # já existe uma instância ali — o erro que aparece é "webServerStartStop",
    # que não diz nada sobre porta ocupada.
    porta = str(urllib.parse.urlsplit(base).port or 8080)
    # Em arquivo, não em PIPE: o Spring escreve dezenas de KB na partida, e um
    # pipe que ninguém lê enche e trava o processo antes de ele abrir a porta.
    registro = tempfile.NamedTemporaryFile(
        "w+", suffix=".log", prefix="api-", delete=False, encoding="utf-8", errors="replace")
    processo = subprocess.Popen(
        ["java", "-jar", jar],
        env={**os.environ,
             "SERVICO_TOKEN": get_settings().servico_token,
             "SPRING_DATASOURCE_URL": _jdbc(engine.url),
             "SPRING_DATASOURCE_USERNAME": engine.url.username or "",
             "SPRING_DATASOURCE_PASSWORD": engine.url.password or "",
             # O schema é do conftest nesta suíte; o Flyway não tem o que fazer.
             "SPRING_FLYWAY_ENABLED": "false",
             "SERVER_PORT": porta},
        stdout=registro, stderr=subprocess.STDOUT,
    )

    def log() -> str:
        registro.flush()
        return pathlib.Path(registro.name).read_text(encoding="utf-8", errors="replace")[-3000:]

    try:
        for _ in range(240):
            if processo.poll() is not None:
                raise RuntimeError(f"a API morreu ao subir:\n{log()}")
            try:
                with urllib.request.urlopen(f"{base}/actuator/health", timeout=1) as r:
                    if r.status == 200:
                        break
            except (urllib.error.URLError, OSError):
                time.sleep(0.5)
        else:
            raise RuntimeError(f"a API não respondeu em {base} a tempo:\n{log()}")
        yield base
    finally:
        processo.terminate()
        try:
            processo.wait(timeout=20)
        except subprocess.TimeoutExpired:
            processo.kill()
        registro.close()


def _jdbc(url) -> str:
    """A mesma URL do SQLAlchemy, como o JDBC a escreve."""
    return f"jdbc:postgresql://{url.host}:{url.port or 5432}/{url.database}"


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
    """O mundo mínimo para as regras aparecerem.

    Duas turmas com alunos diferentes, um módulo publicado na 2027 e outro na
    2026 — é essa assimetria que faz a segregação e o conteúdo bloqueado terem
    o que testar. A taxonomia é compartilhada entre as duas, de propósito: é o
    que liga o erro de um aluno ao vídeo que está na turma do outro.
    """
    professor = Usuario(nome="Helena", email="h@x.demo", senha_hash=hash_senha("x"),
                        papel=Papel.ADMIN)
    joao = Usuario(nome="João", email="joao@x.demo", senha_hash=hash_senha("x"), papel=Papel.ALUNO)
    pedro = Usuario(nome="Pedro", email="pedro@x.demo", senha_hash=hash_senha("x"), papel=Papel.ALUNO)
    db.add_all([professor, joao, pedro])

    t2027 = Turma(nome="Extensivo 2027", ano=2027)
    t2026 = Turma(nome="Extensivo 2026", ano=2026)
    db.add_all([t2027, t2026])
    db.flush()

    db.add_all([
        Matricula(usuario_id=joao.id, turma_id=t2027.id),
        Matricula(usuario_id=pedro.id, turma_id=t2026.id),
    ])

    esteq = Assunto(nome="Estequiometria")
    atom = Assunto(nome="Atomística")
    db.add_all([esteq, atom])
    db.flush()
    pureza = SubAssunto(assunto_id=esteq.id, nome="Pureza e rendimento")
    db.add(pureza)
    db.flush()

    def _modulo(turma, nome, ordem=1):
        modulo = Modulo(turma_id=turma.id, nome=nome, ordem=ordem)
        db.add(modulo)
        db.flush()
        aulas = SubModulo(modulo_id=modulo.id, nome="Aulas", tipo=TipoSubModulo.VIDEO, ordem=1)
        questoes = SubModulo(
            modulo_id=modulo.id, nome="Questões da apostila", tipo=TipoSubModulo.VIDEO, ordem=2
        )
        db.add_all([aulas, questoes])
        db.flush()
        return modulo, aulas, questoes

    # 2027: o módulo completo, publicado.
    modulo, aulas, questoes_sub = _modulo(t2027, "K01 - Estequiometria")
    itens = []
    for numero in (1, 2, 3):
        video = Video(vimeo_id=f"vid{numero}", titulo=f"Vídeo {numero}",
                      embed_url=f"https://player.vimeo.com/video/{numero}?h=abc")
        db.add(video)
        db.flush()
        db.add(VideoAssunto(video_id=video.id, assunto_id=esteq.id, subassunto_id=pureza.id))
        item = Item(submodulo_id=questoes_sub.id, video_id=video.id,
                    nome=f"Q{numero:02d}", ordem=numero, status=Status.PUBLICADO)
        db.add(item)
        itens.append(item)

    # 2026: um módulo com um vídeo do mesmo assunto — o que o aluno da 2027
    # vai ver bloqueado quando errar Estequiometria.
    outro_modulo, _outras_aulas, outras_questoes = _modulo(t2026, "K03 - Estequiometria")
    video_alheio = Video(vimeo_id="vid-2026", titulo="Vídeo de outra turma",
                         embed_url="https://player.vimeo.com/video/99?h=xyz")
    db.add(video_alheio)
    db.flush()
    db.add(VideoAssunto(video_id=video_alheio.id, assunto_id=esteq.id, subassunto_id=pureza.id))
    db.add(Item(submodulo_id=outras_questoes.id, video_id=video_alheio.id,
                nome="Q01", ordem=1, status=Status.PUBLICADO))

    # Acervo de simulado: questão não pertence a turma nenhuma.
    questoes = []
    for numero in (1, 2, 3):
        questao = Questao(enunciado=f"Enunciado {numero}", gabarito="B",
                          dificuldade=Dificuldade.MEDIA, status=Status.PUBLICADO,
                          criado_por_id=professor.id)
        db.add(questao)
        db.flush()
        for letra in "ABCDE":
            db.add(Alternativa(questao_id=questao.id, letra=letra, texto=f"alt {letra}"))
        db.add(QuestaoAssunto(questao_id=questao.id, assunto_id=esteq.id, subassunto_id=pureza.id))
        questoes.append(questao)

    db.commit()

    return {
        "professor": Identidade(professor.id, "Helena", "h@x.demo", Papel.ADMIN, Canal.PORTAL),
        "professor_mcp": Identidade(professor.id, "Helena", "h@x.demo", Papel.ADMIN, Canal.MCP),
        "joao": Identidade(joao.id, "João", "joao@x.demo", Papel.ALUNO, Canal.PORTAL),
        "pedro": Identidade(pedro.id, "Pedro", "pedro@x.demo", Papel.ALUNO, Canal.PORTAL),
        "turma_2027": t2027,
        "turma_2026": t2026,
        "modulo": modulo,
        "aulas": aulas,
        "submodulo": questoes_sub,
        "modulo_2026": outro_modulo,
        "itens": itens,
        "questoes": questoes,
        "assunto": esteq,
        "atomistica": atom,
        "subassunto": pureza,
        "video_de_outra_turma": video_alheio,
    }
