package com.example.paralelismovirthr.examples;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.Semaphore;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Exemplo sobre proteção de recursos limitados (Downstream Resource Protection) com Virtual Threads.
 * <p>
 * O contexto:
 * Virtual Threads removem o "teto de threads". Podemos facilmente criar milhares ou milhões de VTs.
 * Contudo, os sistemas externos aos quais nos conectamos (como bancos de dados JDBC / HikariCP ou APIs legadas)
 * continuam tendo restrições severas de concorrência e capacidade.
 * <p>
 * O que acontece se 10.000 VTs tentarem acessar o banco simultaneamente?
 * Haverá escassez (exhaustion) no connection pool, timeouts generalizados ou falhas no banco downstream.
 * <p>
 * Solução:
 * 1. Não limite as Virtual Threads (não faça pool de VTs).
 * 2. Limite o ACESSO AO RECURSO usando mecanismos como {@link Semaphore} para aplicar backpressure.
 * 3. Evite ThreadLocal para cache de objetos caros (ex: SimpleDateFormat), pois VTs são descartadas a cada tarefa;
 *    prefira instâncias imutáveis compartilhadas (ex: DateTimeFormatter).
 */
public class VirtualThreadResourceProtectionExample {

    // Simula a capacidade de um Connection Pool
    public static final int MAX_CONEXOES_BD = 50;

    // Semáforo para controlar o número máximo de VTs que acessam o banco simultaneamente
    private static final Semaphore semaphore = new Semaphore(MAX_CONEXOES_BD);

    private static final AtomicInteger conexoesAtivas = new AtomicInteger(0);

    // ThreadFactories padronizadas para rastreamento e observabilidade
    private static final ThreadFactory UNPROTECTED_FACTORY = Thread.ofVirtual().name("vt-unprotected-", 1).factory();
    private static final ThreadFactory PROTECTED_FACTORY = Thread.ofVirtual().name("vt-protected-", 1).factory();

    public static void main(String[] args) {
        System.out.println("--- Exemplo SEM Semáforo (O problema) ---");
        exemploSemSemaforo();

        System.out.println("\n--- Exemplo COM Semáforo (A solução) ---");
        exemploComSemaforo();
    }

    /**
     * O jeito ERRADO. Se não controlarmos, as VTs sobrecarregarão o recurso downstream (banco).
     */
    public static void exemploSemSemaforo() {
        System.out.println("Iniciando sobrecarga...");
        try (ExecutorService executor = Executors.newThreadPerTaskExecutor(UNPROTECTED_FACTORY)) {
            List<Future<?>> futures = new ArrayList<>();
            for (int i = 0; i < 500; i++) {
                final int id = i;
                futures.add(executor.submit(() -> simularAcessoAoBanco(id)));
            }

            // Aguardando finalização
            futures.forEach(f -> {
                try {
                    f.get(5, TimeUnit.SECONDS);
                } catch (Exception e) {
                    // Ignorado
                }
            });
        }
    }

    /**
     * O jeito CORRETO. Uso de Semáforo para aplicar 'backpressure' na camada da aplicação.
     */
    public static void exemploComSemaforo() {
        System.out.println("Iniciando acesso protegido com semáforo...");
        Instant inicio = Instant.now();

        try (ExecutorService executor = Executors.newThreadPerTaskExecutor(PROTECTED_FACTORY)) {
            List<Future<?>> futures = new ArrayList<>();

            for (int i = 0; i < 500; i++) {
                final int id = i;
                Future<?> future = executor.submit(() -> {
                    try {
                        // tryAcquire com timeout previne bloqueios indefinidos na thread
                        if (semaphore.tryAcquire(5, TimeUnit.SECONDS)) {
                            try {
                                simularAcessoAoBanco(id);
                            } finally {
                                semaphore.release(); // Liberação garantida no finally
                            }
                        } else {
                            System.err.println(Thread.currentThread().getName() + " falhou ao obter conexão (timeout).");
                        }
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                });
                futures.add(future);
            }

            for (Future<?> f : futures) {
                try {
                    f.get(10, TimeUnit.SECONDS);
                } catch (Exception e) {
                    // Ignorado
                }
            }
        }

        Instant fim = Instant.now();
        System.out.println("Finalizado (com controle) em " + Duration.between(inicio, fim).toMillis() + " ms.");
    }

    /**
     * Simula o recurso externo I/O bound.
     */
    public static void simularAcessoAoBanco(int id) {
        int atual = conexoesAtivas.incrementAndGet();
        if (atual > MAX_CONEXOES_BD) {
            System.err.println("ALERTA CRÍTICO: " + atual + " conexões abertas! Limite de " + MAX_CONEXOES_BD + " excedido.");
        }

        try {
            // Simula o tempo da query ao banco (I/O bloqueante)
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
}
