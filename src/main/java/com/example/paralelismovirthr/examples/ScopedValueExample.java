package com.example.paralelismovirthr.examples;

import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * ScopedValue é final no Java 25 (JEP 506). Substitui ThreadLocal para contexto
 * de requisição imutável e de vida delimitada.
 * <p>
 * Bindings atuais são capturados na criação da thread filha. Por isso uma VT
 * submetida dentro de {@code where(...).call(...)} lê o mesmo tenant/requestId.
 */
public class ScopedValueExample {

    public static final ScopedValue<String> TENANT_ID = ScopedValue.newInstance();
    public static final ScopedValue<String> REQUEST_ID = ScopedValue.newInstance();

    public static void main(String[] args) {
        System.out.println("--- ScopedValue herda para VT filha ---");
        System.out.println(processarComContexto("tenant-123", "req-9"));

        System.out.println("\n--- Fora do escopo o valor não está bound ---");
        System.out.println("TENANT bound? " + TENANT_ID.isBound());
    }

    public static String processarComContexto(String tenant, String requestId) {
        try {
            return ScopedValue.where(TENANT_ID, tenant)
                    .where(REQUEST_ID, requestId)
                    .call(ScopedValueExample::executarNaFilha);
        } catch (Exception e) {
            throw new IllegalStateException("Falha ao processar contexto", e);
        }
    }

    public static String lerContextoAtual() {
        if (!TENANT_ID.isBound() || !REQUEST_ID.isBound()) {
            throw new IllegalStateException("Contexto de requisição ausente");
        }
        return TENANT_ID.get() + ":" + REQUEST_ID.get();
    }

    private static String executarNaFilha() throws ExecutionException, InterruptedException, TimeoutException {
        ThreadFactory factory = Thread.ofVirtual().name("vt-scoped-", 0).factory();
        try (ExecutorService executor = Executors.newThreadPerTaskExecutor(factory)) {
            Future<String> futura = executor.submit(() ->
                    lerContextoAtual() + ":virtual=" + Thread.currentThread().isVirtual());
            return futura.get(2, TimeUnit.SECONDS);
        }
    }
}
