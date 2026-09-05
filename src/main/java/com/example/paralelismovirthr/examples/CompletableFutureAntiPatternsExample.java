package com.example.paralelismovirthr.examples;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;

/**
 * O que é: catálogo didático de anti-padrões e armadilhas ao utilizar {@link CompletableFuture} e Virtual Threads no Java 25 LTS.
 * <p>
 * Mecânica: tarefas assíncronas sem executor explícito recaem no {@code ForkJoinPool.commonPool},
 * um pool global de platform threads dimensionado para o número de cores da CPU. Realizar I/O bloqueante ali
 * rouba workers computacionais de toda a JVM. Cada método desta classe expõe o erro comum e a correção equivalente.
 * <p>
 * Quando usar: como referência de boas práticas e auditoria de código para pipelines assíncronos.
 * <p>
 * Quando não usar: não adote chamadas assíncronas para encadear operações locais triviais;
 * a sobrecarga de alocação de estágios e lambdas degrada a performance sem ganho de throughput.
 * <p>
 * Risco: criar executores inline sem fechamento (leak de recursos), aninhar {@code join()} dentro de callbacks,
 * ou reter instâncias de objetos caros em {@link ThreadLocal} associados a virtual threads efêmeras.
 * <p>
 * Como observar: auditar o uso do commonPool via métricas do ForkJoinPool e inspecionar threads ativas com {@code jcmd}.
 * <p>
 * Leitura: Rahman cap. 1, 3 (commonPool vs carriers) e 5 (ThreadLocal vs ScopedValue).
 */
public class CompletableFutureAntiPatternsExample {

    private static final ThreadFactory factory = Thread.ofVirtual().name("vt-cf-anti-", 1).factory();

    /**
     * Executa a sequência demonstrativa de cada anti-padrão e sua respectiva correção.
     * <p>
     * Ponto: ilustra a execução encadeada das rotinas protegidas por executor de virtual threads.
     * Invariante: o try-with-resources fecha o executor garantindo o término ordenado das demonstrações.
     *
     * @param args argumentos de linha de comando passados na inicialização
     */
    public static void main(String[] args) {
        try (ExecutorService executor = Executors.newThreadPerTaskExecutor(factory)) {
            supplyAsyncWithoutExecutor();
            thenApplyAsyncWithoutExecutor(executor);
            nestedJoinDeadlockRisk(executor);
            parallelStreamForBlockingIo(executor);
            inlineExecutorCreationLeak();
            missingErrorHandler(executor);
            missingTimeout(executor);
            fixedPoolOfVirtualThreads();
            threadLocalExpensiveObjectCache(executor);
        }
    }

    /**
     * Anti-padrão 1: invocar {@code supplyAsync} sem especificar o executor de Virtual Threads.
     * <p>
     * Ponto: demonstra a substituição do perigoso default global {@code commonPool} por executor explícito.
     * Invariante: chamadas assíncronas com I/O bloqueante devem sempre receber executor de VTs.
     */
    private static void supplyAsyncWithoutExecutor() {
        // ❌ Erro: CompletableFuture.supplyAsync(() -> I/O bloqueante)
        // Sem passar executor, a tarefa roda no ForkJoinPool.commonPool global de CPU.
        // Se várias threads bloquearem em I/O ali, o pool esgota e trava tarefas computacionais.
        // Correção: fornecer sempre o ExecutorService de Virtual Threads explicitamente.
        try (ExecutorService executor = Executors.newThreadPerTaskExecutor(factory)) {
            CompletableFuture.supplyAsync(() -> {
                        simulateBlockingIO(100);
                        return "Dados";
                    }, executor)
                    .orTimeout(1, TimeUnit.SECONDS)
                    .exceptionally(ex -> "Erro")
                    .join();
        }
    }

    /**
     * Anti-padrão 2: invocar {@code thenApplyAsync} sem repassar o executor do pipeline.
     * <p>
     * Ponto: ressalta que métodos com sufixo {@code Async} sem executor migram silenciosamente para o commonPool.
     * Invariante: todo estágio {@code *Async} que realiza I/O exige executor explícito em sua chamada.
     */
    private static void thenApplyAsyncWithoutExecutor(ExecutorService executor) {
        // ❌ Erro: thenApplyAsync(dado -> I/O bloqueante)
        // Mesmo que a raiz tenha usado VT, o sufixo Async sem argumento migra para o commonPool.
        // A perda de contexto de execução ocorre silenciosamente no meio da cadeia.
        // Correção: fornecer o mesmo executor em cada salto *Async dependente de I/O.
        CompletableFuture.supplyAsync(() -> "Dado Inicial", executor)
                .thenApplyAsync(dado -> {
                    simulateBlockingIO(100);
                    return dado + " Processado";
                }, executor)
                .orTimeout(1, TimeUnit.SECONDS)
                .exceptionally(ex -> "Erro")
                .join();
    }

    /**
     * Anti-padrão 3: realizar {@code join()} bloqueante de outro Future dentro de um estágio de callback.
     * <p>
     * Ponto: elimina o bloqueio de thread intermediária substituindo o {@code join()} aninhado por {@code thenCompose}.
     * Invariante: não bloqueie threads dentro de pipelines assíncronos; utilize composição de estágios.
     */
    private static void nestedJoinDeadlockRisk(ExecutorService executor) {
        // ❌ Erro: thenApply(req1 -> outroFuture(req1).join())
        // Em pools clássicos de platform threads, gera thread starvation e deadlock imediato.
        // Em Virtual Threads não trava o pool, mas é mau cheiro que quebra a natureza não-bloqueante.
        // Correção: usar thenCompose para encadear futuros dependentes de forma reativa.
        CompletableFuture.supplyAsync(() -> "Req 1", executor)
                .thenCompose(req1 -> CompletableFuture.supplyAsync(() -> "Req 2", executor)
                        .thenApply(req2 -> req1 + " " + req2))
                .orTimeout(1, TimeUnit.SECONDS)
                .exceptionally(ex -> "Erro")
                .join();
    }

    /**
     * Anti-padrão 4: utilizar {@code parallelStream()} para processamento de lote com I/O bloqueante.
     * <p>
     * Ponto: evita o sequestro do {@code commonPool} migrando o lote para lista de Futures sobre Virtual Threads.
     * Invariante: streams paralelas são para tarefas CPU-bound; I/O massivo deve usar VTs com {@code allOf}.
     */
    private static void parallelStreamForBlockingIo(ExecutorService executor) {
        // ❌ Erro: lista.parallelStream().map(this::chamarHttp).toList()
        // parallelStream utiliza o commonPool fixo de platform threads, saturando toda a JVM.
        // Para I/O concorrente em lista, o paralelismo de CPU é o modelo conceitual errado.
        // Correção: mapear cada item para CompletableFuture no executor de VTs e reunir com allOf.
        List<Integer> ids = List.of(1, 2, 3, 4, 5);
        List<CompletableFuture<String>> futures = ids.stream()
                .map(id -> CompletableFuture.supplyAsync(() -> {
                            simulateBlockingIO(100);
                            return "Processado: " + id;
                        }, executor)
                        .orTimeout(1, TimeUnit.SECONDS)
                        .exceptionally(ex -> "Erro " + id))
                .toList();

        CompletableFuture.allOf(futures.toArray(new CompletableFuture[0])).join();
        List<String> results = futures.stream().map(CompletableFuture::join).toList();
        System.out.println("Resultados do lote corrigido: " + results);
    }

    /**
     * Anti-padrão 5: instanciar {@code newThreadPerTaskExecutor()} inline sem gerenciamento de ciclo de vida.
     * <p>
     * Ponto: impede vazamento de executores associando-os a try-with-resources ou beans gerenciados.
     * Invariante: todo executor instanciado deve invocar {@code close()} ou {@code shutdown()} ao término.
     */
    private static void inlineExecutorCreationLeak() {
        // ❌ Erro: CompletableFuture.supplyAsync(tarefa, Executors.newThreadPerTaskExecutor(factory))
        // Instanciar o executor inline a cada requisição cria objetos e threads órfãs sem fechamento.
        // Sem close(), as tarefas não têm garantia de término no shutdown do sistema.
        // Correção: declarar como @Bean(destroyMethod="close") ou envolver em try-with-resources.
        try (ExecutorService localExecutor = Executors.newThreadPerTaskExecutor(factory)) {
            CompletableFuture.supplyAsync(() -> "Fechado corretamente", localExecutor)
                    .orTimeout(1, TimeUnit.SECONDS)
                    .exceptionally(ex -> "Erro")
                    .join();
        }
    }

    /**
     * Anti-padrão 6: submeter pipelines assíncronos sem handler terminal de tratamento de exceções.
     * <p>
     * Ponto: assegura visibilidade de falhas adicionando {@code exceptionally} ou {@code handle} ao final da cadeia.
     * Invariante: etapas assíncronas não logadas ou tratadas engolem erros silenciosamente no pipeline.
     */
    private static void missingErrorHandler(ExecutorService executor) {
        // ❌ Erro: CompletableFuture.supplyAsync(tarefa, executor); // sem exceptionally/handle
        // Se a tarefa lançar exceção, ela fica encapsulada no Future e nunca aparece nos logs.
        // O pipeline falha sem deixar rastros caso ninguém dê join() ou get().
        // Correção: sempre anexar um estágio terminal .exceptionally() ou .handle() para logar/tratar.
        CompletableFuture.supplyAsync(() -> {
                    throw new RuntimeException("Erro tratado");
                }, executor)
                .orTimeout(1, TimeUnit.SECONDS)
                .exceptionally(ex -> {
                    System.err.println("Capturado: " + ex.getMessage());
                    return null;
                });
    }

    /**
     * Anti-padrão 7: compor pipelines assíncronos sem timeout explícito, gerando tarefas zumbis.
     * <p>
     * Ponto: impõe limite temporal com {@code orTimeout} para que chamadores não aguardem indefinidamente.
     * Invariante: mesmo com {@code orTimeout}, o cliente de I/O subjacente deve ter seu próprio timeout de socket.
     */
    private static void missingTimeout(ExecutorService executor) {
        // ❌ Erro: future.join() sem timeout em pipeline aberto
        // Se o serviço remoto congelar a conexão, o Future aguardará eternamente.
        // Milhares de conexões abertas geram vazamento de memória e exaustão de descritores.
        // Correção: aplicar .orTimeout() ou .completeOnTimeout() em todas as cadeias públicas.
        CompletableFuture.supplyAsync(() -> {
                    simulateBlockingIO(100);
                    return "Rápido o suficiente";
                }, executor)
                .orTimeout(2, TimeUnit.SECONDS)
                .exceptionally(ex -> "Timeout ou erro")
                .join();
    }

    /**
     * Anti-padrão 8: encapsular Virtual Threads em pools de tamanho fixo como {@code newFixedThreadPool}.
     * <p>
     * Ponto: elimina o limitador artificial de VTs, adotando o executor per-task conforme arquitetado pelo JDK.
     * Invariante: Virtual Threads são baratas e efêmeras; pools existem exclusivamente para platform threads.
     */
    private static void fixedPoolOfVirtualThreads() {
        // ❌ Erro: Executors.newFixedThreadPool(200, vtThreadFactory)
        // Pool de threads existe para amortizar o custo de criação de Platform Threads do SO (~1 MB).
        // Virtual Threads custam ~1 KB e devem ser criadas por tarefa sem enfileiramento prévio.
        // Correção: utilizar Executors.newThreadPerTaskExecutor(factory); limite recursos com Semaphore.
        try (ExecutorService bomExecutor = Executors.newThreadPerTaskExecutor(factory)) {
            CompletableFuture.runAsync(() -> {
            }, bomExecutor).join();
        }
    }

    /**
     * Anti-padrão 9: utilizar {@link ThreadLocal} como cache de instâncias em Virtual Threads efêmeras.
     * <p>
     * Ponto: substitui o padrão obsoleto de cache em ThreadLocal por instâncias thread-safe imutáveis.
     * Invariante: como VTs nascem e morrem por tarefa, o mapa de ThreadLocal é alocado e descartado continuamente.
     */
    private static void threadLocalExpensiveObjectCache(ExecutorService executor) {
        // ❌ Erro: private static final ThreadLocal<SimpleDateFormat> cache = ThreadLocal.withInitial(...)
        // Em milhões de VTs efêmeras, cada thread cria seu próprio objeto e o joga fora em milissegundos.
        // Isso gera pressão brutal de Garbage Collection e destrói o ganho de memória das VTs.
        // Correção: utilizar classes thread-safe modernas (como DateTimeFormatter) ou ScopedValue (JEP 506).
        DateTimeFormatter formatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
        CompletableFuture.supplyAsync(() -> formatter.format(LocalDateTime.now()), executor)
                .orTimeout(1, TimeUnit.SECONDS)
                .exceptionally(ex -> "Erro de formatação: " + ex.getMessage())
                .thenAccept(data -> System.out.println("Data formatada sem ThreadLocal: " + data))
                .join();
    }

    private static void simulateBlockingIO(int millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
