package com.example.paralelismovirthr.examples;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

/**
 * O que é: árvore de decisão pragmática (Strategy de modelo de execução) combinando Virtual Threads e CompletableFuture no Java 25 LTS.
 * <p>
 * Mecânica: integra o estilo imperativo simples de Virtual Threads com as capacidades funcionais
 * de composição, timeout e fallback providas por CompletableFuture, ambas rodando sobre carriers dedicadas.
 * <p>
 * Quando usar: para I/O próprio e direto, prefira o modelo imperativo simples (VT + {@code Future.get(timeout)});
 * para timeout com fallback declarativo ou agregação de duas fontes assíncronas, use {@code CompletableFuture} com executor explícito de VTs.
 * <p>
 * Quando não usar: isto não é stream reativo (SSE, WebSocket ou backpressure de push exigem WebFlux/reativo nativo);
 * nunca misture WebFlux ou crie complexidade reativa desnecessária para fluxos de request/response e JDBC bloqueante.
 * <p>
 * Risco: confundir os modelos e introduzir encadeamentos assíncronos excessivos em chamadas de I/O simples,
 * ou negligenciar que {@code orTimeout(3s)} cobre o tempo total do pipeline e não cancela as operações subjacentes.
 * <p>
 * Como observar: inspecionar logs com o prefixo da thread do executor de VTs e tempos de resposta agregados.
 * <p>
 * Leitura: Rahman cap. 6 (Virtual Threads vs Programação Reativa) e Shvets (Strategy de execução).
 */
public class VirtualThreadsComCompletableFutureExample {

    private static final ThreadFactory VT_FACTORY = Thread.ofVirtual().name("vt-cf-", 0).factory();

    /**
     * Ponto de entrada que executa os diferentes ramos da estratégia de concorrência.
     * <p>
     * Ponto: demonstra a execução de cada padrão (VT simples, fallback, composição e fan-out).
     * Invariante: cada chamada encapsula seu próprio ciclo de vida de tarefas sem vazamentos.
     *
     * @param args argumentos de linha de comando passados na inicialização
     */
    public static void main(String[] args) {
        VirtualThreadsComCompletableFutureExample example = new VirtualThreadsComCompletableFutureExample();
        example.exemploCombinadoVtComCf();
        example.exemploFanOutComCf();
        System.out.println(example.buscarUsuarioSimples());
        System.out.println(example.buscarUsuarioComFallback());
        System.out.println(example.buscarDadosCompostos());
    }

    /**
     * Demonstra a combinação de duas operações de I/O assíncronas em paralelo com timeout conjunto.
     * <p>
     * Ponto: utiliza {@code thenCombine} sobre executor de Virtual Threads com barreira de {@code orTimeout}.
     * Invariante: o timeout de 3 segundos cobre o processamento concorrente das tarefas (1000ms e 1500ms).
     */
    public void exemploCombinadoVtComCf() {
        try (ExecutorService executor = Executors.newThreadPerTaskExecutor(VT_FACTORY)) {
            CompletableFuture<String> task1 = CompletableFuture.supplyAsync(() -> {
                simularIoBloqueante(1000);
                return "Resultado A";
            }, executor);

            CompletableFuture<String> task2 = CompletableFuture.supplyAsync(() -> {
                simularIoBloqueante(1500);
                return "Resultado B";
            }, executor);

            // orTimeout(3s) cobre a soma concorrente das esperas (1000ms e 1500ms), encerrando o estágio se excedido.
            task1.thenCombine(task2, (a, b) -> a + " e " + b)
                    .orTimeout(3, TimeUnit.SECONDS)
                    .exceptionally(ex -> {
                        System.err.println("Erro durante a combinação: " + ex.getMessage());
                        return "Fallback Resultado";
                    })
                    .thenAccept(resultado -> System.out.println("Combinação concluída: " + resultado))
                    .join();
        }
    }

    /**
     * Demonstra padrão de fan-out com recuperação de falhas parciais usando {@code allOf} e {@code getNow}.
     * <p>
     * Ponto: agrega múltiplos futuros tratando falhas por item sem abortar a coleção completa.
     * Invariante: o manipulador {@code handle} inspeciona o estado final de cada futuro individual após a barreira coletiva.
     */
    public void exemploFanOutComCf() {
        try (ExecutorService executor = Executors.newThreadPerTaskExecutor(VT_FACTORY)) {
            List<CompletableFuture<String>> futures = IntStream.range(1, 6)
                    .mapToObj(i -> CompletableFuture.supplyAsync(() -> {
                                simularIoBloqueante(500 + (i * 100));
                                if (i == 4) {
                                    throw new RuntimeException("Falha simulada na task 4");
                                }
                                return "Dado " + i;
                            }, executor)
                            .orTimeout(3, TimeUnit.SECONDS)
                            .exceptionally(ex -> "Fallback " + i))
                    .toList();

            // allOf orquestra a espera conjunta; o handle com getNow() recupera resultados individuais tratando falhas parciais.
            CompletableFuture.allOf(futures.toArray(new CompletableFuture[0]))
                    .orTimeout(5, TimeUnit.SECONDS)
                    .handle((res, ex) -> futures.stream()
                            .map(cf -> cf.getNow("Fallback"))
                            .collect(Collectors.joining(", ")))
                    .thenAccept(resultados -> System.out.println("Resultados do Fan-out: " + resultados))
                    .join();
        }
    }

    /**
     * Estratégia 1: I/O simples sob controle direto da aplicação.
     * <p>
     * Ponto: adota o estilo imperativo clássico com Virtual Thread direta e {@code Future.get(timeout)}.
     * Invariante: para I/O simples, o código sequencial sobre VTs é preferível a cadeias complexas de callbacks.
     *
     * @return dado textual retornado da operação síncrona
     */
    public String buscarUsuarioSimples() {
        try (ExecutorService executor = Executors.newThreadPerTaskExecutor(VT_FACTORY)) {
            // Modelo imperativo simples: para I/O sob seu controle, Virtual Thread direta com get(timeout) é o código mais limpo.
            return executor.submit(() -> {
                simularIoBloqueante(500);
                return "Usuario Simples";
            }).get(2, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            // Restaura o sinal de interrupção da thread atual.
            Thread.currentThread().interrupt();
            return "Erro";
        } catch (ExecutionException | TimeoutException e) {
            System.err.println("Erro na busca simples: " + e.getMessage());
            return "Erro";
        }
    }

    /**
     * Estratégia 2: I/O com necessidade de timeout e fallback declarativo.
     * <p>
     * Ponto: combina CompletableFuture com Virtual Threads para recuperar de lentidão via {@code exceptionally}.
     * Invariante: {@code orTimeout} encerra o Future no tempo estipulado, devolvendo o fallback seguro.
     *
     * @return dado obtido ou fallback caso a operação exceda o timeout
     */
    public String buscarUsuarioComFallback() {
        try (ExecutorService executor = Executors.newThreadPerTaskExecutor(VT_FACTORY)) {
            // Modelo assíncrono com fallback: orTimeout dispara excepcionalmente e exceptionally injeta o dado de contingência.
            return CompletableFuture.supplyAsync(() -> {
                        simularIoBloqueante(3000);
                        return "Usuario Original";
                    }, executor)
                    .orTimeout(1, TimeUnit.SECONDS)
                    .exceptionally(ex -> {
                        System.err.println("Timeout atingido, acionando fallback: " + ex.getMessage());
                        return "Usuario Fallback";
                    })
                    .join();
        }
    }

    /**
     * Estratégia 3: composição de duas fontes de dados independentes.
     * <p>
     * Ponto: paraleliza a recuperação de dados distintos unificando-os através de {@code thenCombine}.
     * Invariante: ambas as requisições executam concorrentemente em VTs distintas sem bloqueio mútuo.
     *
     * @return combinação concatenada das duas fontes de dados
     */
    public String buscarDadosCompostos() {
        try (ExecutorService executor = Executors.newThreadPerTaskExecutor(VT_FACTORY)) {
            CompletableFuture<String> user = CompletableFuture.supplyAsync(() -> {
                simularIoBloqueante(500);
                return "User123";
            }, executor);

            CompletableFuture<String> preferences = CompletableFuture.supplyAsync(() -> {
                simularIoBloqueante(600);
                return "Modo Escuro";
            }, executor);

            // Composição de duas fontes heterogêneas: dispara em paralelo e unifica o resultado via thenCombine.
            return user.thenCombine(preferences, (u, p) -> u + " -> " + p)
                    .orTimeout(2, TimeUnit.SECONDS)
                    .exceptionally(ex -> "Erro na composição: " + ex.getMessage())
                    .join();
        }
    }

    private void simularIoBloqueante(int millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
