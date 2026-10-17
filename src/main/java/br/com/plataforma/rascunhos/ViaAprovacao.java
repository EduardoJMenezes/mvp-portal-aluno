package br.com.plataforma.rascunhos;

/** Por onde veio o "pode publicar" do humano. */
public enum ViaAprovacao {
    /** Professor logado no navegador, no lugar onde a coisa mora (Banco, página do simulado, curso). */
    PORTAL,
    /** Formulário de confirmação do cliente MCP, aceito pelo usuário. */
    ELICITATION_MCP,
    /**
     * Montagem do curso pelo Claude: vídeos e linhas de aula saem publicados sem pedir o ok, e o
     * professor confere pelo link do módulo. Grava quem estava operando, não uma aprovação.
     */
    MONTAGEM_DO_CURSO
}
