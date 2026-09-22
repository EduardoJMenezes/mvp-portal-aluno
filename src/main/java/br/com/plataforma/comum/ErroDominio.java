package br.com.plataforma.comum;

/**
 * Base dos erros previstos do domínio.
 *
 * <p>Existem para que os serviços digam o que houve sem saber se quem chamou foi o portal ou o
 * MCP. Cada borda traduz para o seu formato.
 *
 * <p>É {@code sealed} de propósito: o {@code switch} que traduz erro em status é exaustivo, então
 * um tipo novo aqui não compila enquanto alguém não decidir o status dele.
 */
public abstract sealed class ErroDominio extends RuntimeException
        permits NaoEncontrado, NaoAutorizado, RegraDeNegocio, CredenciaisInvalidas, MuitasTentativas {

    protected ErroDominio(String mensagem) {
        super(mensagem);
    }
}
