package ar.edu.utn.dds.k3003.mcp;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import org.hamcrest.Matchers;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.ExpectedCount;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestTemplate;

/**
 * Lo que se prueba acá no es que las llamadas salgan bien —de eso se ocupa {@link ToolsTest}— sino
 * que el relato que se muestra en la demostración diga la verdad: que no invente cambios, que
 * avise cuando un módulo no contestó y que explique lo que a simple vista se malinterpreta.
 */
class RelatoTest {

  private static final String DONACIONES = "http://donaciones";
  private static final String DONADORES = "http://donadores";
  private static final String LOGISTICA = "http://logistica";
  private static final String INCENTIVOS = "http://incentivos";

  private RestTemplate rest;
  private MockRestServiceServer servidor;
  private DonaTrackApi api;
  private OperacionTools operaciones;
  private DemoTools demo;
  private SesionMcp sesion;

  @BeforeEach
  void setUp() {
    rest = new RestTemplate();
    // Sin orden estricto: al relato le importa qué preguntó, no en qué secuencia.
    servidor = MockRestServiceServer.bindTo(rest).ignoreExpectOrder(true).build();
    api = new DonaTrackApi(rest, DONACIONES, DONADORES, LOGISTICA, INCENTIVOS);
    sesion = new SesionMcp();
    sesion.iniciarComoAdmin("admin");
    operaciones = new OperacionTools(api, "DEP-UTN-01", sesion, true);
    demo = new DemoTools(api, sesion);
  }

  // ── Donación ───────────────────────────────────────────────────────────────

  @Test
  @DisplayName("El relato de una donación cuenta qué hizo cada módulo")
  void relatoDeDonacion() {
    esperarProducto("3", "Arroz");
    esperarNecesidades(
        "3",
        """
        [{"id":"7","descripcion":"Arroz para el comedor","cantidadObjetivo":20,
          "cantidadActual":0,"tipo":"EXTRAORDINARIA"}]""");
    esperarStock("3", 0, 0);
    servidor
        .expect(requestTo(DONACIONES + "/donaciones"))
        .andExpect(method(HttpMethod.POST))
        .andRespond(
            withSuccess(
                """
                {"id":"12","donadorID":"1","productoID":"3","cantidad":10,"estado":"INGRESADA"}""",
                MediaType.APPLICATION_JSON));

    esperarPaquete("paq-12", "7");

    String relato = operaciones.registrarDonacion("1", "3", 10, "Diez kilos", null);

    assertTrue(relato.contains("nº 12"), "tiene que decir qué donación se creó");
    assertTrue(relato.contains("Arroz"), "el nombre del producto se lee mejor que el número");
    assertTrue(relato.contains("INGRESADA"));
    assertTrue(relato.contains("Donadores"), "hay que mostrar que se consultó a Donadores");
    assertTrue(
        relato.contains("armó el paquete paq-12 y lo asignó a la necesidad nº 7"),
        "el destino se lee del paquete, que es un dato, no se deduce del stock");
    assertTrue(
        relato.contains("no** se satisface al donar"),
        "es la confusión más frecuente: conviene aclararla en el momento");
    assertTrue(relato.contains("reportar_entrega"), "el relato encadena con el paso siguiente");
    assertTrue(
        relato.contains("Donaciones y Donadores escriben esta traza"),
        "la donación entra por Donaciones: se puede seguir en Datadog");
  }

  @Test
  @DisplayName("Si el stock sube, el relato dice que la donación quedó guardada")
  void donacionQueVaAStock() {
    esperarProducto("3", "Arroz");
    esperarNecesidades("3", "[]");
    esperarStock("3", 0, 10);
    sinPaquete("paq-12");
    servidor
        .expect(requestTo(DONACIONES + "/donaciones"))
        .andExpect(method(HttpMethod.POST))
        .andRespond(
            withSuccess(
                """
                {"id":"12","donadorID":"1","productoID":"3","cantidad":10,"estado":"INGRESADA"}""",
                MediaType.APPLICATION_JSON));

    String relato = operaciones.registrarDonacion("1", "3", 10, "Diez kilos", null);

    assertTrue(relato.contains("0 → 10"));
    assertTrue(relato.contains("quedó guardada"));
    assertFalse(
        relato.contains("lo asignó a la necesidad"),
        "no se puede decir las dos cosas: o se asignó o quedó en stock");
  }

  @Test
  @DisplayName("Si Logística no contesta, se dice, en vez de dar por hecho que no pasó nada")
  void moduloQueNoContesta() {
    esperarProducto("3", "Arroz");
    esperarNecesidades("3", "[]");
    servidor
        .expect(ExpectedCount.manyTimes(), requestTo(LOGISTICA + "/stock/3"))
        .andRespond(withStatus(HttpStatus.INTERNAL_SERVER_ERROR).body("boom"));
    servidor
        .expect(ExpectedCount.manyTimes(), requestTo(LOGISTICA + "/api/asignaciones/paquetes/paq-12"))
        .andRespond(withStatus(HttpStatus.INTERNAL_SERVER_ERROR).body("boom"));
    servidor
        .expect(requestTo(DONACIONES + "/donaciones"))
        .andExpect(method(HttpMethod.POST))
        .andRespond(
            withSuccess(
                """
                {"id":"12","donadorID":"1","productoID":"3","cantidad":10,"estado":"INGRESADA"}""",
                MediaType.APPLICATION_JSON));

    String relato = operaciones.registrarDonacion("1", "3", 10, "Diez kilos", null);

    assertTrue(relato.contains("nº 12"), "la donación se registró igual: eso no se pierde");
    assertTrue(relato.contains("Sin respuesta de: Logística"));
    assertFalse(relato.contains("quedó guardada"), "no se puede afirmar lo que no se pudo ver");
  }

  @Test
  @DisplayName("Si el worker de Logística todavía no terminó, se dice en vez de inventar el destino")
  void workerQueTodaviaNoTermino() {
    esperarProducto("3", "Arroz");
    esperarNecesidades(
        "3",
        """
        [{"id":"7","descripcion":"Arroz","cantidadObjetivo":20,"cantidadActual":0}]""");
    esperarStock("3", 0, 0);
    sinPaquete("paq-12");
    servidor
        .expect(requestTo(DONACIONES + "/donaciones"))
        .andExpect(method(HttpMethod.POST))
        .andRespond(
            withSuccess(
                "{\"id\":\"12\",\"cantidad\":10,\"estado\":\"INGRESADA\"}",
                MediaType.APPLICATION_JSON));

    String relato = operaciones.registrarDonacion("1", "3", 10, "Diez kilos", null);

    assertTrue(relato.contains("todavía la está procesando"));
    assertFalse(
        relato.contains("lo asignó"),
        "que el stock no haya subido no prueba nada si el worker no terminó");
    assertFalse(
        relato.contains("Sin respuesta de: Logística"),
        "Logística contestó: que el paquete no exista todavía no es que esté caída");
  }

  @Test
  @DisplayName("La operación viaja con una traza para poder seguirla en Datadog")
  void trazaEnElHeader() {
    esperarProducto("3", "Arroz");
    esperarNecesidades("3", "[]");
    esperarStock("3", 0, 0);
    servidor
        .expect(requestTo(DONACIONES + "/donaciones"))
        .andExpect(method(HttpMethod.POST))
        .andExpect(header("X-Trace-Id", Matchers.startsWith("mcp-")))
        .andRespond(withSuccess("{\"id\":\"12\"}", MediaType.APPLICATION_JSON));
    esperarPaquete("paq-12", "7");

    String relato = operaciones.registrarDonacion("1", "3", 10, "Diez kilos", null);

    servidor.verify();
    assertTrue(relato.contains("traza `mcp-"), "hay que mostrar la traza para poder buscarla");
  }

  // ── Entrega ────────────────────────────────────────────────────────────────

  @Test
  @DisplayName("El relato de una entrega muestra cómo avanzó la necesidad")
  void relatoDeEntrega() {
    servidor
        .expect(ExpectedCount.manyTimes(), requestTo(LOGISTICA + "/api/asignaciones/paquetes/paq-12"))
        // Copiado de una respuesta real: Logística devuelve los campos en minúscula, no como los
        // declara su Swagger.
        .andRespond(
            withSuccess(
                """
                {"asignacionid":"8ecc3d3a","paqueteid":"paq-12","necesidadid":"7",
                 "fecha":"2026-09-10T16:32:55","estado":"ASIGNADA","origen":"MATCHMAKING",
                 "donacionid":"12","productoid":"3","cantidad":10}""",
                MediaType.APPLICATION_JSON));
    servidor
        .expect(requestTo(DONACIONES + "/donaciones/12"))
        .andRespond(
            withSuccess(
                "{\"id\":\"12\",\"productoID\":\"3\",\"estado\":\"INGRESADA\"}",
                MediaType.APPLICATION_JSON));
    servidor
        .expect(requestTo(DONADORES + "/necesidades/7"))
        .andRespond(
            withSuccess(
                """
                {"id":"7","descripcion":"Arroz para el comedor","cantidadObjetivo":20,
                 "cantidadActual":0}""",
                MediaType.APPLICATION_JSON));
    esperarStock("3", 0, 0);
    servidor
        .expect(requestTo(LOGISTICA + "/api/asignaciones/reportar-entrega"))
        .andExpect(method(HttpMethod.POST))
        .andRespond(withSuccess("{}", MediaType.APPLICATION_JSON));
    servidor
        .expect(requestTo(DONACIONES + "/donaciones/12"))
        .andRespond(
            withSuccess(
                "{\"id\":\"12\",\"productoID\":\"3\",\"estado\":\"ACEPTADA\"}",
                MediaType.APPLICATION_JSON));
    servidor
        .expect(requestTo(DONADORES + "/necesidades/7"))
        .andRespond(
            withSuccess(
                """
                {"id":"7","descripcion":"Arroz para el comedor","cantidadObjetivo":20,
                 "cantidadActual":10}""",
                MediaType.APPLICATION_JSON));

    String relato = operaciones.reportarEntrega(null, "12", "3", 10);

    assertTrue(relato.contains("INGRESADA → ACEPTADA"), "la donación recién ahora se da por buena");
    assertTrue(relato.contains("0/20"), "hay que mostrar de dónde venía la necesidad");
    assertTrue(relato.contains("10/20"), "y cómo quedó");
    assertTrue(relato.contains("Todavía le falta"));
    assertTrue(relato.contains("paq-12"), "hay que poder leer los campos reales de Logística");
    assertTrue(relato.contains("matchmaking"), "de dónde salió la asignación es parte del relato");
    assertTrue(
        relato.contains("no se puede seguir de punta a punta"),
        "la entrega entra por Logística, que no propaga la traza: prometer lo contrario es mentir");
  }

  @Test
  @DisplayName("Sin el código del paquete, se deduce de la donación")
  void paqueteDeducido() {
    servidor
        .expect(ExpectedCount.manyTimes(), requestTo(LOGISTICA + "/api/asignaciones/paquetes/paq-5"))
        .andRespond(withStatus(HttpStatus.NOT_FOUND).body("no existe"));
    servidor
        .expect(ExpectedCount.manyTimes(), requestTo(DONACIONES + "/donaciones/5"))
        .andRespond(withSuccess("{\"id\":\"5\"}", MediaType.APPLICATION_JSON));
    servidor
        .expect(requestTo(LOGISTICA + "/api/asignaciones/reportar-entrega"))
        .andExpect(method(HttpMethod.POST))
        .andExpect(
            org.springframework.test.web.client.match.MockRestRequestMatchers.content()
                .string(Matchers.containsString("paq-5")))
        .andRespond(withSuccess("{}", MediaType.APPLICATION_JSON));

    operaciones.reportarEntrega(null, "5", "3", 10);

    servidor.verify();
  }

  // ── Queja ──────────────────────────────────────────────────────────────────

  @Test
  @DisplayName("El relato de una queja muestra la escalada del donador")
  void relatoDeQueja() {
    servidor
        .expect(ExpectedCount.manyTimes(), requestTo(DONACIONES + "/donaciones/12"))
        .andRespond(
            withSuccess(
                "{\"id\":\"12\",\"donadorID\":\"1\",\"estado\":\"ACEPTADA\"}",
                MediaType.APPLICATION_JSON));
    servidor
        .expect(requestTo(DONADORES + "/donadores/1"))
        .andRespond(
            withSuccess(
                "{\"id\":\"1\",\"nombre\":\"Ana\",\"estado\":\"VERIFICADO\"}",
                MediaType.APPLICATION_JSON));
    servidor
        .expect(ExpectedCount.manyTimes(), requestTo(DONADORES + "/donadores/1/quejas"))
        .andRespond(withSuccess("[{},{},{},{},{},{},{},{}]", MediaType.APPLICATION_JSON));
    servidor
        .expect(ExpectedCount.manyTimes(), requestTo(INCENTIVOS + "/donadores/1/insignias"))
        .andRespond(withSuccess("[]", MediaType.APPLICATION_JSON));
    servidor
        .expect(requestTo(DONACIONES + "/donaciones/12/quejas"))
        .andExpect(method(HttpMethod.POST))
        .andRespond(withSuccess("{}", MediaType.APPLICATION_JSON));
    servidor
        .expect(requestTo(DONADORES + "/donadores/1"))
        .andRespond(
            withSuccess(
                "{\"id\":\"1\",\"nombre\":\"Ana\",\"estado\":\"SOSPECHOSO\"}",
                MediaType.APPLICATION_JSON));

    String relato = operaciones.registrarQueja("12", "Llegó en mal estado");

    assertTrue(relato.contains("VERIFICADO → SOSPECHOSO"));
    assertTrue(relato.contains("8 quejas"));
    assertTrue(relato.contains("termina baneado"), "conviene decir qué implica el cambio");
  }

  // ── Preparar y reiniciar ───────────────────────────────────────────────────

  @Test
  @DisplayName("Reiniciar borra los cuatro módulos y avisa cuál falló")
  void reinicio() {
    servidor
        .expect(requestTo(DONACIONES + "/donaciones/reset"))
        .andExpect(method(HttpMethod.DELETE))
        .andRespond(withSuccess());
    servidor
        .expect(requestTo(DONADORES + "/reset"))
        .andExpect(method(HttpMethod.DELETE))
        .andRespond(withSuccess());
    servidor
        .expect(requestTo(LOGISTICA + "/api/limpiar-base"))
        .andExpect(method(HttpMethod.DELETE))
        .andRespond(withSuccess());
    servidor
        .expect(requestTo(INCENTIVOS + "/admin/clear"))
        .andRespond(withStatus(HttpStatus.SERVICE_UNAVAILABLE).body("caído"));

    String salida = demo.reiniciarSistema();

    servidor.verify();
    assertTrue(salida.contains("Donaciones"));
    assertTrue(salida.contains("Logística"));
    assertTrue(salida.contains("⚠️"), "el módulo que falló tiene que quedar a la vista");
    assertTrue(salida.contains("preparar_demo"), "y decir cómo sigue");
  }

  @Test
  @DisplayName("Reiniciar sin ser admin no borra nada")
  void reinicioSinPermiso() {
    sesion.cerrarSesion();
    org.junit.jupiter.api.Assertions.assertThrows(
        IllegalStateException.class, () -> demo.reiniciarSistema());
  }

  @Test
  @DisplayName("Preparar la demo crea el depósito por defecto, no uno cualquiera")
  void prepararUsaElDepositoPorDefecto() {
    SeedTools seed = new SeedTools(api, sesion, "DEP-UTN-01");

    // Antes de escribir nada consulta los cuatro módulos para despertarlos: en Render el primer
    // pedido a un servicio dormido se pierde, y un POST perdido no se puede reintentar.
    servidor
        .expect(requestTo(DONACIONES + "/productos"))
        .andExpect(method(HttpMethod.GET))
        .andRespond(withSuccess("[]", MediaType.APPLICATION_JSON));
    servidor
        .expect(requestTo(DONADORES + "/donadores"))
        .andExpect(method(HttpMethod.GET))
        .andRespond(withSuccess("[]", MediaType.APPLICATION_JSON));
    servidor
        .expect(requestTo(LOGISTICA + "/depositos"))
        .andExpect(method(HttpMethod.GET))
        .andRespond(withSuccess("[]", MediaType.APPLICATION_JSON));
    servidor
        .expect(requestTo(INCENTIVOS + "/insignias"))
        .andExpect(method(HttpMethod.GET))
        .andRespond(withSuccess("[]", MediaType.APPLICATION_JSON));

    servidor
        .expect(requestTo(DONACIONES + "/identificadores"))
        .andExpect(method(HttpMethod.POST))
        .andRespond(withSuccess("{\"id\":\"1\"}", MediaType.APPLICATION_JSON));
    servidor
        .expect(requestTo(DONACIONES + "/productos"))
        .andExpect(method(HttpMethod.POST))
        .andRespond(withSuccess("{\"id\":\"2\"}", MediaType.APPLICATION_JSON));
    servidor
        .expect(requestTo(DONADORES + "/donadores"))
        .andExpect(method(HttpMethod.POST))
        .andRespond(withSuccess("{\"id\":\"3\"}", MediaType.APPLICATION_JSON));
    servidor
        .expect(requestTo(DONADORES + "/entidades"))
        .andExpect(method(HttpMethod.POST))
        .andRespond(withSuccess("{\"id\":\"4\"}", MediaType.APPLICATION_JSON));
    servidor
        .expect(requestTo(LOGISTICA + "/depositos"))
        .andExpect(method(HttpMethod.POST))
        .andExpect(
            org.springframework.test.web.client.match.MockRestRequestMatchers.content()
                .string(Matchers.containsString("DEP-UTN-01")))
        .andRespond(withSuccess("{}", MediaType.APPLICATION_JSON));
    servidor
        .expect(requestTo(INCENTIVOS + "/insignias"))
        .andExpect(method(HttpMethod.POST))
        .andRespond(withSuccess("{}", MediaType.APPLICATION_JSON));
    servidor
        .expect(requestTo(INCENTIVOS + "/misiones"))
        .andExpect(method(HttpMethod.POST))
        .andRespond(withSuccess("{}", MediaType.APPLICATION_JSON));
    servidor
        .expect(requestTo(DONADORES + "/necesidades"))
        .andExpect(method(HttpMethod.POST))
        .andRespond(withSuccess("{\"id\":\"5\"}", MediaType.APPLICATION_JSON));

    String salida = seed.prepararDemo(null);

    servidor.verify();
    assertTrue(salida.contains("DEP-UTN-01"), "es el depósito al que donan el bot y el MCP");
    assertTrue(salida.contains("Donador nº 3"), "los ids hay que tenerlos a mano para los flujos");
    assertFalse(
        salida.contains("Donación nº"),
        "por defecto solo carga precondiciones: los flujos se muestran de a uno");
  }

  // ── Estado ─────────────────────────────────────────────────────────────────

  @Test
  @DisplayName("El estado del sistema resume los cuatro módulos y marca el que no responde")
  void estadoDelSistema() {
    servidor
        .expect(ExpectedCount.manyTimes(), requestTo(DONACIONES + "/productos"))
        .andRespond(withSuccess("[{\"id\":\"1\"},{\"id\":\"2\"}]", MediaType.APPLICATION_JSON));
    servidor
        .expect(requestTo(DONACIONES + "/donaciones"))
        .andRespond(
            withSuccess(
                "[{\"estado\":\"INGRESADA\"},{\"estado\":\"ACEPTADA\"}]",
                MediaType.APPLICATION_JSON));
    servidor
        .expect(requestTo(DONADORES + "/donadores"))
        .andRespond(withSuccess("[{\"estado\":\"VERIFICADO\"}]", MediaType.APPLICATION_JSON));
    servidor
        .expect(requestTo(DONADORES + "/entidades"))
        .andRespond(withSuccess("[{\"id\":\"1\"}]", MediaType.APPLICATION_JSON));
    servidor
        .expect(requestTo(DONADORES + "/necesidades?productoID=1"))
        .andRespond(
            withSuccess(
                "[{\"cantidadActual\":20,\"cantidadObjetivo\":20}]", MediaType.APPLICATION_JSON));
    servidor
        .expect(requestTo(DONADORES + "/necesidades?productoID=2"))
        .andRespond(
            withSuccess(
                "[{\"cantidadActual\":0,\"cantidadObjetivo\":10}]", MediaType.APPLICATION_JSON));
    // El stock real se pide por producto: el listado de depósitos lo devuelve vacío aunque haya
    // unidades guardadas.
    servidor
        .expect(requestTo(LOGISTICA + "/stock/1"))
        .andRespond(withSuccess("{\"disponible\":5}", MediaType.APPLICATION_JSON));
    servidor
        .expect(requestTo(LOGISTICA + "/stock/2"))
        .andRespond(withSuccess("{\"disponible\":20}", MediaType.APPLICATION_JSON));
    servidor
        .expect(requestTo(LOGISTICA + "/depositos"))
        .andRespond(
            withSuccess(
                "[{\"id\":\"DEP-UTN-01\",\"stockActual\":[]}]", MediaType.APPLICATION_JSON));
    servidor
        .expect(requestTo(INCENTIVOS + "/insignias"))
        .andRespond(withStatus(HttpStatus.SERVICE_UNAVAILABLE));
    servidor
        .expect(requestTo(INCENTIVOS + "/misiones"))
        .andRespond(withStatus(HttpStatus.SERVICE_UNAVAILABLE));

    String salida = demo.estadoDelSistema();

    assertTrue(salida.contains("2 productos"));
    assertTrue(salida.contains("1 INGRESADA"));
    assertTrue(salida.contains("2 necesidades (1 pendientes, 1 cubiertas)"));
    assertTrue(
        salida.contains("25 unidades en stock"),
        "el stock sale de sumar el de cada producto, no del listado de depósitos");
    assertTrue(salida.contains("no respondió"), "Incentivos caído no se puede mostrar como vacío");
  }

  // ── Auxiliares de armado ───────────────────────────────────────────────────

  private void esperarProducto(String id, String nombre) {
    servidor
        .expect(ExpectedCount.manyTimes(), requestTo(DONACIONES + "/productos/" + id))
        .andRespond(
            withSuccess(
                "{\"id\":\"" + id + "\",\"nombre\":\"" + nombre + "\"}",
                MediaType.APPLICATION_JSON));
  }

  private void esperarNecesidades(String productoId, String json) {
    servidor
        .expect(
            ExpectedCount.manyTimes(), requestTo(DONADORES + "/necesidades?productoID=" + productoId))
        .andRespond(withSuccess(json, MediaType.APPLICATION_JSON));
  }

  /** Una asignación real de Logística, con los campos en minúscula como los devuelve. */
  private void esperarPaquete(String paquete, String necesidad) {
    servidor
        .expect(ExpectedCount.manyTimes(), requestTo(LOGISTICA + "/api/asignaciones/paquetes/" + paquete))
        .andRespond(
            withSuccess(
                "{\"paqueteid\":\""
                    + paquete
                    + "\",\"necesidadid\":\""
                    + necesidad
                    + "\",\"estado\":\"ASIGNADA\",\"origen\":\"MATCHMAKING\",\"cantidad\":10}",
                MediaType.APPLICATION_JSON));
  }

  /** Logística contesta, pero el paquete todavía no existe. */
  private void sinPaquete(String paquete) {
    servidor
        .expect(ExpectedCount.manyTimes(), requestTo(LOGISTICA + "/api/asignaciones/paquetes/" + paquete))
        .andRespond(withStatus(HttpStatus.NOT_FOUND).body("no existe"));
  }

  /** El stock se consulta dos veces: antes y después. Por eso van dos respuestas. */
  private void esperarStock(String productoId, int antes, int despues) {
    servidor
        .expect(requestTo(LOGISTICA + "/stock/" + productoId))
        .andRespond(withSuccess("{\"disponible\":" + antes + "}", MediaType.APPLICATION_JSON));
    servidor
        .expect(requestTo(LOGISTICA + "/stock/" + productoId))
        .andRespond(withSuccess("{\"disponible\":" + despues + "}", MediaType.APPLICATION_JSON));
  }
}
