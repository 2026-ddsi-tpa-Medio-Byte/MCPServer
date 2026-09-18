package ar.edu.utn.dds.k3003.mcp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Supplier;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.stereotype.Service;

/**
 * Herramientas para preparar y conducir una demostración del sistema.
 *
 * <p>No agregan reglas de negocio: dejan la base en un estado conocido, resumen cómo está todo y
 * dicen en qué orden conviene mostrar los flujos. Son las tres cosas que, sin esto, hay que hacer
 * a mano en cuatro Swagger distintos mientras alguien mira.
 */
@Service
public class DemoTools {

  private static final ObjectMapper MAPPER = new ObjectMapper();

  /** Cuántos productos se recorren para contar necesidades: hay una consulta por producto. */
  private static final int PRODUCTOS_A_RECORRER = 10;

  /** Lo que se espera por cada consulta del resumen antes de darla por perdida. */
  private static final int PACIENCIA_SEGUNDOS = 6;

  private final DonaTrackApi api;
  private final SesionMcp sesion;

  public DemoTools(DonaTrackApi api, SesionMcp sesion) {
    this.api = api;
    this.sesion = sesion;
  }

  // ── Despertar ──────────────────────────────────────────────────────────────

  @Tool(
      name = "despertar_servicios",
      description =
          "Despierta los cuatro módulos y dice cuáles responden. En Render los servicios del "
              + "plan gratuito se duermen sin tráfico y el primer pedido puede tardar un minuto o "
              + "fallar. Conviene usarlo antes de una demostración y cada vez que un módulo "
              + "conteste que no responde.")
  public String despertarServicios() {
    // En paralelo y no uno detrás de otro: un módulo caído tarda lo que tarde en darse por
    // vencido, y en serie esa espera se suma cuatro veces. Nadie va a esperar eso delante de
    // alguien que está mirando.
    java.util.Map<String, Supplier<String>> consultas = new LinkedHashMap<>();
    consultas.put("Donaciones", () -> api.getDonaciones("/productos"));
    consultas.put("Donadores", () -> api.getDonadores("/donadores"));
    consultas.put("Logística", () -> api.getLogistica("/depositos"));
    consultas.put("Incentivos", () -> api.getIncentivos("/insignias"));

    java.util.Map<String, java.util.concurrent.CompletableFuture<String>> pendientes =
        new LinkedHashMap<>();
    consultas.forEach(
        (modulo, consulta) ->
            pendientes.put(
                modulo,
                java.util.concurrent.CompletableFuture.supplyAsync(() -> ping(modulo, consulta))
                    // Se deja de esperar al minuto, pero el pedido sigue viajando: aunque no se
                    // vea la respuesta, alcanza para que Render arranque el servicio.
                    .completeOnTimeout(
                        "- **" + modulo + "** — ⚠️ tardó más de un minuto; probá de nuevo\n",
                        60,
                        java.util.concurrent.TimeUnit.SECONDS)));

    StringBuilder sb = new StringBuilder("**Estado de los módulos**\n\n");
    pendientes.forEach((modulo, futuro) -> sb.append(futuro.join()));
    return sb.append(
            "\nSi alguno sigue sin contestar, esperá un minuto y probá de nuevo: puede estar "
                + "arrancando.\n")
        .toString();
  }

  private String ping(String modulo, Supplier<String> consulta) {
    long inicio = System.currentTimeMillis();
    try {
      consulta.get();
      return "- **" + modulo + "** — ✅ responde (" + (System.currentTimeMillis() - inicio) / 1000
          + "s)\n";
    } catch (Exception e) {
      return "- **" + modulo + "** — ⚠️ no responde\n";
    }
  }

  // ── Dejar la base limpia ───────────────────────────────────────────────────

  @Tool(
      name = "reiniciar_sistema",
      description =
          "Borra los datos de los cuatro módulos y deja el sistema vacío para arrancar una "
              + "demostración desde cero. Es destructivo y no se puede deshacer: usarlo solo si "
              + "el usuario lo pide. Después conviene ejecutar 'preparar_demo'.")
  public String reiniciarSistema() {
    sesion.requerirAdmin("reiniciar el sistema");

    Map<String, String> resultados = new LinkedHashMap<>();
    resultados.put(
        "Donaciones — donaciones, productos e identificadores",
        intentar(() -> api.deleteDonaciones("/donaciones/reset")));
    resultados.put(
        "Donadores — donadores, entidades y necesidades",
        intentar(() -> api.deleteDonadores("/reset")));
    resultados.put(
        "Logística — depósitos, stock y asignaciones",
        intentar(() -> api.deleteLogistica("/api/limpiar-base")));
    resultados.put(
        "Incentivos — insignias, misiones y progreso",
        intentar(() -> api.postIncentivos("/admin/clear", null)));

    StringBuilder sb = new StringBuilder("**Sistema reiniciado**\n\n");
    resultados.forEach((modulo, resultado) -> sb.append("- **").append(modulo).append("** — ").append(resultado).append("\n"));
    sb.append("\nSiguiente paso: `preparar_demo`, que carga las precondiciones de los flujos.\n");
    return sb.toString();
  }

  // ── Ver cómo está todo ─────────────────────────────────────────────────────

  @Tool(
      name = "estado_del_sistema",
      description =
          "Un resumen de cómo está el sistema entero en este momento: cuántos productos, "
              + "donadores, entidades, necesidades, depósitos, stock e insignias hay, y en qué "
              + "estado. Sirve para mostrar el antes y el después de una operación sin tener que "
              + "consultar módulo por módulo. Ya viene redactado: mostrarlo tal cual.")
  public String estadoDelSistema() {
    // Los productos se usan dos veces —para buscar necesidades y para sumar el stock—, así que
    // se piden una sola vez y se recorren juntos.
    PorProducto porProducto = recorrerProductos(leer(() -> api.getDonaciones("/productos")));

    StringBuilder sb = new StringBuilder("**Estado del sistema**\n\n");
    sb.append("- **Donaciones** — ").append(resumenDonaciones(porProducto.productos)).append("\n");
    sb.append("- **Donadores** — ").append(resumenDonadores(porProducto)).append("\n");
    sb.append("- **Logística** — ").append(resumenLogistica(porProducto)).append("\n");
    sb.append("- **Incentivos** — ").append(resumenIncentivos()).append("\n");
    return sb.toString();
  }

  /** Lo que se junta de una sola pasada por los productos. */
  private static class PorProducto {
    JsonNode productos;
    int necesidades;
    int cubiertas;
    int unidadesEnStock;
    boolean logisticaContesto;
    boolean donadoresContesto;
  }

  private PorProducto recorrerProductos(JsonNode productos) {
    PorProducto resultado = new PorProducto();
    resultado.productos = productos;
    if (productos == null || !productos.isArray()) {
      return resultado;
    }
    int recorridos = 0;
    for (JsonNode producto : productos) {
      if (recorridos++ >= PRODUCTOS_A_RECORRER) {
        break;
      }
      String id = producto.path("id").asText("");

      JsonNode necesidades = leer(() -> api.getDonadores("/necesidades?productoID=" + id));
      if (necesidades != null && necesidades.isArray()) {
        resultado.donadoresContesto = true;
        for (JsonNode n : necesidades) {
          resultado.necesidades++;
          if (n.path("cantidadActual").asInt(0) >= n.path("cantidadObjetivo").asInt(1)) {
            resultado.cubiertas++;
          }
        }
      }

      // El listado de depósitos viene con el stock vacío aunque haya unidades guardadas; el dato
      // real está en /stock de cada producto.
      JsonNode stock = leer(() -> api.getLogistica("/stock/" + id));
      if (stock != null) {
        resultado.logisticaContesto = true;
        resultado.unidadesEnStock += stock.path("disponible").asInt(0);
      }
    }
    return resultado;
  }

  private String resumenDonaciones(JsonNode productos) {
    JsonNode donaciones = leer(() -> api.getDonaciones("/donaciones"));
    if (productos == null && donaciones == null) {
      return "no respondió.";
    }
    return cuenta(productos, "producto", "productos")
        + " · "
        + cuenta(donaciones, "donación", "donaciones")
        + porEstado(donaciones, "estado");
  }

  private String resumenDonadores(PorProducto porProducto) {
    JsonNode donadores = leer(() -> api.getDonadores("/donadores"));
    JsonNode entidades = leer(() -> api.getDonadores("/entidades"));
    if (donadores == null && entidades == null) {
      return "no respondió.";
    }
    return cuenta(donadores, "donador", "donadores")
        + porEstado(donadores, "estado")
        + " · "
        + cuenta(entidades, "entidad", "entidades")
        + " · "
        + resumenNecesidades(porProducto);
  }

  /** Donadores no expone la lista completa de necesidades: hay que pedirlas producto por producto. */
  private String resumenNecesidades(PorProducto porProducto) {
    if (!porProducto.donadoresContesto) {
      return "no se pudieron contar las necesidades";
    }
    if (porProducto.necesidades == 0) {
      return "sin necesidades cargadas";
    }
    return porProducto.necesidades
        + " necesidades ("
        + (porProducto.necesidades - porProducto.cubiertas)
        + " pendientes, "
        + porProducto.cubiertas
        + " cubiertas)";
  }

  private String resumenLogistica(PorProducto porProducto) {
    JsonNode depositos = leer(() -> api.getLogistica("/depositos"));
    if (depositos == null && !porProducto.logisticaContesto) {
      return "no respondió.";
    }
    return cuenta(depositos, "depósito", "depósitos")
        + " · "
        + porProducto.unidadesEnStock
        + " unidades en stock";
  }

  private String resumenIncentivos() {
    JsonNode insignias = leer(() -> api.getIncentivos("/insignias"));
    JsonNode misiones = leer(() -> api.getIncentivos("/misiones"));
    if (insignias == null && misiones == null) {
      return "no respondió (el servicio puede estar dormido o caído).";
    }
    return cuenta(insignias, "insignia", "insignias") + " · " + cuenta(misiones, "misión", "misiones");
  }

  // ── El guion de la demostración ────────────────────────────────────────────

  @Tool(
      name = "guion_demo",
      description =
          "El orden sugerido para mostrar el sistema: qué herramienta usar en cada paso y qué "
              + "hace falta antes de cada flujo. Usarlo cuando pregunten cómo demostrar el "
              + "sistema, qué se puede hacer, o por dónde empezar. Mostrarlo tal cual.")
  public String guionDemo() {
    return """
        **Guion de la demostración**

        **Preparación**
        1. `despertar_servicios` — los de Render se duermen; conviene hacerlo unos minutos antes.
        2. `reiniciar_sistema` — deja las cuatro bases vacías.
        3. `preparar_demo` — carga las precondiciones: identificador, producto, donador, entidad,
           depósito, insignia y misión.
        4. `estado_del_sistema` — para mostrar de dónde se parte.

        **Los seis flujos, en el orden en que se encadenan**
        1. `registrar_necesidad` — una entidad pide algo. Si ya había stock, se asigna en el acto.
        2. `registrar_donacion` — el flujo principal: toca Donaciones, Donadores y Logística.
        3. `reportar_entrega` — recién acá la necesidad se da por satisfecha.
        4. `registrar_queja` — la donación deja de estar aceptada y el donador acumula quejas.
        5. `procesar_donador_en_incentivos` — evalúa la misión, otorga o quita la insignia.
        6. `consultar_estadisticas_donador` — cómo quedó el donador al final.

        Cada operación devuelve un resumen del impacto en todos los módulos y el identificador de
        traza, que sirve para buscar esa misma operación en los logs de Datadog.

        **Para mostrar las reglas que no se ven a simple vista**
        - Donar con un donador BANEADO: lo rechaza Donaciones. Se llega con
          `cambiar_estado_donador` o acumulando quejas.
        - Donar menos de lo que pide una necesidad RECURRENTE: no se le asigna, va a stock.
        - Crear un producto con identificador QR y nombre de cantidad impar de letras: lo rechaza.
        """;
  }

  // ── Auxiliares ─────────────────────────────────────────────────────────────

  private String intentar(Supplier<String> operacion) {
    try {
      operacion.get();
      return "✅ borrado";
    } catch (Exception e) {
      return "⚠️ no se pudo: " + e.getMessage();
    }
  }

  /**
   * Lee con un límite de paciencia.
   *
   * <p>Las consultas ya se reintentan solas cuando el módulo está dormido, pero un módulo caído
   * tarda minutos en darse por vencido y el resumen es lo primero que se muestra en una
   * demostración. Vale más un «no respondió» a tiempo que el dato exacto tres minutos después.
   */
  private JsonNode leer(Supplier<String> consulta) {
    try {
      return MAPPER.readTree(
          java.util.concurrent.CompletableFuture.supplyAsync(consulta)
              .get(PACIENCIA_SEGUNDOS, java.util.concurrent.TimeUnit.SECONDS));
    } catch (Exception e) {
      return null;
    }
  }

  private String cuenta(JsonNode lista, String singular, String plural) {
    if (lista == null) {
      return "? " + plural;
    }
    int n = lista.isArray() ? lista.size() : 0;
    return n + " " + (n == 1 ? singular : plural);
  }

  /** Agrega el desglose por estado solo si hay algo que desglosar. */
  private String porEstado(JsonNode lista, String campo) {
    if (lista == null || !lista.isArray() || lista.isEmpty()) {
      return "";
    }
    Map<String, Integer> conteo = new LinkedHashMap<>();
    for (JsonNode item : lista) {
      String estado = item.path(campo).asText("");
      if (!estado.isBlank()) {
        conteo.merge(estado, 1, Integer::sum);
      }
    }
    if (conteo.isEmpty()) {
      return "";
    }
    StringBuilder sb = new StringBuilder(" (");
    conteo.forEach((estado, cantidad) -> sb.append(cantidad).append(" ").append(estado).append(", "));
    sb.setLength(sb.length() - 2);
    return sb.append(")").toString();
  }
}
