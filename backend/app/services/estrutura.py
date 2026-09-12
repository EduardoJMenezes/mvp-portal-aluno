"""Módulo, sub-módulo e item: montar e manter a árvore do curso.

Criar conteúdo continua nascendo como rascunho (ver `rascunhos.py`). **Editar e
remover são diretos** — rascunho de edição exigiria guardar o "antes" e o
"depois" e resolver conflito entre dois rascunhos tocando o mesmo módulo, e
isso não se paga para trocar duas aulas de lugar.

O que compensa a falta do rascunho:

* a tool do MCP sempre confirma com o professor antes de aplicar;
* `alterado_por_id`/`alterado_em` guardam quem mexeu;
* remover nunca apaga — `removido_em`, e pronto.

**Remoção não cascateia de propósito.** Remover um módulo marca só o módulo;
os sub-módulos e itens ficam intactos, e inalcançáveis, porque toda consulta
parte da raiz e filtra o removido. Restaurar o módulo traz a subárvore de
volta exatamente como estava — o que uma cascata tornaria impossível.
"""

from __future__ import annotations

from sqlalchemy import func
from sqlalchemy.orm import Session

from app.errors import NaoEncontrado, RegraDeNegocio
from app.identidade import Identidade
from app.models import Item, Modulo, Status, SubModulo, TipoSubModulo, Turma, Video
from app.services.consultas import remover, selecionar, tocar, vivos

# --- resolução de referências ------------------------------------------------
#
# Quem chama pelo MCP fala "K01 - Introdução à química orgânica", não
# `modulo_id=7`. Quando não acha, o erro lista o que existe: o agente se
# corrige sozinho em vez de insistir num id inventado.


def _por_nome(candidatos: list, referencia: str | int, rotulo: str, onde: str):
    if isinstance(referencia, int) or str(referencia).strip().isdigit():
        alvo = [c for c in candidatos if c.id == int(referencia)]
        if alvo:
            return alvo[0]

    texto = str(referencia).strip().lower()
    exatas = [c for c in candidatos if c.nome.lower() == texto]
    if exatas:
        return exatas[0]

    parciais = [c for c in candidatos if texto in c.nome.lower()]
    if len(parciais) == 1:
        return parciais[0]

    nomes = ", ".join(f"'{c.nome}'" for c in candidatos) or "nenhum cadastrado"
    if len(parciais) > 1:
        ambiguos = ", ".join(f"'{c.nome}'" for c in parciais)
        raise RegraDeNegocio(
            f"'{referencia}' casa com mais de um {rotulo} em {onde}: {ambiguos}. "
            "Diga qual deles."
        )
    raise NaoEncontrado(f"{rotulo.capitalize()} '{referencia}' não existe em {onde}. Há: {nomes}.")


def resolver_modulo(db: Session, turma: Turma, referencia: str | int) -> Modulo:
    modulos = list(
        db.scalars(selecionar(Modulo).where(Modulo.turma_id == turma.id).order_by(Modulo.ordem))
    )
    return _por_nome(modulos, referencia, "módulo", f"'{turma.nome}'")


def resolver_submodulo(db: Session, modulo: Modulo, referencia: str | int) -> SubModulo:
    submodulos = list(
        db.scalars(
            selecionar(SubModulo)
            .where(SubModulo.modulo_id == modulo.id)
            .order_by(SubModulo.ordem)
        )
    )
    return _por_nome(submodulos, referencia, "sub-módulo", f"'{modulo.nome}'")


def resolver_item(db: Session, submodulo: SubModulo, referencia: str | int) -> Item:
    itens = list(
        db.scalars(
            selecionar(Item).where(Item.submodulo_id == submodulo.id).order_by(Item.ordem)
        )
    )
    return _por_nome(itens, referencia, "item", f"'{submodulo.nome}'")


# --- leitura -----------------------------------------------------------------


def _proxima_ordem(db: Session, entidade, coluna, valor: int) -> int:
    atual = db.scalar(
        selecionar(func.max(entidade.ordem)).where(coluna == valor)  # type: ignore[arg-type]
    )
    return int(atual or 0) + 1


def arvore_da_turma(db: Session, turma: Turma, apenas_publicados: bool = False) -> list[dict]:
    """Módulos › sub-módulos › itens de uma turma.

    `apenas_publicados` é o que a tela do aluno pede: módulo e sub-módulo não
    têm status próprio, então eles aparecem quando sobra item publicado dentro
    — e somem quando não sobra.
    """
    modulos = list(
        db.scalars(selecionar(Modulo).where(Modulo.turma_id == turma.id).order_by(Modulo.ordem))
    )

    arvore = []
    for modulo in modulos:
        submodulos = []
        for sub in db.scalars(
            selecionar(SubModulo)
            .where(SubModulo.modulo_id == modulo.id)
            .order_by(SubModulo.ordem)
        ):
            stmt = selecionar(Item).where(Item.submodulo_id == sub.id).order_by(Item.ordem)
            if apenas_publicados:
                stmt = stmt.where(Item.status == Status.PUBLICADO)
            itens = list(db.scalars(stmt))

            if apenas_publicados and not itens:
                continue

            submodulos.append(
                {
                    "id": sub.id,
                    "nome": sub.nome,
                    "tipo": sub.tipo,
                    "ordem": sub.ordem,
                    "itens": [
                        {
                            "id": i.id,
                            "nome": i.nome,
                            "ordem": i.ordem,
                            "status": i.status,
                            "video_id": i.video_id,
                        }
                        for i in itens
                    ],
                }
            )

        if apenas_publicados and not submodulos:
            continue

        arvore.append(
            {
                "id": modulo.id,
                "nome": modulo.nome,
                "ordem": modulo.ordem,
                "turma": turma.nome,
                "submodulos": submodulos,
            }
        )

    return arvore


# --- módulo ------------------------------------------------------------------


def criar_modulo(
    db: Session, ident: Identidade, turma: Turma, nome: str, ordem: int | None = None
) -> Modulo:
    ident.exigir_operador()
    nome = (nome or "").strip()
    if not nome:
        raise RegraDeNegocio("O módulo precisa de um nome, ex.: 'K01 - Introdução à química orgânica'.")

    ja_existe = db.scalar(
        selecionar(Modulo).where(Modulo.turma_id == turma.id, func.lower(Modulo.nome) == nome.lower())
    )
    if ja_existe is not None:
        raise RegraDeNegocio(f"'{turma.nome}' já tem um módulo chamado '{ja_existe.nome}'.")

    modulo = Modulo(
        turma_id=turma.id,
        nome=nome,
        ordem=ordem if ordem is not None else _proxima_ordem(db, Modulo, Modulo.turma_id, turma.id),
    )
    tocar(ident, modulo)
    db.add(modulo)
    db.flush()
    return modulo


def editar_modulo(
    db: Session,
    ident: Identidade,
    modulo: Modulo,
    nome: str | None = None,
    ordem: int | None = None,
) -> Modulo:
    ident.exigir_operador()
    if nome is not None:
        nome = nome.strip()
        if not nome:
            raise RegraDeNegocio("O nome do módulo não pode ficar vazio.")
        modulo.nome = nome
    if ordem is not None:
        modulo.ordem = ordem
    tocar(ident, modulo)
    db.flush()
    return modulo


def remover_modulo(db: Session, ident: Identidade, modulo: Modulo) -> dict:
    ident.exigir_operador()
    publicados = _itens_publicados_do_modulo(db, modulo)
    remover(db, ident, modulo)
    return {
        "modulo": modulo.nome,
        "turma": modulo.turma.nome,
        "itens_publicados_que_somem_da_tela": publicados,
        "reversivel": True,
    }


def _itens_publicados_do_modulo(db: Session, modulo: Modulo) -> int:
    stmt = (
        selecionar(func.count(Item.id))
        .select_from(Item)
        .join(SubModulo, SubModulo.id == Item.submodulo_id)
        .where(SubModulo.modulo_id == modulo.id, Item.status == Status.PUBLICADO)
    )
    return int(db.scalar(vivos(stmt, Item, SubModulo)) or 0)


# --- sub-módulo --------------------------------------------------------------


def criar_submodulo(
    db: Session,
    ident: Identidade,
    modulo: Modulo,
    nome: str,
    tipo: str = TipoSubModulo.VIDEO,
    ordem: int | None = None,
) -> SubModulo:
    ident.exigir_operador()
    nome = (nome or "").strip()
    if not nome:
        raise RegraDeNegocio("O sub-módulo precisa de um nome, ex.: 'Aulas' ou 'Questões da apostila'.")
    if tipo not in TipoSubModulo.TODOS:
        raise RegraDeNegocio(
            f"Tipo '{tipo}' não existe. Tipos: {', '.join(TipoSubModulo.TODOS)}."
        )

    ja_existe = db.scalar(
        selecionar(SubModulo).where(
            SubModulo.modulo_id == modulo.id, func.lower(SubModulo.nome) == nome.lower()
        )
    )
    if ja_existe is not None:
        raise RegraDeNegocio(f"'{modulo.nome}' já tem um sub-módulo chamado '{ja_existe.nome}'.")

    sub = SubModulo(
        modulo_id=modulo.id,
        nome=nome,
        tipo=tipo,
        ordem=ordem
        if ordem is not None
        else _proxima_ordem(db, SubModulo, SubModulo.modulo_id, modulo.id),
    )
    tocar(ident, sub)
    db.add(sub)
    db.flush()
    return sub


def editar_submodulo(
    db: Session,
    ident: Identidade,
    submodulo: SubModulo,
    nome: str | None = None,
    ordem: int | None = None,
) -> SubModulo:
    ident.exigir_operador()
    if nome is not None:
        nome = nome.strip()
        if not nome:
            raise RegraDeNegocio("O nome do sub-módulo não pode ficar vazio.")
        submodulo.nome = nome
    if ordem is not None:
        submodulo.ordem = ordem
    tocar(ident, submodulo)
    db.flush()
    return submodulo


def remover_submodulo(db: Session, ident: Identidade, submodulo: SubModulo) -> dict:
    ident.exigir_operador()
    publicados = int(
        db.scalar(
            vivos(
                selecionar(func.count(Item.id))
                .select_from(Item)
                .where(Item.submodulo_id == submodulo.id, Item.status == Status.PUBLICADO),
                Item,
            )
        )
        or 0
    )
    remover(db, ident, submodulo)
    return {
        "submodulo": submodulo.nome,
        "modulo": submodulo.modulo.nome,
        "itens_publicados_que_somem_da_tela": publicados,
        "reversivel": True,
    }


# --- item --------------------------------------------------------------------


def criar_item(
    db: Session,
    ident: Identidade,
    submodulo: SubModulo,
    video: Video,
    nome: str | None = None,
    ordem: int | None = None,
    status: str = Status.RASCUNHO,
    rascunho_id: int | None = None,
) -> Item:
    ident.exigir_operador()

    repetido = db.scalar(
        selecionar(Item).where(Item.submodulo_id == submodulo.id, Item.video_id == video.id)
    )
    if repetido is not None:
        raise RegraDeNegocio(
            f"'{submodulo.nome}' já tem este vídeo, como '{repetido.nome}'."
        )

    item = Item(
        submodulo_id=submodulo.id,
        video_id=video.id,
        # Sem nome explícito, vale o título do Vimeo — é o que o professor
        # reconhece, e ele edita depois se quiser.
        nome=(nome or video.titulo or "").strip() or video.titulo,
        ordem=ordem
        if ordem is not None
        else _proxima_ordem(db, Item, Item.submodulo_id, submodulo.id),
        status=status,
        rascunho_id=rascunho_id,
    )
    tocar(ident, item)
    db.add(item)
    db.flush()
    return item


def editar_item(
    db: Session,
    ident: Identidade,
    item: Item,
    nome: str | None = None,
    ordem: int | None = None,
) -> Item:
    ident.exigir_operador()
    if nome is not None:
        nome = nome.strip()
        if not nome:
            raise RegraDeNegocio("O nome do item não pode ficar vazio.")
        item.nome = nome
    if ordem is not None:
        item.ordem = ordem
    tocar(ident, item)
    db.flush()
    return item


def mover_item(db: Session, ident: Identidade, item: Item, destino: SubModulo) -> Item:
    """Tira o item de um sub-módulo e põe em outro, no fim da lista."""
    ident.exigir_operador()
    if destino.id == item.submodulo_id:
        return item

    repetido = db.scalar(
        selecionar(Item).where(Item.submodulo_id == destino.id, Item.video_id == item.video_id)
    )
    if repetido is not None:
        raise RegraDeNegocio(f"'{destino.nome}' já tem este vídeo, como '{repetido.nome}'.")

    item.submodulo_id = destino.id
    item.ordem = _proxima_ordem(db, Item, Item.submodulo_id, destino.id)
    tocar(ident, item)
    db.flush()
    return item


def remover_item(db: Session, ident: Identidade, item: Item) -> dict:
    ident.exigir_operador()
    estava_publicado = item.status == Status.PUBLICADO
    remover(db, ident, item)
    return {
        "item": item.nome,
        "submodulo": item.submodulo.nome,
        "sumiu_da_tela_do_aluno": estava_publicado,
        "reversivel": True,
    }


def reordenar(db: Session, ident: Identidade, itens_em_ordem: list[Item]) -> list[Item]:
    """Recebe os itens na ordem desejada e regrava a posição de cada um."""
    ident.exigir_operador()
    for posicao, item in enumerate(itens_em_ordem, start=1):
        item.ordem = posicao
    tocar(ident, *itens_em_ordem)
    db.flush()
    return itens_em_ordem
