package com.example.paralelismovirthr.examples;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.ReentrantLock;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("Testes - ReentrantLock com Virtual Threads (Java 25 / JEP 491)")
class VirtualThreadPinningExampleTest {

    @Test
    @DisplayName("Deve serializar incrementos com ReentrantLock.tryLock sem deadlock")
    void deveExecutarConcorrenciaSeguraComReentrantLock() throws InterruptedException {
        int totalThreads = 20;
        ReentrantLock lock = new ReentrantLock();
        AtomicInteger contador = new AtomicInteger(0);
        CountDownLatch latch = new CountDownLatch(totalThreads);
        ThreadFactory factory = Thread.ofVirtual().name("vt-test-lock-", 1).factory();

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

            assertThat(completou).isTrue();
            assertThat(contador.get()).isEqualTo(totalThreads);
        }
    }

    @Test
    @DisplayName("Deve executar o exemplo de ReentrantLock sem exceção")
    void deveExecutarExemploCorretoComReentrantLock() {
        VirtualThreadPinningExample.exemploCorretoComReentrantLock();
        assertThat(true).isTrue();
    }
}
