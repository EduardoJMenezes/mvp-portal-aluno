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
from app.models import Capitulo, Questao, TurmaQuestao, Video
from app.services import rascunhos
from app.services.catalogo import resolver_turma
from app.services.nomes_vimeo import inferir_numero


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

    def para_importacao(self) -> dict:
        """O formato que `rascunhos.importar_questoes_vimeo` espera."""
        return {
            "vimeo_id": self.vimeo_id,
            "titulo": self.titulo,
            "url": self.url,
            "embed_url": self.embed_url,
            "thumbnail_url": self.thumbnail_url,
            "duracao_segundos": self.duracao_segundos,
            "numero": self.numero,
            "enunciado": (
                f"Questão {self.numero} da apostila — resolução em vídeo"
                if self.numero
                else f"Resolução em vídeo — {self.titulo}"
            ),
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


def avaliar(db: Session, ident: Identidade, plano: PlanoDeImportacao, turma: str | int, capitulo: str) -> dict:
    """O que aconteceria se importássemos. Não grava nada (seção 53)."""
    ident.exigir_operador()
    alvo_turma = resolver_turma(db, turma)
    nome_capitulo = str(capitulo).strip()
    if not nome_capitulo:
        raise RegraDeNegocio("Informe o nome do capítulo que vai receber os vídeos.")

    existente = db.scalar(select(Capitulo).where(Capitulo.nome == nome_capitulo))
    numeros_ocupados: dict[int, str] = {}
    if existente is not None:
        for numero, titulo in db.execute(
            select(TurmaQuestao.numero, Video.titulo)
            .join(Questao, Questao.id == TurmaQuestao.questao_id)
            .outerjoin(Video, Video.id == Questao.video_id)
            .where(TurmaQuestao.turma_id == alvo_turma.id, TurmaQuestao.capitulo_id == existente.id)
        ):
            numeros_ocupados[numero] = titulo or ""

    ids = [item.vimeo_id for item in plano.itens if item.vimeo_id]
    ja_no_acervo = set()
    if ids:
        ja_no_acervo = set(db.scalars(select(Video.vimeo_id).where(Video.vimeo_id.in_(ids))).all())

    conflitos: list[str] = []
    vistos: dict[int, str] = {}
    for item in plano.itens:
        if item.numero is None:
            continue
        if item.numero in vistos:
            conflitos.append(
                f"número {item.numero} aparece em dois vídeos: {vistos[item.numero]} e {item.titulo}"
            )
        vistos[item.numero] = item.titulo
        if item.numero in numeros_ocupados:
            conflitos.append(
                f"número {item.numero} já existe neste capítulo da turma "
                f"({numeros_ocupados[item.numero] or 'questão sem vídeo'})"
            )

    return {
        "pasta": {"id": plano.pasta_id, "nome": plano.pasta_nome},
        "turma": alvo_turma.nome,
        "capitulo": {"nome": nome_capitulo, "ja_existe": existente is not None},
        "videos_na_pasta": len(plano.itens),
        "questoes_que_serao_criadas": len(plano.itens),
        "videos_ja_no_acervo": sorted(ja_no_acervo),
        "conflitos": conflitos,
        "questoes": [item.resumo() for item in plano.itens],
        "observacao": (
            "Nada foi gravado. Confirme com o professor e chame "
            "importar_pasta_vimeo_como_rascunho para criar o rascunho."
        ),
    }


def aplicar(db: Session, ident: Identidade, plano: PlanoDeImportacao, turma: str | int, capitulo: str) -> dict:
    """Grava o plano como RASCUNHO. Continua sem publicar nada."""
    ident.exigir_operador()
    if not plano.itens:
        raise RegraDeNegocio(f"A pasta {plano.pasta_id} não tem vídeos para importar.")

    alvo_turma = resolver_turma(db, turma)
    nome_capitulo = str(capitulo).strip()
    alvo_capitulo = db.scalar(select(Capitulo).where(Capitulo.nome == nome_capitulo))
    capitulo_criado = alvo_capitulo is None
    if alvo_capitulo is None:
        alvo_capitulo = Capitulo(nome=nome_capitulo)
        db.add(alvo_capitulo)
        db.flush()

    detalhe = rascunhos.importar_questoes_vimeo(
        db,
        ident,
        alvo_turma.id,
        alvo_capitulo.id,
        [item.para_importacao() for item in plano.itens],
    )
    detalhe["capitulo_criado"] = capitulo_criado
    detalhe["pasta_vimeo"] = {"id": plano.pasta_id, "nome": plano.pasta_nome}
    return detalhe
