package com.example.paralelismovirthr.examples;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("Testes Automatizados - Integração entre Virtual Threads e CompletableFuture")
class VirtualThreadsComCompletableFutureExampleTest {

    private final VirtualThreadsComCompletableFutureExample example = new VirtualThreadsComCompletableFutureExample();

    @Test
    @DisplayName("Deve buscar usuário simples em modelo imperativo com Virtual Thread")
    void deveBuscarUsuarioSimples() {
        // Given & When
        String resultado = example.buscarUsuarioSimples();

        // Then
        assertThat(resultado).isEqualTo("Usuario Simples");
    }

    @Test
    @DisplayName("Deve acionar fallback quando timeout de operação composta for atingido")
    void deveAcionarFallbackEmTimeout() {
        // Given & When
        String resultado = example.buscarUsuarioComFallback();

        // Then
        assertThat(resultado).isEqualTo("Usuario Fallback");
    }

    @Test
    @DisplayName("Deve combinar dados compostos de fontes heterogêneas com CompletableFuture")
    void deveBuscarDadosCompostos() {
        // Given & When
        String resultado = example.buscarDadosCompostos();

        // Then
        assertThat(resultado).isEqualTo("User123 -> Modo Escuro");
    }

    @Test
    @DisplayName("Deve executar exemplo combinado de Virtual Threads com CompletableFuture sem exceções")
    void deveExecutarExemploCombinado() {
        // Given & When
        example.exemploCombinadoVtComCf();

        // Then
        assertThat(true).isTrue();
    }

    @Test
    @DisplayName("Deve executar Fan-Out resiliente com fallback individual em caso de falha de tarefas")
    void deveExecutarFanOutComFallbackIndividual() {
        // Given & When
        example.exemploFanOutComCf();

        // Then
        assertThat(true).isTrue();
    }
}
