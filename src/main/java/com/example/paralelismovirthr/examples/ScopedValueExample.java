package com.example.paralelismovirthr.examples;

import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * O que é: demonstração do compartilhamento seguro e imutável de contexto com {@link ScopedValue} no Java 25 LTS.
 * <p>
 * Mecânica: ScopedValue é recurso final da linguagem (JEP 506); vincula dados imutáveis a um escopo
 * lexical delimitado por {@code where(...).call(...)}. Ao instanciar uma virtual thread filha dentro do escopo,
 * os bindings ativos são capturados automaticamente no start da thread sem cópia custosa de memória.
 * <p>
 * Quando usar: para propagar metadados de requisição (tenant ID, request ID, credenciais de auditoria)
 * através de chamadas síncronas e despachos concorrentes de VTs.
 * <p>
 * Quando não usar: não use {@link ThreadLocal} como cache em Virtual Threads efêmeras (provoca vazamento
 * e alocação inútil de mapas); não confunda o escopo lexical do ScopedValue com StructuredTaskScope (JEP 505 preview).
 * <p>
 * Risco: chamar {@code get()} fora de um bloco delimitado sem checar previamente {@code isBound()},
 * resultando em {@link java.util.NoSuchElementException}.
 * <p>
 * Como observar: inspecionar o retorno booleano de {@link ScopedValue#isBound()} dentro e fora do escopo.
 * <p>
 * Leitura: Rahman cap. 5 (contexto imutável e substituição de ThreadLocal por ScopedValue na JEP 506 final).
 */
public class ScopedValueExample {

    public static final ScopedValue<String> TENANT_ID = ScopedValue.newInstance();
    public static final ScopedValue<String> REQUEST_ID = ScopedValue.newInstance();

    /**
     * Executa a validação de vinculação e herança de contexto com ScopedValue.
     * <p>
     * Ponto: demonstra a transmissão do contexto para a thread filha e a ausência de vínculo fora do escopo.
     * Invariante: fora do bloco {@code where(...).call(...)}, {@code isBound()} avalia obrigatoriamente para falso.
     *
     * @param args argumentos de linha de comando passados na inicialização
     */
    public static void main(String[] args) {
        System.out.println("--- ScopedValue herda para VT filha ---");
        System.out.println(processarComContexto("tenant-123", "req-9"));

        System.out.println("\n--- Fora do escopo o valor não está bound ---");
        System.out.println("TENANT bound? " + TENANT_ID.isBound());
    }

    /**
     * Estabelece o escopo lexical vinculando tenant e requestId para a execução da tarefa.
     * <p>
     * Ponto: encadeia múltiplos bindings imutáveis através de {@code where(...)} e executa callable com {@code call(...)}.
     * Invariante: os valores vinculados são imutáveis e válidos exclusivamente durante o ciclo de vida da chamada.
     *
     * @param tenant identificador do inquilino (tenant)
     * @param requestId identificador correlacional da requisição
     * @return resultado processado contendo o contexto capturado pela thread filha
     */
    public static String processarComContexto(String tenant, String requestId) {
        try {
            // Delimita o escopo lexical imutável: qualquer thread iniciada no callable herda os bindings.
            return ScopedValue.where(TENANT_ID, tenant)
                    .where(REQUEST_ID, requestId)
                    .call(ScopedValueExample::executarNaFilha);
        } catch (Exception e) {
            throw new IllegalStateException("Falha ao processar contexto", e);
        }
    }

    /**
     * Recupera os valores atualmente vinculados ao escopo da thread invocadora.
     * <p>
     * Ponto: verifica a presença do vínculo com {@code isBound()} antes de acessar os dados com {@code get()}.
     * Invariante: lança {@link IllegalStateException} caso invocado fora de um escopo ativo.
     *
     * @return string formatada contendo tenantId e requestId concatenados
     */
    public static String lerContextoAtual() {
        // isBound() garante leitura segura sem disparar NoSuchElementException fora de escopo.
        if (!TENANT_ID.isBound() || !REQUEST_ID.isBound()) {
            throw new IllegalStateException("Contexto de requisição ausente");
        }
        return TENANT_ID.get() + ":" + REQUEST_ID.get();
    }

    // Cria virtual thread dentro do escopo: os bindings do ScopedValue são capturados no start().
    private static String executarNaFilha() throws ExecutionException, InterruptedException, TimeoutException {
        ThreadFactory factory = Thread.ofVirtual().name("vt-scoped-", 0).factory();
        try (ExecutorService executor = Executors.newThreadPerTaskExecutor(factory)) {
            // A filha executa em virtual thread separada e lê o mesmo contexto imutável da thread pai.
            Future<String> futura = executor.submit(() ->
                    lerContextoAtual() + ":virtual=" + Thread.currentThread().isVirtual());
            return futura.get(2, TimeUnit.SECONDS);
        }
    }
}
