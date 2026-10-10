package br.com.plataforma.portal;

import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import org.springframework.stereotype.Component;

/**
 * A memória das escritas recentes do portal: é ela que deixa o {@link FiltroDaRepeticao} reconhecer
 * o mesmo pedido chegando duas vezes.
 *
 * <p>"O mesmo pedido" é a mesma pessoa, o mesmo método, o mesmo endereço e o mesmo corpo. Ele só é
 * tratado como repetição em dois casos:
 *
 * <ul>
 *   <li>o primeiro <b>ainda está rodando</b> — o segundo espera e recebe a mesma resposta;
 *   <li>o primeiro <b>acabou de dar certo</b> (dentro da {@link #janela(Duration) janela}) e a
 *       pessoa <b>não escreveu mais nada depois</b> — o segundo recebe a resposta guardada.
 * </ul>
 *
 * <p>A segunda condição é o que separa o clique repetido da intenção de fazer de novo: quem
 * adiciona, remove e adiciona outra vez fez três coisas, e a terceira roda. Quem marca, desmarca e
 * marca também. Só o pedido idêntico <i>logo em seguida, sem nada no meio</i>, é respondido de
 * memória.
 *
 * <p>Mora na memória do processo, não no banco: a janela é de segundos e a API roda numa instância
 * só. Com mais de uma réplica, cada uma teria a sua memória, e a garantia valeria só para os
 * pedidos que caíssem na mesma — aí isto precisa ir para o banco.
 */
@Component
public class Repeticoes {

    /** Quem pediu ("PORTAL:12", ou o IP de quem compra sem conta), o método e o endereço com a query. */
    record Chave(String quem, String metodo, String endereco) {}

    /** A resposta como saiu. {@code corpo} nulo: grande demais para guardar. */
    record Guardada(int status, String tipo, byte[] corpo) {}

    static final class Pedido {
        final String digest;
        final CompletableFuture<Guardada> resposta = new CompletableFuture<>();
        private volatile long terminouEm;
        /** Quantas escritas a pessoa tinha feito quando este terminou: mudou, houve algo no meio. */
        private volatile long vez;

        Pedido(String digest) {
            this.digest = digest;
        }
    }

    private static final int FAXINA_A_CADA = 512;

    private final ConcurrentHashMap<Chave, Pedido> ultimos = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, AtomicLong> escritas = new ConcurrentHashMap<>();
    private final AtomicLong entradas = new AtomicLong();
    /**
     * Dois segundos: pega o clique duplo e o reenvio da rede, que chegam em frações de segundo, e é
     * curto o bastante para ninguém repetir a mesma ação de propósito dentro dele.
     */
    private volatile long janelaEmNanos = Duration.ofSeconds(2).toNanos();

    /**
     * Por quanto tempo, depois de dar certo, o pedido idêntico é respondido de memória. Zero desliga
     * essa parte (o pedido que ainda está rodando continua sendo reconhecido) — é como os testes
     * que repetem um pedido de propósito rodam.
     */
    public void janela(Duration janela) {
        janelaEmNanos = janela.toNanos();
        ultimos.clear();
    }

    /**
     * Registra o pedido que chegou. Devolve {@code meu} se ele deve rodar, ou o pedido anterior, de
     * quem a resposta deve ser repetida.
     */
    Pedido entrar(Chave chave, Pedido meu) {
        var agora = System.nanoTime();
        if (entradas.incrementAndGet() % FAXINA_A_CADA == 0) {
            ultimos.values().removeIf(p -> p.resposta.isDone() && agora - p.terminouEm >= janelaEmNanos);
        }
        return ultimos.compute(chave,
                (c, anterior) -> anterior != null && anterior.digest.equals(meu.digest) && repete(c.quem(), anterior, agora)
                        ? anterior
                        : meu);
    }

    private boolean repete(String quem, Pedido anterior, long agora) {
        if (!anterior.resposta.isDone()) {
            return true;
        }
        var guardada = anterior.resposta.getNow(null);
        return guardada != null && guardada.status() / 100 == 2 && guardada.corpo() != null
                && agora - anterior.terminouEm < janelaEmNanos
                && contagem(quem).get() == anterior.vez;
    }

    /** O pedido rodou: guarda a resposta e solta quem estava esperando por ela. */
    void terminar(Chave chave, Pedido meu, Guardada guardada) {
        meu.vez = contagem(chave.quem()).incrementAndGet();
        meu.terminouEm = System.nanoTime();
        meu.resposta.complete(guardada);
    }

    private AtomicLong contagem(String quem) {
        return escritas.computeIfAbsent(quem, q -> new AtomicLong());
    }
}
