package ar.edu.utn.dds.k3003.mcp;

import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
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

  private static final com.fasterxml.jackson.databind.ObjectMapper MAPPER =
      new com.fasterxml.jackson.databind.ObjectMapper();

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

  /** Vacía si no se usa el gateway: en ese caso cada módulo se llama por su URL directa. */
  private final String gateway;

  // Desde acá, la base de cada módulo ya resuelta: o bien el gateway más el prefijo del módulo,
  // o bien su URL directa. Las herramientas arman los paths como siempre y no se enteran.
  private final String donaciones;
  private final String donadores;
  private final String logistica;
  private final String incentivos;

  /** El Servicio de Consultas solo existe detrás del gateway; vacía si no se usa. */
  private final String consultas;

  /**
   * @param gateway URL del API Gateway, o vacía para llamar a cada módulo directamente. Sirve
   *     mientras el gateway no exista, y para probar un módulo aislado.
   * @param prefijoDonaciones lo que el gateway le agrega a cada ruta de Donaciones y quita antes de
   *     reenviar: {@code {gw}/donaciones/productos/1} llega a Donaciones como {@code /productos/1}.
   *     Lo mismo vale para los otros prefijos.
   */
  @Autowired
  public DonaTrackApi(
      RestTemplate rest,
      @Value("${donatrack.donaciones-url}") String donaciones,
      @Value("${donatrack.donadores-url}") String donadores,
      @Value("${donatrack.logistica-url}") String logistica,
      @Value("${donatrack.incentivos-url}") String incentivos,
      @Value("${donatrack.gateway-url:}") String gateway,
      @Value("${donatrack.prefijo-donaciones:/donaciones}") String prefijoDonaciones,
      @Value("${donatrack.prefijo-donadores:/donadores}") String prefijoDonadores,
      @Value("${donatrack.prefijo-logistica:/logistica}") String prefijoLogistica,
      @Value("${donatrack.prefijo-incentivos:/incentivos}") String prefijoIncentivos,
      @Value("${donatrack.prefijo-consultas:/consultas}") String prefijoConsultas) {
    this.rest = rest;
    this.gateway = sinBarraFinal(gateway);
    boolean porGateway = !this.gateway.isEmpty();
    this.donaciones = base(porGateway, this.gateway, prefijoDonaciones, donaciones);
    this.donadores = base(porGateway, this.gateway, prefijoDonadores, donadores);
    this.logistica = base(porGateway, this.gateway, prefijoLogistica, logistica);
    this.incentivos = base(porGateway, this.gateway, prefijoIncentivos, incentivos);
    this.consultas = porGateway ? this.gateway + prefijo(prefijoConsultas) : "";
  }

  /** Sin gateway: cada módulo por su URL directa, como antes de la Entrega 6. */
  public DonaTrackApi(
      RestTemplate rest,
      String donaciones,
      String donadores,
      String logistica,
      String incentivos) {
    this(
        rest,
        donaciones,
        donadores,
        logistica,
        incentivos,
        "",
        "/donaciones",
        "/donadores",
        "/logistica",
        "/incentivos",
        "/consultas");
  }

  private static String base(boolean porGateway, String gateway, String prefijo, String directa) {
    return porGateway ? gateway + prefijo(prefijo) : sinBarraFinal(directa);
  }

  /**
   * Deja el prefijo con una barra al principio y ninguna al final, escrito como se escriba:
   * {@code logistica}, {@code /logistica} y {@code /logistica/} son el mismo. Vacío queda vacío,
   * por si el gateway enruta un módulo sin prefijo.
   */
  private static String prefijo(String valor) {
    String limpio = sinBarraFinal(valor == null ? "" : valor.trim());
    return limpio.isEmpty() || limpio.startsWith("/") ? limpio : "/" + limpio;
  }

  private static String sinBarraFinal(String url) {
    String limpio = url == null ? "" : url.trim();
    while (limpio.endsWith("/")) {
      limpio = limpio.substring(0, limpio.length() - 1);
    }
    return limpio;
  }

  // ── Cómo se está llegando a los módulos ────────────────────────────────────

  public boolean usaGateway() {
    return !gateway.isEmpty();
  }

  public String urlGateway() {
    return gateway;
  }

  /** La base de un módulo tal como se la está llamando, para poder mostrarla. */
  public String baseDe(Modulo modulo) {
    return switch (modulo) {
      case DONACIONES -> donaciones;
      case DONADORES -> donadores;
      case LOGISTICA -> logistica;
      case INCENTIVOS -> incentivos;
    };
  }

  /** Una línea que dice por dónde van los pedidos: sirve para no tener que adivinarlo. */
  public String descripcionDeConexion() {
    return usaGateway()
        ? "los pedidos van por el gateway " + gateway
        : "sin gateway: los pedidos van directo a cada módulo (la variable DONATRACK_GATEWAY_URL "
            + "está vacía)";
  }

  // ── Direcciones de cada módulo ─────────────────────────────────────────────

  /** Para saber si el gateway está despierto, sin pasar por ningún módulo. */
  public String getGateway(String path) {
    if (!usaGateway()) {
      throw new IllegalStateException("No hay gateway configurado.");
    }
    return pedir(HttpMethod.GET, gateway + path, null);
  }

  /** Solo existe a través del gateway. */
  public boolean hayServicioDeConsultas() {
    return !consultas.isEmpty();
  }

  public String getConsultas(String path) {
    return getConsultas(path, Map.of());
  }

  /**
   * Una consulta al modelo de lectura, con sus parámetros de URL.
   *
   * <p>Los valores se codifican uno por uno y la dirección se manda ya armada. Pasarla como texto
   * dejaría sin codificar un {@code &} o un {@code +} dentro de un valor —una categoría «Higiene &
   * Limpieza» cortaría el parámetro en dos— y codificarlos de antemano los codificaría dos veces. Los
   * parámetros vacíos no se mandan: todos son opcionales.
   */
  public String getConsultas(String path, Map<String, String> parametros) {
    exigirConsultas();
    org.springframework.web.util.UriComponentsBuilder url =
        org.springframework.web.util.UriComponentsBuilder.fromUriString(consultas + path);
    Map<String, Object> valores = new java.util.LinkedHashMap<>();
    parametros.forEach(
        (nombre, valor) -> {
          if (valor != null && !valor.isBlank()) {
            url.queryParam(nombre, "{" + nombre + "}");
            valores.put(nombre, valor.trim());
          }
        });
    java.net.URI uri = url.encode().buildAndExpand(valores).toUri();
    return pedir(HttpMethod.GET, uri, uri.toString(), null);
  }

  /** Vaciar el modelo de lectura. Solo para la demostración. */
  public String deleteConsultas(String path) {
    exigirConsultas();
    return pedir(HttpMethod.DELETE, consultas + path, null);
  }

  private void exigirConsultas() {
    if (!hayServicioDeConsultas()) {
      throw new IllegalStateException("El Servicio de Consultas solo se alcanza por el gateway.");
    }
  }

  public String getDonaciones(String path) {
    return pedir(HttpMethod.GET, donaciones + path, null);
  }

  public String postDonaciones(String path, Object body) {
    return pedir(HttpMethod.POST, donaciones + path, body);
  }

  public String patchDonaciones(String path, Object body) {
    return pedir(HttpMethod.PATCH, donaciones + path, body);
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

  public String putLogistica(String path, Object body) {
    return pedir(HttpMethod.PUT, logistica + path, body);
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
    // Se arma la dirección igual que lo haría RestTemplate con un texto, para que nada cambie.
    return pedir(metodo, rest.getUriTemplateHandler().expand(url), url, body);
  }

  /**
   * @param uri la dirección tal como se manda
   * @param url la misma dirección, para mostrarla en los mensajes
   */
  private String pedir(HttpMethod metodo, java.net.URI uri, String url, Object body) {
    log.info("{} {}", metodo, url);
    try {
      String resp =
          rest.exchange(uri, metodo, new HttpEntity<>(body, cabeceras()), String.class).getBody();
      return resp == null || resp.isBlank() ? "Operación realizada." : resp;
    } catch (HttpStatusCodeException e) {
      String explicacion = explicar(e, url);
      // Solo el código decide «no existe»: el texto no sirve, porque un 502 de Donaciones puede
      // citar un error de Logística que diga «no existe» y eso no habla del recurso pedido.
      if (e.getStatusCode().value() == 404) {
        throw new NoEncontrado(explicacion);
      }
      if (estaSuspendido(e)) {
        throw new Suspendido(explicacion);
      }
      // El gateway dice que el módulo de atrás no aceptó la conexión (503) o no contestó a tiempo
      // (504). Es lo mismo que un módulo que no responde: en Render casi siempre es un servicio
      // despertando, así que una consulta se repite una vez, y una operación no.
      if (esDelGateway(e)) {
        if (metodo == HttpMethod.GET) {
          return reintentarConsulta(metodo, uri, url, body, explicacion);
        }
        throw new SinRespuesta(explicacion);
      }
      if (esDeRender(e)) {
        throw new SinRespuesta(explicacion);
      }
      throw new ErrorDeModulo(e.getStatusCode().value(), explicacion);
    } catch (ResourceAccessException e) {
      // Una consulta se puede repetir sin consecuencias, y en Render el primer pedido a un
      // servicio dormido se pierde despertándolo. Una operación que modifica datos NO se
      // reintenta: sin respuesta no se sabe si llegó, y repetirla puede duplicarla.
      if (metodo == HttpMethod.GET) {
        return reintentarConsulta(metodo, uri, url, body, sinRespuesta(url));
      }
      throw new SinRespuesta(sinRespuesta(url));
    }
  }

  /** La segunda vuelta de una consulta que no obtuvo respuesta; si tampoco, queda sin respuesta. */
  private String reintentarConsulta(
      HttpMethod metodo, java.net.URI uri, String url, Object body, String siFalla) {
    log.info("Reintentando {} {} (el módulo estaba dormido)", metodo, url);
    try {
      String resp =
          rest.exchange(uri, metodo, new HttpEntity<>(body, cabeceras()), String.class).getBody();
      return resp == null || resp.isBlank() ? "Operación realizada." : resp;
    } catch (RuntimeException segunda) {
      throw new SinRespuesta(siFalla);
    }
  }

  /** Lo mismo para cualquier forma en que Render deja ver que el servicio está dormido. */
  private static final String CONSEJO_DESPERTAR =
      "Los servicios de Render se duermen: puede tardar hasta un minuto en despertar. "
          + "Conviene usar 'despertar_servicios' antes de seguir.";

  private String sinRespuesta(String url) {
    return "El módulo no responde (" + url + "). " + CONSEJO_DESPERTAR;
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
    String detalle = resumir(mensajeDelModulo(cuerpo));

    if (codigo == 404) {
      return "No existe eso que buscás. Detalle del módulo: " + detalle;
    }
    if (codigo == 400) {
      return "El módulo rechazó la operación por una regla de negocio: " + detalle;
    }
    if (codigo == 403) {
      // El pedido está bien armado: lo que falta es el permiso. Hoy es el donador no habilitado
      // para donar, y el motivo concreto lo dice el módulo.
      return "El módulo no permite esta operación: " + detalle;
    }
    if (codigo == 409) {
      return "Hay un conflicto con el estado actual: " + detalle;
    }
    if (codigo == 502 && errorDeNuestrosModulos(cuerpo) != null) {
      // El 502 lo armó uno de nuestros módulos: él contestó, el que falló es otro del que
      // depende. Decir «el módulo no responde» mandaría a despertar al que sí anda.
      return "El módulo recibió el pedido, pero otro módulo del que depende no respondió, así "
          + "que no pudo completar la operación. Detalle del módulo: "
          + detalle
          + ". Si ese otro módulo es un servicio de Render dormido, 'despertar_servicios' lo "
          + "despierta y después se puede reintentar.";
    }
    if (estaSuspendido(e)) {
      return "El servicio está suspendido en Render (" + url + "). No está dormido: lo suspendió "
          + "su dueño, así que 'despertar_servicios' no lo arregla y repetir el pedido tampoco. "
          + "Hay que reanudarlo desde el panel de Render, y eso solo lo puede hacer quien lo "
          + "administra.";
    }
    if (esDelGateway(e)) {
      return mensajeDelGateway(e, url);
    }
    if (esDeRender(e)) {
      return "Render no pudo comunicarse con el módulo ("
          + url
          + "): suele pasar mientras el servicio está dormido o arrancando. "
          + CONSEJO_DESPERTAR;
    }
    if (codigo >= 500) {
      return "El módulo tuvo un error interno (" + codigo + "). " + detalle;
    }
    return "La operación falló con código " + codigo + ". " + detalle;
  }

  /**
   * Un 502 sin cuerpo JSON lo generó Render, no el módulo.
   *
   * <p>Donaciones responde 502 con su {@code {"error": ...}} cuando otro módulo no le contestó.
   * Render, en cambio, responde 502 con una página HTML (o vacío) cuando el servicio está dormido
   * o arrancando: el pedido ni siquiera llegó a la aplicación. Un 502 con otro JSON lo contestó
   * alguna aplicación, así que tampoco es Render: queda como error del módulo.
   */
  private static boolean esDeRender(HttpStatusCodeException e) {
    return e.getStatusCode().value() == 502 && !esJson(e.getResponseBodyAsString());
  }

  /**
   * Render contesta 503 «Service Suspended» cuando el dueño del servicio lo suspendió desde el
   * panel. Se reconoce por el header {@code x-render-routing: suspend-by-user} o, si un proxy lo
   * sacó, por el texto de la página.
   *
   * <p>Es lo que más se parece a un servicio dormido y lo que menos se le parece en la solución: un
   * dormido despierta solo con el pedido; uno suspendido no despierta nunca hasta que lo reanudan.
   */
  private static boolean estaSuspendido(HttpStatusCodeException e) {
    if (e.getStatusCode().value() != 503) {
      return false;
    }
    org.springframework.http.HttpHeaders headers = e.getResponseHeaders();
    String enrutado = headers == null ? null : headers.getFirst("x-render-routing");
    String cuerpo = e.getResponseBodyAsString();
    return "suspend-by-user".equalsIgnoreCase(enrutado)
        || (cuerpo != null && cuerpo.contains("Service Suspended"));
  }

  /**
   * Un 503 o 504 que armó el gateway porque el módulo de atrás no le contestó.
   *
   * <p>El gateway distingue dos casos y los dos traen el mismo JSON de tres campos,
   * {@code {"error": ..., "modulo": ..., "traceId": ...}}: 503 si el módulo no aceptó la conexión y
   * 504 si no contestó dentro del plazo. Lo que lo delata es {@code modulo}: ningún módulo lo manda.
   *
   * <p>No se reconoce por «un 5xx con otro JSON» porque un módulo también puede contestar 503 por
   * su cuenta —por ejemplo, sin RabbitMQ configurado—, y eso no es que esté dormido: es un motivo
   * que hay que mostrar tal cual. Si el módulo sí contestó, el gateway pasa el código y el cuerpo
   * sin tocarlos, y se tratan como siempre.
   */
  private static boolean esDelGateway(HttpStatusCodeException e) {
    int codigo = e.getStatusCode().value();
    return (codigo == 503 || codigo == 504) && moduloDelGateway(e) != null;
  }

  /** El valor de {@code modulo} en el cuerpo de error del gateway, o null si no es ese cuerpo. */
  private static String moduloDelGateway(HttpStatusCodeException e) {
    try {
      com.fasterxml.jackson.databind.JsonNode nodo = MAPPER.readTree(e.getResponseBodyAsString());
      if (nodo != null && nodo.isObject() && nodo.path("modulo").isTextual()) {
        String modulo = nodo.path("modulo").asText();
        return modulo.isBlank() ? null : modulo;
      }
    } catch (Exception noEsJson) {
      // Una página de Render o texto plano: no es el cuerpo del gateway.
    }
    return null;
  }

  private static String mensajeDelGateway(HttpStatusCodeException e, String url) {
    String modulo = moduloDelGateway(e);
    com.fasterxml.jackson.databind.JsonNode cuerpo;
    try {
      cuerpo = MAPPER.readTree(e.getResponseBodyAsString());
    } catch (Exception inalcanzable) {
      cuerpo = MAPPER.createObjectNode();
    }
    String motivo = cuerpo.path("error").asText("");
    String traza = cuerpo.path("traceId").asText("");
    String que =
        e.getStatusCode().value() == 504
            ? "El módulo «" + modulo + "» no contestó a tiempo a través del gateway"
            : "El gateway no pudo conectarse con el módulo «" + modulo + "»";
    return que
        + " ("
        + url
        + ")"
        + (motivo.isBlank() ? "" : ": " + motivo)
        + (traza.isBlank() ? "" : " [traza " + traza + "]")
        + ". "
        + CONSEJO_DESPERTAR;
  }

  private static boolean esJson(String cuerpo) {
    if (cuerpo == null || cuerpo.isBlank()) {
      return false;
    }
    try {
      return MAPPER.readTree(cuerpo).isContainerNode();
    } catch (Exception noEsJson) {
      return false;
    }
  }

  /**
   * El mensaje de {@code {"error": "<mensaje>"}}, que es la forma en que Donaciones contesta todos
   * sus errores, o null si el cuerpo tiene otra forma.
   *
   * <p>Se exige que {@code error} sea el único campo porque el cuerpo de error por defecto de
   * Spring también trae uno, con el nombre del código ({@code "error": "Bad Gateway"}) junto a
   * {@code status}, {@code path} y {@code timestamp}: tomarlo por el de Donaciones haría pasar un
   * 502 de cualquier otro lado por «otro módulo no respondió».
   */
  private static String errorDeNuestrosModulos(String cuerpo) {
    if (cuerpo == null || cuerpo.isBlank()) {
      return null;
    }
    try {
      com.fasterxml.jackson.databind.JsonNode nodo = MAPPER.readTree(cuerpo);
      if (nodo != null && nodo.isObject() && nodo.size() == 1 && nodo.path("error").isTextual()) {
        String mensaje = nodo.path("error").asText();
        return mensaje.isBlank() ? null : mensaje;
      }
    } catch (Exception noEsJson) {
      // Una página HTML de Render o el texto plano de Donadores: no es el formato de Donaciones.
    }
    return null;
  }

  /** Para mostrar el mensaje limpio, sin las llaves y comillas del JSON que lo envuelve. */
  private static String mensajeDelModulo(String cuerpo) {
    String error = errorDeNuestrosModulos(cuerpo);
    return error != null ? error : cuerpo;
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
   *
   * <p>Es solo el 404. Un 502 nunca es «no existe», diga lo que diga su mensaje: o Render no llegó
   * al módulo ({@link SinRespuesta}) o el módulo contestó que otro del que depende no anda.
   */
  public static class NoEncontrado extends RuntimeException {
    public NoEncontrado(String mensaje) {
      super(mensaje);
    }
  }

  /**
   * El módulo contestó con un error que no es ninguno de los de arriba: una regla de negocio
   * (400), un conflicto (409), un 503 propio, un error interno.
   *
   * <p>Existe para poder decidir con el código y no con el texto del mensaje. Es el caso de un
   * módulo que contesta 503 porque no tiene RabbitMQ configurado: contestó, no está dormido, y lo
   * que corresponde es decirlo así.
   */
  public static class ErrorDeModulo extends RuntimeException {
    private final int codigo;

    public ErrorDeModulo(int codigo, String mensaje) {
      super(mensaje);
      this.codigo = codigo;
    }

    public int codigo() {
      return codigo;
    }
  }

  /**
   * El servicio está suspendido en Render por su dueño.
   *
   * <p>Se distingue de {@link SinRespuesta} porque la respuesta correcta es opuesta: a un servicio
   * dormido se lo despierta y se reintenta; a uno suspendido no se lo despierta desde acá, y
   * reintentar solo hace perder tiempo.
   */
  public static class Suspendido extends RuntimeException {
    public Suspendido(String mensaje) {
      super(mensaje);
    }
  }

  /**
   * El módulo no contestó a tiempo, o Render respondió 502 por él sin llegar a la aplicación.
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
