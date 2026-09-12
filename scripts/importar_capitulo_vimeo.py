"""Importa uma pasta do Vimeo para o portal, como RASCUNHO.

É a mesma coisa que a tool `importar_pasta_vimeo_como_rascunho` do MCP faz,
pela linha de comando: lê a pasta, ordena pelo número no título (o acervo usa
Q04, Q52… e a API devolve fora de ordem) e cria uma questão por vídeo em
rascunho. Nada é publicado; a aprovação continua sendo humana.

    DATABASE_URL=... VIMEO_ACCESS_TOKEN=... \
    python scripts/importar_capitulo_vimeo.py \
        --turma "Extensivo 2026" --capitulo "K03 - Estequiometria" \
        --pasta 27843651 --confirmo

Sem `--confirmo` ele mostra a simulação e não grava nada. No Vimeo, só lê.
"""

from __future__ import annotations

import argparse
import asyncio
import json
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[1] / "backend"))

from sqlalchemy import select  # noqa: E402

from app.db import SessionLocal  # noqa: E402
from app.errors import ErroDominio  # noqa: E402
from app.identidade import Canal, Identidade  # noqa: E402
from app.models import Papel, Usuario  # noqa: E402
from app.services import vimeo_importacao  # noqa: E402


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
    parser.add_argument("--confirmo", action="store_true", help="Sem isto, só simula")
    args = parser.parse_args()

    if hasattr(sys.stdout, "reconfigure"):
        sys.stdout.reconfigure(encoding="utf-8", errors="replace")

    plano = asyncio.run(vimeo_importacao.ler_plano(args.pasta))
    print(f"\nPasta {plano.pasta_id} ({plano.pasta_nome}): {len(plano.itens)} vídeo(s)\n")
    for item in plano.itens:
        numero = f"Q{item.numero:02d}" if item.numero else "sem número"
        avisos = f"  avisos: {'; '.join(item.avisos)}" if item.avisos else ""
        print(
            f"  {numero:<11} {item.titulo:<10} vimeo={item.vimeo_id} "
            f"dur={item.duracao_segundos}s confiança={item.confianca}{avisos}"
        )

    try:
        with SessionLocal() as db:
            ident = identidade_do_professor(db, args.professor)
            simulacao = vimeo_importacao.avaliar(db, ident, plano, args.turma, args.capitulo)
            print("\n=== simulação ===")
            print(f"   turma: {simulacao['turma']}")
            print(f"   capítulo: {simulacao['capitulo']['nome']} (já existe: {simulacao['capitulo']['ja_existe']})")
            print(f"   questões a criar: {simulacao['questoes_que_serao_criadas']}")
            print(f"   vídeos já no acervo: {simulacao['videos_ja_no_acervo'] or 'nenhum'}")
            print(f"   conflitos: {simulacao['conflitos'] or 'nenhum'}")

            if not args.confirmo:
                print("\nNada foi gravado. Rode de novo com --confirmo para criar o rascunho.")
                return 0

            resultado = vimeo_importacao.aplicar(db, ident, plano, args.turma, args.capitulo)
    except ErroDominio as erro:
        print(f"\nrecusado: {erro}")
        return 1

    print("\n=== rascunho criado ===")
    print(json.dumps({k: v for k, v in resultado.items() if k != "itens"}, ensure_ascii=False, indent=2)[:800])
    print(
        "\nNada foi publicado. Para os alunos verem, aprove em Admin > Rascunhos no portal,"
        "\nou peça ao Claude pela tool publicar_rascunho."
    )
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
