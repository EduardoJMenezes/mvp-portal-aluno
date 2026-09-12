"""Instância do servidor MCP da plataforma.

Fica num módulo só dela para que `tools.py` possa importar `mcp` sem criar
import circular com o `main`.
"""

from __future__ import annotations

import os

from fastmcp import FastMCP

from app.mcp_server.auth import construir_auth

# Estas instruções são a camada de bom comportamento — úteis, e insuficientes
# por si só. A garantia de verdade está no backend: publicar exige aprovação
# humana gravada em drafts.aprovado_por_id (ver services/publicacao.py).
INSTRUCOES = """
Servidor MCP da plataforma educacional. Por aqui um professor ou gerenciador
consulta o acervo, cadastra questões, monta simulados e lê estatísticas.

REGRA QUE NÃO SE NEGOCIA: nunca publique nem aplique definitivamente alterações
em conteúdo pedagógico sem aprovação explícita do usuário. Toda escrita nasce
como RASCUNHO. Sempre apresente primeiro o rascunho/resumo para revisão e
espere o professor decidir.

Fluxo esperado:
  1. consultar (listar_turmas, listar_capitulos, buscar_questoes, listar_videos_vimeo);
  2. propor (criar_questao_rascunho, importar_questoes_vimeo, criar_simulado_rascunho);
  3. mostrar ao professor o que foi proposto e perguntar;
  4. só então publicar_rascunho.

Ao falar de turmas, capítulos, simulados e alunos, use os nomes que o professor
usa ("Extensivo 2027", "Estequiometria", "João") — as tools resolvem para os
ids sozinhas. Se algo estiver ambíguo, a tool devolve as opções: repasse a
pergunta ao professor em vez de escolher por ele.
""".strip()

mcp = FastMCP(
    name="plataforma-educacional",
    version=os.getenv("MCP_SERVER_VERSION", "0.1.0"),
    instructions=INSTRUCOES,
    auth=construir_auth(),
)
