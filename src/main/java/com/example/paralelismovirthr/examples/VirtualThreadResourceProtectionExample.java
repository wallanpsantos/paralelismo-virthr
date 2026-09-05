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
 * O que é: demonstração de controle de backpressure e proteção de recursos finitos sob concorrência massiva de VTs.
 * <p>
 * Mecânica: enquanto as virtual threads eliminam o custo de concorrência na JVM, recursos downstream
 * como bancos de dados possuem conexões finitas. O {@link Semaphore} atua como uma catraca de controle
 * de admissão (permits), desacoplando o volume massivo de VTs da capacidade máxima do pool JDBC.
 * <p>
 * Quando usar: ao integrar virtual threads com recursos de capacidade estrita (pools HikariCP, clientes HTTP legados).
 * <p>
 * Quando não usar: não use para limitar a criação de virtual threads na aplicação nem confunda com
 * controle transacional de saldo bancário (este mecanismo não substitui isolation ou locks de domínio).
 * <p>
 * Risco: chamar {@code acquire()} sem timeout (criando requisições zumbis em sobrecarga) ou esquecer o
 * {@code release()} fora do bloco {@code finally}, resultando em vazamento permanente de permits.
 * <p>
 * Como observar: monitorar métricas de conexões ativas do HikariCP e a contagem de permits disponíveis via {@link Semaphore#availablePermits()}.
 * <p>
 * Leitura: Rahman cap. 2 (rate limit e limites de VT), Tudose (recurso finito do pool JDBC) e Xu vol. 1 (backpressure).
 */
public class VirtualThreadResourceProtectionExample {

    public static final int MAX_CONEXOES_BD = 50;

    private static final Semaphore semaphore = new Semaphore(MAX_CONEXOES_BD);
    private static final AtomicInteger conexoesAtivas = new AtomicInteger(0);

    private static final ThreadFactory UNPROTECTED_FACTORY = Thread.ofVirtual().name("vt-unprotected-", 1).factory();
    private static final ThreadFactory PROTECTED_FACTORY = Thread.ofVirtual().name("vt-protected-", 1).factory();

    /**
     * Executa a comparação didática entre acesso desprotegido e acesso protegido por semáforo.
     * <p>
     * Ponto: contrasta a saturação imediata do recurso com a contenção ordenada por permits.
     * Invariante: cada cenário aguarda a finalização de todas as 500 tarefas antes de avançar.
     *
     * @param args argumentos de linha de comando passados na inicialização
     */
    public static void main(String[] args) {
        System.out.println("--- SEM Semáforo (o problema) ---");
        exemploSemSemaforo();

        System.out.println("\n--- COM Semáforo (a solução) ---");
        exemploComSemaforo();
    }

    /**
     * Demonstra a sobrecarga gerada quando 500 Virtual Threads acessam concorrentemente um recurso limitado.
     * <p>
     * Ponto: evidencia que a ausência de barreira de admissão estoura o limite de conexões do pool.
     * Invariante: como não há controle de admissão, a contagem simultânea ultrapassa {@link #MAX_CONEXOES_BD}.
     */
    public static void exemploSemSemaforo() {
        System.out.println("Iniciando sobrecarga...");
        try (ExecutorService executor = Executors.newThreadPerTaskExecutor(UNPROTECTED_FACTORY)) {
            List<Future<?>> futures = new ArrayList<>();
            for (int i = 0; i < 500; i++) {
                final int id = i;
                // 500 VTs concorrentes disputam o recurso simultaneamente sem qualquer rate limit downstream.
                futures.add(executor.submit(() -> simularAcessoAoBanco(id)));
            }
            aguardar(futures, 5);
        }
    }

    /**
     * Demonstra o padrão correto de proteção de recurso downstream utilizando {@link Semaphore}.
     * <p>
     * Ponto: alinha os permits do semáforo ao {@code maximumPoolSize} do banco de dados (50 permits).
     * Invariante: {@code tryAcquire(timeout)} impede threads zumbis e o bloco {@code finally release()}
     * assegura que conexões retornem sempre ao controle do pool mesmo sob falhas.
     */
    public static void exemploComSemaforo() {
        System.out.println("Iniciando acesso protegido com semáforo...");
        Instant inicio = Instant.now();

        try (ExecutorService executor = Executors.newThreadPerTaskExecutor(PROTECTED_FACTORY)) {
            List<Future<?>> futures = new ArrayList<>();
            for (int i = 0; i < 500; i++) {
                final int id = i;
                futures.add(executor.submit(() -> {
                    try {
                        // tryAcquire com timeout: limita o recurso a 50 acessos simultâneos sem travar threads eternamente.
                        if (semaphore.tryAcquire(5, TimeUnit.SECONDS)) {
                            try {
                                simularAcessoAoBanco(id);
                            } finally {
                                // Liberação obrigatória do permit para manter a capacidade do semáforo íntegra.
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

    /**
     * Simula a execução de consulta JDBC com retenção temporária de conexão.
     * <p>
     * Ponto: registra a concorrência ativa através de contador atômico para auditar estouramento.
     * Invariante: decrementa o contador atômico no bloco {@code finally} simulando a devolução da conexão ao pool.
     *
     * @param id identificador numérico da operação simulada
     */
    public static void simularAcessoAoBanco(int id) {
        int atual = conexoesAtivas.incrementAndGet();
        // Alerta disparado quando a quantidade de acessos simultâneos excede o limite tolerado pelo banco.
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

    /**
     * Retorna a quantidade instantânea de conexões ativas simuladas.
     * <p>
     * Ponto: provê visibilidade do nível de concorrência em tempo de execução para asserções e métricas.
     * Invariante: reflete a leitura atômica consistente do contador de conexões ativas.
     *
     * @return número de conexões ativas no momento da chamada
     */
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
