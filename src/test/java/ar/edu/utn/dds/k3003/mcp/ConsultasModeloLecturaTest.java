package ar.edu.utn.dds.k3003.mcp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
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
 * Las herramientas del modelo de lectura (Servicio de Consultas), a través del gateway.
 *
 * <p>Las respuestas simuladas son los ejemplos de {@code CONTRATO-CONSULTAS.md}, tal cual: lo que se
 * prueba es que el MCP pida la dirección exacta del contrato y que no estropee lo que le contestan.
 */
class ConsultasModeloLecturaTest {

  private static final String GW = "https://gateway.onrender.com";
  private static final String CONSULTAS = GW + "/consultas";
  private static final ObjectMapper MAPPER = new ObjectMapper();

  private RestTemplate rest;
  private MockRestServiceServer servidor;
  private ConsultaTools consultas;
  private ConsultaTools sinGateway;

  @BeforeEach
  void setUp() {
    rest = new RestTemplate();
    servidor = MockRestServiceServer.createServer(rest);
    consultas = new ConsultaTools(conGateway(rest, GW));
    sinGateway = new ConsultaTools(conGateway(rest, ""));
  }

  static DonaTrackApi conGateway(RestTemplate rest, String gateway) {
    return new DonaTrackApi(
        rest,
        "http://directa-donaciones",
        "http://directa-donadores",
        "http://directa-logistica",
        "http://directa-incentivos",
        gateway,
        "/donaciones",
        "/donadores",
        "/logistica",
        "/incentivos",
        "/consultas");
  }

  // ── Ejemplos del contrato ──────────────────────────────────────────────────

  private static final String FICHA =
      """
      {
        "donadorId": "12",
        "nombre": "Lucía Fernández",
        "estado": "VERIFICADO",
        "categoria": "COLABORADOR",
        "historialCategorias": [
          { "categoria": "OCASIONAL", "desde": "2026-03-02T00:00:00Z" },
          { "categoria": "COLABORADOR", "desde": "2026-06-18T00:00:00Z" }
        ],
        "insignias": [ "Completitud" ],
        "misionEnCurso": {
          "id": "mis-4340", "nombre": "Donaciones Exitosas", "tipo": "DONACIONES_EXITOSAS",
          "progreso": 14, "objetivo": 20, "faltante": 6,
          "categoriaAlCompletar": "TRANSFORMADOR", "insigniaAlCompletar": "Donaciones Exitosas"
        },
        "donacionesPorEstado": { "INGRESADA": 2, "ACEPTADA": 14, "CONQUEJA": 3 },
        "totalDonaciones": 19,
        "quejasRecibidas": 3,
        "quejas": [
          { "id": "31", "donacionId": "22", "fecha": "2026-10-20T00:00:00Z", "descripcion": "Llegó vencido" }
        ],
        "entidadesBeneficiadas": [
          { "entidadId": "4", "razonSocial": "Comedor Los Girasoles", "unidades": 120 }
        ],
        "fuentes": { "donadores": true, "incentivos": true },
        "ultimaActualizacion": "2026-10-20T14:32:10Z",
        "recalculadaEn": "2026-10-20T14:32:10.412Z"
      }""";

  private static final String URGENTES =
      """
      {
        "filtros": { "urgenciaMin": 7, "tipo": "EXTRAORDINARIA", "categoria": null, "subcategoria": null },
        "cantidad": 1,
        "necesidades": [
          {
            "necesidadId": "7", "descripcion": "Fideos para el comedor", "nivelDeUrgencia": 8,
            "tipo": "EXTRAORDINARIA",
            "entidad": { "entidadId": "4", "razonSocial": "Comedor Los Girasoles" },
            "producto": { "productoId": "3", "nombre": "Fideos", "categoria": "Alimentos", "subcategoria": "Secos" },
            "cantidadObjetivo": 50, "cantidadCubierta": 10, "faltante": 40,
            "asignadasSinEntregar": 30, "faltanteSinAsignar": 10,
            "asignacionesPendientes": [
              { "asignacionId": "3b2a", "paqueteId": "paq-15", "depositoId": "DEP-003", "cantidad": 30,
                "fecha": "2026-10-26T15:10:00Z" }
            ],
            "stockPorDeposito": [
              { "depositoId": "DEP-003", "nombre": "Depósito Sur", "cantidad": 120 },
              { "depositoId": "DEP-001", "nombre": "Depósito Centro", "cantidad": 0 }
            ],
            "stockTotal": 120
          }
        ]
      }""";

  private static final String DEPOSITOS =
      """
      {
        "rango": { "desde": "2026-10-01T00:00:00Z", "hasta": "2026-10-31T23:59:59.999Z" },
        "cantidad": 1,
        "depositos": [
          {
            "depositoId": "DEP-003", "nombre": "Depósito Sur", "algoritmo": "PRIOSCORE",
            "capacidadMaxima": 1500, "stockActual": 450, "ocupacion": 0.3, "ocupacionPorcentaje": 30.0,
            "asignacionesPorEstado": { "ASIGNADA": 2, "COMPLETADA": 5 },
            "tiempoPromedioIngresoEntrega": { "segundos": 1330, "legible": "22 min 10 s",
              "entregasConsideradas": 4, "entregasSinDonacion": 1 },
            "entidadesAtendidas": 3
          }
        ]
      }""";

  private static final String CALIDAD =
      """
      {
        "total": { "donaciones": 40, "entregadas": 30, "conQueja": 3, "proporcionConQueja": 0.1 },
        "porCategoria": [
          { "categoria": "Alimentos", "donaciones": 25, "entregadas": 20, "conQueja": 2,
            "proporcionConQueja": 0.1,
            "subcategorias": [
              { "subcategoria": "Secos", "donaciones": 15, "entregadas": 12, "conQueja": 2,
                "proporcionConQueja": 0.1667 } ] }
        ],
        "donadoresConMasQuejas": [
          { "donadorId": "12", "nombre": "Lucía Fernández", "estado": "SOSPECHOSO",
            "categoria": "COLABORADOR", "quejasRecibidas": 5, "totalDonaciones": 19 }
        ]
      }""";

  private void responde(String url, String cuerpo) {
    servidor
        .expect(requestTo(url))
        .andExpect(method(HttpMethod.GET))
        .andRespond(withSuccess(cuerpo, MediaType.APPLICATION_JSON));
  }

  // ── Consulta 1: la ficha ───────────────────────────────────────────────────

  @Test
  @DisplayName("La ficha se pide a {gw}/consultas/donadores/{id}/ficha y llega tal cual")
  void ficha() throws Exception {
    responde(CONSULTAS + "/donadores/12/ficha", FICHA);

    JsonNode ficha = MAPPER.readTree(consultas.consultarFichaDonador(" 12 "));

    servidor.verify();
    assertEquals("COLABORADOR", ficha.path("categoria").asText());
    assertEquals(6, ficha.path("misionEnCurso").path("faltante").asInt());
    assertEquals(3, ficha.path("quejasRecibidas").asInt());
    assertEquals("Comedor Los Girasoles", ficha.path("entidadesBeneficiadas").get(0).path("razonSocial").asText());
    assertFalse(ficha.has("avisoDelMcp"), "con las dos fuentes presentes no hay nada que avisar");
  }

  @Test
  @DisplayName("Si a la ficha le falta una fuente, lo dice: un null no es «no tiene»")
  void fichaIncompleta() throws Exception {
    responde(
        CONSULTAS + "/donadores/12/ficha",
        """
        {"donadorId":"12","nombre":null,"estado":null,"categoria":null,"insignias":[],
         "misionEnCurso":null,"totalDonaciones":2,"quejasRecibidas":0,
         "fuentes":{"donadores":false,"incentivos":true}}""");

    JsonNode ficha = MAPPER.readTree(consultas.consultarFichaDonador("12"));

    String aviso = ficha.path("avisoDelMcp").asText();
    assertTrue(aviso.contains("todavía no recibió los datos de Donadores"));
    assertFalse(aviso.contains("Incentivos"), "solo avisa de lo que falta");
    assertEquals(2, ficha.path("totalDonaciones").asInt(), "y conserva lo que sí llegó");
  }

  @Test
  @DisplayName("El 404 de la ficha no se afirma como «no existe»: puede no estar sincronizado")
  void fichaSinSincronizar() {
    servidor
        .expect(requestTo(CONSULTAS + "/donadores/99/ficha"))
        .andRespond(
            withStatus(HttpStatus.NOT_FOUND)
                .contentType(MediaType.APPLICATION_JSON)
                .body("{\"error\":\"No hay datos del donador 99\"}"));

    // No es una excepción: es información, y lo que tiene que decir es lo que se sabe y lo que no.
    String respuesta = consultas.consultarFichaDonador("99");

    assertTrue(respuesta.contains("NO prueba que no exista"));
    assertTrue(respuesta.contains("consultar_modelo_lectura"), "sugiere mirar la sincronización");
    assertTrue(respuesta.contains("consultar_donadores"), "y la consulta directa a Donadores");
    assertTrue(respuesta.contains("un par de segundos"), "y que se reintente una vez");
  }

  @Test
  @DisplayName("Si el modelo de lectura está caído, el error sube y no se disfraza de «no sincronizado»")
  void fichaConMongoCaido() {
    servidor
        .expect(requestTo(CONSULTAS + "/donadores/12/ficha"))
        .andRespond(
            withStatus(HttpStatus.SERVICE_UNAVAILABLE)
                .contentType(MediaType.APPLICATION_JSON)
                .body("{\"error\":\"MongoDB no responde\"}"));

    RuntimeException e =
        assertThrows(RuntimeException.class, () -> consultas.consultarFichaDonador("12"));

    assertTrue(e.getMessage().contains("MongoDB no responde"));
  }

  // ── Consulta 2: necesidades urgentes ───────────────────────────────────────

  @Test
  @DisplayName("Las necesidades urgentes se piden con los cuatro filtros en la URL del contrato")
  void necesidadesUrgentesConFiltros() throws Exception {
    responde(
        CONSULTAS
            + "/necesidades/urgentes?urgenciaMin=7&tipo=EXTRAORDINARIA&categoria=Alimentos&subcategoria=Secos",
        URGENTES);

    JsonNode r =
        MAPPER.readTree(
            consultas.consultarNecesidadesUrgentes(7, "EXTRAORDINARIA", "Alimentos", "Secos"));

    servidor.verify();
    JsonNode n = r.path("necesidades").get(0);
    assertEquals(120, n.path("stockTotal").asInt());
    assertEquals(30, n.path("asignadasSinEntregar").asInt());
    assertEquals(10, n.path("faltanteSinAsignar").asInt());
    assertEquals("DEP-003", n.path("stockPorDeposito").get(0).path("depositoId").asText());
  }

  @Test
  @DisplayName("Sin filtros, la URL no lleva «?» ni parámetros vacíos")
  void necesidadesUrgentesSinFiltros() {
    responde(CONSULTAS + "/necesidades/urgentes", "{\"cantidad\":0,\"necesidades\":[]}");

    consultas.consultarNecesidadesUrgentes(null, " ", "", null);

    servidor.verify();
  }

  @Test
  @DisplayName("Una categoría con tilde, espacios y «&» viaja codificada, sin cortar el parámetro")
  void categoriaConCaracteresEspeciales() {
    // Un «&» sin codificar cortaría la categoría en dos parámetros; codificado dos veces, llegaría
    // como «%26».
    responde(
        CONSULTAS + "/necesidades/urgentes?categoria=Higiene%20%26%20Limpieza&subcategoria=L%C3%A1cteos",
        "{\"necesidades\":[]}");

    consultas.consultarNecesidadesUrgentes(null, null, "Higiene & Limpieza", "Lácteos");

    servidor.verify();
  }

  @Test
  @DisplayName("El 400 del servicio, por ejemplo un tipo inválido, llega tal cual al usuario")
  void tipoInvalido() {
    servidor
        .expect(requestTo(CONSULTAS + "/necesidades/urgentes?tipo=SEMANAL"))
        .andRespond(
            withStatus(HttpStatus.BAD_REQUEST)
                .contentType(MediaType.APPLICATION_JSON)
                .body("{\"error\":\"tipo debe ser EXTRAORDINARIA o RECURRENTE\"}"));

    RuntimeException e =
        assertThrows(
            RuntimeException.class,
            () -> consultas.consultarNecesidadesUrgentes(null, "SEMANAL", null, null));

    assertTrue(e.getMessage().contains("tipo debe ser EXTRAORDINARIA o RECURRENTE"));
  }

  @Test
  @DisplayName("Si el gateway contesta 404 porque Consultas no está publicado, se dice y no se afirma «no hay»")
  void consultasSinPublicar() {
    servidor
        .expect(requestTo(CONSULTAS + "/necesidades/urgentes"))
        .andRespond(withStatus(HttpStatus.NOT_FOUND).body("sin ruta"));

    String respuesta = consultas.consultarNecesidadesUrgentes(null, null, null, null);

    assertTrue(respuesta.contains("todavía no está publicado en el gateway"));
  }

  // ── Consulta 3: depósitos ──────────────────────────────────────────────────

  @Test
  @DisplayName("El estado de los depósitos se pide con depósito y rango en la URL del contrato")
  void estadoDepositos() throws Exception {
    responde(
        CONSULTAS + "/depositos/estado?depositoId=DEP-003&desde=2026-10-01&hasta=2026-10-31",
        DEPOSITOS);

    JsonNode r =
        MAPPER.readTree(consultas.consultarEstadoDepositos("DEP-003", "2026-10-01", "2026-10-31"));

    servidor.verify();
    JsonNode d = r.path("depositos").get(0);
    assertEquals(30.0, d.path("ocupacionPorcentaje").asDouble());
    assertEquals("22 min 10 s", d.path("tiempoPromedioIngresoEntrega").path("legible").asText());
  }

  @Test
  @DisplayName("Sin parámetros se piden todos los depósitos")
  void estadoDepositosTodos() {
    responde(CONSULTAS + "/depositos/estado", DEPOSITOS);

    consultas.consultarEstadoDepositos(null, null, null);

    servidor.verify();
  }

  @Test
  @DisplayName("Un depósito que el modelo no tiene no se da por inexistente: puede no estar sincronizado")
  void depositoSinSincronizar() {
    servidor
        .expect(requestTo(CONSULTAS + "/depositos/estado?depositoId=DEP-999"))
        .andRespond(
            withStatus(HttpStatus.NOT_FOUND)
                .contentType(MediaType.APPLICATION_JSON)
                .body("{\"error\":\"No existe el depósito DEP-999\"}"));

    String respuesta = consultas.consultarEstadoDepositos("DEP-999", null, null);

    assertTrue(respuesta.contains("no tiene el depósito DEP-999"));
    assertTrue(respuesta.contains("todavía no se haya sincronizado"));
    assertTrue(respuesta.contains("consultar_depositos_y_stock"), "y dónde confirmarlo");
  }

  // ── Consulta 4: calidad ────────────────────────────────────────────────────

  @Test
  @DisplayName("La calidad de lo donado se pide con el top, o sin él")
  void calidad() throws Exception {
    responde(CONSULTAS + "/calidad?top=5", CALIDAD);
    responde(CONSULTAS + "/calidad", CALIDAD);

    JsonNode r = MAPPER.readTree(consultas.consultarCalidadDonaciones(5));
    consultas.consultarCalidadDonaciones(null);

    servidor.verify();
    assertEquals(0.1, r.path("total").path("proporcionConQueja").asDouble());
    assertEquals("12", r.path("donadoresConMasQuejas").get(0).path("donadorId").asText());
  }

  // ── Estado de la sincronización ────────────────────────────────────────────

  @Test
  @DisplayName("El estado del modelo de lectura se pide a {gw}/consultas/estado")
  void modeloLectura() {
    responde(CONSULTAS + "/estado", "{\"consumidor\":{\"habilitado\":true}}");

    String respuesta = consultas.consultarModeloLectura();

    servidor.verify();
    assertTrue(respuesta.contains("habilitado"));
  }

  // ── Sin gateway ────────────────────────────────────────────────────────────

  @Test
  @DisplayName("Sin gateway, las cinco herramientas dicen que Consultas solo se usa por el gateway")
  void sinGatewayCincoHerramientas() {
    String[] respuestas = {
      sinGateway.consultarFichaDonador("12"),
      sinGateway.consultarNecesidadesUrgentes(7, null, null, null),
      sinGateway.consultarEstadoDepositos(null, null, null),
      sinGateway.consultarCalidadDonaciones(null),
      sinGateway.consultarModeloLectura()
    };

    for (String respuesta : respuestas) {
      assertTrue(respuesta.contains("solo se usa a través del gateway"), respuesta);
      assertTrue(respuesta.contains("DONATRACK_GATEWAY_URL"), "dice qué variable lo habilita");
    }
    // No se llamó a nadie: el servidor simulado no esperaba ningún pedido.
    servidor.verify();
  }

  // ── Las descripciones guían al modelo ──────────────────────────────────────

  @Test
  @DisplayName("Las descripciones dicen de dónde salen los datos y que pueden no reflejar lo recién hecho")
  void descripcionesAvisanLaConsistenciaEventual() throws Exception {
    for (String metodo :
        new String[] {
          "consultarFichaDonador",
          "consultarNecesidadesUrgentes",
          "consultarEstadoDepositos",
          "consultarCalidadDonaciones"
        }) {
      String descripcion = descripcionDe(metodo);
      assertTrue(descripcion.contains("modelo de lectura"), metodo);
      assertTrue(descripcion.contains("consistencia eventual"), metodo);
      assertTrue(descripcion.contains("todavía no lo refleja"), metodo + ": cómo decirlo");
      assertTrue(descripcion.contains("un par de segundos"), metodo + ": reintentar una vez");
    }
  }

  @Test
  @DisplayName("La ficha avisa que un 404 no prueba que el donador no exista")
  void descripcionDeLaFichaAdviertePorElCuatrocientosCuatro() throws Exception {
    String descripcion = descripcionDe("consultarFichaDonador");

    assertTrue(descripcion.contains("404 NO prueba"));
    assertTrue(descripcion.contains("consultar_modelo_lectura"));
  }

  @Test
  @DisplayName("Los pedidos de la demo llevan a la herramienta correcta")
  void lasDescripcionesGuianLosPedidosDeLaDemo() throws Exception {
    // «¿Qué necesidades urgentes siguen sin cubrir y cuánto stock tenemos de esos productos?»
    String urgentes = descripcionDe("consultarNecesidadesUrgentes");
    assertTrue(urgentes.contains("necesidades urgentes siguen sin cubrir"));
    assertTrue(urgentes.contains("stockPorDeposito") && urgentes.contains("stockTotal"));
    assertTrue(urgentes.contains("asignadasSinEntregar") && urgentes.contains("faltanteSinAsignar"));

    // «Armame un resumen del donador 12: categoría, cuánto le falta para su misión y quejas.»
    String ficha = descripcionDe("consultarFichaDonador");
    assertTrue(ficha.contains("resumen de un donador") && ficha.contains("misionEnCurso.faltante"));
    assertTrue(ficha.contains("quejas"));

    // Y las herramientas que consultan directo a un módulo mandan a estas.
    assertTrue(descripcionDe("consultarEstadisticas").contains("consultar_ficha_donador"));
    assertTrue(descripcionDe("consultarNecesidades").contains("consultar_necesidades_urgentes"));
    assertTrue(descripcionDe("consultarLogistica").contains("consultar_estado_depositos"));
  }

  private static String descripcionDe(String metodo) {
    for (java.lang.reflect.Method m : ConsultaTools.class.getDeclaredMethods()) {
      if (m.getName().equals(metodo)) {
        return m.getAnnotation(org.springframework.ai.tool.annotation.Tool.class).description();
      }
    }
    throw new IllegalArgumentException("no existe la herramienta " + metodo);
  }

  // ── La dirección de una consulta con parámetros ────────────────────────────

  @Test
  @DisplayName("Una consulta con parámetros se reintenta una vez si el módulo estaba dormido")
  void consultaConParametrosSeReintenta() {
    servidor
        .expect(ExpectedCount.once(), requestTo(CONSULTAS + "/calidad?top=3"))
        .andRespond(
            withStatus(HttpStatus.GATEWAY_TIMEOUT)
                .contentType(MediaType.APPLICATION_JSON)
                .body("{\"error\":\"Timeout\",\"modulo\":\"consultas\",\"traceId\":\"gw-1\"}"));
    responde(CONSULTAS + "/calidad?top=3", CALIDAD);

    String respuesta = consultas.consultarCalidadDonaciones(3);

    servidor.verify();
    assertTrue(respuesta.contains("donadoresConMasQuejas"));
  }
}
