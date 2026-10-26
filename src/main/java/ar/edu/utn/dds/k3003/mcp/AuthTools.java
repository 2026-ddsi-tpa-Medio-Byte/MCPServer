package ar.edu.utn.dds.k3003.mcp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/**
 * Herramientas de autenticación y sesión para MCP.
 *
 * <p>Permite ingresar como ADMINISTRADOR o identificarse como DONADOR, consultar la sesión actual
 * y cerrar sesión.
 */
@Service
public class AuthTools {

  private static final ObjectMapper mapper = new ObjectMapper();

  private final SesionMcp sesion;
  private final DonaTrackApi api;

  /**
   * La contraseña de admin, o vacía si no se configuró.
   *
   * <p>No tiene valor por defecto: este código y su archivo de configuración están en un
   * repositorio, y una clave escrita ahí la conoce cualquiera que lo abra. Hasta que alguien la
   * define, el ingreso como admin está deshabilitado. Nunca se escribe en un log ni en una
   * respuesta.
   */
  private final String adminPassword;

  public AuthTools(
      SesionMcp sesion,
      DonaTrackApi api,
      @Value("${donatrack.admin-password:}") String adminPassword) {
    this.sesion = sesion;
    this.api = api;
    this.adminPassword = adminPassword == null ? "" : adminPassword.trim();
  }

  @Tool(
      name = "iniciar_sesion",
      description =
          "Inicia sesión en DonaTrack. Permite autenticarse como ADMIN (con usuario y contraseña) "
              + "o como DONADOR (indicando el número de ID de donador registrado). Es requisito para "
              + "operaciones de modificación y para ejecutar la seed de datos. La contraseña de "
              + "ADMIN la define quien instaló el servidor; si no está configurada, el ingreso "
              + "como ADMIN está deshabilitado y la herramienta explica cómo habilitarlo.")
  public String iniciarSesion(
      @ToolParam(description = "Rol a usar: 'ADMIN' o 'DONADOR'") String rol,
      @ToolParam(
              required = false,
              description = "Para ADMIN: usuario (ej. 'admin'). Para DONADOR: su número de ID.")
          String identificador,
      @ToolParam(
              required = false,
              description = "Contraseña de administrador. Solo para ADMIN; pedírsela al usuario.")
          String password) {
    if (rol == null || rol.isBlank()) {
      return "Debe especificar el rol: 'ADMIN' o 'DONADOR'.";
    }
    String rolNorm = rol.trim().toUpperCase();
    if ("ADMIN".equals(rolNorm)) {
      if (adminPassword.isEmpty()) {
        return "El ingreso como ADMIN está deshabilitado: el servidor MCP no tiene configurada "
            + "una contraseña de administrador. Para habilitarlo hay que definir la variable de "
            + "entorno DONATRACK_ADMIN_PASSWORD en el bloque \"env\" del servidor donatrack, en "
            + "claude_desktop_config.json, y reiniciar Claude Desktop (el README tiene el "
            + "ejemplo). Como DONADOR se puede ingresar igual.";
      }
      String pass = password != null ? password.trim() : "";
      if (!coincide(adminPassword, pass)) {
        return "Autenticación fallida: contraseña de Administrador incorrecta.";
      }
      String user =
          (identificador != null && !identificador.isBlank()) ? identificador.trim() : "admin";
      sesion.iniciarComoAdmin(user);
      return "Sesión iniciada con éxito como ADMINISTRADOR ("
          + user
          + "). Tenés acceso total a las operaciones y a las herramientas de demostración "
          + "('guion_demo' dice por dónde empezar).";
    } else if ("DONADOR".equals(rolNorm)) {
      if (identificador == null || identificador.isBlank()) {
        return "Para ingresar como DONADOR debés indicar tu número de donador en 'identificador'.";
      }
      try {
        String resp = api.getDonadores("/donadores/" + identificador.trim());
        JsonNode nodo = mapper.readTree(resp);
        String nombre =
            nodo.path("nombre").asText("Donador")
                + " "
                + nodo.path("apellido").asText("").trim();
        sesion.iniciarComoDonador(identificador.trim(), nombre);
        return "Sesión iniciada como DONADOR ("
            + nombre
            + ", ID "
            + identificador.trim()
            + "). Ya podés realizar donaciones y consultar tus datos.";
      } catch (Exception e) {
        return "No se pudo autenticar como donador con ID " + identificador + ": " + e.getMessage();
      }
    } else {
      return "Rol desconocido '" + rol + "'. Los roles válidos son 'ADMIN' y 'DONADOR'.";
    }
  }

  @Tool(
      name = "cerrar_sesion",
      description = "Cierra la sesión activa actual en DonaTrack.")
  public String cerrarSesion() {
    if (!sesion.estaAutenticado()) {
      return "No hay ninguna sesión activa actualmente.";
    }
    sesion.cerrarSesion();
    return "Sesión cerrada correctamente. Ahora estás en modo ANÓNIMO.";
  }

  @Tool(
      name = "quien_soy",
      description =
          "Informa el estado de la sesión actual: si está autenticado, usuario/rol activo y permisos.")
  public String quienSoy() {
    return estadoDeSesion() + "\nConexión: " + api.descripcionDeConexion() + ".";
  }

  private String estadoDeSesion() {
    if (!sesion.estaAutenticado()) {
      return "Estado de sesión: ANÓNIMO (No autenticado). Para realizar operaciones o cargar datos, usá 'iniciar_sesion'."
          + (adminPassword.isEmpty()
              ? " El ingreso como ADMIN está deshabilitado: falta configurar la contraseña "
                  + "(DONATRACK_ADMIN_PASSWORD)."
              : "");
    }
    if (sesion.esAdmin()) {
      return "Estado de sesión: ADMINISTRADOR ("
          + sesion.getUsuario()
          + "). Acceso total a todas las herramientas y seed habilitado.";
    }
    return "Estado de sesión: DONADOR ("
        + sesion.getNombre()
        + ", ID: "
        + sesion.getUsuario()
        + "). Habilitado para consultar historial y donar.";
  }

  /**
   * Compara sin cortar en el primer carácter distinto: el tiempo de respuesta no tiene que dejar
   * adivinar la clave de a una letra.
   */
  private static boolean coincide(String esperada, String recibida) {
    return java.security.MessageDigest.isEqual(
        esperada.getBytes(java.nio.charset.StandardCharsets.UTF_8),
        recibida.getBytes(java.nio.charset.StandardCharsets.UTF_8));
  }
}
