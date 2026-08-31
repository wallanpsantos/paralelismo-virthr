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
 * Exemplos do que NÃO fazer com CompletableFuture e Virtual Threads.
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
        // a. supplyAsync without executor (pollutes commonPool)

        // ❌ WRONG: Usa o ForkJoinPool.commonPool() implicitamente. Isso pode esgotar as threads
        // do sistema se a operação for bloqueante, afetando todo o resto da aplicação.
        /*
        CompletableFuture.supplyAsync(() -> {
            simulateBlockingIO(1000);
            return "Dados";
        }).join();
        */

        // ✅ CORRECT: Sempre passe um executor explícito baseado em Virtual Threads para I/O.
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
        // b. thenApplyAsync without executor

        // ❌ WRONG: O thenApplyAsync sem executor também jogará a tarefa para o commonPool.
        /*
        CompletableFuture.supplyAsync(() -> "Dado Inicial", executor)
            .thenApplyAsync(dado -> {
                simulateBlockingIO(500); // Bloqueia uma thread do commonPool!
                return dado + " Processado";
            })
            .join();
        */

        // ✅ CORRECT: Passe o executor para os métodos Async ou use o thenApply normal 
        // (que executará na mesma thread virtual anterior ou na main thread se já completou).
        CompletableFuture.supplyAsync(() -> "Dado Inicial", executor)
                .thenApplyAsync(dado -> {
                    simulateBlockingIO(100);
                    return dado + " Processado";
                }, executor) // Executor explícito aqui também
                .orTimeout(1, TimeUnit.SECONDS)
                .exceptionally(ex -> "Erro")
                .join();
    }

    private static void nestedJoinDeadlockRisk(ExecutorService executor) {
        // c. Nested CF with .join() inside a stage (deadlock risk)

        // ❌ WRONG: Fazer .join() dentro de um estágio do CompletableFuture bloqueia a thread
        // do pool esperando outra. Se o pool tiver tamanho fixo, pode causar deadlock (starvation).
        /*
        CompletableFuture.supplyAsync(() -> "Req 1", executor)
            .thenApply(req1 -> {
                String req2 = CompletableFuture.supplyAsync(() -> "Req 2", executor).join(); // ❌ JOIN AQUI
                return req1 + " " + req2;
            })
            .join();
        */

        // ✅ CORRECT: Use flatMap (thenCompose) para encadear futuros dependentes sem bloquear.
        CompletableFuture.supplyAsync(() -> "Req 1", executor)
                .thenCompose(req1 -> CompletableFuture.supplyAsync(() -> "Req 2", executor)
                        .thenApply(req2 -> req1 + " " + req2)
                )
                .orTimeout(1, TimeUnit.SECONDS)
                .exceptionally(ex -> "Erro")
                .join();
    }

    private static void parallelStreamForBlockingIo(ExecutorService executor) {
        // d. parallelStream for blocking I/O (saturates commonPool)

        List<Integer> ids = List.of(1, 2, 3, 4, 5);

        // ❌ WRONG: parallelStream() usa o ForkJoinPool.commonPool(). Operações de I/O 
        // bloquearão as threads da plataforma baseadas nesse pool.
        /*
        ids.parallelStream().map(id -> {
            simulateBlockingIO(500);
            return "Processado: " + id;
        }).collect(Collectors.toList());
        */

        // ✅ CORRECT: Mapeie para CompletableFutures usando um executor explícito e aguarde com allOf/join.
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
        System.out.println("Resultados do parallelStream corrigido: " + results);
    }

    private static void inlineExecutorCreationLeak() {
        // e. Creating executor inline without closing (resource leak)

        // ❌ WRONG: Cria um novo executor na hora e não o fecha. Isso vaza recursos, 
        // especialmente grave em longo prazo ou em loops.
        /*
        CompletableFuture.supplyAsync(() -> "Sem fechar executor", Executors.newVirtualThreadPerTaskExecutor())
            .join();
        */

        // ✅ CORRECT: Crie como um campo da classe se for para uso geral, ou use try-with-resources.
        try (ExecutorService localExecutor = Executors.newThreadPerTaskExecutor(factory)) {
            CompletableFuture.supplyAsync(() -> "Fechado corretamente", localExecutor)
                    .orTimeout(1, TimeUnit.SECONDS)
                    .exceptionally(ex -> "Erro")
                    .join();
        }
    }

    private static void missingErrorHandler(ExecutorService executor) {
        // f. Missing error handler

        // ❌ WRONG: Sem tratamento de erro, se a pipeline falhar silenciosamente (e não houver join), 
        // você nunca saberá o que deu errado. Se houver join, lançará CompletionException.
        /*
        CompletableFuture.supplyAsync(() -> {
            throw new RuntimeException("Erro invisível");
        }, executor);
        */

        // ✅ CORRECT: Sempre termine um pipeline com .exceptionally() ou .handle().
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
        // g. Missing timeout

        // ❌ WRONG: Sem timeout, se a chamada de rede ou I/O travar para sempre, 
        // o future nunca completará (zombie future) e vazará memória/recursos.
        /*
        CompletableFuture.supplyAsync(() -> {
            simulateBlockingIO(9999999);
            return "Não vai voltar";
        }, executor).exceptionally(ex -> "Erro");
        */

        // ✅ CORRECT: Sempre coloque orTimeout ou completeOnTimeout em operações que fazem I/O ou podem demorar.
        CompletableFuture.supplyAsync(() -> {
                    simulateBlockingIO(100);
                    return "Rápido o suficiente";
                }, executor)
                .orTimeout(2, TimeUnit.SECONDS)
                .exceptionally(ex -> "Timeout ou erro")
                .join();
    }

    private static void fixedPoolOfVirtualThreads() {
        // h. Fixed pool of virtual threads (defeats purpose)

        // ❌ WRONG: Threads virtuais são baratas, criar um pool fixo para elas limita 
        // artificialmente a concorrência sem nenhum ganho, desfazendo todo o benefício.
        /*
        ExecutorService badExecutor = Executors.newFixedThreadPool(10, Thread.ofVirtual().factory());
        */

        // ✅ CORRECT: Use sempre newThreadPerTaskExecutor() quando usar Threads Virtuais. Não pool threads virtuais!
        try (ExecutorService bomExecutor = Executors.newThreadPerTaskExecutor(factory)) {
            CompletableFuture.runAsync(() -> {
            }, bomExecutor).join();
        }
    }

    private static void threadLocalExpensiveObjectCache(ExecutorService executor) {
        // i. ThreadLocal for caching expensive mutable objects (anti-pattern with Virtual Threads)

        // ❌ WRONG: Em pools tradicionais de Platform Threads, usar ThreadLocal<SimpleDateFormat>
        // para reaproveitar objetos caros e mutáveis fazia sentido porque as threads eram poucas e de longa vida.
        // Com Virtual Threads (efêmeras, criadas on-demand aos milhares por tarefa), cada Virtual Thread terá sua própria
        // instância criada e descartada imediatamente, multiplicando alocações na Heap e anulando o cache.
        /*
        ThreadLocal<java.text.SimpleDateFormat> badCache =
                ThreadLocal.withInitial(() -> new java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss"));
        */

        // ✅ CORRECT: Use classes imutáveis e thread-safe (como DateTimeFormatter da java.time),
        // que podem ser compartilhadas livremente entre todas as Virtual Threads em constantes estáticas.
        DateTimeFormatter formatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

        CompletableFuture.supplyAsync(() -> {
                    LocalDateTime agora = LocalDateTime.now();
                    return formatter.format(agora);
                }, executor)
                .orTimeout(1, TimeUnit.SECONDS)
                .exceptionally(ex -> "Erro de formatação: " + ex.getMessage())
                .thenAccept(dataFormatada -> System.out.println("Data formatada com segurança (sem ThreadLocal): " + dataFormatada))
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
