# Guia prático: Virtual Threads no Java 25 LTS

Concorrência estável. Nada de `--enable-preview`.

## 1. Conceitos

**Platform thread:** 1:1 com thread do SO. ~1 MB nativo. Cara de criar. Poucas milhares no servidor.

**Virtual thread (JEP 444):** leve, heap (~1 KB no início), M:N sobre poucas *carrier threads*. No bloqueio de I/O a JVM desmonta a VT e libera a carrier.

Use VT em I/O (HTTP, JDBC, arquivo). Não use VT como atalho de CPU — aí vale platform / ForkJoinPool dimensionado.

**CompletableFuture:** orquestra resultados (compor, timeout, fallback). Não é o mecanismo de escala. Em I/O simples, código imperativo em VT lê e debuga melhor.

## 2. Pinning — o que mudou

Até o Java 23, `synchronized` + I/O pinava a VT na carrier. **JEP 491 (Java 24+)** desacoplou o monitor da carrier. No Java 25 `synchronized` **não pina**.

Ainda pina: JNI/nativo, FFM, class loading durante a execução, parte do file I/O no Linux.

`ReentrantLock.tryLock(timeout)` continua o padrão quando a crítica espera I/O:

- monitor não tem espera limitada
- `synchronized` não é interrompível
- milhares de VTs no mesmo monitor serializam o throughput

Diagnóstico: JFR `jdk.VirtualThreadPinned` e `jdk.VirtualThreadSubmitFailed`. `-Djdk.tracePinnedThreads` foi removido no Java 24.

## 3. ScopedValue (JEP 506, final)

`ThreadLocal` como cache de objeto caro não escala: cada VT é efêmera. Para contexto de requisição (tenant, request id) use `ScopedValue`.

Bindings atuais são capturados na criação da thread filha. Uma VT submetida dentro de `where(...).call(...)` lê o mesmo valor. Fora do bloco, `isBound()` é false.

Ver `ScopedValueExample`.

## 4. Fan-out sem preview

`StructuredTaskScope` segue preview no JDK 25 (JEP 505). Substitutos estáveis:

```java
try (var executor = Executors.newThreadPerTaskExecutor(factory)) {
    Future<A> a = executor.submit(this::buscarA);
    Future<B> b = executor.submit(this::buscarB);
    return new Resultado(a.get(3, SECONDS), b.get(3, SECONDS));
}
```

Ou CF com executor de VT + `orTimeout` + `exceptionally`/`handle`.

`close()` do executor espera as tarefas — o escopo termina de forma definida.

## 5. CompletableFuture

- Sem executor = `ForkJoinPool.commonPool` (platform, tamanho de CPU).
- `thenApplyAsync(fn)` sem executor volta ao `commonPool`.
- `orTimeout` / `completeOnTimeout` completam o *future*. A tarefa em curso **não é cancelada**. Timeout de verdade vive no HTTP client / JDBC / `Future.cancel(true)`.
- `anyOf` não cancela o perdedor.
- `parallelStream()` em I/O satura o `commonPool`.

## 6. Backpressure

VT tira o teto de threads da aplicação. Hikari, API externa e broker não acompanham. `Semaphore` com permits ≤ `maximumPoolSize`. `tryAcquire(timeout)` no lugar de `acquire()` sem bound.

## 7. Spring Boot 4.1.1

```yaml
spring:
  threads:
    virtual:
      enabled: true
  task:
    execution:
      mode: force
```

Isso liga VT no Tomcat embutido e no `AsyncTaskExecutor` auto-configurado (Java 21+). `@Async` precisa de `@EnableAsync`. O bean `ExecutorService` do projeto é para CF; `mode: force` impede que ele desligue a auto-configuração.

## 8. Produção

- `jcmd <pid> Thread.dump_to_file` — `jstack` clássico não lista VT.
- JFR contínuo com teto de arquivo. Alertar pinning residual e `VirtualThreadSubmitFailed`.
- Container: `-Xmx` maior (stack de VT na heap), CPU limit ≥ 2 (evitar SerialGC), `ulimit` de FD alto.
- Probe HTTP no actuator. Não use contagem de platform threads como saúde da app.

## 9. Árvore de decisão

```
I/O simples que você controla     → VT imperativa + try-with-resources
Fan-out/fan-in                    → VT executor + Future.get(timeout)
Compor async / fallback / timeout → CF + executor de VT
CPU-bound                         → platform / ForkJoinPool
Contexto de requisição            → ScopedValue
Lock que espera I/O               → ReentrantLock.tryLock(timeout)
Downstream (JDBC/HTTP/broker)     → Semaphore ≤ pool
StructuredTaskScope               → não (preview)
```
