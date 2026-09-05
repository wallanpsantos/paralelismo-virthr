package com.example.paralelismovirthr.examples;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Garante que instâncias de Virtual Threads comportem-se como daemon e virtuais no Java 25.
 * Valida a execução ordenada de tarefas em executores com ThreadFactory nomeada e o padrão
 * de fan-out com limites de timeout explícitos sem a necessidade de APIs de preview.
 */
@DisplayName("Testes - Fundamentos de Virtual Threads")
class VirtualThreadBasicsExampleTest {

    @Test
    @DisplayName("Virtual Threads são virtuais e daemon")
    void deveVerificarPropriedadesDeVirtualThread() throws InterruptedException {
        List<Boolean> isVirtualFlag = new ArrayList<>();
        List<Boolean> isDaemonFlag = new ArrayList<>();

        Thread vt = Thread.ofVirtual().name("vt-test-prop").start(() -> {
            isVirtualFlag.add(Thread.currentThread().isVirtual());
            isDaemonFlag.add(Thread.currentThread().isDaemon());
        });
        vt.join();

        assertThat(isVirtualFlag).containsExactly(true);
        assertThat(isDaemonFlag).containsExactly(true);
    }

    @Test
    @DisplayName("Executor com ThreadFactory nomeada")
    void deveExecutarTarefasViaExecutorComThreadFactory() {
        ThreadFactory factory = Thread.ofVirtual().name("vt-unit-", 1).factory();
        List<String> threadNames = new ArrayList<>();

        try (ExecutorService executor = Executors.newThreadPerTaskExecutor(factory)) {
            Future<?> f1 = executor.submit(() -> threadNames.add(Thread.currentThread().getName()));
            Future<?> f2 = executor.submit(() -> threadNames.add(Thread.currentThread().getName()));

            assertThat(f1).succeedsWithin(Duration.ofSeconds(2));
            assertThat(f2).succeedsWithin(Duration.ofSeconds(2));
        }

        assertThat(threadNames).hasSize(2).allMatch(name -> name.startsWith("vt-unit-"));
    }

    @Test
    @DisplayName("Fan-out com Future.get(timeout)")
    void deveExecutarFanOutComSucesso() throws ExecutionException, InterruptedException, TimeoutException {
        ThreadFactory factory = Thread.ofVirtual().name("vt-fanout-test-", 1).factory();
        int totalTarefas = 5;
        List<String> resultados = new ArrayList<>();

        try (ExecutorService executor = Executors.newThreadPerTaskExecutor(factory)) {
            List<Future<String>> futures = new ArrayList<>();
            for (int i = 1; i <= totalTarefas; i++) {
                final int id = i;
                futures.add(executor.submit(() -> {
                    Thread.sleep(Duration.ofMillis(50));
                    return "Item " + id;
                }));
            }
            for (Future<String> future : futures) {
                resultados.add(future.get(2, TimeUnit.SECONDS));
            }
        }

        assertThat(resultados).containsExactly("Item 1", "Item 2", "Item 3", "Item 4", "Item 5");
    }
}
