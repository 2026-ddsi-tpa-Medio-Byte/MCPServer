package ar.edu.utn.dds.k3003.mcp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
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
import org.springframework.test.web.client.ExpectedCount;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestTemplate;

/**
 * Cómo se arma la dirección de cada módulo según haya o no gateway, y cómo se explican los errores
 * que aparecen con él delante.
 *
 * <p>Las herramientas arman sus paths como siempre y no saben si hay gateway: todo lo que cambia
 * pasa en {@link DonaTrackApi}, y por eso se prueba ahí, mirando la URL a la que de verdad sale el
 * pedido.
 */
class GatewayTest {

  private static final String GW = "https://gateway.onrender.com";

  private RestTemplate rest;
  private MockRestServiceServer servidor;

  @BeforeEach
  void setUp() {
    rest = new RestTemplate();
    servidor = MockRestServiceServer.createServer(rest);
  }

  /** Un cliente con el gateway y los prefijos que se indiquen; las URLs directas no deberían usarse. */
  private DonaTrackApi conGateway(String gateway, String... prefijos) {
    return new DonaTrackApi(
        rest,
        "http://directa-donaciones",
        "http://directa-donadores",
        "http://directa-logistica",
        "http://directa-incentivos",
        gateway,
        prefijos.length > 0 ? prefijos[0] : "/donaciones",
        prefijos.length > 1 ? prefijos[1] : "/donadores",
        prefijos.length > 2 ? prefijos[2] : "/logistica",
        prefijos.length > 3 ? prefijos[3] : "/incentivos",
        prefijos.length > 4 ? prefijos[4] : "/consultas");
  }

  private void esperar(HttpMethod metodo, String url) {
    servidor
        .expect(requestTo(url))
        .andExpect(method(metodo))
        .andRespond(withSuccess("{}", MediaType.APPLICATION_JSON));
  }

  // ── La URL de cada módulo ──────────────────────────────────────────────────

  @Test
  @DisplayName("Con gateway, cada módulo se llama por su prefijo y no por su URL directa")
  void cadaModuloPorSuPrefijo() {
    DonaTrackApi api = conGateway(GW);
    esperar(HttpMethod.GET, GW + "/donaciones/productos/1");
    esperar(HttpMethod.GET, GW + "/donadores/necesidades/1");
    esperar(HttpMethod.GET, GW + "/logistica/stock/3");
    esperar(HttpMethod.GET, GW + "/incentivos/insignias");

    api.getDonaciones("/productos/1");
    api.getDonadores("/necesidades/1");
    api.getLogistica("/stock/3");
    api.getIncentivos("/insignias");

    // Si hubiera usado alguna URL directa, el servidor simulado habría fallado por pedido inesperado.
    servidor.verify();
  }

  @Test
  @DisplayName("Trampa: Logística con /api/... queda {gw}/logistica/api/... sin duplicar ni perder el /api")
  void logisticaConApi() {
    DonaTrackApi api = conGateway(GW);
    esperar(HttpMethod.GET, GW + "/logistica/api/depositos");
    esperar(HttpMethod.POST, GW + "/logistica/api/asignaciones/reportar-entrega");

    api.getLogistica("/api/depositos");
    api.postLogistica("/api/asignaciones/reportar-entrega", null);

    servidor.verify();
  }

  @Test
  @DisplayName("Trampa: Incentivos con /donadores/... no se confunde con Donadores")
  void incentivosConDonadores() {
    DonaTrackApi api = conGateway(GW);
    esperar(HttpMethod.GET, GW + "/incentivos/donadores/3/insignias");
    esperar(HttpMethod.POST, GW + "/incentivos/donadores/3/procesar");
    // Donadores también tiene rutas /donadores/...: el prefijo de módulo se suma a la ruta del
    // módulo, que se llama igual.
    esperar(HttpMethod.GET, GW + "/donadores/donadores/3");

    api.getIncentivos("/donadores/3/insignias");
    api.postIncentivos("/donadores/3/procesar", null);
    api.getDonadores("/donadores/3");

    servidor.verify();
  }

  @Test
  @DisplayName("Todos los verbos de escritura usan la misma base que las lecturas")
  void escriturasPorElGateway() {
    DonaTrackApi api = conGateway(GW);
    esperar(HttpMethod.POST, GW + "/donaciones/donaciones");
    esperar(HttpMethod.PATCH, GW + "/donaciones/donaciones/5/estado");
    esperar(HttpMethod.PUT, GW + "/donadores/entidades/2");
    esperar(HttpMethod.PATCH, GW + "/donadores/donadores/1/estado");
    esperar(HttpMethod.DELETE, GW + "/donadores/necesidades/9");
    esperar(HttpMethod.PUT, GW + "/logistica/api/depositos/DEP-UTN-01");
    esperar(HttpMethod.DELETE, GW + "/logistica/api/depositos/DEP-UTN-01");
    esperar(HttpMethod.DELETE, GW + "/donaciones/donaciones/reset");

    api.postDonaciones("/donaciones", "{}");
    api.patchDonaciones("/donaciones/5/estado", "ACEPTADA");
    api.putDonadores("/entidades/2", "{}");
    api.patchDonadores("/donadores/1/estado", "{}");
    api.deleteDonadores("/necesidades/9");
    api.putLogistica("/api/depositos/DEP-UTN-01", "{}");
    api.deleteLogistica("/api/depositos/DEP-UTN-01");
    api.deleteDonaciones("/donaciones/reset");

    servidor.verify();
  }

  @Test
  @DisplayName("La barra final del gateway y las variantes del prefijo dan la misma URL")
  void barrasYPrefijos() {
    // Con barra final, y prefijos escritos sin barra, con barra final o con las dos.
    DonaTrackApi api = conGateway(GW + "/", "donaciones", "/donadores/", "logistica/", "/incentivos");
    esperar(HttpMethod.GET, GW + "/donaciones/productos");
    esperar(HttpMethod.GET, GW + "/donadores/donadores");
    esperar(HttpMethod.GET, GW + "/logistica/api/depositos");
    esperar(HttpMethod.GET, GW + "/incentivos/insignias");

    api.getDonaciones("/productos");
    api.getDonadores("/donadores");
    api.getLogistica("/api/depositos");
    api.getIncentivos("/insignias");

    servidor.verify();
  }

  @Test
  @DisplayName("Los prefijos se pueden cambiar si el gateway enruta con otros")
  void prefijosPropios() {
    DonaTrackApi api = conGateway(GW, "/api/donaciones", "/api/donadores", "/api/log", "/api/inc");
    esperar(HttpMethod.GET, GW + "/api/donaciones/productos");
    esperar(HttpMethod.GET, GW + "/api/log/api/depositos");

    api.getDonaciones("/productos");
    api.getLogistica("/api/depositos");

    servidor.verify();
  }

  @Test
  @DisplayName("Un prefijo vacío deja el módulo en la raíz del gateway")
  void prefijoVacio() {
    DonaTrackApi api = conGateway(GW, "", "/donadores", "/logistica", "/incentivos");
    esperar(HttpMethod.GET, GW + "/productos");

    api.getDonaciones("/productos");

    servidor.verify();
  }

  @Test
  @DisplayName("Sin gateway, cada módulo se llama por su URL directa, como antes")
  void sinGatewayUrlsDirectas() {
    DonaTrackApi api = conGateway("");
    esperar(HttpMethod.GET, "http://directa-donaciones/productos/1");
    esperar(HttpMethod.GET, "http://directa-logistica/api/depositos");
    esperar(HttpMethod.GET, "http://directa-incentivos/donadores/3/insignias");

    api.getDonaciones("/productos/1");
    api.getLogistica("/api/depositos");
    api.getIncentivos("/donadores/3/insignias");

    servidor.verify();
    assertFalse(api.usaGateway());
  }

  @Test
  @DisplayName("El constructor de cuatro URLs sigue llamando directo, sin gateway")
  void constructorDirecto() {
    DonaTrackApi api = new DonaTrackApi(rest, "http://d1", "http://d2", "http://l", "http://i");
    esperar(HttpMethod.GET, "http://d2/donadores");

    api.getDonadores("/donadores");

    servidor.verify();
    assertFalse(api.usaGateway());
  }

  // ── Decir por dónde se está yendo ──────────────────────────────────────────

  @Test
  @DisplayName("La descripción de la conexión dice si se usa el gateway y cuál")
  void descripcionDeLaConexion() {
    String conGateway = conGateway(GW).descripcionDeConexion();
    String sinGateway = conGateway("").descripcionDeConexion();

    assertTrue(conGateway.contains("gateway " + GW));
    assertTrue(sinGateway.contains("sin gateway"));
    assertTrue(sinGateway.contains("DONATRACK_GATEWAY_URL"), "dice qué variable lo habilita");
  }

  @Test
  @DisplayName("La base de cada módulo se puede ver tal como se la está llamando")
  void baseDeCadaModulo() {
    DonaTrackApi api = conGateway(GW);

    assertEquals(GW + "/logistica", api.baseDe(Modulo.LOGISTICA));
    assertEquals(GW + "/incentivos", api.baseDe(Modulo.INCENTIVOS));
    assertEquals("http://directa-donaciones", conGateway("").baseDe(Modulo.DONACIONES));
  }

  // ── El gateway y el Servicio de Consultas ──────────────────────────────────

  @Test
  @DisplayName("El gateway y el Servicio de Consultas se alcanzan por sus propias rutas")
  void gatewayYConsultas() {
    DonaTrackApi api = conGateway(GW);
    esperar(HttpMethod.GET, GW + "/actuator/health");
    esperar(HttpMethod.GET, GW + "/consultas/estado");

    api.getGateway("/actuator/health");
    api.getConsultas("/estado");

    servidor.verify();
    assertTrue(api.hayServicioDeConsultas());
  }

  @Test
  @DisplayName("Sin gateway no hay a quién preguntarle ni Servicio de Consultas que alcanzar")
  void sinGatewayNoHayConsultas() {
    DonaTrackApi api = conGateway("");

    assertFalse(api.hayServicioDeConsultas());
    assertThrows(IllegalStateException.class, () -> api.getGateway("/actuator/health"));
    assertThrows(IllegalStateException.class, () -> api.getConsultas("/estado"));
  }

  // ── Errores con el gateway delante ─────────────────────────────────────────

  /** El cuerpo que arma el gateway cuando el módulo de atrás no le contesta. */
  private static String cuerpoDelGateway(String error, String modulo) {
    return "{\"error\":\"" + error + "\",\"modulo\":\"" + modulo + "\",\"traceId\":\"gw-4f2a\"}";
  }

  @Test
  @DisplayName("Un 503 del gateway con «modulo» es un módulo que no responde, y el mensaje lo nombra")
  void gatewayConModuloDormido() {
    DonaTrackApi api = conGateway(GW);
    // Una operación no se reintenta: un solo pedido.
    servidor
        .expect(requestTo(GW + "/logistica/api/depositos"))
        .andExpect(method(HttpMethod.POST))
        .andRespond(
            withStatus(HttpStatus.SERVICE_UNAVAILABLE)
                .contentType(MediaType.APPLICATION_JSON)
                .body(cuerpoDelGateway("Connection refused", "logistica")));

    DonaTrackApi.SinRespuesta e =
        assertThrows(
            DonaTrackApi.SinRespuesta.class, () -> api.postLogistica("/api/depositos", "{}"));

    servidor.verify();
    assertTrue(e.getMessage().contains("«logistica»"), "nombra el módulo que no respondió");
    assertTrue(e.getMessage().contains("no pudo conectarse"));
    assertTrue(e.getMessage().contains("gw-4f2a"), "deja la traza para buscarla en Datadog");
    assertTrue(e.getMessage().contains("despertar_servicios"), "dice con qué se resuelve");
    assertFalse(e.getMessage().contains("error interno"));
  }

  @Test
  @DisplayName("Un 504 del gateway con «modulo» es un módulo que no contestó a tiempo")
  void gatewayConTimeout() {
    DonaTrackApi api = conGateway(GW);
    servidor
        .expect(requestTo(GW + "/donaciones/donaciones"))
        .andExpect(method(HttpMethod.POST))
        .andRespond(
            withStatus(HttpStatus.GATEWAY_TIMEOUT)
                .contentType(MediaType.APPLICATION_JSON)
                .body(cuerpoDelGateway("Timeout", "donaciones")));

    DonaTrackApi.SinRespuesta e =
        assertThrows(
            DonaTrackApi.SinRespuesta.class, () -> api.postDonaciones("/donaciones", "{}"));

    assertTrue(e.getMessage().contains("«donaciones» no contestó a tiempo"));
    assertTrue(e.getMessage().contains("despertar_servicios"));
  }

  @Test
  @DisplayName("Una consulta que el gateway no pudo entregar se repite una vez, y a la segunda anda")
  void consultaConGatewaySeReintenta() {
    DonaTrackApi api = conGateway(GW);
    // Primero el módulo todavía estaba despertando; en el segundo pedido ya contesta.
    servidor
        .expect(requestTo(GW + "/logistica/api/depositos"))
        .andRespond(
            withStatus(HttpStatus.SERVICE_UNAVAILABLE)
                .contentType(MediaType.APPLICATION_JSON)
                .body(cuerpoDelGateway("Connection refused", "logistica")));
    servidor
        .expect(requestTo(GW + "/logistica/api/depositos"))
        .andRespond(withSuccess("[{\"depositoid\":\"DEP-UTN-01\"}]", MediaType.APPLICATION_JSON));

    String respuesta = api.getLogistica("/api/depositos");

    servidor.verify();
    assertTrue(respuesta.contains("DEP-UTN-01"));
  }

  @Test
  @DisplayName("Una consulta se repite una sola vez: si el 504 sigue, queda sin respuesta")
  void consultaConGatewaySeReintentaUnaSolaVez() {
    DonaTrackApi api = conGateway(GW);
    servidor
        .expect(ExpectedCount.times(2), requestTo(GW + "/incentivos/insignias"))
        .andRespond(
            withStatus(HttpStatus.GATEWAY_TIMEOUT)
                .contentType(MediaType.APPLICATION_JSON)
                .body(cuerpoDelGateway("Timeout", "incentivos")));

    DonaTrackApi.SinRespuesta e =
        assertThrows(DonaTrackApi.SinRespuesta.class, () -> api.getIncentivos("/insignias"));

    // Dos pedidos exactos: un tercero habría fallado por pedido inesperado.
    servidor.verify();
    assertTrue(e.getMessage().contains("«incentivos»"));
  }

  @Test
  @DisplayName("Una operación que el gateway no pudo entregar no se reintenta: podría duplicarse")
  void operacionConGatewayNoSeReintenta() {
    DonaTrackApi api = conGateway(GW);
    servidor
        .expect(ExpectedCount.once(), requestTo(GW + "/donaciones/donaciones"))
        .andRespond(
            withStatus(HttpStatus.GATEWAY_TIMEOUT)
                .contentType(MediaType.APPLICATION_JSON)
                .body(cuerpoDelGateway("Timeout", "donaciones")));

    assertThrows(DonaTrackApi.SinRespuesta.class, () -> api.postDonaciones("/donaciones", "{}"));

    // Con el timeout no se sabe si la donación llegó: repetirla podría registrarla dos veces.
    servidor.verify();
  }

  @Test
  @DisplayName("El 502 de Donaciones por otro módulo caído sigue siendo ese, aunque pase por el gateway")
  void errorDeDonacionesPorElGateway() {
    DonaTrackApi api = conGateway(GW);
    servidor
        .expect(requestTo(GW + "/donaciones/donaciones"))
        .andRespond(
            withStatus(HttpStatus.BAD_GATEWAY)
                .contentType(MediaType.APPLICATION_JSON)
                .body("{\"error\":\"Logística no respondió\"}"));

    RuntimeException e =
        assertThrows(RuntimeException.class, () -> api.postDonaciones("/donaciones", "{}"));

    assertFalse(e instanceof DonaTrackApi.SinRespuesta, "el módulo contestó: no es que no responda");
    assertTrue(e.getMessage().contains("otro módulo del que depende"));
  }

  @Test
  @DisplayName("El 502 HTML de Render sigue siendo un servicio dormido, con o sin gateway")
  void render502PorElGateway() {
    DonaTrackApi api = conGateway(GW);
    // El módulo no contestó pero tampoco es un error del gateway: el gateway pasa lo que le llegue.
    servidor
        .expect(ExpectedCount.once(), requestTo(GW + "/logistica/api/depositos"))
        .andRespond(withStatus(HttpStatus.BAD_GATEWAY).contentType(MediaType.TEXT_HTML).body("<html/>"));

    assertThrows(DonaTrackApi.SinRespuesta.class, () -> api.getLogistica("/api/depositos"));
  }

  @Test
  @DisplayName("Un 503 de un módulo, sin «modulo», es del módulo: se muestra tal cual y no se atribuye al gateway")
  void unModuloQueContestaSuPropio503() {
    DonaTrackApi api = conGateway(GW);
    // Por ejemplo, un módulo sin RABBITMQ_URL al republicar: contestó, no está dormido.
    servidor
        .expect(requestTo(GW + "/donadores/admin/republicar-eventos"))
        .andRespond(
            withStatus(HttpStatus.SERVICE_UNAVAILABLE)
                .contentType(MediaType.APPLICATION_JSON)
                .body("{\"error\":\"RabbitMQ no está configurado (falta RABBITMQ_URL)\"}"));

    RuntimeException e =
        assertThrows(
            RuntimeException.class, () -> api.postDonadores("/admin/republicar-eventos", null));

    assertFalse(e instanceof DonaTrackApi.SinRespuesta, "no hay nada que reintentar ni despertar");
    assertTrue(e.getMessage().contains("RABBITMQ_URL"), "el motivo real llega al usuario");
    assertFalse(e.getMessage().contains("gateway no pudo"));
  }

  @Test
  @DisplayName("Un 503 de otro formato sigue siendo un error del módulo, con o sin gateway")
  void unCincuentaYTresSinModulo() {
    DonaTrackApi conGw = conGateway(GW);
    DonaTrackApi sinGw = conGateway("");
    servidor
        .expect(requestTo(GW + "/logistica/api/depositos"))
        .andRespond(
            withStatus(HttpStatus.SERVICE_UNAVAILABLE)
                .contentType(MediaType.APPLICATION_JSON)
                .body("{\"status\":503,\"error\":\"Service Unavailable\"}"));
    servidor
        .expect(requestTo("http://directa-logistica/api/depositos"))
        .andRespond(
            withStatus(HttpStatus.SERVICE_UNAVAILABLE)
                .contentType(MediaType.APPLICATION_JSON)
                .body("{\"status\":503,\"error\":\"Service Unavailable\"}"));

    RuntimeException porGateway =
        assertThrows(RuntimeException.class, () -> conGw.getLogistica("/api/depositos"));
    RuntimeException directo =
        assertThrows(RuntimeException.class, () -> sinGw.getLogistica("/api/depositos"));

    assertTrue(porGateway.getMessage().contains("error interno"));
    assertTrue(directo.getMessage().contains("error interno"));
  }

  @Test
  @DisplayName("El cuerpo del gateway se reconoce aunque no haya gateway configurado en el MCP")
  void cuerpoDelGatewaySinConfigurarlo() {
    // Lo que delata al gateway es el cuerpo, no la configuración: si alguien llega a un gateway
    // por su URL directa, el error se entiende igual.
    DonaTrackApi api = conGateway("");
    servidor
        .expect(requestTo("http://directa-incentivos/insignias"))
        .andRespond(
            withStatus(HttpStatus.SERVICE_UNAVAILABLE)
                .contentType(MediaType.APPLICATION_JSON)
                .body(cuerpoDelGateway("Connection refused", "incentivos")));
    servidor
        .expect(requestTo("http://directa-incentivos/insignias"))
        .andRespond(withSuccess("[]", MediaType.APPLICATION_JSON));

    assertEquals("[]", api.getIncentivos("/insignias"));
  }

  // ── Servicios suspendidos en Render ────────────────────────────────────────

  @Test
  @DisplayName("Un servicio suspendido por su dueño no se explica como dormido: despertarlo no sirve")
  void servicioSuspendido() {
    DonaTrackApi api = conGateway("");
    servidor
        .expect(requestTo("http://directa-logistica/api/depositos"))
        .andRespond(
            withStatus(HttpStatus.SERVICE_UNAVAILABLE)
                .header("x-render-routing", "suspend-by-user")
                .contentType(MediaType.TEXT_HTML)
                .body("<html><body>This service has been suspended by its owner.</body></html>"));

    DonaTrackApi.Suspendido e =
        assertThrows(DonaTrackApi.Suspendido.class, () -> api.getLogistica("/api/depositos"));

    assertTrue(e.getMessage().contains("suspendido en Render"));
    assertTrue(e.getMessage().contains("panel de Render"), "dice dónde se reanuda");
    assertFalse(e.getMessage().contains("Conviene usar"), "no manda a despertar lo que no despierta");
  }

  @Test
  @DisplayName("Con gateway, un módulo suspendido también se reconoce y no se confunde con uno dormido")
  void suspendidoDetrasDelGateway() {
    DonaTrackApi api = conGateway(GW);
    // El gateway reenvía la respuesta de Render tal cual, con el header que la delata.
    servidor
        .expect(requestTo(GW + "/incentivos/insignias"))
        .andRespond(
            withStatus(HttpStatus.SERVICE_UNAVAILABLE)
                .header("x-render-routing", "suspend-by-user")
                .body("Service Suspended"));

    assertThrows(DonaTrackApi.Suspendido.class, () -> api.getIncentivos("/insignias"));
  }

  @Test
  @DisplayName("Sin el header, la página «Service Suspended» alcanza para reconocerlo")
  void suspendidoPorElTexto() {
    DonaTrackApi api = conGateway("");
    servidor
        .expect(requestTo("http://directa-incentivos/insignias"))
        .andRespond(
            withStatus(HttpStatus.SERVICE_UNAVAILABLE)
                .body("<title>Service Suspended</title>"));

    assertThrows(DonaTrackApi.Suspendido.class, () -> api.getIncentivos("/insignias"));
  }

  @Test
  @DisplayName("Un 503 común sin ninguna marca de suspensión no se toma por suspendido")
  void unaCaidaComunNoEsUnaSuspension() {
    DonaTrackApi api = conGateway("");
    servidor
        .expect(requestTo("http://directa-incentivos/insignias"))
        .andRespond(withStatus(HttpStatus.SERVICE_UNAVAILABLE).body("mantenimiento"));

    RuntimeException e = assertThrows(RuntimeException.class, () -> api.getIncentivos("/insignias"));

    assertFalse(e instanceof DonaTrackApi.Suspendido);
  }
}
