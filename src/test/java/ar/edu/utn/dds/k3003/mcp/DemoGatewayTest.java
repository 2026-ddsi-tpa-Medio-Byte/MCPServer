package ar.edu.utn.dds.k3003.mcp;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
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
 * Cómo despiertan y se resumen el gateway y los módulos detrás de él.
 *
 * <p>Con el gateway delante hay dos arranques en frío encadenados, y a un módulo caído no se lo
 * distingue de uno dormido si no se mira la puerta primero. Estos tests fijan ese orden.
 */
class DemoGatewayTest {

  private static final String GW = "https://gateway.onrender.com";
  private static final String DIRECTA = "http://directa-";

  private RestTemplate rest;
  private MockRestServiceServer servidor;

  @BeforeEach
  void setUp() {
    rest = new RestTemplate();
    // Las consultas salen a la vez: al test le importa qué se pidió, no en qué orden.
    servidor = MockRestServiceServer.bindTo(rest).ignoreExpectOrder(true).build();
  }

  private DemoTools demo(String gateway) {
    SesionMcp sesion = new SesionMcp();
    sesion.iniciarComoAdmin("admin");
    DonaTrackApi api =
        new DonaTrackApi(
            rest,
            DIRECTA + "donaciones",
            DIRECTA + "donadores",
            DIRECTA + "logistica",
            DIRECTA + "incentivos",
            gateway,
            "/donaciones",
            "/donadores",
            "/logistica",
            "/incentivos",
            "/consultas");
    return new DemoTools(api, sesion);
  }

  private void responde(String url, String cuerpo) {
    servidor
        .expect(ExpectedCount.manyTimes(), requestTo(url))
        .andExpect(method(HttpMethod.GET))
        .andRespond(withSuccess(cuerpo, MediaType.APPLICATION_JSON));
  }

  // ── despertar_servicios ────────────────────────────────────────────────────

  @Test
  @DisplayName("Sin gateway, despertar consulta los cuatro módulos directo y lo dice")
  void despertarSinGateway() {
    responde(DIRECTA + "donaciones/productos", "[]");
    responde(DIRECTA + "donadores/donadores", "[]");
    responde(DIRECTA + "logistica/depositos", "[]");
    responde(DIRECTA + "incentivos/insignias", "[]");

    String salida = demo("").despertarServicios();

    servidor.verify();
    assertTrue(salida.contains("sin gateway"));
    assertFalse(salida.contains("**Gateway**"), "sin gateway no hay puerta que despertar");
    assertTrue(salida.contains("Todo está despierto"));
  }

  @Test
  @DisplayName("Con gateway, despierta primero al gateway y después a cada módulo a través de él")
  void despertarConGateway() {
    responde(GW + "/actuator/health", "{\"status\":\"UP\"}");
    responde(GW + "/donaciones/productos", "[]");
    responde(GW + "/donadores/donadores", "[]");
    // La trampa de siempre: Logística con /depositos e Incentivos con /insignias, cada uno detrás
    // de su prefijo.
    responde(GW + "/logistica/depositos", "[]");
    responde(GW + "/incentivos/insignias", "[]");
    servidor
        .expect(ExpectedCount.manyTimes(), requestTo(GW + "/consultas/actuator/health"))
        .andRespond(withStatus(HttpStatus.NOT_FOUND).body("no hay ruta"));

    String salida = demo(GW).despertarServicios();

    // Si hubiera llamado a alguna URL directa, el servidor simulado habría fallado por pedido
    // inesperado.
    servidor.verify();
    assertTrue(salida.contains("gateway " + GW));
    assertTrue(salida.indexOf("**Gateway**") < salida.indexOf("**Donaciones**"), "el gateway va primero");
    assertTrue(salida.contains("**Incentivos** — ✅"));
  }

  @Test
  @DisplayName("El Servicio de Consultas que todavía no existe no cuenta como algo que falta despertar")
  void consultasInexistenteNoEsUnaFalla() {
    responde(GW + "/actuator/health", "{}");
    responde(GW + "/donaciones/productos", "[]");
    responde(GW + "/donadores/donadores", "[]");
    responde(GW + "/logistica/depositos", "[]");
    responde(GW + "/incentivos/insignias", "[]");
    servidor
        .expect(ExpectedCount.manyTimes(), requestTo(GW + "/consultas/actuator/health"))
        .andRespond(withStatus(HttpStatus.NOT_FOUND).body("no hay ruta"));

    String salida = demo(GW).despertarServicios();

    assertTrue(salida.contains("Servicio de Consultas** — ⚪"), "se dice que todavía no está");
    assertFalse(salida.contains("Repetí esta herramienta"), "no hay nada que repetir");
    assertTrue(salida.contains("Todo está despierto"));
  }

  @Test
  @DisplayName("Si el gateway no responde, no se despierta a los módulos a través de él")
  void gatewayCaidoNoSigueConLosModulos() {
    servidor
        .expect(ExpectedCount.manyTimes(), requestTo(GW + "/actuator/health"))
        .andRespond(
            withStatus(HttpStatus.SERVICE_UNAVAILABLE)
                .contentType(MediaType.APPLICATION_JSON)
                .body("{\"status\":503,\"error\":\"Service Unavailable\"}"));

    String salida = demo(GW).despertarServicios();

    // Cualquier pedido a un módulo habría fallado por pedido inesperado y figuraría en la salida.
    assertTrue(salida.contains("**Gateway** — ⚠️ no responde"));
    assertFalse(salida.contains("**Donaciones**"), "no se les pregunta a los módulos");
    assertTrue(salida.contains("repetí esta herramienta"), "y se dice qué hacer");
  }

  @Test
  @DisplayName("Un módulo suspendido se marca aparte: no es uno dormido y repetir no lo despierta")
  void despertarConUnModuloSuspendido() {
    responde(GW + "/actuator/health", "{}");
    responde(GW + "/donaciones/productos", "[]");
    responde(GW + "/donadores/donadores", "[]");
    responde(GW + "/incentivos/insignias", "[]");
    servidor
        .expect(ExpectedCount.manyTimes(), requestTo(GW + "/logistica/depositos"))
        .andRespond(
            withStatus(HttpStatus.SERVICE_UNAVAILABLE)
                .header("x-render-routing", "suspend-by-user")
                .body("<title>Service Suspended</title>"));
    servidor
        .expect(ExpectedCount.manyTimes(), requestTo(GW + "/consultas/actuator/health"))
        .andRespond(withStatus(HttpStatus.NOT_FOUND).body("no hay ruta"));

    String salida = demo(GW).despertarServicios();

    assertTrue(salida.contains("**Logística** — ⛔ suspendido en Render"));
    assertTrue(salida.contains("Hay servicios suspendidos en Render"));
    assertTrue(salida.contains("panel de Render"), "dice dónde se reanuda");
    assertFalse(salida.contains("Todo está despierto"), "no es verdad con uno suspendido");
    assertFalse(salida.contains("Repetí esta herramienta"), "repetir no lo arregla");
  }

  // ── estado_del_sistema ─────────────────────────────────────────────────────

  @Test
  @DisplayName("El estado del sistema dice por dónde se llega y si responden el gateway y Consultas")
  void estadoConGateway() {
    // El estado mira a Consultas por /estado (la sincronización), no por el health check.
    servidor
        .expect(ExpectedCount.manyTimes(), requestTo(GW + "/consultas/estado"))
        .andRespond(withStatus(HttpStatus.NOT_FOUND).body("no hay ruta"));
    // Todo lo demás, vacío pero contestando.
    servidor
        .expect(ExpectedCount.manyTimes(), requestTo(Matchers.startsWith(GW)))
        .andRespond(withSuccess("[]", MediaType.APPLICATION_JSON));

    String salida = demo(GW).estadoDelSistema();

    assertTrue(salida.contains("**Conexión** — los pedidos van por el gateway " + GW));
    assertTrue(salida.contains("**Gateway** — ✅ responde"));
    assertTrue(salida.contains("**Servicio de Consultas** — ⚪"));
    assertTrue(salida.contains("**Donaciones** — 0 productos"), "y sigue resumiendo los módulos");
  }

  @Test
  @DisplayName("Con el gateway caído, el estado explica por qué todos los módulos figuran sin responder")
  void estadoConGatewayCaido() {
    servidor
        .expect(ExpectedCount.manyTimes(), requestTo(Matchers.startsWith(GW)))
        .andRespond(
            withStatus(HttpStatus.SERVICE_UNAVAILABLE)
                .contentType(MediaType.APPLICATION_JSON)
                .body("{\"status\":503,\"error\":\"Service Unavailable\"}"));

    String salida = demo(GW).estadoDelSistema();

    assertTrue(salida.contains("**Gateway** — ⚠️ no responde"));
    assertTrue(salida.contains("**Donaciones** — no respondió"));
    assertTrue(
        salida.contains("aunque estén despiertos"),
        "sin esto parecería que los cuatro módulos están caídos, cuando es la puerta");
    assertTrue(salida.contains("despertar_servicios"));
  }

  @Test
  @DisplayName("Sin gateway, el estado lo dice y no inventa un gateway ni un Servicio de Consultas")
  void estadoSinGateway() {
    servidor
        .expect(ExpectedCount.manyTimes(), requestTo(Matchers.startsWith(DIRECTA)))
        .andRespond(withSuccess("[]", MediaType.APPLICATION_JSON));

    String salida = demo("").estadoDelSistema();

    assertTrue(salida.contains("**Conexión** — sin gateway"));
    assertFalse(salida.contains("**Gateway**"));
    assertFalse(salida.contains("Servicio de Consultas"));
  }
}
