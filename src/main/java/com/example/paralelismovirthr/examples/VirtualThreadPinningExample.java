package com.example.paralelismovirthr.examples;

import java.time.Duration;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Exemplo sobre o problema de Pinning (fixação de Carrier Thread) em Virtual Threads no Java 21 LTS.
 * <p>
 * O que é Pinning:
 * Uma Virtual Thread (VT) executa sobre uma Platform Thread (chamada de Carrier Thread).
 * Quando a VT faz uma operação bloqueante de I/O, a JVM normalmente "desmonta" (unmount) a VT,
 * liberando a Carrier Thread para rodar outra VT.
 * Porém, no Java 21, se o bloqueio de I/O ocorrer dentro de um bloco 'synchronized' ou método nativo (JNI),
 * a VT não consegue ser desmontada. Ela "fixa" (pins) a Carrier Thread, bloqueando a Platform
 * Thread subjacente de atender outras tarefas.
 * <p>
 * Solução no Java 21:
 * Substituir 'synchronized' por 'ReentrantLock' ao realizar I/O ou bloqueios prolongados.
 * <p>
 * Como detectar Pinning em execução (Recursos Nativos e Estáveis do JDK 21):
 * 1. Propriedade de Sistema da JVM:
 *    -Djdk.tracePinnedThreads=full  -> Imprime stack trace completo no stderr ao pinar
 *    -Djdk.tracePinnedThreads=short -> Imprime apenas os frames problemáticos
 * <p>
 *    Exemplo de execução via Maven:
 *    .\mvnw.cmd test-compile exec:java -Dexec.mainClass="com.example.paralelismovirthr.examples.VirtualThreadPinningExample" -Dexec.jvmArgs="-Djdk.tracePinnedThreads=full"
 * <p>
 *    Exemplo de saída emitida pelo JDK quando ocorre pinning:
 *    {@code
 *    Thread[#23,vt-pin-sync-1,5,main]
 *        java.base/java.lang.VirtualThread$VThreadContinuation.onPinned(VirtualThread.java:185)
 *        java.base/jdk.internal.vm.Continuation.onPinned0(Continuation.java:393)
 *        java.base/java.lang.VirtualThread.parkNanos(VirtualThread.java:631)
 *        java.base/java.lang.Thread.sleep(Thread.java:507)
 *        com.example.paralelismovirthr.examples.VirtualThreadPinningExample.lambda$exemploPinningComSynchronized$0(VirtualThreadPinningExample.java:70) <== PINNED HERE (synchronized)
 *    }
 * <p>
 * 2. Java Flight Recorder (JFR):
 *    O evento 'jdk.VirtualThreadPinned' é ativado por padrão com limiar de 20 ms.
 *    Pode ser gravado em produção com:
 *    -XX:StartFlightRecording=filename=recording.jfr,settings=profile
 *    e analisado visualmente pelo JDK Mission Control (JMC).
 */
public class VirtualThreadPinningExample {

    private static final Object lockObject = new Object();
    private static final ReentrantLock reentrantLock = new ReentrantLock();

    // Padronização com ThreadFactory para nomeação consistente e observabilidade
    private static final ThreadFactory PINNED_FACTORY = Thread.ofVirtual().name("vt-pin-sync-", 1).factory();
    private static final ThreadFactory LOCK_FACTORY = Thread.ofVirtual().name("vt-pin-lock-", 1).factory();

    public static void main(String[] args) {
        System.out.println("=== Diagnóstico de Pinning no Java 21 ===");
        System.out.println("Dica: Execute com -Djdk.tracePinnedThreads=full para visualizar o stack trace de pinning no console.");

        System.out.println("\n--- 1. Exemplo de Pinning com Synchronized (INCORRETO) ---");
        exemploPinningComSynchronized();

        System.out.println("\n--- 2. Exemplo Seguro com ReentrantLock (CORRETO) ---");
        exemploCorretoComReentrantLock();
    }

    /**
     * O jeito ERRADO no Java 21 para I/O em bloco sincronizado.
     * Causa o Pinning da Carrier Thread do SO.
     */
    public static void exemploPinningComSynchronized() {
        try (ExecutorService executor = Executors.newThreadPerTaskExecutor(PINNED_FACTORY)) {
            for (int i = 0; i < 3; i++) {
                executor.submit(() -> {
                    String threadName = Thread.currentThread().getName();
                    System.out.println(threadName + " tentando entrar no bloco synchronized...");

                    // O bloco synchronized impede que a VT desmonte (unmount) ao bloquear no sleep/I/O
                    synchronized (lockObject) {
                        System.out.println(threadName + " executando I/O bloqueante no synchronized (PINNING OCORRENDO)...");
                        try {
                            // Simula I/O bloqueante longo
                            Thread.sleep(Duration.ofMillis(300));
                        } catch (InterruptedException e) {
                            Thread.currentThread().interrupt();
                        }
                    }
                    System.out.println(threadName + " liberou o synchronized.");
                });
            }
        }
    }

    /**
     * O jeito CORRETO no Java 21 usando ReentrantLock.
     * Permite que a Virtual Thread seja desmontada (unmounted), mantendo a Carrier Thread livre.
     */
    public static void exemploCorretoComReentrantLock() {
        try (ExecutorService executor = Executors.newThreadPerTaskExecutor(LOCK_FACTORY)) {
            for (int i = 0; i < 3; i++) {
                executor.submit(() -> {
                    String threadName = Thread.currentThread().getName();
                    System.out.println(threadName + " tentando obter o ReentrantLock...");

                    try {
                        // Tenta obter o lock com timeout para evitar deadlocks
                        if (reentrantLock.tryLock(2, TimeUnit.SECONDS)) {
                            try {
                                System.out.println(threadName + " executando I/O bloqueante de forma segura com ReentrantLock...");
                                // Simula I/O bloqueante. A VT aqui desce da Carrier Thread normalmente (unmount).
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
                    System.out.println(threadName + " concluiu com sucesso.");
                });
            }
        }
    }
}
