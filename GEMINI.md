# Diretrizes: Concorrência no Java 25 LTS

Regras obrigatórias do projeto **paralelismo-virthr** (Java 25 LTS, Spring Boot 4.1.1). Sem preview, sem incubating.

## Virtual Threads

1. Nunca faça pool de VT. Use `Executors.newVirtualThreadPerTaskExecutor()` / `newThreadPerTaskExecutor(factory)` ou `Thread.ofVirtual()`.
2. Nomeie as threads (`Thread.ofVirtual().name(prefixo, inicio)`).
3. JEP 491 (Java 24+): `synchronized` não pina VT. Prefira `ReentrantLock.tryLock(timeout)` quando a seção crítica espera I/O — monitor não tem timeout e não é interrompível.
4. Pinning restante: JNI, FFM, class loading, parte do file I/O Linux. Detectar com JFR `jdk.VirtualThreadPinned` e `jdk.VirtualThreadSubmitFailed`. Não documente `-Djdk.tracePinnedThreads` como ferramenta atual.
5. Semáforo no acesso a Hikari/HTTP/broker, alinhado ao pool máximo. Não limite o número de VTs.
6. Contexto de requisição: `ScopedValue` (JEP 506). Não use `ThreadLocal` como cache em caminho de VT.

## CompletableFuture

1. `supplyAsync` / `*Async` com I/O sempre recebem o executor de VT. Sem executor = `commonPool`.
2. Toda cadeia visível tem timeout (`orTimeout` / `completeOnTimeout`) e handler terminal (`exceptionally` / `handle`).
3. `orTimeout` não cancela a tarefa em andamento. Bound real = timeout no cliente + cancelamento explícito.
4. `thenCompose` para futuros dependentes. Não faça `join()` dentro de estágio.
5. Executor compartilhado como `@Bean(destroyMethod = "close")`. Não crie executor inline e vaze.

## O que não entra no código

- `StructuredTaskScope` e qualquer API que exija `--enable-preview` (JEP 505 no JDK 25).
- Fan-out estável: `newThreadPerTaskExecutor` + `Future.get(timeout)` ou CF com timeout e handler.

## Spring

- `spring.threads.virtual.enabled=true`
- `spring.task.execution.mode=force` enquanto existir bean `ExecutorService` próprio
- `@Async` só com `@EnableAsync`, método público, sem self-invoke
- `@Transactional` só na camada de aplicação
