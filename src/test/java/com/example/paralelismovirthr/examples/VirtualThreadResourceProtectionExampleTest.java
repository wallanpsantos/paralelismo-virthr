package com.example.paralelismovirthr.examples;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Semaphore;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("Testes - Proteção de recursos com Semaphore")
class VirtualThreadResourceProtectionExampleTest {

    @Test
    @DisplayName("Semáforo limita o pico de concorrência")
    void deveLimitarConcorrenciaComSemaforo() throws InterruptedException {
        int limiteMaximo = 5;
        int totalTarefas = 50;
        Semaphore semaphore = new Semaphore(limiteMaximo);
        AtomicInteger ativos = new AtomicInteger(0);
        AtomicInteger picoConcorrencia = new AtomicInteger(0);
        CountDownLatch latch = new CountDownLatch(totalTarefas);
        ThreadFactory factory = Thread.ofVirtual().name("vt-test-sem-", 1).factory();

        try (ExecutorService executor = Executors.newThreadPerTaskExecutor(factory)) {
            for (int i = 0; i < totalTarefas; i++) {
                executor.submit(() -> {
                    try {
                        if (semaphore.tryAcquire(5, TimeUnit.SECONDS)) {
                            try {
                                int atual = ativos.incrementAndGet();
                                picoConcorrencia.updateAndGet(max -> Math.max(max, atual));
                                Thread.sleep(Duration.ofMillis(20));
                            } finally {
                                ativos.decrementAndGet();
                                semaphore.release();
                            }
                        }
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    } finally {
                        latch.countDown();
                    }
                });
            }

            assertThat(latch.await(10, TimeUnit.SECONDS)).isTrue();
            assertThat(picoConcorrencia.get()).isLessThanOrEqualTo(limiteMaximo);
            assertThat(semaphore.availablePermits()).isEqualTo(limiteMaximo);
        }
    }

    @Test
    @DisplayName("exemploComSemaforo não deixa conexão ativa ao final")
    void deveExecutarExemploComSemaforoSemVazamento() {
        VirtualThreadResourceProtectionExample.exemploComSemaforo();
        assertThat(VirtualThreadResourceProtectionExample.getConexoesAtivas()).isZero();
    }
}
