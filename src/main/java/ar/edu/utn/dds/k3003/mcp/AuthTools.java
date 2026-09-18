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
  private final String adminPassword;

  public AuthTools(
      SesionMcp sesion,
      DonaTrackApi api,
      @Value("${donatrack.admin-password:admin123}") String adminPassword) {
    this.sesion = sesion;
    this.api = api;
    this.adminPassword = adminPassword;
  }

  @Tool(
      name = "iniciar_sesion",
      description =
          "Inicia sesión en DonaTrack. Permite autenticarse como ADMIN (con usuario y contraseña) "
              + "o como DONADOR (indicando el número de ID de donador registrado). Es requisito para "
              + "operaciones de modificación y para ejecutar la seed de datos.")
  public String iniciarSesion(
      @ToolParam(description = "Rol a usar: 'ADMIN' o 'DONADOR'") String rol,
      @ToolParam(
              required = false,
              description = "Para ADMIN: usuario (ej. 'admin'). Para DONADOR: su número de ID.")
          String identificador,
      @ToolParam(
              required = false,
              description = "Contraseña requerida solo para ADMIN (por defecto 'admin123')")
          String password) {
    if (rol == null || rol.isBlank()) {
      return "Debe especificar el rol: 'ADMIN' o 'DONADOR'.";
    }
    String rolNorm = rol.trim().toUpperCase();
    if ("ADMIN".equals(rolNorm)) {
      String pass = password != null ? password.trim() : "";
      if (!adminPassword.equals(pass)) {
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
    if (!sesion.estaAutenticado()) {
      return "Estado de sesión: ANÓNIMO (No autenticado). Para realizar operaciones o cargar datos, usá 'iniciar_sesion'.";
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
}
