package br.com.plataforma.comum;

/**
 * Quem está pedindo — sem dizer por qual porta entrou.
 *
 * <p>Os serviços recebem sempre uma {@code Identidade}. Se ela veio do portal ou do MCP é
 * problema da borda, não das regras: é isso que permite o mesmo serviço atender os dois sem
 * duplicar autorização.
 */
public record Identidade(Integer usuarioId, String nome, String email, Papel papel, Canal canal,
        Integer turmaDaPrevia) {

    public Identidade(Integer usuarioId, String nome, String email, Papel papel, Canal canal) {
        this(usuarioId, nome, email, papel, canal, null);
    }

    /**
     * O professor vendo o portal como um aluno da turma (a "prévia do aluno"). Só um operador
     * entra nela, e só pelas rotas do aluno: aí ele <b>é</b> aluno daquela turma para os serviços
     * — as mesmas regras de segregação valem —, mas nada é gravado em nome de ninguém.
     */
    public Identidade emPreviaDa(Integer turmaId) {
        if (!papel.eOperador()) {
            throw new NaoAutorizado("Só ADMIN ou GERENCIADOR veem o portal como aluno.");
        }
        return new Identidade(usuarioId, nome, email, papel, canal, turmaId);
    }

    public boolean emPrevia() {
        return turmaDaPrevia != null;
    }

    public boolean eAluno() {
        return papel == Papel.ALUNO || emPrevia();
    }

    public boolean eOperador() {
        return papel.eOperador() && !emPrevia();
    }

    /** Toda escrita do aluno passa aqui: na prévia, o professor olha e não grava. */
    public void exigirQueGrave(String acao) {
        if (emPrevia()) {
            throw new NaoAutorizado("Na prévia do aluno nada é gravado: %s fica para o aluno de verdade."
                    .formatted(acao));
        }
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
