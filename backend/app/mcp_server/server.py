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
#
# Para criar, editar e remover, o preview no chat é a única barreira, por
# decisão do professor: o formulário de confirmação que existia era respondido
# pelo próprio app, sem chegar a ele (ver tools_estrutura.py).
INSTRUCOES = """
Servidor MCP da plataforma educacional. Por aqui um professor ou gerenciador
monta o curso (módulos, sub-módulos e vídeos do Vimeo), cadastra questões,
monta simulados e lê estatísticas.

REGRA QUE NÃO SE NEGOCIA: nunca publique nem aplique definitivamente alterações
em conteúdo pedagógico sem aprovação explícita do usuário.

Conteúdo novo nasce como RASCUNHO:
  1. consultar (listar_turmas, listar_modulos, listar_pastas_vimeo, buscar_questoes);
  2. propor (importar_pasta_vimeo_como_rascunho, criar_questao_rascunho,
     criar_simulado_rascunho);
  3. mostrar ao professor o que foi proposto e perguntar;
  4. só então publicar_rascunho.

Criar, editar, remover e classificar (criar_modulo, criar_submodulo,
editar_modulo, editar_item, remover_do_curso, cadastrar_assunto,
classificar_videos) alteram o curso NA HORA, sem rascunho. Antes de chamar
qualquer uma delas, mostre no chat um preview de como vai ficar — o antes e o
depois, e quantos itens publicados são afetados — e só chame depois do ok do
professor, dado no próprio chat.

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
