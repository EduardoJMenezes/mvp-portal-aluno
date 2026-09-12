"""Leitura do número da questão no título do vídeo do Vimeo.

A API não expõe ordem manual de pasta (ver `docs/vimeo-integracao`, gap 1), e o
acervo real devolve os vídeos fora de ordem. Quem carrega a ordem é o título:
no acervo de 2026 os vídeos se chamam `Q04`, `Q30`, `Q52`.

Esse número é o da **apostila**, e é assim que o professor e o aluno se referem
à questão. Por isso ele vira o número da questão no capítulo, em vez de uma
contagem sequencial nossa: senão a mesma questão teria dois números diferentes
(a plataforma diria Q01 para a Q04 da apostila).

Padrões aceitos, nesta ordem:

    Q04, Q 4, q52                  -> 4, 4, 52
    Questão 12, Questao 12         -> 12
    001 - alguma coisa             -> 1
"""

from __future__ import annotations

import re
from dataclasses import dataclass

_PADROES: tuple[tuple[str, re.Pattern[str]], ...] = (
    ("Q + número", re.compile(r"\bQ\s*0*(\d{1,4})\b", re.IGNORECASE)),
    ("questão + número", re.compile(r"\bquest[aã]o\s*0*(\d{1,4})\b", re.IGNORECASE)),
    ("prefixo numérico", re.compile(r"^\s*0*(\d{1,4})\s*[-–—.)]")),
)


@dataclass(frozen=True)
class NumeroInferido:
    """O número lido de um título, e de que jeito ele foi lido.

    `confianca` é o que o preview mostra ao professor antes de ele aprovar
    (seção 38 da especificação de longo prazo): quem decide é quem revisa.
    """

    numero: int | None
    padrao: str | None
    confianca: str  # "alta", "baixa" ou "nenhuma"


def inferir_numero(titulo: str | None) -> NumeroInferido:
    texto = (titulo or "").strip()
    if not texto:
        return NumeroInferido(None, None, "nenhuma")
    for nome, padrao in _PADROES:
        encontrado = padrao.search(texto)
        if encontrado:
            # Título que é só o código ("Q04") não deixa dúvida; título longo
            # com um número no meio pode ser outra coisa.
            confianca = "alta" if len(texto) <= 12 or nome != "Q + número" else "baixa"
            return NumeroInferido(int(encontrado.group(1)), nome, confianca)
    return NumeroInferido(None, None, "nenhuma")


def numero_do_titulo(titulo: str | None) -> int | None:
    """Só o número, para quem não precisa saber de onde ele veio."""
    return inferir_numero(titulo).numero
