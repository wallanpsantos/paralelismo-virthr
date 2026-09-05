package com.example.paralelismovirthr.examples;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * Fundamentos de Virtual Threads no Java 25 (JEP 444, GA desde o Java 21).
 * <p>
 * VT é mecanismo de escala para I/O bloqueante. Não é atalho de CPU.
 * Não faça pool de VT. Nomeie as threads. Feche o executor.
 */
public class VirtualThreadBasicsExample {

    public static void main(String[] args) {
        System.out.println("--- Plataforma vs Virtual ---");
        exemploPlataformaVsVirtual();

        System.out.println("\n--- Criação de Virtual Threads ---");
        exemploCriacaoVirtualThreads();

        System.out.println("\n--- Milhares de Threads ---");
        exemploMilharesDeThreads();

        System.out.println("\n--- Fan-Out estável (sem StructuredTaskScope) ---");
        exemploFanOut();
    }

    public static void exemploPlataformaVsVirtual() {
        Thread platformThread = Thread.ofPlatform().name("platform-thread-1").start(() -> {
            System.out.println("Executando: " + Thread.currentThread());
            System.out.println("É virtual? " + Thread.currentThread().isVirtual());
            System.out.println("É daemon? " + Thread.currentThread().isDaemon());
        });

        Thread virtualThread = Thread.ofVirtual().name("virtual-thread-1").start(() -> {
            System.out.println("Executando: " + Thread.currentThread());
            System.out.println("É virtual? " + Thread.currentThread().isVirtual());
            System.out.println("É daemon? " + Thread.currentThread().isDaemon());
        });

        try {
            platformThread.join();
            virtualThread.join();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            System.err.println("Thread interrompida: " + e.getMessage());
        }
    }

    public static void exemploCriacaoVirtualThreads() {
        Thread vThread1 = Thread.ofVirtual().name("vt-builder-1").start(() ->
                System.out.println("Criada pelo builder direto: " + Thread.currentThread().getName()));

        ThreadFactory factory = Thread.ofVirtual().name("vt-factory-", 1).factory();
        Thread vThread2 = factory.newThread(() ->
                System.out.println("Criada pelo factory: " + Thread.currentThread().getName()));
        vThread2.start();

        try {
            vThread1.join();
            vThread2.join();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }

        ThreadFactory executorFactory = Thread.ofVirtual().name("vt-executor-", 1).factory();
        try (ExecutorService executor = Executors.newThreadPerTaskExecutor(executorFactory)) {
            executor.submit(() ->
                    System.out.println("Criada via ExecutorService (tarefa 1): " + Thread.currentThread().getName()));
            executor.submit(() ->
                    System.out.println("Criada via ExecutorService (tarefa 2): " + Thread.currentThread().getName()));
        }
    }

    public static void exemploMilharesDeThreads() {
        Instant inicio = Instant.now();
        List<Thread> threads = new ArrayList<>();

        for (int i = 0; i < 10_000; i++) {
            Thread t = Thread.ofVirtual().name("vt-massiva-", i).unstarted(() -> {
                try {
                    Thread.sleep(Duration.ofSeconds(1));
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            });
            threads.add(t);
        }

        threads.forEach(Thread::start);

        for (Thread t : threads) {
            try {
                t.join();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }

        Instant fim = Instant.now();
        System.out.println("Tempo total para 10.000 chamadas bloqueantes: "
                + Duration.between(inicio, fim).toMillis() + " ms");
    }

    /**
     * Fan-out/fan-in estável: uma VT por tarefa, timeout em cada {@code Future.get}.
     * StructuredTaskScope permanece preview no JDK 25 (JEP 505) e não é usado aqui.
     */
    public static void exemploFanOut() {
        ThreadFactory fanoutFactory = Thread.ofVirtual().name("vt-fanout-http-", 1).factory();
        try (ExecutorService executor = Executors.newThreadPerTaskExecutor(fanoutFactory)) {
            List<Future<String>> futures = new ArrayList<>();

            for (int i = 1; i <= 5; i++) {
                final int id = i;
                futures.add(executor.submit(() -> {
                    Thread.sleep(Duration.ofMillis(500));
                    return "Resposta da chamada " + id;
                }));
            }

            for (Future<String> future : futures) {
                try {
                    String resultado = future.get(5, TimeUnit.SECONDS);
                    System.out.println("Recebido: " + resultado);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } catch (ExecutionException | TimeoutException e) {
                    System.err.println("Erro ou timeout na tarefa: " + e.getMessage());
                }
            }
        }
    }
}
