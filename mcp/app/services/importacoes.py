"""Importação de simulado pelo link de envio: .docx ou prints.

O fluxo inteiro acontece no chat (docs/IMPORTADOR-SIMULADO.md): a tool gera o
link e o professor envia por ele. O .docx este módulo lê e transforma em
rascunho, e o Claude revisa na conversa — vendo as figuras — e completa o que
as regras não fecharam apontando os blocos do documento, sem redigitar. Os
prints ficam guardados como chegaram: o Claude os lê, transcreve as questões
e aponta onde está cada figura, que sai recortada do print original.

Nada aqui publica: o que sai é um rascunho, e publicar continua exigindo a
aprovação gravada em `drafts.aprovado_por_id`.
"""

from __future__ import annotations

import io
import re
import secrets
from datetime import UTC, datetime, timedelta

from PIL import Image, ImageFilter
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
    Status,
    StatusImportacao,
    Usuario,
)
from app.security import hash_token
from app import leitor_docx
from app.services import questoes, rascunhos, simulados, taxonomia
from app.services.catalogo import resolver_turma
from app.services.consultas import selecionar
from app.integracoes.vimeo.nomes import interpretar_faixa
from app.services.simulados import em_brasilia, ler_data_hora

VALIDADE_DO_LINK = timedelta(minutes=30)
LIMITE_DO_ARQUIVO = 25 * 1024 * 1024
_REFERENCIA = re.compile(r"figura:(\d+)")

DOCX, PRINTS = "docx", "prints"
LIMITE_DOS_PRINTS = 50
LIMITE_DO_PRINT = 5 * 1024 * 1024

# A conta de pixel é uma só, em app/imagens.py, de onde o adaptador MCP também
# a chama. A escala da vista e o limiar do traço são calibrados contra print de
# verdade — manter duas cópias seria garantir que uma delas envelhecesse.
from app.imagens import (  # noqa: E402
    AMPLIACAO_MAXIMA,
    LADO_DA_VISTA,
    PIXELS_DA_VISTA,
    TRACO,
    _estender_ate_o_desenho,
    _jpeg,
    _png,
    _tamanho_da_vista,
)


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

    return _novo_link(
        db, ident, agora,
        {"turmas": nomes, "titulo": titulo, "abre_em": abre_em, "fecha_em": fecha_em,
         "duracao_minutos": duracao_minutos, "pasta_resolucao": pasta_resolucao},
        "ele abre, envia o .docx e avisa aqui que enviou. Aí chame revisar_importacao",
    )


def criar_link_de_prints(db: Session, ident: Identidade, agora: datetime | None = None) -> dict:
    """O link para prints de questões. Turma e agenda ficam para quando o Claude criar as questões."""
    ident.exigir_operador()
    return _novo_link(
        db, ident, _agora(agora), {"formato": PRINTS},
        "ele cola os prints (Ctrl+V) ou escolhe as imagens, envia e avisa aqui. Aí chame ver_prints",
    )


def _novo_link(db: Session, ident: Identidade, agora: datetime, parametros: dict, passos: str) -> dict:
    token = secrets.token_urlsafe(32)
    importacao = Importacao(
        criado_por_id=ident.usuario_id,
        token_hash=hash_token(token),
        expira_em=agora + VALIDADE_DO_LINK,
        parametros=parametros,
    )
    db.add(importacao)
    db.commit()

    base = (get_settings().mcp_base_url or "").rstrip("/")
    return {
        "importacao_id": importacao.id,
        "link": f"{base}/enviar/{token}",
        "expira_em": em_brasilia(importacao.expira_em),
        "instrucao": f"Passe o link ao professor: {passos} com importacao={importacao.id}.",
    }


def _formato(importacao: Importacao) -> str:
    return importacao.parametros.get("formato", DOCX)


def _pelo_token(db: Session, token: str) -> Importacao:
    importacao = db.scalar(select(Importacao).where(Importacao.token_hash == hash_token(token)))
    if importacao is None:
        raise NaoEncontrado("Este link de envio não existe. Peça um novo no chat.")
    return importacao


def _aguardando(db: Session, token: str, formato: str, agora: datetime) -> Importacao:
    """O link que ainda pode receber este envio. Envio recusado não gasta o link."""
    importacao = _pelo_token(db, token)
    if importacao.status == StatusImportacao.PROCESSADA:
        raise RegraDeNegocio("Este link já recebeu um envio. Volte ao chat.")
    if agora >= importacao.expira_em:
        raise RegraDeNegocio("Este link expirou. Peça um novo no chat.")
    if _formato(importacao) != formato:
        raise RegraDeNegocio(
            "Este link é para prints de questões: cole ou escolha as imagens."
            if formato == DOCX else "Este link é para o .docx do simulado."
        )
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
        "formato": _formato(importacao),
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
    importacao = _aguardando(db, token, DOCX, agora)
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


# --- os prints ---------------------------------------------------------------


def receber_prints(
    db: Session, token: str, arquivos: list[tuple[str | None, bytes]], agora: datetime | None = None
) -> dict:
    """Guarda os prints na ordem em que chegaram. Ler fica com o Claude, na conversa."""
    agora = _agora(agora)
    importacao = _aguardando(db, token, PRINTS, agora)
    if not arquivos:
        raise RegraDeNegocio("Nenhum print chegou. Cole ou escolha as imagens das questões.")
    if len(arquivos) > LIMITE_DOS_PRINTS:
        raise RegraDeNegocio(f"Mande até {LIMITE_DOS_PRINTS} prints por link; peça outro no chat para o resto.")

    ids = []
    for numero, (nome, conteudo) in enumerate(arquivos, start=1):
        rotulo = f"O arquivo {numero} ({nome or 'sem nome'})"
        tipo = questoes.tipo_da_imagem(conteudo)
        if tipo is None:
            raise RegraDeNegocio(f"{rotulo} não é imagem PNG, JPEG, WEBP ou GIF.")
        if len(conteudo) > LIMITE_DO_PRINT:
            raise RegraDeNegocio(f"{rotulo} passa de {LIMITE_DO_PRINT // 1024 // 1024} MB.")
        try:
            with Image.open(io.BytesIO(conteudo)) as imagem:
                imagem.verify()
        except Exception:
            raise RegraDeNegocio(f"{rotulo} não abriu como imagem.") from None
        print_ = Imagem(conteudo=conteudo, tipo=tipo, nome=(nome or f"print {numero}")[:200])
        db.add(print_)
        db.flush()
        ids.append(print_.id)

    importacao.status = StatusImportacao.PROCESSADA
    importacao.recebido_em = agora
    importacao.arquivo_nome = f"{len(ids)} print(s)"
    importacao.relatorio = {"prints": ids}
    db.commit()
    return {
        "importacao_id": importacao.id,
        "prints": len(ids),
        "mensagem": "Recebido! Volte ao chat e avise que enviou — o Claude monta as questões a partir dos prints.",
    }


def _ids_dos_prints(db: Session, ident: Identidade, importacao_id: int) -> list[int]:
    ids = (_processada(db, ident, importacao_id).relatorio or {}).get("prints")
    if ids is None:
        raise RegraDeNegocio(f"A importação {importacao_id} é de um .docx: revise com revisar_importacao.")
    return ids


def total_de_prints(db: Session, ident: Identidade, importacao_id: int) -> dict:
    """Quantos prints chegaram — o portal pede a vista de cada um em seguida."""
    return {"importacao_id": importacao_id, "total_prints": len(_ids_dos_prints(db, ident, importacao_id))}


def _print(db: Session, ids: list[int], numero: int) -> Image.Image:
    if not 1 <= numero <= len(ids):
        raise RegraDeNegocio(f"O print {numero} não existe: esta importação tem de 1 a {len(ids)}.")
    with Image.open(io.BytesIO(db.get(Imagem, ids[numero - 1]).conteudo)) as original:
        return leitor_docx.sobre_branco(original)






def ver_prints(
    db: Session, ident: Identidade, importacao_id: int, de: int = 1, ate: int | None = None
) -> tuple[dict, list[bytes]]:
    """Os prints como o Claude os vê, na escala em que o retângulo do recorte vale."""
    ids = _ids_dos_prints(db, ident, importacao_id)
    ate = min(ate or de + 4, len(ids))
    descricao, vistas = [], []
    for numero in range(de, ate + 1):
        imagem = _print(db, ids, numero)
        tamanho = _tamanho_da_vista(*imagem.size)
        if tamanho != imagem.size:
            imagem = imagem.resize(tamanho, Image.Resampling.LANCZOS)
        descricao.append({"print": numero, "largura": tamanho[0], "altura": tamanho[1]})
        vistas.append(_jpeg(imagem))
    if not vistas:
        raise RegraDeNegocio(f"Esta importação tem {len(ids)} print(s); peça de 1 a {len(ids)}.")
    return {
        "importacao_id": importacao_id,
        "total_prints": len(ids),
        "mostrando": f"{de} a {ate}",
        "prints": descricao,
    }, vistas


def recortar_figura(
    db: Session,
    ident: Identidade,
    importacao_id: int,
    numero: int,
    questao_id: int,
    retangulo: list[float],
    parte: str = ParteDaQuestao.ENUNCIADO,
    alternativa: str | None = None,
    substituir: int | None = None,
    estender: bool = True,
    agora: datetime | None = None,
) -> tuple[dict, bytes]:
    """Recorta a figura de dentro do print e a põe na questão, no lugar da marca.

    `retangulo` é [x0, y0, x1, y1] na escala de `ver_prints`. O recorte sai do
    print original: estendido até o desenho acabar (`estender`), com a margem
    branca aparada. Devolve o resultado e a prévia, na escala da vista, para o
    Claude conferir. `substituir` troca um recorte que saiu errado sem mexer no
    texto. Só em questão de rascunho: o professor ainda vê tudo antes de aprovar.
    """
    ids = _ids_dos_prints(db, ident, importacao_id)
    questao = questoes.resolver_questao(db, questao_id)
    if questao.status != Status.RASCUNHO:
        raise RegraDeNegocio(
            f"A questão {questao.id} já foi publicada: recorte de print só entra em questão de rascunho."
        )
    imagem = _print(db, ids, numero)
    largura, altura = _tamanho_da_vista(*imagem.size)
    try:
        x0, y0, x1, y1 = (float(v) for v in retangulo)
    except (TypeError, ValueError):
        raise RegraDeNegocio("O retângulo vai como [x0, y0, x1, y1], em pixels.") from None
    folga = 10  # o que passa um pouco da borda é só a borda
    if not (-folga <= x0 < x1 <= largura + folga and -folga <= y0 < y1 <= altura + folga):
        raise RegraDeNegocio(
            f"O retângulo {list(retangulo)} não cabe no print {numero}, que tem {largura}×{altura} px "
            "na escala de ver_prints. Use [x0, y0, x1, y1] com x0 < x1 e y0 < y1."
        )

    fx, fy = imagem.width / largura, imagem.height / altura
    e = min(max(0, round(x0 * fx)), imagem.width - 1)
    t = min(max(0, round(y0 * fy)), imagem.height - 1)
    caixa = (e, t, max(e + 1, min(imagem.width, round(x1 * fx))), max(t + 1, min(imagem.height, round(y1 * fy))))
    if estender:
        caixa = _estender_ate_o_desenho(imagem, caixa)
    recorte = leitor_docx.aparar_margem(_png(imagem.crop(caixa)))

    if substituir is not None:
        figura = db.get(Imagem, substituir)
        if figura is None or figura.questao_id != questao.id:
            raise RegraDeNegocio(f"A figura {substituir} não é da questão {questao.id}.")
        saida = questoes.trocar_figura(db, ident, substituir, recorte, agora)
    else:
        saida = questoes.anexar_figura(
            db, ident, questao.id, recorte, f"print {numero}", parte, alternativa, agora
        )

    # O Claude confere na escala em que viu o print: recorte de print pequeno,
    # do tamanho original, é miúdo demais para ele notar um corte.
    previa = recorte
    if fx < 1:
        with Image.open(io.BytesIO(recorte)) as pequena:
            previa = _png(pequena.resize((round(pequena.width / fx), round(pequena.height / fy)),
                                         Image.Resampling.LANCZOS))
    return saida, previa
