"""Os quatro fluxos de sucesso do MVP (seção 21), de ponta a ponta.

Ensaio geral da demonstração: fala com o MCP como o Claude falaria e com o
portal como o aluno usaria. Rode com o servidor no ar e o banco recém-semeado.

    python -m app.seed --reset          # anote o token impresso
    python scripts/verificar_fluxos.py <token-do-professor>

Passa o token direto, ou o caminho de um arquivo que o contenha.
"""
import asyncio
import sys
from pathlib import Path

import httpx
from fastmcp import Client
from fastmcp.client.elicitation import ElicitResult

if len(sys.argv) < 2:
    print(__doc__)
    raise SystemExit(2)

_arg = sys.argv[1]
TOKEN = Path(_arg).read_text().strip() if Path(_arg).is_file() else _arg.strip()
API = "http://127.0.0.1:8000"
DECISAO = {"aceitar": True}
confirmacoes = []


async def handler(message, response_type, params, context):
    confirmacoes.append(message)
    print(f"   [confirmação pedida ao professor → {'ACEITA' if DECISAO['aceitar'] else 'RECUSA'}]")
    if not DECISAO["aceitar"]:
        return ElicitResult(action="decline")
    return ElicitResult(action="accept", content={"publicar": True})


def entrar(email):
    r = httpx.post(f"{API}/api/login", json={"email": email, "senha": "demo1234"})
    r.raise_for_status()
    # O login grava a sessão no cookie httpOnly; a API aceita o mesmo JWT como Bearer.
    return {"authorization": "Bearer " + r.cookies["sessao"]}


def secao(t):
    print(f"\n{'='*70}\n{t}\n{'='*70}")


async def main():
    async with Client(f"{API}/mcp", auth=TOKEN, elicitation_handler=handler) as cli:
        async def tool(nome, **kw):
            r = await cli.call_tool(nome, kw, raise_on_error=False)
            if r.is_error:
                raise AssertionError(f"{nome}: {r.content[0].text}")
            return r.data

        # ---------------- FLUXO A: Vimeo -> plataforma ----------------
        secao("FLUXO A — Vimeo → nosso MCP → backend → plataforma")
        pastas = await tool("listar_videos_vimeo")
        print("pastas no Vimeo:", [p["nome"] for p in pastas["pastas"]])

        videos = (await tool("listar_videos_vimeo", pasta="Cinética"))["videos"]
        print(f"{len(videos)} vídeos em Cinética")

        itens = [{"vimeo_id": v["vimeo_id"], "titulo": v["titulo"], "url": v["url"]} for v in videos]
        rascunho = await tool("importar_questoes_vimeo",
                              turma="Extensivo 2027", capitulo="Cinética", videos=itens)
        rid = rascunho["rascunho_id"]
        print(f"rascunho #{rid} — publicado? {rascunho['publicado']}")
        assert rascunho["publicado"] is False

        antes = await tool("buscar_questoes", turma="Extensivo 2027", capitulo="Cinética",
                           status="PUBLICADO")
        assert antes == [], "nada podia estar publicado ainda"
        print("questões publicadas em Cinética/2027 antes de aprovar:", len(antes))

        pub = await tool("publicar_rascunho", rascunho_id=rid)
        assert pub["publicado"] and confirmacoes
        print(f"publicado por {pub['aprovado_por']} via {pub['aprovado_via']}")

        depois = await tool("buscar_questoes", turma="Extensivo 2027", capitulo="Cinética",
                            status="PUBLICADO")
        print(f"questões publicadas depois: {len(depois)}")
        assert len(depois) == 3

        # ---------------- FLUXO B: segregação ----------------
        secao("FLUXO B — segregação por turma")
        joao, pedro = entrar("joao@aluno.demo"), entrar("pedro@aluno.demo")
        for nome, h in (("João (2027)", joao), ("Pedro (2026)", pedro)):
            arvore = httpx.get(f"{API}/api/aluno/conteudo", headers=h).json()
            print(f"{nome}: ", end="")
            print(" | ".join(
                f"{t['turma']}: " + ", ".join(f"{c['capitulo']}({len(c['questoes'])})"
                                              for c in t["capitulos"])
                for t in arvore))
        arvore_joao = httpx.get(f"{API}/api/aluno/conteudo", headers=joao).json()
        arvore_pedro = httpx.get(f"{API}/api/aluno/conteudo", headers=pedro).json()
        caps_joao = {c["capitulo"] for t in arvore_joao for c in t["capitulos"]}
        caps_pedro = {c["capitulo"] for t in arvore_pedro for c in t["capitulos"]}
        assert "Estequiometria" in caps_joao and "Estequiometria" not in caps_pedro
        print("Pedro NÃO enxerga Estequiometria (exclusiva do 2027): OK")

        # ---------------- FLUXO C: simulado ----------------
        secao("FLUXO C — simulado via MCP → aprovação → aluno responde")
        sim = await tool("criar_simulado_rascunho", turma="Extensivo 2027",
                         titulo="Revisão de Estequiometria",
                         questoes=["1", "3", "5"], capitulo="Estequiometria")
        srid = sim["rascunho_id"]
        print(f"rascunho #{srid}: {sim['resumo']}")
        print("questões:", [q["ordem"] for q in sim["simulado"]["questoes"]])

        visiveis = httpx.get(f"{API}/api/aluno/simulados", headers=joao).json()
        assert visiveis == [], "simulado em rascunho não pode aparecer para o aluno"
        print("aluno vê simulados antes da publicação:", len(visiveis))

        pub = await tool("publicar_rascunho", rascunho_id=srid)
        print("publicado:", pub["simulado_publicado"])

        visiveis = httpx.get(f"{API}/api/aluno/simulados", headers=joao).json()
        print("aluno vê agora:", [(s["titulo"], s["questoes"]) for s in visiveis])
        sid = visiveis[0]["simulado_id"]

        # Pedro (2026) não pode abrir simulado do 2027
        r = httpx.get(f"{API}/api/aluno/simulados/{sid}", headers=pedro)
        print(f"Pedro tentando abrir o simulado do 2027 → HTTP {r.status_code}")
        assert r.status_code == 403

        # João responde: acerta 1 e 3, erra 5. Maria acerta só a 3.
        prova = httpx.get(f"{API}/api/aluno/simulados/{sid}", headers=joao).json()
        gabaritos = {q["questao_id"]: q for q in prova["questoes"]}
        print("respondendo como João e Maria...")

        def responder(h, respostas):
            for qid, letra in respostas.items():
                httpx.post(f"{API}/api/aluno/simulados/{sid}/responder", headers=h,
                           json={"questao_id": qid, "alternativa": letra}).raise_for_status()
            return httpx.post(f"{API}/api/aluno/simulados/{sid}/finalizar", headers=h).json()

        # João acerta as duas primeiras e erra a última; Maria acerta só a do meio.
        ids = list(gabaritos)
        joao_resp = {ids[0]: "C", ids[1]: "B", ids[2]: "A"}
        maria_resp = {ids[0]: "A", ids[1]: "B", ids[2]: "A"}
        rj = responder(joao, joao_resp)
        maria = entrar("maria@aluno.demo")
        rm = responder(maria, maria_resp)
        print(f"João: {rj['acertos']}/{rj['total']} ({rj['percentual']}%)")
        print(f"Maria: {rm['acertos']}/{rm['total']} ({rm['percentual']}%)")

        # ---------------- FLUXO D: analytics ----------------
        secao("FLUXO D — estatísticas de volta pelo MCP")
        d = await tool("buscar_desempenho_aluno", aluno="João")
        print(f"João em '{d['simulado']}': {d['acertos']}/{d['total_questoes']} ({d['percentual']}%)")
        print("erros por tópico:", d["erros_por_topico"])

        e = await tool("buscar_estatisticas_simulado", simulado="Revisão de Estequiometria")
        print(f"\n{e['simulado']} — {e['turma']}")
        print(f"alunos: {e['alunos_responderam']}/{e['alunos_matriculados']} | média {e['media_percentual']}%")
        for q in e["por_questao"]:
            print(f"  Q{q['numero']:02d} {q['percentual_acerto']}% de acerto — {q['topico']}")
        print("maior dificuldade:", e["maior_dificuldade"]["topico"],
              f"({e['maior_dificuldade']['percentual_acerto']}%)")

        secao("OS QUATRO FLUXOS PASSARAM")


asyncio.run(main())
