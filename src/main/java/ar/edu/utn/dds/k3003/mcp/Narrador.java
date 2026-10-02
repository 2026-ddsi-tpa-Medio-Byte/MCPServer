package ar.edu.utn.dds.k3003.mcp;

import static ar.edu.utn.dds.k3003.mcp.Panorama.cantidadInsignias;
import static ar.edu.utn.dds.k3003.mcp.Panorama.numero;
import static ar.edu.utn.dds.k3003.mcp.Panorama.texto;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Convierte dos fotos del sistema —antes y después— en un relato de qué pasó.
 *
 * <p>Está pensado para mostrarse en pantalla mientras se demuestra el sistema: cada operación toca
 * varios módulos, y sin esto hay que ir consultando uno por uno para ver el efecto.
 *
 * <p>Dos criterios de redacción, porque son los que hacen que se entienda:
 *
 * <ul>
 *   <li><b>Lo que no cambió también se cuenta.</b> Que una necesidad siga en 0/10 después de una
 *       donación no es un error, es la regla del sistema, y es justo lo que se malinterpreta.
 *   <li><b>No se afirma lo que no se pudo ver.</b> Si un módulo no contestó, se dice que no
 *       contestó en lugar de dar por hecho que no hubo cambios.
 * </ul>
 */
class Narrador {

  private static final int CASILLEROS = 10;

  private Narrador() {}

  // ── Donación ───────────────────────────────────────────────────────────────

  static String donacion(JsonNode creada, Panorama antes, Panorama despues, String traza) {
    StringBuilder sb = new StringBuilder();
    sb.append(encabezado("Donación registrada", traza, "Donaciones"));

    String nombreProducto = nombreDe(antes.producto, texto(creada, "productoID"));
    sb.append(
        linea(
            "Donaciones",
            "donación nº "
                + texto(creada, "id")
                + ": "
                + numero(creada, "cantidad")
                + " unidades de «"
                + nombreProducto
                + "», estado "
                + estadoDe(creada)
                + "."));
    sb.append(
        linea(
            "Donadores",
            "confirmó que el donador nº "
                + texto(creada, "donadorID")
                + " existe y que está habilitado para donar. Son dos consultas que Donaciones "
                + "hace antes de guardar nada."));
    sb.append(
        linea(
            "Logística",
            efectoEnLogistica(antes, despues, numero(creada, "cantidad"), texto(creada, "id"))));
    sb.append(necesidades(antes, despues, nombreProducto));

    sb.append(
        "\n> Ojo con esto: la necesidad **no** se satisface al donar. Recién cambia cuando "
            + "Logística reporta la entrega del paquete.\n");
    sb.append(pie(antes, despues, "reportar_entrega"));
    return sb.toString();
  }

  /**
   * Qué hizo Logística con la donación.
   *
   * <p>Lo primero que se mira es el paquete, porque es un dato: dice a qué necesidad fue. El
   * stock solo sirve para confirmar cuando la donación se guardó. Deducir la asignación de que el
   * stock no subió sería frágil: Logística trabaja en segundo plano, y si se mira antes de que
   * termine, que no haya subido no significa nada.
   */
  private static String efectoEnLogistica(
      Panorama antes, Panorama despues, int cantidad, String donacionId) {
    Integer diferencia =
        antes.stock == null || despues.stock == null ? null : despues.stock - antes.stock;
    String stock =
        diferencia == null
            ? ""
            : " Stock del producto: " + antes.stock + " → " + despues.stock + ".";

    if (despues.asignacion != null) {
      int asignadas = numero(despues.asignacion, "cantidad");
      String origen = Panorama.alguno(despues.asignacion, "origen");
      String texto =
          "armó el paquete "
              + Panorama.alguno(despues.asignacion, "paqueteid", "paqueteID")
              + " y lo asignó a la necesidad nº "
              + Panorama.alguno(despues.asignacion, "necesidadid", "necesidadID")
              + ("MATCHMAKING".equals(origen) ? ", por matchmaking" : "")
              + ".";
      if (asignadas > 0 && asignadas < cantidad) {
        texto += " Asignó " + asignadas + " de las " + cantidad + " unidades y el resto lo guardó.";
      }
      return texto + stock;
    }
    if (diferencia != null && diferencia >= cantidad) {
      return "no había ninguna necesidad que la pudiera recibir, así que quedó guardada esperando "
          + "a que alguien la pida."
          + stock;
    }
    if (despues.asignacionPendiente) {
      return "todavía la está procesando: lo hace en segundo plano, con un worker, y el paquete "
          + "paq-"
          + donacionId
          + " tarda unos segundos en aparecer."
          + stock;
    }
    return "no se pudo consultar el paquete, así que no se puede afirmar a dónde fue la donación."
        + stock;
  }

  // ── Entrega ────────────────────────────────────────────────────────────────

  static String entrega(Panorama antes, Panorama despues, String traza) {
    StringBuilder sb = new StringBuilder();
    sb.append(encabezado("Entrega reportada", traza, "Logística"));

    // Si antes de la entrega el paquete todavía no figuraba —Logística procesa en segundo plano—,
    // los datos que no cambian (código, origen) se toman de la foto de después.
    JsonNode asignacion = antes.asignacion != null ? antes.asignacion : despues.asignacion;
    String estadoAntes =
        antes.asignacionPendiente ? "todavía no figuraba" : texto(antes.asignacion, "estado");
    String origen = Panorama.alguno(asignacion, "origen");
    sb.append(
        linea(
            "Logística",
            "paquete "
                + Panorama.alguno(asignacion, "paqueteID", "paqueteid")
                + ", asignación "
                + cambio(estadoAntes, texto(despues.asignacion, "estado"))
                + (origen.isBlank()
                    ? "."
                    : ". Se había asignado por "
                        + ("MATCHMAKING".equals(origen)
                            ? "matchmaking, es decir al entrar la donación."
                            : "una solicitud de Donadores, es decir sobre stock que ya estaba."))));
    sb.append(
        linea(
            "Donaciones",
            "donación nº "
                + texto(antes.donacion, "id")
                + ": "
                + cambio(estadoDe(antes.donacion), estadoDe(despues.donacion))
                + "."));

    if (antes.necesidad != null) {
      String progreso =
          barra(antes.necesidad)
              + " "
              + numero(antes.necesidad, "cantidadActual")
              + "/"
              + numero(antes.necesidad, "cantidadObjetivo")
              + " → "
              + barra(despues.necesidad)
              + " "
              + numero(despues.necesidad, "cantidadActual")
              + "/"
              + numero(despues.necesidad, "cantidadObjetivo");
      boolean completa =
          numero(despues.necesidad, "cantidadActual")
              >= numero(despues.necesidad, "cantidadObjetivo");
      sb.append(
          linea(
              "Donadores",
              "necesidad nº "
                  + texto(antes.necesidad, "id")
                  + " «"
                  + texto(antes.necesidad, "descripcion")
                  + "»: "
                  + progreso
                  + (completa ? ". Quedó cubierta." : ". Todavía le falta.")));
    } else if (despues.necesidad != null) {
      // No se supo a qué necesidad iba hasta después de entregar: se muestra cómo quedó, sin
      // inventar de dónde venía.
      sb.append(
          linea(
              "Donadores",
              "necesidad nº "
                  + texto(despues.necesidad, "id")
                  + " «"
                  + texto(despues.necesidad, "descripcion")
                  + "»: quedó en "
                  + barra(despues.necesidad)
                  + " "
                  + numero(despues.necesidad, "cantidadActual")
                  + "/"
                  + numero(despues.necesidad, "cantidadObjetivo")
                  + "."));
    } else {
      sb.append(
          linea(
              "Donadores",
              "no se pudo identificar la necesidad del paquete, así que no se puede mostrar cómo "
                  + "quedó."));
    }

    if (antes.stock != null && despues.stock != null) {
      int diferencia = despues.stock - antes.stock;
      sb.append(
          linea(
              "Logística",
              "stock del producto: "
                  + antes.stock
                  + " → "
                  + despues.stock
                  + (diferencia > 0 ? ". Lo que sobró de la entrega quedó guardado." : ".")));
    }

    sb.append(
        "\n> Este es el momento en que la donación se da por cumplida: hasta acá estaba "
            + "comprometida, no entregada.\n");
    sb.append(pie(antes, despues, "procesar_donador_en_incentivos"));
    return sb.toString();
  }

  // ── Queja ──────────────────────────────────────────────────────────────────

  static String queja(Panorama antes, Panorama despues, String traza) {
    StringBuilder sb = new StringBuilder();
    sb.append(encabezado("Queja registrada", traza, "Donaciones"));

    sb.append(
        linea(
            "Donaciones",
            "donación nº "
                + texto(antes.donacion, "id")
                + ": "
                + cambio(estadoDe(antes.donacion), estadoDe(despues.donacion))
                + "."));

    String estadoAntes = texto(antes.donador, "estado");
    String estadoDespues = texto(despues.donador, "estado");
    String quejas =
        despues.quejas != null && despues.quejas.isArray()
            ? " Lleva " + despues.quejas.size() + " queja" + (despues.quejas.size() == 1 ? "" : "s") + "."
            : "";
    sb.append(
        linea(
            "Donadores",
            "donador nº "
                + texto(antes.donador, "id")
                + ": "
                + cambio(estadoAntes, estadoDespues)
                + "."
                + quejas));

    if (!estadoAntes.equals(estadoDespues)) {
      sb.append(
          linea(
              "Consecuencia",
              "BANEADO".equals(estadoDespues)
                  ? "a partir de ahora este donador **no puede donar**: Donaciones va a rechazar "
                      + "sus donaciones."
                  : "quedó marcado como sospechoso; si siguen las quejas termina baneado."));
    }

    int insigniasAntes = cantidadInsignias(antes.insignias);
    int insigniasDespues = cantidadInsignias(despues.insignias);
    if (antes.insignias != null || despues.insignias != null) {
      sb.append(
          linea(
              "Incentivos",
              "insignias: "
                  + insigniasAntes
                  + " → "
                  + insigniasDespues
                  + (insigniasAntes == insigniasDespues
                      ? ". Se recalculan al procesar al donador, no en el momento."
                      : ".")));
    }

    // Los umbrales son los de Donador.actualizarEstadoSegunQuejas en Donadores. Se dicen en el
    // relato porque es lo que el que mira pregunta; la escalada la sigue decidiendo el módulo.
    sb.append(
        "\n> La escalada es por cantidad: con 5 quejas el donador pasa a SOSPECHOSO y con 10 queda "
            + "BANEADO. Cada queja tiene que ser sobre una donación entregada distinta: una "
            + "donación admite una sola queja.\n");
    sb.append(pie(antes, despues, "procesar_donador_en_incentivos"));
    return sb.toString();
  }

  // ── Necesidad ──────────────────────────────────────────────────────────────

  static String necesidad(JsonNode creada, Panorama antes, Panorama despues, String traza) {
    StringBuilder sb = new StringBuilder();
    sb.append(encabezado("Necesidad registrada", traza, "Donadores"));

    // Lo cubierto sale de la necesidad releída: la respuesta del alta no lo trae, y mostrar el 0
    // de un campo ausente sería afirmar algo que no se vio.
    JsonNode guardada = despues.necesidad;
    String progreso =
        guardada != null
            ? barra(guardada)
                + " "
                + numero(guardada, "cantidadActual")
                + "/"
                + numero(guardada, "cantidadObjetivo")
            : "pide "
                + numero(creada, "cantidadObjetivo")
                + " unidades (no se pudo releer cuánto quedó cubierto)";
    sb.append(
        linea(
            "Donadores",
            "necesidad nº "
                + texto(creada, "id")
                + " «"
                + texto(creada, "descripcion")
                + "»: "
                + progreso
                + ", tipo "
                + texto(creada, "tipo")
                + ", urgencia "
                + numero(creada, "nivelDeUrgencia")
                + "."));
    sb.append(
        linea(
            "Donaciones",
            "validó que el producto nº "
                + texto(creada, "productoSolicitadoID")
                + " exista antes de aceptar la necesidad."));
    sb.append(linea("Logística", stockAlCrear(creada, antes, despues)));

    sb.append(
        "\n> Una necesidad EXTRAORDINARIA acepta que le asignen menos de lo que pide; una "
            + "RECURRENTE solo acepta que la cubran del todo. Lo que se toma del stock al crearla "
            + "se registra solo si Logística confirma la asignación.\n");
    sb.append(pie(antes, despues, "registrar_donacion"));
    return sb.toString();
  }

  /**
   * Si a la necesidad nueva se le asignó stock que ya estaba guardado, y si no, por qué.
   *
   * <p>El dato es lo cubierto, no el stock: Donadores registra lo asignado solo cuando Logística lo
   * confirma. Que el stock no haya bajado no quiere decir que no hubiera nada guardado, que es lo
   * que se decía antes: con stock disponible, una RECURRENTE que no queda cubierta entera no se
   * asigna, y si Logística no confirma la necesidad también se crea en 0.
   */
  private static String stockAlCrear(JsonNode creada, Panorama antes, Panorama despues) {
    String stock =
        antes.stock == null || despues.stock == null
            ? ""
            : "stock del producto: " + antes.stock + " → " + despues.stock + ". ";
    JsonNode guardada = despues.necesidad;

    if (guardada == null) {
      if (antes.stock == null) {
        return "no se pudo leer el stock ni releer la necesidad, así que no se ve si se le asignó "
            + "algo.";
      }
      if (antes.stock == 0) {
        return stock + "No había nada guardado, así que la necesidad queda esperando una donación.";
      }
      return stock
          + "Había unidades guardadas, pero no se pudo releer la necesidad, así que no se puede "
          + "afirmar si se le asignaron.";
    }

    int cubierta = numero(guardada, "cantidadActual");
    int objetivo = numero(guardada, "cantidadObjetivo");
    if (cubierta > 0) {
      return stock
          + "Había donaciones guardadas: Logística confirmó la asignación y la necesidad arrancó con "
          + cubierta
          + " unidades cubiertas, sin esperar una donación nueva.";
    }
    if (antes.stock == null) {
      return "no se pudo leer el stock, así que no se ve si había reservas. La necesidad quedó en 0/"
          + objetivo
          + ".";
    }
    if (antes.stock == 0) {
      return stock + "No había nada guardado, así que la necesidad queda esperando una donación.";
    }
    return stock
        + "Había "
        + antes.stock
        + " unidades guardadas, pero la necesidad quedó en 0/"
        + objetivo
        + ": no se le asignó nada. "
        + causasSinAsignar(
            texto(guardada, "tipo").isBlank() ? texto(creada, "tipo") : texto(guardada, "tipo"),
            antes.stock,
            objetivo);
  }

  /**
   * Por qué una necesidad con stock disponible pudo quedar en 0.
   *
   * <p>No se elige una sola causa: desde afuera no se ve si Donadores le pidió la asignación a
   * Logística ni qué le contestó. Lo que sí se puede es descartar la regla de las RECURRENTE cuando
   * no aplica, para no mandar a buscar el problema donde no está.
   */
  private static String causasSinAsignar(String tipo, int stock, int objetivo) {
    String logistica =
        "que Logística no haya confirmado la asignación o no haya informado el stock a tiempo: en "
            + "ese caso Donadores crea la necesidad en 0 para no contar unidades que nadie apartó";
    String regla =
        "una RECURRENTE solo se asigna si el stock la cubre entera, y si no ni se le pide nada a "
            + "Logística";
    if ("RECURRENTE".equalsIgnoreCase(tipo) && stock < objetivo) {
      return "Las causas posibles son dos: que el stock no alcance para las "
          + objetivo
          + " unidades que pide —"
          + regla
          + "—, o "
          + logistica
          + ".";
    }
    if (tipo.isBlank()) {
      return "Las causas posibles son dos: que sea RECURRENTE y el stock no alcance para cubrirla "
          + "—"
          + regla
          + "—, o "
          + logistica
          + ".";
    }
    String reglaQueNoAplica =
        "RECURRENTE".equalsIgnoreCase(tipo)
            ? "el stock alcanzaba para cubrirla entera"
            : "es EXTRAORDINARIA y acepta que le asignen una parte";
    return "La regla del tipo no lo impedía (" + reglaQueNoAplica + "), así que lo más probable es "
        + logistica
        + ".";
  }

  // ── Incentivos ─────────────────────────────────────────────────────────────

  static String procesado(Panorama antes, Panorama despues, String traza) {
    StringBuilder sb = new StringBuilder();
    sb.append(encabezado("Donador procesado en Incentivos", traza, "Incentivos"));

    int insigniasAntes = cantidadInsignias(antes.insignias);
    int insigniasDespues = cantidadInsignias(despues.insignias);
    sb.append(
        linea(
            "Incentivos",
            "insignias: "
                + insigniasAntes
                + " → "
                + insigniasDespues
                + (insigniasDespues > insigniasAntes
                    ? ". Cumplió la misión y ganó una."
                    : insigniasDespues < insigniasAntes
                        ? ". Perdió progreso: algo dejó de cumplirse, normalmente por una queja."
                        : ". Todavía no cumple lo que pide la misión.")));
    sb.append(
        linea(
            "Incentivos",
            "misión: "
                + cambio(descripcionMision(antes.mision), descripcionMision(despues.mision))
                + "."));
    sb.append(
        linea(
            "Donadores",
            "categoría: "
                + cambio(texto(antes.donador, "categoria"), texto(despues.donador, "categoria"))
                + "."));

    sb.append(
        "\n> Normalmente esto lo dispara un proceso automático cada cierto tiempo; acá se forzó "
            + "para poder mostrarlo.\n");
    sb.append(pie(antes, despues, "consultar_estadisticas_donador"));
    return sb.toString();
  }

  // ── Piezas comunes ─────────────────────────────────────────────────────────

  /**
   * La nota sobre la traza depende de por dónde entra la operación.
   *
   * <p>Solo Donaciones y Donadores leen la traza, la escriben en sus logs y la reenvían al módulo
   * siguiente. Logística e Incentivos todavía no: si la operación entra por ellos, la traza se
   * pierde en el primer salto. Decir que se puede seguir en Datadog sería prometer algo que no
   * está.
   */
  private static String encabezado(String titulo, String traza, String entraPor) {
    String nota =
        switch (entraPor) {
          case "Donaciones", "Donadores" ->
              "Donaciones y Donadores escriben esta traza en cada línea de log: filtrando por ella "
                  + "en Datadog se ve qué hizo cada uno en esta operación.";
          default ->
              "Esta operación entra por "
                  + entraPor
                  + ", que todavía no propaga la traza: en Datadog no se puede seguir de punta a "
                  + "punta.";
        };
    return "**" + titulo + "** · traza `" + traza + "`\n\n_(" + nota + ")_\n\n";
  }

  private static String linea(String modulo, String texto) {
    return "- **" + modulo + "** — " + texto + "\n";
  }

  /** Une los módulos que no contestaron en las dos fotos, para avisarlo una sola vez. */
  private static String pie(Panorama antes, Panorama despues, String siguiente) {
    Set<String> caidos = new LinkedHashSet<>(antes.sinRespuesta);
    caidos.addAll(despues.sinRespuesta);
    String aviso =
        caidos.isEmpty()
            ? ""
            : "\n⚠️ Sin respuesta de: "
                + String.join(", ", caidos)
                + ". Esa parte del relato quedó incompleta.\n";
    return aviso + "\nSiguiente paso sugerido: `" + siguiente + "`.\n";
  }

  private static String cambio(String antes, String despues) {
    String a = antes == null || antes.isBlank() ? "—" : antes;
    String d = despues == null || despues.isBlank() ? "—" : despues;
    return a.equals(d) ? "sigue en " + a : a + " → " + d;
  }

  private static String estadoDe(JsonNode donacion) {
    String estado = texto(donacion, "estado");
    return estado.isBlank() ? "—" : estado;
  }

  private static String nombreDe(JsonNode producto, String idPorDefecto) {
    String nombre = texto(producto, "nombre");
    return nombre.isBlank() ? "producto nº " + idPorDefecto : nombre;
  }

  private static String descripcionMision(JsonNode mision) {
    if (mision == null) {
      return "";
    }
    String nombre = texto(mision, "nombre");
    return nombre.isBlank() ? texto(mision, "id") : nombre;
  }

  private static String necesidades(Panorama antes, Panorama despues, String nombreProducto) {
    if (despues.necesidadesDelProducto.isEmpty()) {
      return linea(
          "Donadores",
          "no hay ninguna necesidad pendiente de «" + nombreProducto + "» para asignarle esto.");
    }
    StringBuilder sb = new StringBuilder();
    int mostradas = Math.min(3, despues.necesidadesDelProducto.size());
    for (int i = 0; i < mostradas; i++) {
      JsonNode n = despues.necesidadesDelProducto.get(i);
      JsonNode previa = buscarPorId(antes.necesidadesDelProducto, texto(n, "id"));
      boolean cambio =
          previa != null && numero(previa, "cantidadActual") != numero(n, "cantidadActual");
      sb.append(
          linea(
              "Donadores",
              "necesidad nº "
                  + texto(n, "id")
                  + " «"
                  + texto(n, "descripcion")
                  + "» "
                  + barra(n)
                  + " "
                  + numero(n, "cantidadActual")
                  + "/"
                  + numero(n, "cantidadObjetivo")
                  + (cambio ? ", cambió con esta operación." : ", sin cambios.")));
    }
    if (despues.necesidadesDelProducto.size() > mostradas) {
      sb.append(
          linea(
              "Donadores",
              "y " + (despues.necesidadesDelProducto.size() - mostradas) + " necesidad(es) más."));
    }
    return sb.toString();
  }

  private static JsonNode buscarPorId(List<JsonNode> lista, String id) {
    for (JsonNode n : lista) {
      if (texto(n, "id").equals(id)) {
        return n;
      }
    }
    return null;
  }

  /** Una barra de diez casilleros se lee de un vistazo mejor que un porcentaje. */
  private static String barra(JsonNode necesidad) {
    int objetivo = numero(necesidad, "cantidadObjetivo");
    int actual = numero(necesidad, "cantidadActual");
    if (objetivo <= 0) {
      return "░".repeat(CASILLEROS);
    }
    int llenos = Math.min(CASILLEROS, (int) Math.round((double) actual / objetivo * CASILLEROS));
    return "▓".repeat(llenos) + "░".repeat(CASILLEROS - llenos);
  }
}
