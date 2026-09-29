package br.com.plataforma.materiais;

/** O PDF que uma aula leva (decisão 0013), como sai para a tela: abre no leitor de materiais. */
public record MaterialLigado(Integer materialId, String titulo) {

    public static MaterialLigado de(Material m) {
        return m == null ? null : new MaterialLigado(m.getId(), m.getTitulo());
    }
}
