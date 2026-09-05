package com.example.paralelismovirthr.examples;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

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
