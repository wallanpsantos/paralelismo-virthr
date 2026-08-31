# ⚡ Concorrência Moderna e Paralelismo no Java 21 LTS

[![Java](https://img.shields.io/badge/Java-21%20LTS-orange.svg)](https://openjdk.org/projects/jdk/21/)
[![Spring Boot](https://img.shields.io/badge/Spring%20Boot-4.x-brightgreen.svg)](https://spring.io/projects/spring-boot)
[![Project Loom](https://img.shields.io/badge/Project%20Loom-JEP%20444-blue.svg)](https://openjdk.org/jeps/444)
[![License: MIT](https://img.shields.io/badge/License-MIT-yellow.svg)](LICENSE)
[![Tests](https://img.shields.io/badge/Tests-23%20Passing-success.svg)](#-executando-os-testes)

Projeto de referência prática e arquitetural para o ecossistema **Java 21 LTS** e **Spring Boot 4.x**, demonstrando como projetar sistemas de alto rendimento (*throughput*) e baixa latência combinando **Virtual Threads (Project Loom - JEP 444)**, **CompletableFuture**, **Structured Concurrency (JEP 453)** e **Scoped Values (JEP 446)**.

---

## 📖 Guia Didático Completo

Para uma explicação aprofundada dos conceitos, analogias, mecanismos internos de *mount/unmount*, diagnóstico de *pinning* e métricas de produção, consulte o documento:

👉 **[GUIA_CONCORRENCIA_JAVA21.md](GUIA_CONCORRENCIA_JAVA21.md)**

---

## 🎯 Visão Geral: O que muda com Java 21?

| Paradigma | Platform Threads (OS) | Virtual Threads (Loom) | CompletableFuture | Structured Concurrency |
| :--- | :--- | :--- | :--- | :--- |
| **Gerenciamento** | Kernel do SO (1:1) | JVM na Heap (M:N) | Callbacks / Executor | Escopo Léxico (`try-with-resources`) |
| **Custo de Memória** | ~1 MB por thread | ~1 KB por thread | Objeto Future leve | Threads filhas efêmeras na heap |
| **Estilo de Código** | Imperativo bloqueante | Imperativo sequencial | Funcional / Reativo | Imperativo Estruturado |
| **Melhor Caso de Uso** | Tarefas CPU-bound | I/O massivo (HTTP, JDBC) | Pipelines & Fallbacks | Fan-Out/Fan-In com Fail-Fast |
| **Tratamento de Erro** | `try / catch` | `try / catch` | `.exceptionally()`, `.handle()` | `scope.throwIfFailed()` |

---

## 📂 Estrutura do Repositório

```
paralelismo-virthr/
├── GUIA_CONCORRENCIA_JAVA21.md       # Guia definitivo de concorrência e padrões
├── pom.xml                           # Configuração Maven (Java 21 / Spring Boot)
└── src/
    ├── main/java/com/example/paralelismovirthr/
    │   ├── ParalelismoVirthrApplication.java
    │   ├── config/
    │   │   └── VirtualThreadConfig.java                     # Bean global de ExecutorService com VTs
    │   └── examples/
    │       ├── VirtualThreadBasicsExample.java              # Criação, Daemon, Escala (10k VTs) e Fan-Out
    │       ├── VirtualThreadPinningExample.java             # Pinning com synchronized vs ReentrantLock
    │       ├── VirtualThreadResourceProtectionExample.java  # Backpressure em banco/APIs com Semaphore
    │       ├── CompletableFutureBasicsExample.java          # Pipelines, timeouts, fallbacks e combinações
    │       ├── CompletableFutureAntiPatternsExample.java    # 8 anti-patterns comuns e como corrigi-los
    │       └── VirtualThreadsComCompletableFutureExample.java # Combinação ideal: CompletableFuture + VTs
    └── test/java/com/example/paralelismovirthr/
        ├── ParalelismoVirthrApplicationTests.java           # Teste de contexto Spring Boot
        └── examples/                                        # 22 testes unitários cobrindo todos os cenários
```

---

## 🔬 Destaques Técnicos

### 1. Prevenção e Diagnóstico de Pinning
No Java 21, blocos `synchronized` contendo operações bloqueantes de I/O provocam o *pinning* da Virtual Thread na *Carrier Thread* do SO.
* **Solução:** Substituição segura por `ReentrantLock` com `try/finally`.
* **Detecção em tempo de execução:** `-Djdk.tracePinnedThreads=short` e eventos JFR `jdk.VirtualThreadPinned`.

### 2. Backpressure e Proteção de Recursos Downstream
Virtual Threads escalam até milhões, mas bancos de dados relacionais e pools de conexão (ex.: HikariCP) não.
* **Solução:** Limitação com `Semaphore` alinhado ao pool máximo para evitar *pool exhaustion*.

### 3. Integração Segura: CompletableFuture + Virtual Threads
* Uso de `CompletableFuture.supplyAsync(task, vtExecutor)` e `thenApplyAsync(fn, vtExecutor)` para garantir que toda a cadeia assíncrona opere em threads leves sem contaminar a `ForkJoinPool.commonPool`.

### 4. Structured Concurrency e Scoped Values
* Demonstração conceitual e prática de `StructuredTaskScope.ShutdownOnFailure` para cancelamento em cascata (*short-circuiting*) e `ScopedValue` para substituição leve e imutável de `ThreadLocal`.

---

## 🛠️ Como Executar

### 🧪 Executando os Testes Automatizados

```powershell
# Windows (PowerShell / CMD)
.\mvnw.cmd test

# Linux / macOS
./mvnw test
```

### 🚀 Executando os Exemplos Práticos

```powershell
# 1. Fundamentos e Escala de Virtual Threads (10.000 tarefas concorrentes)
.\mvnw.cmd test-compile exec:java -Dexec.mainClass="com.example.paralelismovirthr.examples.VirtualThreadBasicsExample"

# 2. Diagnóstico de Pinning e ReentrantLock
.\mvnw.cmd test-compile exec:java -Dexec.mainClass="com.example.paralelismovirthr.examples.VirtualThreadPinningExample" -Djdk.tracePinnedThreads=short

# 3. Proteção de Conexões Downstream com Semaphore
.\mvnw.cmd test-compile exec:java -Dexec.mainClass="com.example.paralelismovirthr.examples.VirtualThreadResourceProtectionExample"

# 4. Pipelines e Combinações de CompletableFuture
.\mvnw.cmd test-compile exec:java -Dexec.mainClass="com.example.paralelismovirthr.examples.CompletableFutureBasicsExample"

# 5. Anti-padrões e Correções
.\mvnw.cmd test-compile exec:java -Dexec.mainClass="com.example.paralelismovirthr.examples.CompletableFutureAntiPatternsExample"

# 6. Orquestração CompletableFuture + Virtual Threads
.\mvnw.cmd test-compile exec:java -Dexec.mainClass="com.example.paralelismovirthr.examples.VirtualThreadsComCompletableFutureExample"
```

---

## ⚙️ Configuração no Spring Boot

Para habilitar o uso automático de Virtual Threads no servidor embutido (Tomcat) e anotações assíncronas (`@Async`), basta configurar no `src/main/resources/application.yml`:

```yaml
spring:
  threads:
    virtual:
      enabled: true
  application:
    name: paralelismo-virthr
```

---

## 📊 Observabilidade em Produção

```bash
# Gerar thread dump oficial com Virtual Threads (jcmd)
jcmd <PID> Thread.dump_to_file -format=json thread_dump.json
jcmd <PID> Thread.dump_to_file -format=text thread_dump.txt
```

---

## 📄 Licença

Este projeto é distribuído sob os termos da licença [MIT](LICENSE).
