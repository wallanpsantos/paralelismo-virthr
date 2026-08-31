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
import java.util.concurrent.Semaphore;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("Testes Automatizados - Proteção de Recursos Downstream com Semaphore")
class VirtualThreadResourceProtectionExampleTest {

    @Test
    @DisplayName("Deve garantir que o Semáforo limita estritamente o número máximo de execuções concorrentes")
    void deveLimitarConcorrenciaComSemaforo() throws InterruptedException {
        // Given
        int limiteMaximo = 5;
        int totalTarefas = 50;
        Semaphore semaphore = new Semaphore(limiteMaximo);
        AtomicInteger ativos = new AtomicInteger(0);
        AtomicInteger picoConcorrencia = new AtomicInteger(0);
        CountDownLatch latch = new CountDownLatch(totalTarefas);
        ThreadFactory factory = Thread.ofVirtual().name("vt-test-sem-", 1).factory();

        // When
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

            boolean finalizou = latch.await(10, TimeUnit.SECONDS);

            // Then
            assertThat(finalizou)
                    .as("Todas as tarefas devem finalizar dentro do prazo")
                    .isTrue();
            assertThat(picoConcorrencia.get())
                    .as("O pico de concorrência não pode ultrapassar o limite do Semáforo")
                    .isLessThanOrEqualTo(limiteMaximo);
            assertThat(semaphore.availablePermits())
                    .as("Todos os permits do Semáforo devem ser devolvidos após o término")
                    .isEqualTo(limiteMaximo);
        }
    }

    @Test
    @DisplayName("Deve executar método exemploComSemaforo sem vazamento de conexões ativas")
    void deveExecutarExemploComSemaforoSemVazamento() {
        // Given & When
        VirtualThreadResourceProtectionExample.exemploComSemaforo();

        // Then
        assertThat(VirtualThreadResourceProtectionExample.getConexoesAtivas())
                .as("Ao final da execução todas as conexões simuladas devem ter sido encerradas")
                .isZero();
    }
}
