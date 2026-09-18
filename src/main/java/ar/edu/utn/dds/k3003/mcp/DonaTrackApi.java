package ar.edu.utn.dds.k3003.mcp;

import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpEntity;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpStatusCodeException;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestTemplate;

/**
 * Único punto por el que el servidor MCP habla con DonaTrack.
 *
 * <p>Acá no vive ninguna regla de negocio: las validaciones —que la cantidad sea positiva, que el
 * donador pueda donar, que el producto exista— son de los módulos y tienen que seguir siéndolo.
 * Si el MCP las repitiera, habría dos lugares donde mantenerlas y tarde o temprano dirían cosas
 * distintas. Lo único que hace esta clase es llamar y traducir los errores a algo que un modelo
 * de lenguaje pueda explicarle al usuario.
 */
@Component
public class DonaTrackApi {

  private static final Logger log = LoggerFactory.getLogger(DonaTrackApi.class);

  /**
   * El header que los módulos leen para agrupar los logs de una misma operación.
   *
   * <p>Donaciones y Donadores lo reenvían a quien llamen después, así que alcanza con mandarlo una
   * vez desde acá para que la operación entera quede atada al mismo identificador en Datadog.
   */
  private static final String HEADER_TRAZA = "X-Trace-Id";

  /** Vale por llamada, no por servidor: cada flujo arranca la suya. */
  private final ThreadLocal<String> traza = new ThreadLocal<>();

  private final RestTemplate rest;
  private final String donaciones;
  private final String donadores;
  private final String logistica;
  private final String incentivos;

  public DonaTrackApi(
      RestTemplate rest,
      @Value("${donatrack.donaciones-url}") String donaciones,
      @Value("${donatrack.donadores-url}") String donadores,
      @Value("${donatrack.logistica-url}") String logistica,
      @Value("${donatrack.incentivos-url}") String incentivos) {
    this.rest = rest;
    this.donaciones = donaciones;
    this.donadores = donadores;
    this.logistica = logistica;
    this.incentivos = incentivos;
  }

  // ── Direcciones de cada módulo ─────────────────────────────────────────────

  public String getDonaciones(String path) {
    return pedir(HttpMethod.GET, donaciones + path, null);
  }

  public String postDonaciones(String path, Object body) {
    return pedir(HttpMethod.POST, donaciones + path, body);
  }

  public String deleteDonaciones(String path) {
    return pedir(HttpMethod.DELETE, donaciones + path, null);
  }

  public String patchDonadores(String path, Object body) {
    return pedir(HttpMethod.PATCH, donadores + path, body);
  }

  public String getDonadores(String path) {
    return pedir(HttpMethod.GET, donadores + path, null);
  }

  public String postDonadores(String path, Object body) {
    return pedir(HttpMethod.POST, donadores + path, body);
  }

  public String putDonadores(String path, Object body) {
    return pedir(HttpMethod.PUT, donadores + path, body);
  }

  public String deleteDonadores(String path) {
    return pedir(HttpMethod.DELETE, donadores + path, null);
  }

  public String getLogistica(String path) {
    return pedir(HttpMethod.GET, logistica + path, null);
  }

  public String postLogistica(String path, Object body) {
    return pedir(HttpMethod.POST, logistica + path, body);
  }

  public String deleteLogistica(String path) {
    return pedir(HttpMethod.DELETE, logistica + path, null);
  }

  public String getIncentivos(String path) {
    return pedir(HttpMethod.GET, incentivos + path, null);
  }

  public String postIncentivos(String path, Object body) {
    return pedir(HttpMethod.POST, incentivos + path, body);
  }

  // ── Trazabilidad ───────────────────────────────────────────────────────────

  /**
   * Empieza una traza nueva y devuelve su identificador, para poder mostrarlo.
   *
   * <p>Se usa antes de un flujo completo: todas las llamadas que salgan después viajan con el
   * mismo valor y los módulos lo escriben en cada línea de log.
   */
  public String nuevaTraza() {
    String id = "mcp-" + java.util.UUID.randomUUID().toString().substring(0, 8);
    traza.set(id);
    return id;
  }

  public void cerrarTraza() {
    traza.remove();
  }

  /**
   * Solo agrega la traza. El tipo de contenido lo sigue resolviendo el conversor según el cuerpo:
   * si acá se fijara a mano, las quejas —que viajan como texto plano— se romperían.
   */
  private org.springframework.http.HttpHeaders cabeceras() {
    org.springframework.http.HttpHeaders headers = new org.springframework.http.HttpHeaders();
    String actual = traza.get();
    if (actual != null) {
      headers.set(HEADER_TRAZA, actual);
    }
    return headers;
  }

  // ── Llamada ────────────────────────────────────────────────────────────────

  private String pedir(HttpMethod metodo, String url, Object body) {
    log.info("{} {}", metodo, url);
    try {
      String resp =
          rest.exchange(url, metodo, new HttpEntity<>(body, cabeceras()), String.class).getBody();
      return resp == null || resp.isBlank() ? "Operación realizada." : resp;
    } catch (HttpStatusCodeException e) {
      String explicacion = explicar(e, url);
      if (e.getStatusCode().value() == 404) {
        throw new NoEncontrado(explicacion);
      }
      throw new RuntimeException(explicacion);
    } catch (ResourceAccessException e) {
      // Una consulta se puede repetir sin consecuencias, y en Render el primer pedido a un
      // servicio dormido se pierde despertándolo. Una operación que modifica datos NO se
      // reintenta: sin respuesta no se sabe si llegó, y repetirla puede duplicarla.
      if (metodo == HttpMethod.GET) {
        log.info("Reintentando {} {} (el módulo estaba dormido)", metodo, url);
        try {
          String resp =
              rest.exchange(url, metodo, new HttpEntity<>(body, cabeceras()), String.class)
                  .getBody();
          return resp == null || resp.isBlank() ? "Operación realizada." : resp;
        } catch (RuntimeException segunda) {
          throw new SinRespuesta(sinRespuesta(url));
        }
      }
      throw new SinRespuesta(sinRespuesta(url));
    }
  }

  private String sinRespuesta(String url) {
    return "El módulo no responde ("
        + url
        + "). Los servicios de Render se duermen: puede tardar hasta un minuto en despertar. "
        + "Conviene usar 'despertar_servicios' antes de seguir.";
  }

  /**
   * Traduce el error a algo que el modelo pueda transmitirle al usuario.
   *
   * <p>Se conserva el mensaje del módulo porque ahí está la regla de negocio que se violó; solo
   * se le agrega contexto sobre qué significa.
   */
  private String explicar(HttpStatusCodeException e, String url) {
    int codigo = e.getStatusCode().value();
    String cuerpo = e.getResponseBodyAsString();

    if (codigo == 404) {
      return "No existe eso que buscás. Detalle del módulo: " + resumir(cuerpo);
    }
    if (codigo == 400) {
      return "El módulo rechazó la operación por una regla de negocio: " + resumir(cuerpo);
    }
    if (codigo == 409) {
      return "Hay un conflicto con el estado actual: " + resumir(cuerpo);
    }
    if (codigo >= 500) {
      return "El módulo tuvo un error interno (" + codigo + "). " + resumir(cuerpo);
    }
    return "La operación falló con código " + codigo + ". " + resumir(cuerpo);
  }

  private String resumir(String cuerpo) {
    if (cuerpo == null || cuerpo.isBlank()) {
      return "sin detalle";
    }
    String limpio = cuerpo.replaceAll("[{}\"]", " ").replaceAll("\\s+", " ").trim();
    return limpio.length() > 300 ? limpio.substring(0, 300) + "..." : limpio;
  }

  /**
   * Para distinguir «eso no existe» de «el módulo no anda» sin tener que mirar el texto del error.
   *
   * <p>La diferencia importa al armar los relatos: que un producto no tenga stock guardado es
   * información válida, y que Logística no conteste es otra cosa muy distinta.
   */
  public static class NoEncontrado extends RuntimeException {
    public NoEncontrado(String mensaje) {
      super(mensaje);
    }
  }

  /**
   * El módulo no contestó a tiempo.
   *
   * <p>En Render esto casi siempre es un servicio despertando, y el segundo intento funciona. Se
   * distingue del resto de los errores para poder reintentar solo cuando tiene sentido.
   */
  public static class SinRespuesta extends RuntimeException {
    public SinRespuesta(String mensaje) {
      super(mensaje);
    }
  }

  /** Para que las tools puedan armar cuerpos sin repetir el mapa en cada una. */
  public static Map<String, Object> cuerpo(Object... paresClaveValor) {
    Map<String, Object> m = new java.util.LinkedHashMap<>();
    for (int i = 0; i + 1 < paresClaveValor.length; i += 2) {
      Object valor = paresClaveValor[i + 1];
      if (valor != null) {
        m.put(String.valueOf(paresClaveValor[i]), valor);
      }
    }
    return m;
  }
}
