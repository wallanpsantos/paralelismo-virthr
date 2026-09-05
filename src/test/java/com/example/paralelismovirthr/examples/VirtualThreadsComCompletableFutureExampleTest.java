package com.example.paralelismovirthr.examples;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Garante o funcionamento integrado dos ramos da estratégia entre Virtual Threads e CompletableFuture.
 * Valida a recuperação direta de I/O em VT, o acionamento determinístico de fallback sob timeout,
 * a combinação de fontes de dados via thenCombine e o processamento de fan-out com recuperação parcial.
 */
@DisplayName("Testes - VT + CompletableFuture")
class VirtualThreadsComCompletableFutureExampleTest {

    private final VirtualThreadsComCompletableFutureExample example = new VirtualThreadsComCompletableFutureExample();

    @Test
    @DisplayName("Busca simples em VT com timeout no Future.get")
    void deveBuscarUsuarioSimples() {
        assertThat(example.buscarUsuarioSimples()).isEqualTo("Usuario Simples");
    }

    @Test
    @DisplayName("Fallback quando orTimeout dispara")
    void deveAcionarFallbackEmTimeout() {
        assertThat(example.buscarUsuarioComFallback()).isEqualTo("Usuario Fallback");
    }

    @Test
    @DisplayName("Combina duas fontes com thenCombine")
    void deveBuscarDadosCompostos() {
        assertThat(example.buscarDadosCompostos()).isEqualTo("User123 -> Modo Escuro");
    }

    @Test
    void deveExecutarExemploCombinado() {
        example.exemploCombinadoVtComCf();
    }

    @Test
    void deveExecutarFanOutComFallbackIndividual() {
        example.exemploFanOutComCf();
    }
}
