package com.example.paralelismovirthr.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;

/**
 * O que é: configuração do bean de {@link ExecutorService} dedicado a pipelines assíncronos.
 * <p>
 * Mecânica: instancia um executor por tarefa (per-task) onde cada execução gera uma nova
 * virtual thread montada temporariamente sobre carriers de um ForkJoinPool dedicado.
 * <p>
 * Quando usar: para injeção explícita em chamadas de I/O em {@code CompletableFuture.supplyAsync},
 * coexistindo com o executor web configurado automaticamente pelo Spring Boot.
 * <p>
 * Quando não usar: nunca crie pool fixo ou limitado de virtual threads; pool clássico
 * serve para amortizar o custo de platform threads do SO, custo inexistente em virtual threads.
 * <p>
 * Risco: omitir {@code destroyMethod = "close"}, provocando vazamento de threads não finalizadas
 * no encerramento da aplicação, ou omitir {@code mode=force} no YAML fazendo o Boot recuar.
 * <p>
 * Como observar: inspecionar o prefixo das threads geradas no dump via {@code jcmd <pid> Thread.dump_to_file}.
 * <p>
 * Leitura: Rahman cap. 3 (mecânica de execução e ausência de pool em virtual threads) e Shvets (Factory).
 */
@Configuration
public class VirtualThreadConfig {

    /**
     * Produz o bean de {@link ExecutorService} baseado em Virtual Threads nomeadas.
     * <p>
     * Ponto: provê executor per-task para injeção direta em {@code CompletableFuture}.
     * Invariante: {@code destroyMethod = "close"} garante que o ciclo de vida do executor
     * execute {@code shutdown()} e aguarde a conclusão das tarefas ativas ao parar a aplicação.
     *
     * @return instância de {@link ExecutorService} que cria uma nova virtual thread por tarefa
     */
    @Bean(destroyMethod = "close")
    public ExecutorService virtualThreadExecutor() {
        // Factory nomeada para facilitar a observabilidade e rastreabilidade em thread dumps do JDK.
        ThreadFactory factory = Thread.ofVirtual().name("vt-shared-", 0).factory();
        // Criação sob demanda por tarefa: sem retenção nem fila fixa de pool de threads.
        return Executors.newThreadPerTaskExecutor(factory);
    }
}
