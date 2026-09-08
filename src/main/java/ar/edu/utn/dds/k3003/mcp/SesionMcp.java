package ar.edu.utn.dds.k3003.mcp;

import org.springframework.stereotype.Component;

/**
 * Mantiene el estado de la sesión activa para el cliente MCP (Claude Desktop).
 *
 * <p>El servidor corre local como proceso interactivo por sesión de stdio, por lo que el estado de
 * autenticación aplica a la conversación en curso.
 */
@Component
public class SesionMcp {

  public enum Rol {
    ANONIMO,
    DONADOR,
    ADMIN
  }

  private Rol rol = Rol.ANONIMO;
  private String usuario = null;
  private String nombre = null;

  public synchronized Rol getRol() {
    return rol;
  }

  public synchronized String getUsuario() {
    return usuario;
  }

  public synchronized String getNombre() {
    return nombre;
  }

  public synchronized boolean estaAutenticado() {
    return rol != Rol.ANONIMO;
  }

  public synchronized boolean esAdmin() {
    return rol == Rol.ADMIN;
  }

  public synchronized boolean esDonador() {
    return rol == Rol.DONADOR;
  }

  public synchronized void iniciarComoAdmin(String usuario) {
    this.rol = Rol.ADMIN;
    this.usuario = (usuario != null && !usuario.isBlank()) ? usuario.trim() : "admin";
    this.nombre = "Administrador";
  }

  public synchronized void iniciarComoDonador(String donadorId, String nombre) {
    this.rol = Rol.DONADOR;
    this.usuario = (donadorId != null) ? donadorId.trim() : "";
    this.nombre = (nombre != null && !nombre.isBlank()) ? nombre.trim() : "Donador " + donadorId;
  }

  public synchronized void cerrarSesion() {
    this.rol = Rol.ANONIMO;
    this.usuario = null;
    this.nombre = null;
  }

  public synchronized void requerirLogin(String operacion) {
    if (!estaAutenticado()) {
      throw new IllegalStateException(
          "Acceso denegado para "
              + operacion
              + ": debés iniciar sesión primero usando la herramienta 'iniciar_sesion' (como ADMIN o indicando tu número de DONADOR).");
    }
  }

  public synchronized void requerirAdmin(String operacion) {
    if (!esAdmin()) {
      throw new IllegalStateException(
          "Acceso denegado para "
              + operacion
              + ": esta operación requiere permisos de Administrador. Iniciá sesión con la herramienta 'iniciar_sesion' con rol ADMIN.");
    }
  }
}
