"""Quem está pedindo — sem dizer por qual porta entrou.

Os services recebem sempre uma `Identidade`. Se ela veio de um login no portal
ou de um token do MCP é problema da borda, não das regras: é isso que permite
o mesmo service atender REST e MCP sem duplicar autorização (seção 19).
"""

from __future__ import annotations

from dataclasses import dataclass

from app.errors import NaoAutorizado
from app.models import Papel


class Canal:
    PORTAL = "PORTAL"
    MCP = "MCP"


@dataclass(frozen=True)
class Identidade:
    usuario_id: int
    nome: str
    email: str
    papel: str
    canal: str

    @property
    def e_aluno(self) -> bool:
        return self.papel == Papel.ALUNO

    @property
    def e_operador(self) -> bool:
        return self.papel in Papel.OPERADORES

    def exigir_operador(self) -> None:
        """Barra aluno em operação administrativa (seção 4)."""
        if not self.e_operador:
            raise NaoAutorizado(
                f"'{self.nome}' tem papel {self.papel}; esta operação é de ADMIN ou GERENCIADOR."
            )

    def exigir_humano_no_portal(self, acao: str) -> None:
        """Exige que a ação venha de uma sessão de navegador, não do MCP.

        Usado nas transições que precisam de um humano de fato clicando —
        aprovar conteúdo, por exemplo. Um agente com token de MCP não passa
        por aqui, e é essa a intenção.
        """
        if self.canal != Canal.PORTAL:
            raise NaoAutorizado(
                f"{acao} exige sessão do portal (professor autenticado no navegador); "
                f"canal atual: {self.canal}."
            )
