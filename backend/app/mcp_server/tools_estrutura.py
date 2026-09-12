"""Tools que montam e mantêm o curso: módulo, sub-módulo, item e assunto.

Separadas de `tools.py` por peso, não por natureza — são as mesmas regras e os
mesmos services. O que as distingue é que **todas alteram direto**: não há
rascunho entre a decisão e o efeito.

Por isso, toda tool daqui que muda alguma coisa mostra o que vai mudar e
espera o professor confirmar, por `confirmacao.py`. Cliente que não sabe
confirmar recebe recusa, não a alteração aplicada.

Remover nunca apaga: preenche `removido_em`, e a tool diz na resposta que é
reversível.
"""

from __future__ import annotations

from typing import Annotated

from fastmcp import Context
from fastmcp.exceptions import ToolError
from mcp_types import InputRequiredResult
from pydantic import Field

from app.errors import ErroDominio
from app.mcp_server import confirmacao
from app.mcp_server.auth import identidade_da_sessao
from app.mcp_server.server import mcp
from app.mcp_server.tools import ESCREVE_RASCUNHO, SOMENTE_LEITURA, _em_thread, _sessao
from app.models import TipoSubModulo
from app.services import catalogo, estrutura, taxonomia

ALTERA = {"read_only_hint": False, "destructive_hint": False, "open_world_hint": False}
REMOVE = {"read_only_hint": False, "destructive_hint": True, "open_world_hint": False}


async def _confirmando(
    ctx: Context, resumo, aplicar, titulo: str, descricao: str, estado: str = "-"
):
    """O fluxo de toda alteração: mostrar, perguntar, e só então aplicar."""
    ja_respondeu = confirmacao.resposta_do_usuario(ctx)
    if ja_respondeu is False:
        return confirmacao.recusa(titulo)
    if ja_respondeu is True:
        return await _em_thread(aplicar)

    try:
        mensagem = await _em_thread(resumo)
    except ErroDominio as e:
        raise ToolError(str(e)) from e

    decisao = await confirmacao.perguntar(ctx, mensagem, estado, titulo, descricao)
    if isinstance(decisao, InputRequiredResult):
        return decisao
    if not decisao:
        return confirmacao.recusa(titulo)
    return await _em_thread(aplicar)


# --- leitura -----------------------------------------------------------------


@mcp.tool(name="listar_modulos", annotations=SOMENTE_LEITURA)
def listar_modulos(
    turma: Annotated[
        str | None, Field(description="Nome ou id da turma, ex.: 'Extensivo 2026'")
    ] = None,
) -> list[dict]:
    """Mostra a árvore do curso: módulos, sub-módulos e itens de cada turma.

    O módulo é o capítulo como aquela turma o numera ("K01 - Introdução à
    química orgânica"); dentro dele, os sub-módulos ("Aulas", "Questões da
    apostila") e os vídeos de cada um, com o status de publicação.

    É daqui que saem os nomes que as outras tools pedem.
    """
    with _sessao() as (db, ident):
        return catalogo.listar_modulos(db, ident, turma)


@mcp.tool(name="listar_assuntos", annotations=SOMENTE_LEITURA)
def listar_assuntos() -> list[dict]:
    """Lista a taxonomia: assuntos e seus sub-assuntos.

    Assunto é a etiqueta do conteúdo ("Estequiometria" › "Pureza e
    rendimento"), e **não** leva o número do capítulo: K03 é Estequiometria em
    2026 e Tabela Periódica em 2025, então uma etiqueta com K03 no nome não
    serviria para as duas.

    É por esta etiqueta que a plataforma liga o erro do aluno no simulado aos
    vídeos que explicam aquilo.
    """
    with _sessao() as (db, _ident):
        return taxonomia.listar_assuntos(db)


# --- estrutura do curso ------------------------------------------------------


@mcp.tool(name="criar_modulo", annotations=ALTERA)
async def criar_modulo(
    turma: Annotated[str, Field(description="Nome ou id da turma")],
    nome: Annotated[str, Field(description="Ex.: 'K01 - Introdução à química orgânica'")],
    submodulos: Annotated[
        list[str] | None,
        Field(description="Sub-módulos a criar junto, ex.: ['Aulas', 'Questões da apostila']"),
    ] = None,
    ctx: Context = None,  # type: ignore[assignment]
) -> dict:
    """Cria um módulo (capítulo) na turma, com os sub-módulos que ele terá.

    O módulo pertence à turma porque a numeração é da apostila dela. Criar já
    com os sub-módulos poupa uma rodada — o normal é 'Aulas' e 'Questões da
    apostila'.

    Módulo novo nasce vazio: nada aparece para o aluno até haver item
    publicado dentro.
    """
    ident = identidade_da_sessao()
    nomes_sub = submodulos or ["Aulas", "Questões da apostila"]

    def resumo() -> str:
        with _sessao() as (db, _i):
            alvo = catalogo.resolver_turma(db, turma)
            linhas = [f"Criar o módulo '{nome}' em {alvo.nome}, com:"]
            linhas += [f"  • {s}" for s in nomes_sub]
            return "\n".join(linhas)

    def aplicar() -> dict:
        with _sessao() as (db, i):
            alvo = catalogo.resolver_turma(db, turma)
            modulo = estrutura.criar_modulo(db, i, alvo, nome)
            criados = [
                estrutura.criar_submodulo(db, i, modulo, s, TipoSubModulo.VIDEO, ordem=n)
                for n, s in enumerate(nomes_sub, start=1)
            ]
            db.commit()
            return {
                "aplicado": True,
                "modulo": modulo.nome,
                "turma": alvo.nome,
                "submodulos": [s.nome for s in criados],
                "mensagem": "Módulo criado, ainda sem nenhum vídeo.",
            }

    return await _confirmando(
        ctx,
        resumo,
        aplicar,
        titulo="Criar módulo",
        descricao="Confere o nome e os sub-módulos antes de criar.",
        estado=f"criar_modulo:{turma}:{nome}",
    )


@mcp.tool(name="criar_submodulo", annotations=ALTERA)
async def criar_submodulo(
    turma: Annotated[str, Field(description="Nome ou id da turma")],
    modulo: Annotated[str, Field(description="Nome ou id do módulo")],
    nome: Annotated[str, Field(description="Ex.: 'Questões da apostila', 'Revisão'")],
    ctx: Context = None,  # type: ignore[assignment]
) -> dict:
    """Acrescenta um sub-módulo a um módulo que já existe.

    Cada sub-módulo guarda um tipo só de conteúdo — hoje, vídeo. É o que
    separa 'Aulas' (poucos vídeos longos) de 'Questões da apostila' (muitos e
    curtos) sem precisar de duas estruturas diferentes.
    """
    ident = identidade_da_sessao()

    def resumo() -> str:
        with _sessao() as (db, _i):
            t = catalogo.resolver_turma(db, turma)
            m = estrutura.resolver_modulo(db, t, modulo)
            return f"Criar o sub-módulo '{nome}' em {t.nome} › {m.nome}."

    def aplicar() -> dict:
        with _sessao() as (db, i):
            t = catalogo.resolver_turma(db, turma)
            m = estrutura.resolver_modulo(db, t, modulo)
            sub = estrutura.criar_submodulo(db, i, m, nome)
            db.commit()
            return {
                "aplicado": True,
                "submodulo": sub.nome,
                "modulo": m.nome,
                "turma": t.nome,
            }

    return await _confirmando(
        ctx,
        resumo,
        aplicar,
        titulo="Criar sub-módulo",
        descricao="Confere onde ele vai entrar.",
        estado=f"criar_submodulo:{turma}:{modulo}:{nome}",
    )


@mcp.tool(name="editar_modulo", annotations=ALTERA)
async def editar_modulo(
    turma: Annotated[str, Field(description="Nome ou id da turma")],
    modulo: Annotated[str, Field(description="Módulo a alterar")],
    novo_nome: Annotated[str | None, Field(description="Novo nome, se for renomear")] = None,
    nova_ordem: Annotated[int | None, Field(description="Posição na lista da turma")] = None,
    ctx: Context = None,  # type: ignore[assignment]
) -> dict:
    """Renomeia um módulo ou muda a posição dele na turma.

    A alteração vale na hora, inclusive para os alunos: não há rascunho entre
    a confirmação e o efeito. Quem mexeu fica registrado no próprio módulo.
    """
    ident = identidade_da_sessao()
    if novo_nome is None and nova_ordem is None:
        raise ToolError("Diga o que mudar: novo_nome, nova_ordem, ou os dois.")

    def resumo() -> str:
        with _sessao() as (db, _i):
            t = catalogo.resolver_turma(db, turma)
            m = estrutura.resolver_modulo(db, t, modulo)
            mudancas = []
            if novo_nome:
                mudancas.append(f"nome: '{m.nome}' → '{novo_nome}'")
            if nova_ordem is not None:
                mudancas.append(f"posição: {m.ordem} → {nova_ordem}")
            return f"Alterar o módulo '{m.nome}' ({t.nome}):\n  " + "\n  ".join(mudancas)

    def aplicar() -> dict:
        with _sessao() as (db, i):
            t = catalogo.resolver_turma(db, turma)
            m = estrutura.resolver_modulo(db, t, modulo)
            estrutura.editar_modulo(db, i, m, nome=novo_nome, ordem=nova_ordem)
            db.commit()
            return {"aplicado": True, "modulo": m.nome, "turma": t.nome}

    return await _confirmando(
        ctx,
        resumo,
        aplicar,
        titulo="Alterar módulo",
        descricao="A mudança vale imediatamente para os alunos.",
        estado=f"editar_modulo:{turma}:{modulo}",
    )


@mcp.tool(name="editar_item", annotations=ALTERA)
async def editar_item(
    turma: Annotated[str, Field(description="Nome ou id da turma")],
    modulo: Annotated[str, Field(description="Módulo onde o item está")],
    submodulo: Annotated[str, Field(description="Sub-módulo onde o item está")],
    item: Annotated[str, Field(description="Nome ou id do item, ex.: 'Q04'")],
    novo_nome: Annotated[str | None, Field(description="Novo nome do item")] = None,
    nova_ordem: Annotated[int | None, Field(description="Posição na lista")] = None,
    mover_para_submodulo: Annotated[
        str | None, Field(description="Sub-módulo de destino, para mover o item")
    ] = None,
    ctx: Context = None,  # type: ignore[assignment]
) -> dict:
    """Renomeia, reordena ou move um item de lugar.

    `nome` é a identidade editorial ("Q04", "Aula 1 — cadeias carbônicas") e
    `ordem` é a posição na tela — são coisas diferentes, e é por isso que dá
    para exibir a Q52 antes da Q04 sem renumerar nada.
    """
    ident = identidade_da_sessao()
    if novo_nome is None and nova_ordem is None and mover_para_submodulo is None:
        raise ToolError("Diga o que mudar: novo_nome, nova_ordem ou mover_para_submodulo.")

    def _alvos(db):
        t = catalogo.resolver_turma(db, turma)
        m = estrutura.resolver_modulo(db, t, modulo)
        s = estrutura.resolver_submodulo(db, m, submodulo)
        return t, m, s, estrutura.resolver_item(db, s, item)

    def resumo() -> str:
        with _sessao() as (db, _i):
            t, m, s, alvo = _alvos(db)
            mudancas = []
            if novo_nome:
                mudancas.append(f"nome: '{alvo.nome}' → '{novo_nome}'")
            if nova_ordem is not None:
                mudancas.append(f"posição: {alvo.ordem} → {nova_ordem}")
            if mover_para_submodulo:
                destino = estrutura.resolver_submodulo(db, m, mover_para_submodulo)
                mudancas.append(f"sai de '{s.nome}' e vai para '{destino.nome}'")
            publicado = " (já visível para os alunos)" if alvo.status == "PUBLICADO" else ""
            return (
                f"Alterar '{alvo.nome}' em {t.nome} › {m.nome} › {s.nome}{publicado}:\n  "
                + "\n  ".join(mudancas)
            )

    def aplicar() -> dict:
        with _sessao() as (db, i):
            _t, m, _s, alvo = _alvos(db)
            if mover_para_submodulo:
                destino = estrutura.resolver_submodulo(db, m, mover_para_submodulo)
                estrutura.mover_item(db, i, alvo, destino)
            estrutura.editar_item(db, i, alvo, nome=novo_nome, ordem=nova_ordem)
            db.commit()
            return {"aplicado": True, "item": alvo.nome, "submodulo": alvo.submodulo.nome}

    return await _confirmando(
        ctx,
        resumo,
        aplicar,
        titulo="Alterar item",
        descricao="Item publicado muda na tela do aluno imediatamente.",
        estado=f"editar_item:{turma}:{modulo}:{submodulo}:{item}",
    )


@mcp.tool(name="remover_do_curso", annotations=REMOVE)
async def remover_do_curso(
    turma: Annotated[str, Field(description="Nome ou id da turma")],
    modulo: Annotated[str, Field(description="Módulo alvo, ou onde está o alvo")],
    submodulo: Annotated[
        str | None, Field(description="Informe para remover o sub-módulo (ou o item dentro dele)")
    ] = None,
    item: Annotated[str | None, Field(description="Informe para remover só este item")] = None,
    ctx: Context = None,  # type: ignore[assignment]
) -> dict:
    """Remove um item, um sub-módulo ou um módulo inteiro — o mais específico
    que você informar.

    **Nada é apagado de verdade.** A remoção é lógica e reversível: o conteúdo
    sai da tela do aluno e continua no banco. Remover um módulo não cascateia
    — os sub-módulos e itens ficam intactos e voltam junto se ele for
    restaurado.

    A resposta diz quantos itens publicados somem da tela, para você conferir
    o tamanho do estrago antes de confirmar.
    """
    ident = identidade_da_sessao()

    def _alvo(db):
        t = catalogo.resolver_turma(db, turma)
        m = estrutura.resolver_modulo(db, t, modulo)
        if submodulo is None:
            return t, m, None, None
        s = estrutura.resolver_submodulo(db, m, submodulo)
        if item is None:
            return t, m, s, None
        return t, m, s, estrutura.resolver_item(db, s, item)

    def resumo() -> str:
        with _sessao() as (db, _i):
            t, m, s, i = _alvo(db)
            if i is not None:
                estado = "publicado, visível agora" if i.status == "PUBLICADO" else "em rascunho"
                return (
                    f"Remover o item '{i.nome}' ({estado}) de {t.nome} › {m.nome} › {s.nome}.\n"
                    "A remoção é reversível."
                )
            if s is not None:
                return (
                    f"Remover o sub-módulo '{s.nome}' de {t.nome} › {m.nome}, com tudo que "
                    "está dentro dele.\nA remoção é reversível."
                )
            return (
                f"Remover o módulo '{m.nome}' inteiro de {t.nome}, com os sub-módulos e itens "
                "dentro dele.\nA remoção é reversível."
            )

    def aplicar() -> dict:
        with _sessao() as (db, ident_):
            _t, m, s, i = _alvo(db)
            if i is not None:
                saida = estrutura.remover_item(db, ident_, i)
            elif s is not None:
                saida = estrutura.remover_submodulo(db, ident_, s)
            else:
                saida = estrutura.remover_modulo(db, ident_, m)
            db.commit()
            return {"aplicado": True, **saida}

    return await _confirmando(
        ctx,
        resumo,
        aplicar,
        titulo="Remover do curso",
        descricao="A remoção é reversível, mas o conteúdo some da tela do aluno agora.",
        estado=f"remover:{turma}:{modulo}:{submodulo}:{item}",
    )


# --- taxonomia ---------------------------------------------------------------


@mcp.tool(name="cadastrar_assunto", annotations=ALTERA)
async def cadastrar_assunto(
    nome: Annotated[str, Field(description="Ex.: 'Estequiometria' — sem o K03 na frente")],
    subassuntos: Annotated[
        list[str] | None, Field(description="Ex.: ['Pureza e rendimento', 'Reagente limitante']")
    ] = None,
    ctx: Context = None,  # type: ignore[assignment]
) -> dict:
    """Cadastra um assunto e, se quiser, os sub-assuntos dele de uma vez.

    O nome **não** leva numeração de capítulo. "K03" é a posição na apostila
    de uma turma, e as apostilas mudam de um ano para o outro — um assunto
    chamado "K03 - Estequiometria" precisaria ser recriado a cada turma, que é
    exatamente o que a etiqueta existe para evitar.
    """
    ident = identidade_da_sessao()
    filhos = subassuntos or []

    def resumo() -> str:
        linhas = [f"Cadastrar o assunto '{nome}'"]
        linhas += [f"  • {s}" for s in filhos]
        return "\n".join(linhas)

    def aplicar() -> dict:
        with _sessao() as (db, i):
            assunto = taxonomia.criar_assunto(db, i, nome)
            criados = [taxonomia.criar_subassunto(db, i, assunto, s) for s in filhos]
            db.commit()
            return {
                "aplicado": True,
                "assunto": assunto.nome,
                "subassuntos": [s.nome for s in criados],
            }

    return await _confirmando(
        ctx,
        resumo,
        aplicar,
        titulo="Cadastrar assunto",
        descricao="Confere a grafia: é ela que vai aparecer em toda classificação.",
        estado=f"assunto:{nome}",
    )


@mcp.tool(name="classificar_videos", annotations=ALTERA)
async def classificar_videos(
    turma: Annotated[str, Field(description="Nome ou id da turma")],
    modulo: Annotated[str, Field(description="Módulo onde estão os vídeos")],
    submodulo: Annotated[str, Field(description="Sub-módulo onde estão os vídeos")],
    assunto: Annotated[str, Field(description="Assunto já cadastrado")],
    subassunto: Annotated[str | None, Field(description="Sub-assunto, quando houver")] = None,
    itens: Annotated[
        str | None,
        Field(description="Quais itens, por nome ou faixa: 'Q01-Q03' ou 'Q04,Q07'. Vazio = todos"),
    ] = None,
    ctx: Context = None,  # type: ignore[assignment]
) -> dict:
    """Etiqueta os vídeos de um sub-módulo com um assunto — em lote.

    Aceita o mesmo jeito de falar da importação: "Q01 a Q03 são cadeias
    carbônicas, Q04 a Q08 nomenclatura". Vazio, classifica o sub-módulo
    inteiro de uma vez.

    A etiqueta vai no **vídeo**, não no item: o mesmo vídeo em 2026 e 2027
    ensina a mesma coisa, então classificar uma vez basta. É por ela que a
    análise do aluno encontra o que explica o erro dele.
    """
    ident = identidade_da_sessao()

    def _alvos(db):
        from app.services.nomes_vimeo import interpretar_faixa

        t = catalogo.resolver_turma(db, turma)
        m = estrutura.resolver_modulo(db, t, modulo)
        s = estrutura.resolver_submodulo(db, m, submodulo)
        todos = list(s.itens)
        if not itens:
            return t, m, s, [i for i in todos if i.removido_em is None]

        try:
            numeros = interpretar_faixa(itens)
        except ValueError as e:
            raise ErroDominio(str(e)) from None

        def numero_do(nome: str) -> int | None:
            digitos = "".join(c for c in nome if c.isdigit())
            return int(digitos) if digitos else None

        escolhidos = [
            i for i in todos if i.removido_em is None and numero_do(i.nome) in numeros
        ]
        return t, m, s, escolhidos

    def resumo() -> str:
        with _sessao() as (db, _i):
            t, m, s, escolhidos = _alvos(db)
            etiqueta = f"{assunto}" + (f" › {subassunto}" if subassunto else "")
            nomes = ", ".join(i.nome for i in escolhidos) or "(nenhum item casou)"
            return (
                f"Etiquetar {len(escolhidos)} vídeo(s) de {t.nome} › {m.nome} › {s.nome} "
                f"como '{etiqueta}':\n  {nomes}"
            )

    def aplicar() -> dict:
        with _sessao() as (db, i):
            _t, _m, _s, escolhidos = _alvos(db)
            if not escolhidos:
                raise ErroDominio("Nenhum item casou com a faixa informada.")
            alvo_assunto = taxonomia.resolver_assunto(db, assunto)
            alvo_sub = (
                taxonomia.resolver_subassunto(db, alvo_assunto, subassunto)
                if subassunto
                else None
            )
            for escolhido in escolhidos:
                taxonomia.classificar_video(db, i, escolhido.video, alvo_assunto, alvo_sub)
            db.commit()
            return {
                "aplicado": True,
                "videos_classificados": len(escolhidos),
                "assunto": alvo_assunto.nome,
                "subassunto": alvo_sub.nome if alvo_sub else None,
            }

    return await _confirmando(
        ctx,
        resumo,
        aplicar,
        titulo="Classificar vídeos",
        descricao="A etiqueta vale para todas as turmas onde estes vídeos aparecem.",
        estado=f"classificar:{turma}:{modulo}:{submodulo}:{assunto}",
    )
