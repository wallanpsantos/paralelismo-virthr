package com.example.paralelismovirthr.examples;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;

/**
 * CompletableFutureBasicsExample
 * <p>
 * O que é: Uma API para compor operações assíncronas de forma funcional.
 * <p>
 * Quando usar:
 * - Para combinar resultados de múltiplas operações assíncronas independentes.
 * - Para criar pipelines de processamento de dados (encadeamento).
 * - Para integrar com APIs assíncronas baseadas em callbacks.
 * <p>
 * Quando NÃO usar:
 * - Para simples operações de I/O bloqueante onde o código imperativo com Virtual Threads
 * (apenas chamadas diretas) é muito mais fácil de ler, debugar e manter.
 */
public class CompletableFutureBasicsExample {

    // Criação de ThreadFactory com threads virtuais nomeadas para melhor observabilidade
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
        // Inicia uma operação assíncrona, usando o executor explícito (NUNCA usar sem executor para I/O)
        CompletableFuture<String> future = CompletableFuture.supplyAsync(() -> {
                    simulateSlowOperation(1000);
                    return "Resultado da operação lenta";
                }, executor)
                .orTimeout(2, TimeUnit.SECONDS) // Sempre adicionar timeout para operações bloqueantes
                .exceptionally(ex -> "Erro: " + ex.getMessage()); // Tratamento terminal de erro

        System.out.println("Operação iniciada. Fazendo outras coisas...");

        // join() bloqueia até que o resultado esteja disponível
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
                .thenApply(valor -> valor * 2) // Transforma o dado
                .thenApply(valor -> "Valor processado: " + valor)
                .thenAccept(System.out::println) // Consome o dado
                .exceptionally(ex -> { // Tratamento terminal
                    System.err.println("Falha no pipeline: " + ex.getMessage());
                    return null;
                })
                .join();
    }

    private static void exemploTratamentoDeErros(ExecutorService executor) {
        System.out.println("\n--- Exemplo: Tratamento de Erros ---");

        // a. exceptionally() - captura e fornece um valor de fallback
        String resultExceptionally = CompletableFuture.<String>supplyAsync(() -> {
                    throw new RuntimeException("Erro forçado");
                }, executor)
                .orTimeout(1, TimeUnit.SECONDS)
                .exceptionally(ex -> "Fallback devido a: " + ex.getMessage())
                .join();
        System.out.println("Com exceptionally: " + resultExceptionally);

        // b. handle() - trata sucesso e erro no mesmo local
        String resultHandle = CompletableFuture.supplyAsync(() -> {
                    simulateSlowOperation(100);
                    return "Sucesso!";
                }, executor)
                .orTimeout(1, TimeUnit.SECONDS)
                .handle((res, ex) -> {
                    if (ex != null) {
                        return "Falhou: " + ex.getMessage();
                    }
                    return "Sucesso: " + res;
                })
                .join();
        System.out.println("Com handle: " + resultHandle);

        // c. Pipeline com único handler terminal
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

        // orTimeout - Falha com TimeoutException
        try {
            CompletableFuture.supplyAsync(() -> {
                        simulateSlowOperation(3000); // Demora mais que o timeout
                        return "Dados lentos";
                    }, executor)
                    .orTimeout(1, TimeUnit.SECONDS)
                    .join();
        } catch (Exception e) {
            System.out.println("orTimeout acionado: " + e.getMessage());
        }

        // completeOnTimeout - Retorna um valor padrão
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

        // a. thenCombine - combina dois futures independentes
        // Sem .exceptionally() prematuro para demonstrar a propagação e tratamento do pipeline combinado
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

        // b. allOf - espera múltiplos futures e propaga erro se qualquer um falhar
        CompletableFuture<String> taskA = CompletableFuture.supplyAsync(() -> {
            simulateSlowOperation(200);
            return "Task A";
        }, executor).orTimeout(1, TimeUnit.SECONDS);

        CompletableFuture<String> taskB = CompletableFuture.supplyAsync(() -> {
            simulateSlowOperation(300);
            return "Task B";
        }, executor).orTimeout(1, TimeUnit.SECONDS);

        CompletableFuture<Void> todos = CompletableFuture.allOf(taskA, taskB)
                .orTimeout(2, TimeUnit.SECONDS)
                .exceptionally(ex -> {
                    System.err.println("Erro no allOf: " + ex.getMessage());
                    return null;
                });
        todos.join();
        System.out.println("Todos finalizados: " + taskA.join() + ", " + taskB.join());

        // c. anyOf - o primeiro que terminar vence (retorna Object com o resultado da mais rápida)
        CompletableFuture<String> fastTask = CompletableFuture.supplyAsync(() -> {
            simulateSlowOperation(100);
            return "Mais Rápido";
        }, executor).orTimeout(1, TimeUnit.SECONDS);

        CompletableFuture<String> slowTask = CompletableFuture.supplyAsync(() -> {
            simulateSlowOperation(500);
            return "Mais Lento";
        }, executor).orTimeout(1, TimeUnit.SECONDS);

        CompletableFuture<Object> primeiro = CompletableFuture.anyOf(fastTask, slowTask)
                .orTimeout(1, TimeUnit.SECONDS)
                .exceptionally(ex -> "Erro anyOf: " + ex.getMessage());
        System.out.println("O primeiro completou com: " + primeiro.join());
    }

    private static void simulateSlowOperation(int millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
