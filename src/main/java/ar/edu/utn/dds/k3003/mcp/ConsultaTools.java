package ar.edu.utn.dds.k3003.mcp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
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
          "Estadísticas de un donador: su estado, su categoría, el id de la misión que tiene en "
              + "curso y los ids de las insignias que ganó. Usar cuando pregunten por el nivel o "
              + "los logros de alguien, directo de Donadores. Para un resumen completo de un "
              + "donador (categoría, cuánto le falta para su misión, quejas, donaciones) usar "
              + "'consultar_ficha_donador', que lo trae todo en una sola consulta; si no está "
              + "disponible, se combina con 'consultar_mision_de_donador', "
              + "'consultar_quejas_de_donador' y 'consultar_donaciones'.")
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
              + "progreso (cuánto se cubrió del objetivo). Con un producto devuelve las de ese "
              + "producto, que es lo que mira Logística para decidir a quién asignarle una "
              + "donación. Cada una trae nivelDeUrgencia (del 1 al 10, más alto es más urgente), "
              + "cantidadObjetivo y cantidadActual: está sin cubrir si cantidadActual es menor que "
              + "cantidadObjetivo, y lo que falta es la diferencia. Consulta directo a Donadores "
              + "y no hay un listado de todas. Para «¿qué necesidades urgentes siguen sin cubrir "
              + "y cuánto stock hay de esos productos?» usar 'consultar_necesidades_urgentes', "
              + "que las trae ordenadas con la entidad y el stock incluidos. Esta herramienta "
              + "sirve para una necesidad puntual o recién creada. La razón social de la entidad "
              + "que la pidió se ve con 'consultar_entidades'.")
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
              + "devuelve ese producto. Cada uno trae su categoría y, si se cargó, su "
              + "subcategoría. Consultarlo antes de registrar una donación o una necesidad, "
              + "porque hace falta el número del producto.")
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
              + "los productos de una sola vez. Consulta directo a Logística. Para cruzar el "
              + "stock con las necesidades sin cubrir, 'consultar_necesidades_urgentes' ya lo "
              + "trae; para ocupación, asignaciones por estado y tiempos de entrega por depósito, "
              + "'consultar_estado_depositos'.")
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
      name = "consultar_mision_de_donador",
      description =
          "La misión que un donador tiene en curso en Incentivos y las insignias que ya ganó. "
              + "Usar para saber qué le falta para completar su misión: la misión dice qué hay "
              + "que cumplir y a qué categoría lleva. Si no tiene misión en curso, lo dice. Es "
              + "lo que le falta a 'consultar_estadisticas_donador', que solo trae el id de la "
              + "misión. Para saber cuánto le falta para completarla, 'consultar_ficha_donador' "
              + "lo trae calculado (misionEnCurso.faltante); esta herramienta consulta directo a "
              + "Incentivos.")
  public String consultarMisionDeDonador(
      @ToolParam(description = "Número del donador") String donadorId) {
    String id = donadorId.trim();
    // Las dos consultas son independientes: en serie, con Incentivos dormido, sumarían sus esperas.
    String[] respuestas =
        aLaVez(
            () -> siExiste(() -> api.getIncentivos("/donadores/" + id + "/mision-actual"),
                "El donador no tiene una misión en curso (Incentivos contestó que no hay)."),
            () -> siExiste(() -> api.getIncentivos("/donadores/" + id + "/insignias"),
                "Incentivos no tiene insignias registradas para este donador."));
    ObjectNode juntas = MAPPER.createObjectNode();
    juntas.set("mision", comoJson(respuestas[0]));
    juntas.set("insignias", comoJson(respuestas[1]));
    return juntas.toString();
  }

  /**
   * Un 404 de Incentivos acá es información —no hay misión, no hay insignias—, no una falla. Lo
   * que no sea un 404 sigue subiendo como error.
   */
  private static String siExiste(Supplier<String> consulta, String siNoHay) {
    try {
      return consulta.get();
    } catch (DonaTrackApi.NoEncontrado e) {
      return MAPPER.getNodeFactory().textNode(siNoHay).toString();
    }
  }

  @Tool(
      name = "consultar_insignias_y_misiones",
      description =
          "El catálogo de Incentivos: las insignias que se pueden ganar y las misiones que las "
              + "otorgan. Una misión define qué hay que cumplir, por ejemplo tener 20 donaciones "
              + "aceptadas. Es el catálogo general, no lo de un donador: lo que tiene en curso uno "
              + "puntual se ve con 'consultar_mision_de_donador'.")
  public String consultarIncentivos(
      @ToolParam(
              required = false,
              description = "Poner 'misiones' para ver las misiones; vacío para las insignias")
          String que) {
    boolean misiones = que != null && que.toLowerCase().contains("mision");
    return api.getIncentivos(misiones ? "/misiones" : "/insignias");
  }

  // ── Modelo de lectura (CQRS) ───────────────────────────────────────────────

  /**
   * Lo que el modelo tiene que saber de dónde salen estos datos. Va en cada descripción porque el
   * modelo la lee al elegir la herramienta, no antes.
   */
  private static final String DE_DONDE_SALE =
      "Los datos salen del modelo de lectura (CQRS): una base MongoDB que combina los cuatro "
          + "módulos y se actualiza por eventos, sin llamar a los módulos en el momento. Tiene "
          + "consistencia eventual: justo después de una operación (una donación, una entrega, "
          + "una queja) la consulta puede no reflejarla todavía. Lo normal es menos de un "
          + "segundo, pero si el servicio estaba dormido puede tardar unos segundos. Si falta "
          + "algo recién hecho, esperar un par de segundos y volver a consultar una vez; si "
          + "sigue faltando, decir «el modelo de lectura todavía no lo refleja» (no que falló) y "
          + "mirar 'consultar_modelo_lectura'.";

  /** Sin gateway no hay forma de llegar al Servicio de Consultas: se dice claro y no se falla. */
  private static final String SIN_GATEWAY =
      "El Servicio de Consultas (el modelo de lectura) solo se usa a través del gateway, y este "
          + "servidor MCP no tiene gateway configurado. Para habilitarlo hay que definir la "
          + "variable de entorno DONATRACK_GATEWAY_URL en el bloque \"env\" del servidor "
          + "donatrack, en claude_desktop_config.json, y reiniciar Claude Desktop (el README tiene "
          + "el ejemplo). Mientras tanto se puede consultar cada módulo por separado con "
          + "'consultar_donadores', 'consultar_necesidades', 'consultar_depositos_y_stock' y "
          + "'consultar_asignaciones', aunque cruzar los datos queda a mano.";

  @Tool(
      name = "consultar_ficha_donador",
      description =
          "La ficha integral de UN donador, armada con los cuatro módulos: estado, categoría y su "
              + "historial, insignias, misión en curso con su progreso, objetivo y faltante "
              + "(misionEnCurso.faltante es cuánto le falta para completarla), donaciones por "
              + "estado, quejas recibidas con su detalle y las entidades beneficiadas con las "
              + "unidades entregadas. Usar cuando pidan un resumen de un donador, en qué "
              + "categoría está, cuánto le falta para completar su misión o si tiene quejas: es "
              + "una sola consulta, no hace falta combinar otras herramientas. "
              + DE_DONDE_SALE
              + " Un 404 NO prueba que el donador no exista: puede que todavía no se haya "
              + "sincronizado; antes de afirmarlo, mirar 'consultar_modelo_lectura' o consultar a "
              + "Donadores directo con 'consultar_donadores'. Si fuentes.donadores o "
              + "fuentes.incentivos es false, faltan los datos de ese módulo: no afirmar que no "
              + "tiene insignias ni misión. Las entidades beneficiadas cuentan lo ENTREGADO, no lo "
              + "que solo está asignado.")
  public String consultarFichaDonador(
      @ToolParam(description = "Número del donador, por ejemplo 12") String donadorId) {
    if (!api.hayServicioDeConsultas()) {
      return SIN_GATEWAY;
    }
    String id = donadorId.trim();
    try {
      return conAvisoDeFuentes(api.getConsultas("/donadores/" + id + "/ficha"));
    } catch (DonaTrackApi.NoEncontrado e) {
      return "El modelo de lectura no tiene datos del donador "
          + id
          + " (404). Eso NO prueba que no exista: puede que todavía no se haya sincronizado, o "
          + "que el Servicio de Consultas todavía no esté publicado en el gateway. Si el "
          + "donador se acaba de crear o modificar, esperar un par de segundos y volver a "
          + "consultar una vez. Antes de afirmar que no existe, mirar 'consultar_modelo_lectura' "
          + "(si los eventos de Donadores están llegando) o consultar a Donadores directo con "
          + "'consultar_donadores'.";
    }
  }

  /**
   * Si a la ficha le falta el documento de Donadores o el progreso de Incentivos, lo dice en un
   * campo aparte. Un {@code null} suelto se lee como «no tiene», y lo que pasa es que todavía no
   * llegó: es la confusión que el campo {@code fuentes} del contrato existe para evitar.
   */
  private static String conAvisoDeFuentes(String ficha) {
    try {
      JsonNode nodo = MAPPER.readTree(ficha);
      JsonNode fuentes = nodo.path("fuentes");
      boolean sinDonadores = fuentes.has("donadores") && !fuentes.path("donadores").asBoolean(true);
      boolean sinIncentivos =
          fuentes.has("incentivos") && !fuentes.path("incentivos").asBoolean(true);
      if (!nodo.isObject() || (!sinDonadores && !sinIncentivos)) {
        return ficha;
      }
      List<String> faltan = new ArrayList<>();
      if (sinDonadores) {
        faltan.add("Donadores (nombre, estado, categoría y quejas pueden estar vacíos)");
      }
      if (sinIncentivos) {
        faltan.add("Incentivos (insignias y misión pueden estar vacías)");
      }
      ((ObjectNode) nodo)
          .put(
              "avisoDelMcp",
              "El modelo de lectura todavía no recibió los datos de "
                  + String.join(" ni de ", faltan)
                  + ". Lo que figura vacío o en null no significa que no exista: todavía no "
                  + "llegó. Esperar unos segundos y volver a consultar, o mirar "
                  + "'consultar_modelo_lectura'.");
      return nodo.toString();
    } catch (Exception noEsJson) {
      return ficha;
    }
  }

  @Tool(
      name = "consultar_necesidades_urgentes",
      description =
          "Las necesidades sin cubrir, de la más urgente a la menos urgente. Cada una trae la "
              + "entidad (razón social), el producto, lo que falta (faltante), el stock de ese "
              + "producto en cada depósito (stockPorDeposito) y en total (stockTotal), lo ya "
              + "asignado pero sin entregar (asignadasSinEntregar) y lo que sigue sin cubrir "
              + "aunque se entregue todo lo asignado (faltanteSinAsignar). Usar para «¿qué "
              + "necesidades urgentes siguen sin cubrir y cuánto stock tenemos de esos "
              + "productos?»: responde las dos cosas en una sola consulta. Una donación asignada "
              + "no cubre la necesidad hasta que se reporta la entrega, por eso una necesidad "
              + "puede tener faltante y asignadasSinEntregar a la vez: no es una inconsistencia. "
              + "El nivelDeUrgencia va del 1 al 10 y mayor es más urgente. Todos los filtros son "
              + "opcionales y se combinan; sin ninguno trae todas las sin cubrir. Si se filtra "
              + "por categoría o subcategoría quedan afuera las necesidades cuyo producto "
              + "todavía no llegó al modelo. "
              + DE_DONDE_SALE
              + " Un error 400 (por ejemplo un tipo inválido) lo dice el servicio y se "
              + "transmite tal cual.")
  public String consultarNecesidadesUrgentes(
      @ToolParam(
              required = false,
              description =
                  "Urgencia mínima, del 1 al 10: trae las de ese nivel o más. Por ejemplo 7")
          Integer urgenciaMin,
      @ToolParam(required = false, description = "EXTRAORDINARIA o RECURRENTE")
          String tipo,
      @ToolParam(
              required = false,
              description = "Categoría del producto pedido, por ejemplo Alimentos")
          String categoria,
      @ToolParam(
              required = false,
              description = "Subcategoría del producto pedido, por ejemplo Secos")
          String subcategoria) {
    if (!api.hayServicioDeConsultas()) {
      return SIN_GATEWAY;
    }
    Map<String, String> filtros = new LinkedHashMap<>();
    filtros.put("urgenciaMin", urgenciaMin == null ? null : String.valueOf(urgenciaMin));
    filtros.put("tipo", tipo);
    filtros.put("categoria", categoria);
    filtros.put("subcategoria", subcategoria);
    return consultarModeloDeLectura("/necesidades/urgentes", filtros);
  }

  @Tool(
      name = "consultar_estado_depositos",
      description =
          "El estado de los depósitos: ocupación (stock sobre capacidad), algoritmo, stock por "
              + "producto, asignaciones por estado (ASIGNADA es pendiente de entrega, COMPLETADA "
              + "es entregada), tiempo promedio entre el ingreso de una donación y su entrega, y "
              + "las entidades distintas atendidas con las unidades entregadas a cada una. Sin "
              + "depositoId trae todos. El rango desde/hasta filtra las ENTREGAS (el tiempo "
              + "promedio y las entidades); la ocupación, el stock y las asignaciones por estado "
              + "son siempre la foto actual. Las fechas van como 2026-10-01 o "
              + "2026-10-01T00:00:00Z; con solo la fecha, hasta incluye el día entero. Usar para "
              + "promedios, ocupación y totales por depósito; para el stock de un producto "
              + "puntual directo de Logística está 'consultar_depositos_y_stock'. "
              + DE_DONDE_SALE
              + " Un 404 con depositoId quiere decir que el modelo no tiene ese depósito: puede "
              + "no existir o no haberse sincronizado.")
  public String consultarEstadoDepositos(
      @ToolParam(
              required = false,
              description = "Id del depósito, por ejemplo DEP-UTN-01. Vacío para traer todos.")
          String depositoId,
      @ToolParam(
              required = false,
              description = "Entregas desde esta fecha o instante, por ejemplo 2026-10-01")
          String desde,
      @ToolParam(
              required = false,
              description = "Entregas hasta esta fecha o instante, por ejemplo 2026-10-31")
          String hasta) {
    if (!api.hayServicioDeConsultas()) {
      return SIN_GATEWAY;
    }
    Map<String, String> filtros = new LinkedHashMap<>();
    filtros.put("depositoId", depositoId);
    filtros.put("desde", desde);
    filtros.put("hasta", hasta);
    return consultarModeloDeLectura("/depositos/estado", filtros);
  }

  @Tool(
      name = "consultar_calidad_donaciones",
      description =
          "La calidad de lo donado: por categoría y subcategoría de producto, la proporción de "
              + "donaciones entregadas que terminaron con queja (proporcionConQueja), y el "
              + "ranking de los donadores con más quejas con su estado y su categoría. Usar para "
              + "preguntas como qué categorías de producto reciben más quejas o qué donadores "
              + "acumulan más. La proporción se calcula sobre las donaciones entregadas, no sobre "
              + "el total. "
              + DE_DONDE_SALE)
  public String consultarCalidadDonaciones(
      @ToolParam(
              required = false,
              description =
                  "Cuántos donadores muestra el ranking, de 1 a 100. Por defecto 10")
          Integer top) {
    if (!api.hayServicioDeConsultas()) {
      return SIN_GATEWAY;
    }
    Map<String, String> filtros = new LinkedHashMap<>();
    filtros.put("top", top == null ? null : String.valueOf(top));
    return consultarModeloDeLectura("/calidad", filtros);
  }

  @Tool(
      name = "consultar_modelo_lectura",
      description =
          "El estado de la sincronización del modelo de lectura: si el consumidor de eventos "
              + "está habilitado y escuchando, el último evento recibido de cada módulo con su "
              + "atraso (atrasoMs), cuántos mensajes esperan en la cola y en la DLQ (eventos que "
              + "no se pudieron aplicar), cuántos eventos se aplicaron, duplicaron o descartaron, "
              + "y cuántos documentos hay por colección. Usar para saber si una operación recién "
              + "hecha ya llegó al modelo antes de decir que «falta», o si una consulta compleja "
              + "devuelve poco porque la sincronización va atrasada. Si el consumidor no está "
              + "habilitado (falta RABBITMQ_URL) el servicio contesta con lo que ya tiene pero no "
              + "recibe nada nuevo. Si faltan datos, 'resincronizar_modelo_lectura' los vuelve a "
              + "publicar.")
  public String consultarModeloLectura() {
    if (!api.hayServicioDeConsultas()) {
      return SIN_GATEWAY;
    }
    return consultarModeloDeLectura("/estado", Map.of());
  }

  /** El camino común: pide al Servicio de Consultas y explica un 404 como lo que suele ser. */
  private String consultarModeloDeLectura(String path, Map<String, String> filtros) {
    try {
      return api.getConsultas(path, filtros);
    } catch (DonaTrackApi.NoEncontrado e) {
      // Estas consultas no contestan 404 por «no hay datos» (devuelven listas vacías), salvo la de
      // un depósito puntual. Casi siempre es que la ruta /consultas todavía no está publicada.
      if (filtros.containsKey("depositoId") && !isBlank(filtros.get("depositoId"))) {
        return "El modelo de lectura no tiene el depósito "
            + filtros.get("depositoId").trim()
            + " (404). Puede que no exista, que se haya eliminado o que todavía no se haya "
            + "sincronizado; consultar 'consultar_depositos_y_stock' (directo a Logística) o "
            + "'consultar_modelo_lectura' antes de afirmarlo.";
      }
      return "El gateway contestó 404: el Servicio de Consultas todavía no está publicado en el "
          + "gateway (ruta /consultas) o esta consulta no existe en él. "
          + e.getMessage();
    }
  }

  private static boolean isBlank(String valor) {
    return valor == null || valor.isBlank();
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
