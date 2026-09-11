"""POC de escrita e upload na API do Vimeo: os passos W e U de docs/vimeo-integracao/05-plano-de-poc.md.

Este script **altera a conta real do Vimeo**. Ele toca somente:

* uma pasta nova, criada por ele, com nome começando em "POC API";
* até dois showcases novos, criados por ele;
* o vídeo descartável que você indicar em `--video-teste`;
* o vídeo que ele mesmo enviar, quando `--arquivo` for usado.

**Nada é apagado.** O script não implementa DELETE de vídeo, de pasta nem de
item: a lista de métodos permitidos é fechada. A limpeza final é manual, pela
interface do Vimeo, e no fim ele diz exatamente o que apagar.

O nome e a privacidade do vídeo de teste são lidos antes e restaurados no fim.

    export VIMEO_POC_TOKEN_ESCRITA=...   # public private create edit interact upload
    python scripts/poc_vimeo_escrita.py --video-teste 123456789 \
        --dominio meu-portal.up.railway.app --confirmo

Para exercitar upload e replace, acrescente um arquivo pequeno e descartável:

    python scripts/poc_vimeo_escrita.py --video-teste 123456789 --arquivo teste.mp4 --confirmo

Sem `--confirmo` o script só mostra o que faria e sai.
"""

from __future__ import annotations

import argparse
import json
import os
import sys
import time
from datetime import UTC, datetime
from pathlib import Path
from typing import Any

import httpx

sys.path.insert(0, str(Path(__file__).resolve().parent))

from poc_vimeo import (  # noqa: E402
    CAMPOS_FAIXA,
    CAMPOS_PASTA,
    CAMPOS_VERSAO,
    Api,
    pular,
    titulo,
    valor,
)

# Fechado de propósito: DELETE não entra nesta POC.
METODOS_PERMITIDOS = frozenset({"GET", "HEAD", "POST", "PUT", "PATCH"})

# O guia recomenda blocos de 128–512 MB para arquivo grande; aqui o arquivo é
# pequeno de propósito, então 8 MB já exercita a retomada.
BLOCO = 8 * 1024 * 1024

TAG_DA_POC = "poc-mvp-portal"


class ApiEscrita(Api):
    """A API de leitura mais os métodos de escrita. Sem nenhum DELETE."""

    def enviar(
        self,
        metodo: str,
        url: str,
        *,
        corpo: Any = None,
        conteudo: bytes | None = None,
        cabecalhos: dict[str, str] | None = None,
        passo: str | None = None,
        timeout: httpx.Timeout | None = None,
    ) -> tuple[int, Any]:
        if metodo not in METODOS_PERMITIDOS:
            raise RuntimeError(f"método {metodo} não é permitido nesta POC")
        resposta = self._http.request(
            metodo, url, json=corpo, content=conteudo, headers=cabecalhos, timeout=timeout
        )
        return self._processar(
            resposta, passo, {"metodo": metodo, "url": url, "corpo_enviado": corpo}
        )

    def post(self, caminho: str, corpo: Any, *, passo: str | None = None) -> tuple[int, Any]:
        return self.enviar("POST", caminho, corpo=corpo, passo=passo)

    def patch(self, caminho: str, corpo: Any, *, passo: str | None = None) -> tuple[int, Any]:
        return self.enviar("PATCH", caminho, corpo=corpo, passo=passo)

    def put(self, caminho: str, corpo: Any = None, *, passo: str | None = None) -> tuple[int, Any]:
        return self.enviar("PUT", caminho, corpo=corpo, passo=passo)

    # --- tus ------------------------------------------------------------------

    def progresso_do_upload(self, upload_link: str) -> tuple[int, int]:
        resposta = self._http.request(
            "HEAD", upload_link, headers={"Tus-Resumable": "1.0.0"}, timeout=httpx.Timeout(60.0)
        )
        offset = int(resposta.headers.get("Upload-Offset", 0) or 0)
        tamanho = int(resposta.headers.get("Upload-Length", 0) or 0)
        return offset, tamanho

    def enviar_arquivo(self, upload_link: str, caminho: Path) -> int:
        """PATCH do tus em blocos, retomando pelo Upload-Offset que o Vimeo devolve."""
        total = caminho.stat().st_size
        offset, _ = self.progresso_do_upload(upload_link)
        with caminho.open("rb") as arquivo:
            while offset < total:
                arquivo.seek(offset)
                bloco = arquivo.read(BLOCO)
                if not bloco:
                    break
                resposta = self._http.request(
                    "PATCH",
                    upload_link,
                    content=bloco,
                    headers={
                        "Tus-Resumable": "1.0.0",
                        "Upload-Offset": str(offset),
                        "Content-Type": "application/offset+octet-stream",
                    },
                    timeout=httpx.Timeout(300.0, connect=10.0),
                )
                self.chamadas += 1
                if resposta.status_code == 409:
                    offset, _ = self.progresso_do_upload(upload_link)
                    print(f"   offset fora de sincronia; retomando de {offset}")
                    continue
                if resposta.status_code >= 400:
                    print(f"   PATCH do tus falhou: HTTP {resposta.status_code}")
                    break
                offset = int(resposta.headers.get("Upload-Offset", offset))
                print(f"   enviado {offset}/{total} bytes")
        return offset


def esperar_ficar_pronto(api: Api, video_id: str, *, limite_segundos: int, passo: str) -> dict:
    """Polling de `status` e `transcode.status`: a API não tem webhook confiável para isso."""
    campos = "uri,status,transcode.status,is_playable,transcript.status"
    corpo: Any = {}
    inicio = time.time()
    while time.time() - inicio < limite_segundos:
        _, corpo = api.get(f"/videos/{video_id}", passo=passo, fields=campos)
        if not isinstance(corpo, dict):
            break
        estado = corpo.get("status")
        transcode = valor(corpo, "transcode.status")
        print(f"   status={estado} transcode={transcode} reproduzível={corpo.get('is_playable')}")
        if corpo.get("is_playable") or transcode in ("complete", "error") or estado in (
            "available",
            "transcoding_error",
            "uploading_error",
            "quota_exceeded",
            "total_cap_exceeded",
        ):
            break
        time.sleep(15)
    return corpo if isinstance(corpo, dict) else {}


# --- passos de escrita --------------------------------------------------------


def w1_criar_pasta(api: ApiEscrita, args, achados: dict, ctx: dict) -> None:
    titulo("W1", "criar a pasta de teste")
    nome = f"POC API {datetime.now(UTC):%Y-%m-%d %H:%M} (apagar)"
    status, corpo = api.post("/me/projects", {"name": nome}, passo="W1-pasta")
    ctx["pasta_a"] = valor(corpo, "uri")
    ctx["pasta_a_id"] = (ctx["pasta_a"] or "").rsplit("/", 1)[-1] or None
    print(f"   HTTP {status}; pasta: {ctx['pasta_a']} ({nome})")
    achados["criar_pasta"] = f"HTTP {status} — {ctx['pasta_a']}"


def w2_criar_subpasta(api: ApiEscrita, args, achados: dict, ctx: dict) -> None:
    titulo("W2", "criar subpasta e conferir pai e ancestrais")
    if not ctx.get("pasta_a"):
        pular("W2", "a pasta de teste não foi criada")
        return
    status, corpo = api.post(
        "/me/projects", {"name": "POC Sub", "parent_folder_uri": ctx["pasta_a"]}, passo="W2-subpasta"
    )
    ctx["pasta_b"] = valor(corpo, "uri")
    ctx["pasta_b_id"] = (ctx["pasta_b"] or "").rsplit("/", 1)[-1] or None
    print(f"   HTTP {status}; subpasta: {ctx['pasta_b']}")
    if ctx.get("pasta_b_id"):
        _, detalhe = api.get(
            f"/me/projects/{ctx['pasta_b_id']}", passo="W2-subpasta-detalhe", fields=CAMPOS_PASTA
        )
        pai = valor(detalhe, "metadata.connections.parent_folder.uri")
        ancestrais = valor(detalhe, "metadata.connections.ancestor_path")
        print(f"   parent_folder: {pai}")
        print(f"   ancestor_path: {json.dumps(ancestrais, ensure_ascii=False)[:200]}")
        achados["subpasta"] = f"HTTP {status}; pai={pai}"


def w3_renomear_pasta(api: ApiEscrita, args, achados: dict, ctx: dict) -> None:
    titulo("W3", "renomear a subpasta")
    if not ctx.get("pasta_b_id"):
        pular("W3", "sem subpasta")
        return
    status, _ = api.patch(
        f"/me/projects/{ctx['pasta_b_id']}", {"name": "POC Sub renomeada"}, passo="W3-renomear"
    )
    print(f"   HTTP {status}")
    achados["renomear_pasta"] = f"HTTP {status}"


def w4_colocar_video_na_subpasta(api: ApiEscrita, args, achados: dict, ctx: dict) -> None:
    titulo("W4", "colocar o vídeo de teste na subpasta")
    campos = "uri,name,link,player_embed_url,parent_project,privacy.view,privacy.embed"
    _, antes = api.get(f"/videos/{args.video_teste}", passo="W4-video-antes", fields=campos)
    ctx["video_original"] = antes if isinstance(antes, dict) else {}
    print(f"   antes: pasta={valor(antes, 'parent_project.uri')} privacidade={valor(antes, 'privacy.view')}")

    if not ctx.get("pasta_b_id"):
        pular("W4", "sem subpasta")
        return
    status, _ = api.put(
        f"/me/projects/{ctx['pasta_b_id']}/videos/{args.video_teste}", passo="W4-colocar"
    )
    _, depois = api.get(f"/videos/{args.video_teste}", passo="W4-video-depois", fields=campos)
    print(f"   HTTP {status}; pasta agora: {valor(depois, 'parent_project.uri')}")
    print(f"   link e embed mudaram? {valor(antes, 'link') != valor(depois, 'link')}")
    achados["colocar_em_pasta"] = f"HTTP {status}; parent_project={valor(depois, 'parent_project.uri')}"


def w5_mover_para_outra_pasta(api: ApiEscrita, args, achados: dict, ctx: dict) -> None:
    titulo("W5", "incluir na pasta raiz move o vídeo?")
    if not (ctx.get("pasta_a_id") and ctx.get("pasta_b_id")):
        pular("W5", "faltam as pastas de teste")
        return
    status, _ = api.put(
        f"/me/projects/{ctx['pasta_a_id']}/videos/{args.video_teste}", passo="W5-mover"
    )
    _, depois = api.get(
        f"/videos/{args.video_teste}", passo="W5-video-depois", fields="uri,parent_project"
    )
    _, itens_b = api.get(
        f"/me/projects/{ctx['pasta_b_id']}/items",
        passo="W5-itens-da-subpasta",
        filter="video",
        fields="type,video.uri",
    )
    ainda_em_b = any(
        valor(item, "video.uri") == f"/videos/{args.video_teste}"
        for item in (valor(itens_b, "data") or [])
    )
    print(f"   HTTP {status}; pasta agora: {valor(depois, 'parent_project.uri')}")
    print(f"   continua na subpasta anterior? {ainda_em_b}")
    achados["mover_video"] = (
        f"HTTP {status}; pasta={valor(depois, 'parent_project.uri')}; "
        f"{'duplicou' if ainda_em_b else 'moveu'}"
    )


def w6_renomear_video(api: ApiEscrita, args, achados: dict, ctx: dict) -> None:
    titulo("W6", "renomear o vídeo mantém o ID?")
    status, corpo = api.patch(
        f"/videos/{args.video_teste}",
        {"name": "POC API — renomeado (será restaurado)"},
        passo="W6-renomear-video",
    )
    print(f"   HTTP {status}; uri: {valor(corpo, 'uri')}")
    achados["renomear_video"] = f"HTTP {status}; uri={valor(corpo, 'uri')}"


def w7_privacidade_e_dominio(api: ApiEscrita, args, achados: dict, ctx: dict) -> None:
    titulo("W7", "fora do Vimeo e embed só nos nossos domínios")
    status, corpo = api.patch(
        f"/videos/{args.video_teste}",
        {"privacy": {"view": "disable", "embed": "whitelist"}},
        passo="W7-privacidade",
    )
    print(f"   PATCH privacidade -> HTTP {status}: {json.dumps(valor(corpo, 'privacy'), ensure_ascii=False)}")
    achados["privacidade"] = f"HTTP {status}"
    if status >= 400:
        return
    for dominio in [d for d in (args.dominio, "localhost") if d]:
        status_dominio, _ = api.put(
            f"/videos/{args.video_teste}/privacy/domains/{dominio}", passo=f"W7-dominio-{dominio}"
        )
        print(f"   liberar {dominio} -> HTTP {status_dominio}")
    _, lista = api.get(
        f"/videos/{args.video_teste}/privacy/domains", passo="W7-dominios", fields="domain"
    )
    dominios = [item.get("domain") for item in (valor(lista, "data") or [])]
    _, video = api.get(f"/videos/{args.video_teste}", passo="W7-embed", fields="player_embed_url")
    print(f"   domínios liberados: {dominios}")
    print(f"   teste no navegador este embed dentro do portal: {valor(video, 'player_embed_url')}")
    achados["dominios"] = f"{dominios}"


def w8_showcase(api: ApiEscrita, args, achados: dict, ctx: dict) -> None:
    titulo("W8", "showcase: criar, incluir vídeo e tentar gravar a ordem")
    status, corpo = api.post(
        "/me/albums", {"name": "POC showcase (apagar)"}, passo="W8-showcase"
    )
    ctx["showcase"] = valor(corpo, "uri")
    ctx["showcase_id"] = (ctx["showcase"] or "").rsplit("/", 1)[-1] or None
    print(f"   HTTP {status}; showcase: {ctx['showcase']}")
    if not ctx.get("showcase_id"):
        achados["showcase"] = f"HTTP {status}, sem uri"
        return

    videos = [v for v in (args.video_teste, args.video_extra) if v]
    for video in videos:
        status_incluir, _ = api.put(
            f"/me/albums/{ctx['showcase_id']}/videos/{video}", passo=f"W8-incluir-{video}"
        )
        print(f"   incluir {video} -> HTTP {status_incluir}")

    _, lista = api.get(
        f"/me/albums/{ctx['showcase_id']}/videos", passo="W8-ordem", sort="manual", fields="uri,name"
    )
    ordem = [valor(item, "uri") for item in (valor(lista, "data") or [])]
    print(f"   ordem com sort=manual: {ordem}")
    achados["showcase"] = f"HTTP {status}; {len(ordem)} vídeo(s)"

    if len(videos) >= 2:
        invertida = [f"/videos/{v}" for v in reversed(videos)]
        status_ordem, corpo_ordem = api.put(
            f"/me/albums/{ctx['showcase_id']}/videos",
            {"videos": [{"uri": uri} for uri in invertida]},
            passo="W8-gravar-ordem",
        )
        print(f"   PUT da lista invertida -> HTTP {status_ordem}: {json.dumps(corpo_ordem, ensure_ascii=False)[:200]}")
        _, depois = api.get(
            f"/me/albums/{ctx['showcase_id']}/videos", passo="W8-ordem-depois", sort="manual", fields="uri"
        )
        nova = [valor(item, "uri") for item in (valor(depois, "data") or [])]
        print(f"   ordem depois: {nova}")
        achados["ordem_manual_do_showcase"] = (
            f"HTTP {status_ordem}; {'respeitou a lista' if nova == invertida else 'não respeitou'}"
        )
    else:
        achados["ordem_manual_do_showcase"] = "não testado: passe --video-extra com um segundo vídeo"


def w9_video_em_dois_showcases(api: ApiEscrita, args, achados: dict, ctx: dict) -> None:
    titulo("W9", "o mesmo vídeo em dois showcases")
    status, corpo = api.post("/me/albums", {"name": "POC showcase 2 (apagar)"}, passo="W9-showcase2")
    ctx["showcase2"] = valor(corpo, "uri")
    segundo = (ctx["showcase2"] or "").rsplit("/", 1)[-1] or None
    if not segundo:
        achados["dois_showcases"] = f"HTTP {status}, sem uri"
        return
    status_incluir, _ = api.put(
        f"/me/albums/{segundo}/videos/{args.video_teste}", passo="W9-incluir"
    )
    _, lista = api.get(f"/videos/{args.video_teste}/albums", passo="W9-showcases-do-video", fields="uri,name")
    quantos = len(valor(lista, "data") or [])
    print(f"   incluir -> HTTP {status_incluir}; o vídeo está em {quantos} showcase(s)")
    achados["dois_showcases"] = f"HTTP {status_incluir}; {quantos} showcase(s)"


def w10_tags(api: ApiEscrita, args, achados: dict, ctx: dict) -> None:
    titulo("W10", "tag no vídeo e busca por tag no acervo privado")
    status, _ = api.put(f"/videos/{args.video_teste}/tags/{TAG_DA_POC}", passo="W10-tag")
    _, lista = api.get("/me/videos", passo="W10-busca-por-tag", filter_tag=TAG_DA_POC, fields="uri,name")
    encontrados = [valor(item, "uri") for item in (valor(lista, "data") or [])]
    print(f"   PUT tag -> HTTP {status}; busca por filter_tag devolveu: {encontrados}")
    achados["tags"] = f"HTTP {status}; {len(encontrados)} encontrado(s)"
    ctx["tag_aplicada"] = status < 400


def w11_custom_metadata(api: ApiEscrita, args, achados: dict, ctx: dict) -> None:
    titulo("W11", "custom metadata do time está disponível?")
    _, eu = api.get("/me", passo="W11-me", fields="uri")
    meu_id = (valor(eu, "uri") or "").rsplit("/", 1)[-1]
    if not meu_id:
        pular("W11", "não consegui descobrir o id da conta")
        return
    status, corpo = api.get(f"/teams/{meu_id}/custom_metadata", passo="W11-custom-metadata", fields="id,name,type")
    print(f"   HTTP {status}: {json.dumps(corpo, ensure_ascii=False)[:200]}")
    achados["custom_metadata"] = f"HTTP {status}"


# --- upload e replace ---------------------------------------------------------


def u1_upload(api: ApiEscrita, args, achados: dict, ctx: dict) -> None:
    titulo("U1", "upload pelo servidor, com tus")
    arquivo = Path(args.arquivo).expanduser()
    if not arquivo.is_file():
        pular("U1", f"arquivo não encontrado: {arquivo}")
        return
    tamanho = arquivo.stat().st_size
    corpo_pedido: dict[str, Any] = {
        "upload": {"approach": "tus", "size": tamanho},
        "name": f"POC upload {datetime.now(UTC):%Y-%m-%d %H:%M} (apagar)",
        "privacy": {"view": "nobody"},
    }
    if ctx.get("pasta_a"):
        corpo_pedido["folder_uri"] = ctx["pasta_a"]
    status, corpo = api.post("/me/videos", corpo_pedido, passo="U1-criar")
    print(f"   POST /me/videos -> HTTP {status}")
    if status >= 400:
        achados["upload"] = f"HTTP {status}: {json.dumps(corpo, ensure_ascii=False)[:200]}"
        return
    ctx["video_enviado"] = valor(corpo, "uri")
    enviado_id = (ctx["video_enviado"] or "").rsplit("/", 1)[-1]
    upload_link = valor(corpo, "upload.upload_link")
    print(f"   vídeo criado: {ctx['video_enviado']}; approach: {valor(corpo, 'upload.approach')}")
    inicio = time.time()
    offset = api.enviar_arquivo(upload_link, arquivo)
    print(f"   enviados {offset}/{tamanho} bytes em {time.time() - inicio:.0f}s")
    pronto = esperar_ficar_pronto(api, enviado_id, limite_segundos=args.espera_transcode, passo="U1-status")
    achados["upload"] = (
        f"HTTP {status}; {offset}/{tamanho} bytes; status final {pronto.get('status')}; "
        f"transcrição {valor(pronto, 'transcript.status')}"
    )
    ctx["video_enviado_id"] = enviado_id


def u3_replace(api: ApiEscrita, args, achados: dict, ctx: dict) -> None:
    titulo("U3", "substituir o arquivo mantém ID, embed, thumbnail e legenda?")
    enviado = ctx.get("video_enviado_id")
    if not enviado:
        pular("U3", "o upload do U1 não terminou")
        return
    arquivo = Path(args.arquivo).expanduser()
    campos = "uri,player_embed_url,pictures.base_link,transcript.status,metadata.connections.versions.current_uri"
    _, antes = api.get(f"/videos/{enviado}", passo="U3-antes", fields=campos)
    _, faixas_antes = api.get(f"/videos/{enviado}/texttracks", passo="U3-faixas-antes", fields=CAMPOS_FAIXA)

    status, corpo = api.post(
        f"/videos/{enviado}/versions",
        {
            "file_name": arquivo.name,
            "upload": {"status": "in_progress", "size": arquivo.stat().st_size, "approach": "tus"},
        },
        passo="U3-nova-versao",
    )
    print(f"   POST versions -> HTTP {status}")
    if status >= 400:
        achados["replace"] = f"HTTP {status}: {json.dumps(corpo, ensure_ascii=False)[:200]}"
        return
    api.enviar_arquivo(valor(corpo, "upload.upload_link"), arquivo)
    esperar_ficar_pronto(api, enviado, limite_segundos=args.espera_transcode, passo="U3-status")

    _, depois = api.get(f"/videos/{enviado}", passo="U3-depois", fields=campos)
    _, faixas_depois = api.get(f"/videos/{enviado}/texttracks", passo="U3-faixas-depois", fields=CAMPOS_FAIXA)
    _, versoes = api.get(f"/videos/{enviado}/versions", passo="U3-versoes", fields=CAMPOS_VERSAO)
    iguais = {
        "uri": valor(antes, "uri") == valor(depois, "uri"),
        "embed": valor(antes, "player_embed_url") == valor(depois, "player_embed_url"),
        "thumbnail": valor(antes, "pictures.base_link") == valor(depois, "pictures.base_link"),
        "faixas": len(valor(faixas_antes, "data") or []) == len(valor(faixas_depois, "data") or []),
    }
    print(f"   permaneceu igual: {iguais}")
    print(f"   versões agora: {len(valor(versoes, 'data') or [])}")
    print(f"   versão ativa: {valor(depois, 'metadata.connections.versions.current_uri')}")
    achados["replace"] = f"HTTP {status}; igual={iguais}; versões={len(valor(versoes, 'data') or [])}"
    achados["legenda_em_nova_versao"] = str(valor(depois, "transcript.status"))


# --- restauração e limpeza ----------------------------------------------------


def restaurar(api: ApiEscrita, args, achados: dict, ctx: dict) -> None:
    titulo("Restauração", "devolver nome e privacidade do vídeo de teste")
    original = ctx.get("video_original") or {}
    if not original:
        pular("Restauração", "não guardei o estado original")
        return
    corpo: dict[str, Any] = {}
    if original.get("name"):
        corpo["name"] = original["name"]
    privacidade = original.get("privacy") or {}
    if privacidade.get("view") or privacidade.get("embed"):
        corpo["privacy"] = {k: v for k, v in privacidade.items() if k in ("view", "embed")}
    if not corpo:
        pular("Restauração", "nada para restaurar")
        return
    status, _ = api.patch(f"/videos/{args.video_teste}", corpo, passo="restauracao")
    print(f"   HTTP {status}; restaurado: {json.dumps(corpo, ensure_ascii=False)}")
    achados["restauracao"] = f"HTTP {status}"


def instrucoes_de_limpeza(ctx: dict) -> None:
    print("\n== Limpeza manual (o script não apaga nada)")
    print("   Na interface do Vimeo, apague:")
    for rotulo, chave in (
        ("pasta de teste", "pasta_a"),
        ("showcase", "showcase"),
        ("segundo showcase", "showcase2"),
        ("vídeo enviado", "video_enviado"),
    ):
        if ctx.get(chave):
            print(f"     - {rotulo}: {ctx[chave]}")
    if ctx.get("tag_aplicada"):
        print(f"     - a tag {TAG_DA_POC} do vídeo de teste")
    print("   A pasta pode ser apagada sem apagar os vídeos: confira a opção antes de confirmar.")


RESUMO = [
    ("Criar pasta e subpasta (W1, W2)", ("criar_pasta", "subpasta")),
    ("Renomear pasta (W3)", ("renomear_pasta",)),
    ("Colocar vídeo em pasta (W4)", ("colocar_em_pasta",)),
    ("Incluir em outra pasta move? (W5)", ("mover_video",)),
    ("Renomear vídeo mantém o ID? (W6)", ("renomear_video",)),
    ("Privacidade e domínios (W7)", ("privacidade", "dominios")),
    ("Showcase e ordem manual (W8)", ("showcase", "ordem_manual_do_showcase")),
    ("Vídeo em dois showcases (W9)", ("dois_showcases",)),
    ("Tag e busca por tag (W10)", ("tags",)),
    ("Custom metadata do time (W11)", ("custom_metadata",)),
    ("Upload com tus (U1)", ("upload",)),
    ("Replace preserva tudo? (U3)", ("replace", "legenda_em_nova_versao")),
    ("Restauração do vídeo de teste", ("restauracao",)),
]


def escrever_resumo(saida: Path, achados: dict, api: Api, ctx: dict) -> Path:
    linhas = [
        "# Resultado da POC de escrita e upload no Vimeo",
        "",
        f"Executada em {datetime.now(UTC).isoformat(timespec='seconds')} — {api.chamadas} chamadas.",
        "",
        "| Pergunta | Resultado |",
        "|---|---|",
    ]
    for pergunta, chaves in RESUMO:
        respostas = [achados[c] for c in chaves if c in achados]
        texto = " · ".join(respostas).replace("|", "\\|") if respostas else "não executado"
        linhas.append(f"| {pergunta} | {texto} |")
    linhas += ["", "## Para apagar na mão", ""]
    for rotulo, chave in (
        ("pasta de teste", "pasta_a"),
        ("showcase", "showcase"),
        ("segundo showcase", "showcase2"),
        ("vídeo enviado", "video_enviado"),
    ):
        if ctx.get(chave):
            linhas.append(f"* {rotulo}: `{ctx[chave]}`")
    linhas.append("")
    destino = saida / "resumo-escrita.md"
    destino.write_text("\n".join(linhas), encoding="utf-8")
    return destino


def main() -> int:
    parser = argparse.ArgumentParser(description="POC de escrita e upload na API do Vimeo.")
    parser.add_argument("--video-teste", help="ID de um vídeo DESCARTÁVEL da sua conta")
    parser.add_argument("--video-extra", help="Segundo vídeo descartável, para testar a ordem do showcase")
    parser.add_argument("--dominio", help="Domínio a liberar para embed, por exemplo o do Railway")
    parser.add_argument("--arquivo", help="Arquivo de vídeo pequeno e descartável, para upload e replace")
    parser.add_argument("--espera-transcode", type=int, default=600, help="Segundos de polling do processamento")
    parser.add_argument("--saida", default=".poc_vimeo", help="Pasta de saída (fora do git)")
    parser.add_argument("--confirmo", action="store_true", help="Sem esta opção o script só mostra o que faria")
    args = parser.parse_args()

    if hasattr(sys.stdout, "reconfigure"):
        sys.stdout.reconfigure(encoding="utf-8", errors="replace")

    token = os.environ.get("VIMEO_POC_TOKEN_ESCRITA")
    if not token:
        print("Defina VIMEO_POC_TOKEN_ESCRITA (public private create edit interact upload, sem delete).")
        return 1

    print("Esta POC vai ALTERAR a conta real do Vimeo. O que ela faz:")
    print("  1. cria uma pasta 'POC API …' e uma subpasta dentro dela;")
    print("  2. renomeia a subpasta;")
    if args.video_teste:
        print(f"  3. move o vídeo {args.video_teste} entre essas duas pastas;")
        print("  4. renomeia esse vídeo e, no fim, devolve o nome original;")
        if args.dominio:
            print(f"  5. troca a privacidade dele para 'fora do Vimeo' e libera o embed em {args.dominio} e localhost,")
            print("     restaurando a privacidade original no fim;")
        print("  6. cria dois showcases, inclui o vídeo neles e testa a ordem manual;")
        print("  7. aplica a tag de teste e busca por ela.")
    else:
        print("  (sem --video-teste, os passos que mexem em vídeo são pulados)")
    if args.arquivo:
        print(f"  8. envia {args.arquivo} como vídeo novo e depois substitui o arquivo dele (replace).")
    print("  Nada é apagado: nenhum DELETE é implementado aqui.")

    if not args.confirmo:
        print("\nNada foi executado. Rode de novo com --confirmo quando quiser valer.")
        return 0

    saida = Path(args.saida)
    saida.mkdir(parents=True, exist_ok=True)
    api = ApiEscrita(token, saida)
    achados: dict[str, str] = {}
    ctx: dict[str, Any] = {}

    passos: list = [w1_criar_pasta, w2_criar_subpasta, w3_renomear_pasta]
    if args.video_teste:
        passos += [w4_colocar_video_na_subpasta, w5_mover_para_outra_pasta, w6_renomear_video]
        if args.dominio:
            passos.append(w7_privacidade_e_dominio)
        passos += [w8_showcase, w9_video_em_dois_showcases, w10_tags]
    passos.append(w11_custom_metadata)
    if args.arquivo:
        passos += [u1_upload, u3_replace]
    if args.video_teste:
        passos.append(restaurar)

    try:
        for passo in passos:
            try:
                passo(api, args, achados, ctx)
            except httpx.HTTPError as erro:
                print(f"   erro de rede: {type(erro).__name__}: {erro}")
            except Exception as erro:  # um passo que falha não derruba a POC
                print(f"   erro: {type(erro).__name__}: {erro}")
    finally:
        api.fechar()

    destino = escrever_resumo(saida, achados, api, ctx)
    instrucoes_de_limpeza(ctx)
    print(f"\nResumo em {destino} — {api.chamadas} chamadas, cota final: {dict(api.limite)}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
