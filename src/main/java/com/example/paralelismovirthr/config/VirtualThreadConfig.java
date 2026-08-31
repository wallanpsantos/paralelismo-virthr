package com.example.paralelismovirthr.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;

/**
 * Configuração de Virtual Threads para a aplicação Spring Boot.
 */
@Configuration
public class VirtualThreadConfig {

    /**
     * Cria um ExecutorService global e compartilhado de Virtual Threads.
     * <p>
     * NOTA IMPORTANTE: No Spring Boot 3.2+ / 4.x com Java 21, basta definir
     * spring.threads.virtual.enabled=true no application.yml para habilitar
     * VTs em toda a aplicação nativamente (Tomcat, @Async, etc).
     * <p>
     * Caso você precise de um ExecutorService customizado para ser injetado,
     * crie este bean.
     * <p>
     * Regras aplicadas:
     * 1. destroyMethod = "close" garante o shutdown correto (equivalente ao try-with-resources).
     * 2. VTs sempre nomeadas para observabilidade.
     * 3. Sem pooling! VTs são feitas para serem criadas on-demand por tarefa.
     * 4. O executor criado aqui deve ser COMPARTILHADO e reutilizado via injeção de dependência,
     * evitando criação manual e inline repetida do ExecutorService na aplicação inteira.
     */
    @Bean(destroyMethod = "close")
    public ExecutorService virtualThreadExecutor() {
        ThreadFactory factory = Thread.ofVirtual().name("vt-shared-", 0).factory();
        return Executors.newThreadPerTaskExecutor(factory);
    }
}
