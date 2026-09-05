package com.example.paralelismovirthr.examples;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

/**
 * VT imperativo quando você controla o I/O.
 * CF + executor de VT quando precisa compor APIs, timeout e fallback.
 * CPU intenso: platform / ForkJoinPool.
 */
public class VirtualThreadsComCompletableFutureExample {

    private static final ThreadFactory VT_FACTORY = Thread.ofVirtual().name("vt-cf-", 0).factory();

    public static void main(String[] args) {
        VirtualThreadsComCompletableFutureExample example = new VirtualThreadsComCompletableFutureExample();
        example.exemploCombinadoVtComCf();
        example.exemploFanOutComCf();
        System.out.println(example.buscarUsuarioSimples());
        System.out.println(example.buscarUsuarioComFallback());
        System.out.println(example.buscarDadosCompostos());
    }

    public void exemploCombinadoVtComCf() {
        try (ExecutorService executor = Executors.newThreadPerTaskExecutor(VT_FACTORY)) {
            CompletableFuture<String> task1 = CompletableFuture.supplyAsync(() -> {
                simularIoBloqueante(1000);
                return "Resultado A";
            }, executor);

            CompletableFuture<String> task2 = CompletableFuture.supplyAsync(() -> {
                simularIoBloqueante(1500);
                return "Resultado B";
            }, executor);

            task1.thenCombine(task2, (a, b) -> a + " e " + b)
                    .orTimeout(3, TimeUnit.SECONDS)
                    .exceptionally(ex -> {
                        System.err.println("Erro durante a combinação: " + ex.getMessage());
                        return "Fallback Resultado";
                    })
                    .thenAccept(resultado -> System.out.println("Combinação concluída: " + resultado))
                    .join();
        }
    }

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
                            .orTimeout(3, TimeUnit.SECONDS)
                            .exceptionally(ex -> "Fallback " + i))
                    .toList();

            CompletableFuture.allOf(futures.toArray(new CompletableFuture[0]))
                    .orTimeout(5, TimeUnit.SECONDS)
                    .handle((res, ex) -> futures.stream()
                            .map(cf -> cf.getNow("Fallback"))
                            .collect(Collectors.joining(", ")))
                    .thenAccept(resultados -> System.out.println("Resultados do Fan-out: " + resultados))
                    .join();
        }
    }

    public String buscarUsuarioSimples() {
        try (ExecutorService executor = Executors.newThreadPerTaskExecutor(VT_FACTORY)) {
            return executor.submit(() -> {
                simularIoBloqueante(500);
                return "Usuario Simples";
            }).get(2, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return "Erro";
        } catch (ExecutionException | TimeoutException e) {
            System.err.println("Erro na busca simples: " + e.getMessage());
            return "Erro";
        }
    }

    public String buscarUsuarioComFallback() {
        try (ExecutorService executor = Executors.newThreadPerTaskExecutor(VT_FACTORY)) {
            return CompletableFuture.supplyAsync(() -> {
                        simularIoBloqueante(3000);
                        return "Usuario Original";
                    }, executor)
                    .orTimeout(1, TimeUnit.SECONDS)
                    .exceptionally(ex -> {
                        System.err.println("Timeout atingido, acionando fallback: " + ex.getMessage());
                        return "Usuario Fallback";
                    })
                    .join();
        }
    }

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
