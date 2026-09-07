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

  private final DonaTrackApi api;
  private final String depositoPorDefecto;

  public OperacionTools(
      DonaTrackApi api, @Value("${donatrack.deposito-default}") String depositoPorDefecto) {
    this.api = api;
    this.depositoPorDefecto = depositoPorDefecto;
  }

  // ── Registrar cosas ────────────────────────────────────────────────────────

  @Tool(
      name = "registrar_donacion",
      description =
          "Registra una donación. Es la operación central del sistema: valida el producto, "
              + "consulta a Donadores si esa persona puede donar, y avisa a Logística para que la "
              + "asigne a una necesidad. Hace falta saber el número del donador y el del "
              + "producto: si no se tienen, consultarlos primero.")
  public String registrarDonacion(
      @ToolParam(description = "Número del donador que dona") String donadorId,
      @ToolParam(description = "Número del producto que se dona") String productoId,
      @ToolParam(description = "Cuántas unidades. Tiene que ser mayor a cero.") int cantidad,
      @ToolParam(description = "Descripción de la donación en pocas palabras") String descripcion,
      @ToolParam(required = false, description = "Depósito. Si se omite se usa el habitual.")
          String depositoId) {
    String deposito =
        (depositoId == null || depositoId.isBlank()) ? depositoPorDefecto : depositoId.trim();
    return api.postDonaciones(
        "/donaciones",
        DonaTrackApi.cuerpo(
            "donadorID", donadorId.trim(),
            "depositoID", deposito,
            "descripcion", descripcion,
            "productoID", productoId.trim(),
            "cantidad", cantidad));
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
    return api.postDonadores(
        "/necesidades",
        DonaTrackApi.cuerpo(
            "entidadID", entidadId.trim(),
            "nivelDeUrgencia", urgencia,
            "descripcion", descripcion,
            "cantidadObjetivo", cantidadObjetivo,
            "productoSolicitadoID", productoId.trim(),
            "tipo", tipo.toUpperCase().trim()));
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
    // La API espera el texto plano entre comillas, no un objeto.
    return api.postDonaciones("/donaciones/" + donacionId.trim() + "/quejas", descripcion);
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
    return api.putDonadores(
        "/entidades/" + entidadId.trim(),
        DonaTrackApi.cuerpo(
            "razonSocial", razonSocial,
            "domicilio", domicilio,
            "telefono", telefono,
            "correo", correo));
  }

  @Tool(
      name = "eliminar_necesidad",
      description =
          "Borra una necesidad. Usar solo si el usuario lo pide de forma explícita: no se puede "
              + "deshacer.")
  public String eliminarNecesidad(
      @ToolParam(description = "Número de la necesidad a borrar") String necesidadId) {
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
    return api.postIncentivos("/donadores/" + donadorId.trim() + "/procesar", null);
  }
}
