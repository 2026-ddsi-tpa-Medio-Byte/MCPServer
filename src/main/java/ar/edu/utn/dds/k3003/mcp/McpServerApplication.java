package ar.edu.utn.dds.k3003.mcp;

import java.util.List;
import org.springframework.ai.tool.ToolCallbackProvider;
import org.springframework.ai.tool.method.MethodToolCallbackProvider;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.web.client.RestTemplateBuilder;
import org.springframework.context.annotation.Bean;
import org.springframework.web.client.RestTemplate;

/**
 * Servidor MCP de DonaTrack.
 *
 * <p>Corre local y se comunica con Claude Desktop por entrada y salida estándar. Expone el
 * sistema como herramientas para que se lo pueda operar conversando, sin conocer los endpoints.
 *
 * <p>No tiene base de datos ni lógica de negocio propia: cada herramienta llama al módulo que
 * corresponde y devuelve lo que ese módulo responda.
 */
@SpringBootApplication
public class McpServerApplication {

  public static void main(String[] args) {
    SpringApplication.run(McpServerApplication.class, args);
  }

  /** Registra los métodos anotados con @Tool como herramientas del servidor. */
  @Bean
  public ToolCallbackProvider herramientas(ConsultaTools consultas, OperacionTools operaciones) {
    return MethodToolCallbackProvider.builder()
        .toolObjects(consultas, operaciones)
        .build();
  }

  @Bean
  public RestTemplate restTemplate(RestTemplateBuilder builder) {
    return builder
        .connectTimeout(java.time.Duration.ofSeconds(30))
        .readTimeout(java.time.Duration.ofSeconds(90))
        .build();
  }
}
