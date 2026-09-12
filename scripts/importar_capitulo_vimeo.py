"""Importa um capítulo do Vimeo para o portal, como RASCUNHO.

Lê uma pasta do Vimeo, ordena os vídeos pelo número no título (Q52, Q04…) e
cria uma questão por vídeo, em rascunho, pelo mesmo service que o MCP usa. Nada
é publicado: quem publica é uma pessoa, no portal ou pela tool
`publicar_rascunho`.

    DATABASE_URL=... VIMEO_ACCESS_TOKEN=... \
    python scripts/importar_capitulo_vimeo.py \
        --turma "Extensivo 2026" --capitulo "K03 - Estequiometria" \
        --pasta 27843651 --confirmo

Sem `--confirmo` ele mostra o plano e não grava nada. No Vimeo, só faz leitura.
"""

from __future__ import annotations

import argparse
import asyncio
import os
import re
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[1] / "backend"))

from sqlalchemy import select  # noqa: E402

from app.db import SessionLocal  # noqa: E402
from app.identidade import Canal, Identidade  # noqa: E402
from app.integracoes.vimeo import (  # noqa: E402
    CAMPOS_VIDEO_IMPORTACAO,
    ClienteVimeoLeitura,
    TransporteVimeo,
)
from app.models import Capitulo, Papel, Usuario  # noqa: E402
from app.services import rascunhos  # noqa: E402
from app.services.catalogo import resolver_turma  # noqa: E402

# Os títulos do acervo são "Q52", "Q04"… O número é o da questão na apostila,
# e a pasta não devolve os vídeos em ordem numérica.
PADRAO_NUMERO = re.compile(r"\bQ\s*0*(\d+)", re.IGNORECASE)


def numero_do_titulo(titulo: str | None) -> int | None:
    encontrado = PADRAO_NUMERO.search(titulo or "")
    return int(encontrado.group(1)) if encontrado else None


async def ler_pasta(pasta: str, limite: int | None) -> list[dict]:
    """Lê os vídeos da pasta e devolve na ordem do número do título."""
    token = os.environ.get("VIMEO_ACCESS_TOKEN")
    if not token:
        raise SystemExit("Defina VIMEO_ACCESS_TOKEN (escopos public private).")

    transporte = TransporteVimeo(token, agente="mvp-portal-aluno/0.1 (importacao)")
    leitura = ClienteVimeoLeitura(transporte)
    try:
        pasta_info = await leitura.obter_pasta(pasta)
        videos = await leitura.listar_videos_da_pasta(pasta, campos=CAMPOS_VIDEO_IMPORTACAO)
    finally:
        await transporte.aclose()

    itens = []
    for video in videos:
        itens.append(
            {
                "vimeo_id": video.id,
                "titulo": video.nome,
                "url": video.link,
                # Guardado como veio: sem o hash `?h=`, vídeo restrito não toca.
                "embed_url": video.embed_url,
                "thumbnail_url": video.thumbnail_url,
                "duracao_segundos": video.duracao_segundos,
                "pasta": pasta_info.nome,
                "numero_no_titulo": numero_do_titulo(video.nome),
                "publicavel": video.publicavel,
                "privacidade": f"{video.privacidade_view}/{video.privacidade_embed}",
                "transcricao": video.transcricao_status,
            }
        )

    sem_numero = [i for i in itens if i["numero_no_titulo"] is None]
    com_numero = sorted(
        (i for i in itens if i["numero_no_titulo"] is not None), key=lambda i: i["numero_no_titulo"]
    )
    ordenados = com_numero + sem_numero
    return ordenados[:limite] if limite else ordenados


def garantir_capitulo(db, nome: str) -> Capitulo:
    existente = db.scalar(select(Capitulo).where(Capitulo.nome == nome))
    if existente:
        return existente
    capitulo = Capitulo(nome=nome)
    db.add(capitulo)
    db.flush()
    print(f"   capítulo '{nome}' criado (id {capitulo.id})")
    return capitulo


def identidade_do_professor(db, email: str) -> Identidade:
    usuario = db.scalar(select(Usuario).where(Usuario.email == email.lower()))
    if usuario is None:
        raise SystemExit(f"Usuário '{email}' não existe no banco.")
    if usuario.papel not in Papel.OPERADORES:
        raise SystemExit(f"{usuario.nome} é {usuario.papel}; a importação é de ADMIN ou GERENCIADOR.")
    return Identidade(usuario.id, usuario.nome, usuario.email, usuario.papel, Canal.MCP)


def main() -> int:
    parser = argparse.ArgumentParser(description="Importa uma pasta do Vimeo como rascunho.")
    parser.add_argument("--turma", required=True, help='Nome da turma, ex.: "Extensivo 2026"')
    parser.add_argument("--capitulo", required=True, help='Nome do capítulo, ex.: "K03 - Estequiometria"')
    parser.add_argument("--pasta", required=True, help="ID da pasta no Vimeo")
    parser.add_argument("--professor", default="professor@escola.demo", help="E-mail do operador")
    parser.add_argument("--limite", type=int, help="Importar no máximo N vídeos")
    parser.add_argument("--confirmo", action="store_true", help="Sem isto, só mostra o plano")
    args = parser.parse_args()

    if hasattr(sys.stdout, "reconfigure"):
        sys.stdout.reconfigure(encoding="utf-8", errors="replace")

    itens = asyncio.run(ler_pasta(args.pasta, args.limite))
    print(f"\nPasta {args.pasta}: {len(itens)} vídeo(s), na ordem que serão importados:\n")
    for posicao, item in enumerate(itens, start=1):
        aviso = "" if item["publicavel"] else "  [NÃO REPRODUZÍVEL]"
        print(
            f"  {posicao:>2}. {item['titulo']:<10} vimeo={item['vimeo_id']} "
            f"num={item['numero_no_titulo']} dur={item['duracao_segundos']}s "
            f"privacidade={item['privacidade']} transcrição={item['transcricao']}{aviso}"
        )

    if not args.confirmo:
        print("\nNada foi gravado. Rode de novo com --confirmo para importar como rascunho.")
        return 0

    with SessionLocal() as db:
        ident = identidade_do_professor(db, args.professor)
        turma = resolver_turma(db, args.turma)
        capitulo = garantir_capitulo(db, args.capitulo)
        db.commit()

        resultado = rascunhos.importar_questoes_vimeo(
            db,
            ident,
            turma.id,
            capitulo.id,
            [
                {k: v for k, v in item.items() if k in
                 ("vimeo_id", "titulo", "url", "embed_url", "thumbnail_url", "duracao_segundos", "pasta")}
                for item in itens
            ],
        )

    print("\n=== rascunho criado ===")
    print(f"   id: {resultado.get('id')}")
    print(f"   resumo: {resultado.get('resumo')}")
    print(f"   questões: {resultado.get('videos_associados')}")
    if resultado.get("erros"):
        print(f"   erros: {resultado['erros']}")
    print(
        "\nNada foi publicado. Para os alunos verem, aprove em Admin > Rascunhos no portal,"
        "\nou peça ao Claude pela tool publicar_rascunho."
    )
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
