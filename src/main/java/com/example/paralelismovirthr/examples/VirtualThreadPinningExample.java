package com.example.paralelismovirthr.examples;

import java.time.Duration;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Pinning no Java 25.
 * <p>
 * JEP 491 (Java 24+): {@code synchronized} e {@code Object.wait} <strong>não pinam</strong>
 * mais a Virtual Thread na carrier. O exemplo com {@code synchronized} abaixo é histórico
 * e de contenção — não de pinning.
 * <p>
 * O que ainda pina no 25: JNI/nativo, FFM, class loading em execução, parte do file I/O no Linux.
 * <p>
 * {@code ReentrantLock.tryLock(timeout)} continua o padrão quando a seção crítica espera I/O:
 * monitor não tem timeout e não é interrompível.
 * <p>
 * Diagnóstico: JFR {@code jdk.VirtualThreadPinned} e {@code jdk.VirtualThreadSubmitFailed}.
 * {@code -Djdk.tracePinnedThreads} foi removido no Java 24.
 */
public class VirtualThreadPinningExample {

    private static final Object lockObject = new Object();
    private static final ReentrantLock reentrantLock = new ReentrantLock();

    private static final ThreadFactory SYNC_FACTORY = Thread.ofVirtual().name("vt-sync-", 1).factory();
    private static final ThreadFactory LOCK_FACTORY = Thread.ofVirtual().name("vt-lock-", 1).factory();

    public static void main(String[] args) {
        System.out.println("=== Locks e pinning no Java 25 (JEP 491) ===");
        System.out.println("synchronized NÃO pina VT. Ainda serializa e não tem timeout.");
        System.out.println("Detectar pinning restante: JFR jdk.VirtualThreadPinned / jdk.VirtualThreadSubmitFailed.");

        System.out.println("\n--- 1. synchronized com I/O (não pina; serializa sem timeout) ---");
        exemploSynchronizedComIo();

        System.out.println("\n--- 2. ReentrantLock com tryLock (timeout + interruptível) ---");
        exemploCorretoComReentrantLock();
    }

    /**
     * Java 25: não causa pinning. Continua ruim para I/O compartilhado porque
     * milhares de VTs enfileiram no mesmo monitor sem bound de espera.
     */
    public static void exemploSynchronizedComIo() {
        try (ExecutorService executor = Executors.newThreadPerTaskExecutor(SYNC_FACTORY)) {
            for (int i = 0; i < 3; i++) {
                executor.submit(() -> {
                    String threadName = Thread.currentThread().getName();
                    System.out.println(threadName + " tentando entrar no synchronized...");
                    synchronized (lockObject) {
                        System.out.println(threadName + " em I/O dentro do monitor (sem timeout).");
                        try {
                            Thread.sleep(Duration.ofMillis(300));
                        } catch (InterruptedException e) {
                            Thread.currentThread().interrupt();
                        }
                    }
                    System.out.println(threadName + " saiu do synchronized.");
                });
            }
        }
    }

    public static void exemploCorretoComReentrantLock() {
        try (ExecutorService executor = Executors.newThreadPerTaskExecutor(LOCK_FACTORY)) {
            for (int i = 0; i < 3; i++) {
                executor.submit(() -> {
                    String threadName = Thread.currentThread().getName();
                    System.out.println(threadName + " tentando obter o ReentrantLock...");
                    try {
                        if (reentrantLock.tryLock(2, TimeUnit.SECONDS)) {
                            try {
                                System.out.println(threadName + " em I/O com lock com timeout.");
                                Thread.sleep(Duration.ofMillis(300));
                            } finally {
                                reentrantLock.unlock();
                            }
                        } else {
                            System.err.println(threadName + " não conseguiu o lock a tempo.");
                        }
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        System.err.println("Thread interrompida: " + e.getMessage());
                    }
                    System.out.println(threadName + " concluiu.");
                });
            }
        }
    }
}
