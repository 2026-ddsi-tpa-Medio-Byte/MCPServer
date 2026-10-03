package ar.edu.utn.dds.k3003.mcp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.function.Supplier;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Service;

/**
 * Herramientas de consulta: todo lo que el modelo puede mirar sin cambiar nada.
 *
 * <p>Están agrupadas por lo que alguien querría preguntar, no por endpoint. Por ejemplo, hay una
 * sola tool para donadores que sirve tanto para listarlos como para traer uno: desde el punto de
 * vista de quien pregunta es la misma intención, y darle dos herramientas casi iguales solo hace
 * que elija mal más seguido.
 */
@Service
public class ConsultaTools {

  private static final ObjectMapper MAPPER = new ObjectMapper();

  private final DonaTrackApi api;

  public ConsultaTools(DonaTrackApi api) {
    this.api = api;
  }

  @Tool(
      name = "consultar_donadores",
      description =
          "Trae los donadores del sistema. Sin parámetro devuelve todos; con un id devuelve ese "
              + "donador con su estado (VERIFICADO, SOSPECHOSO o BANEADO), edad, email y "
              + "domicilio. Usar cuando pregunten quién donó, cuántos donadores hay, o los datos "
              + "de alguien puntual.")
  public String consultarDonadores(
      @ToolParam(required = false, description = "Número del donador. Vacío para traer todos.")
          String donadorId) {
    boolean uno = donadorId != null && !donadorId.isBlank();
    return api.getDonadores(uno ? "/donadores/" + donadorId.trim() : "/donadores");
  }

  @Tool(
      name = "consultar_estadisticas_donador",
      description =
          "Estadísticas de un donador: su categoría, las insignias que ganó y la misión que tiene "
              + "en curso. Usar cuando pregunten por el progreso, los logros o el nivel de "
              + "alguien.")
  public String consultarEstadisticas(
      @ToolParam(description = "Número del donador") String donadorId) {
    return api.getDonadores("/donadores/" + donadorId.trim() + "/estadisticas");
  }

  @Tool(
      name = "consultar_quejas_de_donador",
      description =
          "Las quejas que recibió un donador. Cada queja baja su reputación: con 5 pasa a "
              + "SOSPECHOSO y con 10 queda BANEADO, y ahí ya no puede donar.")
  public String consultarQuejas(@ToolParam(description = "Número del donador") String donadorId) {
    return api.getDonadores("/donadores/" + donadorId.trim() + "/quejas");
  }

  @Tool(
      name = "puede_donar",
      description =
          "Dice si un donador tiene la cuenta habilitada para donar. Conviene consultarlo antes "
              + "de registrar una donación a su nombre, para poder explicar el motivo si está "
              + "bloqueado.")
  public String puedeDonar(@ToolParam(description = "Número del donador") String donadorId) {
    return api.getDonadores("/donadores/" + donadorId.trim() + "/puede-donar");
  }

  @Tool(
      name = "consultar_entidades",
      description =
          "Las entidades beneficiarias: comedores, hogares y demás organizaciones que reciben "
              + "donaciones. Sin parámetro devuelve todas; con un id devuelve esa entidad.")
  public String consultarEntidades(
      @ToolParam(required = false, description = "Número de la entidad. Vacío para traer todas.")
          String entidadId) {
    boolean uno = entidadId != null && !entidadId.isBlank();
    return api.getDonadores(uno ? "/entidades/" + entidadId.trim() : "/entidades");
  }

  @Tool(
      name = "consultar_necesidades",
      description =
          "Las necesidades que tienen las entidades. Con un id devuelve esa necesidad con su "
              + "progreso (cuánto se cubrió del objetivo). Con un producto devuelve las que "
              + "siguen sin cubrirse de ese producto, que es lo que mira Logística para decidir "
              + "a quién asignarle una donación.")
  public String consultarNecesidades(
      @ToolParam(required = false, description = "Número de la necesidad") String necesidadId,
      @ToolParam(required = false, description = "Número del producto") String productoId) {
    if (necesidadId != null && !necesidadId.isBlank()) {
      return api.getDonadores("/necesidades/" + necesidadId.trim());
    }
    if (productoId != null && !productoId.isBlank()) {
      return api.getDonadores("/necesidades?productoID=" + productoId.trim());
    }
    throw new IllegalArgumentException(
        "Hace falta el número de la necesidad o el del producto. "
            + "El listado general de necesidades no está disponible: siempre se consulta por uno "
            + "de los dos.");
  }

  @Tool(
      name = "consultar_productos",
      description =
          "El catálogo de productos que se pueden donar. Sin parámetro devuelve todos; con un id "
              + "devuelve ese producto. Consultarlo antes de registrar una donación o una "
              + "necesidad, porque hace falta el número del producto.")
  public String consultarProductos(
      @ToolParam(required = false, description = "Número del producto. Vacío para traer todos.")
          String productoId) {
    boolean uno = productoId != null && !productoId.isBlank();
    return api.getDonaciones(uno ? "/productos/" + productoId.trim() : "/productos");
  }

  @Tool(
      name = "consultar_donaciones",
      description =
          "Las donaciones registradas y su estado: INGRESADA (esperando que Logística la asigne), "
              + "ACEPTADA (ya entregada) o CONQUEJA. Sin parámetros trae todas; con un donador "
              + "trae las suyas; con un id trae esa donación.")
  public String consultarDonaciones(
      @ToolParam(required = false, description = "Número de la donación") String donacionId,
      @ToolParam(required = false, description = "Número del donador") String donadorId) {
    if (donacionId != null && !donacionId.isBlank()) {
      return api.getDonaciones("/donaciones/" + donacionId.trim());
    }
    if (donadorId != null && !donadorId.isBlank()) {
      // Donaciones filtra por donador aunque no haya fecha: sin ella, trae todo su historial.
      return api.getDonaciones("/donaciones?donadorID=" + donadorId.trim());
    }
    return api.getDonaciones("/donaciones");
  }

  @Tool(
      name = "consultar_identificadores",
      description =
          "Los identificadores de producto (CODIGODEBARRAS o QR) ya creados. Sin parámetro "
              + "devuelve todos; con un id devuelve ese identificador. Consultarlo antes de "
              + "'crear_producto', que necesita el número de uno existente, y de ahí depende la "
              + "regla que tiene que cumplir el producto.")
  public String consultarIdentificadores(
      @ToolParam(
              required = false,
              description = "Número del identificador. Vacío para traer todos.")
          String identificadorId) {
    boolean uno = identificadorId != null && !identificadorId.isBlank();
    return api.getDonaciones(
        uno ? "/identificadores/" + identificadorId.trim() : "/identificadores");
  }

  @Tool(
      name = "consultar_depositos_y_stock",
      description =
          "Los depósitos de Logística y el stock que guardan. El stock son las unidades de "
              + "donaciones que no se asignaron a ninguna necesidad, más los sobrantes de las que "
              + "sí. Usar un parámetro por vez. Sin parámetros devuelve todos los depósitos con "
              + "nombre, id, dirección, capacidad, algoritmo y stockActual, que es el total real "
              + "de unidades guardadas: alcanza para responder qué stock tiene cada depósito. Con "
              + "depositoId devuelve los datos de ese depósito y su stock desglosado por "
              + "producto; para el detalle por producto de todos, consultar cada depósito con su "
              + "depositoId. Con productoId devuelve cuántas unidades hay de ese producto en "
              + "cada depósito y el total. Con todoElStock en true devuelve lo mismo para todos "
              + "los productos.")
  public String consultarLogistica(
      @ToolParam(required = false, description = "Id del depósito, por ejemplo DEP-UTN-01")
          String depositoId,
      @ToolParam(required = false, description = "Número del producto, por ejemplo 3")
          String productoId,
      @ToolParam(
              required = false,
              description = "true para traer el stock de todos los productos. Por defecto false.")
          Boolean todoElStock) {
    // /depositos es el endpoint de integración con los otros módulos y trae stockActual siempre
    // vacío: con él, el modelo concluía que los depósitos no tenían stock. Los /api traen el real.
    if (hay(depositoId)) {
      String id = depositoId.trim();
      String[] respuestas =
          aLaVez(
              () -> api.getLogistica("/api/depositos/" + id),
              () -> api.getLogistica("/api/depositos/" + id + "/stock"));
      ObjectNode juntas = MAPPER.createObjectNode();
      juntas.set("deposito", comoJson(respuestas[0]));
      juntas.set("stock", comoJson(respuestas[1]));
      return juntas.toString();
    }
    if (hay(productoId)) {
      return api.getLogistica("/stock/" + productoId.trim() + "/detalle");
    }
    if (Boolean.TRUE.equals(todoElStock)) {
      return api.getLogistica("/stock");
    }
    return api.getLogistica("/api/depositos");
  }

  @Tool(
      name = "consultar_asignaciones",
      description =
          "Las asignaciones de Logística: qué paquete se armó para qué necesidad, con qué "
              + "producto y cantidad, y si ya se entregó. Cada una trae asignacionid, paqueteid, "
              + "necesidadid, fecha, estado, origen, donacionid, productoid y cantidad. El estado "
              + "ASIGNADA significa pendiente de entrega y COMPLETADA, ya entregada. Para saber "
              + "qué paquete reportar con 'reportar_entrega', buscar las que están en estado "
              + "ASIGNADA. Usar un criterio por vez, salvo estado y necesidadId, que se pueden "
              + "combinar. Sin parámetros devuelve todas.")
  public String consultarAsignaciones(
      @ToolParam(
              required = false,
              description = "Código del paquete, por ejemplo paq-12 o paq-solicitud-<uuid>")
          String paqueteId,
      @ToolParam(
              required = false,
              description =
                  "Número de la donación, por ejemplo 12. Devuelve una lista: una donación puede "
                      + "generar más de un paquete.")
          String donacionId,
      @ToolParam(
              required = false,
              description = "ASIGNADA (pendiente de entrega) o COMPLETADA (ya entregada)")
          String estado,
      @ToolParam(required = false, description = "Número de la necesidad, por ejemplo 7")
          String necesidadId) {
    if (hay(paqueteId)) {
      return api.getLogistica("/api/asignaciones/paquetes/" + paqueteId.trim());
    }
    if (hay(donacionId)) {
      return api.getLogistica("/api/asignaciones/donaciones/" + donacionId.trim());
    }
    StringBuilder filtros = new StringBuilder();
    if (hay(estado)) {
      filtros.append("estado=").append(estado.trim().toUpperCase());
    }
    if (hay(necesidadId)) {
      filtros.append(filtros.isEmpty() ? "" : "&").append("necesidadid=").append(necesidadId.trim());
    }
    return api.getLogistica("/api/asignaciones" + (filtros.isEmpty() ? "" : "?" + filtros));
  }

  @Tool(
      name = "consultar_insignias_y_misiones",
      description =
          "El catálogo de Incentivos: las insignias que se pueden ganar y las misiones que las "
              + "otorgan. Una misión define qué hay que cumplir, por ejemplo tener 20 donaciones "
              + "aceptadas.")
  public String consultarIncentivos(
      @ToolParam(
              required = false,
              description = "Poner 'misiones' para ver las misiones; vacío para las insignias")
          String que) {
    boolean misiones = que != null && que.toLowerCase().contains("mision");
    return api.getIncentivos(misiones ? "/misiones" : "/insignias");
  }

  private static boolean hay(String valor) {
    return valor != null && !valor.isBlank();
  }

  /**
   * Corre dos consultas a la vez y devuelve las dos respuestas.
   *
   * <p>En serie, con Logística dormida, cada una puede tardar 50 segundos con su reintento y la
   * herramienta pasaría el corte de 60. Si alguna falla, sube el error tal como lo tradujo
   * DonaTrackApi, sin el envoltorio del futuro.
   */
  private static String[] aLaVez(Supplier<String> una, Supplier<String> otra) {
    CompletableFuture<String> primera = CompletableFuture.supplyAsync(una, Hilos.ESPERA);
    CompletableFuture<String> segunda = CompletableFuture.supplyAsync(otra, Hilos.ESPERA);
    try {
      return new String[] {primera.join(), segunda.join()};
    } catch (CompletionException e) {
      if (e.getCause() instanceof RuntimeException causa) {
        throw causa;
      }
      throw e;
    }
  }

  /** Para juntar dos respuestas en un solo JSON; si alguna no lo es, va como texto. */
  private static JsonNode comoJson(String respuesta) {
    try {
      return MAPPER.readTree(respuesta);
    } catch (Exception e) {
      return MAPPER.getNodeFactory().textNode(respuesta);
    }
  }
}
