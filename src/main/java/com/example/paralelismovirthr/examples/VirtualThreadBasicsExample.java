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
 * O que é: demonstração prática dos fundamentos de Virtual Threads no Java 25 LTS (JEP 444).
 * <p>
 * Mecânica: uma virtual thread é uma continuação com pilha na heap; ao rodar, é montada
 * sobre uma carrier (platform thread de um ForkJoinPool dedicado). Durante I/O ou espera
 * desmontável (como sleep), a JVM faz o unmount e libera a carrier para atender outras VTs.
 * Pense na carrier como um garçom que atende dezenas de mesas enquanto os pratos cozinham.
 * <p>
 * Quando usar: em cenários de concorrência com alto throughput de I/O bloqueante (HTTP, JDBC).
 * <p>
 * Quando não usar: para tarefas estritamente CPU-bound; processamento numérico exige cores
 * reais e deve usar platform threads ou ForkJoinPool dimensionado aos processadores.
 * <p>
 * Risco: fazer pool de virtual threads ou supor paralelismo computacional onde há apenas
 * concorrência de sobreposição de esperas; ignorar que {@code close()} no try-with-resources é o escopo.
 * <p>
 * Como observar: inspecionar flags via {@code Thread.isVirtual()} e dumps com {@code jcmd <pid> Thread.dump_to_file}.
 * <p>
 * Leitura: Rahman cap. 1 a 3 (evolução e mecânica de mount/unmount) e cap. 4 (concorrência).
 */
public class VirtualThreadBasicsExample {

    /**
     * Executa a sequência didática de exemplos sobre fundamentos de Virtual Threads.
     * <p>
     * Ponto: coordena a execução sequencial de cada caso demonstrativo a partir da main thread.
     * Invariante: cada exemplo lida com o término de suas threads antes de prosseguir.
     *
     * @param args argumentos de linha de comando passados na inicialização
     */
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

    /**
     * Compara diretamente atributos fundamentais entre Platform Threads e Virtual Threads.
     * <p>
     * Ponto: demonstra a diferença em {@code isVirtual()} e a natureza daemon inerente das VTs.
     * Invariante: Virtual Threads são sempre daemon e ignoram prioridades de thread legadas;
     * o método aguarda ambas com {@code join()} para impedir o encerramento prematuro da JVM.
     */
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
            // Aguarda o término das threads; sem o join a thread daemon seria encerrada com o fim da main.
            platformThread.join();
            virtualThread.join();
        } catch (InterruptedException e) {
            // Restaura o status de interrupção da thread atual.
            Thread.currentThread().interrupt();
            System.err.println("Thread interrompida: " + e.getMessage());
        }
    }

    /**
     * Demonstra as três formas canônicas de criação de Virtual Threads no Java moderno.
     * <p>
     * Ponto: ilustra criação direta via builder fluente, via {@link ThreadFactory} e via {@link ExecutorService}.
     * Invariante: o try-with-resources no executor executa {@code close()}, que bloqueia até que
     * todas as tarefas submetidas concluam sua execução, estabelecendo um escopo implícito.
     */
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
        // O try-with-resources chama close() do executor, que espera o término de todas as tarefas.
        try (ExecutorService executor = Executors.newThreadPerTaskExecutor(executorFactory)) {
            executor.submit(() ->
                    System.out.println("Criada via ExecutorService (tarefa 1): " + Thread.currentThread().getName()));
            executor.submit(() ->
                    System.out.println("Criada via ExecutorService (tarefa 2): " + Thread.currentThread().getName()));
        }
    }

    /**
     * Demonstra a criação massiva de 10.000 Virtual Threads com bloqueio concorrente.
     * <p>
     * Ponto: evidencia a mecânica de unmount onde 10.000 esperas de 1s concluem em ~1 segundo.
     * Invariante: a JVM não aloca 10.000 platform threads do SO; as continuações residem na heap
     * e liberam as poucas carriers do pool interno durante o {@code Thread.sleep}.
     */
    public static void exemploMilharesDeThreads() {
        Instant inicio = Instant.now();
        List<Thread> threads = new ArrayList<>();

        for (int i = 0; i < 10_000; i++) {
            // Continuação instanciada na heap com unstarted sem iniciar imediatamente.
            Thread t = Thread.ofVirtual().name("vt-massiva-", i).unstarted(() -> {
                try {
                    // No bloqueio de I/O ou sleep, a VT é desmontada e a carrier atende outra tarefa.
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
     * <p>
     * Ponto: orquestra tarefas concorrentes delimitando o ciclo de vida via {@code Future.get(timeout)}.
     * Invariante: {@code StructuredTaskScope} permanece preview no JDK 25 (JEP 505) e não entra aqui;
     * o cancelamento e limite de espera estáveis são exercidos por timeouts individuais em cada {@link Future}.
     */
    public static void exemploFanOut() {
        ThreadFactory fanoutFactory = Thread.ofVirtual().name("vt-fanout-http-", 1).factory();
        // O try-with-resources define o escopo: aguarda as tarefas ativas finalizarem no encerramento.
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
                    // Timeout explícito por tarefa: mitiga tarefas zumbis sem requerer APIs de preview.
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
