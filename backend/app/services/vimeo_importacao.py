"""Importação de uma pasta do Vimeo como capítulo da plataforma.

Três passos, na ordem: ler a pasta no Vimeo, montar o plano (número inferido do
título, conflitos e avisos) e, só se o professor mandar, gravar como rascunho.

Nada é publicado aqui. Publicar continua em `services/publicacao.py`, com
aprovação humana gravada — a integração com o Vimeo não abre exceção.

A leitura usa `app/integracoes/vimeo`, cuja allowlist só deixa passar GET e
HEAD: por construção, nenhuma importação altera coisa alguma no Vimeo.
"""

from __future__ import annotations

from contextlib import asynccontextmanager
from dataclasses import dataclass, field

from sqlalchemy import select
from sqlalchemy.orm import Session

from app.config import get_settings
from app.errors import RegraDeNegocio
from app.identidade import Identidade
from app.integracoes.vimeo import (
    CAMPOS_VIDEO_IMPORTACAO,
    ClienteVimeoLeitura,
    TransporteVimeo,
    VideoVimeo,
)
from app.models import Item, SubModulo, Video
from app.services import estrutura, rascunhos
from app.services.catalogo import resolver_turma
from app.services.consultas import selecionar
from app.services.nomes_vimeo import inferir_numero, interpretar_faixa


@dataclass
class ItemDoPlano:
    """Um vídeo do Vimeo, já com o número que ele teria na plataforma."""

    vimeo_id: str
    titulo: str
    numero: int | None
    confianca: str
    duracao_segundos: int | None
    url: str | None
    embed_url: str | None
    thumbnail_url: str | None
    publicavel: bool
    privacidade: str
    transcricao: str | None
    avisos: list[str] = field(default_factory=list)

    def para_importacao(self, assunto: str | None = None, subassunto: str | None = None) -> dict:
        """O formato que `rascunhos.importar_videos_como_itens` espera.

        O `nome` do item nasce do título do Vimeo. Antes daqui saía um
        enunciado sintético ("Questão 4 da apostila — resolução em vídeo"),
        porque o vídeo precisava virar uma `Questao` para caber no modelo: a
        questão da apostila mora na apostila, e o que a plataforma guarda é a
        resolução em vídeo.
        """
        return {
            "vimeo_id": self.vimeo_id,
            "titulo": self.titulo,
            "url": self.url,
            "embed_url": self.embed_url,
            "thumbnail_url": self.thumbnail_url,
            "duracao_segundos": self.duracao_segundos,
            "nome": self.titulo,
            "assunto": assunto,
            "subassunto": subassunto,
        }

    def resumo(self) -> dict:
        return {
            "numero": self.numero,
            "titulo": self.titulo,
            "vimeo_id": self.vimeo_id,
            "duracao_segundos": self.duracao_segundos,
            "confianca_do_numero": self.confianca,
            "avisos": self.avisos,
        }


@dataclass
class PlanoDeImportacao:
    pasta_id: str
    pasta_nome: str | None
    itens: list[ItemDoPlano]


@asynccontextmanager
async def abrir_leitura():
    """Cliente de leitura do Vimeo, criado a partir do .env e fechado no fim."""
    s = get_settings()
    if not s.vimeo_real:
        raise RegraDeNegocio(
            "O acervo real do Vimeo não está configurado: falta VIMEO_ACCESS_TOKEN no ambiente."
        )
    transporte = TransporteVimeo(s.vimeo_access_token, agente="mvp-portal-aluno/0.1")
    try:
        yield ClienteVimeoLeitura(transporte)
    finally:
        await transporte.aclose()


def _avisos_do_video(video: VideoVimeo, numero: int | None) -> list[str]:
    avisos = []
    if numero is None:
        avisos.append("não consegui ler o número da questão no título")
    if not video.publicavel:
        avisos.append(f"ainda não está pronto no Vimeo (status {video.status})")
    if video.privacidade_embed == "private":
        avisos.append("o embed está desativado no Vimeo; o aluno não conseguiria assistir")
    if video.privacidade_embed == "whitelist":
        avisos.append("o embed é restrito a domínios: confirme que o domínio do portal está liberado")
    return avisos


async def ler_plano(pasta_id: str) -> PlanoDeImportacao:
    """Lê a pasta no Vimeo e ordena pelo número do título (a API devolve fora de ordem)."""
    async with abrir_leitura() as leitura:
        pasta = await leitura.obter_pasta(pasta_id)
        videos = await leitura.listar_videos_da_pasta(pasta_id, campos=CAMPOS_VIDEO_IMPORTACAO)

    itens = []
    for video in videos:
        inferido = inferir_numero(video.nome)
        itens.append(
            ItemDoPlano(
                vimeo_id=video.id or "",
                titulo=video.nome or "(sem título)",
                numero=inferido.numero,
                confianca=inferido.confianca,
                duracao_segundos=video.duracao_segundos,
                url=video.link,
                embed_url=video.embed_url,
                thumbnail_url=video.thumbnail_url,
                publicavel=video.publicavel,
                privacidade=f"{video.privacidade_view}/{video.privacidade_embed}",
                transcricao=video.transcricao_status,
                avisos=_avisos_do_video(video, inferido.numero),
            )
        )

    itens.sort(key=lambda item: (item.numero is None, item.numero or 0, item.titulo))
    return PlanoDeImportacao(pasta_id=str(pasta_id), pasta_nome=pasta.nome, itens=itens)


def _distribuir(
    plano: PlanoDeImportacao, destinos: list[dict]
) -> tuple[list[dict], list[ItemDoPlano]]:
    """Casa cada vídeo com o destino cuja faixa contém o número dele.

    É o gesto que o professor faz em voz alta: "da 1 até a 14 é o K01, sub
    Questões da apostila; 15, 18, 22 e 25 são o K02". A faixa fala dos números
    da apostila, lidos do título — não da posição na lista.

    Um destino sem faixa recolhe o que sobrou, inclusive os vídeos cujo título
    não trouxe número legível. Sem nenhum destino assim, esses vídeos ficam de
    fora e a tool pergunta em vez de chutar.
    """
    if not destinos:
        raise RegraDeNegocio(
            "Informe ao menos um destino, ex.: faixa '1-14' para o módulo 'K01 - ...' "
            "e sub-módulo 'Questões da apostila'."
        )

    coringas = [d for d in destinos if not str(d.get("faixa") or "").strip()]
    if len(coringas) > 1:
        raise RegraDeNegocio("Só um destino pode ficar sem faixa — ele recolhe o que sobrar.")

    usados: set[str] = set()
    distribuicao: list[dict] = []

    for destino in destinos:
        texto = str(destino.get("faixa") or "").strip()
        if not texto:
            continue
        try:
            numeros = interpretar_faixa(texto)
        except ValueError as e:
            raise RegraDeNegocio(str(e)) from None

        escolhidos = [
            item
            for item in plano.itens
            if item.numero in numeros and item.vimeo_id not in usados
        ]
        usados.update(item.vimeo_id for item in escolhidos)
        faltando = sorted(numeros - {item.numero for item in escolhidos if item.numero})
        distribuicao.append({"destino": destino, "itens": escolhidos, "nao_encontrados": faltando})

    sobraram = [item for item in plano.itens if item.vimeo_id not in usados]
    if coringas:
        distribuicao.append({"destino": coringas[0], "itens": sobraram, "nao_encontrados": []})
        sobraram = []

    return distribuicao, sobraram


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
