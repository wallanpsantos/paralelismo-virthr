package com.example.paralelismovirthr.examples;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

/**
 * Exemplo demonstrando a integração ideal entre Virtual Threads e CompletableFuture.
 * <p>
 * Árvore de decisão:
 * - I/O-bound + você controla o código -> Virtual Thread imperativo (código mais simples de ler e debugar).
 * - Compor APIs async heterogêneas / fallback / timeouts compostos -> CompletableFuture + VT executor.
 * - CPU-bound intenso -> platform threads / ForkJoinPool tradicional.
 */
public class VirtualThreadsComCompletableFutureExample {

    // Sempre nomeie as Virtual Threads para observabilidade
    private static final ThreadFactory VT_FACTORY = Thread.ofVirtual().name("vt-cf-", 0).factory();

    /**
     * Mostra como usar CompletableFuture.supplyAsync() com um executor de Virtual Threads
     * para executar chamadas bloqueantes e combinar os resultados.
     */
    public void exemploCombinadoVtComCf() {
        try (ExecutorService executor = Executors.newThreadPerTaskExecutor(VT_FACTORY)) {

            // Nunca usar a commonPool para tarefas bloqueantes, usar executor de VTs explicitamente
            CompletableFuture<String> task1 = CompletableFuture.supplyAsync(() -> {
                simularIoBloqueante(1000); // Simulando uma chamada I/O
                return "Resultado A";
            }, executor);

            CompletableFuture<String> task2 = CompletableFuture.supplyAsync(() -> {
                simularIoBloqueante(1500); // Simulando outra chamada I/O
                return "Resultado B";
            }, executor);

            task1.thenCombine(task2, (a, b) -> a + " e " + b)
                    .orTimeout(2, TimeUnit.SECONDS) // Sempre defina timeout em chamadas remotas/bloqueantes
                    .exceptionally(ex -> {
                        // Tratamento terminal de erros OBRIGATÓRIO em cadeias de CF
                        System.err.println("Erro durante a combinação: " + ex.getMessage());
                        return "Fallback Resultado";
                    })
                    .thenAccept(resultado -> System.out.println("Combinação concluída: " + resultado))
                    .join();
        }
    }

    /**
     * Padrão Fan-out: Cria uma lista de tarefas, executa todas em Virtual Threads,
     * aguarda com allOf e coleta os resultados, com timeout e tratamento de erros apropriados.
     */
    public void exemploFanOutComCf() {
        try (ExecutorService executor = Executors.newThreadPerTaskExecutor(VT_FACTORY)) {

            List<CompletableFuture<String>> futures = IntStream.range(1, 6)
                    .mapToObj(i -> CompletableFuture.supplyAsync(() -> {
                                simularIoBloqueante(500 + (i * 100));
                                if (i == 4) {
                                    throw new RuntimeException("Falha simulada na task 4");
                                }
                                return "Dado " + i;
                            }, executor)
                            .orTimeout(3, TimeUnit.SECONDS))
                    .toList();

            CompletableFuture<Void> allOf = CompletableFuture.allOf(futures.toArray(new CompletableFuture[0]));

            allOf.handle((res, ex) -> {
                        // Tratamento terminal OBRIGATÓRIO
                        if (ex != null) {
                            System.err.println("Erro em pelo menos uma das tarefas do fan-out: " + ex.getMessage());
                        }
                        return futures.stream()
                                .map(cf -> {
                                    try {
                                        return cf.getNow("Fallback");
                                    } catch (Exception e) {
                                        return "Falha";
                                    }
                                })
                                .collect(Collectors.joining(", "));
                    })
                    .thenAccept(resultados -> System.out.println("Resultados do Fan-out: " + resultados))
                    .join();
        }
    }

    // -------------------------------------------------------------------------
    // Exemplo de quando usar cada abordagem (Árvore de decisão na prática)
    // -------------------------------------------------------------------------

    /**
     * Abordagem 1: I/O simples (Imperativo com Virtual Threads).
     * Quando você controla o fluxo e faz apenas I/O,
     * o código imperativo executado em uma Virtual Thread é mais limpo.
     */
    public String buscarUsuarioSimples() {
        try (ExecutorService executor = Executors.newThreadPerTaskExecutor(VT_FACTORY)) {
            return executor.submit(() -> {
                simularIoBloqueante(500);
                return "Usuario Simples";
            }).get(); // Bloqueia a VT, o que é barato e desenhado para isso
        } catch (Exception e) {
            System.err.println("Erro na busca simples: " + e.getMessage());
            return "Erro";
        }
    }

    /**
     * Abordagem 2: Composição e Fallback (CompletableFuture + VT).
     * Útil quando precisamos compor fluxos ou tratar timeouts de forma granular.
     */
    public String buscarUsuarioComFallback() {
        try (ExecutorService executor = Executors.newThreadPerTaskExecutor(VT_FACTORY)) {
            return CompletableFuture.supplyAsync(() -> {
                        simularIoBloqueante(3000);
                        return "Usuario Original";
                    }, executor)
                    .orTimeout(1, TimeUnit.SECONDS) // Gatilho de timeout
                    .exceptionally(ex -> {
                        System.err.println("Timeout atingido, acionando fallback: " + ex.getMessage());
                        return "Usuario Fallback";
                    })
                    .join();
        }
    }

    /**
     * Abordagem 3: Combinando múltiplas fontes heterogêneas.
     * CompletableFuture e VTs juntos formam a melhor solução para este caso.
     */
    public String buscarDadosCompostos() {
        try (ExecutorService executor = Executors.newThreadPerTaskExecutor(VT_FACTORY)) {
            CompletableFuture<String> user = CompletableFuture.supplyAsync(() -> {
                simularIoBloqueante(500);
                return "User123";
            }, executor);

            CompletableFuture<String> preferences = CompletableFuture.supplyAsync(() -> {
                simularIoBloqueante(600);
                return "Modo Escuro";
            }, executor);

            return user.thenCombine(preferences, (u, p) -> u + " -> " + p)
                    .orTimeout(2, TimeUnit.SECONDS)
                    .exceptionally(ex -> "Erro na composição: " + ex.getMessage())
                    .join();
        }
    }

    private void simularIoBloqueante(int millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
