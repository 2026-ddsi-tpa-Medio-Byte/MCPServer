package ar.edu.utn.dds.k3003.mcp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Supplier;

/**
 * Una foto de lo que se ve del sistema desde afuera, para poder sacar dos —antes y después de una
 * operación— y contar qué cambió.
 *
 * <p>Existe para que el que mira la demostración no tenga que ir a buscar el efecto de cada
 * operación módulo por módulo: la donación se registra en Donaciones, pero lo interesante pasa en
 * Donadores y en Logística.
 *
 * <p><b>Ninguna de estas consultas puede hacer fallar la operación que narra.</b> Si un módulo no
 * contesta, esa parte de la foto queda vacía, el relato lo aclara y la operación sigue siendo
 * válida: ya se ejecutó. Por eso todo está envuelto y nunca se propaga una excepción.
 */
class Panorama {

  private static final ObjectMapper MAPPER = new ObjectMapper();

  private final DonaTrackApi api;

  /** Los módulos que no contestaron, para poder decirlo en vez de mentir que no hubo cambios. */
  final Set<String> sinRespuesta = new LinkedHashSet<>();

  JsonNode donador;
  JsonNode donacion;
  JsonNode producto;
  JsonNode necesidad;
  List<JsonNode> necesidadesDelProducto = List.of();
  Integer stock;
  JsonNode asignacion;
  JsonNode insignias;
  JsonNode mision;
  JsonNode quejas;

  /**
   * Logística contestó, pero el paquete todavía no figura.
   *
   * <p>No es lo mismo que no contestar: Logística procesa las donaciones en segundo plano, con un
   * worker, y durante unos segundos después de donar el paquete todavía no existe.
   */
  boolean asignacionPendiente;

  private Panorama(DonaTrackApi api) {
    this.api = api;
  }

  // ── Qué mirar en cada flujo ────────────────────────────────────────────────

  /** Para una donación importan el producto, las necesidades que podrían recibirla y el stock. */
  static Panorama deDonacion(DonaTrackApi api, String productoId) {
    Panorama p = new Panorama(api);
    p.producto = p.leer("Donaciones", () -> api.getDonaciones("/productos/" + productoId));
    p.necesidadesDelProducto = p.leerLista(productoId);
    p.stock = p.leerStock(productoId);
    return p;
  }

  /**
   * Para una entrega importa la necesidad que se cubre, el estado de la donación y el stock.
   *
   * <p>La necesidad no se pide por parámetro porque quien reporta la entrega conoce el paquete, no
   * la necesidad: se la pregunta a Logística.
   */
  static Panorama deEntrega(DonaTrackApi api, String paqueteId, String donacionId) {
    Panorama p = new Panorama(api);
    p.leerAsignacion(paqueteId);
    p.donacion = p.leer("Donaciones", () -> api.getDonaciones("/donaciones/" + donacionId));
    String necesidadId = alguno(p.asignacion, "necesidadID", "necesidadid");
    if (!necesidadId.isBlank()) {
      p.necesidad = p.leer("Donadores", () -> api.getDonadores("/necesidades/" + necesidadId));
    }
    String productoId = texto(p.donacion, "productoID");
    if (!productoId.isBlank()) {
      p.stock = p.leerStock(productoId);
    }
    return p;
  }

  /** Para una queja importan el estado del donador y el de la donación reclamada. */
  static Panorama deQueja(DonaTrackApi api, String donacionId, String donadorId) {
    Panorama p = new Panorama(api);
    p.donacion = p.leer("Donaciones", () -> api.getDonaciones("/donaciones/" + donacionId));
    String id = donadorId != null && !donadorId.isBlank() ? donadorId : texto(p.donacion, "donadorID");
    if (!id.isBlank()) {
      p.donador = p.leer("Donadores", () -> api.getDonadores("/donadores/" + id));
      p.quejas = p.leer("Donadores", () -> api.getDonadores("/donadores/" + id + "/quejas"));
      p.insignias = p.leer("Incentivos", () -> api.getIncentivos("/donadores/" + id + "/insignias"));
    }
    return p;
  }

  /** Para una necesidad nueva importa si había stock esperando que alguien lo pida. */
  static Panorama deNecesidad(DonaTrackApi api, String productoId) {
    Panorama p = new Panorama(api);
    p.stock = p.leerStock(productoId);
    p.necesidadesDelProducto = p.leerLista(productoId);
    return p;
  }

  /** Para el procesamiento en Incentivos importan la categoría, las insignias y la misión. */
  static Panorama deIncentivos(DonaTrackApi api, String donadorId) {
    Panorama p = new Panorama(api);
    p.donador = p.leer("Donadores", () -> api.getDonadores("/donadores/" + donadorId));
    p.insignias =
        p.leer("Incentivos", () -> api.getIncentivos("/donadores/" + donadorId + "/insignias"));
    p.mision =
        p.leer("Incentivos", () -> api.getIncentivos("/donadores/" + donadorId + "/mision-actual"));
    return p;
  }

  // ── Lectura tolerante a fallas ─────────────────────────────────────────────

  /**
   * Segundos que el relato espera por cada consulta antes de darla por perdida.
   *
   * <p>Con los módulos despiertos cada una tarda menos de un segundo. El límite existe para el
   * caso contrario: un módulo caído tarda un minuto y medio en darse por vencido, y el relato
   * acompaña a una operación que <b>ya ocurrió</b>. Nadie va a esperar tres minutos delante de
   * alguien que está mirando la pantalla, y menos para enterarse de que no se pudo saber nada.
   */
  private static final int PACIENCIA_SEGUNDOS = 6;

  /** El worker de Logística suele tardar un par de segundos en procesar una donación. */
  private static final int INTENTOS_ASIGNACION = 3;

  private static final long ESPERA_ENTRE_INTENTOS_MS = 1500;

  private JsonNode leer(String modulo, Supplier<String> consulta) {
    try {
      return MAPPER.readTree(conPaciencia(consulta));
    } catch (Exception e) {
      sinRespuesta.add(modulo);
      return null;
    }
  }

  /** Espera la consulta, pero no más de lo que dura la atención de quien está mirando. */
  private static String conPaciencia(Supplier<String> consulta) throws Exception {
    return java.util.concurrent.CompletableFuture.supplyAsync(consulta)
        .get(PACIENCIA_SEGUNDOS, java.util.concurrent.TimeUnit.SECONDS);
  }

  private List<JsonNode> leerLista(String productoId) {
    JsonNode nodo =
        leer("Donadores", () -> api.getDonadores("/necesidades?productoID=" + productoId));
    List<JsonNode> lista = new ArrayList<>();
    if (nodo != null && nodo.isArray()) {
      nodo.forEach(lista::add);
    }
    return lista;
  }

  /**
   * Suma a la foto la asignación que Logística le dio a una donación recién hecha.
   *
   * <p>Se consulta el paquete en vez de deducir el destino del stock, porque la deducción falla
   * justo acá: Logística procesa en segundo plano, y si se mira antes de que el worker termine,
   * que el stock no haya subido no dice nada. Por eso se reintenta un par de veces.
   */
  Panorama conAsignacionDe(String donacionId) {
    for (int intento = 0; intento < INTENTOS_ASIGNACION; intento++) {
      if (intento > 0) {
        esperar(ESPERA_ENTRE_INTENTOS_MS);
      }
      asignacionPendiente = false;
      leerAsignacion("paq-" + donacionId);
      if (asignacion != null || !asignacionPendiente) {
        break;
      }
    }
    return this;
  }

  /** Distingue «el paquete todavía no existe» de «Logística no contestó». */
  private void leerAsignacion(String paqueteId) {
    try {
      asignacion =
          MAPPER.readTree(
              conPaciencia(() -> api.getLogistica("/api/asignaciones/paquetes/" + paqueteId)));
    } catch (Exception e) {
      asignacion = null;
      if (e.getCause() instanceof DonaTrackApi.NoEncontrado
          || e instanceof DonaTrackApi.NoEncontrado) {
        asignacionPendiente = true;
      } else {
        sinRespuesta.add("Logística");
      }
    }
  }

  private static void esperar(long milisegundos) {
    try {
      Thread.sleep(milisegundos);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
    }
  }

  /** Logística devuelve {@code {"disponible": n}}. */
  private Integer leerStock(String productoId) {
    try {
      return MAPPER.readTree(conPaciencia(() -> api.getLogistica("/stock/" + productoId)))
          .path("disponible")
          .asInt(0);
    } catch (Exception e) {
      // Que un producto no figure en el stock significa que no hay nada guardado de él, no que
      // Logística esté fallando. Son cero unidades, y contarlo así deja ver si después suben.
      if (e.getCause() instanceof DonaTrackApi.NoEncontrado
          || e instanceof DonaTrackApi.NoEncontrado) {
        return 0;
      }
      sinRespuesta.add("Logística");
      return null;
    }
  }

  // ── Lectura cómoda de los nodos ────────────────────────────────────────────

  static String texto(JsonNode nodo, String campo) {
    return nodo == null ? "" : nodo.path(campo).asText("");
  }

  /**
   * Devuelve el primero de los nombres que exista.
   *
   * <p>Hace falta porque Logística responde las asignaciones con los campos en minúscula
   * ({@code necesidadid}) mientras que su propio Swagger los declara en camelCase. Confiar en uno
   * solo deja el relato mudo justo en el flujo de entrega.
   */
  static String alguno(JsonNode nodo, String... campos) {
    for (String campo : campos) {
      String valor = texto(nodo, campo);
      if (!valor.isBlank()) {
        return valor;
      }
    }
    return "";
  }

  static int numero(JsonNode nodo, String campo) {
    return nodo == null ? 0 : nodo.path(campo).asInt(0);
  }

  static int cantidadInsignias(JsonNode insignias) {
    return insignias == null || !insignias.isArray() ? 0 : insignias.size();
  }
}
