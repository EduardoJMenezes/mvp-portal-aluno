"""Remoção lógica: o filtro e o rastro, num lugar só.

Nada é apagado no banco (ver `models.Rastreavel`). Remover é preencher
`removido_em`, o que traz uma consequência que morde em silêncio: **toda**
consulta precisa excluir o que foi removido, e esquecer em um único lugar faz
conteúdo removido reaparecer para o aluno.

Por isso o filtro não se escreve à mão. Quem consulta usa `selecionar()`; quem
remove usa `remover()`. Se um dia um `select()` cru aparecer num service de
conteúdo, é bug — não estilo.
"""

from __future__ import annotations

from datetime import UTC, datetime
from typing import Any

from sqlalchemy import Select, select
from sqlalchemy.orm import Session

from app.identidade import Identidade


def _dono(entidade: Any) -> Any | None:
    """A classe removível por trás de uma entidade **ou de uma coluna dela**.

    `selecionar(Item)` e `selecionar(Item.video_id)` precisam filtrar igual —
    e só a classe tem `removido_em`, a coluna não. Sem esta tradução, pedir
    uma coluna desligaria o filtro em silêncio, que é exatamente o bug que
    este módulo existe para tornar impossível.
    """
    if hasattr(entidade, "removido_em"):
        return entidade
    classe = getattr(getattr(entidade, "parent", None), "class_", None)
    if classe is not None and hasattr(classe, "removido_em"):
        return classe
    return None


def selecionar(*entidades: Any) -> Select:
    """`select()` que já exclui o removido de cada entidade envolvida.

    Funciona para a classe e para colunas dela. **Não** alcança expressões
    como `func.count(Item.id)`, onde a entidade se perde: nesses casos aplique
    `vivos(stmt, Item)` na mão.
    """
    stmt = select(*entidades)
    for entidade in entidades:
        dono = _dono(entidade)
        if dono is not None:
            stmt = stmt.where(dono.removido_em.is_(None))
    return stmt


def vivos(stmt: Select, *entidades: Any) -> Select:
    """Mesmo filtro, para entidades que entram depois — num join, por exemplo.

        stmt = selecionar(Item).join(SubModulo).join(Modulo)
        stmt = vivos(stmt, SubModulo, Modulo)
    """
    for entidade in entidades:
        dono = _dono(entidade)
        if dono is not None:
            stmt = stmt.where(dono.removido_em.is_(None))
    return stmt


def tocar(ident: Identidade, *objetos: Any) -> None:
    """Carimba quem alterou e quando.

    Editar não passa por rascunho, então é este carimbo que responde "quem
    mexeu nisso?" — no lugar da tabela de auditoria que a §20 deixou fora.
    """
    agora = datetime.now(UTC)
    for objeto in objetos:
        objeto.alterado_por_id = ident.usuario_id
        objeto.alterado_em = agora


def remover(db: Session, ident: Identidade, *objetos: Any) -> int:
    """Remoção lógica, com rastro. Não emite DELETE.

    Devolve quantos foram efetivamente removidos agora (o que já estava
    removido não conta, para a operação ser idempotente).
    """
    agora = datetime.now(UTC)
    removidos = 0
    for objeto in objetos:
        if objeto.removido_em is None:
            objeto.removido_em = agora
            objeto.alterado_por_id = ident.usuario_id
            objeto.alterado_em = agora
            removidos += 1
    db.flush()
    return removidos


def restaurar(db: Session, ident: Identidade, *objetos: Any) -> int:
    """O outro lado da remoção lógica — e a razão de ela existir.

    Um `DELETE` mal pedido pela IA seria uma tarde perdida; aqui é um desfazer.
    """
    restaurados = 0
    for objeto in objetos:
        if objeto.removido_em is not None:
            objeto.removido_em = None
            restaurados += 1
    tocar(ident, *objetos)
    db.flush()
    return restaurados
