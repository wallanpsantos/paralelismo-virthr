package com.example.paralelismovirthr.examples;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;

/**
 * CompletableFuture orquestra resultados. Não substitui VT em I/O simples.
 * <p>
 * Sempre: executor explícito em I/O, timeout, handler terminal.
 * {@code orTimeout}/{@code completeOnTimeout} completam o future; a tarefa em
 * andamento não é cancelada automaticamente — bound real exige timeout no cliente HTTP/JDBC
 * ou {@code Future.cancel(true)} no trabalho que você controla.
 * {@code anyOf} também não cancela o perdedor.
 */
public class CompletableFutureBasicsExample {

    private static final ThreadFactory factory = Thread.ofVirtual().name("vt-cf-basics-", 1).factory();

    public static void main(String[] args) {
        try (ExecutorService executor = Executors.newThreadPerTaskExecutor(factory)) {
            exemploSupplyAsync(executor);
            exemploEncadeamento(executor);
            exemploTratamentoDeErros(executor);
            exemploTimeout(executor);
            exemploCombinacoes(executor);
        }
    }

    private static void exemploSupplyAsync(ExecutorService executor) {
        System.out.println("\n--- Exemplo: supplyAsync ---");
        CompletableFuture<String> future = CompletableFuture.supplyAsync(() -> {
                    simulateSlowOperation(1000);
                    return "Resultado da operação lenta";
                }, executor)
                .orTimeout(2, TimeUnit.SECONDS)
                .exceptionally(ex -> "Erro: " + ex.getMessage());

        System.out.println("Operação iniciada. Fazendo outras coisas...");
        String result = future.join();
        System.out.println("Resultado: " + result);
    }

    private static void exemploEncadeamento(ExecutorService executor) {
        System.out.println("\n--- Exemplo: Encadeamento ---");
        CompletableFuture.supplyAsync(() -> {
                    simulateSlowOperation(500);
                    return 10;
                }, executor)
                .orTimeout(1, TimeUnit.SECONDS)
                .thenApply(valor -> valor * 2)
                .thenApply(valor -> "Valor processado: " + valor)
                .thenAccept(System.out::println)
                .exceptionally(ex -> {
                    System.err.println("Falha no pipeline: " + ex.getMessage());
                    return null;
                })
                .join();
    }

    private static void exemploTratamentoDeErros(ExecutorService executor) {
        System.out.println("\n--- Exemplo: Tratamento de Erros ---");

        String resultExceptionally = CompletableFuture.<String>supplyAsync(() -> {
                    throw new RuntimeException("Erro forçado");
                }, executor)
                .orTimeout(1, TimeUnit.SECONDS)
                .exceptionally(ex -> "Fallback devido a: " + ex.getMessage())
                .join();
        System.out.println("Com exceptionally: " + resultExceptionally);

        String resultHandle = CompletableFuture.supplyAsync(() -> {
                    simulateSlowOperation(100);
                    return "Sucesso!";
                }, executor)
                .orTimeout(1, TimeUnit.SECONDS)
                .handle((res, ex) -> ex != null ? "Falhou: " + ex.getMessage() : "Sucesso: " + res)
                .join();
        System.out.println("Com handle: " + resultHandle);

        CompletableFuture.supplyAsync(() -> {
                    simulateSlowOperation(100);
                    return "Passo 1";
                }, executor)
                .orTimeout(1, TimeUnit.SECONDS)
                .thenApply(res -> {
                    throw new RuntimeException("Erro no passo 2");
                })
                .thenApply(res -> res + " Passo 3")
                .exceptionally(ex -> "Tratamento terminal global pegou o erro: " + ex.getMessage())
                .thenAccept(System.out::println)
                .join();
    }

    private static void exemploTimeout(ExecutorService executor) {
        System.out.println("\n--- Exemplo: Timeouts ---");

        try {
            CompletableFuture.supplyAsync(() -> {
                        simulateSlowOperation(3000);
                        return "Dados lentos";
                    }, executor)
                    .orTimeout(1, TimeUnit.SECONDS)
                    .join();
        } catch (Exception e) {
            System.out.println("orTimeout acionado: " + e.getMessage());
        }

        String fallback = CompletableFuture.supplyAsync(() -> {
                    simulateSlowOperation(3000);
                    return "Dados lentos";
                }, executor)
                .completeOnTimeout("Dados de fallback rápido", 1, TimeUnit.SECONDS)
                .exceptionally(ex -> "Erro terminal: " + ex.getMessage())
                .join();
        System.out.println("completeOnTimeout retornou: " + fallback);
    }

    private static void exemploCombinacoes(ExecutorService executor) {
        System.out.println("\n--- Exemplo: Combinações ---");

        CompletableFuture<String> c1 = CompletableFuture.supplyAsync(() -> {
            simulateSlowOperation(500);
            return "Hello";
        }, executor).orTimeout(1, TimeUnit.SECONDS);

        CompletableFuture<String> c2 = CompletableFuture.supplyAsync(() -> {
            simulateSlowOperation(300);
            return "World";
        }, executor).orTimeout(1, TimeUnit.SECONDS);

        c1.thenCombine(c2, (res1, res2) -> res1 + " " + res2)
                .exceptionally(ex -> "Erro ao combinar: " + ex.getMessage())
                .thenAccept(System.out::println)
                .join();

        CompletableFuture<String> taskA = CompletableFuture.supplyAsync(() -> {
            simulateSlowOperation(200);
            return "Task A";
        }, executor).orTimeout(1, TimeUnit.SECONDS);

        CompletableFuture<String> taskB = CompletableFuture.supplyAsync(() -> {
            simulateSlowOperation(300);
            return "Task B";
        }, executor).orTimeout(1, TimeUnit.SECONDS);

        CompletableFuture.allOf(taskA, taskB)
                .orTimeout(2, TimeUnit.SECONDS)
                .exceptionally(ex -> {
                    System.err.println("Erro no allOf: " + ex.getMessage());
                    return null;
                })
                .join();
        System.out.println("Todos finalizados: " + taskA.join() + ", " + taskB.join());

        CompletableFuture<String> fastTask = CompletableFuture.supplyAsync(() -> {
            simulateSlowOperation(100);
            return "Mais Rápido";
        }, executor).orTimeout(1, TimeUnit.SECONDS);

        CompletableFuture<String> slowTask = CompletableFuture.supplyAsync(() -> {
            simulateSlowOperation(500);
            return "Mais Lento";
        }, executor).orTimeout(1, TimeUnit.SECONDS);

        Object primeiro = CompletableFuture.anyOf(fastTask, slowTask)
                .orTimeout(1, TimeUnit.SECONDS)
                .exceptionally(ex -> "Erro anyOf: " + ex.getMessage())
                .join();
        System.out.println("O primeiro completou com: " + primeiro);
        System.out.println("anyOf não cancela a tarefa lenta — ela segue até o fim.");
    }

    private static void simulateSlowOperation(int millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
