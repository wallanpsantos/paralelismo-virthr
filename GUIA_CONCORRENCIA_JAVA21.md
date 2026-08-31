# Guia Prático: Virtual Threads e CompletableFuture no Java 21

Este guia foi elaborado para quem deseja entender como funciona a concorrência moderna no **Java 21 LTS**, partindo do
zero até as melhores práticas de produção com Spring Boot.

---

## 1. Conceitos Fundamentais (Do Zero)

### O que é uma Thread?

Pense em um programa como um restaurante:

- Uma **Thread** é um **garçom**.
- Se você tem apenas 1 garçom (*Single Thread*), ele precisa atender uma mesa, ir até a cozinha esperar o prato ficar
  pronto (I/O bloqueante), levar o prato e só então atender a próxima mesa.
- Se você tiver múltiplos garçons (*Multi-threading*), várias mesas podem ser atendidas ao mesmo tempo.

---

### O Problema das Threads Tradicionais (Platform Threads)

Antes do Java 21, toda thread criada no Java (`Thread` tradicional) era mapeada 1:1 para uma thread do Sistema
Operacional (SO).

* **Pesadas:** Cada thread reservava cerca de 1 MB de memória RAM logo de início.
* **Caras:** Criar ou alternar entre threads exigia intervenção direta do SO (*kernel context switch*).
* **Limite físico:** Um servidor típico não conseguia manter mais do que alguns milhares de threads antes de esgotar a
  memória ou degradar a performance.

Se 5.000 clientes fizessem uma requisição simultânea que demora 1 segundo aguardando o banco de dados, o servidor
precisaria de 5.000 threads de SO, a maioria apenas **ociosa esperando resposta de rede**.

---

### O que são Virtual Threads (Java 21 LTS - JEP 444)?

As **Virtual Threads (VTs)** são threads superleves gerenciadas diretamente pela **JVM**, e não pelo Sistema
Operacional.

```
┌────────────────────────────────────────────────────────┐
│  Milhares ou Milhões de Virtual Threads (na Heap)      │
│  [VT 1]  [VT 2]  [VT 3]  [VT 4]  ...  [VT 100.000]     │
└──────────────┬────────────────────────────┬────────────┘
               │ (monta/desmonta sob demanda)│
┌──────────────▼────────────────────────────▼────────────┐
│  Poucas Carrier Threads (Platform Threads do SO)       │
│  [Carrier 1]   [Carrier 2]   [Carrier 3]   [Carrier 4] │
└────────────────────────────────────────────────────────┘
```

1. **Memória ínfima:** Iniciam com apenas ~1 KB na Heap da JVM (e crescem só se necessário).
2. **Desmontagem automática (*unmount*):** Quando uma Virtual Thread faz uma chamada bloqueante (ex: consultar banco,
   chamar API HTTP externa, `Thread.sleep`), a JVM pausa essa thread, salva seu estado na memória Heap e **libera a
   Carrier Thread** para atender outra tarefa.
3. **Escala massiva:** Você pode criar 100.000 ou 1.000.000 de threads simultâneas sem derrubar a máquina.
4. **Código sequencial simples:** Você não precisa de programação reativa complexa para ter alto rendimento
   (*throughput*) em I/O.

---

### O que é CompletableFuture?

O `CompletableFuture` é uma **API de orquestração assíncrona** (introduzida no Java 8 e aprimorada até o Java 21).

Pense no `CompletableFuture` como uma **senha de pedido** em uma lanchonete:

- Você faz o pedido e recebe um comprovante (a "promessa" de um resultado futuro).
- Você pode definir o que acontecerá quando o pedido estiver pronto:
    - *"Quando o lanche estiver pronto (`thenApply`), adicione molho."*
    - *"Combine o lanche com o refrigerante (`thenCombine`)."*
    - *"Se o lanche demorar mais de 5 minutos, me dê um desconto (`orTimeout` / `completeOnTimeout`)."*
    - *"Se o pedido queimar na chapa, me sirva uma salada (`exceptionally`)."*

---

## 2. Tabela Comparativa: Qual a diferença?

| Característica              | Virtual Threads                                         | CompletableFuture                                          |
|:----------------------------|:--------------------------------------------------------|:-----------------------------------------------------------|
| **O que é?**                | Mecanismo de execução (Thread leve)                     | API de orquestração de resultados assíncronos              |
| **Estilo de Código**        | Imperativo e sequencial (`try/catch`, chamadas normais) | Funcional / Declarativo (Cadeia de métodos `then...`)      |
| **Melhor Para**             | Tarefas bloqueantes de I/O em grande escala             | Combinar várias fontes assíncronas, fallbacks e timeouts   |
| **Tratamento de Erros**     | `try / catch` tradicional                               | `.exceptionally()`, `.handle()`                            |
| **Depuração / Stack Trace** | Muito fácil (stack trace linear e claro)                | Mais complexo (pilha de chamadas fragmentada em callbacks) |

---

## 3. Exemplos Práticos no Projeto

A estrutura de classes de exemplo deste projeto está organizada sob `src/main/java/com/example/paralelismovirthr/`:

```
src/main/java/com/example/paralelismovirthr/
├── config/
│   └── VirtualThreadConfig.java
└── examples/
    ├── VirtualThreadBasicsExample.java
    ├── VirtualThreadPinningExample.java
    ├── VirtualThreadResourceProtectionExample.java
    ├── CompletableFutureBasicsExample.java
    ├── CompletableFutureAntiPatternsExample.java
    └── VirtualThreadsComCompletableFutureExample.java
```

### 1. `VirtualThreadBasicsExample.java`

- **Comparação direta:** Demonstra criação de Platform Threads vs. Virtual Threads e suas propriedades (`isDaemon()`,
  `isVirtual()`).
- **3 Formas de Criação:**
    1. `Thread.ofVirtual().name("nome").start(runnable)`
    2. `ThreadFactory` via `Thread.ofVirtual().factory()`
    3. `Executors.newVirtualThreadPerTaskExecutor()` com bloco `try-with-resources`.
- **Escala com 10.000 Threads:** Executa 10.000 requisições simultâneas em ~1 segundo.
- **Padrão Fan-Out:** Dispara múltiplas requisições paralelas e agrega as respostas com `Future.get(timeout)`.

### 2. `VirtualThreadPinningExample.java`

- **O que é Pinning?** No Java 21, se uma Virtual Thread executa uma operação bloqueante de I/O dentro de um bloco
  `synchronized`, ela "gruda" (*pins*) na Carrier Thread do SO, impedindo a desmontagem.
- **Solução no Java 21:** Substituir `synchronized` por `ReentrantLock` com `tryLock(timeout)` e bloco `try/finally`.
- **Como comprovar na prática:** veja a seção 4.1 para detectar o pinning em execução (JFR e
  `-Djdk.tracePinnedThreads`).

### 3. `VirtualThreadResourceProtectionExample.java`

- **Atenção:** As Virtual Threads removem o limite de threads da aplicação, mas o banco de dados (HikariCP) ainda tem um
  limite (ex: 50 conexões).
- **Solução:** Uso do `Semaphore` alinhado ao tamanho do pool para evitar esgotamento de conexões (*backpressure*).

### 4. `CompletableFutureBasicsExample.java`

- **Execução Assíncrona:** `CompletableFuture.supplyAsync(..., executor)` sempre com executor explícito.
- **Transformação e Encadeamento:** `thenApply` (mapear dado) e `thenAccept` (consumir).
- **Tratamento de Erros:** `.exceptionally(...)` para fallback e `.handle(...)` para tratamento unificado.
- **Timeouts Seguros:** `.orTimeout(tempo, unidade)` e `.completeOnTimeout(valorPadrao, tempo, unidade)`.
- **Combinações:** `thenCombine` (duas tarefas), `allOf` (todas as tarefas) e `anyOf` (a mais rápida vence).
- **Atenção ao ler `exemploCombinacoes`:** `c1` e `c2` já são recuperados individualmente com `.exceptionally(...)`
  antes de chegarem em `allOf`/`anyOf`. Por isso, os blocos `.exceptionally()` aplicados sobre `allOf(c1, c2)` e
  `anyOf(c1, c2)` não são alcançados nesse exemplo — para ver esse tratamento disparar de fato, seria necessário usar
  futures sem recuperação prévia.

### 5. `CompletableFutureAntiPatternsExample.java`

- ❌ **`supplyAsync()` sem executor:** Utiliza `commonPool` e bloqueia threads de CPU.
- ❌ **`thenApplyAsync()` sem executor:** Mesmo problema de poluição da pool padrão.
- ❌ **`.join()` aninhado dentro de callbacks:** Risco grave de deadlock.
- ❌ **`parallelStream()` para I/O:** Monopoliza a `ForkJoinPool.commonPool`.
- ❌ **Fazer pool de Virtual Threads:** `Executors.newFixedThreadPool(10, Thread.ofVirtual().factory())` elimina o
  propósito das VTs.
- ❌ **Futures sem timeout ou sem tratamento de erro terminal.**

### 6. `VirtualThreadsComCompletableFutureExample.java`

- Mostra como utilizar o `CompletableFuture` abastecido por um `ExecutorService` de **Virtual Threads**.
- Permite orquestrar pipelines de dados com timeouts e fallbacks enquanto cada estágio roda em threads leves.
- ⚠️ **Atenção aos Callbacks Assíncronos:**
    - `thenApply(fn)` roda na thread que completou o estágio anterior (geralmente a Virtual Thread, mas pode variar se o
      estágio já estiver concluído).
    - `thenApplyAsync(fn)` **sem executor** redireciona a execução de volta para a `ForkJoinPool.commonPool` (Platform
      Threads de CPU).
    - `thenApplyAsync(fn, vtExecutor)` **com executor explícito** garante que cada estágio subsequente permaneça em uma
      Virtual Thread.

### 7. Structured Concurrency (JEP 453 - Preview no JDK 21)

O `CompletableFuture.allOf()` permite Fan-Out/Fan-In, mas opera em concorrência não estruturada (*unstructured
concurrency*): se uma tarefa falhar, as outras continuam rodando em background até o fim (risco de vazamento de threads
e desperdício de I/O). A **Structured Concurrency** (`StructuredTaskScope`) trata múltiplas tarefas simultâneas como uma
única unidade de trabalho:

```java
// Exemplo com StructuredTaskScope.ShutdownOnFailure (JEP 453)
public record DetalhesPedido(Cliente cliente, List<Item> itens, Frete frete) {
}

public DetalhesPedido buscarDetalhesPedido(String pedidoId) {
    try (var scope = new StructuredTaskScope.ShutdownOnFailure()) {
        // Dispara sub-tarefas concorrentes em Virtual Threads
        Subtask<Cliente> subtaskCliente = scope.fork(() -> servicoCliente.buscar(pedidoId));
        Subtask<List<Item>> subtaskItens = scope.fork(() -> servicoEstoque.buscar(pedidoId));
        Subtask<Frete> subtaskFrete = scope.fork(() -> servicoFrete.buscar(pedidoId));

        // Aguarda todas as tarefas concluírem ou cancela as demais na PRIMEIRA falha
        scope.joinUntil(Instant.now().plusSeconds(3));
        scope.throwIfFailed(); // Propaga exceção se alguma falhou

        // Extração segura dos resultados
        return new DetalhesPedido(
                subtaskCliente.get(),
                subtaskItens.get(),
                subtaskFrete.get()
        );
    } // Ao sair do bloco try-with-resources, todas as threads filhas são obrigatoriamente finalizadas
}
```

### 8. Configuração no Spring Boot

- **`VirtualThreadConfig.java`**: Bean global de `ExecutorService` gerenciado pelo Spring com
  `@Bean(destroyMethod = "close")`.
- **`application.yml`**:
  ```yaml
  spring:
    threads:
      virtual:
        enabled: true
    application:
      name: paralelismo-virthr
  ```
  *(Habilita o Tomcat e o `@Async` a utilizarem Virtual Threads nativamente).*
- **Nota:** mantenha apenas uma das extensões (`application.yml` **ou** `application.yaml`) no projeto. Ter as duas ao
  mesmo tempo é fonte comum de dúvida sobre qual arquivo o Spring Boot está de fato carregando/mesclando.

---

## 4. Diagnóstico e Observabilidade (funcionalidades estáveis do JDK 21)

Os exemplos deste projeto nomeiam as Virtual Threads para observabilidade (`vt-cf-`, `vt-fanout-http-`, `vt-protegida-`
etc.), mas não mostram como usar essa nomenclatura na prática. As três ferramentas abaixo são oficiais e estáveis desde
o JDK 21 — nenhuma exige `--enable-preview`.

### 4.1 Detectando Pinning em Execução

Além de trocar `synchronized` por `ReentrantLock` (`VirtualThreadPinningExample`), dá para confirmar quando e onde o
pinning ocorre:

- **Evento de JFR `jdk.VirtualThreadPinned`:** habilitado por padrão, é registrado sempre que uma Virtual Thread fica
  pinada por mais de 20 ms.
- **Propriedade de sistema `-Djdk.tracePinnedThreads`:** imprime o stack trace no momento do bloqueio.
    - `-Djdk.tracePinnedThreads=full` → stack trace completo.
    - `-Djdk.tracePinnedThreads=short` → apenas os frames que causam o pinning.

Exemplo rodando o `VirtualThreadPinningExample` com a flag ativada:

```powershell
.\mvnw.cmd test-compile exec:java -Dexec.mainClass="com.example.paralelismovirthr.examples.VirtualThreadPinningExample" -Djdk.tracePinnedThreads=short
```

### 4.2 `jcmd Thread.dump_to_file` — Thread Dump com Virtual Threads

O `jstack` tradicional não lista Virtual Threads. Para visualizá-las (agrupadas com as platform threads que as
carregam), use o `jcmd`:

```
jcmd <pid> Thread.dump_to_file -format=json <file>
jcmd <pid> Thread.dump_to_file -format=text <file>
```

Essa é a forma oficial de conferir, em produção, se os nomes definidos nos exemplos estão sendo aplicados como esperado.

### 4.3 `ThreadLocal` vs. `ScopedValue` (JEP 446 - Preview no JDK 21)

`ThreadLocal` continua funcionando normalmente com Virtual Threads, mas apresenta dois problemas críticos em larga
escala:

1. **Cache de Objetos Caros:** O padrão de cachear um objeto caro e mutável (ex.: `ThreadLocal<SimpleDateFormat>`) deixa
   de fazer sentido. Como cada Virtual Thread normalmente vive por uma única tarefa efêmera e é descartada, o objeto
   seria recriado a cada nova thread. A orientação oficial é usar classes imutáveis e thread-safe (ex.:
   `DateTimeFormatter`).
2. **Overhead de Memória e Vazamentos:** Com centenas de milhares de threads, herdar mapas de `ThreadLocal` consome
   muita memória Heap e pode causar retenção indevida de dados.

**A Solução Moderna: `ScopedValue`**
Introduzido na JEP 446, o `ScopedValue` é imutável, possui ciclo de vida delimitado lexicalmente e é compartilhado com
threads filhas de forma segura e sem cópias pesadas de memória:

```java
public class SecurityContext {
    public static final ScopedValue<String> TENANT_ID = ScopedValue.newInstance();
}

// Execução com escopo delimitado:
ScopedValue.where(SecurityContext.TENANT_ID, "tenant-123")
           .run(() -> {
               // Todas as subtasks disparadas dentro do escopo herdam o valor
               service.processar();
           });
```

### 4.4 Métricas Contínuas em Produção (Prometheus / Micrometer)

Além de diagnósticos sob demanda (`jcmd` e JFR), sistemas em produção devem monitorar métricas contínuas via Spring Boot
Actuator e Micrometer:

| Métrica / Indicador                             | O que sinaliza                                   | Ação Recomendada                                                    |
|:------------------------------------------------|:-------------------------------------------------|:--------------------------------------------------------------------|
| **`jdk.VirtualThreadPinned` (JFR/Micrometer)**  | Taxa de threads ficando pinadas no SO            | Identificar e substituir blocos `synchronized` por `ReentrantLock`  |
| **`hikaricp.connections.pending`**              | Threads aguardando conexão no pool JDBC          | Ajustar `Semaphore` ou redimensionar pool do banco                  |
| **Razão `Virtual Threads / Carrier Threads`**   | Grau de paralelismo real vs. concorrência de I/O | Se a razão for < 10:1 constantemente, o workload pode ser CPU-bound |
| **`jvm.threads.live` vs `jvm.threads.virtual`** | Diferenciação de threads de SO vs. virtuais      | Garantir que o número de Platform Threads permaneça baixo e estável |

---

## 5. Árvore de Decisão Rápida

```
                            Qual é o seu objetivo?
                                      │
         ┌────────────────────────────┼────────────────────────────┐
         ▼                            ▼                            ▼
  Operação de I/O Simples     Fan-Out / Fan-In Paralelo      Pipelines Assíncronos
  (HTTP, JDBC, Arquivos)     (Todas ou nenhuma sub-tarefa)   (Cadeias then..., Fallbacks)
         │                            │                            │
         ▼                            ▼                            ▼
  VIRTUAL THREADS direta      STRUCTUREDTASKSCOPE           COMPLETABLEFUTURE
  (Código imperativo com      (JEP 453: cancelamento        (Alimentado por Executor
  try-with-resources)         automático em falhas)         de Virtual Threads)
```

---

## 6. Como Executar os Exemplos

Você pode executar o método `main` das classes de exemplo diretamente pela sua IDE ou via linha de comando no terminal:

```powershell
# Exemplo básico de Virtual Threads:
.\mvnw.cmd test-compile exec:java -Dexec.mainClass="com.example.paralelismovirthr.examples.VirtualThreadBasicsExample"

# Exemplo sobre Pinning e Locks:
.\mvnw.cmd test-compile exec:java -Dexec.mainClass="com.example.paralelismovirthr.examples.VirtualThreadPinningExample"

# Exemplo de Proteção com Semáforo:
.\mvnw.cmd test-compile exec:java -Dexec.mainClass="com.example.paralelismovirthr.examples.VirtualThreadResourceProtectionExample"

# Exemplo básico de CompletableFuture:
.\mvnw.cmd test-compile exec:java -Dexec.mainClass="com.example.paralelismovirthr.examples.CompletableFutureBasicsExample"

# Exemplo de Anti-Padrões:
.\mvnw.cmd test-compile exec:java -Dexec.mainClass="com.example.paralelismovirthr.examples.CompletableFutureAntiPatternsExample"
```
