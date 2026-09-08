package ar.edu.utn.dds.k3003.mcp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
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
    operaciones = new OperacionTools(api, "DEP-UTN-01", sesion);
    auth = new AuthTools(sesion, api, "admin123");
    seed = new SeedTools(api, sesion, "DEP-UTN-01");
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
  @DisplayName("Las donaciones de un donador piden el histórico completo")
  void donacionesDeUnDonador() {
    servidor
        .expect(requestTo(DONACIONES + "/donaciones?donadorID=1&fecha=2020-01-01"))
        .andRespond(withSuccess("[]", MediaType.APPLICATION_JSON));

    consultas.consultarDonaciones(null, "1");

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
  @DisplayName("Si el módulo no responde, se explica que puede estar dormido")
  void moduloCaido() {
    RestTemplate sinSalida = new RestTemplate();
    sinSalida.setRequestFactory(
        new org.springframework.http.client.SimpleClientHttpRequestFactory() {
          @Override
          public org.springframework.http.client.ClientHttpRequest createRequest(
              java.net.URI uri, HttpMethod httpMethod) throws java.io.IOException {
            throw new java.io.IOException("conexión rechazada");
          }
        });
    DonaTrackApi api =
        new DonaTrackApi(sinSalida, DONACIONES, DONADORES, LOGISTICA, INCENTIVOS);

    RuntimeException e =
        assertThrows(RuntimeException.class, () -> new ConsultaTools(api).consultarProductos(null));

    assertTrue(e.getMessage().contains("no responde"));
    assertTrue(
        e.getMessage().contains("reintentar"),
        "conviene sugerir el reintento: en Render es lo que suele resolverlo");
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
  @DisplayName("Reportar entrega en Logística arma el cuerpo esperado")
  void reportarEntrega() {
    servidor
        .expect(requestTo(LOGISTICA + "/api/asignaciones/reportar-entrega"))
        .andExpect(method(HttpMethod.POST))
        .andExpect(
            content()
                .json(
                    """
                    {"paqueteid":"PAQ-1","donacionID":"5","productoid":"3","cantidad":10}
                    """))
        .andRespond(withSuccess("{}", MediaType.APPLICATION_JSON));

    operaciones.reportarEntrega("PAQ-1", "5", "3", 10);
    servidor.verify();
  }
}
