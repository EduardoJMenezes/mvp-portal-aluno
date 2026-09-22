package br.com.plataforma.importacoes;

import br.com.plataforma.comum.RegraDeNegocio;
import java.awt.Color;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.List;
import javax.imageio.ImageIO;

/**
 * A conta de pixel do recorte de figura, que antes era Pillow.
 *
 * <p>Ampliar o print para a escala em que se marca o retângulo, estender o retângulo até o desenho
 * acabar e aparar o branco. Guardar a figura, pôr a referência no texto e tirar a pendência é do
 * {@code QuestoesServico}.
 */
public final class Prints {

    /** O print que se vê já cabe no limite de imagem do modelo, para não ser reduzido de novo. */
    static final int LADO_DA_VISTA = 1568;
    static final int PIXELS_DA_VISTA = 1_150_000;
    static final int AMPLIACAO_MAXIMA = 3;
    /** Escuro o bastante para ser traço de desenho, e não o esfumado da letra vizinha. */
    static final int TRACO = 100;

    private Prints() {}

    public record Tamanho(int largura, int altura) {}

    public static BufferedImage abrir(byte[] bytes) {
        try {
            var lida = ImageIO.read(new ByteArrayInputStream(bytes));
            if (lida == null) {
                throw new RegraDeNegocio("O print não abriu como imagem.");
            }
            return sobreBranco(lida);
        } catch (IOException e) {
            throw new RegraDeNegocio("O print não abriu como imagem.");
        }
    }

    /** A imagem em RGB, com a parte transparente pintada de branco, como na página. */
    static BufferedImage sobreBranco(BufferedImage original) {
        var saida = new BufferedImage(original.getWidth(), original.getHeight(), BufferedImage.TYPE_INT_RGB);
        var g = saida.createGraphics();
        g.setColor(Color.WHITE);
        g.fillRect(0, 0, saida.getWidth(), saida.getHeight());
        g.drawImage(original, 0, 0, null);
        g.dispose();
        return saida;
    }

    public static Tamanho tamanhoDaVista(int largura, int altura) {
        var escala = Math.min(AMPLIACAO_MAXIMA, Math.min((double) LADO_DA_VISTA / Math.max(largura, altura),
                Math.sqrt((double) PIXELS_DA_VISTA / ((double) largura * altura))));
        return new Tamanho((int) (largura * escala), (int) (altura * escala)); // arredondar para cima estoura o limite
    }

    static BufferedImage redimensionar(BufferedImage imagem, int largura, int altura) {
        var saida = new BufferedImage(largura, altura, BufferedImage.TYPE_INT_RGB);
        var g = saida.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC);
        g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
        g.drawImage(imagem, 0, 0, largura, altura, null);
        g.dispose();
        return saida;
    }

    /** O print na escala em que o retângulo do recorte vale, em JPEG: em PNG passaria de 600 KB. */
    public static byte[] vista(byte[] bytes) {
        var imagem = abrir(bytes);
        var tamanho = tamanhoDaVista(imagem.getWidth(), imagem.getHeight());
        if (tamanho.largura() != imagem.getWidth() || tamanho.altura() != imagem.getHeight()) {
            imagem = redimensionar(imagem, tamanho.largura(), tamanho.altura());
        }
        return codificar(imagem, "jpeg");
    }

    /**
     * Recorta a figura de dentro do print. {@code retangulo} é [x0, y0, x1, y1] na escala da vista.
     * O recorte sai do print original: estendido até o desenho acabar, com a margem branca aparada.
     */
    public static byte[] recortar(byte[] bytes, int numero, List<Double> retangulo, boolean estender) {
        var imagem = abrir(bytes);
        var vista = tamanhoDaVista(imagem.getWidth(), imagem.getHeight());
        if (retangulo == null || retangulo.size() != 4 || retangulo.stream().anyMatch(v -> v == null)) {
            throw new RegraDeNegocio("O retângulo vai como [x0, y0, x1, y1], em pixels.");
        }
        double x0 = retangulo.get(0);
        double y0 = retangulo.get(1);
        double x1 = retangulo.get(2);
        double y1 = retangulo.get(3);
        var folga = 10; // o que passa um pouco da borda é só a borda
        if (!(-folga <= x0 && x0 < x1 && x1 <= vista.largura() + folga
                && -folga <= y0 && y0 < y1 && y1 <= vista.altura() + folga)) {
            throw new RegraDeNegocio(("O retângulo %s não cabe no print %d, que tem %d×%d px na escala da "
                    + "vista. Use [x0, y0, x1, y1] com x0 < x1 e y0 < y1.")
                    .formatted(retangulo, numero, vista.largura(), vista.altura()));
        }
        var fx = (double) imagem.getWidth() / vista.largura();
        var fy = (double) imagem.getHeight() / vista.altura();
        var e = (int) Math.min(Math.max(0, Math.round(x0 * fx)), imagem.getWidth() - 1);
        var t = (int) Math.min(Math.max(0, Math.round(y0 * fy)), imagem.getHeight() - 1);
        var d = (int) Math.max(e + 1, Math.min(imagem.getWidth(), Math.round(x1 * fx)));
        var b = (int) Math.max(t + 1, Math.min(imagem.getHeight(), Math.round(y1 * fy)));
        int[] caixa = {e, t, d, b};
        if (estender) {
            caixa = estenderAteODesenho(imagem, caixa);
        }
        var recorte = imagem.getSubimage(caixa[0], caixa[1], caixa[2] - caixa[0], caixa[3] - caixa[1]);
        return apararMargem(recorte);
    }

    /** Máscara do que se afasta do branco mais que a tolerância: traço, letra, foto. */
    static boolean[][] tinta(BufferedImage imagem, int tolerancia) {
        var w = imagem.getWidth();
        var h = imagem.getHeight();
        var mascara = new boolean[h][w];
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                var rgb = imagem.getRGB(x, y);
                var dr = 255 - ((rgb >> 16) & 0xFF);
                var dg = 255 - ((rgb >> 8) & 0xFF);
                var db = 255 - (rgb & 0xFF);
                // A luminância da diferença, como o Pillow faz ao converter para "L".
                var l = (299 * dr + 587 * dg + 114 * db) / 1000;
                mascara[y][x] = l > tolerancia;
            }
        }
        return mascara;
    }

    /** O traço engordado em 1 px: atravessa o vão entre a ligação e o átomo, mas não chega à linha vizinha. */
    static boolean[][] engordar(boolean[][] mascara) {
        var h = mascara.length;
        var w = h == 0 ? 0 : mascara[0].length;
        var saida = new boolean[h][w];
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                if (!mascara[y][x]) {
                    continue;
                }
                for (int dy = -1; dy <= 1; dy++) {
                    for (int dx = -1; dx <= 1; dx++) {
                        var yy = y + dy;
                        var xx = x + dx;
                        if (yy >= 0 && yy < h && xx >= 0 && xx < w) {
                            saida[yy][xx] = true;
                        }
                    }
                }
            }
        }
        return saida;
    }

    private static boolean temTinta(boolean[][] m, int x0, int y0, int x1, int y1) {
        for (int y = Math.max(0, y0); y < Math.min(m.length, y1); y++) {
            for (int x = Math.max(0, x0); x < Math.min(m[0].length, x1); x++) {
                if (m[y][x]) {
                    return true;
                }
            }
        }
        return false;
    }

    /** Empurra cada borda do retângulo enquanto ela corta traço, até uma faixa em branco. */
    static int[] estenderAteODesenho(BufferedImage imagem, int[] caixa) {
        var traco = engordar(tinta(imagem, TRACO));
        int e = caixa[0];
        int t = caixa[1];
        int d = caixa[2];
        int b = caixa[3];
        while (true) {
            var antes = new int[] {e, t, d, b};
            if (t > 0 && temTinta(traco, e, t, d, t + 1)) {
                t--;
            }
            if (b < imagem.getHeight() && temTinta(traco, e, b - 1, d, b)) {
                b++;
            }
            if (e > 0 && temTinta(traco, e, t, e + 1, b)) {
                e--;
            }
            if (d < imagem.getWidth() && temTinta(traco, d - 1, t, d, b)) {
                d++;
            }
            if (antes[0] == e && antes[1] == t && antes[2] == d && antes[3] == b) {
                return new int[] {e, t, d, b};
            }
        }
    }

    /** Recorta o branco em volta do desenho, com uma folga de 8 px para ele não encostar na borda. */
    static byte[] apararMargem(BufferedImage imagem) {
        var m = tinta(imagem, 40);
        int minX = Integer.MAX_VALUE;
        int minY = Integer.MAX_VALUE;
        int maxX = -1;
        int maxY = -1;
        for (int y = 0; y < m.length; y++) {
            for (int x = 0; x < m[0].length; x++) {
                if (m[y][x]) {
                    minX = Math.min(minX, x);
                    minY = Math.min(minY, y);
                    maxX = Math.max(maxX, x);
                    maxY = Math.max(maxY, y);
                }
            }
        }
        if (maxX < 0) {
            return codificar(imagem, "png");
        }
        var folga = 8;
        var recorte = imagem.getSubimage(minX, minY, maxX - minX + 1, maxY - minY + 1);
        var saida = new BufferedImage(recorte.getWidth() + 2 * folga, recorte.getHeight() + 2 * folga,
                BufferedImage.TYPE_INT_RGB);
        var g = saida.createGraphics();
        g.setColor(Color.WHITE);
        g.fillRect(0, 0, saida.getWidth(), saida.getHeight());
        g.drawImage(recorte, folga, folga, null);
        g.dispose();
        return codificar(saida, "png");
    }

    static byte[] codificar(BufferedImage imagem, String formato) {
        try {
            var saida = new ByteArrayOutputStream();
            if (!ImageIO.write(imagem, formato, saida)) {
                throw new IllegalStateException("ImageIO sem " + formato);
            }
            return saida.toByteArray();
        } catch (IOException e) {
            throw new IllegalStateException("falha ao codificar " + formato, e);
        }
    }
}
