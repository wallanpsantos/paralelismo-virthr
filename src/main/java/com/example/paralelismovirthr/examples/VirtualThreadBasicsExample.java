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
 * Exemplo básico sobre Virtual Threads no Java 21 (JEP 444 - General Availability).
 * <p>
 * O que são:
 * Threads leves gerenciadas pela própria JVM em vez do sistema operacional.
 * Milhões de Virtual Threads (VTs) podem rodar sobre um pequeno número de Platform Threads (Carrier Threads).
 * <p>
 * Quando usar:
 * - Cenários de I/O-bound (ex: chamadas HTTP, banco de dados, acesso a arquivos).
 * - Quando há muitas tarefas bloqueantes concorrentes.
 * <p>
 * Quando NÃO usar:
 * - Cenários de CPU-bound pesados (ex: processamento de imagem, cálculos matemáticos complexos).
 * - Para isso, Platform Threads normais e paralelismo de dados (Streams) são mais indicados.
 * <p>
 * Regras de ouro:
 * - Não faça pool de Virtual Threads. Crie-as sob demanda por tarefa (newThreadPerTaskExecutor).
 * - Elas são daemon threads por padrão e não impedem a JVM de encerrar.
 * - Padronize a nomenclatura usando ThreadFactory para observabilidade.
 * <p>
 * Diagnóstico e Thread Dump com Virtual Threads (JDK 21):
 * O comando tradicional 'jstack' NÃO inclui Virtual Threads. Para inspecionar Virtual Threads em execução:
 * {@code
 *   jcmd <pid> Thread.dump_to_file -format=json /caminho/thread-dump.json
 *   ou
 *   jcmd <pid> Thread.dump_to_file -format=text /caminho/thread-dump.txt
 * }
 * O dump JSON categoriza threads de plataforma e agrupa milhares de Virtual Threads por factory/prefixo.
 */
public class VirtualThreadBasicsExample {

    public static void main(String[] args) {
        System.out.println("--- Exemplo Plataforma vs Virtual ---");
        exemploPlataformaVsVirtual();

        System.out.println("\n--- Exemplo Criação de Virtual Threads ---");
        exemploCriacaoVirtualThreads();

        System.out.println("\n--- Exemplo Milhares de Threads ---");
        exemploMilharesDeThreads();

        System.out.println("\n--- Exemplo Fan-Out ---");
        exemploFanOut();
    }

    /**
     * Compara a criação e propriedades de Platform e Virtual Threads.
     */
    public static void exemploPlataformaVsVirtual() {
        // Platform Thread
        Thread platformThread = Thread.ofPlatform().name("platform-thread-1").start(() -> {
            System.out.println("Executando: " + Thread.currentThread());
            System.out.println("É virtual? " + Thread.currentThread().isVirtual());
            System.out.println("É daemon? " + Thread.currentThread().isDaemon());
        });

        // Virtual Thread
        Thread virtualThread = Thread.ofVirtual().name("virtual-thread-1").start(() -> {
            System.out.println("Executando: " + Thread.currentThread());
            System.out.println("É virtual? " + Thread.currentThread().isVirtual());
            // VTs são daemon por padrão.
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

    /**
     * Demonstra 3 formas diferentes de criar Virtual Threads.
     */
    public static void exemploCriacaoVirtualThreads() {
        // 1. Builder direto
        Thread vThread1 = Thread.ofVirtual().name("vt-builder-1").start(() -> {
            System.out.println("Criada pelo builder direto: " + Thread.currentThread().getName());
        });

        // 2. Usando um ThreadFactory (prática recomendada para nomeação padronizada)
        ThreadFactory factory = Thread.ofVirtual().name("vt-factory-", 1).factory();
        Thread vThread2 = factory.newThread(() -> {
            System.out.println("Criada pelo factory: " + Thread.currentThread().getName());
        });
        vThread2.start();

        try {
            vThread1.join();
            vThread2.join();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }

        // 3. Usando ExecutorService com ThreadFactory nomeada e try-with-resources
        // O try-with-resources garante shutdown() e awaitTermination() automáticos
        ThreadFactory executorFactory = Thread.ofVirtual().name("vt-executor-", 1).factory();
        try (ExecutorService executor = Executors.newThreadPerTaskExecutor(executorFactory)) {
            executor.submit(() -> {
                System.out.println("Criada via ExecutorService (tarefa 1): " + Thread.currentThread().getName());
            });
            executor.submit(() -> {
                System.out.println("Criada via ExecutorService (tarefa 2): " + Thread.currentThread().getName());
            });
        }
    }

    /**
     * Demonstra a escalabilidade criando 10.000 Virtual Threads de forma leve.
     */
    public static void exemploMilharesDeThreads() {
        Instant inicio = Instant.now();
        List<Thread> threads = new ArrayList<>();

        for (int i = 0; i < 10000; i++) {
            Thread t = Thread.ofVirtual().name("vt-massiva-", i).unstarted(() -> {
                try {
                    // Simula uma chamada de I/O bloqueante (1 segundo)
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
        // Tempo total deverá ser próximo de 1 segundo, mesmo com 10.000 threads bloqueantes simultâneas.
        // O uso de Platform Threads poderia esgotar a memória da JVM e do SO neste mesmo cenário.
        System.out.println("Tempo total para 10.000 chamadas bloqueantes: " + Duration.between(inicio, fim).toMillis() + " ms");
    }

    /**
     * Demonstra o padrão Fan-Out: lançar várias tarefas concorrentes, coletar os resultados.
     */
    public static void exemploFanOut() {
        // Usamos ExecutorService com ThreadFactory padronizada em bloco try-with-resources
        ThreadFactory fanoutFactory = Thread.ofVirtual().name("vt-fanout-http-", 1).factory();
        try (ExecutorService executor = Executors.newThreadPerTaskExecutor(fanoutFactory)) {
            List<Future<String>> futures = new ArrayList<>();

            for (int i = 1; i <= 5; i++) {
                final int id = i;
                // Submete as chamadas concorrentes
                Future<String> future = executor.submit(() -> {
                    Thread.sleep(Duration.ofMillis(500)); // Simula I/O (ex: requisição HTTP)
                    return "Resposta da chamada " + id;
                });
                futures.add(future);
            }

            // Coleta os resultados
            for (Future<String> future : futures) {
                try {
                    // Imprescindível usar timeout no get()
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
