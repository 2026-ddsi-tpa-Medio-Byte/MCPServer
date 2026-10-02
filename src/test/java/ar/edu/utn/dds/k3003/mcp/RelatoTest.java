package ar.edu.utn.dds.k3003.mcp;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
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

    // Solo el paquete: la donación que el relato necesita se deduce de su nombre.
    String relato = operaciones.reportarEntrega("paq-12", null);

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
            content()
                .json(
                    "{\"paqueteid\":\"paq-5\"}",
                    org.springframework.test.json.JsonCompareMode.STRICT))
        .andRespond(withSuccess("{}", MediaType.APPLICATION_JSON));

    operaciones.reportarEntrega(null, "5");

    servidor.verify();
  }

  @Test
  @DisplayName("Si el paquete no se llama paq- más la donación, la donación se lee de la asignación")
  void donacionDeLaAsignacion() {
    servidor
        .expect(ExpectedCount.manyTimes(), requestTo(LOGISTICA + "/api/asignaciones/paquetes/ENVIO-77"))
        .andRespond(
            withSuccess(
                "{\"paqueteid\":\"ENVIO-77\",\"donacionid\":\"12\",\"estado\":\"ASIGNADA\"}",
                MediaType.APPLICATION_JSON));
    servidor
        .expect(ExpectedCount.manyTimes(), requestTo(DONACIONES + "/donaciones/12"))
        .andRespond(
            withSuccess("{\"id\":\"12\",\"estado\":\"INGRESADA\"}", MediaType.APPLICATION_JSON));
    servidor
        .expect(requestTo(LOGISTICA + "/api/asignaciones/reportar-entrega"))
        .andExpect(method(HttpMethod.POST))
        .andRespond(withSuccess("{}", MediaType.APPLICATION_JSON));

    String relato = operaciones.reportarEntrega("ENVIO-77", null);

    servidor.verify();
    assertTrue(relato.contains("donación nº 12"), "sin deducirla del nombre, se le pregunta a Logística");
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
    // La quinta queja es la que lo pasa a SOSPECHOSO (Donador.actualizarEstadoSegunQuejas).
    servidor
        .expect(ExpectedCount.manyTimes(), requestTo(DONADORES + "/donadores/1/quejas"))
        .andRespond(withSuccess("[{},{},{},{},{}]", MediaType.APPLICATION_JSON));
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
    assertTrue(relato.contains("5 quejas"));
    assertTrue(relato.contains("termina baneado"), "conviene decir qué implica el cambio");
    assertTrue(
        relato.contains("con 5 quejas el donador pasa a SOSPECHOSO y con 10 queda BANEADO"),
        "los umbrales tienen que ser los del módulo, no aproximados");
    assertTrue(
        relato.contains("donación entregada distinta"),
        "para seguir la escalada hace falta una donación entregada por queja");
  }

  // ── Necesidad ──────────────────────────────────────────────────────────────

  @Test
  @DisplayName("Con stock y una RECURRENTE que no alcanza a cubrir, el relato no dice que no había nada")
  void necesidadRecurrenteConStockQueNoAlcanza() {
    esperarNecesidades("3", "[]");
    esperarStock("3", 5, 5);
    esperarAltaDeNecesidad("RECURRENTE");
    esperarNecesidadGuardada(0, "RECURRENTE");

    String relato =
        operaciones.registrarNecesidad("1", "3", 20, "Arroz para el comedor", 8, "recurrente");

    assertFalse(
        relato.contains("No había nada guardado"), "había 5 unidades: decir lo contrario es mentir");
    assertTrue(relato.contains("Había 5 unidades guardadas"), "lo que no cambió también se cuenta");
    assertTrue(relato.contains("0/20"), "la necesidad quedó en cero");
    assertTrue(relato.contains("RECURRENTE solo se asigna si el stock la cubre entera"));
    assertTrue(
        relato.contains("Logística no haya confirmado"),
        "desde afuera no se ve cuál de las dos causas fue: se dicen ambas");
  }

  @Test
  @DisplayName("Con stock y una EXTRAORDINARIA en cero, la regla del tipo se descarta como causa")
  void necesidadExtraordinariaConStockSinAsignar() {
    esperarNecesidades("3", "[]");
    esperarStock("3", 5, 5);
    esperarAltaDeNecesidad("EXTRAORDINARIA");
    esperarNecesidadGuardada(0, "EXTRAORDINARIA");

    String relato =
        operaciones.registrarNecesidad("1", "3", 20, "Arroz para el comedor", 8, "extraordinaria");

    assertFalse(relato.contains("No había nada guardado"));
    assertTrue(relato.contains("Logística no haya confirmado"));
    assertFalse(
        relato.contains("Las causas posibles son dos"),
        "una EXTRAORDINARIA acepta asignación parcial: no hay que mandar a buscar por ahí");
  }

  @Test
  @DisplayName("Sin stock guardado, el relato dice que la necesidad queda esperando una donación")
  void necesidadSinStock() {
    esperarNecesidades("3", "[]");
    esperarStock("3", 0, 0);
    esperarAltaDeNecesidad("RECURRENTE");
    esperarNecesidadGuardada(0, "RECURRENTE");

    String relato =
        operaciones.registrarNecesidad("1", "3", 20, "Arroz para el comedor", 8, "recurrente");

    assertTrue(relato.contains("No había nada guardado"));
    assertTrue(relato.contains("0/20"));
    assertFalse(relato.contains("causas posibles"), "sin stock no hay nada que explicar");
  }

  @Test
  @DisplayName("Si se asignó stock al crearla, lo cubierto se lee de la necesidad, no del alta")
  void necesidadCubiertaConStock() {
    esperarNecesidades("3", "[]");
    esperarStock("3", 30, 10);
    // El alta devuelve el DTO de cátedra, sin cantidadActual: de ahí solo saldría un 0.
    esperarAltaDeNecesidad("RECURRENTE");
    esperarNecesidadGuardada(20, "RECURRENTE");

    String relato =
        operaciones.registrarNecesidad("1", "3", 20, "Arroz para el comedor", 8, "recurrente");

    assertTrue(relato.contains("20/20"), "lo cubierto sale de releer la necesidad");
    assertTrue(relato.contains("30 → 10"));
    assertTrue(relato.contains("Logística confirmó la asignación"));
    // Con espacio adelante: «20/20» contiene «0/20».
    assertFalse(relato.contains(" 0/20"), "el 0 del alta no es lo que quedó guardado");
  }

  @Test
  @DisplayName("Si Logística da 502 al pedir el stock, no se toma como que no había nada guardado")
  void necesidadConLogisticaCaida() {
    esperarNecesidades("3", "[]");
    servidor
        .expect(ExpectedCount.manyTimes(), requestTo(LOGISTICA + "/stock/3"))
        .andRespond(
            withStatus(HttpStatus.BAD_GATEWAY)
                .body("<html>502 Bad Gateway</html>")
                .contentType(MediaType.TEXT_HTML));
    esperarAltaDeNecesidad("EXTRAORDINARIA");
    esperarNecesidadGuardada(0, "EXTRAORDINARIA");

    String relato =
        operaciones.registrarNecesidad("1", "3", 20, "Arroz para el comedor", 8, "extraordinaria");

    assertFalse(
        relato.contains("No había nada guardado"),
        "un 502 es que Logística no anda, no que el producto no tenga stock");
    assertTrue(relato.contains("no se pudo leer el stock"));
    assertTrue(relato.contains("Sin respuesta de: Logística"));
  }

  @Test
  @DisplayName("Si no se pudo releer la necesidad, el relato no inventa cuánto quedó cubierto")
  void necesidadQueNoSePudoReleer() {
    esperarNecesidades("3", "[]");
    esperarStock("3", 5, 5);
    esperarAltaDeNecesidad("EXTRAORDINARIA");
    servidor
        .expect(ExpectedCount.manyTimes(), requestTo(DONADORES + "/necesidades/9"))
        .andRespond(withStatus(HttpStatus.SERVICE_UNAVAILABLE));

    String relato =
        operaciones.registrarNecesidad("1", "3", 20, "Arroz para el comedor", 8, "extraordinaria");

    assertTrue(relato.contains("nº 9"), "la necesidad se creó igual: eso no se pierde");
    assertFalse(relato.contains("0/20"), "no se vio cuánto quedó cubierto");
    assertTrue(relato.contains("no se puede afirmar si se le asignaron"));
    assertTrue(relato.contains("Sin respuesta de: Donadores"));
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

  // ── Reiniciar y preparar un módulo solo ────────────────────────────────────

  @Test
  @DisplayName("Reiniciar un módulo borra solo ese y avisa qué queda inconsistente")
  void reiniciarUnModulo() {
    servidor
        .expect(requestTo(LOGISTICA + "/api/limpiar-base"))
        .andExpect(method(HttpMethod.DELETE))
        .andRespond(withSuccess("{}", MediaType.APPLICATION_JSON));

    String salida = demo.reiniciarModulo("Logística");

    // Si hubiera tocado otro módulo, el servidor simulado habría fallado por pedido inesperado.
    servidor.verify();
    assertTrue(salida.contains("Logística reiniciado"));
    assertTrue(
        salida.contains("quedaron como estaban"), "hay que decir que los otros tres no se tocaron");
    assertTrue(
        salida.contains("depósito por defecto"), "y que sin el depósito las donaciones fallan");
  }

  @Test
  @DisplayName("Un módulo que no existe no borra nada y dice cuáles hay")
  void moduloQueNoExiste() {
    IllegalArgumentException error =
        org.junit.jupiter.api.Assertions.assertThrows(
            IllegalArgumentException.class, () -> demo.reiniciarModulo("logisitca"));

    servidor.verify();
    assertTrue(error.getMessage().contains("donaciones"), "el error tiene que listar los que hay");
  }

  @Test
  @DisplayName("Preparar un solo módulo no escribe en los otros tres")
  void prepararUnSoloModulo() {
    SeedTools seed = new SeedTools(api, sesion, "DEP-UTN-01");
    // La consulta de siempre antes de escribir: en Render el primer pedido a un servicio dormido
    // se pierde, y un POST perdido no se puede reintentar.
    servidor
        .expect(requestTo(LOGISTICA + "/depositos"))
        .andExpect(method(HttpMethod.GET))
        .andRespond(withSuccess("[]", MediaType.APPLICATION_JSON));
    servidor
        .expect(requestTo(LOGISTICA + "/depositos"))
        .andExpect(method(HttpMethod.POST))
        .andRespond(withSuccess("{}", MediaType.APPLICATION_JSON));

    String salida = seed.prepararModulo("logistica", null);

    servidor.verify();
    assertTrue(salida.contains("DEP-UTN-01"), "tiene que decir con qué depósito quedó");
  }

  @Test
  @DisplayName("Preparar donadores con un producto dado no crea ninguno en Donaciones")
  void prepararDonadoresConProductoDado() {
    SeedTools seed = new SeedTools(api, sesion, "DEP-UTN-01");
    servidor
        .expect(requestTo(DONADORES + "/donadores"))
        .andExpect(method(HttpMethod.GET))
        .andRespond(withSuccess("[]", MediaType.APPLICATION_JSON));
    servidor
        .expect(requestTo(DONADORES + "/donadores"))
        .andExpect(method(HttpMethod.POST))
        .andRespond(
            withSuccess(
                """
                {"id":"3"}
                """,
                MediaType.APPLICATION_JSON));
    servidor
        .expect(requestTo(DONADORES + "/entidades"))
        .andExpect(method(HttpMethod.POST))
        .andRespond(
            withSuccess(
                """
                {"id":"5"}
                """,
                MediaType.APPLICATION_JSON));
    servidor
        .expect(requestTo(DONADORES + "/necesidades"))
        .andExpect(method(HttpMethod.POST))
        .andExpect(
            content()
                .json(
                    """
                    {"entidadID":"5","productoSolicitadoID":"7","cantidadObjetivo":20,
                     "tipo":"EXTRAORDINARIA"}
                    """))
        .andRespond(
            withSuccess(
                """
                {"id":"9"}
                """,
                MediaType.APPLICATION_JSON));

    String salida = seed.prepararModulo("donadores", "7");

    servidor.verify();
    assertTrue(salida.contains("necesidad nº 9"), "tiene que decir con qué necesidad quedó");
  }

  @Test
  @DisplayName("Sin productos cargados se cargan donador y entidad, y se avisa de la necesidad")
  void prepararDonadoresSinProductos() {
    SeedTools seed = new SeedTools(api, sesion, "DEP-UTN-01");
    servidor
        .expect(requestTo(DONADORES + "/donadores"))
        .andExpect(method(HttpMethod.GET))
        .andRespond(withSuccess("[]", MediaType.APPLICATION_JSON));
    servidor
        .expect(requestTo(DONADORES + "/donadores"))
        .andExpect(method(HttpMethod.POST))
        .andRespond(
            withSuccess(
                """
                {"id":"3"}
                """,
                MediaType.APPLICATION_JSON));
    servidor
        .expect(requestTo(DONADORES + "/entidades"))
        .andExpect(method(HttpMethod.POST))
        .andRespond(
            withSuccess(
                """
                {"id":"5"}
                """,
                MediaType.APPLICATION_JSON));
    // Una necesidad pide un producto, y Donaciones no tiene ninguno.
    servidor
        .expect(ExpectedCount.manyTimes(), requestTo(DONACIONES + "/productos"))
        .andExpect(method(HttpMethod.GET))
        .andRespond(withSuccess("[]", MediaType.APPLICATION_JSON));

    String salida = seed.prepararModulo("donadores", null);

    // Si hubiera intentado crear la necesidad, el servidor habría fallado por pedido inesperado.
    servidor.verify();
    assertTrue(salida.contains("donador nº 3"), "lo que sí se pudo cargar tiene que figurar");
    assertTrue(salida.contains("entidad nº 5"));
    assertTrue(
        salida.contains("la necesidad quedó sin cargar"), "y lo que faltó, dicho sin rodeos");
    assertTrue(salida.contains("preparar_modulo"), "con cómo resolverlo");
  }

  @Test
  @DisplayName("Si el módulo no despertó, preparar ese módulo no escribe nada")
  void prepararModuloDormido() {
    SeedTools seed = new SeedTools(api, sesion, "DEP-UTN-01");
    servidor
        .expect(ExpectedCount.manyTimes(), requestTo(INCENTIVOS + "/insignias"))
        .andExpect(method(HttpMethod.GET))
        .andRespond(withStatus(HttpStatus.SERVICE_UNAVAILABLE));

    String salida = seed.prepararModulo("incentivos", null);

    servidor.verify();
    assertTrue(salida.contains("No se cargó nada"));
    assertTrue(salida.contains("Incentivos"), "hay que decir cuál no contestó");
    assertTrue(salida.contains("despertar_servicios"), "y cómo resolverlo");
  }

  @Test
  @DisplayName("Preparar un módulo sin ser admin no carga nada")
  void prepararModuloSinPermiso() {
    SeedTools seed = new SeedTools(api, sesion, "DEP-UTN-01");
    sesion.cerrarSesion();

    org.junit.jupiter.api.Assertions.assertThrows(
        IllegalStateException.class, () -> seed.prepararModulo("logistica", null));
    servidor.verify();
  }

  @Test
  @DisplayName("Si un módulo no despertó, preparar la demo no carga nada a medias")
  void prepararNoCargaSiFaltaUnModulo() {
    SeedTools seed = new SeedTools(api, sesion, "DEP-UTN-01");
    servidor
        .expect(ExpectedCount.manyTimes(), requestTo(DONACIONES + "/productos"))
        .andExpect(method(HttpMethod.GET))
        .andRespond(withStatus(HttpStatus.SERVICE_UNAVAILABLE));
    servidor
        .expect(ExpectedCount.manyTimes(), requestTo(DONADORES + "/donadores"))
        .andExpect(method(HttpMethod.GET))
        .andRespond(withSuccess("[]", MediaType.APPLICATION_JSON));
    servidor
        .expect(ExpectedCount.manyTimes(), requestTo(LOGISTICA + "/depositos"))
        .andExpect(method(HttpMethod.GET))
        .andRespond(withSuccess("[]", MediaType.APPLICATION_JSON));
    servidor
        .expect(ExpectedCount.manyTimes(), requestTo(INCENTIVOS + "/insignias"))
        .andExpect(method(HttpMethod.GET))
        .andRespond(withSuccess("[]", MediaType.APPLICATION_JSON));

    String salida = seed.prepararDemo(null);

    // Si hubiera intentado escribir, el servidor simulado habría fallado por pedido inesperado.
    servidor.verify();
    assertTrue(salida.contains("No se cargó nada"));
    assertTrue(salida.contains("Donaciones"), "hay que decir cuál falta");
    assertTrue(salida.contains("despertar_servicios"), "y cómo resolverlo");
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

  /** El alta responde con el DTO de cátedra, que no trae lo cubierto: como el módulo real. */
  private void esperarAltaDeNecesidad(String tipo) {
    servidor
        .expect(requestTo(DONADORES + "/necesidades"))
        .andExpect(method(HttpMethod.POST))
        .andRespond(
            withSuccess(
                "{\"id\":\"9\",\"entidadID\":\"1\",\"nivelDeUrgencia\":8,"
                    + "\"descripcion\":\"Arroz para el comedor\",\"cantidadObjetivo\":20,"
                    + "\"productoSolicitadoID\":\"3\",\"tipo\":\""
                    + tipo
                    + "\"}",
                MediaType.APPLICATION_JSON));
  }

  /** La necesidad releída después del alta, con lo cubierto que registró Donadores. */
  private void esperarNecesidadGuardada(int cubierta, String tipo) {
    servidor
        .expect(requestTo(DONADORES + "/necesidades/9"))
        .andExpect(method(HttpMethod.GET))
        .andRespond(
            withSuccess(
                "{\"id\":\"9\",\"descripcion\":\"Arroz para el comedor\",\"cantidadObjetivo\":20,"
                    + "\"cantidadActual\":"
                    + cubierta
                    + ",\"productoSolicitadoID\":\"3\",\"tipo\":\""
                    + tipo
                    + "\"}",
                MediaType.APPLICATION_JSON));
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
