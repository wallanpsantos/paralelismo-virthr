package com.example.paralelismovirthr.examples;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Garante a imutabilidade e a transmissão correta de contexto via ScopedValue no Java 25.
 * Valida a herança de bindings no start de Virtual Threads filhas criadas dentro do escopo lexical
 * e assegura que fora do bloco delimitado os valores permaneçam estritamente unbound.
 */
@DisplayName("Testes - ScopedValue (JEP 506, final no Java 25)")
class ScopedValueExampleTest {

    @Test
    @DisplayName("Deve herdar tenant e requestId na Virtual Thread filha")
    void deveHerdarContextoNaVirtualThreadFilha() {
        String resultado = ScopedValueExample.processarComContexto("tenant-123", "req-9");

        assertThat(resultado).isEqualTo("tenant-123:req-9:virtual=true");
    }

    @Test
    @DisplayName("Fora do where() o ScopedValue não está bound")
    void foraDoEscopoNaoEstaBound() {
        assertThat(ScopedValueExample.TENANT_ID.isBound()).isFalse();
        assertThatThrownBy(ScopedValueExample::lerContextoAtual)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("ausente");
    }
}
