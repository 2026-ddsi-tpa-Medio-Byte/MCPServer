package ar.edu.utn.dds.k3003.mcp;

import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/**
 * Herramientas que modifican el estado del sistema.
 *
 * <p>Ninguna valida nada por su cuenta: las reglas —cantidad mayor a cero, producto existente,
 * donador habilitado, entidad válida— viven en los módulos y ahí se quedan. Cuando un módulo
 * rechaza algo, el mensaje sube tal cual para que el modelo pueda explicar el motivo real en
 * lugar de inventar uno.
 */
@Service
public class OperacionTools {

  private static final com.fasterxml.jackson.databind.ObjectMapper MAPPER =
      new com.fasterxml.jackson.databind.ObjectMapper();

  private final DonaTrackApi api;
  private final String depositoPorDefecto;
  private final SesionMcp sesion;

  /**
   * Si está en falso, las operaciones devuelven la respuesta cruda del módulo.
   *
   * <p>Sirve para los tests que verifican qué se le manda a cada módulo: sin esto habría que
   * simular también las consultas del relato, y el test dejaría de hablar de lo que quiere probar.
   */
  private final boolean narrar;

  @org.springframework.beans.factory.annotation.Autowired
  public OperacionTools(
      DonaTrackApi api,
      @Value("${donatrack.deposito-default:DEP-UTN-01}") String depositoPorDefecto,
      SesionMcp sesion) {
    this(api, depositoPorDefecto, sesion, true);
  }

  public OperacionTools(
      DonaTrackApi api, String depositoPorDefecto, SesionMcp sesion, boolean narrar) {
    this.api = api;
    this.depositoPorDefecto = depositoPorDefecto;
    this.sesion = sesion;
    this.narrar = narrar;
  }

  public OperacionTools(DonaTrackApi api, String depositoPorDefecto) {
    this(api, depositoPorDefecto, new SesionMcp(), true);
  }

  // ── Cómo se cuenta cada operación ──────────────────────────────────────────

  /** Lo que sabe hacer un relato: mirar la respuesta y las dos fotos, y escribir qué pasó. */
  @FunctionalInterface
  interface Relato {
    String contar(
        com.fasterxml.jackson.databind.JsonNode respuesta,
        Panorama antes,
        Panorama despues,
        String traza);
  }

  /**
   * Saca una foto, ejecuta, saca otra y cuenta la diferencia.
   *
   * <p>Dos cuidados que importan: la traza se abre solo alrededor de la operación, para que en
   * Datadog quede el recorrido del negocio y no las consultas del relato; y si armar el relato
   * falla, se devuelve igual la respuesta del módulo, porque la operación ya ocurrió y decir lo
   * contrario sería mentir.
   */
  private String conRelato(
      java.util.function.Supplier<Panorama> foto,
      java.util.function.Supplier<String> operacion,
      Relato relato) {
    return conRelato(foto, respuesta -> foto.get(), operacion, relato);
  }

  /**
   * Variante para cuando la foto de después necesita algo que recién se sabe con la respuesta,
   * como el número de la donación que se acaba de crear.
   */
  private String conRelato(
      java.util.function.Supplier<Panorama> fotoAntes,
      java.util.function.Function<com.fasterxml.jackson.databind.JsonNode, Panorama> fotoDespues,
      java.util.function.Supplier<String> operacion,
      Relato relato) {
    if (!narrar) {
      return operacion.get();
    }
    Panorama antes = fotoAntes.get();
    String respuesta;
    String traza = api.nuevaTraza();
    try {
      respuesta = operacion.get();
    } finally {
      api.cerrarTraza();
    }
    try {
      com.fasterxml.jackson.databind.JsonNode cuerpo = parsear(respuesta);
      return relato.contar(cuerpo, antes, fotoDespues.apply(cuerpo), traza);
    } catch (Exception e) {
      return respuesta + "\n\n_(No se pudo armar el resumen del impacto: " + e.getMessage() + ")_";
    }
  }

  /** Devuelve null si la respuesta no es JSON: hay endpoints que contestan texto plano. */
  private static com.fasterxml.jackson.databind.JsonNode parsear(String respuesta) {
    try {
      return MAPPER.readTree(respuesta);
    } catch (Exception e) {
      return null;
    }
  }

  // ── Registrar cosas ────────────────────────────────────────────────────────

  @Tool(
      name = "registrar_donacion",
      description =
          "Registra una donación. Es la operación central del sistema: valida el producto, "
              + "consulta a Donadores si esa persona puede donar, y avisa a Logística para que la "
              + "asigne a una necesidad. Hace falta saber el número del donador y el del "
              + "producto: si no se tienen, consultarlos primero. Devuelve un resumen del efecto "
              + "en cada módulo, ya redactado: mostrarlo tal cual, sin resumirlo.")
  public String registrarDonacion(
      @ToolParam(description = "Número del donador que dona") String donadorId,
      @ToolParam(description = "Número del producto que se dona") String productoId,
      @ToolParam(description = "Cuántas unidades. Tiene que ser mayor a cero.") int cantidad,
      @ToolParam(description = "Descripción de la donación en pocas palabras") String descripcion,
      @ToolParam(required = false, description = "Depósito. Si se omite se usa el habitual.")
          String depositoId) {
    sesion.requerirLogin("registrar una donación");
    String dId = (donadorId != null && !donadorId.isBlank()) ? donadorId.trim() : sesion.getUsuario();
    String deposito =
        (depositoId == null || depositoId.isBlank()) ? depositoPorDefecto : depositoId.trim();
    String producto = productoId.trim();
    return conRelato(
        () -> Panorama.deDonacion(api, producto),
        // Después, además de lo mismo que antes, se busca el paquete que armó Logística: es la
        // única forma de saber con certeza a qué necesidad fue la donación.
        creada ->
            Panorama.deDonacion(api, producto)
                .conAsignacionDe(Panorama.texto(creada, "id")),
        () ->
            api.postDonaciones(
                "/donaciones",
                DonaTrackApi.cuerpo(
                    "donadorID", dId,
                    "depositoID", deposito,
                    "descripcion", descripcion,
                    "productoID", producto,
                    "cantidad", cantidad)),
        Narrador::donacion);
  }

  @Tool(
      name = "registrar_necesidad",
      description =
          "Registra lo que necesita una entidad. Valida contra Donaciones que el producto exista "
              + "y le consulta el stock a Logística: si ya hay unidades disponibles, se asignan "
              + "en el momento sin esperar una donación nueva, pero solo si Logística confirma la "
              + "asignación. El tipo puede ser EXTRAORDINARIA (acepta que le asignen menos de lo "
              + "pedido) o RECURRENTE (solo acepta que la cubran del todo: si el stock no alcanza "
              + "para cubrirla entera, queda en 0). Devuelve un resumen ya redactado que dice si "
              + "se le asignó stock: mostrarlo tal cual.")
  public String registrarNecesidad(
      @ToolParam(description = "Número de la entidad que necesita") String entidadId,
      @ToolParam(description = "Número del producto que se necesita") String productoId,
      @ToolParam(description = "Cuántas unidades hacen falta. Mayor a cero.") int cantidadObjetivo,
      @ToolParam(description = "Qué se necesita y para qué, en pocas palabras") String descripcion,
      @ToolParam(description = "Urgencia del 1 al 10") int urgencia,
      @ToolParam(description = "EXTRAORDINARIA o RECURRENTE") String tipo) {
    sesion.requerirAdmin("registrar una necesidad");
    String producto = productoId.trim();
    return conRelato(
        () -> Panorama.deNecesidad(api, producto),
        // Después se relee la necesidad creada: es lo único que dice si se le asignó stock.
        creada -> Panorama.despuesDeNecesidad(api, producto, Panorama.texto(creada, "id")),
        () ->
            api.postDonadores(
                "/necesidades",
                DonaTrackApi.cuerpo(
                    "entidadID", entidadId.trim(),
                    "nivelDeUrgencia", urgencia,
                    "descripcion", descripcion,
                    "cantidadObjetivo", cantidadObjetivo,
                    "productoSolicitadoID", producto,
                    "tipo", tipo.toUpperCase().trim())),
        Narrador::necesidad);
  }

  @Tool(
      name = "registrar_queja",
      description =
          "Registra una queja sobre una donación. Requiere ADMIN: es un reclamo contra un donador y "
              + "no lo hace el propio donador. Solo se puede quejar de una donación "
              + "ENTREGADA (estado ACEPTADA): si todavía está INGRESADA, primero hay que reportar "
              + "su entrega con 'reportar_entrega'; si ya está CONQUEJA, ya tiene su queja y no "
              + "admite otra. En esos casos Donaciones la rechaza y su mensaje dice el estado "
              + "actual: transmitirlo tal cual. Tiene dos efectos: la donación pasa a CONQUEJA y "
              + "el donador acumula la queja; con 5 pasa a SOSPECHOSO y con 10 queda BANEADO, así "
              + "que para llegar ahí hace falta una donación entregada por cada queja. También "
              + "puede hacerle perder insignias que hubiera ganado.")
  public String registrarQueja(
      @ToolParam(
              description =
                  "Número de la donación sobre la que se reclama. Tiene que estar entregada "
                      + "(ACEPTADA).")
          String donacionId,
      @ToolParam(description = "Qué pasó con esa donación") String descripcion) {
    sesion.requerirAdmin("registrar una queja");
    String donacion = donacionId.trim();
    return conRelato(
        () -> Panorama.deQueja(api, donacion, null),
        // La API espera el texto plano entre comillas, no un objeto.
        () -> api.postDonaciones("/donaciones/" + donacion + "/quejas", descripcion),
        (respuesta, antes, despues, traza) -> Narrador.queja(antes, despues, traza));
  }

  // ── Altas de precondiciones ────────────────────────────────────────────────

  @Tool(
      name = "crear_donador",
      description =
          "Da de alta un donador nuevo. Devuelve el número asignado, que es el que hace falta "
              + "después para registrar donaciones a su nombre.")
  public String crearDonador(
      @ToolParam(description = "Nombre") String nombre,
      @ToolParam(description = "Apellido") String apellido,
      @ToolParam(description = "Edad") int edad,
      @ToolParam(description = "Email") String email,
      @ToolParam(description = "Número de documento") String documento,
      @ToolParam(description = "Domicilio") String domicilio) {
    sesion.requerirAdmin("dar de alta un donador");
    return api.postDonadores(
        "/donadores",
        DonaTrackApi.cuerpo(
            "nombre", nombre,
            "apellido", apellido,
            "edad", edad,
            "email", email,
            "nroDocumento", documento,
            "domicilio", domicilio));
  }

  @Tool(
      name = "crear_entidad",
      description =
          "Da de alta una entidad beneficiaria: un comedor, un hogar, una organización. Devuelve "
              + "el número asignado, que hace falta para cargarle necesidades.")
  public String crearEntidad(
      @ToolParam(description = "Razón social o nombre de la organización") String razonSocial,
      @ToolParam(description = "Domicilio") String domicilio,
      @ToolParam(description = "Teléfono") String telefono,
      @ToolParam(description = "Correo de contacto") String correo) {
    sesion.requerirAdmin("dar de alta una entidad");
    return api.postDonadores(
        "/entidades",
        DonaTrackApi.cuerpo(
            "razonSocial", razonSocial,
            "domicilio", domicilio,
            "telefono", telefono,
            "correo", correo));
  }

  @Tool(
      name = "crear_producto",
      description =
          "Da de alta un producto donable. Necesita un identificador ya creado. Ojo con dos "
              + "reglas: si el identificador es de código de barras, la descripción tiene que "
              + "tener al menos tres palabras; si es QR, el nombre tiene que tener una cantidad "
              + "par de letras. Además no puede repetirse un nombre ya usado.")
  public String crearProducto(
      @ToolParam(description = "Nombre del producto") String nombre,
      @ToolParam(description = "Descripción") String descripcion,
      @ToolParam(description = "Categoría, por ejemplo alimentos o abrigo") String categoria,
      @ToolParam(description = "Número del identificador a usar") String identificadorId) {
    sesion.requerirAdmin("dar de alta un producto");
    return api.postDonaciones(
        "/productos",
        DonaTrackApi.cuerpo(
            "nombre", nombre,
            "descripcion", descripcion,
            "categoriaID", categoria,
            "identificadorID", identificadorId.trim()));
  }

  @Tool(
      name = "crear_identificador",
      description =
          "Crea un identificador, que es lo que hace falta antes de poder crear un producto. El "
              + "tipo puede ser CODIGODEBARRAS o QR, y de eso dependen las reglas que después "
              + "tiene que cumplir el producto.")
  public String crearIdentificador(
      @ToolParam(description = "CODIGODEBARRAS o QR") String tipo,
      @ToolParam(description = "Descripción del identificador") String descripcion) {
    sesion.requerirAdmin("crear un identificador");
    return api.postDonaciones(
        "/identificadores",
        DonaTrackApi.cuerpo("tipo", tipo.toUpperCase().trim(), "descripcion", descripcion));
  }

  // ── Modificar ──────────────────────────────────────────────────────────────

  @Tool(
      name = "modificar_necesidad",
      description =
          "Cambia los datos de una necesidad existente: la urgencia, la cantidad que hace falta o "
              + "la descripción.")
  public String modificarNecesidad(
      @ToolParam(description = "Número de la necesidad") String necesidadId,
      @ToolParam(description = "Número del producto") String productoId,
      @ToolParam(description = "Nueva cantidad objetivo") int cantidadObjetivo,
      @ToolParam(description = "Nueva descripción") String descripcion,
      @ToolParam(description = "Nueva urgencia del 1 al 10") int urgencia,
      @ToolParam(description = "EXTRAORDINARIA o RECURRENTE") String tipo) {
    sesion.requerirAdmin("modificar una necesidad");
    return api.putDonadores(
        "/necesidades/" + necesidadId.trim(),
        DonaTrackApi.cuerpo(
            "nivelDeUrgencia", urgencia,
            "descripcion", descripcion,
            "cantidadObjetivo", cantidadObjetivo,
            "productoSolicitadoID", productoId.trim(),
            "tipo", tipo.toUpperCase().trim()));
  }

  @Tool(
      name = "modificar_entidad",
      description = "Cambia los datos de contacto de una entidad beneficiaria.")
  public String modificarEntidad(
      @ToolParam(description = "Número de la entidad") String entidadId,
      @ToolParam(description = "Razón social") String razonSocial,
      @ToolParam(description = "Domicilio") String domicilio,
      @ToolParam(description = "Teléfono") String telefono,
      @ToolParam(description = "Correo") String correo) {
    sesion.requerirAdmin("modificar una entidad");
    return api.putDonadores(
        "/entidades/" + entidadId.trim(),
        DonaTrackApi.cuerpo(
            "razonSocial", razonSocial,
            "domicilio", domicilio,
            "telefono", telefono,
            "correo", correo));
  }

  @Tool(
      name = "cambiar_estado_donador",
      description =
          "Cambia a mano el estado de un donador: VERIFICADO, SOSPECHOSO o BANEADO. Normalmente "
              + "el estado lo maneja el sistema según las quejas que acumula, pero para mostrar "
              + "que un donador baneado no puede donar conviene forzarlo en vez de cargar diez "
              + "quejas, que exigen diez donaciones entregadas.")
  public String cambiarEstadoDonador(
      @ToolParam(description = "Número del donador") String donadorId,
      @ToolParam(description = "VERIFICADO, SOSPECHOSO o BANEADO") String estado) {
    sesion.requerirAdmin("cambiar el estado de un donador");
    return api.patchDonadores(
        "/donadores/" + donadorId.trim() + "/estado",
        DonaTrackApi.cuerpo("estado", estado.toUpperCase().trim()));
  }

  @Tool(
      name = "cambiar_categoria_donador",
      description =
          "Cambia a mano la categoría de un donador. La categoría normalmente la otorga "
              + "Incentivos al procesarlo; esto sirve para dejar un donador en una categoría "
              + "determinada antes de mostrar un flujo.")
  public String cambiarCategoriaDonador(
      @ToolParam(description = "Número del donador") String donadorId,
      @ToolParam(description = "Categoría a asignar") String categoria) {
    sesion.requerirAdmin("cambiar la categoría de un donador");
    return api.patchDonadores(
        "/donadores/" + donadorId.trim() + "/categoria",
        DonaTrackApi.cuerpo("categoria", categoria.trim()));
  }

  @Tool(
      name = "eliminar_necesidad",
      description =
          "Borra una necesidad. Usar solo si el usuario lo pide de forma explícita: no se puede "
              + "deshacer.")
  public String eliminarNecesidad(
      @ToolParam(description = "Número de la necesidad a borrar") String necesidadId) {
    sesion.requerirAdmin("eliminar una necesidad");
    api.deleteDonadores("/necesidades/" + necesidadId.trim());
    return "Necesidad " + necesidadId + " eliminada.";
  }

  @Tool(
      name = "procesar_donador_en_incentivos",
      description =
          "Le pide a Incentivos que evalúe a un donador contra la misión que tiene asignada. Si "
              + "cumple, le otorga la insignia y lo sube de categoría; si dejó de cumplir —por "
              + "ejemplo porque le pusieron quejas— le quita el progreso. Normalmente esto lo "
              + "hace un proceso automático, pero se puede forzar.")
  public String procesarDonador(
      @ToolParam(description = "Número del donador a procesar") String donadorId) {
    sesion.requerirLogin("procesar un donador en incentivos");
    String donador = donadorId.trim();
    return conRelato(
        () -> Panorama.deIncentivos(api, donador),
        () -> api.postIncentivos("/donadores/" + donador + "/procesar", null),
        (respuesta, antes, despues, traza) -> Narrador.procesado(antes, despues, traza));
  }

  @Tool(
      name = "reportar_entrega",
      description =
          "Reporta que el paquete de una donación llegó a destino. Requiere ADMIN. Solo hace "
              + "falta saber cuál es el paquete: la donación, el producto y la cantidad Logística "
              + "los saca de la asignación que ya tiene guardada, así que no hay que pedírselos "
              + "al usuario. Si en vez del paquete se tiene el número de la donación, alcanza con "
              + "eso. Recién al reportar la entrega la donación pasa a ACEPTADA y la necesidad "
              + "suma lo entregado.")
  public String reportarEntrega(
      @ToolParam(
              required = false,
              description =
                  "Código del paquete, por ejemplo paq-12. Es el único dato que usa Logística.")
          String paqueteId,
      @ToolParam(
              required = false,
              description =
                  "Número de la donación, solo si no se tiene el código del paquete: Logística "
                      + "nombra cada paquete paq- más el número de la donación.")
          String donacionId) {
    sesion.requerirAdmin("reportar una entrega");
    String paquete = paqueteDe(paqueteId, donacionId);
    return conRelato(
        // La donación se deduce del paquete y no del parámetro: así el relato cuenta siempre lo
        // del paquete que se reportó, aunque vinieran los dos y no coincidieran.
        () -> Panorama.deEntrega(api, paquete, donacionDe(paquete)),
        // Logística solo lee el paquete: lo demás lo saca de la asignación guardada.
        () ->
            api.postLogistica(
                "/api/asignaciones/reportar-entrega", DonaTrackApi.cuerpo("paqueteid", paquete)),
        (respuesta, antes, despues, traza) -> Narrador.entrega(antes, despues, traza));
  }

  /**
   * Logística nombra cada paquete "paq-" + el id de la donación que lo originó. Aceptar la
   * donación evita una traba en la demostración: quien reporta la entrega suele tener a mano la
   * donación, no el paquete, y no hay forma de listarlos.
   */
  private static String paqueteDe(String paqueteId, String donacionId) {
    if (paqueteId != null && !paqueteId.isBlank()) {
      return paqueteId.trim();
    }
    if (donacionId != null && !donacionId.isBlank()) {
      return "paq-" + donacionId.trim();
    }
    // No es una regla de negocio: sin ninguno de los dos no hay a quién reportar. Avisarlo antes
    // de llamar deja que el modelo pida el dato que falta en vez de mostrar un error del módulo.
    throw new IllegalArgumentException(
        "Para reportar una entrega hace falta el código del paquete o el número de la donación.");
  }

  /** El camino inverso, solo para el relato. Vacío si el paquete no sigue la convención. */
  private static String donacionDe(String paquete) {
    return paquete.startsWith("paq-") ? paquete.substring("paq-".length()) : "";
  }

  // ── Depósitos de Logística ─────────────────────────────────────────────────

  /**
   * Lo que espera la lectura previa a modificar un depósito.
   *
   * <p>Menos que una consulta común, que con su reintento llega a 50 segundos: sumada a la
   * escritura, que llega a 35, la herramienta pasaría el corte de 60 de Claude.
   */
  private static final int PACIENCIA_LECTURA_SEGUNDOS = 20;

  @Tool(
      name = "crear_deposito",
      description =
          "Da de alta un depósito en Logística, donde se guarda el stock de las donaciones que "
              + "no se asignaron a ninguna necesidad. Requiere ADMIN. El id es opcional: si no se "
              + "indica, Logística genera uno con el formato DEP-UTN-XX. El algoritmo también: si "
              + "no se indica, el depósito queda sin algoritmo y Logística aplica SUBATENDIDOS al "
              + "procesar donaciones. Devuelve el depósito creado, con el id que le quedó.")
  public String crearDeposito(
      @ToolParam(description = "Nombre del depósito, por ejemplo Deposito Central UTN")
          String nombre,
      @ToolParam(description = "Dirección, por ejemplo Av. Medrano 951") String direccion,
      @ToolParam(description = "Cuántas unidades entran en total, por ejemplo 5000")
          int capacidadMaxima,
      @ToolParam(
              required = false,
              description = "Id con el formato DEP-UTN-01. Si se omite lo genera Logística.")
          String depositoId,
      @ToolParam(
              required = false,
              description =
                  "SUBATENDIDOS (prioriza la necesidad con menor porcentaje cubierto) o PRIOSCORE "
                      + "(prioriza por urgencia sobre progreso). Si se omite, no se configura.")
          String algoritmo) {
    sesion.requerirAdmin("crear un depósito");
    // El stock no se manda: arranca vacío y lo llenan las donaciones.
    return api.postLogistica(
        "/api/depositos",
        DonaTrackApi.cuerpo(
            "nombre", nombre,
            "depositoid", textoONulo(depositoId),
            "direccion", direccion,
            "capacidadMaxima", capacidadMaxima,
            "algoritmo", algoritmoDe(algoritmo)));
  }

  @Tool(
      name = "modificar_deposito",
      description =
          "Cambia los datos de un depósito de Logística: nombre, dirección, capacidad máxima o "
              + "algoritmo. Requiere ADMIN. Alcanza con indicar lo que cambia: lo demás se "
              + "completa con los valores actuales del depósito. El stock no se modifica por acá: "
              + "lo mueven las donaciones y las entregas. Devuelve la respuesta de Logística.")
  public String modificarDeposito(
      @ToolParam(description = "Id del depósito, por ejemplo DEP-UTN-01") String depositoId,
      @ToolParam(required = false, description = "Nuevo nombre. Vacío para dejar el actual.")
          String nombre,
      @ToolParam(required = false, description = "Nueva dirección. Vacío para dejar la actual.")
          String direccion,
      @ToolParam(
              required = false,
              description = "Nueva capacidad en unidades, por ejemplo 8000. Vacío para dejar la actual.")
          Integer capacidadMaxima,
      @ToolParam(
              required = false,
              description = "SUBATENDIDOS o PRIOSCORE. Vacío para dejar el actual.")
          String algoritmo) {
    sesion.requerirAdmin("modificar un depósito");
    String id = depositoId.trim();
    String nuevoNombre = textoONulo(nombre);
    String nuevaDireccion = textoONulo(direccion);
    Integer nuevaCapacidad = capacidadMaxima;
    String nuevoAlgoritmo = algoritmoDe(algoritmo);
    if (nuevoNombre == null
        && nuevaDireccion == null
        && nuevaCapacidad == null
        && nuevoAlgoritmo == null) {
      throw new IllegalArgumentException(
          "Para modificar el depósito hace falta indicar al menos un dato que cambie.");
    }

    // Logística pide el depósito entero en cada PUT. Para no obligar a repetir lo que no cambia,
    // se completa con lo que tiene ahora; si vino todo, no hace falta leerlo.
    if (nuevoNombre == null
        || nuevaDireccion == null
        || nuevaCapacidad == null
        || nuevoAlgoritmo == null) {
      com.fasterxml.jackson.databind.JsonNode actual =
          parsear(leerConPaciencia(() -> api.getLogistica("/api/depositos/" + id)));
      nuevoNombre = nuevoNombre != null ? nuevoNombre : campo(actual, "nombre");
      nuevaDireccion = nuevaDireccion != null ? nuevaDireccion : campo(actual, "direccion");
      if (nuevaCapacidad == null && actual != null && actual.path("capacidadMaxima").isNumber()) {
        nuevaCapacidad = actual.path("capacidadMaxima").asInt();
      }
      nuevoAlgoritmo = nuevoAlgoritmo != null ? nuevoAlgoritmo : campo(actual, "algoritmo");
    }

    return api.putLogistica(
        "/api/depositos/" + id,
        DonaTrackApi.cuerpo(
            "nombre", nuevoNombre,
            "direccion", nuevaDireccion,
            "capacidadMaxima", nuevaCapacidad,
            "algoritmo", nuevoAlgoritmo));
  }

  @Tool(
      name = "eliminar_deposito",
      description =
          "Borra un depósito de Logística. Requiere ADMIN. Usar solo si el usuario lo pide de "
              + "forma explícita: no se puede deshacer. Logística no deja borrar un depósito que "
              + "todavía tiene stock. Si es el depósito al que donan por defecto el bot y el MCP, "
              + "las donaciones van a fallar hasta volver a crearlo.")
  public String eliminarDeposito(
      @ToolParam(description = "Id del depósito a borrar, por ejemplo DEP-UTN-01")
          String depositoId) {
    sesion.requerirAdmin("eliminar un depósito");
    String id = depositoId.trim();
    api.deleteLogistica("/api/depositos/" + id);
    return "Depósito " + id + " eliminado.";
  }

  @Tool(
      name = "configurar_algoritmo_deposito",
      description =
          "Cambia el algoritmo con el que un depósito de Logística decide a qué necesidad "
              + "asignar cada donación que recibe. Requiere ADMIN. SUBATENDIDOS prioriza la "
              + "necesidad con menor porcentaje cubierto; PRIOSCORE prioriza por urgencia sobre "
              + "el progreso. Devuelve la respuesta de Logística.")
  public String configurarAlgoritmoDeposito(
      @ToolParam(description = "Id del depósito, por ejemplo DEP-UTN-01") String depositoId,
      @ToolParam(description = "SUBATENDIDOS o PRIOSCORE") String algoritmo) {
    sesion.requerirAdmin("cambiar el algoritmo de un depósito");
    String valor = algoritmoDe(algoritmo);
    if (valor == null) {
      throw new IllegalArgumentException(
          "Hace falta indicar el algoritmo: SUBATENDIDOS o PRIOSCORE.");
    }
    // Logística lo espera como parámetro de la URL, no en el cuerpo.
    return api.putLogistica(
        "/api/depositos/" + depositoId.trim() + "/algoritmo?algoritmo=" + valor, null);
  }

  /**
   * Traduce lo que se escribió al nombre que esperan los endpoints /api de Logística.
   *
   * <p>Es formato, no regla de negocio: «sub atendidos» y «prioridad por score» son los mismos
   * algoritmos escritos de otra forma. En /api solo valen SUBATENDIDOS y PRIOSCORE; los nombres
   * SUB_ATENDIDOS y PRIORIDAD_POR_SCORE son del endpoint de integración /depositos, el que usa la
   * seed. Lo que no se reconoce se manda tal cual para que Logística lo rechace con su motivo.
   */
  private static String algoritmoDe(String valor) {
    if (valor == null || valor.isBlank()) {
      return null;
    }
    return switch (valor.toUpperCase().replaceAll("[^A-Z]", "")) {
      case "SUBATENDIDOS", "SUBATENDIDO" -> "SUBATENDIDOS";
      case "PRIOSCORE", "PRIORIDADPORSCORE", "PRIORIDADSCORE" -> "PRIOSCORE";
      default -> valor.trim().toUpperCase();
    };
  }

  private static String textoONulo(String valor) {
    return valor == null || valor.isBlank() ? null : valor.trim();
  }

  /** Null si falta o viene en null: asText() de un null de JSON devolvería la palabra "null". */
  private static String campo(com.fasterxml.jackson.databind.JsonNode nodo, String nombre) {
    return nodo != null && nodo.hasNonNull(nombre) ? nodo.get(nombre).asText() : null;
  }

  /**
   * Espera la consulta, pero no más que el plazo de lectura.
   *
   * <p>Si el módulo contesta con un error, sube ese error tal como lo tradujo DonaTrackApi: lo
   * único que se saca es el envoltorio del futuro.
   */
  private static String leerConPaciencia(java.util.function.Supplier<String> consulta) {
    try {
      return java.util.concurrent.CompletableFuture.supplyAsync(consulta, Hilos.ESPERA)
          .get(PACIENCIA_LECTURA_SEGUNDOS, java.util.concurrent.TimeUnit.SECONDS);
    } catch (java.util.concurrent.ExecutionException e) {
      if (e.getCause() instanceof RuntimeException causa) {
        throw causa;
      }
      throw new IllegalStateException(e.getCause());
    } catch (java.util.concurrent.TimeoutException e) {
      throw new DonaTrackApi.SinRespuesta(
          "Logística no contestó en "
              + PACIENCIA_LECTURA_SEGUNDOS
              + " segundos al leer el depósito, así que no se modificó nada. Si está dormido, "
              + "'despertar_servicios' lo despierta y después se puede reintentar.");
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException("Se interrumpió la lectura del depósito.", e);
    }
  }
}
