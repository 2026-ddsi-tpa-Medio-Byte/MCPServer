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
    if (!narrar) {
      return operacion.get();
    }
    Panorama antes = foto.get();
    String respuesta;
    String traza = api.nuevaTraza();
    try {
      respuesta = operacion.get();
    } finally {
      api.cerrarTraza();
    }
    try {
      return relato.contar(parsear(respuesta), antes, foto.get(), traza);
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
              + "en el momento sin esperar una donación nueva. El tipo puede ser EXTRAORDINARIA "
              + "(acepta que le asignen menos de lo pedido) o RECURRENTE (solo acepta que la "
              + "cubran del todo).")
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
          "Registra una queja sobre una donación ya entregada. Tiene dos efectos: la donación "
              + "deja de estar ACEPTADA y el donador acumula la queja, lo que puede bajarle la "
              + "reputación hasta dejarlo baneado. También puede hacerle perder insignias que "
              + "hubiera ganado.")
  public String registrarQueja(
      @ToolParam(description = "Número de la donación sobre la que se reclama") String donacionId,
      @ToolParam(description = "Qué pasó con esa donación") String descripcion) {
    sesion.requerirLogin("registrar una queja");
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
              + "que un donador baneado no puede donar conviene forzarlo en vez de cargar once "
              + "quejas.")
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
          "Reporta la entrega de un paquete en Logística. Requiere permisos de ADMIN. "
              + "Al reportar la entrega, la donación pasa a ACEPTADA y se satisface la necesidad.")
  public String reportarEntrega(
      @ToolParam(
              required = false,
              description =
                  "Código del paquete. Si se omite se deduce de la donación, que es como lo arma "
                      + "Logística.")
          String paqueteId,
      @ToolParam(description = "Número de la donación asociada") String donacionId,
      @ToolParam(description = "Número del producto entregado") String productoId,
      @ToolParam(description = "Cantidad de unidades entregadas") int cantidad) {
    sesion.requerirAdmin("reportar una entrega");
    String donacion = donacionId.trim();
    // Logística nombra cada paquete "paq-" + el id de la donación que lo originó. Pedirlo es
    // una traba en la demostración: quien reporta la entrega tiene a mano la donación, no el
    // paquete, y no hay forma de listarlos.
    String paquete =
        (paqueteId == null || paqueteId.isBlank()) ? "paq-" + donacion : paqueteId.trim();
    return conRelato(
        () -> Panorama.deEntrega(api, paquete, donacion),
        () ->
            api.postLogistica(
                "/api/asignaciones/reportar-entrega",
                DonaTrackApi.cuerpo(
                    "paqueteid", paquete,
                    "donacionID", donacion,
                    "productoid", productoId.trim(),
                    "cantidad", cantidad)),
        (respuesta, antes, despues, traza) -> Narrador.entrega(antes, despues, traza));
  }
}
