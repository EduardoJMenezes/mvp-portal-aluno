package br.com.plataforma.comum;

public enum Papel {
    ADMIN,
    GERENCIADOR,
    ALUNO;

    /** Quem pode operar o MCP administrativo (seção 4). */
    public boolean eOperador() {
        return this == ADMIN || this == GERENCIADOR;
    }
}
