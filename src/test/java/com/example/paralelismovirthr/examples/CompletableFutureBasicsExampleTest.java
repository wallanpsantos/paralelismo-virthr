package com.example.paralelismovirthr.examples;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("Testes - fundamentos de CompletableFuture")
class CompletableFutureBasicsExampleTest {

    private final ThreadFactory factory = Thread.ofVirtual().name("vt-test-cf-", 1).factory();

    @Test
    void deveExecutarSupplyAsyncComEncadeamento() {
        try (ExecutorService executor = Executors.newThreadPerTaskExecutor(factory)) {
            String resultado = CompletableFuture.supplyAsync(() -> 10, executor)
                    .thenApply(valor -> valor * 3)
                    .thenApply(valor -> "Total: " + valor)
                    .orTimeout(2, TimeUnit.SECONDS)
                    .join();
            assertThat(resultado).isEqualTo("Total: 30");
        }
    }

    @Test
    void deveRetornarFallbackComExceptionally() {
        try (ExecutorService executor = Executors.newThreadPerTaskExecutor(factory)) {
            String resultado = CompletableFuture.<String>supplyAsync(() -> {
                        throw new IllegalStateException("Falha simulada");
                    }, executor)
                    .orTimeout(2, TimeUnit.SECONDS)
                    .exceptionally(ex -> "Fallback: " + ex.getCause().getMessage())
                    .join();
            assertThat(resultado).isEqualTo("Fallback: Falha simulada");
        }
    }

    @Test
    void deveTratarSucessoEErroComHandle() {
        try (ExecutorService executor = Executors.newThreadPerTaskExecutor(factory)) {
            String sucesso = CompletableFuture.supplyAsync(() -> "OK", executor)
                    .handle((res, ex) -> ex != null ? "Erro" : "Sucesso: " + res)
                    .join();
            String falha = CompletableFuture.<String>supplyAsync(() -> {
                        throw new RuntimeException("Erro");
                    }, executor)
                    .handle((res, ex) -> ex != null ? "Tratado: " + ex.getCause().getMessage() : res)
                    .join();
            assertThat(sucesso).isEqualTo("Sucesso: OK");
            assertThat(falha).isEqualTo("Tratado: Erro");
        }
    }

    @Test
    void deveRetornarValorPadraoComCompleteOnTimeout() {
        try (ExecutorService executor = Executors.newThreadPerTaskExecutor(factory)) {
            String resultado = CompletableFuture.supplyAsync(() -> {
                        try {
                            Thread.sleep(500);
                        } catch (InterruptedException e) {
                            Thread.currentThread().interrupt();
                        }
                        return "Resposta Tardia";
                    }, executor)
                    .completeOnTimeout("Valor Default", 50, TimeUnit.MILLISECONDS)
                    .join();
            assertThat(resultado).isEqualTo("Valor Default");
        }
    }

    @Test
    void deveCombinarDoisFuturesComThenCombine() {
        try (ExecutorService executor = Executors.newThreadPerTaskExecutor(factory)) {
            String combinado = CompletableFuture.supplyAsync(() -> "Hello", executor)
                    .thenCombine(CompletableFuture.supplyAsync(() -> "World", executor), (r1, r2) -> r1 + " " + r2)
                    .orTimeout(2, TimeUnit.SECONDS)
                    .join();
            assertThat(combinado).isEqualTo("Hello World");
        }
    }
}
