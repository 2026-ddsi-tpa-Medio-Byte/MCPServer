package ar.edu.utn.dds.k3003.mcp;

/**
 * Los cuatro módulos del sistema, para las herramientas que trabajan sobre uno solo.
 *
 * <p>Existe porque reiniciar y cargar precondiciones se pueden pedir módulo por módulo, y el
 * nombre lo escribe quien lo pide: conviene aceptar «logistica» y «Logística» antes que fallar
 * por una tilde.
 */
enum Modulo {
  DONACIONES("Donaciones"),
  DONADORES("Donadores"),
  LOGISTICA("Logística"),
  INCENTIVOS("Incentivos");

  private final String nombre;

  Modulo(String nombre) {
    this.nombre = nombre;
  }

  String nombre() {
    return nombre;
  }

  /**
   * Resuelve lo que se escribió como nombre de módulo.
   *
   * <p>Validar esto acá no contradice que el MCP no valide reglas de negocio: es un parámetro de
   * la herramienta, no una regla del dominio, y ningún módulo lo conoce.
   */
  static Modulo desde(String valor) {
    String limpio =
        valor == null
            ? ""
            : valor.trim().toLowerCase().replace("í", "i").replace("ó", "o").replace("á", "a");
    return switch (limpio) {
      case "donaciones" -> DONACIONES;
      case "donadores", "entidades", "donadores y entidades", "donadoresyentidades" -> DONADORES;
      case "logistica" -> LOGISTICA;
      case "incentivos" -> INCENTIVOS;
      default ->
          throw new IllegalArgumentException(
              "No conozco el módulo «"
                  + valor
                  + "». Los que hay son: donaciones, donadores, logistica e incentivos.");
    };
  }
}
