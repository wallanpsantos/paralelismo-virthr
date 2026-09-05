package com.example.paralelismovirthr;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * O que é: ponto de entrada (bootstrap) da aplicação Spring Boot no Java 25 LTS.
 * <p>
 * Mecânica: a execução inicia na platform thread principal do sistema operacional;
 * as virtual threads (continuações montadas sobre carriers) são instanciadas sob demanda
 * pelo container web conforme as diretrizes declaradas na configuração do Spring.
 * <p>
 * Quando usar: para disparar a inicialização do contexto e composição dos beans gerenciados.
 * <p>
 * Quando não usar: para criar virtual threads avulsas, processar lógica de domínio ou
 * demarcar fronteiras transacionais; esta classe não atua como Unit of Work.
 * <p>
 * Risco: anotar a classe com {@code @EnableAsync} sem a devida configuração do executor,
 * ou assumir erroneamente que o método principal instancia virtual threads diretamente.
 * <p>
 * Como observar: verificar os logs de bootstrap do Spring Boot e inspecionar o estado
 * das threads em execução por meio do utilitário {@code jcmd <pid> Thread.dump_to_file}.
 * <p>
 * Leitura: Rahman cap. 7 (configuração de virtual threads em frameworks) e Fowler PoEAA (Unit of Work).
 */
@SpringBootApplication
public class ParalelismoVirthrApplication {

    /**
     * Ponto de entrada que inicializa a infraestrutura da aplicação Spring Boot.
     * <p>
     * Ponto: roda na platform thread principal gerenciada diretamente pelo SO.
     * Invariante: o método {@code main} não cria virtual threads; o despacho em virtual
     * threads para atender requisições é habilitado via {@code spring.threads.virtual.enabled=true}.
     *
     * @param args argumentos de linha de comando passados na inicialização
     */
    public static void main(String[] args) {
        // Sobe o contexto na platform thread; o container web assumirá virtual threads via configuração externa.
        SpringApplication.run(ParalelismoVirthrApplication.class, args);
    }
}
