package ar.edu.utn.dds.k3003.mcp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.json.JsonCompareMode;
import org.springframework.test.web.client.ExpectedCount;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestTemplate;

/** Tests de las herramientas del servidor MCP, con los módulos de DonaTrack simulados. */
class ToolsTest {

  private static final String DONACIONES = "http://donaciones";
  private static final String DONADORES = "http://donadores";
  private static final String LOGISTICA = "http://logistica";
  private static final String INCENTIVOS = "http://incentivos";

  private RestTemplate rest;
  private MockRestServiceServer servidor;
  private ConsultaTools consultas;
  private OperacionTools operaciones;
  private SesionMcp sesion;
  private AuthTools auth;
  private SeedTools seed;

  @BeforeEach
  void setUp() {
    rest = new RestTemplate();
    servidor = MockRestServiceServer.createServer(rest);
    DonaTrackApi api = new DonaTrackApi(rest, DONACIONES, DONADORES, LOGISTICA, INCENTIVOS);
    sesion = new SesionMcp();
    sesion.iniciarComoAdmin("admin");
    consultas = new ConsultaTools(api);
    // Sin relato: estos tests miran qué se le manda a cada módulo. Con el relato prendido habría
    // que simular además todas las consultas del antes y el después, y el test dejaría de hablar
    // de lo que quiere probar. El relato se prueba aparte, en RelatoTest.
    operaciones = new OperacionTools(api, "DEP-UTN-01", sesion, false);
    auth = new AuthTools(sesion, api, "admin123");
    // Sin espera entre intentos: con los dos segundos reales, cada test del paquete tardaría 18.
    seed = new SeedTools(api, sesion, "DEP-UTN-01", 0);
  }

  // ── Consultas ──────────────────────────────────────────────────────────────

  @Test
  @DisplayName("Sin id trae todos los donadores; con id trae uno")
  void consultarDonadores() {
    servidor
        .expect(requestTo(DONADORES + "/donadores"))
        .andRespond(withSuccess("[]", MediaType.APPLICATION_JSON));
    consultas.consultarDonadores(null);
    servidor.verify();

    servidor.reset();
    servidor
        .expect(requestTo(DONADORES + "/donadores/7"))
        .andRespond(withSuccess("{\"id\":\"7\"}", MediaType.APPLICATION_JSON));
    consultas.consultarDonadores("7");
    servidor.verify();
  }

  @Test
  @DisplayName("Las necesidades se consultan por id o por producto, nunca en general")
  void consultarNecesidades() {
    servidor
        .expect(requestTo(DONADORES + "/necesidades?productoID=3"))
        .andRespond(withSuccess("[]", MediaType.APPLICATION_JSON));
    consultas.consultarNecesidades(null, "3");
    servidor.verify();

    // Sin ninguno de los dos, la API devolvería 400; conviene avisarlo antes de llamar
    // para que el modelo pueda pedir el dato que falta en vez de mostrar un error.
    assertThrows(
        IllegalArgumentException.class, () -> consultas.consultarNecesidades(null, null));
  }

  @Test
  @DisplayName("Las donaciones de un donador se piden sin fecha: Donaciones trae todo el historial")
  void donacionesDeUnDonador() {
    servidor
        .expect(requestTo(DONACIONES + "/donaciones?donadorID=1"))
        .andRespond(withSuccess("[]", MediaType.APPLICATION_JSON));

    consultas.consultarDonaciones(null, "1");

    servidor.verify();
  }

  @Test
  @DisplayName("Sin id, los identificadores se listan todos; con id se trae ese")
  void identificadores() {
    servidor
        .expect(requestTo(DONACIONES + "/identificadores"))
        .andRespond(withSuccess("[]", MediaType.APPLICATION_JSON));
    servidor
        .expect(requestTo(DONACIONES + "/identificadores/3"))
        .andRespond(withSuccess("{}", MediaType.APPLICATION_JSON));

    consultas.consultarIdentificadores(null);
    consultas.consultarIdentificadores(" 3 ");

    servidor.verify();
  }

  // ── Operaciones ────────────────────────────────────────────────────────────

  @Test
  @DisplayName("Registrar una donación arma el cuerpo que espera el módulo")
  void registrarDonacion() {
    servidor
        .expect(requestTo(DONACIONES + "/donaciones"))
        .andExpect(method(HttpMethod.POST))
        .andExpect(content().json(
            """
            {"donadorID":"1","depositoID":"DEP-UTN-01","descripcion":"Diez kilos de arroz",
             "productoID":"3","cantidad":10}"""))
        .andRespond(withSuccess("{\"id\":\"5\"}", MediaType.APPLICATION_JSON));

    operaciones.registrarDonacion("1", "3", 10, "Diez kilos de arroz", null);

    servidor.verify();
  }

  @Test
  @DisplayName("Si se indica un depósito, se usa ese en vez del habitual")
  void depositoExplicito() {
    servidor
        .expect(requestTo(DONACIONES + "/donaciones"))
        .andExpect(content().string(org.hamcrest.Matchers.containsString("DEP-OTRO")))
        .andRespond(withSuccess("{}", MediaType.APPLICATION_JSON));

    operaciones.registrarDonacion("1", "3", 5, "algo", "DEP-OTRO");

    servidor.verify();
  }

  @Test
  @DisplayName("El tipo de necesidad se normaliza a mayúsculas")
  void tipoEnMayusculas() {
    servidor
        .expect(requestTo(DONADORES + "/necesidades"))
        .andExpect(content().string(org.hamcrest.Matchers.containsString("EXTRAORDINARIA")))
        .andRespond(withSuccess("{}", MediaType.APPLICATION_JSON));

    operaciones.registrarNecesidad("1", "2", 10, "Arroz para el comedor", 8, "extraordinaria");

    servidor.verify();
  }

  @Test
  @DisplayName("La queja va como texto plano, no como objeto")
  void quejaEsTextoPlano() {
    servidor
        .expect(requestTo(DONACIONES + "/donaciones/4/quejas"))
        .andExpect(method(HttpMethod.POST))
        .andRespond(withSuccess("{}", MediaType.APPLICATION_JSON));

    operaciones.registrarQueja("4", "Llego en mal estado");

    servidor.verify();
  }

  // ── Errores ────────────────────────────────────────────────────────────────

  @Test
  @DisplayName("Un 400 del módulo se explica como regla de negocio, sin inventar el motivo")
  void errorDeNegocio() {
    servidor
        .expect(requestTo(DONACIONES + "/donaciones"))
        .andRespond(
            withStatus(HttpStatus.BAD_REQUEST)
                .body("{\"error\":\"La cantidad donada debe ser mayor a 0\"}")
                .contentType(MediaType.APPLICATION_JSON));

    RuntimeException e =
        assertThrows(
            RuntimeException.class,
            () -> operaciones.registrarDonacion("1", "3", 0, "cantidad cero", null));

    assertTrue(e.getMessage().contains("regla de negocio"));
    assertTrue(
        e.getMessage().contains("mayor a 0"),
        "el motivo real del módulo tiene que llegar al usuario, no perderse");
  }

  @Test
  @DisplayName("Un 404 se explica como que no existe")
  void errorNoEncontrado() {
    servidor
        .expect(requestTo(DONADORES + "/donadores/999"))
        .andRespond(withStatus(HttpStatus.NOT_FOUND).body("no existe"));

    RuntimeException e =
        assertThrows(RuntimeException.class, () -> consultas.consultarDonadores("999"));

    assertTrue(e.getMessage().contains("No existe"));
  }

  @Test
  @DisplayName("Un 403 dice que el módulo no permite la operación y conserva su motivo")
  void errorSinPermiso() {
    servidor
        .expect(requestTo(DONACIONES + "/donaciones"))
        .andRespond(
            withStatus(HttpStatus.FORBIDDEN)
                .body(
                    "{\"error\":\"No puede donar: el donador 5 no está habilitado para donar\"}")
                .contentType(MediaType.APPLICATION_JSON));

    RuntimeException e =
        assertThrows(
            RuntimeException.class, () -> operaciones.registrarDonacion("5", "3", 10, "algo", null));

    assertTrue(e.getMessage().contains("no permite"), e.getMessage());
    assertTrue(
        e.getMessage().contains("el donador 5 no está habilitado para donar"),
        "el motivo real del módulo tiene que llegar al usuario");
    assertFalse(e.getMessage().contains("código 403"), "no alcanza con repetir el número");
  }

  @Test
  @DisplayName("Un 502 de nuestro módulo dice que otro módulo no respondió y nombra cuál")
  void error502DeOtroModulo() {
    servidor
        .expect(requestTo(DONACIONES + "/donaciones"))
        .andRespond(
            withStatus(HttpStatus.BAD_GATEWAY)
                // El detalle cita a Logística diciendo «no existe»: aun así no es un 404.
                .body(
                    "{\"error\":\"El módulo Logística respondió con error 500: el depósito no"
                        + " existe\"}")
                .contentType(MediaType.APPLICATION_JSON));

    RuntimeException e =
        assertThrows(
            RuntimeException.class, () -> operaciones.registrarDonacion("1", "3", 10, "algo", null));

    assertTrue(e.getMessage().contains("otro módulo del que depende no respondió"), e.getMessage());
    assertTrue(e.getMessage().contains("Logística"), "el mensaje del módulo dice cuál falló");
    assertFalse(
        e instanceof DonaTrackApi.NoEncontrado,
        "un 502 nunca es «no existe», aunque el detalle lo diga");
    assertFalse(
        e instanceof DonaTrackApi.SinRespuesta,
        "Donaciones sí contestó: no es el módulo el que está dormido");
  }

  @Test
  @DisplayName("Un 502 de Render, sin el cuerpo de nuestros módulos, manda a despertar el servicio")
  void error502DeRender() {
    servidor
        .expect(requestTo(DONADORES + "/donadores/7"))
        .andRespond(
            withStatus(HttpStatus.BAD_GATEWAY)
                .body("<html><body><h1>502 Bad Gateway</h1></body></html>")
                .contentType(MediaType.TEXT_HTML));

    RuntimeException e =
        assertThrows(RuntimeException.class, () -> consultas.consultarDonadores("7"));

    assertTrue(e instanceof DonaTrackApi.SinRespuesta, "es el servicio que no anda, no un dato");
    assertTrue(e.getMessage().contains("despertar_servicios"), e.getMessage());
    assertFalse(e.getMessage().contains("otro módulo"), "no hay ningún otro módulo en juego");
  }

  @Test
  @DisplayName("Un 502 vacío también se toma como Render despertando el servicio")
  void error502Vacio() {
    servidor
        .expect(requestTo(DONACIONES + "/productos"))
        .andRespond(withStatus(HttpStatus.BAD_GATEWAY));

    RuntimeException e =
        assertThrows(RuntimeException.class, () -> consultas.consultarProductos(null));

    assertTrue(e instanceof DonaTrackApi.SinRespuesta);
    assertTrue(e.getMessage().contains("despertar_servicios"));
  }

  @Test
  @DisplayName("Quejarse de una donación sin entregar sube el motivo del módulo como conflicto")
  void quejaSobreDonacionSinEntregar() {
    servidor
        .expect(requestTo(DONACIONES + "/donaciones/4/quejas"))
        .andExpect(method(HttpMethod.POST))
        .andRespond(
            withStatus(HttpStatus.CONFLICT)
                .body(
                    "{\"error\":\"No se puede registrar la queja: la donación 4 todavía no fue"
                        + " entregada (estado actual: INGRESADA). Solo se aceptan quejas sobre"
                        + " donaciones entregadas (ACEPTADA).\"}")
                .contentType(MediaType.APPLICATION_JSON));

    RuntimeException e =
        assertThrows(
            RuntimeException.class, () -> operaciones.registrarQueja("4", "Llegó en mal estado"));

    assertTrue(e.getMessage().contains("conflicto con el estado actual"));
    assertTrue(
        e.getMessage().contains("todavía no fue entregada (estado actual: INGRESADA)"),
        "el MCP no valida el estado: el motivo lo pone Donaciones y tiene que llegar entero");
    assertFalse(e.getMessage().contains("error :"), "el envoltorio JSON no aporta nada al leerlo");
  }

  @Test
  @DisplayName("La descripción de registrar_queja avisa que solo se puede quejar de una donación entregada")
  void descripcionDeLaQueja() throws Exception {
    String descripcion =
        OperacionTools.class
            .getMethod("registrarQueja", String.class, String.class)
            .getAnnotation(org.springframework.ai.tool.annotation.Tool.class)
            .description();

    assertTrue(descripcion.contains("ENTREGADA"), descripcion);
    assertTrue(
        descripcion.contains("reportar_entrega"), "tiene que decir cómo se llega a una entregada");
    assertTrue(
        descripcion.contains("con 5") && descripcion.contains("con 10"),
        "los umbrales de la escalada son los del módulo Donadores");
  }

  @Test
  @DisplayName("El guion de la demo pone la queja después de la entrega y explica cómo llegar a BANEADO")
  void guionConQuejaSobreEntregada() {
    String guion = new DemoTools(null, sesion).guionDemo();

    assertTrue(
        guion.indexOf("`reportar_entrega`") < guion.indexOf("`registrar_queja`"),
        "la queja necesita una donación ya entregada");
    assertTrue(guion.contains("donación entregada"));
    assertTrue(guion.contains("hacen falta 10"), "con una sola donación no se llega a BANEADO");
  }

  @Test
  @DisplayName("Una consulta a un módulo dormido se reintenta sola una vez")
  void consultaAModuloDormidoSeReintenta() {
    java.util.concurrent.atomic.AtomicInteger intentos =
        new java.util.concurrent.atomic.AtomicInteger();
    DonaTrackApi api = new DonaTrackApi(sinSalida(intentos), DONACIONES, DONADORES, LOGISTICA, INCENTIVOS);

    RuntimeException e =
        assertThrows(RuntimeException.class, () -> new ConsultaTools(api).consultarProductos(null));

    assertEquals(2, intentos.get(), "en Render el primer pedido se pierde despertando al servicio");
    assertTrue(e.getMessage().contains("no responde"));
    assertTrue(
        e.getMessage().contains("despertar_servicios"),
        "conviene decir con qué herramienta se resuelve");
  }

  @Test
  @DisplayName("Una operación que escribe no se reintenta: podría duplicarse")
  void operacionNoSeReintenta() {
    java.util.concurrent.atomic.AtomicInteger intentos =
        new java.util.concurrent.atomic.AtomicInteger();
    DonaTrackApi api = new DonaTrackApi(sinSalida(intentos), DONACIONES, DONADORES, LOGISTICA, INCENTIVOS);

    assertThrows(
        RuntimeException.class,
        () -> new OperacionTools(api, "DEP-UTN-01", sesion, false)
            .registrarDonacion("1", "3", 10, "algo", null));

    assertEquals(
        1, intentos.get(), "sin respuesta no se sabe si la donación llegó: repetirla la duplicaría");
  }

  /** Un RestTemplate que nunca llega a destino, contando cuántas veces se intentó. */
  private RestTemplate sinSalida(java.util.concurrent.atomic.AtomicInteger intentos) {
    RestTemplate sinSalida = new RestTemplate();
    sinSalida.setRequestFactory(
        new org.springframework.http.client.SimpleClientHttpRequestFactory() {
          @Override
          public org.springframework.http.client.ClientHttpRequest createRequest(
              java.net.URI uri, HttpMethod httpMethod) throws java.io.IOException {
            intentos.incrementAndGet();
            throw new java.io.IOException("conexión rechazada");
          }
        });
    return sinSalida;
  }

  // ── Cuerpo ─────────────────────────────────────────────────────────────────

  @Test
  @DisplayName("Los campos nulos no se mandan en el cuerpo")
  void cuerpoSinNulos() {
    var m = DonaTrackApi.cuerpo("a", "uno", "b", null, "c", 3);

    assertEquals(2, m.size());
    assertTrue(m.containsKey("a"));
    assertTrue(m.containsKey("c"));
  }

  // ── Autenticación y Control de Sesión ──────────────────────────────────────

  @Test
  @DisplayName("Una operación sin sesión iniciada es rechazada")
  void operacionSinSesionRechazada() {
    sesion.cerrarSesion();
    assertThrows(
        IllegalStateException.class,
        () -> operaciones.registrarDonacion("1", "3", 10, "Diez kilos de arroz", null));
  }

  @Test
  @DisplayName("Iniciar sesión como admin valida credenciales y actualiza sesión")
  void loginAdmin() {
    sesion.cerrarSesion();
    String rErr = auth.iniciarSesion("ADMIN", "admin", "clave_erronea");
    assertTrue(rErr.contains("incorrecta"));
    assertTrue(!sesion.estaAutenticado());

    String rOk = auth.iniciarSesion("ADMIN", "admin", "admin123");
    assertTrue(rOk.contains("ADMINISTRADOR"));
    assertTrue(sesion.esAdmin());
    assertTrue(auth.quienSoy().contains("ADMINISTRADOR"));

    auth.cerrarSesion();
    assertTrue(!sesion.estaAutenticado());
  }

  @Test
  @DisplayName("Reportar una entrega le manda a Logística solo el paquete")
  void reportarEntrega() {
    servidor
        .expect(requestTo(LOGISTICA + "/api/asignaciones/reportar-entrega"))
        .andExpect(method(HttpMethod.POST))
        // Estricto: Logística saca lo demás de la asignación guardada, y un campo de más
        // volvería a hacer que el modelo le pida al usuario datos que no se usan.
        .andExpect(content().json("{\"paqueteid\":\"PAQ-1\"}", JsonCompareMode.STRICT))
        .andRespond(withSuccess("{}", MediaType.APPLICATION_JSON));

    operaciones.reportarEntrega("PAQ-1", null);
    servidor.verify();
  }

  @Test
  @DisplayName("Sin paquete ni donación se avisa antes de llamar a Logística")
  void reportarEntregaSinDatos() {
    assertThrows(IllegalArgumentException.class, () -> operaciones.reportarEntrega(null, " "));
    servidor.verify();
  }

  // ── Seed: entrega del flujo completo ───────────────────────────────────────

  @Test
  @DisplayName("La seed espera a que el worker cree la asignación antes de reportar la entrega")
  void seedEsperaAlWorker() {
    servidor
        .expect(ExpectedCount.times(2), requestTo(LOGISTICA + "/api/asignaciones/paquetes/paq-12"))
        .andExpect(method(HttpMethod.GET))
        .andRespond(withStatus(HttpStatus.NOT_FOUND).body("no existe"));
    servidor
        .expect(requestTo(LOGISTICA + "/api/asignaciones/paquetes/paq-12"))
        .andExpect(method(HttpMethod.GET))
        .andRespond(withSuccess("{\"paqueteid\":\"paq-12\"}", MediaType.APPLICATION_JSON));
    servidor
        .expect(requestTo(LOGISTICA + "/api/asignaciones/reportar-entrega"))
        .andExpect(method(HttpMethod.POST))
        .andExpect(content().json("{\"paqueteid\":\"paq-12\"}", JsonCompareMode.STRICT))
        .andRespond(withSuccess("{}", MediaType.APPLICATION_JSON));
    StringBuilder salida = new StringBuilder();

    seed.reportarEntregaCuandoExista(salida, "12");

    servidor.verify();
    assertTrue(salida.toString().contains("paq-12 reportada"));
  }

  @Test
  @DisplayName("Si el paquete nunca aparece, la seed avisa del worker y no reporta la entrega")
  void seedSinPaqueteNoReporta() {
    servidor
        .expect(ExpectedCount.times(10), requestTo(LOGISTICA + "/api/asignaciones/paquetes/paq-12"))
        .andExpect(method(HttpMethod.GET))
        .andRespond(withStatus(HttpStatus.NOT_FOUND).body("no existe"));
    StringBuilder salida = new StringBuilder();

    seed.reportarEntregaCuandoExista(salida, "12");

    // Si hubiera reportado igual, el servidor simulado habría fallado por pedido inesperado.
    servidor.verify();
    assertTrue(salida.toString().contains("la entrega no se reportó"));
    assertTrue(salida.toString().contains("worker"), "hay que decir dónde mirar");
  }

  @Test
  @DisplayName("Si Logística falla al consultar el paquete, la seed no insiste ni reporta")
  void seedConLogisticaFallandoNoInsiste() {
    servidor
        .expect(requestTo(LOGISTICA + "/api/asignaciones/paquetes/paq-12"))
        .andRespond(withStatus(HttpStatus.INTERNAL_SERVER_ERROR).body("boom"));
    StringBuilder salida = new StringBuilder();

    seed.reportarEntregaCuandoExista(salida, "12");

    // Un error que no es 404 no se arregla esperando: insistir solo consumiría el plazo.
    servidor.verify();
    assertTrue(salida.toString().contains("No se pudo reportar la entrega de paq-12"));
  }

  // ── Logística: depósitos, stock y asignaciones ─────────────────────────────

  @Test
  @DisplayName("Sin parámetros, el stock de los depósitos se pide a /api/depositos y no a /depositos")
  void depositosConStockReal() {
    servidor
        .expect(requestTo(LOGISTICA + "/api/depositos"))
        .andExpect(method(HttpMethod.GET))
        .andRespond(
            withSuccess(
                "[{\"depositoid\":\"DEP-UTN-01\",\"stockActual\":26}]", MediaType.APPLICATION_JSON));

    String respuesta = consultas.consultarLogistica(null, null, null);

    // /depositos trae stockActual siempre vacío: si lo pidiera, el servidor fallaría por pedido
    // inesperado.
    servidor.verify();
    assertTrue(respuesta.contains("26"));
  }

  @Test
  @DisplayName("Con un depósito, trae sus datos y su stock por producto en una sola respuesta")
  void depositoConSuStock() throws Exception {
    // Las dos consultas salen a la vez, así que el orden en que llegan no está garantizado.
    servidor = MockRestServiceServer.bindTo(rest).ignoreExpectOrder(true).build();
    servidor
        .expect(requestTo(LOGISTICA + "/api/depositos/DEP-UTN-01"))
        .andRespond(
            withSuccess(
                "{\"depositoid\":\"DEP-UTN-01\",\"stockActual\":26}", MediaType.APPLICATION_JSON));
    servidor
        .expect(requestTo(LOGISTICA + "/api/depositos/DEP-UTN-01/stock"))
        .andRespond(
            withSuccess(
                "[{\"productoid\":\"3\",\"cantidad\":20},{\"productoid\":\"2\",\"cantidad\":6}]",
                MediaType.APPLICATION_JSON));

    var respuesta =
        new ObjectMapper().readTree(consultas.consultarLogistica(" DEP-UTN-01 ", null, null));

    servidor.verify();
    assertEquals(26, respuesta.path("deposito").path("stockActual").asInt());
    assertEquals(20, respuesta.path("stock").get(0).path("cantidad").asInt());
  }

  @Test
  @DisplayName("Con un producto trae su detalle por depósito; con todoElStock, el de todos")
  void stockPorProductoYCompleto() {
    servidor
        .expect(requestTo(LOGISTICA + "/stock/3/detalle"))
        .andRespond(withSuccess("{\"totalDisponible\":20}", MediaType.APPLICATION_JSON));
    servidor
        .expect(requestTo(LOGISTICA + "/stock"))
        .andRespond(withSuccess("[]", MediaType.APPLICATION_JSON));

    consultas.consultarLogistica(null, "3", null);
    consultas.consultarLogistica(null, null, true);

    servidor.verify();
  }

  @Test
  @DisplayName("Crear un depósito manda solo lo que se indicó, sin stock")
  void crearDepositoMinimo() {
    servidor
        .expect(requestTo(LOGISTICA + "/api/depositos"))
        .andExpect(method(HttpMethod.POST))
        // Sin id ni algoritmo: el id lo genera Logística y el algoritmo queda sin configurar.
        .andExpect(
            content()
                .json(
                    """
                    {"nombre":"Deposito Norte","direccion":"Av. Cabildo 100","capacidadMaxima":3000}
                    """,
                    JsonCompareMode.STRICT))
        .andRespond(
            withStatus(HttpStatus.CREATED)
                .body("{\"depositoid\":\"DEP-UTN-02\"}")
                .contentType(MediaType.APPLICATION_JSON));

    String respuesta =
        operaciones.crearDeposito("Deposito Norte", "Av. Cabildo 100", 3000, null, null);

    servidor.verify();
    assertTrue(respuesta.contains("DEP-UTN-02"), "el id generado tiene que llegar al usuario");
  }

  @Test
  @DisplayName("El algoritmo escrito de otra forma se traduce al nombre que esperan los /api")
  void crearDepositoConAlgoritmo() {
    servidor
        .expect(requestTo(LOGISTICA + "/api/depositos"))
        .andExpect(method(HttpMethod.POST))
        .andExpect(
            content()
                .json(
                    """
                    {"nombre":"Deposito Norte","depositoid":"DEP-UTN-02","direccion":"Av. Cabildo 100",
                     "capacidadMaxima":3000,"algoritmo":"PRIOSCORE"}
                    """,
                    JsonCompareMode.STRICT))
        .andRespond(withSuccess("{}", MediaType.APPLICATION_JSON));

    operaciones.crearDeposito(
        "Deposito Norte", "Av. Cabildo 100", 3000, "DEP-UTN-02", "prioridad por score");

    servidor.verify();
  }

  @Test
  @DisplayName("Modificar un depósito completa lo que no cambia con los valores actuales")
  void modificarDepositoCompleta() {
    servidor
        .expect(requestTo(LOGISTICA + "/api/depositos/DEP-UTN-01"))
        .andExpect(method(HttpMethod.GET))
        .andRespond(
            withSuccess(
                """
                {"nombre":"Deposito Central UTN","depositoid":"DEP-UTN-01",
                 "direccion":"Av. Medrano 951","capacidadMaxima":5000,"stockActual":26,
                 "algoritmo":"SUBATENDIDOS"}""",
                MediaType.APPLICATION_JSON));
    servidor
        .expect(requestTo(LOGISTICA + "/api/depositos/DEP-UTN-01"))
        .andExpect(method(HttpMethod.PUT))
        // El stock no viaja: no se modifica por acá.
        .andExpect(
            content()
                .json(
                    """
                    {"nombre":"Deposito Central UTN","direccion":"Av. Medrano 951",
                     "capacidadMaxima":8000,"algoritmo":"SUBATENDIDOS"}
                    """,
                    JsonCompareMode.STRICT))
        .andRespond(withSuccess("{}", MediaType.APPLICATION_JSON));

    operaciones.modificarDeposito("DEP-UTN-01", null, null, 8000, null);

    servidor.verify();
  }

  @Test
  @DisplayName("Si se indica todo, modificar un depósito no lo lee antes")
  void modificarDepositoSinLeer() {
    servidor
        .expect(requestTo(LOGISTICA + "/api/depositos/DEP-UTN-01"))
        .andExpect(method(HttpMethod.PUT))
        .andExpect(
            content()
                .json(
                    """
                    {"nombre":"Central","direccion":"Medrano 951","capacidadMaxima":8000,
                     "algoritmo":"SUBATENDIDOS"}
                    """,
                    JsonCompareMode.STRICT))
        .andRespond(withSuccess("{}", MediaType.APPLICATION_JSON));

    operaciones.modificarDeposito("DEP-UTN-01", "Central", "Medrano 951", 8000, "SUB_ATENDIDOS");

    servidor.verify();
  }

  @Test
  @DisplayName("Eliminar un depósito llama al DELETE de /api/depositos")
  void eliminarDeposito() {
    servidor
        .expect(requestTo(LOGISTICA + "/api/depositos/DEP-UTN-01"))
        .andExpect(method(HttpMethod.DELETE))
        .andRespond(withStatus(HttpStatus.NO_CONTENT));

    String respuesta = operaciones.eliminarDeposito("DEP-UTN-01");

    servidor.verify();
    assertTrue(respuesta.contains("DEP-UTN-01 eliminado"));
  }

  @Test
  @DisplayName("El algoritmo de un depósito viaja como parámetro de la URL, no en el cuerpo")
  void configurarAlgoritmo() {
    servidor
        .expect(requestTo(LOGISTICA + "/api/depositos/DEP-UTN-01/algoritmo?algoritmo=SUBATENDIDOS"))
        .andExpect(method(HttpMethod.PUT))
        .andExpect(content().string(""))
        .andRespond(withSuccess("{}", MediaType.APPLICATION_JSON));

    operaciones.configurarAlgoritmoDeposito("DEP-UTN-01", "sub atendidos");

    servidor.verify();
  }

  @Test
  @DisplayName("Registrar una queja exige sesión de admin: un donador no reclama contra otros")
  void quejaSinAdmin() {
    sesion.iniciarComoDonador("1", "Carlos");

    assertThrows(
        IllegalStateException.class, () -> operaciones.registrarQueja("4", "No llegó nada"));

    // No llegó a Donaciones.
    servidor.verify();
  }

  @Test
  @DisplayName("Las operaciones sobre depósitos no se hacen sin sesión de admin")
  void depositosSinAdmin() {
    sesion.iniciarComoDonador("1", "Carlos");

    assertThrows(
        IllegalStateException.class,
        () -> operaciones.crearDeposito("Norte", "Cabildo 100", 3000, null, null));
    assertThrows(
        IllegalStateException.class,
        () -> operaciones.modificarDeposito("DEP-UTN-01", null, null, 8000, null));
    assertThrows(IllegalStateException.class, () -> operaciones.eliminarDeposito("DEP-UTN-01"));
    assertThrows(
        IllegalStateException.class,
        () -> operaciones.configurarAlgoritmoDeposito("DEP-UTN-01", "PRIOSCORE"));

    // Ninguna llegó a Logística.
    servidor.verify();
  }

  @Test
  @DisplayName("Las asignaciones se consultan por paquete, por donación, por filtros o todas")
  void consultarAsignaciones() {
    // Las consultas no piden sesión.
    sesion.cerrarSesion();
    servidor
        .expect(requestTo(LOGISTICA + "/api/asignaciones/paquetes/paq-solicitud-abc"))
        .andRespond(withSuccess("{}", MediaType.APPLICATION_JSON));
    servidor
        .expect(requestTo(LOGISTICA + "/api/asignaciones/donaciones/12"))
        .andRespond(withSuccess("[]", MediaType.APPLICATION_JSON));
    servidor
        .expect(requestTo(LOGISTICA + "/api/asignaciones?estado=ASIGNADA&necesidadid=7"))
        .andRespond(withSuccess("[]", MediaType.APPLICATION_JSON));
    servidor
        .expect(requestTo(LOGISTICA + "/api/asignaciones"))
        .andRespond(withSuccess("[]", MediaType.APPLICATION_JSON));

    consultas.consultarAsignaciones("paq-solicitud-abc", null, null, null);
    consultas.consultarAsignaciones(null, "12", null, null);
    consultas.consultarAsignaciones(null, null, "asignada", "7");
    consultas.consultarAsignaciones(null, null, null, null);

    servidor.verify();
  }

  // ── Contraseña de admin ────────────────────────────────────────────────────

  private DonaTrackApi apiDirecta() {
    return new DonaTrackApi(rest, DONACIONES, DONADORES, LOGISTICA, INCENTIVOS);
  }

  @Test
  @DisplayName("Sin contraseña configurada, el ingreso como admin está deshabilitado y dice cómo habilitarlo")
  void adminDeshabilitadoSinContrasena() {
    sesion.cerrarSesion();
    AuthTools sinClave = new AuthTools(sesion, apiDirecta(), "");

    // Aunque alguien pruebe con la clave que tenía antes por defecto, no entra.
    String respuesta = sinClave.iniciarSesion("ADMIN", "admin", "admin123");

    assertTrue(respuesta.contains("deshabilitado"));
    assertTrue(respuesta.contains("DONATRACK_ADMIN_PASSWORD"), "dice qué variable lo habilita");
    assertTrue(respuesta.contains("\"env\""), "y en qué parte de la configuración va");
    assertFalse(sesion.estaAutenticado(), "sin clave configurada no se entra con ninguna");
  }

  @Test
  @DisplayName("Una contraseña configurada en blanco cuenta como no configurada")
  void contrasenaEnBlancoEsNoConfigurada() {
    sesion.cerrarSesion();
    AuthTools enBlanco = new AuthTools(sesion, apiDirecta(), "   ");

    assertTrue(enBlanco.iniciarSesion("ADMIN", "admin", "   ").contains("deshabilitado"));
    assertFalse(sesion.estaAutenticado(), "ni siquiera con la clave en blanco");
  }

  @Test
  @DisplayName("Sin contraseña configurada se puede ingresar como donador, y quien_soy lo avisa")
  void sinClaveElDonadorEntraIgual() {
    sesion.cerrarSesion();
    AuthTools sinClave = new AuthTools(sesion, apiDirecta(), "");
    servidor
        .expect(requestTo(DONADORES + "/donadores/3"))
        .andRespond(
            withSuccess("{\"nombre\":\"Carlos\",\"apellido\":\"Seed\"}", MediaType.APPLICATION_JSON));

    assertTrue(sinClave.iniciarSesion("DONADOR", "3", null).contains("DONADOR"));
    sesion.cerrarSesion();
    assertTrue(sinClave.quienSoy().contains("deshabilitado"));
  }

  @Test
  @DisplayName("Con contraseña configurada, la correcta entra y una equivocada no, sin repetir ninguna")
  void contrasenaCorrectaEIncorrecta() {
    sesion.cerrarSesion();
    AuthTools conClave = new AuthTools(sesion, apiDirecta(), "clave-del-servidor");

    String mal = conClave.iniciarSesion("ADMIN", "admin", "clave-tipeada-mal");

    assertTrue(mal.contains("incorrecta"));
    assertFalse(mal.contains("clave-tipeada-mal"), "no repite lo que se tipeó");
    assertFalse(mal.contains("clave-del-servidor"), "ni deja ver la verdadera");
    assertFalse(sesion.estaAutenticado());

    String bien = conClave.iniciarSesion("ADMIN", "admin", "clave-del-servidor");

    assertTrue(bien.contains("ADMINISTRADOR"));
    assertFalse(bien.contains("clave-del-servidor"));
    assertTrue(sesion.esAdmin());
  }

  @Test
  @DisplayName("La contraseña no queda escrita en ningún log, ni al entrar ni al fallar")
  void contrasenaNoVaALosLogs() {
    sesion.cerrarSesion();
    ch.qos.logback.classic.Logger raiz =
        (ch.qos.logback.classic.Logger)
            org.slf4j.LoggerFactory.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME);
    ch.qos.logback.core.read.ListAppender<ch.qos.logback.classic.spi.ILoggingEvent> eventos =
        new ch.qos.logback.core.read.ListAppender<>();
    eventos.start();
    raiz.addAppender(eventos);
    try {
      AuthTools conClave = new AuthTools(sesion, apiDirecta(), "clave-secreta-123");
      conClave.iniciarSesion("ADMIN", "admin", "clave-secreta-123");
      sesion.cerrarSesion();
      conClave.iniciarSesion("ADMIN", "admin", "otra-clave-456");
      conClave.quienSoy();
    } finally {
      raiz.detachAppender(eventos);
    }

    String todo =
        eventos.list.stream().map(e -> e.getFormattedMessage()).reduce("", String::concat);
    assertFalse(todo.contains("clave-secreta-123"), "la configurada");
    assertFalse(todo.contains("otra-clave-456"), "la que se tipeó mal");
  }

  @Test
  @DisplayName("Ni la herramienta ni la configuración traen una contraseña por defecto")
  void sinContrasenaPorDefecto() throws Exception {
    java.lang.reflect.Method iniciar =
        AuthTools.class.getMethod("iniciarSesion", String.class, String.class, String.class);
    org.springframework.ai.tool.annotation.Tool tool =
        iniciar.getAnnotation(org.springframework.ai.tool.annotation.Tool.class);
    String descripcionDelParametro =
        iniciar.getParameters()[2]
            .getAnnotation(org.springframework.ai.tool.annotation.ToolParam.class)
            .description();

    assertFalse(tool.description().contains("admin123"), "la descripción de la tool");
    assertFalse(descripcionDelParametro.contains("admin123"), "la del parámetro");

    String propiedades;
    try (var in = getClass().getResourceAsStream("/application.properties")) {
      propiedades = new String(in.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
    }
    assertFalse(propiedades.contains("admin123"), "el archivo se sube al repositorio");
    assertTrue(propiedades.contains("donatrack.admin-password=${DONATRACK_ADMIN_PASSWORD:}"));
  }

  @Test
  @DisplayName("quien_soy dice por dónde van los pedidos: gateway o directo")
  void quienSoyDiceLaConexion() {
    String directo = auth.quienSoy();
    DonaTrackApi conGateway =
        new DonaTrackApi(
            rest,
            DONACIONES,
            DONADORES,
            LOGISTICA,
            INCENTIVOS,
            "https://gw.onrender.com",
            "/donaciones",
            "/donadores",
            "/logistica",
            "/incentivos",
            "/consultas");
    String porGateway = new AuthTools(sesion, conGateway, "admin123").quienSoy();

    assertTrue(directo.contains("sin gateway"));
    assertTrue(porGateway.contains("gateway https://gw.onrender.com"));
    assertTrue(porGateway.contains("ADMINISTRADOR"), "sigue diciendo la sesión");
  }

  // ── Modo admin local ───────────────────────────────────────────────────────

  @Test
  @DisplayName("En modo admin local, el ADMIN entra sin contraseña")
  void adminLocalEntraSinContrasena() {
    sesion.cerrarSesion();
    // Sin contraseña configurada a propósito: en modo admin local no hace falta ninguna.
    AuthTools local = new AuthTools(sesion, apiDirecta(), "", "true");

    String respuesta = local.iniciarSesion("ADMIN", null, null);

    assertTrue(respuesta.contains("ADMINISTRADOR"));
    assertTrue(respuesta.contains("modo admin local"), "dice por qué entró sin contraseña");
    assertTrue(sesion.esAdmin());
    assertEquals("admin", sesion.getUsuario());
  }

  @Test
  @DisplayName("En modo admin local, la contraseña que mande el modelo se ignora: no se compara, no se repite ni se loguea")
  void adminLocalIgnoraLaContrasena() {
    sesion.cerrarSesion();
    ch.qos.logback.classic.Logger raiz =
        (ch.qos.logback.classic.Logger)
            org.slf4j.LoggerFactory.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME);
    ch.qos.logback.core.read.ListAppender<ch.qos.logback.classic.spi.ILoggingEvent> eventos =
        new ch.qos.logback.core.read.ListAppender<>();
    eventos.start();
    raiz.addAppender(eventos);
    String respuesta;
    String quienSoy;
    try {
      // Con una contraseña configurada distinta: si se comparara, no entraría.
      AuthTools local = new AuthTools(sesion, apiDirecta(), "clave-del-servidor", "true");
      respuesta = local.iniciarSesion("ADMIN", "admin", "cualquier-cosa-789");
      quienSoy = local.quienSoy();
    } finally {
      raiz.detachAppender(eventos);
    }

    assertTrue(respuesta.contains("ADMINISTRADOR"));
    assertTrue(sesion.esAdmin(), "entra aunque la contraseña no coincida: no se mira");
    assertFalse(respuesta.contains("cualquier-cosa-789"), "no repite la que mandó el modelo");
    assertFalse(respuesta.contains("clave-del-servidor"), "ni la configurada");
    assertFalse(quienSoy.contains("cualquier-cosa-789"));
    assertFalse(quienSoy.contains("clave-del-servidor"));
    String todo =
        eventos.list.stream().map(e -> e.getFormattedMessage()).reduce("", String::concat);
    assertFalse(todo.contains("cualquier-cosa-789"), "ni queda en el log");
  }

  @Test
  @DisplayName("Sin modo admin local, llamar sin contraseña dice que no va en el chat y cómo activar el modo admin local")
  void sinAdminLocalYSinContrasenaExplicaElModoLocal() {
    sesion.cerrarSesion();
    AuthTools conClave = new AuthTools(sesion, apiDirecta(), "clave-del-servidor");

    for (String vacia : new String[] {null, "", "   "}) {
      String respuesta = conClave.iniciarSesion("ADMIN", "admin", vacia);

      assertTrue(respuesta.contains("no se escribe en el chat"), "le dice al modelo que no la pida");
      assertTrue(respuesta.contains("DONATRACK_ADMIN_LOCAL=true"), "dice qué variable lo destraba");
      assertTrue(respuesta.contains("\"env\""), "y en qué parte de la configuración va");
      assertFalse(respuesta.contains("clave-del-servidor"));
      assertFalse(sesion.estaAutenticado());
    }
  }

  @Test
  @DisplayName("Sin modo admin local ni contraseña, el aviso de deshabilitado ofrece el modo admin local")
  void deshabilitadoOfreceElModoLocal() {
    sesion.cerrarSesion();
    AuthTools sinNada = new AuthTools(sesion, apiDirecta(), "");

    String respuesta = sinNada.iniciarSesion("ADMIN", "admin", null);

    assertTrue(respuesta.contains("deshabilitado"));
    assertTrue(respuesta.contains("DONATRACK_ADMIN_LOCAL=true"));
    assertFalse(sesion.estaAutenticado());
  }

  @Test
  @DisplayName("quien_soy dice si el modo admin local está activo")
  void quienSoyDiceElModoAdminLocal() {
    sesion.cerrarSesion();
    String conLocal = new AuthTools(sesion, apiDirecta(), "", "true").quienSoy();
    String sinLocal = new AuthTools(sesion, apiDirecta(), "clave-del-servidor").quienSoy();

    assertTrue(conLocal.contains("Modo admin local: activo"));
    assertFalse(
        conLocal.contains("deshabilitado"), "con el modo local, el ingreso de admin no lo está");
    assertTrue(sinLocal.contains("Modo admin local: inactivo"));
    assertTrue(sinLocal.contains("DONATRACK_ADMIN_LOCAL=true"), "y dice cómo activarlo");
    assertTrue(sinLocal.contains("Conexión:"), "sigue diciendo por dónde van los pedidos");
  }

  @Test
  @DisplayName("Solo «true» activa el modo admin local; un valor mal escrito lo deja apagado")
  void soloTrueActivaElModoLocal() {
    for (String apagado : new String[] {null, "", "false", "si", "1"}) {
      sesion.cerrarSesion();
      new AuthTools(sesion, apiDirecta(), "", apagado).iniciarSesion("ADMIN", "admin", null);
      assertFalse(sesion.estaAutenticado(), "con «" + apagado + "» no entra");
    }

    sesion.cerrarSesion();
    new AuthTools(sesion, apiDirecta(), "", " TRUE ").iniciarSesion("ADMIN", "admin", null);
    assertTrue(sesion.esAdmin(), "sin importar mayúsculas ni espacios");
  }

  @Test
  @DisplayName("El modo admin local viene apagado, y la tool le pide al modelo no pedir la contraseña")
  void modoLocalApagadoPorDefectoYDescripcion() throws Exception {
    String propiedades;
    try (var in = getClass().getResourceAsStream("/application.properties")) {
      propiedades = new String(in.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
    }
    assertTrue(propiedades.contains("donatrack.admin-local=${DONATRACK_ADMIN_LOCAL:false}"));

    java.lang.reflect.Method iniciar =
        AuthTools.class.getMethod("iniciarSesion", String.class, String.class, String.class);
    String descripcion =
        iniciar.getAnnotation(org.springframework.ai.tool.annotation.Tool.class).description();
    String descripcionDelParametro =
        iniciar.getParameters()[2]
            .getAnnotation(org.springframework.ai.tool.annotation.ToolParam.class)
            .description();

    assertTrue(descripcion.contains("SIN contraseña"));
    assertTrue(descripcion.contains("NUNCA pedirle la contraseña al usuario"));
    assertTrue(descripcionDelParametro.contains("Nunca pedirle la contraseña al usuario"));
    assertFalse(descripcionDelParametro.contains("pedírsela"), "ya no manda a pedirla");
  }

  // ── Estado de donaciones e Incentivos ──────────────────────────────────────

  @Test
  @DisplayName("Cambiar el estado de una donación manda el estado solo, como JSON")
  void cambiarEstadoDonacion() {
    servidor
        .expect(requestTo(DONACIONES + "/donaciones/12/estado"))
        .andExpect(method(HttpMethod.PATCH))
        // Donaciones espera "ACEPTADA" con comillas y como JSON: sin las comillas lo rechaza.
        .andExpect(header("Content-Type", org.hamcrest.Matchers.containsString("application/json")))
        .andExpect(content().string("\"ACEPTADA\""))
        .andRespond(
            withSuccess("{\"id\":\"12\",\"estado\":\"ACEPTADA\"}", MediaType.APPLICATION_JSON));

    String respuesta = operaciones.cambiarEstadoDonacion(" 12 ", " aceptada ");

    servidor.verify();
    assertTrue(respuesta.contains("ACEPTADA"));
  }

  @Test
  @DisplayName("El estado inválido lo rechaza Donaciones y el motivo llega al usuario")
  void estadoInvalidoLoRechazaElModulo() {
    servidor
        .expect(requestTo(DONACIONES + "/donaciones/12/estado"))
        .andRespond(
            withStatus(HttpStatus.BAD_REQUEST)
                .contentType(MediaType.APPLICATION_JSON)
                .body("{\"error\":\"Estado inválido\"}"));

    RuntimeException e =
        assertThrows(
            RuntimeException.class, () -> operaciones.cambiarEstadoDonacion("12", "VOLANDO"));

    assertTrue(e.getMessage().contains("Estado inválido"));
  }

  @Test
  @DisplayName("Crear una insignia manda id, nombre y descripción")
  void crearInsignia() {
    servidor
        .expect(requestTo(INCENTIVOS + "/insignias"))
        .andExpect(method(HttpMethod.POST))
        .andExpect(
            content()
                .json(
                    "{\"id\":\"ins-solidario\",\"nombre\":\"Solidario\","
                        + "\"descripcion\":\"20 donaciones aceptadas\"}",
                    JsonCompareMode.STRICT))
        .andRespond(withSuccess("{}", MediaType.APPLICATION_JSON));

    operaciones.crearInsignia(" ins-solidario ", "Solidario", "20 donaciones aceptadas");

    servidor.verify();
  }

  @Test
  @DisplayName("Crear una misión manda los seis datos con las categorías y el tipo en mayúsculas")
  void crearMision() {
    servidor
        .expect(requestTo(INCENTIVOS + "/misiones"))
        .andExpect(method(HttpMethod.POST))
        .andExpect(
            content()
                .json(
                    """
                    {"id":"mis-1","nombre":"Mision Solidaria","insigniaID":"ins-solidario",
                     "categoriaInicio":"OCASIONAL","categoriaFin":"COLABORADOR","tipo":"COMPLETITUD"}
                    """,
                    JsonCompareMode.STRICT))
        .andRespond(withSuccess("{}", MediaType.APPLICATION_JSON));

    operaciones.crearMision(
        "mis-1", "Mision Solidaria", "ins-solidario", "ocasional", "colaborador", "completitud");

    servidor.verify();
  }

  @Test
  @DisplayName("Asignar una misión la busca en Incentivos y manda la misión entera, sin campos de más")
  void asignarMision() {
    servidor
        .expect(requestTo(INCENTIVOS + "/misiones"))
        .andExpect(method(HttpMethod.GET))
        .andRespond(
            withSuccess(
                """
                [{"id":"mis-0","nombre":"Otra","insigniaID":"ins-0","categoriaInicio":"OCASIONAL",
                  "categoriaFin":"COLABORADOR","tipo":"COMPLETITUD"},
                 {"id":"mis-1","nombre":"Mision Solidaria","insigniaID":"ins-1",
                  "categoriaInicio":"OCASIONAL","categoriaFin":"COLABORADOR","tipo":"COMPLETITUD",
                  "campoQueElPostNoConoce":"x"}]""",
                MediaType.APPLICATION_JSON));
    servidor
        .expect(requestTo(INCENTIVOS + "/donadores/3/mision-actual"))
        .andExpect(method(HttpMethod.POST))
        .andExpect(
            content()
                .json(
                    """
                    {"id":"mis-1","nombre":"Mision Solidaria","insigniaID":"ins-1",
                     "categoriaInicio":"OCASIONAL","categoriaFin":"COLABORADOR","tipo":"COMPLETITUD"}
                    """,
                    JsonCompareMode.STRICT))
        .andRespond(withSuccess("{}", MediaType.APPLICATION_JSON));

    operaciones.asignarMisionADonador("3", " mis-1 ");

    servidor.verify();
  }

  @Test
  @DisplayName("Si la misión no existe en Incentivos, se dice cuáles hay y no se manda nada")
  void asignarMisionInexistente() {
    servidor
        .expect(requestTo(INCENTIVOS + "/misiones"))
        .andRespond(
            withSuccess("[{\"id\":\"mis-0\"},{\"id\":\"mis-9\"}]", MediaType.APPLICATION_JSON));

    IllegalArgumentException e =
        assertThrows(
            IllegalArgumentException.class, () -> operaciones.asignarMisionADonador("3", "mis-1"));

    // Si hubiera intentado asignarla igual, el servidor simulado habría fallado por pedido inesperado.
    servidor.verify();
    assertTrue(e.getMessage().contains("mis-0, mis-9"), "lista las que sí hay");
    assertTrue(e.getMessage().contains("crear_mision"), "y cómo se crea una");
  }

  @Test
  @DisplayName("Las altas y cambios nuevos de Donaciones e Incentivos exigen sesión de admin")
  void nuevasOperacionesExigenAdmin() {
    sesion.iniciarComoDonador("1", "Carlos");

    assertThrows(
        IllegalStateException.class, () -> operaciones.cambiarEstadoDonacion("12", "ACEPTADA"));
    assertThrows(IllegalStateException.class, () -> operaciones.crearInsignia("i", "n", "d"));
    assertThrows(
        IllegalStateException.class,
        () -> operaciones.crearMision("m", "n", "i", "OCASIONAL", "COLABORADOR", "COMPLETITUD"));
    assertThrows(
        IllegalStateException.class, () -> operaciones.asignarMisionADonador("3", "mis-1"));

    // Ninguna llegó a ningún módulo.
    servidor.verify();
  }

  @Test
  @DisplayName("La misión de un donador se pide a Incentivos junto con sus insignias")
  void misionDeDonador() throws Exception {
    // Las dos consultas salen a la vez, así que el orden en que llegan no está garantizado.
    servidor = MockRestServiceServer.bindTo(rest).ignoreExpectOrder(true).build();
    servidor
        .expect(requestTo(INCENTIVOS + "/donadores/3/mision-actual"))
        .andRespond(
            withSuccess(
                "{\"id\":\"mis-1\",\"nombre\":\"Mision Solidaria\"}", MediaType.APPLICATION_JSON));
    servidor
        .expect(requestTo(INCENTIVOS + "/donadores/3/insignias"))
        .andRespond(withSuccess("[{\"id\":\"ins-1\"}]", MediaType.APPLICATION_JSON));

    var respuesta = new ObjectMapper().readTree(consultas.consultarMisionDeDonador(" 3 "));

    servidor.verify();
    assertEquals("mis-1", respuesta.path("mision").path("id").asText());
    assertEquals("ins-1", respuesta.path("insignias").get(0).path("id").asText());
  }

  @Test
  @DisplayName("Un donador sin misión en curso no es un error: se dice que no tiene")
  void donadorSinMision() throws Exception {
    servidor = MockRestServiceServer.bindTo(rest).ignoreExpectOrder(true).build();
    servidor
        .expect(requestTo(INCENTIVOS + "/donadores/3/mision-actual"))
        .andRespond(withStatus(HttpStatus.NOT_FOUND).body("sin misión"));
    servidor
        .expect(requestTo(INCENTIVOS + "/donadores/3/insignias"))
        .andRespond(withSuccess("[]", MediaType.APPLICATION_JSON));

    var respuesta = new ObjectMapper().readTree(consultas.consultarMisionDeDonador("3"));

    assertTrue(respuesta.path("mision").asText().contains("no tiene una misión en curso"));
    assertEquals(0, respuesta.path("insignias").size());
  }

  @Test
  @DisplayName("Si Incentivos falla de verdad al pedir la misión, el error sube y no se disfraza de «sin misión»")
  void errorRealDeIncentivos() {
    servidor = MockRestServiceServer.bindTo(rest).ignoreExpectOrder(true).build();
    servidor
        .expect(requestTo(INCENTIVOS + "/donadores/3/mision-actual"))
        .andRespond(withStatus(HttpStatus.INTERNAL_SERVER_ERROR).body("boom"));
    servidor
        .expect(requestTo(INCENTIVOS + "/donadores/3/insignias"))
        .andRespond(withSuccess("[]", MediaType.APPLICATION_JSON));

    RuntimeException e =
        assertThrows(RuntimeException.class, () -> consultas.consultarMisionDeDonador("3"));

    assertTrue(e.getMessage().contains("error interno"));
  }

  // ── Producto con subcategoría ──────────────────────────────────────────────

  @Test
  @DisplayName("Crear un producto manda la subcategoría si se indica")
  void crearProductoConSubcategoria() {
    servidor
        .expect(requestTo(DONACIONES + "/productos"))
        .andExpect(method(HttpMethod.POST))
        .andExpect(
            content()
                .json(
                    """
                    {"nombre":"Fideos","descripcion":"Fideos secos tipo tirabuzon","categoriaID":"Alimentos",
                     "subcategoria":"Secos","identificadorID":"1"}
                    """,
                    JsonCompareMode.STRICT))
        .andRespond(withSuccess("{\"id\":\"3\"}", MediaType.APPLICATION_JSON));

    operaciones.crearProducto("Fideos", "Fideos secos tipo tirabuzon", "Alimentos", " 1 ", "Secos");

    servidor.verify();
  }

  @Test
  @DisplayName("Sin subcategoría el cuerpo no la lleva: un POST como los de siempre")
  void crearProductoSinSubcategoria() {
    servidor
        .expect(requestTo(DONACIONES + "/productos"))
        .andExpect(method(HttpMethod.POST))
        // Estricto: un campo vacío de más cambiaría lo que ya hacían los clientes actuales.
        .andExpect(
            content()
                .json(
                    """
                    {"nombre":"Fideos","descripcion":"Fideos secos tipo tirabuzon","categoriaID":"Alimentos",
                     "identificadorID":"1"}
                    """,
                    JsonCompareMode.STRICT))
        .andRespond(withSuccess("{\"id\":\"3\"}", MediaType.APPLICATION_JSON));

    operaciones.crearProducto("Fideos", "Fideos secos tipo tirabuzon", "Alimentos", "1", "  ");

    servidor.verify();
  }
}
