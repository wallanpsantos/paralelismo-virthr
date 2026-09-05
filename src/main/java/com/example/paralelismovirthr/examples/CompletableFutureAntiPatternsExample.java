package com.example.paralelismovirthr.examples;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;

/**
 * O que não fazer com CompletableFuture e Virtual Threads no Java 25.
 */
public class CompletableFutureAntiPatternsExample {

    private static final ThreadFactory factory = Thread.ofVirtual().name("vt-cf-anti-", 1).factory();

    public static void main(String[] args) {
        try (ExecutorService executor = Executors.newThreadPerTaskExecutor(factory)) {
            supplyAsyncWithoutExecutor();
            thenApplyAsyncWithoutExecutor(executor);
            nestedJoinDeadlockRisk(executor);
            parallelStreamForBlockingIo(executor);
            inlineExecutorCreationLeak();
            missingErrorHandler(executor);
            missingTimeout(executor);
            fixedPoolOfVirtualThreads();
            threadLocalExpensiveObjectCache(executor);
        }
    }

    private static void supplyAsyncWithoutExecutor() {
        // ❌ supplyAsync sem executor usa ForkJoinPool.commonPool (platform, CPU-sized).
        try (ExecutorService executor = Executors.newThreadPerTaskExecutor(factory)) {
            CompletableFuture.supplyAsync(() -> {
                        simulateBlockingIO(100);
                        return "Dados";
                    }, executor)
                    .orTimeout(1, TimeUnit.SECONDS)
                    .exceptionally(ex -> "Erro")
                    .join();
        }
    }

    private static void thenApplyAsyncWithoutExecutor(ExecutorService executor) {
        // ❌ thenApplyAsync sem executor também vai para o commonPool.
        CompletableFuture.supplyAsync(() -> "Dado Inicial", executor)
                .thenApplyAsync(dado -> {
                    simulateBlockingIO(100);
                    return dado + " Processado";
                }, executor)
                .orTimeout(1, TimeUnit.SECONDS)
                .exceptionally(ex -> "Erro")
                .join();
    }

    private static void nestedJoinDeadlockRisk(ExecutorService executor) {
        // join() dentro de estágio: em pool fixo de platform threads pode starvation/deadlock.
        // Em executor de VT o risco de pool some; o cheiro de código permanece — use thenCompose.
        CompletableFuture.supplyAsync(() -> "Req 1", executor)
                .thenCompose(req1 -> CompletableFuture.supplyAsync(() -> "Req 2", executor)
                        .thenApply(req2 -> req1 + " " + req2))
                .orTimeout(1, TimeUnit.SECONDS)
                .exceptionally(ex -> "Erro")
                .join();
    }

    private static void parallelStreamForBlockingIo(ExecutorService executor) {
        List<Integer> ids = List.of(1, 2, 3, 4, 5);
        List<CompletableFuture<String>> futures = ids.stream()
                .map(id -> CompletableFuture.supplyAsync(() -> {
                            simulateBlockingIO(100);
                            return "Processado: " + id;
                        }, executor)
                        .orTimeout(1, TimeUnit.SECONDS)
                        .exceptionally(ex -> "Erro " + id))
                .toList();

        CompletableFuture.allOf(futures.toArray(new CompletableFuture[0])).join();
        List<String> results = futures.stream().map(CompletableFuture::join).toList();
        System.out.println("Resultados do lote corrigido: " + results);
    }

    private static void inlineExecutorCreationLeak() {
        try (ExecutorService localExecutor = Executors.newThreadPerTaskExecutor(factory)) {
            CompletableFuture.supplyAsync(() -> "Fechado corretamente", localExecutor)
                    .orTimeout(1, TimeUnit.SECONDS)
                    .exceptionally(ex -> "Erro")
                    .join();
        }
    }

    private static void missingErrorHandler(ExecutorService executor) {
        CompletableFuture.supplyAsync(() -> {
                    throw new RuntimeException("Erro tratado");
                }, executor)
                .orTimeout(1, TimeUnit.SECONDS)
                .exceptionally(ex -> {
                    System.err.println("Capturado: " + ex.getMessage());
                    return null;
                });
    }

    private static void missingTimeout(ExecutorService executor) {
        CompletableFuture.supplyAsync(() -> {
                    simulateBlockingIO(100);
                    return "Rápido o suficiente";
                }, executor)
                .orTimeout(2, TimeUnit.SECONDS)
                .exceptionally(ex -> "Timeout ou erro")
                .join();
    }

    private static void fixedPoolOfVirtualThreads() {
        try (ExecutorService bomExecutor = Executors.newThreadPerTaskExecutor(factory)) {
            CompletableFuture.runAsync(() -> {
            }, bomExecutor).join();
        }
    }

    private static void threadLocalExpensiveObjectCache(ExecutorService executor) {
        DateTimeFormatter formatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
        CompletableFuture.supplyAsync(() -> formatter.format(LocalDateTime.now()), executor)
                .orTimeout(1, TimeUnit.SECONDS)
                .exceptionally(ex -> "Erro de formatação: " + ex.getMessage())
                .thenAccept(data -> System.out.println("Data formatada sem ThreadLocal: " + data))
                .join();
    }

    private static void simulateBlockingIO(int millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
