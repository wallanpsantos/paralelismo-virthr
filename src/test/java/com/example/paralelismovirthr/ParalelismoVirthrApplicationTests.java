package com.example.paralelismovirthr;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

/**
 * Garante o carregamento integral do ApplicationContext do Spring Boot no Java 25 LTS.
 * Valida se as anotações, os beans de configuração de concorrência e as propriedades
 * declarativas sobem sem falhas ou conflitos durante o bootstrap da aplicação.
 */
@SpringBootTest
class ParalelismoVirthrApplicationTests {

    @Test
    void contextLoads() {
    }
}
