package com.example.paralelismovirthr.examples;

import java.time.Duration;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantLock;

/**
 * O que é: demonstração sobre pinning de Virtual Threads e sincronização no Java 25 LTS.
 * <p>
 * Mecânica: pinning ocorre quando a VT não pode ser desmontada (unmount) da carrier durante
 * um bloqueio, prendendo a platform thread subjacente. Desde a JEP 491 (Java 24+), blocos
 * {@code synchronized} e chamadas a {@code Object.wait()} NÃO pinam mais a carrier.
 * <p>
 * Quando usar: use {@code ReentrantLock.tryLock(timeout)} para seções críticas que realizam I/O;
 * o motivo não é evitar pinning, mas sim dispor de timeout explícito e permitir interrupção.
 * <p>
 * Quando não usar: evite {@code synchronized} em caminhos de I/O de alto throughput; o monitor
 * não suporta timeout e gera filas cegas de contenção. Ainda causam pinning no Java 25:
 * frames nativos via JNI, Foreign Function & Memory (FFM), class loading dinâmico e parte do file I/O no Linux.
 * <p>
 * Risco: acreditar no folclore de que {@code synchronized} ainda pina no Java 25 ou usar a flag obsoleta
 * {@code -Djdk.tracePinnedThreads} (removida no JDK 24).
 * <p>
 * Como observar: utilize eventos JFR (Java Flight Recorder) {@code jdk.VirtualThreadPinned} e {@code jdk.VirtualThreadSubmitFailed}.
 * <p>
 * Leitura: Rahman cap. 2 e 3 (pinning e limites) atualizados pela JEP 491.
 */
public class VirtualThreadPinningExample {

    private static final Object lockObject = new Object();
    private static final ReentrantLock reentrantLock = new ReentrantLock();

    private static final ThreadFactory SYNC_FACTORY = Thread.ofVirtual().name("vt-sync-", 1).factory();
    private static final ThreadFactory LOCK_FACTORY = Thread.ofVirtual().name("vt-lock-", 1).factory();

    /**
     * Ponto de entrada que executa as comparações entre monitor sincronizado e locks explícitos.
     * <p>
     * Ponto: ilustra a diferença de comportamento entre monitor e lock sob concorrência de VTs.
     * Invariante: cada exemplo fecha seu executor per-task antes que o próximo seja executado.
     *
     * @param args argumentos de linha de comando passados na inicialização
     */
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
     * Demonstra execução de I/O sob bloco {@code synchronized} no Java 25.
     * <p>
     * Ponto: evidencia que, graças à JEP 491, o monitor não pina mais a carrier thread.
     * Invariante: embora não haja pinning, o monitor serializa as tarefas sem suporte a timeout
     * nem interrupção imediata, tornando-o inadequado para operações de I/O concorrentes.
     */
    public static void exemploSynchronizedComIo() {
        try (ExecutorService executor = Executors.newThreadPerTaskExecutor(SYNC_FACTORY)) {
            for (int i = 0; i < 3; i++) {
                executor.submit(() -> {
                    String threadName = Thread.currentThread().getName();
                    System.out.println(threadName + " tentando entrar no synchronized...");
                    // JEP 491: no Java 25 não há pinning aqui, mas o monitor não possui timeout e serializa as VTs.
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

    /**
     * Demonstra o controle de concorrência recomendado para I/O com {@link ReentrantLock}.
     * <p>
     * Ponto: aplica tentativa temporizada de aquisição de lock com {@code tryLock(timeout)}.
     * Invariante: a liberação em bloco {@code finally} garante ausência de vazamento de locks,
     * e o timeout impede que threads fiquem bloqueadas indefinidamente em caso de lentidão.
     */
    public static void exemploCorretoComReentrantLock() {
        try (ExecutorService executor = Executors.newThreadPerTaskExecutor(LOCK_FACTORY)) {
            for (int i = 0; i < 3; i++) {
                executor.submit(() -> {
                    String threadName = Thread.currentThread().getName();
                    System.out.println(threadName + " tentando obter o ReentrantLock...");
                    try {
                        // tryLock com timeout evita requisições zumbis e permite tratar contenção com fallback.
                        if (reentrantLock.tryLock(2, TimeUnit.SECONDS)) {
                            try {
                                System.out.println(threadName + " em I/O com lock com timeout.");
                                Thread.sleep(Duration.ofMillis(300));
                            } finally {
                                // Liberação obrigatória do lock para assegurar o ciclo de vida do recurso.
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
