package com.example.paralelismovirthr.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;

/**
 * Executor compartilhado de Virtual Threads para pipelines de CompletableFuture.
 * <p>
 * {@code spring.threads.virtual.enabled=true} já coloca o Tomcat e o
 * {@code AsyncTaskExecutor} auto-configurado em VT (Java 21+ / Boot 4).
 * Este bean existe para injeção explícita em {@code supplyAsync(..., executor)}.
 * {@code spring.task.execution.mode=force} evita que este {@link ExecutorService}
 * substitua o executor auto-configurado do Boot.
 * <p>
 * {@code destroyMethod = "close"} faz shutdown + awaitTermination (Java 19+).
 */
@Configuration
public class VirtualThreadConfig {

    @Bean(destroyMethod = "close")
    public ExecutorService virtualThreadExecutor() {
        ThreadFactory factory = Thread.ofVirtual().name("vt-shared-", 0).factory();
        return Executors.newThreadPerTaskExecutor(factory);
    }
}
