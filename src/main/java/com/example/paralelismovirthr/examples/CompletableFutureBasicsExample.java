package com.example.paralelismovirthr.examples;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;

/**
 * O que é: guia didático sobre orquestração assíncrona de pipelines com {@link CompletableFuture} sobre Virtual Threads.
 * <p>
 * Mecânica: CompletableFuture define o grafo de estágios (stages) e dependências de dados;
 * as Virtual Threads fornecem a escala de execução sem bloquear platform threads do SO.
 * Pense no Future como uma senha de pedido: ele avisa quando o resultado estiver pronto sem que você fique parado no balcão.
 * <p>
 * Quando usar: para compor resultados heterogêneos em paralelo (ex.: chamar múltiplos microserviços independentes).
 * <p>
 * Quando não usar: para chamadas de I/O simples e sequenciais em controladores MVC, onde o código síncrono
 * direto sobre Virtual Threads é muito mais legível; nunca use métodos assíncronos sem executor explícito.
 * <p>
 * Risco: supor que {@code orTimeout} ou {@code completeOnTimeout} cancelam a thread ou o socket em andamento
 * (o I/O subjacente continua rodando até o timeout do cliente downstream), ou assumir que {@code allOf} cancela os irmãos na falha.
 * <p>
 * Como observar: inspecionar o nome da thread nos callbacks e auditar exceções através de handlers terminais.
 * <p>
 * Leitura: Rahman cap. 1, 4 (concorrência não estruturada) e 6 (orquestração assíncrona vs reativo).
 */
public class CompletableFutureBasicsExample {

    private static final ThreadFactory factory = Thread.ofVirtual().name("vt-cf-basics-", 1).factory();

    /**
     * Executa a série demonstrativa de padrões de orquestração com CompletableFuture.
     * <p>
     * Ponto: coordena exemplos de disparo assíncrono, encadeamento, recuperação de erros e combinação.
     * Invariante: o {@link ExecutorService} per-task é compartilhado no bloco try-with-resources,
     * garantindo o encerramento ordenado e drenagem das tarefas ao final da demonstração.
     *
     * @param args argumentos de linha de comando
     */
    public static void main(String[] args) {
        try (ExecutorService executor = Executors.newThreadPerTaskExecutor(factory)) {
            exemploSupplyAsync(executor);
            exemploEncadeamento(executor);
            exemploTratamentoDeErros(executor);
            exemploTimeout(executor);
            exemploCombinacoes(executor);
        }
    }

    /**
     * Demonstra a submissão assíncrona básica com executor explícito e tratamento terminal.
     * <p>
     * Ponto: ilustra o uso de {@code supplyAsync} com executor de virtual threads associado a timeout.
     * Invariante: chamadas assíncronas com I/O devem sempre receber o executor explicitamente para evitar o {@code commonPool}.
     *
     * @param executor executor configurado em virtual threads
     */
    public static void exemploSupplyAsync(ExecutorService executor) {
        System.out.println("\n--- Exemplo: supplyAsync ---");
        // supplyAsync com executor explícito de VT: sem executor o processamento cairia no ForkJoinPool.commonPool.
        CompletableFuture<String> future = CompletableFuture.supplyAsync(() -> {
                    simulateSlowOperation(1000);
                    return "Resultado da operação lenta";
                }, executor)
                .orTimeout(2, TimeUnit.SECONDS)
                .exceptionally(ex -> "Erro: " + ex.getMessage());

        System.out.println("Operação iniciada. Fazendo outras coisas...");
        String result = future.join();
        System.out.println("Resultado: " + result);
    }

    /**
     * Demonstra encadeamento e transformação de dados em múltiplos estágios do pipeline.
     * <p>
     * Ponto: encadeia transformações síncronas usando {@code thenApply} e consumo terminal com {@code thenAccept}.
     * Invariante: {@code thenApply} executa na mesma thread do estágio anterior caso já finalizado, sem custo de troca de contexto.
     *
     * @param executor executor de virtual threads
     */
    public static void exemploEncadeamento(ExecutorService executor) {
        System.out.println("\n--- Exemplo: Encadeamento ---");
        // Encadeamento linear: cada estágio é disparado sequencialmente assim que o dado se torna disponível.
        CompletableFuture.supplyAsync(() -> {
                    simulateSlowOperation(500);
                    return 10;
                }, executor)
                .orTimeout(1, TimeUnit.SECONDS)
                .thenApply(valor -> valor * 2)
                .thenApply(valor -> "Valor processado: " + valor)
                .thenAccept(System.out::println)
                .exceptionally(ex -> {
                    System.err.println("Falha no pipeline: " + ex.getMessage());
                    return null;
                })
                .join();
    }

    /**
     * Demonstra estratégias de tratamento de exceções com {@code exceptionally} e {@code handle}.
     * <p>
     * Ponto: contrasta fallback condicional na falha com interceptação unificada de valor e erro.
     * Invariante: pipelines sem handler terminal propagam {@link java.util.concurrent.CompletionException} silenciosamente.
     *
     * @param executor executor de virtual threads
     */
    public static void exemploTratamentoDeErros(ExecutorService executor) {
        System.out.println("\n--- Exemplo: Tratamento de Erros ---");

        // exceptionally trata apenas exceções, retornando um valor substituto padrão (fallback).
        String resultExceptionally = CompletableFuture.<String>supplyAsync(() -> {
                    throw new RuntimeException("Erro forçado");
                }, executor)
                .orTimeout(1, TimeUnit.SECONDS)
                .exceptionally(ex -> "Fallback devido a: " + ex.getMessage())
                .join();
        System.out.println("Com exceptionally: " + resultExceptionally);

        // handle recebe tanto o resultado quanto a exceção, permitindo tradução e enriquecimento unificado.
        String resultHandle = CompletableFuture.supplyAsync(() -> {
                    simulateSlowOperation(100);
                    return "Sucesso!";
                }, executor)
                .orTimeout(1, TimeUnit.SECONDS)
                .handle((res, ex) -> ex != null ? "Falhou: " + ex.getMessage() : "Sucesso: " + res)
                .join();
        System.out.println("Com handle: " + resultHandle);

        CompletableFuture.supplyAsync(() -> {
                    simulateSlowOperation(100);
                    return "Passo 1";
                }, executor)
                .orTimeout(1, TimeUnit.SECONDS)
                .thenApply(res -> {
                    throw new RuntimeException("Erro no passo 2");
                })
                .thenApply(res -> res + " Passo 3")
                .exceptionally(ex -> "Tratamento terminal global pegou o erro: " + ex.getMessage())
                .thenAccept(System.out::println)
                .join();
    }

    /**
     * Demonstra comportamentos de timeout com {@code orTimeout} e {@code completeOnTimeout}.
     * <p>
     * Ponto: evidencia que o timeout afeta o Future, mas não cancela a thread ou o socket em andamento.
     * Invariante: {@code orTimeout} completa com exceção; {@code completeOnTimeout} completa com valor default de contingência.
     *
     * @param executor executor de virtual threads
     */
    public static void exemploTimeout(ExecutorService executor) {
        System.out.println("\n--- Exemplo: Timeouts ---");

        try {
            // orTimeout completa o Future excepcionalmente; a tarefa submetida continua até o encerramento natural do seu I/O.
            CompletableFuture.supplyAsync(() -> {
                        simulateSlowOperation(3000);
                        return "Dados lentos";
                    }, executor)
                    .orTimeout(1, TimeUnit.SECONDS)
                    .join();
        } catch (Exception e) {
            System.out.println("orTimeout acionado: " + e.getMessage());
        }

        // completeOnTimeout fornece valor default sem propagar exceção caso o tempo limite seja atingido.
        String fallback = CompletableFuture.supplyAsync(() -> {
                    simulateSlowOperation(3000);
                    return "Dados lentos";
                }, executor)
                .completeOnTimeout("Dados de fallback rápido", 1, TimeUnit.SECONDS)
                .exceptionally(ex -> "Erro terminal: " + ex.getMessage())
                .join();
        System.out.println("completeOnTimeout retornou: " + fallback);
    }

    /**
     * Demonstra combinação de futuros através de {@code thenCombine}, {@code allOf} e {@code anyOf}.
     * <p>
     * Ponto: expõe os desafios da concorrência não estruturada: {@code allOf} e {@code anyOf} não cancelam tarefas ativas remanescentes.
     * Invariante: se uma das tarefas de {@code allOf} falhar, as demais continuam rodando; {@code anyOf} não aborta o perdedor da corrida.
     *
     * @param executor executor de virtual threads
     */
    public static void exemploCombinacoes(ExecutorService executor) {
        System.out.println("\n--- Exemplo: Combinações ---");

        CompletableFuture<String> c1 = CompletableFuture.supplyAsync(() -> {
            simulateSlowOperation(500);
            return "Hello";
        }, executor).orTimeout(1, TimeUnit.SECONDS);

        CompletableFuture<String> c2 = CompletableFuture.supplyAsync(() -> {
            simulateSlowOperation(300);
            return "World";
        }, executor).orTimeout(1, TimeUnit.SECONDS);

        // thenCombine une dois resultados independentes quando ambos estiverem concluídos.
        c1.thenCombine(c2, (res1, res2) -> res1 + " " + res2)
                .exceptionally(ex -> "Erro ao combinar: " + ex.getMessage())
                .thenAccept(System.out::println)
                .join();

        CompletableFuture<String> taskA = CompletableFuture.supplyAsync(() -> {
            simulateSlowOperation(200);
            return "Task A";
        }, executor).orTimeout(1, TimeUnit.SECONDS);

        CompletableFuture<String> taskB = CompletableFuture.supplyAsync(() -> {
            simulateSlowOperation(300);
            return "Task B";
        }, executor).orTimeout(1, TimeUnit.SECONDS);

        // allOf espera todos os futuros, mas não tem escopo pai: falha em um não cancela automaticamente o outro.
        CompletableFuture.allOf(taskA, taskB)
                .orTimeout(2, TimeUnit.SECONDS)
                .exceptionally(ex -> {
                    System.err.println("Erro no allOf: " + ex.getMessage());
                    return null;
                })
                .join();
        System.out.println("Todos finalizados: " + taskA.join() + ", " + taskB.join());

        CompletableFuture<String> fastTask = CompletableFuture.supplyAsync(() -> {
            simulateSlowOperation(100);
            return "Mais Rápido";
        }, executor).orTimeout(1, TimeUnit.SECONDS);

        CompletableFuture<String> slowTask = CompletableFuture.supplyAsync(() -> {
            simulateSlowOperation(500);
            return "Mais Lento";
        }, executor).orTimeout(1, TimeUnit.SECONDS);

        // anyOf retorna o primeiro a responder, mas deixa a tarefa perdedora em execução até o final.
        Object primeiro = CompletableFuture.anyOf(fastTask, slowTask)
                .orTimeout(1, TimeUnit.SECONDS)
                .exceptionally(ex -> "Erro anyOf: " + ex.getMessage())
                .join();
        System.out.println("O primeiro completou com: " + primeiro);
        System.out.println("anyOf não cancela a tarefa lenta — ela segue até o fim.");
    }

    private static void simulateSlowOperation(int millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
