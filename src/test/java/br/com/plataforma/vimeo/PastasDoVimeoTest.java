package br.com.plataforma.vimeo;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;

/** O que a tela de montar o curso lê do Vimeo: a hierarquia das pastas e a pasta inteira, em ordem. */
class PastasDoVimeoTest {

    /** Um Vimeo de mentira com o desenho do acervo real: ano, depois o curso, depois o capítulo. */
    private static final class Acervo implements Vimeo {

        private final List<Video> videos;

        Acervo(String... titulos) {
            this.videos = java.util.stream.IntStream.range(0, titulos.length)
                    .mapToObj(i -> video(String.valueOf(100 + i), titulos[i], "available", "public"))
                    .toList();
        }

        Acervo(List<Video> videos) {
            this.videos = videos;
        }

        static Video video(String id, String titulo, String status, String embed) {
            return new Video(id, titulo, "https://vimeo.com/" + id, null, 60, null, null,
                    "https://player.vimeo.com/video/" + id + "?h=abc", status, "available".equals(status),
                    "unlisted", embed, null, "3");
        }

        @Override
        public boolean real() {
            return true;
        }

        @Override
        public List<Pasta> listarPastas() {
            return List.of(
                    new Pasta("1", "2026", "/users/9/projects/1", null, true, 0, 40),
                    new Pasta("2", "EXTENSIVO", "/users/9/projects/2", "/users/9/projects/1", true, 0, 40),
                    new Pasta("3", "K01", "/users/9/projects/3", "/users/9/projects/2", false, 40, 40));
        }

        @Override
        public Pasta obterPasta(String pastaId) {
            return listarPastas().stream().filter(p -> p.id().equals(pastaId)).findFirst().orElseThrow();
        }

        @Override
        public List<Video> listarVideosDaPasta(String pastaId) {
            return videos;
        }

        @Override
        public Video obterVideo(String vimeoId) {
            throw new UnsupportedOperationException();
        }

        @Override
        public List<Video> listarVideos(String pastaId, String busca, int limite) {
            throw new UnsupportedOperationException();
        }
    }

    private static ImportacaoVimeo com(Vimeo vimeo) {
        return new ImportacaoVimeo(vimeo, null, null, null);
    }

    @Test
    void oVideoDizEmQualPastaMora() {
        // O Vimeo manda a pasta do vídeo em parent_project; a busca pelo título mostra de onde ele veio.
        var video = VimeoReal.video(java.util.Map.of("uri", "/videos/901", "name", "Q01",
                "parent_project", java.util.Map.of("uri", "/users/9/projects/55", "name", "K01")), null);

        assertThat(video.pastaId()).isEqualTo("55");
        assertThat(video.pasta()).isEqualTo("K01");
        assertThat(VimeoReal.video(java.util.Map.of("uri", "/videos/902", "name", "Solto"), null).pastaId()).isNull();
    }

    @Test
    void cadaPastaDizEmQualPastaMora() {
        var pastas = com(new Acervo()).listarPastas(null, 100).pastas();

        assertThat(pastas).extracting(ImportacaoVimeo.PastaNaLista::paiId).containsExactly(null, "1", "2");
        assertThat(pastas).extracting(ImportacaoVimeo.PastaNaLista::dentroDe).containsExactly(null, "2026", "EXTENSIVO");
    }

    @Test
    void aPastaVemNaOrdemDoNumeroDoTitulo() {
        // O Vimeo devolve fora de ordem; quem carrega a ordem é o título.
        var videos = com(new Acervo("Q10", "Q02", "Q01")).videosDaPasta("3").videos();

        assertThat(videos).extracting(ImportacaoVimeo.VideoDaPasta::titulo).containsExactly("Q01", "Q02", "Q10");
    }

    @Test
    void semNumeroDeQuestaoValeAOrdemNaturalDoTitulo() {
        var videos = com(new Acervo("Aula 10 - Revisão", "Aula 2 - Mol", "Aula 1 - Introdução", "Abertura"))
                .videosDaPasta("3").videos();

        assertThat(videos).extracting(ImportacaoVimeo.VideoDaPasta::titulo)
                .containsExactly("Abertura", "Aula 1 - Introdução", "Aula 2 - Mol", "Aula 10 - Revisão");
        // Título sem "Q04" não é problema aqui: o aviso de número é só da importação por faixa.
        assertThat(videos).allSatisfy(v -> assertThat(v.avisos()).isEmpty());
    }

    @Test
    void oVideoQueOAlunoNaoConseguiriaAssistirVemComAviso() {
        var videos = com(new Acervo(List.of(
                Acervo.video("1", "Q01", "transcoding", "public"),
                Acervo.video("2", "Q02", "available", "private"),
                Acervo.video("3", "Q03", "available", "public")))).videosDaPasta("3").videos();

        assertThat(videos.get(0).avisos()).singleElement().asString().contains("ainda não está pronto");
        assertThat(videos.get(1).avisos()).singleElement().asString().contains("embed está desativado");
        assertThat(videos.get(2).avisos()).isEmpty();
        // O endereço do player vai como o Vimeo devolveu, com o hash de privacidade.
        assertThat(videos.get(2).embedUrl()).isEqualTo("https://player.vimeo.com/video/3?h=abc");
    }
}
