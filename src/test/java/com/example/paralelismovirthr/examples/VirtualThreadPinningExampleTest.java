package com.example.paralelismovirthr.examples;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.ReentrantLock;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("Testes Automatizados - Diagnóstico e Prevenção de Pinning com ReentrantLock")
class VirtualThreadPinningExampleTest {

    @Test
    @DisplayName("Deve sincronizar acesso crítico concorrente usando ReentrantLock com Virtual Threads sem deadlocks")
    void deveExecutarConcorrenciaSeguraComReentrantLock() throws InterruptedException {
        // Given
        int totalThreads = 20;
        ReentrantLock lock = new ReentrantLock();
        AtomicInteger contador = new AtomicInteger(0);
        CountDownLatch latch = new CountDownLatch(totalThreads);
        ThreadFactory factory = Thread.ofVirtual().name("vt-test-lock-", 1).factory();

        // When
        try (ExecutorService executor = Executors.newThreadPerTaskExecutor(factory)) {
            for (int i = 0; i < totalThreads; i++) {
                executor.submit(() -> {
                    try {
                        if (lock.tryLock(3, TimeUnit.SECONDS)) {
                            try {
                                Thread.sleep(Duration.ofMillis(10));
                                contador.incrementAndGet();
                            } finally {
                                lock.unlock();
                            }
                        }
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    } finally {
                        latch.countDown();
                    }
                });
            }

            boolean completou = latch.await(10, TimeUnit.SECONDS);

            // Then
            assertThat(completou)
                    .as("Todas as threads devem concluir dentro do tempo limite")
                    .isTrue();
            assertThat(contador.get())
                    .as("O contador deve refletir exatamente o número de incrementos sincronizados")
                    .isEqualTo(totalThreads);
        }
    }

    @Test
    @DisplayName("Deve executar método exemploCorretoComReentrantLock sem lançar exceções")
    void deveExecutarExemploCorretoComReentrantLock() {
        // Given & When
        // Executa a lógica corrigida de pinning com ReentrantLock
        VirtualThreadPinningExample.exemploCorretoComReentrantLock();

        // Then
        assertThat(true).isTrue();
    }
}
