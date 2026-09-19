"""O comando de partida: é ele que impede a configuração perigosa.

Multiplicar processos com o /mcp dentro quebra a sessão do conector, e a falha
não se anuncia — o Claude simplesmente perde o servidor. Por isso a regra mora
aqui, e não só na documentação.
"""

import pytest

from app.servir import plano


def test_sem_argumento_e_o_de_sempre():
    """O padrão do Dockerfile não pode mudar comportamento de deploy nenhum."""
    p = plano([], ambiente={})
    assert (p.papel, p.workers, p.porta, p.migrar) == ("tudo", 1, 8000, True)


def test_o_ambiente_serve_de_padrao():
    p = plano([], ambiente={"PAPEL": "portal", "WEB_CONCURRENCY": "4", "PORT": "9000"})
    assert (p.papel, p.workers, p.porta) == ("portal", 4, 9000)


def test_o_argumento_ganha_do_ambiente():
    p = plano(["mcp"], ambiente={"PAPEL": "portal"})
    assert p.papel == "mcp"


def test_varios_processos_so_com_o_portal():
    p = plano(["portal", "--workers", "8"], ambiente={})
    assert p.workers == 8


@pytest.mark.parametrize("papel", ["mcp", "tudo"])
def test_multiplicar_processo_com_mcp_dentro_e_recusado(papel, capsys):
    """A falha que isto evita é silenciosa: o pedido cai no processo errado."""
    with pytest.raises(SystemExit):
        plano([papel, "--workers", "4"], ambiente={})
    assert "só vale com o papel 'portal'" in capsys.readouterr().err


def test_papel_inventado_e_recusado():
    with pytest.raises(SystemExit):
        plano(["balcao"], ambiente={})


def test_zero_processos_nao_existe():
    with pytest.raises(SystemExit):
        plano(["portal", "--workers", "0"], ambiente={})
