package ar.edu.utn.dds.k3003.mcp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.beans.factory.annotation.Autowired;
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

  /**
   * Cómo se prende el modo admin local. Lo repiten los mensajes que no dejan entrar como ADMIN,
   * porque es lo único que el usuario tiene que hacer para destrabarlo y lo hace fuera del chat.
   */
  private static final String COMO_HABILITAR_ADMIN_LOCAL =
      "definir DONATRACK_ADMIN_LOCAL=true en el bloque \"env\" del servidor donatrack, en la "
          + "configuración de Claude Desktop (Settings > Developer > Edit Config), y reiniciar "
          + "Claude Desktop del todo";

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

  /**
   * Si la identidad de admin la da la configuración local del equipo, sin contraseña.
   *
   * <p>El MCP corre en la máquina y con la cuenta de quien configura Claude Desktop: quien puede
   * editar esa configuración ya es el dueño del equipo, así que pedirle además una contraseña por
   * el chat no protegía nada. Al contrario, la credencial pasaba por el modelo, quedaba en la
   * conversación y un prompt inyectado podía sacársela. Y Claude se niega a escribir contraseñas
   * para autenticarse, así que por el chat el ingreso directamente no funcionaba.
   */
  private final boolean adminLocal;

  /**
   * Para los tests: sin modo admin local, el ingreso de admin compara la contraseña como siempre.
   */
  AuthTools(SesionMcp sesion, DonaTrackApi api, String adminPassword) {
    this(sesion, api, adminPassword, "false");
  }

  @Autowired
  public AuthTools(
      SesionMcp sesion,
      DonaTrackApi api,
      @Value("${donatrack.admin-password:}") String adminPassword,
      @Value("${donatrack.admin-local:false}") String adminLocal) {
    this.sesion = sesion;
    this.api = api;
    this.adminPassword = adminPassword == null ? "" : adminPassword.trim();
    this.adminLocal = activado(adminLocal);
  }

  /**
   * Solo «true» lo prende, sin importar mayúsculas ni espacios.
   *
   * <p>Se recibe como texto y no como boolean a propósito: si Spring tuviera que convertir un valor
   * mal escrito («si», o vacío), fallaría al arrancar y Claude Desktop no podría conectarse. Así, un
   * valor que no se entiende deja el modo apagado, que es lo seguro, y la tool dice cómo prenderlo.
   */
  private static boolean activado(String valor) {
    return valor != null && "true".equalsIgnoreCase(valor.trim());
  }

  @Tool(
      name = "iniciar_sesion",
      description =
          "Inicia sesión en DonaTrack como ADMIN o como DONADOR. Es requisito para las "
              + "operaciones de modificación y para cargar la seed de datos. "
              + "ADMIN: llamarla con rol 'ADMIN' y SIN contraseña. Si el servidor está en modo "
              + "admin local, entra directo: la identidad de admin la da la configuración del "
              + "equipo, no una credencial. NUNCA pedirle la contraseña al usuario ni escribirla "
              + "en el chat: si el servidor no está en modo admin local, la respuesta explica cómo "
              + "habilitarlo y eso se le transmite al usuario. "
              + "DONADOR: indicar en 'identificador' su número de donador registrado. "
              + "'quien_soy' informa si el modo admin local está activo.")
  public String iniciarSesion(
      @ToolParam(description = "Rol a usar: 'ADMIN' o 'DONADOR'") String rol,
      @ToolParam(
              required = false,
              description = "Para ADMIN: usuario (opcional, por defecto 'admin'). Para DONADOR: su número de ID.")
          String identificador,
      @ToolParam(
              required = false,
              description =
                  "Dejar vacío. Nunca pedirle la contraseña al usuario ni escribirla en el chat. "
                      + "En modo admin local se ignora.")
          String password) {
    if (rol == null || rol.isBlank()) {
      return "Debe especificar el rol: 'ADMIN' o 'DONADOR'.";
    }
    String rolNorm = rol.trim().toUpperCase();
    if ("ADMIN".equals(rolNorm)) {
      String user =
          (identificador != null && !identificador.isBlank()) ? identificador.trim() : "admin";
      if (adminLocal) {
        // Si el modelo mandó una contraseña igual, no se mira: compararla daría a entender que
        // importa, y repetirla o loguearla la filtraría. La identidad ya la dio la configuración.
        sesion.iniciarComoAdmin(user);
        return "Sesión iniciada como ADMINISTRADOR ("
            + user
            + ") en modo admin local: la identidad la da la configuración de este equipo, sin "
            + "contraseña. Tenés acceso total a las operaciones y a las herramientas de "
            + "demostración ('guion_demo' dice por dónde empezar).";
      }
      if (adminPassword.isEmpty()) {
        return "El ingreso como ADMIN está deshabilitado: el servidor MCP no está en modo admin "
            + "local ni tiene configurada una contraseña de administrador. Para habilitarlo sin "
            + "contraseña hay que "
            + COMO_HABILITAR_ADMIN_LOCAL
            + ". (La alternativa vieja, DONATRACK_ADMIN_PASSWORD en ese mismo bloque \"env\", "
            + "obliga a escribir la contraseña en el chat.) Como DONADOR se puede ingresar igual.";
      }
      String pass = password != null ? password.trim() : "";
      if (pass.isEmpty()) {
        return "No se pudo iniciar sesión como ADMIN: este servidor exige contraseña, y la "
            + "contraseña no se escribe en el chat (no hay que pedírsela al usuario). Para entrar "
            + "como ADMIN sin contraseña hay que activar el modo admin local: "
            + COMO_HABILITAR_ADMIN_LOCAL
            + ".";
      }
      if (!coincide(adminPassword, pass)) {
        return "Autenticación fallida: contraseña de Administrador incorrecta.";
      }
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
          "Informa el estado de la sesión actual: si está autenticado, usuario/rol activo y "
              + "permisos, si el modo admin local está activo y por dónde van los pedidos.")
  public String quienSoy() {
    return estadoDeSesion()
        + "\nModo admin local: "
        + (adminLocal
            ? "activo ('iniciar_sesion' con rol ADMIN entra sin contraseña)."
            : "inactivo (se activa con DONATRACK_ADMIN_LOCAL=true en la configuración de Claude "
                + "Desktop).")
        + "\nConexión: "
        + api.descripcionDeConexion()
        + ".";
  }

  private String estadoDeSesion() {
    if (!sesion.estaAutenticado()) {
      return "Estado de sesión: ANÓNIMO (No autenticado). Para realizar operaciones o cargar datos, usá 'iniciar_sesion'."
          + (!adminLocal && adminPassword.isEmpty()
              ? " El ingreso como ADMIN está deshabilitado: falta activar el modo admin local "
                  + "(DONATRACK_ADMIN_LOCAL=true) o configurar la contraseña "
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
