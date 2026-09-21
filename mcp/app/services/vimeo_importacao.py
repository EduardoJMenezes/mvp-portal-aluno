"""O que o portal ainda faz com uma pasta do Vimeo: avaliar e aplicar.

Ler a pasta e montar o plano não toca no banco, e por isso mudou de endereço:
mora em `app/integracoes/vimeo/importacao.py`, do lado do adaptador MCP, que é
quem fala com o Vimeo. O que sobrou aqui é o que grava — e gravar, no portal,
ainda é SQLAlchemy.

O MCP não passa mais por estas duas funções: as tools dele chamam a API em
Java (`_avaliar_plano` e `_aplicar_plano` em `mcp_server/tools.py`). Enquanto o
portal não for portado, as duas implementações convivem — e é de propósito que
elas partem do **mesmo** plano, produzido pelo mesmo código.
"""

from __future__ import annotations

from sqlalchemy import select
from sqlalchemy.orm import Session

from app.errors import RegraDeNegocio
from app.identidade import Identidade
# Reexportados porque `admin_routes` chama tudo por este nome: ler a pasta e
# montar o plano continua sendo a mesma função, só que noutro endereço.
from app.integracoes.vimeo.importacao import (  # noqa: F401
    ItemDoPlano,
    PlanoDeImportacao,
    ler_plano,
    listar_pastas,
    questoes_com_resolucao,
    resolucao,
    resolucoes_por_numero,
)
from app.integracoes.vimeo.importacao import distribuir as _distribuir
from app.models import Item, Video
from app.services import estrutura, rascunhos
from app.services.catalogo import resolver_turma
from app.services.consultas import selecionar


def avaliar(
    db: Session,
    ident: Identidade,
    plano: PlanoDeImportacao,
    turma: str | int,
    destinos: list[dict],
) -> dict:
    """O que aconteceria se importássemos. Não grava nada (seção 53)."""
    ident.exigir_operador()
    alvo_turma = resolver_turma(db, turma)
    distribuicao, sem_destino = _distribuir(plano, destinos)

    ids = [item.vimeo_id for item in plano.itens if item.vimeo_id]
    ja_no_acervo = set()
    if ids:
        ja_no_acervo = set(db.scalars(select(Video.vimeo_id).where(Video.vimeo_id.in_(ids))).all())

    saida = []
    for grupo in distribuicao:
        destino = grupo["destino"]
        alvo_modulo = estrutura.resolver_modulo(db, alvo_turma, destino["modulo"])
        alvo_sub = estrutura.resolver_submodulo(db, alvo_modulo, destino["submodulo"])

        ja_no_submodulo = set(
            db.scalars(
                selecionar(Video.vimeo_id)
                .select_from(Item)
                .join(Video, Video.id == Item.video_id)
                .where(Item.submodulo_id == alvo_sub.id)
            ).all()
        )

        saida.append(
            {
                "modulo": alvo_modulo.nome,
                "submodulo": alvo_sub.nome,
                "faixa": destino.get("faixa") or "(o que sobrar)",
                "assunto": destino.get("assunto"),
                "subassunto": destino.get("subassunto"),
                "itens_que_serao_criados": len(
                    [i for i in grupo["itens"] if i.vimeo_id not in ja_no_submodulo]
                ),
                "ja_neste_submodulo": sorted(
                    i.vimeo_id for i in grupo["itens"] if i.vimeo_id in ja_no_submodulo
                ),
                "numeros_da_faixa_sem_video": grupo["nao_encontrados"],
                "itens": [i.resumo() for i in grupo["itens"]],
            }
        )

    return {
        "pasta": {"id": plano.pasta_id, "nome": plano.pasta_nome},
        "turma": alvo_turma.nome,
        "videos_na_pasta": len(plano.itens),
        "videos_ja_no_acervo": sorted(ja_no_acervo),
        "destinos": saida,
        "sem_destino": [i.resumo() for i in sem_destino],
        "observacao": (
            "Nada foi gravado. Confirme com o professor e chame "
            "importar_pasta_vimeo_como_rascunho para criar o rascunho."
            + (
                f" Atenção: {len(sem_destino)} vídeo(s) ficaram sem destino — diga a faixa "
                "deles ou informe um destino sem faixa."
                if sem_destino
                else ""
            )
        ),
    }


def aplicar(
    db: Session,
    ident: Identidade,
    plano: PlanoDeImportacao,
    turma: str | int,
    destinos: list[dict],
) -> dict:
    """Grava o plano como RASCUNHO. Continua sem publicar nada.

    Um rascunho por destino: cada sub-módulo é um lote de aprovação próprio,
    e o professor pode liberar o K01 e segurar o K02 sem depender de ter
    importado em chamadas separadas.
    """
    ident.exigir_operador()
    if not plano.itens:
        raise RegraDeNegocio(f"A pasta {plano.pasta_id} não tem vídeos para importar.")

    alvo_turma = resolver_turma(db, turma)
    distribuicao, sem_destino = _distribuir(plano, destinos)

    criados = []
    for grupo in distribuicao:
        if not grupo["itens"]:
            continue
        destino = grupo["destino"]
        detalhe = rascunhos.importar_videos_como_itens(
            db,
            ident,
            alvo_turma.id,
            destino["modulo"],
            destino["submodulo"],
            [
                item.para_importacao(destino.get("assunto"), destino.get("subassunto"))
                for item in grupo["itens"]
            ],
        )
        criados.append(detalhe)

    if not criados:
        raise RegraDeNegocio(
            "Nenhum vídeo casou com os destinos informados. Confira as faixas contra os "
            "números lidos dos títulos."
        )

    return {
        "pasta_vimeo": {"id": plano.pasta_id, "nome": plano.pasta_nome},
        "turma": alvo_turma.nome,
        "rascunhos": criados,
        "sem_destino": [i.resumo() for i in sem_destino],
        "aviso": (
            "Nada foi publicado. Cada rascunho precisa da aprovação do professor."
            + (
                f" {len(sem_destino)} vídeo(s) ficaram de fora por não casarem com nenhuma faixa."
                if sem_destino
                else ""
            )
        ),
    }
