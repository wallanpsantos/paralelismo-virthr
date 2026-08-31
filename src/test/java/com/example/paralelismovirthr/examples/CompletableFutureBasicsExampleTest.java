package com.example.paralelismovirthr.examples;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("Testes Automatizados - Fundamentos de CompletableFuture com Virtual Threads")
class CompletableFutureBasicsExampleTest {

    private final ThreadFactory factory = Thread.ofVirtual().name("vt-test-cf-", 1).factory();

    @Test
    @DisplayName("Deve executar supplyAsync encadeado com thenApply e retornar valor transformado")
    void deveExecutarSupplyAsyncComEncadeamento() {
        // Given
        try (ExecutorService executor = Executors.newThreadPerTaskExecutor(factory)) {
            // When
            String resultado = CompletableFuture.supplyAsync(() -> 10, executor)
                    .thenApply(valor -> valor * 3)
                    .thenApply(valor -> "Total: " + valor)
                    .orTimeout(2, TimeUnit.SECONDS)
                    .join();

            // Then
            assertThat(resultado).isEqualTo("Total: 30");
        }
    }

    @Test
    @DisplayName("Deve capturar exceção e retornar valor de fallback com exceptionally")
    void deveRetornarFallbackComExceptionally() {
        // Given
        try (ExecutorService executor = Executors.newThreadPerTaskExecutor(factory)) {
            // When
            String resultado = CompletableFuture.<String>supplyAsync(() -> {
                        throw new IllegalStateException("Falha simulada");
                    }, executor)
                    .orTimeout(2, TimeUnit.SECONDS)
                    .exceptionally(ex -> "Fallback: " + ex.getCause().getMessage())
                    .join();

            // Then
            assertThat(resultado).isEqualTo("Fallback: Falha simulada");
        }
    }

    @Test
    @DisplayName("Deve tratar sucesso e erro unificados via handle")
    void deveTratarSucessoEErroComHandle() {
        // Given
        try (ExecutorService executor = Executors.newThreadPerTaskExecutor(factory)) {
            // When
            String sucesso = CompletableFuture.supplyAsync(() -> "OK", executor)
                    .handle((res, ex) -> ex != null ? "Erro" : "Sucesso: " + res)
                    .join();

            String falha = CompletableFuture.<String>supplyAsync(() -> {
                        throw new RuntimeException("Erro");
                    }, executor)
                    .handle((res, ex) -> ex != null ? "Tratado: " + ex.getCause().getMessage() : res)
                    .join();

            // Then
            assertThat(sucesso).isEqualTo("Sucesso: OK");
            assertThat(falha).isEqualTo("Tratado: Erro");
        }
    }

    @Test
    @DisplayName("Deve retornar valor padrão quando completeOnTimeout é acionado")
    void deveRetornarValorPadraoComCompleteOnTimeout() {
        // Given
        try (ExecutorService executor = Executors.newThreadPerTaskExecutor(factory)) {
            // When
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

            // Then
            assertThat(resultado).isEqualTo("Valor Default");
        }
    }

    @Test
    @DisplayName("Deve combinar dois futures independentes usando thenCombine")
    void deveCombinarDoisFuturesComThenCombine() {
        // Given
        try (ExecutorService executor = Executors.newThreadPerTaskExecutor(factory)) {
            CompletableFuture<String> f1 = CompletableFuture.supplyAsync(() -> "Hello", executor);
            CompletableFuture<String> f2 = CompletableFuture.supplyAsync(() -> "World", executor);

            // When
            String combinado = f1.thenCombine(f2, (r1, r2) -> r1 + " " + r2)
                    .orTimeout(2, TimeUnit.SECONDS)
                    .join();

            // Then
            assertThat(combinado).isEqualTo("Hello World");
        }
    }

    @Test
    @DisplayName("Deve aguardar múltiplos futures com allOf e coletar resultados individuais")
    void deveAguardarTodosOsFuturesComAllOf() {
        // Given
        try (ExecutorService executor = Executors.newThreadPerTaskExecutor(factory)) {
            CompletableFuture<String> f1 = CompletableFuture.supplyAsync(() -> "A", executor);
            CompletableFuture<String> f2 = CompletableFuture.supplyAsync(() -> "B", executor);
            CompletableFuture<String> f3 = CompletableFuture.supplyAsync(() -> "C", executor);

            // When
            CompletableFuture<Void> all = CompletableFuture.allOf(f1, f2, f3);
            all.join();

            // Then
            assertThat(all.isDone()).isTrue();
            assertThat(f1.join()).isEqualTo("A");
            assertThat(f2.join()).isEqualTo("B");
            assertThat(f3.join()).isEqualTo("C");
        }
    }

    @Test
    @DisplayName("Deve retornar o resultado do future mais rápido com anyOf")
    void deveRetornarPrimeiroCompletadoComAnyOf() {
        // Given
        try (ExecutorService executor = Executors.newThreadPerTaskExecutor(factory)) {
            CompletableFuture<String> fast = CompletableFuture.supplyAsync(() -> "Rapido", executor);
            CompletableFuture<String> slow = CompletableFuture.supplyAsync(() -> {
                try {
                    Thread.sleep(300);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                return "Lento";
            }, executor);

            // When
            Object primeiro = CompletableFuture.anyOf(fast, slow)
                    .orTimeout(2, TimeUnit.SECONDS)
                    .join();

            // Then
            assertThat(primeiro).isEqualTo("Rapido");
        }
    }
}
