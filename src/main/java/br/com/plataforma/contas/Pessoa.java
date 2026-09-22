package br.com.plataforma.contas;

/** O mínimo de alguém numa lista: quem alcança um material, quem está numa aula. */
public record Pessoa(Integer id, String nome, String email) {

    public static Pessoa de(Usuario u) {
        return new Pessoa(u.getId(), u.getNome(), u.getEmail());
    }
}
