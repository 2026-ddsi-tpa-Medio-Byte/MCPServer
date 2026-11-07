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

  /**
   * Así nombra Logística el paquete que arma cuando Donadores le pide stock al crear una necesidad.
   * No sale de una donación: no hay número de donación que deducir del nombre.
   */
  static final String PREFIJO_SOLICITUD = "paq-solicitud-";

  private final DonaTrackApi api;

  /** Los módulos que no contestaron, para poder decirlo en vez de mentir que no hubo cambios. */
  final Set<String> sinRespuesta = java.util.Collections.synchronizedSet(new LinkedHashSet<>());

  JsonNode donador;
  JsonNode donacion;
  JsonNode producto;
  JsonNode necesidad;
  List<JsonNode> necesidadesDelProducto = List.of();
  Integer stock;
  JsonNode asignacion;

  /** La necesidad a la que fue la asignación, y la entidad que la pidió. Solo tras una donación. */
  JsonNode necesidadAsignada;

  JsonNode entidadAsignada;

  /**
   * Lo que Logística apartó del stock para una necesidad recién creada: sus asignaciones
   * pendientes de origen {@code SOLICITUD_DONADORES} ({@code paq-solicitud-…}). Solo tras crear una
   * necesidad.
   *
   * <p>Vacía es «Logística contestó y no apartó nada»; {@code null}, «no se pudo ver». Son cosas
   * distintas y el relato no puede confundirlas: la primera explica por qué la necesidad no tiene
   * nada asignado, la segunda no explica nada.
   */
  List<JsonNode> asignacionesDeSolicitud;

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

  /**
   * El paquete que se entrega salió de stock apartado al crear una necesidad, no de una donación.
   * Solo en una entrega.
   *
   * <p>Cambia de dónde salen los datos: no hay donación que leer, así que el producto y la
   * cantidad se toman de la asignación. Sin esto el relato preguntaba por una donación inexistente
   * y decía que Donaciones no había contestado.
   */
  boolean paqueteDeSolicitud;

  /** El código del paquete que se entrega, tal como se pidió: sirve aunque Logística no conteste. */
  String paquete = "";

  private Panorama(DonaTrackApi api) {
    this.api = api;
  }

  // ── Qué mirar en cada flujo ────────────────────────────────────────────────

  /** Para una donación importan el producto, las necesidades que podrían recibirla y el stock. */
  static Panorama deDonacion(DonaTrackApi api, String productoId) {
    Panorama p = new Panorama(api);
    p.aLaVez(
        () -> p.producto = p.leer("Donaciones", () -> api.getDonaciones("/productos/" + productoId)),
        () -> p.necesidadesDelProducto = p.leerLista(productoId),
        () -> p.stock = p.leerStock(productoId));
    return p;
  }

  /**
   * Para una entrega importa la necesidad que se cubre, el estado de la donación y el stock.
   *
   * <p>La necesidad no se pide por parámetro porque quien reporta la entrega conoce el paquete, no
   * la necesidad: se la pregunta a Logística. La donación puede venir vacía si el paquete no sigue
   * la convención de nombre; en ese caso también se la pregunta a Logística. Un paquete de
   * solicitud no tiene donación: el producto sale de la asignación.
   */
  static Panorama deEntrega(DonaTrackApi api, String paqueteId, String donacionId) {
    Panorama p = new Panorama(api);
    p.paquete = paqueteId;
    p.aLaVez(() -> p.leerAsignacion(paqueteId), () -> p.leerDonacion(donacionId));
    if (donacionId.isBlank()) {
      p.leerDonacion(alguno(p.asignacion, "donacionID", "donacionid"));
    }
    // Por el nombre, o, si el paquete no sigue la convención, porque la asignación dice que salió
    // de una solicitud y no apunta a ninguna donación.
    p.paqueteDeSolicitud =
        esPaqueteDeSolicitud(paqueteId)
            || ("SOLICITUD_DONADORES".equals(alguno(p.asignacion, "origen"))
                && alguno(p.asignacion, "donacionID", "donacionid").isBlank());
    // La necesidad sale de la asignación y el producto de la donación: recién ahora se pueden pedir.
    // El de una donación se sigue leyendo de la donación, como siempre.
    String necesidadId = alguno(p.asignacion, "necesidadID", "necesidadid");
    String productoId =
        p.paqueteDeSolicitud
            ? alguno(p.asignacion, "productoid", "productoID")
            : texto(p.donacion, "productoID");
    p.aLaVez(
        () -> {
          if (!necesidadId.isBlank()) {
            p.necesidad = p.leer("Donadores", () -> api.getDonadores("/necesidades/" + necesidadId));
          }
        },
        () -> {
          if (!productoId.isBlank()) {
            p.stock = p.leerStock(productoId);
          }
        });
    return p;
  }

  /** Para una queja importan el estado del donador y el de la donación reclamada. */
  static Panorama deQueja(DonaTrackApi api, String donacionId, String donadorId) {
    Panorama p = new Panorama(api);
    p.donacion = p.leer("Donaciones", () -> api.getDonaciones("/donaciones/" + donacionId));
    String id = donadorId != null && !donadorId.isBlank() ? donadorId : texto(p.donacion, "donadorID");
    if (!id.isBlank()) {
      p.aLaVez(
          () -> p.donador = p.leer("Donadores", () -> api.getDonadores("/donadores/" + id)),
          () -> p.quejas = p.leer("Donadores", () -> api.getDonadores("/donadores/" + id + "/quejas")),
          () ->
              p.insignias =
                  p.leer("Incentivos", () -> api.getIncentivos("/donadores/" + id + "/insignias")));
    }
    return p;
  }

  /** Para una necesidad nueva importa si había stock esperando que alguien lo pida. */
  static Panorama deNecesidad(DonaTrackApi api, String productoId) {
    Panorama p = new Panorama(api);
    p.aLaVez(
        () -> p.stock = p.leerStock(productoId),
        () -> p.necesidadesDelProducto = p.leerLista(productoId));
    return p;
  }

  /**
   * Lo mismo, más la necesidad recién creada tal como quedó guardada y lo que Logística apartó
   * para ella.
   *
   * <p>La necesidad se relee porque la respuesta del alta es el DTO de cátedra, que no trae lo
   * cubierto ({@code cantidadActual}): sin releerla, el relato mostraría un 0 que nadie vio. Pero
   * lo cubierto ya no dice si se le asignó stock: la necesidad se satisface al entregar, así que
   * nace en 0 aunque Logística haya apartado unidades. Eso se ve en las asignaciones de Logística.
   * Las cuatro lecturas van a la vez: así la foto tarda lo que la más lenta, a lo sumo el plazo de
   * una consulta, y no suma esperas hacia el corte de 60 segundos de Claude.
   */
  static Panorama despuesDeNecesidad(DonaTrackApi api, String productoId, String necesidadId) {
    Panorama p = new Panorama(api);
    p.aLaVez(
        () -> p.stock = p.leerStock(productoId),
        () -> p.necesidadesDelProducto = p.leerLista(productoId),
        () -> {
          // Sin número no se pregunta: pedir /necesidades/ no trae la que se acaba de crear.
          if (!necesidadId.isBlank()) {
            p.necesidad =
                p.leer("Donadores", () -> api.getDonadores("/necesidades/" + necesidadId));
          }
        },
        () -> {
          // Sin número tampoco: sin filtro, Logística devolvería las de todas las necesidades.
          if (!necesidadId.isBlank()) {
            p.asignacionesDeSolicitud = p.leerAsignacionesDeSolicitud(necesidadId);
          }
        });
    return p;
  }

  /** Para el procesamiento en Incentivos importan la categoría, las insignias y la misión. */
  static Panorama deIncentivos(DonaTrackApi api, String donadorId) {
    Panorama p = new Panorama(api);
    p.aLaVez(
        () -> p.donador = p.leer("Donadores", () -> api.getDonadores("/donadores/" + donadorId)),
        () ->
            p.insignias =
                p.leer("Incentivos", () -> api.getIncentivos("/donadores/" + donadorId + "/insignias")),
        () ->
            p.mision =
                p.leer(
                    "Incentivos",
                    () -> api.getIncentivos("/donadores/" + donadorId + "/mision-actual")));
    return p;
  }

  /**
   * Corre lecturas independientes a la vez.
   *
   * <p>En serie, un módulo caído suma su espera completa por cada consulta, y el relato de una
   * operación sobre Incentivos llegaba a rozar el minuto: Claude corta las herramientas a los 60
   * segundos y las da por falladas aunque hayan terminado bien.
   */
  private void aLaVez(Runnable... lecturas) {
    java.util.concurrent.CompletableFuture.allOf(
            java.util.Arrays.stream(lecturas)
                .map(lectura -> java.util.concurrent.CompletableFuture.runAsync(lectura, Hilos.ESPERA))
                .toArray(java.util.concurrent.CompletableFuture[]::new))
        .join();
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
  private static final int PACIENCIA_SEGUNDOS = 4;

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
    return java.util.concurrent.CompletableFuture.supplyAsync(consulta, Hilos.ESPERA)
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
   * Suma a la foto la asignación que Logística le dio a una donación recién hecha, y a dónde fue:
   * la necesidad y la entidad que la pidió.
   *
   * <p>Se consulta el paquete en vez de deducir el destino del stock, porque la deducción falla
   * justo acá: Logística procesa en segundo plano, y si se mira antes de que el worker termine,
   * que el stock no haya subido no dice nada. Por eso se espera a que aparezca.
   *
   * <p>La espera termina en lo primero que ocurra: la ventana pedida, o {@code hastaMs}, el
   * instante a partir del cual ya no queda presupuesto de tiempo. Siempre se mira al menos una vez.
   *
   * @param ventanaMs cuánto esperar como máximo desde ahora
   * @param hastaMs instante absoluto (en milisegundos) que no se puede pasar, para no cruzar el
   *     corte de 60 segundos de Claude
   * @param intervaloMs cada cuánto volver a mirar
   */
  Panorama conAsignacionDe(String donacionId, long ventanaMs, long hastaMs, long intervaloMs) {
    long limite = Math.min(System.currentTimeMillis() + ventanaMs, hastaMs);
    while (true) {
      asignacionPendiente = false;
      leerAsignacion("paq-" + donacionId);
      if (asignacion != null || !asignacionPendiente) {
        break;
      }
      if (System.currentTimeMillis() + intervaloMs >= limite) {
        break;
      }
      esperar(intervaloMs);
    }
    if (asignacion != null) {
      leerDestino();
    }
    return this;
  }

  /**
   * De la asignación a quien la pidió: paquete → necesidad → entidad.
   *
   * <p>Son dos lecturas encadenadas, porque la entidad sale de la necesidad. Si la primera falla no
   * se intenta la segunda: no hay de dónde sacar la entidad.
   */
  private void leerDestino() {
    String necesidadId = alguno(asignacion, "necesidadid", "necesidadID");
    if (necesidadId.isBlank()) {
      return;
    }
    necesidadAsignada = leer("Donadores", () -> api.getDonadores("/necesidades/" + necesidadId));
    String entidadId = texto(necesidadAsignada, "entidadID");
    if (!entidadId.isBlank()) {
      entidadAsignada = leer("Donadores", () -> api.getDonadores("/entidades/" + entidadId));
    }
  }

  /**
   * Las asignaciones pendientes que Logística armó para una necesidad a pedido de Donadores, es
   * decir sobre stock que ya estaba guardado.
   *
   * <p>Logística las crea antes de que Donadores conteste el alta (la solicitud es sincrónica), así
   * que si la necesidad recibió stock, acá ya figura. Se filtra otra vez de este lado aunque se
   * pida por necesidad: si Logística ignorara el parámetro, devolvería las de todas, y se le
   * atribuiría a la nueva lo apartado para otra. Los campos llegan en minúscula aunque el Swagger
   * de Logística diga camelCase; se aceptan los dos.
   */
  private List<JsonNode> leerAsignacionesDeSolicitud(String necesidadId) {
    JsonNode nodo =
        leer(
            "Logística",
            () -> api.getLogistica("/api/asignaciones?estado=ASIGNADA&necesidadid=" + necesidadId));
    if (nodo == null || !nodo.isArray()) {
      return null;
    }
    List<JsonNode> propias = new ArrayList<>();
    for (JsonNode asignacion : nodo) {
      boolean deEstaNecesidad =
          necesidadId.equals(alguno(asignacion, "necesidadid", "necesidadID"));
      boolean deSolicitud =
          "SOLICITUD_DONADORES".equals(alguno(asignacion, "origen"))
              || esPaqueteDeSolicitud(alguno(asignacion, "paqueteid", "paqueteID"));
      if (deEstaNecesidad && deSolicitud) {
        propias.add(asignacion);
      }
    }
    return propias;
  }

  /** Sin número no se pregunta: pedir {@code /donaciones/} traería el listado entero. */
  private void leerDonacion(String donacionId) {
    if (!donacionId.isBlank()) {
      donacion = leer("Donaciones", () -> api.getDonaciones("/donaciones/" + donacionId));
    }
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

  static boolean esPaqueteDeSolicitud(String paqueteId) {
    return paqueteId != null && paqueteId.startsWith(PREFIJO_SOLICITUD);
  }

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
