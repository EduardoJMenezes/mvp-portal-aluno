package br.com.plataforma.rascunhos;

/** Por onde veio o "pode publicar" do humano. */
public enum ViaAprovacao {
    /** Professor logado no navegador, em Admin › Rascunhos. */
    PORTAL,
    /** Formulário de confirmação do cliente MCP, aceito pelo usuário. */
    ELICITATION_MCP
}
