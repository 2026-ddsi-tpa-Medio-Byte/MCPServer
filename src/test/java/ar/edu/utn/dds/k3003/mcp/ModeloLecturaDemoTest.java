package ar.edu.utn.dds.k3003.mcp;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
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
 * Lo que las herramientas de demostración hacen con el modelo de lectura: resincronizarlo,
 * vaciarlo junto con los módulos y contar cómo viene la sincronización.
 */
class ModeloLecturaDemoTest {

  private static final String GW = "https://gateway.onrender.com";
  private static final String RUTA = "/admin/republicar-eventos";

  private RestTemplate rest;
  private MockRestServiceServer servidor;
  private SesionMcp sesion;

  @BeforeEach
  void setUp() {
    rest = new RestTemplate();
    // Los pedidos salen a la vez: importa qué se pidió y cuántas veces, no en qué orden.
    servidor = MockRestServiceServer.bindTo(rest).ignoreExpectOrder(true).build();
    sesion = new SesionMcp();
    sesion.iniciarComoAdmin("admin");
  }

  private DemoTools demo(String gateway) {
    return new DemoTools(ConsultasModeloLecturaTest.conGateway(rest, gateway), sesion);
  }

  /** Un solo pedido: una operación no se reintenta, y un segundo habría fallado por inesperado. */
  private void unaVez(HttpMethod metodo, String url) {
    servidor
        .expect(ExpectedCount.once(), requestTo(url))
        .andExpect(method(metodo))
        .andRespond(withSuccess("{}", MediaType.APPLICATION_JSON));
  }

  // ── resincronizar_modelo_lectura ───────────────────────────────────────────

  @Test
  @DisplayName("Resincronizar llama a republicar en los cuatro módulos y cuenta cada resultado")
  void resincronizarLosCuatro() {
    servidor
        .expect(ExpectedCount.once(), requestTo(GW + "/donaciones" + RUTA))
        .andExpect(method(HttpMethod.POST))
        .andRespond(
            withSuccess(
                "{\"eventosHabilitados\":true,\"total\":5,\"publicados\":5,\"fallidos\":0}",
                MediaType.APPLICATION_JSON));
    servidor
        .expect(ExpectedCount.once(), requestTo(GW + "/donadores" + RUTA))
        .andExpect(method(HttpMethod.POST))
        .andRespond(
            withSuccess(
                "{\"eventosHabilitados\":true,\"total\":9,\"publicados\":9,\"fallidos\":0}",
                MediaType.APPLICATION_JSON));
    servidor
        .expect(ExpectedCount.once(), requestTo(GW + "/logistica" + RUTA))
        .andRespond(withStatus(HttpStatus.NOT_FOUND).body("sin ruta"));
    servidor
        .expect(ExpectedCount.once(), requestTo(GW + "/incentivos" + RUTA))
        .andRespond(withStatus(HttpStatus.NOT_FOUND).body("sin ruta"));

    String salida = demo(GW).resincronizarModeloLectura();

    servidor.verify();
    assertTrue(salida.contains("**Donaciones** — ✅ publicó 5 de 5 eventos"));
    assertTrue(salida.contains("**Donadores** — ✅ publicó 9 de 9 eventos"));
    assertTrue(salida.contains("**Logística** — ⚪ todavía no implementó"), "un 404 no es una falla");
    assertTrue(salida.contains("**Incentivos** — ⚪ todavía no implementó"));
    assertFalse(salida.contains("⚠️"), "ningún resultado se cuenta como falla");
    assertTrue(salida.contains("tarda unos segundos"), "y se aclara que no es instantáneo");
  }

  @Test
  @DisplayName("Un módulo que da 503 por falta de RabbitMQ se informa así, no como caído")
  void moduloSinRabbit() {
    unaVez(HttpMethod.POST, GW + "/donaciones" + RUTA);
    servidor
        .expect(ExpectedCount.once(), requestTo(GW + "/donadores" + RUTA))
        .andRespond(
            withStatus(HttpStatus.SERVICE_UNAVAILABLE)
                .contentType(MediaType.TEXT_PLAIN)
                .body("No se publicó nada: el publicador de eventos está deshabilitado (falta RABBITMQ_URL)"));
    unaVez(HttpMethod.POST, GW + "/logistica" + RUTA);
    unaVez(HttpMethod.POST, GW + "/incentivos" + RUTA);

    String salida = demo(GW).resincronizarModeloLectura();

    assertTrue(salida.contains("**Donadores** — ⚠️ ese módulo no tiene configurado RabbitMQ"));
    assertFalse(salida.contains("despertar_servicios"), "contestó: no hay nada que despertar");
  }

  @Test
  @DisplayName("Donaciones sin RabbitMQ contesta 200 con los eventos deshabilitados: se informa igual")
  void donacionesSinRabbit() {
    servidor
        .expect(ExpectedCount.once(), requestTo(GW + "/donaciones" + RUTA))
        .andRespond(
            withSuccess(
                "{\"eventosHabilitados\":false,\"total\":0,\"publicados\":0,\"fallidos\":0}",
                MediaType.APPLICATION_JSON));
    unaVez(HttpMethod.POST, GW + "/donadores" + RUTA);
    unaVez(HttpMethod.POST, GW + "/logistica" + RUTA);
    unaVez(HttpMethod.POST, GW + "/incentivos" + RUTA);

    String salida = demo(GW).resincronizarModeloLectura();

    // Un «publicó 0 de 0» parecería una base vacía; lo que pasa es que no se intentó nada.
    assertTrue(salida.contains("**Donaciones** — ⚠️ ese módulo no tiene configurado RabbitMQ"));
    assertFalse(salida.contains("publicó 0"));
  }

  @Test
  @DisplayName("Si algunos eventos fallaron al publicar, se dice cuántos y que se puede repetir")
  void eventosFallidos() {
    servidor
        .expect(ExpectedCount.once(), requestTo(GW + "/donaciones" + RUTA))
        .andRespond(
            withSuccess(
                "{\"eventosHabilitados\":true,\"total\":10,\"publicados\":7,\"fallidos\":3}",
                MediaType.APPLICATION_JSON));
    unaVez(HttpMethod.POST, GW + "/donadores" + RUTA);
    unaVez(HttpMethod.POST, GW + "/logistica" + RUTA);
    unaVez(HttpMethod.POST, GW + "/incentivos" + RUTA);

    String salida = demo(GW).resincronizarModeloLectura();

    assertTrue(salida.contains("publicó 7 de 10, pero 3 fallaron: se puede repetir"));
  }

  @Test
  @DisplayName("Un módulo dormido, uno suspendido y uno caído no frenan a los demás ni se reintentan")
  void modulosQueNoResponden() {
    unaVez(HttpMethod.POST, GW + "/donaciones" + RUTA);
    servidor
        .expect(ExpectedCount.once(), requestTo(GW + "/donadores" + RUTA))
        .andRespond(
            withStatus(HttpStatus.SERVICE_UNAVAILABLE)
                .contentType(MediaType.APPLICATION_JSON)
                .body("{\"error\":\"Connection refused\",\"modulo\":\"donadores\",\"traceId\":\"gw-9\"}"));
    servidor
        .expect(ExpectedCount.once(), requestTo(GW + "/logistica" + RUTA))
        .andRespond(
            withStatus(HttpStatus.SERVICE_UNAVAILABLE)
                .header("x-render-routing", "suspend-by-user")
                .body("Service Suspended"));
    servidor
        .expect(ExpectedCount.once(), requestTo(GW + "/incentivos" + RUTA))
        .andRespond(withStatus(HttpStatus.INTERNAL_SERVER_ERROR).body("boom"));

    String salida = demo(GW).resincronizarModeloLectura();

    // Cada módulo recibió exactamente un pedido: sin respuesta no se sabe si publicó.
    servidor.verify();
    assertTrue(salida.contains("**Donaciones** — ✅"));
    assertTrue(salida.contains("**Donadores** — ⚠️ no respondió"));
    assertTrue(salida.contains("**Logística** — ⛔ suspendido en Render"));
    assertTrue(salida.contains("**Incentivos** — ⚠️ no se pudo"));
  }

  @Test
  @DisplayName("Resincronizar exige sesión de admin y no llama a nadie sin ella")
  void resincronizarExigeAdmin() {
    sesion.iniciarComoDonador("1", "Carlos");

    assertThrows(IllegalStateException.class, () -> demo(GW).resincronizarModeloLectura());

    servidor.verify();
  }

  // ── reiniciar_sistema ──────────────────────────────────────────────────────

  @Test
  @DisplayName("Reiniciar el sistema también vacía el modelo de lectura, a través del gateway")
  void reiniciarVaciaConsultas() {
    unaVez(HttpMethod.DELETE, GW + "/donaciones/donaciones/reset");
    unaVez(HttpMethod.DELETE, GW + "/donadores/reset");
    unaVez(HttpMethod.DELETE, GW + "/logistica/api/limpiar-base");
    unaVez(HttpMethod.POST, GW + "/incentivos/admin/clear");
    unaVez(HttpMethod.DELETE, GW + "/consultas/admin/reset");

    String salida = demo(GW).reiniciarSistema();

    // Si faltara el DELETE a Consultas, verify() fallaría: Mongo tiene que quedar vacío también.
    servidor.verify();
    assertTrue(salida.contains("**Servicio de Consultas — el modelo de lectura (MongoDB)** — ✅ borrado"));
    assertTrue(salida.contains("resincronizar_modelo_lectura"), "y dice cómo recargarlo");
  }

  @Test
  @DisplayName("Sin gateway, reiniciar el sistema vacía los cuatro módulos y no intenta llegar a Consultas")
  void reiniciarSinGateway() {
    unaVez(HttpMethod.DELETE, "http://directa-donaciones/donaciones/reset");
    unaVez(HttpMethod.DELETE, "http://directa-donadores/reset");
    unaVez(HttpMethod.DELETE, "http://directa-logistica/api/limpiar-base");
    unaVez(HttpMethod.POST, "http://directa-incentivos/admin/clear");

    String salida = demo("").reiniciarSistema();

    servidor.verify();
    assertFalse(salida.contains("Servicio de Consultas"));
  }

  @Test
  @DisplayName("Si Consultas no contesta al reiniciar, se dice y los módulos igual quedan vacíos")
  void reiniciarConConsultasCaido() {
    unaVez(HttpMethod.DELETE, GW + "/donaciones/donaciones/reset");
    unaVez(HttpMethod.DELETE, GW + "/donadores/reset");
    unaVez(HttpMethod.DELETE, GW + "/logistica/api/limpiar-base");
    unaVez(HttpMethod.POST, GW + "/incentivos/admin/clear");
    servidor
        .expect(ExpectedCount.once(), requestTo(GW + "/consultas/admin/reset"))
        .andRespond(
            withStatus(HttpStatus.SERVICE_UNAVAILABLE)
                .contentType(MediaType.APPLICATION_JSON)
                .body("{\"error\":\"MongoDB no responde\"}"));

    String salida = demo(GW).reiniciarSistema();

    assertTrue(salida.contains("**Donaciones — donaciones, productos e identificadores** — ✅ borrado"));
    assertTrue(salida.contains("**Servicio de Consultas — el modelo de lectura (MongoDB)** — ⚠️ no se pudo"));
    assertTrue(salida.contains("MongoDB no responde"));
  }

  // ── estado_del_sistema: la sincronización ──────────────────────────────────

  private static final String ESTADO_ESCUCHANDO =
      """
      {
        "generadoEn": "2026-10-26T18:40:00Z",
        "consumidor": { "habilitado": true, "escuchando": true, "mensajesEnCola": 0,
                        "mensajesEnDlq": %s, "detalle": null },
        "modulos": [
          { "modulo": "logistica",
            "ultimoEvento": { "tipo": "ASIGNACION_COMPLETADA", "ocurridoEn": "2026-10-26T18:32:10.123Z",
                              "recibidoEn": "2026-10-26T18:32:10.410Z", "atrasoMs": 287,
                              "resultado": "APLICADO" } },
          { "modulo": "donaciones", "ultimoEvento": null }
        ]
      }""";

  /** Todo lo demás contesta vacío; lo que importa acá es lo que dice /consultas/estado. */
  private void estadoDelModeloDeLectura(org.springframework.test.web.client.ResponseCreator respuesta) {
    servidor.expect(ExpectedCount.manyTimes(), requestTo(GW + "/consultas/estado")).andRespond(respuesta);
    servidor
        .expect(ExpectedCount.manyTimes(), requestTo(Matchers.startsWith(GW)))
        .andRespond(withSuccess("[]", MediaType.APPLICATION_JSON));
  }

  @Test
  @DisplayName("El estado del sistema resume la sincronización: último evento de cada módulo y atraso")
  void estadoDeLaSincronizacion() {
    estadoDelModeloDeLectura(
        withSuccess(ESTADO_ESCUCHANDO.formatted("0"), MediaType.APPLICATION_JSON));

    String salida = demo(GW).estadoDelSistema();

    assertTrue(salida.contains("**Servicio de Consultas** — ✅ escuchando eventos · en cola 0 · DLQ vacía"));
    assertTrue(salida.contains("logistica: último ASIGNACION_COMPLETADA (atraso 287 ms)"));
    assertTrue(salida.contains("donaciones: sin eventos todavía"));
  }

  @Test
  @DisplayName("Si hay mensajes en la DLQ, el estado lo marca como alerta")
  void estadoConMensajesEnLaDlq() {
    estadoDelModeloDeLectura(
        withSuccess(ESTADO_ESCUCHANDO.formatted("3"), MediaType.APPLICATION_JSON));

    String salida = demo(GW).estadoDelSistema();

    assertTrue(salida.contains("⚠️ 3 en la DLQ (eventos que no se pudieron aplicar)"));
  }

  @Test
  @DisplayName("Con el consumidor apagado no se muestran «últimos eventos»: serían datos viejos")
  void estadoConConsumidorDeshabilitado() {
    estadoDelModeloDeLectura(
        withSuccess(
            """
            {"consumidor":{"habilitado":false,"escuchando":false,"mensajesEnCola":null,
              "mensajesEnDlq":null,"detalle":"Falta RABBITMQ_URL"},
             "modulos":[{"modulo":"logistica","ultimoEvento":{"tipo":"X","atrasoMs":5}}]}""",
            MediaType.APPLICATION_JSON));

    String salida = demo(GW).estadoDelSistema();

    assertTrue(salida.contains("el consumidor de eventos no está habilitado"));
    assertTrue(salida.contains("Falta RABBITMQ_URL"), "el motivo que da el servicio");
    assertFalse(salida.contains("logistica: último"), "no se muestra lo que no se está actualizando");
  }

  @Test
  @DisplayName("Con el consumidor habilitado pero sin escuchar, se avisa que lo nuevo no se va a reflejar")
  void estadoSinEscuchar() {
    estadoDelModeloDeLectura(
        withSuccess(
            """
            {"consumidor":{"habilitado":true,"escuchando":false,"mensajesEnCola":12,
              "mensajesEnDlq":0,"detalle":"el broker no contesta"},"modulos":[]}""",
            MediaType.APPLICATION_JSON));

    String salida = demo(GW).estadoDelSistema();

    assertTrue(salida.contains("no está escuchando eventos (el broker no contesta)"));
    assertTrue(salida.contains("no se va a reflejar en las consultas"));
  }

  @Test
  @DisplayName("Si el gateway contesta 404 en /consultas/estado, se dice que todavía no está publicado")
  void estadoSinPublicar() {
    estadoDelModeloDeLectura(withStatus(HttpStatus.NOT_FOUND).body("sin ruta"));

    String salida = demo(GW).estadoDelSistema();

    assertTrue(salida.contains("**Servicio de Consultas** — ⚪ el gateway contestó 404"));
  }
}
