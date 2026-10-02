package ar.edu.utn.dds.k3003.mcp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;
import java.util.function.Supplier;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Service;

/**
 * Herramientas para preparar y conducir una demostración del sistema.
 *
 * <p>No agregan reglas de negocio: dejan la base en un estado conocido, resumen cómo está todo y
 * dicen en qué orden conviene mostrar los flujos. Son las tres cosas que, sin esto, hay que hacer
 * a mano en cuatro Swagger distintos mientras alguien mira.
 *
 * <p><b>Todas terminan antes de un minuto, pase lo que pase.</b> Claude corta cualquier
 * herramienta que tarde más de 60 segundos y la da por fallada, aunque del lado del servidor haya
 * terminado bien. Con un servicio de Render dormido o caído cada pedido puede tardar eso solo, así
 * que las consultas se hacen en paralelo y se deja de esperar al llegar al plazo.
 */
@Service
public class DemoTools {

  private static final ObjectMapper MAPPER = new ObjectMapper();

  /** Cuántos productos se recorren para contar necesidades y stock: son dos consultas por cada uno. */
  private static final int PRODUCTOS_A_RECORRER = 10;

  /** Lo que puede tardar una herramienta entera, con margen respecto del corte de 60 segundos. */
  private static final int PLAZO_SEGUNDOS = 45;

  /**
   * Lo que se espera por cada módulo al despertarlo.
   *
   * <p>Es corto a propósito. Un servicio de Render que arranca de cero tarda uno o dos minutos:
   * más que el corte de 60 segundos, así que ninguna espera razonable alcanza para verlo
   * arrancar. Lo que sí sirve es el pedido en sí, que dispara el arranque aunque nadie espere la
   * respuesta. Conviene entonces contestar rápido y que se repita, en vez de hacer esperar al
   * pedo.
   */
  private static final int PLAZO_DESPERTAR_SEGUNDOS = 20;

  /** El resumen se hace en dos rondas de consultas, así que cada una tiene la mitad del plazo. */
  private static final int PLAZO_POR_RONDA_SEGUNDOS = 20;

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
    Map<String, Supplier<String>> pings = new LinkedHashMap<>();
    pings.put("Donaciones", () -> ping(() -> api.getDonaciones("/productos")));
    pings.put("Donadores", () -> ping(() -> api.getDonadores("/donadores")));
    pings.put("Logística", () -> ping(() -> api.getLogistica("/depositos")));
    pings.put("Incentivos", () -> ping(() -> api.getIncentivos("/insignias")));

    // Al llegar al plazo se deja de esperar, pero el pedido sigue viajando: aunque no se vea la
    // respuesta, alcanza para que Render termine de arrancar el servicio.
    Map<String, String> resultados =
        aLaVez(
            pings,
            modulo -> "⏳ arrancando (el pedido ya lo despertó)",
            PLAZO_DESPERTAR_SEGUNDOS);

    StringBuilder sb = new StringBuilder("**Estado de los módulos**\n\n");
    resultados.forEach(
        (modulo, resultado) -> sb.append("- **").append(modulo).append("** — ").append(resultado).append("\n"));

    boolean faltaAlguno = resultados.values().stream().anyMatch(r -> !r.startsWith("✅"));
    if (faltaAlguno) {
      sb.append(
          "\nArrancar de cero le lleva a Render uno o dos minutos, más de lo que se puede esperar "
              + "en una sola consulta. **Repetí esta herramienta hasta que los cuatro digan que "
              + "responden**: cada intento los empuja un poco más.\n");
    } else {
      sb.append("\nLos cuatro están despiertos. Se duermen de nuevo tras un rato sin uso.\n");
    }
    return sb.toString();
  }

  private String ping(Supplier<String> consulta) {
    long inicio = System.currentTimeMillis();
    try {
      consulta.get();
      return "✅ responde (" + (System.currentTimeMillis() - inicio) / 1000 + "s)";
    } catch (Exception e) {
      return "⚠️ no responde";
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

    Map<String, Supplier<String>> borrados = new LinkedHashMap<>();
    borrados.put(
        "Donaciones — donaciones, productos e identificadores",
        () -> intentar(() -> api.deleteDonaciones("/donaciones/reset")));
    borrados.put(
        "Donadores — donadores, entidades y necesidades",
        () -> intentar(() -> api.deleteDonadores("/reset")));
    borrados.put(
        "Logística — depósitos, stock y asignaciones",
        () -> intentar(() -> api.deleteLogistica("/api/limpiar-base")));
    borrados.put(
        "Incentivos — insignias, misiones y progreso",
        () -> intentar(() -> api.postIncentivos("/admin/clear", null)));

    // Cada módulo tiene su propia base: no hay un orden que respetar, y en paralelo un módulo
    // caído no demora a los demás.
    Map<String, String> resultados =
        aLaVez(
            borrados,
            modulo -> "⚠️ no contestó a tiempo; puede haberse borrado igual, conviene revisarlo",
            PLAZO_SEGUNDOS);

    StringBuilder sb = new StringBuilder("**Sistema reiniciado**\n\n");
    resultados.forEach(
        (modulo, resultado) -> sb.append("- **").append(modulo).append("** — ").append(resultado).append("\n"));
    sb.append("\nSiguiente paso: `preparar_demo`, que carga las precondiciones de los flujos.\n");
    return sb.toString();
  }

  @Tool(
      name = "reiniciar_modulo",
      description =
          "Borra los datos de UN solo módulo y deja los otros tres como estaban. Sirve para "
              + "volver a empezar una parte de la demostración sin perder lo que ya está cargado "
              + "en el resto. Es destructivo y no se puede deshacer: usarlo solo si el usuario lo "
              + "pide. Para vaciar los cuatro de una vez está 'reiniciar_sistema'. Requiere "
              + "ADMIN. El resultado ya viene redactado: mostrarlo tal cual.")
  public String reiniciarModulo(
      @ToolParam(description = "Cuál módulo borrar: donaciones, donadores, logistica o incentivos.")
          String modulo) {
    sesion.requerirAdmin("reiniciar un módulo");
    Modulo m = Modulo.desde(modulo);

    // Un solo pedido, así que no hace falta el plazo en paralelo del reinicio completo: el límite
    // de lectura del RestTemplate corta antes del minuto que aguanta una herramienta.
    String resultado = intentar(() -> borrar(m));

    return "**"
        + m.nombre()
        + " reiniciado**\n\n- "
        + queBorra(m)
        + " — "
        + resultado
        + "\n\nLos otros tres módulos quedaron como estaban.\n\n> "
        + consecuencia(m)
        + "\n";
  }

  private String borrar(Modulo m) {
    return switch (m) {
      case DONACIONES -> api.deleteDonaciones("/donaciones/reset");
      case DONADORES -> api.deleteDonadores("/reset");
      case LOGISTICA -> api.deleteLogistica("/api/limpiar-base");
      case INCENTIVOS -> api.postIncentivos("/admin/clear", null);
    };
  }

  private String queBorra(Modulo m) {
    return switch (m) {
      case DONACIONES -> "donaciones, productos e identificadores";
      case DONADORES -> "donadores, entidades y necesidades";
      case LOGISTICA -> "depósitos, stock y asignaciones";
      case INCENTIVOS -> "insignias, misiones y progreso";
    };
  }

  /**
   * Lo que queda inconsistente por haber borrado un módulo solo.
   *
   * <p>Los módulos se referencian por identificador y ninguno valida contra la base del otro, así
   * que vaciar uno deja a los demás apuntando a cosas que ya no existen. No es un error del
   * reinicio, pero quien está demostrando tiene que saberlo antes de que un flujo falle en
   * pantalla.
   */
  private String consecuencia(Modulo m) {
    return switch (m) {
      case DONACIONES ->
          "Las necesidades de Donadores quedan pidiendo productos que ya no existen: donar va a "
              + "fallar hasta que haya un producto nuevo y una necesidad que lo pida.";
      case DONADORES ->
          "Las donaciones quedan a nombre de donadores que ya no existen, y no hay ninguna "
              + "necesidad a la que asignar lo que se done.";
      case LOGISTICA ->
          "Se borró también el depósito por defecto, así que donar va a fallar hasta volver a "
              + "crearlo: `preparar_modulo` con logistica lo deja listo.";
      case INCENTIVOS ->
          "Los donadores siguen existiendo, pero sin insignias ni misiones no hay nada que "
              + "procesar: `preparar_modulo` con incentivos vuelve a cargarlas.";
    };
  }

  private String intentar(Supplier<String> operacion) {
    try {
      operacion.get();
      return "✅ borrado";
    } catch (Exception e) {
      return "⚠️ no se pudo: " + e.getMessage();
    }
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
    // Primera ronda: los listados de cada módulo, todos a la vez.
    Map<String, Supplier<String>> listados = new LinkedHashMap<>();
    listados.put("productos", () -> api.getDonaciones("/productos"));
    listados.put("donaciones", () -> api.getDonaciones("/donaciones"));
    listados.put("donadores", () -> api.getDonadores("/donadores"));
    listados.put("entidades", () -> api.getDonadores("/entidades"));
    listados.put("depositos", () -> api.getLogistica("/depositos"));
    listados.put("insignias", () -> api.getIncentivos("/insignias"));
    listados.put("misiones", () -> api.getIncentivos("/misiones"));
    Map<String, JsonNode> datos = leerTodo(listados);

    // Segunda ronda: necesidades y stock, que solo se pueden pedir producto por producto. Si el
    // módulo no contestó en la primera, no tiene sentido volver a esperarlo diez veces.
    PorProducto porProducto =
        recorrerProductos(
            datos.get("productos"),
            datos.get("donadores") != null || datos.get("entidades") != null,
            datos.get("depositos") != null);

    StringBuilder sb = new StringBuilder("**Estado del sistema**\n\n");
    sb.append("- **Donaciones** — ").append(resumenDonaciones(datos)).append("\n");
    sb.append("- **Donadores** — ").append(resumenDonadores(datos, porProducto)).append("\n");
    sb.append("- **Logística** — ").append(resumenLogistica(datos, porProducto)).append("\n");
    sb.append("- **Incentivos** — ").append(resumenIncentivos(datos)).append("\n");
    return sb.toString();
  }

  /** Lo que se junta de la pasada por los productos. */
  private static class PorProducto {
    int necesidades;
    int cubiertas;
    int unidadesEnStock;
    boolean necesidadesLeidas;
    boolean stockLeido;
  }

  private PorProducto recorrerProductos(
      JsonNode productos, boolean donadoresContesta, boolean logisticaContesta) {
    PorProducto resultado = new PorProducto();
    if (productos == null || !productos.isArray()) {
      return resultado;
    }

    Map<String, Supplier<String>> consultas = new LinkedHashMap<>();
    int recorridos = 0;
    for (JsonNode producto : productos) {
      if (recorridos++ >= PRODUCTOS_A_RECORRER) {
        break;
      }
      String id = producto.path("id").asText("");
      if (donadoresContesta) {
        consultas.put("necesidades:" + id, () -> api.getDonadores("/necesidades?productoID=" + id));
      }
      // El listado de depósitos viene con el stock vacío aunque haya unidades guardadas; el
      // dato real está en /stock de cada producto.
      if (logisticaContesta) {
        consultas.put("stock:" + id, () -> api.getLogistica("/stock/" + id));
      }
    }

    leerTodo(consultas)
        .forEach(
            (clave, nodo) -> {
              if (nodo == null) {
                return;
              }
              if (clave.startsWith("necesidades:") && nodo.isArray()) {
                resultado.necesidadesLeidas = true;
                for (JsonNode n : nodo) {
                  resultado.necesidades++;
                  if (n.path("cantidadActual").asInt(0) >= n.path("cantidadObjetivo").asInt(1)) {
                    resultado.cubiertas++;
                  }
                }
              } else if (clave.startsWith("stock:")) {
                resultado.stockLeido = true;
                resultado.unidadesEnStock += nodo.path("disponible").asInt(0);
              }
            });
    return resultado;
  }

  private String resumenDonaciones(Map<String, JsonNode> datos) {
    JsonNode productos = datos.get("productos");
    JsonNode donaciones = datos.get("donaciones");
    if (productos == null && donaciones == null) {
      return "no respondió.";
    }
    return cuenta(productos, "producto", "productos")
        + " · "
        + cuenta(donaciones, "donación", "donaciones")
        + porEstado(donaciones, "estado");
  }

  private String resumenDonadores(Map<String, JsonNode> datos, PorProducto porProducto) {
    JsonNode donadores = datos.get("donadores");
    JsonNode entidades = datos.get("entidades");
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
    if (!porProducto.necesidadesLeidas) {
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

  private String resumenLogistica(Map<String, JsonNode> datos, PorProducto porProducto) {
    JsonNode depositos = datos.get("depositos");
    if (depositos == null && !porProducto.stockLeido) {
      return "no respondió.";
    }
    return cuenta(depositos, "depósito", "depósitos")
        + " · "
        + porProducto.unidadesEnStock
        + " unidades en stock";
  }

  private String resumenIncentivos(Map<String, JsonNode> datos) {
    JsonNode insignias = datos.get("insignias");
    JsonNode misiones = datos.get("misiones");
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

  // ── Ejecución con plazo ────────────────────────────────────────────────────

  /**
   * Corre varias tareas a la vez y no espera más que el plazo.
   *
   * <p>Las que no terminaron a tiempo devuelven lo que diga {@code siNoLlega}. Siguen corriendo
   * —no hay forma limpia de cortar un pedido HTTP en vuelo—, pero ya nadie las espera.
   */
  private static <T> Map<String, T> aLaVez(
      Map<String, Supplier<T>> tareas, Function<String, T> siNoLlega, int segundos) {
    Map<String, CompletableFuture<T>> enCurso = new LinkedHashMap<>();
    tareas.forEach(
        (nombre, tarea) ->
            enCurso.put(
                nombre,
                CompletableFuture.supplyAsync(tarea, Hilos.ESPERA)
                    .completeOnTimeout(siNoLlega.apply(nombre), segundos, TimeUnit.SECONDS)
                    .exceptionally(error -> siNoLlega.apply(nombre))));
    Map<String, T> resultados = new LinkedHashMap<>();
    enCurso.forEach((nombre, futuro) -> resultados.put(nombre, futuro.join()));
    return resultados;
  }

  /** Lee varias cosas a la vez; lo que no llegó a tiempo o falló queda en null. */
  private Map<String, JsonNode> leerTodo(Map<String, Supplier<String>> consultas) {
    Map<String, Supplier<JsonNode>> lecturas = new LinkedHashMap<>();
    consultas.forEach(
        (clave, consulta) ->
            lecturas.put(
                clave,
                () -> {
                  try {
                    return MAPPER.readTree(consulta.get());
                  } catch (Exception e) {
                    return null;
                  }
                }));
    return aLaVez(lecturas, clave -> null, PLAZO_POR_RONDA_SEGUNDOS);
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
