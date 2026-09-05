# Concorrência Moderna e Paralelismo no Java 25 LTS

[![Java](https://img.shields.io/badge/Java-25%20LTS-orange.svg)](https://openjdk.org/projects/jdk/25/)
[![Spring Boot](https://img.shields.io/badge/Spring%20Boot-4.1.1-brightgreen.svg)](https://spring.io/projects/spring-boot)
[![Virtual Threads](https://img.shields.io/badge/Virtual%20Threads-JEP%20444-blue.svg)](https://openjdk.org/jeps/444)
[![License: MIT](https://img.shields.io/badge/License-MIT-yellow.svg)](LICENSE)

Referência prática para **Java 25 LTS** e **Spring Boot 4.1.1**. Apenas APIs estáveis: sem `--enable-preview`, sem incubating.

Trilha: **Virtual Threads** (JEP 444) + **ScopedValue** (JEP 506, final) + **CompletableFuture** com executor de VT. `StructuredTaskScope` permanece preview no JDK 25 (JEP 505) e **não entra neste repositório**.

Guia: **[GUIA_CONCORRENCIA_JAVA25.md](GUIA_CONCORRENCIA_JAVA25.md)**

---

## O que muda do Java 21 para o 25

| Tema | Java 21 | Java 25 |
| :--- | :--- | :--- |
| Virtual Threads | GA (JEP 444) | GA, inalteradas na essência |
| `synchronized` + I/O | Pinava a carrier | **Não pina** (JEP 491, desde o 24) |
| `-Djdk.tracePinnedThreads` | Ferramenta principal | **Removida** no 24. Use JFR |
| `ScopedValue` | Preview (JEP 446) | **Final** (JEP 506) |
| `StructuredTaskScope` | Preview (JEP 453) | **Ainda preview** (JEP 505) — fora da trilha |
| Pinning restante | `synchronized`, JNI, nativo | JNI, FFM, class loading, parte do file I/O Linux |

`ReentrantLock.tryLock(timeout)` continua o padrão quando a seção crítica espera I/O: monitor não tem timeout e não é interrompível. Não é mais por pinning.

---

## Visão geral

| Paradigma | Platform Threads | Virtual Threads | CompletableFuture |
| :--- | :--- | :--- | :--- |
| Gerenciamento | Kernel 1:1 | JVM M:N na heap | Callbacks + executor |
| Memória | ~1 MB nativo | ~1 KB na heap, cresce sob demanda | Objeto Future |
| Melhor caso | CPU-bound | I/O massivo | Composição, fallback, timeout |
| Erro | `try/catch` | `try/catch` | `.exceptionally()` / `.handle()` |

Fan-out estável: `newThreadPerTaskExecutor` em try-with-resources + `Future.get(timeout)`.

---

## Estrutura

```
src/main/java/com/example/paralelismovirthr/
├── ParalelismoVirthrApplication.java
├── config/VirtualThreadConfig.java
└── examples/
    ├── VirtualThreadBasicsExample.java
    ├── VirtualThreadPinningExample.java
    ├── VirtualThreadResourceProtectionExample.java
    ├── ScopedValueExample.java
    ├── CompletableFutureBasicsExample.java
    ├── CompletableFutureAntiPatternsExample.java
    └── VirtualThreadsComCompletableFutureExample.java
```

---

## Como executar

Requer **JDK 25**. O wrapper Maven do repositório original (`mvnw`) pode ser copiado de volta se você aplicar estes arquivos sobre o clone.

```bash
./mvnw test

./mvnw test-compile exec:java -Dexec.mainClass="com.example.paralelismovirthr.examples.VirtualThreadBasicsExample"
./mvnw test-compile exec:java -Dexec.mainClass="com.example.paralelismovirthr.examples.VirtualThreadPinningExample"
./mvnw test-compile exec:java -Dexec.mainClass="com.example.paralelismovirthr.examples.VirtualThreadResourceProtectionExample"
./mvnw test-compile exec:java -Dexec.mainClass="com.example.paralelismovirthr.examples.ScopedValueExample"
./mvnw test-compile exec:java -Dexec.mainClass="com.example.paralelismovirthr.examples.CompletableFutureBasicsExample"
./mvnw test-compile exec:java -Dexec.mainClass="com.example.paralelismovirthr.examples.CompletableFutureAntiPatternsExample"
./mvnw test-compile exec:java -Dexec.mainClass="com.example.paralelismovirthr.examples.VirtualThreadsComCompletableFutureExample"
```

---

## Spring Boot

```yaml
spring:
  threads:
    virtual:
      enabled: true
  task:
    execution:
      mode: force
```

A propriedade habilita VT no Tomcat embutido e no `AsyncTaskExecutor` auto-configurado. `@Async` ainda exige `@EnableAsync`. `mode: force` evita que o bean `ExecutorService` deste projeto desligue essa auto-configuração.

---

## Observabilidade

```bash
jcmd <PID> Thread.dump_to_file -format=json thread_dump.json

# JFR em produção
java -XX:StartFlightRecording=filename=vt.jfr,settings=profile \
     -jar app.jar
```

Alertar `jdk.VirtualThreadPinned` (pinning residual, tipicamente JNI/FFM) e `jdk.VirtualThreadSubmitFailed` (qualquer ocorrência é crítica).

Em container: subir `-Xmx` (stack de VT mora na heap), CPU limit ≥ 2, `ulimit` de file descriptor alto.
