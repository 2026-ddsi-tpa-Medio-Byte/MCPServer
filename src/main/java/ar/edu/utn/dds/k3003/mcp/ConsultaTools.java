package ar.edu.utn.dds.k3003.mcp;

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
          "Las quejas que recibió un donador. Cada queja baja su reputación: con varias pasa a "
              + "SOSPECHOSO y con más queda BANEADO, y ahí ya no puede donar.")
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
      // La API exige una fecha desde; se usa una vieja para que traiga todo el historial.
      return api.getDonaciones("/donaciones?donadorID=" + donadorId.trim() + "&fecha=2020-01-01");
    }
    return api.getDonaciones("/donaciones");
  }

  @Tool(
      name = "consultar_depositos_y_stock",
      description =
          "Los depósitos de Logística con su capacidad, o el stock disponible de un producto. El "
              + "stock es lo que quedó guardado de donaciones que no se asignaron a ninguna "
              + "necesidad, o el sobrante de las que sí.")
  public String consultarLogistica(
      @ToolParam(required = false, description = "Número del producto para ver su stock")
          String productoId) {
    if (productoId != null && !productoId.isBlank()) {
      return api.getLogistica("/stock/" + productoId.trim());
    }
    return api.getLogistica("/depositos");
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
}
