"""Importação de simulado por .docx: o link de envio, o arquivo e a revisão.

O fluxo inteiro acontece no chat (docs/IMPORTADOR-SIMULADO.md): a tool gera o
link, o professor envia o arquivo por ele, este módulo lê o .docx e cria o
rascunho, e o Claude revisa na conversa — vendo as figuras — e completa o que
as regras não fecharam apontando os blocos do documento, sem redigitar.

Nada aqui publica: o que sai é um rascunho, e publicar continua exigindo a
aprovação gravada em `drafts.aprovado_por_id`.
"""

from __future__ import annotations

import re
import secrets
from datetime import UTC, datetime, timedelta

from sqlalchemy import select
from sqlalchemy.orm import Session

from app.config import get_settings
from app.errors import NaoEncontrado, RegraDeNegocio
from app.identidade import Canal, Identidade
from app.models import (
    LETRAS,
    Imagem,
    Importacao,
    ParteDaQuestao,
    Simulado,
    StatusImportacao,
    Usuario,
)
from app.security import hash_token
from app.services import leitor_docx, rascunhos, simulados, taxonomia
from app.services.catalogo import resolver_turma
from app.services.consultas import selecionar
from app.services.nomes_vimeo import interpretar_faixa
from app.services.simulados import em_brasilia, ler_data_hora

VALIDADE_DO_LINK = timedelta(minutes=30)
LIMITE_DO_ARQUIVO = 25 * 1024 * 1024
_REFERENCIA = re.compile(r"figura:(\d+)")


def _agora(agora: datetime | None) -> datetime:
    return agora or datetime.now(UTC)


# --- o link ------------------------------------------------------------------


def criar_link(
    db: Session,
    ident: Identidade,
    turmas: list[str | int] | str,
    titulo: str | None = None,
    abre_em: str | None = None,
    fecha_em: str | None = None,
    duracao_minutos: int | None = None,
    pasta_resolucao: str | None = None,
    agora: datetime | None = None,
) -> dict:
    """O link de envio: uso único, com prazo, e preso a quem pediu.

    Turmas, título, agenda e pasta do Vimeo são conferidos já aqui — um erro de
    digitação aparece no chat antes de o professor enviar o arquivo.
    """
    ident.exigir_operador()
    agora = _agora(agora)
    turmas = [turmas] if isinstance(turmas, (str, int)) else list(turmas or [])
    if not turmas:
        raise RegraDeNegocio("Informe ao menos uma turma para o simulado.")
    nomes = [resolver_turma(db, turma).nome for turma in turmas]
    ler_data_hora(abre_em)
    ler_data_hora(fecha_em)
    if duracao_minutos is not None and duracao_minutos <= 0:
        raise RegraDeNegocio("O tempo de prova precisa ser maior que zero.")

    token = secrets.token_urlsafe(32)
    importacao = Importacao(
        criado_por_id=ident.usuario_id,
        token_hash=hash_token(token),
        expira_em=agora + VALIDADE_DO_LINK,
        parametros={
            "turmas": nomes, "titulo": titulo, "abre_em": abre_em, "fecha_em": fecha_em,
            "duracao_minutos": duracao_minutos, "pasta_resolucao": pasta_resolucao,
        },
    )
    db.add(importacao)
    db.commit()

    base = (get_settings().mcp_base_url or "").rstrip("/")
    return {
        "importacao_id": importacao.id,
        "link": f"{base}/enviar/{token}",
        "expira_em": em_brasilia(importacao.expira_em),
        "instrucao": (
            "Passe o link ao professor: ele abre, envia o .docx e avisa aqui que enviou. "
            f"Aí chame revisar_importacao com importacao={importacao.id}."
        ),
    }


def _pelo_token(db: Session, token: str) -> Importacao:
    importacao = db.scalar(select(Importacao).where(Importacao.token_hash == hash_token(token)))
    if importacao is None:
        raise NaoEncontrado("Este link de envio não existe. Peça um novo no chat.")
    return importacao


def situacao_do_link(db: Session, token: str, agora: datetime | None = None) -> dict:
    """O que a página de envio mostra antes de receber o arquivo."""
    importacao = _pelo_token(db, token)
    if importacao.status == StatusImportacao.PROCESSADA:
        situacao = "RECEBIDO"
    elif _agora(agora) >= importacao.expira_em:
        situacao = "EXPIRADO"
    else:
        situacao = "AGUARDANDO"
    return {
        "situacao": situacao,
        "titulo": importacao.parametros.get("titulo"),
        "turmas": importacao.parametros.get("turmas", []),
        "pasta_resolucao": importacao.parametros.get("pasta_resolucao"),
        "pedido_por": importacao.criado_por.nome,
        "expira_em": em_brasilia(importacao.expira_em),
    }


# --- o arquivo ---------------------------------------------------------------


def receber_arquivo(
    db: Session,
    token: str,
    nome: str | None,
    conteudo: bytes,
    resolucoes: dict[int, dict] | None = None,
    agora: datetime | None = None,
) -> dict:
    """Lê o .docx, grava as figuras e cria o rascunho do simulado.

    Só as questões que as regras fecharam entram no rascunho; as outras ficam
    no relatório, com os blocos, para o Claude completar na revisão. Figura em
    formato antigo que não deu para converter vira `figura:pendente` no texto,
    e a questão fica com imagem pendente.
    """
    agora = _agora(agora)
    importacao = _pelo_token(db, token)
    if importacao.status == StatusImportacao.PROCESSADA:
        raise RegraDeNegocio("Este link já recebeu um arquivo. Volte ao chat.")
    if agora >= importacao.expira_em:
        raise RegraDeNegocio("Este link expirou. Peça um novo no chat.")
    if not (nome or "").lower().endswith(".docx"):
        raise RegraDeNegocio("Envie o arquivo .docx do simulado.")
    if len(conteudo) > LIMITE_DO_ARQUIVO:
        raise RegraDeNegocio(f"O arquivo passa de {LIMITE_DO_ARQUIVO // 1024 // 1024} MB.")

    documento = leitor_docx.ler_docx(conteudo)
    leitura = leitor_docx.separar_questoes(documento.blocos)
    completas = [q for q in leitura.questoes if q.completa]
    if not completas:
        raise RegraDeNegocio(
            "Não reconheci nenhuma questão completa neste arquivo (número, alternativas a) a e) "
            "e gabarito). Se ele não segue esse formato, monte o simulado pelo chat."
        )
    nao_convertidas = leitor_docx.converter_formatos_antigos(documento.figuras)

    ids: dict[str, int] = {}
    for chave, figura in documento.figuras.items():
        if figura.tipo is not None:
            imagem = Imagem(conteudo=figura.conteudo, tipo=figura.tipo,
                            nome=figura.nome.rsplit("/", 1)[-1][:200])
            db.add(imagem)
            db.flush()
            ids[chave] = imagem.id

    def com_ids(texto: str | None) -> str | None:
        if texto is None:
            return None
        return re.sub(r"figura:(f\d+)",
                      lambda m: f"figura:{ids[m.group(1)]}" if m.group(1) in ids else "figura:pendente",
                      texto)

    entradas = []
    for q in completas:
        entrada = q.como_entrada()
        entrada["enunciado"] = com_ids(entrada["enunciado"])
        entrada["resolucao_comentada"] = com_ids(entrada["resolucao_comentada"])
        entrada["alternativas"] = {letra: com_ids(t) for letra, t in entrada["alternativas"].items()}
        entradas.append(entrada)

    dono = db.get(Usuario, importacao.criado_por_id)
    ident = Identidade(dono.id, dono.nome, dono.email, dono.papel, Canal.DOCX)
    parametros = importacao.parametros
    titulo = (parametros.get("titulo") or leitura.titulo
              or (nome or "Simulado").rsplit(".", 1)[0]).strip()
    detalhe = rascunhos.criar_simulado_rascunho(
        db, ident, parametros["turmas"], titulo, entradas, parametros.get("abre_em"),
        parametros.get("fecha_em"), parametros.get("duracao_minutos"), resolucoes,
    )

    questao_por_numero = {}
    for lida, criada in zip(completas, detalhe["simulado"]["questoes"]):
        questao_por_numero[lida.numero] = criada["questao_id"]
        _ligar_figuras(db, criada["questao_id"], {ids[c]: p for c, p in lida.figuras.items() if c in ids})

    avisos = list(leitura.avisos)
    if nao_convertidas:
        avisos.append(f"{len(nao_convertidas)} figura(s) em formato antigo não convertida(s): as "
                      "questões delas ficaram com imagem pendente.")
    importacao.status = StatusImportacao.PROCESSADA
    importacao.arquivo_nome = (nome or "")[:200]
    importacao.arquivo = conteudo
    importacao.recebido_em = agora
    importacao.rascunho_id = detalhe["rascunho_id"]
    importacao.blocos = [{"indice": b.indice, "texto": com_ids(b.texto)} for b in documento.blocos]
    importacao.relatorio = {
        "titulo": titulo,
        "avisos": avisos,
        "questoes": [
            {"numero": q.numero, "questao_id": questao_por_numero.get(q.numero),
             "avisos": q.avisos, "blocos": q.blocos}
            for q in leitura.questoes
        ],
    }
    db.commit()

    return {
        "importacao_id": importacao.id,
        "titulo": titulo,
        "questoes_lidas": len(leitura.questoes),
        "questoes_completas": len(completas),
        "figuras": len(ids),
        "mensagem": "Recebido! Volte ao chat e avise que enviou — o Claude mostra o que foi lido.",
    }


def _ligar_figuras(db: Session, questao_id: int, partes: dict[int, str]) -> None:
    for figura_id, parte in partes.items():
        figura = db.get(Imagem, figura_id)
        if figura is not None and figura.questao_id is None:
            figura.questao_id, figura.parte = questao_id, parte


# --- a revisão no chat -------------------------------------------------------


def _processada(db: Session, ident: Identidade, importacao_id: int) -> Importacao:
    ident.exigir_operador()
    importacao = db.get(Importacao, importacao_id)
    if importacao is None:
        raise NaoEncontrado(f"Importação {importacao_id} não existe.")
    if importacao.status != StatusImportacao.PROCESSADA:
        raise RegraDeNegocio(
            f"O arquivo da importação {importacao_id} ainda não chegou (o link vale até "
            f"{em_brasilia(importacao.expira_em)}). Confirme com o professor se ele enviou."
        )
    return importacao


def _simulado(db: Session, importacao: Importacao) -> Simulado:
    simulado = db.scalar(selecionar(Simulado).where(Simulado.rascunho_id == importacao.rascunho_id))
    if simulado is None:
        raise RegraDeNegocio("O simulado desta importação foi removido.")
    return simulado


def revisar(
    db: Session, ident: Identidade, importacao_id: int, de: int = 1, ate: int | None = None
) -> tuple[dict, list[Imagem]]:
    """O preview: as questões como estão agora no rascunho, os avisos e as figuras.

    Devolve o texto e, à parte, as figuras das questões mostradas — a borda do
    MCP as entrega ao Claude como imagem. Mostra de `de` até `ate` (ordem na
    prova), para simulado grande caber na conversa.
    """
    importacao = _processada(db, ident, importacao_id)
    simulado = _simulado(db, importacao)
    relatorio = importacao.relatorio or {}
    por_questao = {r["questao_id"]: r for r in relatorio.get("questoes", []) if r["questao_id"]}
    ate = ate or len(simulado.questoes)

    questoes, figuras = [], []
    for sq in simulado.questoes:
        if not de <= sq.ordem <= ate:
            continue
        q = sq.questao
        alternativas = {a.letra: a.texto for a in q.alternativas}
        textos = [q.enunciado, q.resolucao_comentada or "", *alternativas.values()]
        ids = sorted({int(i) for t in textos for i in _REFERENCIA.findall(t)})
        figuras += [f for f in (db.get(Imagem, i) for i in ids) if f is not None]
        leitura = por_questao.get(q.id, {})
        questoes.append({
            "ordem": sq.ordem,
            "numero_no_documento": leitura.get("numero"),
            "questao_id": q.id,
            "enunciado": q.enunciado,
            "alternativas": alternativas,
            "gabarito": q.gabarito,
            "resolucao_comentada": q.resolucao_comentada,
            "imagem_pendente": q.imagem_pendente,
            "classificacao": taxonomia.assuntos_da_questao(q),
            "avisos": [a for a in leitura.get("avisos", []) if not a.startswith("Não fechou")],
            "figuras": ids,
        })

    blocos = {b["indice"]: b["texto"] for b in importacao.blocos or []}
    incompletas = [
        {"numero": r["numero"], "avisos": r["avisos"],
         "blocos": [{"indice": i, "texto": blocos.get(i, "")} for i in r["blocos"]]}
        for r in relatorio.get("questoes", []) if not r["questao_id"]
    ]
    return {
        "importacao_id": importacao.id,
        "rascunho_id": importacao.rascunho_id,
        "simulado_id": simulado.id,
        "titulo": simulado.titulo,
        "arquivo": importacao.arquivo_nome,
        "total_questoes": len(simulado.questoes),
        "mostrando": f"{de} a {min(ate, len(simulado.questoes))}",
        "avisos_gerais": relatorio.get("avisos", []),
        "questoes": questoes,
        "incompletas": incompletas,
        "pendencias_para_publicar": simulados.pendencias_para_publicar(simulado),
    }, figuras


def _blocos(importacao: Importacao, faixa: str, rotulo: re.Pattern | None = None) -> str:
    textos = {b["indice"]: b["texto"] for b in importacao.blocos or []}
    try:
        indices = sorted(interpretar_faixa(faixa))
    except ValueError as e:
        raise RegraDeNegocio(str(e)) from None
    faltando = [i for i in indices if i not in textos]
    if not indices or faltando:
        raise RegraDeNegocio(f"Blocos {faltando or faixa} não existem neste documento.")
    partes = [textos[i] for i in indices]
    if rotulo is not None:
        partes[0] = rotulo.sub("", partes[0], count=1)
    return "\n\n".join(p for p in partes if p.strip())


def completar_questao(
    db: Session,
    ident: Identidade,
    importacao_id: int,
    numero: int,
    enunciado: str,
    alternativas: dict[str, str] | str,
    gabarito: str,
    resolucao: str | None = None,
    agora: datetime | None = None,
) -> dict:
    """Monta, a partir dos blocos do documento, a questão que as regras não fecharam.

    `enunciado` e `resolucao` são faixas de blocos ("12-18"); `alternativas` é
    uma faixa de cinco blocos, um por letra ("19-23"), ou uma faixa por letra
    ({"A": "19", "B": "20-21", …}). O texto sai do documento, não de uma
    redigitação, e a questão entra no rascunho na posição do seu número.
    """
    importacao = _processada(db, ident, importacao_id)
    simulado = _simulado(db, importacao)
    relatorio = importacao.relatorio or {}
    linha = next((r for r in relatorio.get("questoes", []) if r["numero"] == numero), None)
    if linha is None:
        faltam = [r["numero"] for r in relatorio.get("questoes", []) if not r["questao_id"]]
        raise NaoEncontrado(f"A questão {numero} não foi lida neste documento. Incompletas: {faltam or 'nenhuma'}.")
    if linha["questao_id"]:
        raise RegraDeNegocio(f"A questão {numero} já está no rascunho; ajuste com editar_questao.")

    if isinstance(alternativas, str):
        indices = sorted(interpretar_faixa(alternativas))
        if len(indices) != len(LETRAS):
            raise RegraDeNegocio("Uma faixa só de alternativas precisa ter exatamente cinco blocos, de A a E.")
        faixas = dict(zip(LETRAS, map(str, indices)))
    else:
        faixas = {str(letra).strip().upper(): faixa for letra, faixa in (alternativas or {}).items()}

    entrada = {
        "numero": numero,
        "enunciado": _blocos(importacao, enunciado, leitor_docx._NUMERO_MD),
        "alternativas": {letra: _blocos(importacao, faixa, leitor_docx._ROTULO_MD) for letra, faixa in faixas.items()},
        "gabarito": gabarito,
        "resolucao_comentada": _blocos(importacao, resolucao, leitor_docx._MARCA_RESOLUCAO) if resolucao else None,
    }

    numero_por_questao = {r["questao_id"]: r["numero"] for r in relatorio["questoes"] if r["questao_id"]}
    atuais = [sq.questao_id for sq in simulado.questoes]
    posicao = next((n for n, qid in enumerate(atuais) if numero_por_questao.get(qid, 0) > numero), len(atuais))
    simulados.editar_simulado(db, ident, simulado.id, questoes=atuais[:posicao] + [entrada] + atuais[posicao:], agora=agora)

    db.refresh(simulado)
    nova = next(sq.questao for sq in simulado.questoes if sq.questao_id not in atuais)
    partes = {}
    for parte, texto in ((ParteDaQuestao.ENUNCIADO, nova.enunciado),
                         (ParteDaQuestao.RESOLUCAO, nova.resolucao_comentada or ""),
                         *((ParteDaQuestao.ALTERNATIVA, a.texto) for a in nova.alternativas)):
        partes.update({int(i): parte for i in _REFERENCIA.findall(texto) if int(i) not in partes})
    _ligar_figuras(db, nova.id, partes)

    importacao.relatorio = {
        **relatorio,
        "questoes": [
            {**r, "questao_id": nova.id, "avisos": ["Completada na revisão, a partir dos blocos."]}
            if r["numero"] == numero else r
            for r in relatorio["questoes"]
        ],
    }
    db.commit()
    return {"numero": numero, "questao_id": nova.id, "ordem": posicao + 1,
            "total_questoes": len(simulado.questoes)}
