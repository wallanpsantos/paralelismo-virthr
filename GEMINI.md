# Diretrizes de Projeto: Concorrência Moderna e Virtual Threads (Java 21 LTS)

Este arquivo define as regras, padrões arquiteturais e restrições de código obrigatórias para o projeto **paralelismo-virthr** (Java 21 LTS e Spring Boot 4.x). O assistente e os desenvolvedores devem seguir estas diretrizes rigorosamente em todas as manutenções, refatorações e novas implementações.

---

## 1. Regras Fundamentais de Virtual Threads (Project Loom - JEP 444)

1. **Nunca crie pools de Virtual Threads:**
   - Virtual Threads são efêmeras e extremamente leves (~1 KB de heap).
   - Use sempre `Executors.newVirtualThreadPerTaskExecutor()` ou a API fluente `Thread.ofVirtual().start(...)` / `Thread.ofVirtual().unstarted(...)`.
   - **Proibido:** Usar `Executors.newFixedThreadPool(...)` ou criar subclasses de `ThreadPoolExecutor` para gerenciar Virtual Threads. Limitar o número de threads anula o propósito das VTs.

2. **Prevenção Estrita de Pinning:**
   - **Proibido:** Executar operações de I/O bloqueante (chamadas HTTP, consultas JDBC, operações de arquivos ou `Thread.sleep`) dentro de blocos ou métodos `synchronized`. Isso causa o *pinning* da Virtual Thread na *Carrier Thread* do Sistema Operacional, degradando o throughput de toda a JVM.
   - **Solução Padrão:** Substitua `synchronized` por `java.util.concurrent.locks.ReentrantLock`, sempre com o padrão obrigatório `try / finally`:
     ```java
     private final ReentrantLock lock = new ReentrantLock();

     public void operacaoComProtecao() {
         lock.lock();
         try {
             // Operação crítica ou com I/O
         } finally {
             lock.unlock();
         }
     }
     ```
   - Diagnósticos de pinning devem utilizar a flag de JVM `-Djdk.tracePinnedThreads=short`.

3. **Proteção de Recursos Downstream (Backpressure com Semaphore):**
   - Virtual Threads escalam facilmente para centenas de milhares, porém pools de conexões (HikariCP), bancos de dados e APIs externas têm capacidade finita.
   - **Regra:** Nunca submeta tarefas massivas em Virtual Threads a recursos externos sem limitação de concorrência.
   - Use `java.util.concurrent.Semaphore` para restringir o paralelismo de acesso ao pool de conexões ou chamadas de downstream.

4. **Substituição de ThreadLocal por ScopedValue (JEP 446):**
   - Evite armazenar objetos mutáveis ou de longa duração em `ThreadLocal` quando criar milhões de Virtual Threads, pois isso consome memória heap desnecessária.
   - Prefira dados imutáveis ou utilize `ScopedValue` para passar contexto de forma segura e eficiente pelo ciclo de vida da requisição.

---

## 2. Padrões Obrigatórios para CompletableFuture

1. **Executor Explícito:**
   - **Proibido:** Chamar métodos assíncronos como `CompletableFuture.supplyAsync(supplier)` ou `thenApplyAsync(fn)` sem fornecer o `Executor` configurado para Virtual Threads. Sem isso, a JVM utiliza por padrão a `ForkJoinPool.commonPool`, que é baseada em Platform Threads limitadas pelo número de CPUs.
   - **Padrão Obrigatório:** Sempre injete ou utilize o bean/executor de Virtual Threads:
     ```java
     CompletableFuture.supplyAsync(() -> servico.buscarDados(), vtExecutor);
     ```

2. **Timeouts Obrigatórios:**
   - Toda composição ou pipeline de `CompletableFuture` deve ser protegida contra requisições zumbis usando `.orTimeout(timeout, unit)` ou `.completeOnTimeout(defaultValue, timeout, unit)`.

3. **Tratamento de Exceções e Fallbacks:**
   - Use `.exceptionally(ex -> ...)` ou `.handle((res, ex) -> ...)` em pipelines assíncronos para garantir resiliência e fail-safe.
   - Evite chamadas bloqueantes manuais como `.get()` ou `.join()` sem timeout explícito ou dentro de laços de iteração (`for` / `while`).

---

## 3. Structured Concurrency (JEP 453)

1. **Escopo Léxico Estruturado:**
   - Para operações do tipo Fan-Out/Fan-In (disparar múltiplas tarefas e aguardar os resultados), priorize `StructuredTaskScope` com bloco `try-with-resources`.
   - Utilize `StructuredTaskScope.ShutdownOnFailure` quando a falha de qualquer subtarefa deva cancelar as demais em cascata (*fail-fast*).
   - Utilize `StructuredTaskScope.ShutdownOnSuccess` para cenários especulativos onde o primeiro resultado válido satisfaz a requisição.
   - Sempre invoque `scope.join()` e trate `scope.throwIfFailed()` antes de coletar os retornos através de `Subtask.get()`.

---

## 4. Estrutura do Código, Testes e Ferramentas

1. **Execução de Build e Testes:**
   - No Windows, utilize `.\mvnw.cmd test` ou `.\mvnw.cmd test-compile`.
   - Todos os novos métodos ou refatorações em concorrência devem ser acompanhados por testes unitários assertivos com JUnit 5 sob `src/test/java/com/example/paralelismovirthr/`.
   - Testes assíncronos devem utilizar timeouts razoáveis para evitar que a suíte trave indefinidamente.

2. **Preservação Didática:**
   - Este repositório tem foco didático e de referência para desenvolvedores. Mantenha os comentários explicativos, referências a JEPs e explicações técnicas de decisões arquiteturais nos arquivos Java e documentos Markdown (`README.md`, `GUIA_CONCORRENCIA_JAVA21.md`).
