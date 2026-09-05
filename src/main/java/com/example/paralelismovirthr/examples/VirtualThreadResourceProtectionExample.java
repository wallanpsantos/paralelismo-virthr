package com.example.paralelismovirthr.examples;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.Semaphore;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * VT remove o teto de threads da aplicação. Banco, HTTP e broker continuam finitos.
 * Limite o acesso ao recurso (Semaphore ≤ Hikari maximumPoolSize), não o número de VTs.
 */
public class VirtualThreadResourceProtectionExample {

    public static final int MAX_CONEXOES_BD = 50;

    private static final Semaphore semaphore = new Semaphore(MAX_CONEXOES_BD);
    private static final AtomicInteger conexoesAtivas = new AtomicInteger(0);

    private static final ThreadFactory UNPROTECTED_FACTORY = Thread.ofVirtual().name("vt-unprotected-", 1).factory();
    private static final ThreadFactory PROTECTED_FACTORY = Thread.ofVirtual().name("vt-protected-", 1).factory();

    public static void main(String[] args) {
        System.out.println("--- SEM Semáforo (o problema) ---");
        exemploSemSemaforo();

        System.out.println("\n--- COM Semáforo (a solução) ---");
        exemploComSemaforo();
    }

    public static void exemploSemSemaforo() {
        System.out.println("Iniciando sobrecarga...");
        try (ExecutorService executor = Executors.newThreadPerTaskExecutor(UNPROTECTED_FACTORY)) {
            List<Future<?>> futures = new ArrayList<>();
            for (int i = 0; i < 500; i++) {
                final int id = i;
                futures.add(executor.submit(() -> simularAcessoAoBanco(id)));
            }
            aguardar(futures, 5);
        }
    }

    public static void exemploComSemaforo() {
        System.out.println("Iniciando acesso protegido com semáforo...");
        Instant inicio = Instant.now();

        try (ExecutorService executor = Executors.newThreadPerTaskExecutor(PROTECTED_FACTORY)) {
            List<Future<?>> futures = new ArrayList<>();
            for (int i = 0; i < 500; i++) {
                final int id = i;
                futures.add(executor.submit(() -> {
                    try {
                        if (semaphore.tryAcquire(5, TimeUnit.SECONDS)) {
                            try {
                                simularAcessoAoBanco(id);
                            } finally {
                                semaphore.release();
                            }
                        } else {
                            System.err.println(Thread.currentThread().getName()
                                    + " falhou ao obter permit (timeout).");
                        }
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                }));
            }
            aguardar(futures, 10);
        }

        Instant fim = Instant.now();
        System.out.println("Finalizado (com controle) em " + Duration.between(inicio, fim).toMillis() + " ms.");
    }

    public static void simularAcessoAoBanco(int id) {
        int atual = conexoesAtivas.incrementAndGet();
        if (atual > MAX_CONEXOES_BD) {
            System.err.println("ALERTA CRÍTICO: " + atual + " conexões abertas! Limite de "
                    + MAX_CONEXOES_BD + " excedido. id=" + id);
        }
        try {
            Thread.sleep(Duration.ofMillis(50));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } finally {
            conexoesAtivas.decrementAndGet();
        }
    }

    public static int getConexoesAtivas() {
        return conexoesAtivas.get();
    }

    private static void aguardar(List<Future<?>> futures, int timeoutSeconds) {
        for (Future<?> f : futures) {
            try {
                f.get(timeoutSeconds, TimeUnit.SECONDS);
            } catch (TimeoutException e) {
                System.err.println("Timeout aguardando tarefa: " + e.getMessage());
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } catch (ExecutionException e) {
                System.err.println("Tarefa falhou: " + e.getCause());
            }
        }
    }
}
