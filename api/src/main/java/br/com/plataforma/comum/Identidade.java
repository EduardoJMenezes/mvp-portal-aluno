package br.com.plataforma.comum;

/**
 * Quem está pedindo — sem dizer por qual porta entrou.
 *
 * <p>Os serviços recebem sempre uma {@code Identidade}. Se ela veio do portal ou do MCP é
 * problema da borda, não das regras: é isso que permite o mesmo serviço atender os dois sem
 * duplicar autorização.
 */
public record Identidade(Integer usuarioId, String nome, String email, Papel papel, Canal canal) {

    public boolean eAluno() {
        return papel == Papel.ALUNO;
    }

    public boolean eOperador() {
        return papel.eOperador();
    }

    /** Barra aluno em operação administrativa (seção 4). */
    public void exigirOperador() {
        if (!eOperador()) {
            throw new NaoAutorizado(
                    "'%s' tem papel %s; esta operação é de ADMIN ou GERENCIADOR.".formatted(nome, papel));
        }
    }

    /**
     * Exige que a ação venha de uma sessão de navegador, não do MCP.
     *
     * <p>Usado nas transições que precisam de um humano de fato clicando — aprovar conteúdo, por
     * exemplo. Um agente com credencial de MCP não passa por aqui, e é essa a intenção.
     */
    public void exigirHumanoNoPortal(String acao) {
        if (canal != Canal.PORTAL) {
            throw new NaoAutorizado(
                    "%s exige sessão do portal (professor autenticado no navegador); canal atual: %s."
                            .formatted(acao, canal));
        }
    }
}
