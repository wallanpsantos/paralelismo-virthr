package com.example.paralelismovirthr.examples;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Garante a eficácia das correções dos anti-padrões clássicos de concorrência com VTs.
 * Valida a composição assíncrona com thenCompose sem bloqueios de thread, o processamento de lotes
 * de I/O com allOf em substituição ao parallelStream e a segurança de formato imutável sem ThreadLocal.
 */
@DisplayName("Testes - correções dos anti-padrões de CF")
class CompletableFutureAntiPatternsExampleTest {

    private final ThreadFactory factory = Thread.ofVirtual().name("vt-test-anti-", 1).factory();

    @Test
    @DisplayName("thenCompose encadeia futuros dependentes")
    void deveComporFuturesComThenComposeSemBloquear() {
        try (ExecutorService executor = Executors.newThreadPerTaskExecutor(factory)) {
            String resultado = CompletableFuture.supplyAsync(() -> "Cliente", executor)
                    .thenCompose(cliente -> CompletableFuture.supplyAsync(() -> cliente + " -> Pedido", executor))
                    .orTimeout(2, TimeUnit.SECONDS)
                    .join();
            assertThat(resultado).isEqualTo("Cliente -> Pedido");
        }
    }

    @Test
    @DisplayName("Lote I/O com CF + allOf em vez de parallelStream")
    void deveProcessarLoteComCompletableFutureEAllOf() {
        List<Integer> ids = List.of(1, 2, 3, 4, 5);
        try (ExecutorService executor = Executors.newThreadPerTaskExecutor(factory)) {
            List<CompletableFuture<String>> futures = ids.stream()
                    .map(id -> CompletableFuture.supplyAsync(() -> "Processado: " + id, executor)
                            .orTimeout(2, TimeUnit.SECONDS))
                    .toList();
            CompletableFuture.allOf(futures.toArray(new CompletableFuture[0])).join();
            List<String> results = futures.stream().map(CompletableFuture::join).toList();
            assertThat(results).containsExactly(
                    "Processado: 1", "Processado: 2", "Processado: 3", "Processado: 4", "Processado: 5");
        }
    }

    @Test
    @DisplayName("DateTimeFormatter compartilhado sem ThreadLocal")
    void deveFormatarDataComDateTimeFormatterImutavel() {
        DateTimeFormatter formatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");
        LocalDateTime timestampFixo = LocalDateTime.of(2026, 8, 30, 22, 30);
        try (ExecutorService executor = Executors.newThreadPerTaskExecutor(factory)) {
            List<CompletableFuture<String>> futures = IntStream.range(0, 20)
                    .mapToObj(i -> CompletableFuture.supplyAsync(() -> formatter.format(timestampFixo), executor))
                    .toList();
            CompletableFuture.allOf(futures.toArray(new CompletableFuture[0])).join();
            assertThat(futures.stream().map(CompletableFuture::join).toList())
                    .hasSize(20)
                    .allMatch(data -> data.equals("2026-08-30 22:30"));
        }
    }
}
