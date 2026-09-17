package ar.edu.utn.dds.k3003.mcp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.util.ArrayList;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/**
 * Deja el sistema con los datos que cada flujo necesita para poder ejecutarse.
 *
 * <p>Los flujos principales tienen precondiciones que no son parte de lo que se quiere mostrar:
 * para donar hace falta un producto, un donador y un depósito; para que la donación se asigne hace
 * falta una necesidad; para que Incentivos otorgue algo hace falta una misión. Cargar todo eso a
 * mano en cuatro Swagger delante de alguien es lento y se presta a errores.
 *
 * <p>El depósito se crea con el identificador que usan por defecto el bot y el MCP: si se creara
 * con otro, donar sin indicar depósito iría a uno inexistente.
 */
@Service
public class SeedTools {

  private static final Logger log = LoggerFactory.getLogger(SeedTools.class);
  private static final ObjectMapper mapper = new ObjectMapper();

  private final DonaTrackApi api;
  private final SesionMcp sesion;
  private final String depositoPorDefecto;

  public SeedTools(
      DonaTrackApi api,
      SesionMcp sesion,
      @Value("${donatrack.deposito-default:DEP-UTN-01}") String depositoPorDefecto) {
    this.api = api;
    this.sesion = sesion;
    this.depositoPorDefecto = depositoPorDefecto;
  }

  @Tool(
      name = "preparar_demo",
      description =
          "Carga las precondiciones de todos los flujos: identificador, producto, donador, "
              + "entidad, depósito, insignia, misión y una necesidad pendiente. Deja el sistema "
              + "listo para mostrar los flujos uno por uno. Por defecto no ejecuta ninguna "
              + "operación de negocio: eso se hace después, para poder mostrarlo. Requiere ADMIN. "
              + "El resultado ya viene redactado: mostrarlo tal cual.")
  public String prepararDemo(
      @ToolParam(
              required = false,
              description =
                  "Si es true, además de las precondiciones ejecuta el flujo entero (donación, "
                      + "entrega, queja e incentivos) para dejar datos ya procesados. Por defecto "
                      + "es false.")
          Boolean ejecutarFlujoPrincipal) {

    sesion.requerirAdmin("preparar la demostración");

    boolean flujoCompleto = Boolean.TRUE.equals(ejecutarFlujoPrincipal);
    long suf = Instant.now().getEpochSecond();
    StringBuilder sb = new StringBuilder("**Precondiciones cargadas**\n\n");

    try {
      String identId = crearIdentificador(sb, suf);
      String prodId = crearProducto(sb, suf, identId);
      String donadorId = crearDonador(sb, suf);
      String entidadId = crearEntidad(sb, suf);
      crearDeposito(sb, suf);
      String[] incentivos = crearInsigniaYMision(sb, suf);
      String necesidadId = crearNecesidad(sb, entidadId, prodId);

      sb.append("\n**Para usar en los flujos**\n\n")
          .append("- Donador nº ").append(donadorId).append("\n")
          .append("- Producto nº ").append(prodId).append("\n")
          .append("- Entidad nº ").append(entidadId).append("\n")
          .append("- Necesidad nº ").append(necesidadId).append(" (20 unidades, EXTRAORDINARIA)\n")
          .append("- Depósito ").append(depositoPorDefecto).append("\n");

      if (!flujoCompleto) {
        sb.append("\nSiguiente paso: `registrar_donacion` con ese donador y ese producto.\n");
        return sb.toString();
      }

      sb.append("\n**Flujo completo ejecutado**\n\n");
      ejecutarFlujo(sb, donadorId, prodId, incentivos[0], incentivos[1], suf);
      return sb.toString();

    } catch (Exception e) {
      log.error("Error preparando la demostración", e);
      return sb + "\n⚠️ Se cortó acá: " + e.getMessage();
    }
  }

  // ── Precondiciones ─────────────────────────────────────────────────────────

  private String crearIdentificador(StringBuilder sb, long suf) throws Exception {
    String id =
        idDe(
            api.postDonaciones(
                "/identificadores",
                DonaTrackApi.cuerpo(
                    "tipo", "CODIGODEBARRAS", "descripcion", "Codigo de barras seed " + suf)));
    sb.append("- **Donaciones** — identificador nº ").append(id).append(" (CODIGODEBARRAS)\n");
    return id;
  }

  /** Con CODIGODEBARRAS la descripción necesita al menos tres palabras: es una regla del dominio. */
  private String crearProducto(StringBuilder sb, long suf, String identId) throws Exception {
    String id =
        idDe(
            api.postDonaciones(
                "/productos",
                DonaTrackApi.cuerpo(
                    "nombre", "ArrozSeed" + suf,
                    "descripcion", "Arroz blanco largo fino",
                    "categoriaID", "alimentos",
                    "identificadorID", identId)));
    sb.append("- **Donaciones** — producto nº ").append(id).append(" (ArrozSeed").append(suf).append(")\n");
    return id;
  }

  private String crearDonador(StringBuilder sb, long suf) throws Exception {
    String doc = String.valueOf(suf);
    if (doc.length() > 8) {
      doc = doc.substring(doc.length() - 8);
    }
    String id =
        idDe(
            api.postDonadores(
                "/donadores",
                DonaTrackApi.cuerpo(
                    "nombre", "Carlos",
                    "apellido", "Seed",
                    "edad", 35,
                    "email", "carlos" + suf + "@seed.com",
                    "nroDocumento", doc,
                    "domicilio", "Av Corrientes 1234")));
    sb.append("- **Donadores** — donador nº ").append(id).append(" (Carlos Seed, VERIFICADO)\n");
    return id;
  }

  private String crearEntidad(StringBuilder sb, long suf) throws Exception {
    String id =
        idDe(
            api.postDonadores(
                "/entidades",
                DonaTrackApi.cuerpo(
                    "razonSocial", "Comedor Solidario " + suf,
                    "domicilio", "Av Rivadavia 5000",
                    "telefono", "1144445555",
                    "correo", "comedor" + suf + "@seed.com")));
    sb.append("- **Donadores** — entidad nº ").append(id).append(" (Comedor Solidario)\n");
    return id;
  }

  private void crearDeposito(StringBuilder sb, long suf) {
    try {
      api.postLogistica(
          "/depositos",
          DonaTrackApi.cuerpo(
              "id", depositoPorDefecto,
              "algoritmo", "SUB_ATENDIDOS",
              "nombre", "Deposito Central UTN",
              "direccion", "Av Medrano 951",
              "capacidadMaxima", 5000,
              "stockActual", new ArrayList<>()));
      sb.append("- **Logística** — depósito ")
          .append(depositoPorDefecto)
          .append(" (capacidad 5000, algoritmo SUB_ATENDIDOS)\n");
    } catch (Exception e) {
      sb.append("- **Logística** — ⚠️ no se pudo crear el depósito ")
          .append(depositoPorDefecto)
          .append(": ")
          .append(e.getMessage())
          .append(". Si ya existía está bien; si no, las donaciones van a fallar.\n");
    }
  }

  private String[] crearInsigniaYMision(StringBuilder sb, long suf) {
    String insId = "ins-" + suf;
    String misId = "mis-" + suf;
    try {
      api.postIncentivos(
          "/insignias",
          DonaTrackApi.cuerpo(
              "id", insId, "nombre", "Solidario " + suf, "descripcion", "Donacion realizada"));
      api.postIncentivos(
          "/misiones",
          DonaTrackApi.cuerpo(
              "id", misId,
              "nombre", "Mision Solidaria " + suf,
              "insigniaID", insId,
              "categoriaInicio", "OCASIONAL",
              "categoriaFin", "COLABORADOR",
              "tipo", "COMPLETITUD"));
      sb.append("- **Incentivos** — insignia ").append(insId).append(" y misión ").append(misId).append("\n");
    } catch (Exception e) {
      sb.append("- **Incentivos** — ⚠️ no respondió: ")
          .append(e.getMessage())
          .append(". El flujo de incentivos no se va a poder mostrar.\n");
    }
    return new String[] {insId, misId};
  }

  private String crearNecesidad(StringBuilder sb, String entidadId, String prodId)
      throws Exception {
    String respuesta =
        api.postDonadores(
            "/necesidades",
            DonaTrackApi.cuerpo(
                "entidadID", entidadId,
                "nivelDeUrgencia", 8,
                "descripcion", "Arroz para el comedor mensual",
                "cantidadObjetivo", 20,
                "productoSolicitadoID", prodId,
                "tipo", "EXTRAORDINARIA"));
    String id = idDe(respuesta);
    sb.append("- **Donadores** — necesidad nº ").append(id).append(" (20 unidades, urgencia 8)\n");
    return id;
  }

  // ── Flujo completo, opcional ───────────────────────────────────────────────

  /**
   * Registra la donación y sigue el circuito hasta la entrega.
   *
   * <p>Acá no se llama a Logística para que gestione la donación: eso ya lo hace Donaciones al
   * registrarla. Llamarla de nuevo, como se hacía antes, le entregaba la misma donación dos veces.
   * El paquete se deduce del identificador de la donación, que es como lo arma Logística.
   */
  private void ejecutarFlujo(
      StringBuilder sb, String donadorId, String prodId, String insId, String misId, long suf) {
    String donacionId;
    try {
      donacionId =
          idDe(
              api.postDonaciones(
                  "/donaciones",
                  DonaTrackApi.cuerpo(
                      "donadorID", donadorId,
                      "depositoID", depositoPorDefecto,
                      "descripcion", "Donacion de arroz mensual",
                      "productoID", prodId,
                      "cantidad", 10)));
      sb.append("- Donación nº ").append(donacionId).append(" registrada (10 unidades)\n");
    } catch (Exception e) {
      sb.append("- ⚠️ La donación falló: ").append(e.getMessage()).append("\n");
      return;
    }

    String paqueteId = "paq-" + donacionId;
    try {
      api.postLogistica(
          "/api/asignaciones/reportar-entrega",
          DonaTrackApi.cuerpo(
              "paqueteid", paqueteId,
              "donacionID", donacionId,
              "productoid", prodId,
              "cantidad", 10));
      sb.append("- Entrega del paquete ").append(paqueteId).append(" reportada\n");
    } catch (Exception e) {
      sb.append("- ⚠️ No se pudo reportar la entrega de ")
          .append(paqueteId)
          .append(": ")
          .append(e.getMessage())
          .append("\n");
    }

    try {
      api.postIncentivos(
          "/donadores/" + donadorId + "/mision-actual",
          DonaTrackApi.cuerpo(
              "id", misId,
              "nombre", "Mision Solidaria " + suf,
              "insigniaID", insId,
              "categoriaInicio", "OCASIONAL",
              "categoriaFin", "COLABORADOR",
              "tipo", "COMPLETITUD"));
      api.postIncentivos("/donadores/" + donadorId + "/procesar", null);
      sb.append("- Donador procesado en Incentivos\n");
    } catch (Exception e) {
      sb.append("- ⚠️ Incentivos no respondió: ").append(e.getMessage()).append("\n");
    }
  }

  private String idDe(String respuesta) throws Exception {
    JsonNode nodo = mapper.readTree(respuesta);
    return nodo.path("id").asText();
  }
}
