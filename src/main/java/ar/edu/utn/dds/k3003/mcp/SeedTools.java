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
 * Herramienta para cargar la semilla de datos (Seed) y ejecutar el flujo principal en DonaTrack.
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
      name = "ejecutar_seed",
      description =
          "Carga datos de prueba en el sistema (identificador, producto, donador, entidad, depósito, "
              + "insignia/misión y necesidad) y opcionalmente ejecuta las acciones del flujo principal "
              + "(donación, asignación/entrega en logística, queja de prueba y procesamiento de incentivos). "
              + "Requiere haber iniciado sesión como ADMIN.")
  public String ejecutarSeed(
      @ToolParam(
              required = false,
              description =
                  "Si es true (por defecto), además de las precondiciones ejecuta la donación, "
                      + "entrega en logística, queja y evaluación de incentivos.")
          Boolean ejecutarFlujoPrincipal) {

    sesion.requerirAdmin("ejecutar la seed del sistema");

    boolean flujoCompleto = (ejecutarFlujoPrincipal == null) || ejecutarFlujoPrincipal;
    long suf = Instant.now().getEpochSecond();
    StringBuilder sb = new StringBuilder();
    sb.append("=== INICIANDO SEED DE DONATRACK (Sufijo: ").append(suf).append(") ===\n\n");

    try {
      // 1. Identificador
      sb.append("1. Creando identificador (CODIGODEBARRAS)... ");
      String respIdent =
          api.postDonaciones(
              "/identificadores",
              DonaTrackApi.cuerpo(
                  "tipo", "CODIGODEBARRAS", "descripcion", "Codigo de barras seed " + suf));
      JsonNode nodoIdent = mapper.readTree(respIdent);
      String identId = nodoIdent.path("id").asText();
      sb.append("OK (ID: ").append(identId).append(")\n");

      // 2. Producto (CODIGODEBARRAS exige >= 3 palabras en descripción)
      sb.append("2. Creando producto donable... ");
      String respProd =
          api.postDonaciones(
              "/productos",
              DonaTrackApi.cuerpo(
                  "nombre", "ArrozSeed" + suf,
                  "descripcion", "Arroz blanco largo fino",
                  "categoriaID", "alimentos",
                  "identificadorID", identId));
      JsonNode nodoProd = mapper.readTree(respProd);
      String prodId = nodoProd.path("id").asText();
      sb.append("OK (ID: ").append(prodId).append(")\n");

      // 3. Donador
      sb.append("3. Creando donador... ");
      String doc = String.valueOf(suf);
      if (doc.length() > 8) {
        doc = doc.substring(doc.length() - 8);
      }
      String respDon =
          api.postDonadores(
              "/donadores",
              DonaTrackApi.cuerpo(
                  "nombre", "Carlos",
                  "apellido", "Seed",
                  "edad", 35,
                  "email", "carlos" + suf + "@seed.com",
                  "nroDocumento", doc,
                  "domicilio", "Av Corrientes 1234"));
      JsonNode nodoDon = mapper.readTree(respDon);
      String donadorId = nodoDon.path("id").asText();
      sb.append("OK (ID: ").append(donadorId).append(")\n");

      // 4. Entidad beneficiaria
      sb.append("4. Creando entidad benéfica... ");
      String respEnt =
          api.postDonadores(
              "/entidades",
              DonaTrackApi.cuerpo(
                  "razonSocial", "Comedor Solidario " + suf,
                  "domicilio", "Av Rivadavia 5000",
                  "telefono", "1144445555",
                  "correo", "comedor" + suf + "@seed.com"));
      JsonNode nodoEnt = mapper.readTree(respEnt);
      String entidadId = nodoEnt.path("id").asText();
      sb.append("OK (ID: ").append(entidadId).append(")\n");

      // 5. Depósito en Logística
      String depId = "DEP-SEED-" + (suf % 10000);
      sb.append("5. Creando depósito en Logística (").append(depId).append(")... ");
      try {
        api.postLogistica(
            "/depositos",
            DonaTrackApi.cuerpo(
                "id", depId,
                "algoritmo", "SUB_ATENDIDOS",
                "nombre", "Deposito Central Seed " + suf,
                "direccion", "Av Medrano 951",
                "capacidadMaxima", 5000,
                "stockActual", new ArrayList<>()));
        sb.append("OK\n");
      } catch (Exception e) {
        sb.append("Aviso: ")
            .append(e.getMessage())
            .append(" (Se usará ")
            .append(depositoPorDefecto)
            .append(")\n");
        depId = depositoPorDefecto;
      }

      // 6. Insignias y Misiones en Incentivos
      String insId = "ins-" + suf;
      String misId = "mis-" + suf;
      sb.append("6. Creando insignia y misión en Incentivos... ");
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
        sb.append("OK\n");
      } catch (Exception e) {
        sb.append("Omitido (Incentivos no disponible: ").append(e.getMessage()).append(")\n");
      }

      // 7. Necesidad en Donadores
      sb.append("7. Creando necesidad material (EXTRAORDINARIA)... ");
      String respNec =
          api.postDonadores(
              "/necesidades",
              DonaTrackApi.cuerpo(
                  "entidadID", entidadId,
                  "nivelDeUrgencia", 8,
                  "descripcion", "Arroz para el comedor mensual",
                  "cantidadObjetivo", 20,
                  "productoSolicitadoID", prodId,
                  "tipo", "EXTRAORDINARIA"));
      JsonNode nodoNec = mapper.readTree(respNec);
      String necesidadId = nodoNec.path("id").asText();
      sb.append("OK (ID: ").append(necesidadId).append(")\n\n");

      if (!flujoCompleto) {
        sb.append("Precondiciones cargadas exitosamente. (Flujo principal omitido por parámetro).\n");
        return sb.toString();
      }

      // ── ACCIONES DEL FLUJO PRINCIPAL ──────────────────────────────────────
      sb.append("--- EJECUTANDO ACCIONES DEL FLUJO PRINCIPAL ---\n");

      // 8. Donación en Donaciones
      sb.append("8. Registrando donación (10 unidades)... ");
      String respDonacion =
          api.postDonaciones(
              "/donaciones",
              DonaTrackApi.cuerpo(
                  "donadorID", donadorId,
                  "depositoID", depId,
                  "descripcion", "Donacion de arroz mensual",
                  "productoID", prodId,
                  "cantidad", 10));
      JsonNode nodoDonacion = mapper.readTree(respDonacion);
      String donacionId = nodoDonacion.path("id").asText();
      sb.append("OK (ID: ").append(donacionId).append(", Estado: INGRESADA)\n");

      // 9. Logística: Gestión y Entrega
      sb.append("9. Gestionando entrega en Logística... ");
      try {
        String respGestion =
            api.postLogistica(
                "/api/depositos/gestionar-donacion?depositoid="
                    + depId
                    + "&donacionid="
                    + donacionId
                    + "&productoid="
                    + prodId
                    + "&cantidad=10",
                null);
        JsonNode nodoGestion = mapper.readTree(respGestion);
        String paqueteId = nodoGestion.path("paqueteid").asText("");
        if (paqueteId.isBlank()) {
          paqueteId = nodoGestion.path("id").asText("1");
        }

        api.postLogistica(
            "/api/asignaciones/reportar-entrega",
            DonaTrackApi.cuerpo(
                "paqueteid", paqueteId,
                "donacionID", donacionId,
                "productoid", prodId,
                "cantidad", 10));
        sb.append("OK (Paquete: ").append(paqueteId).append(" entregado)\n");
      } catch (Exception e) {
        sb.append("Aviso en logística: ").append(e.getMessage()).append("\n");
      }

      // 10. Queja de prueba sobre donación
      sb.append("10. Registrando queja de prueba sobre la donación... ");
      try {
        api.postDonaciones(
            "/donaciones/" + donacionId + "/quejas", "Paquete con rotura menor de empaque");
        sb.append("OK (Donación marcada con queja)\n");
      } catch (Exception e) {
        sb.append("Aviso en queja: ").append(e.getMessage()).append("\n");
      }

      // 11. Incentivos: procesar donador
      sb.append("11. Procesando donador en Incentivos... ");
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
        sb.append("OK\n");
      } catch (Exception e) {
        sb.append("Aviso en Incentivos: ").append(e.getMessage()).append("\n");
      }

      sb.append("\n=== SEED Y FLUJO PRINCIPAL COMPLETADOS CON ÉXITO ===");
      return sb.toString();

    } catch (Exception e) {
      log.error("Error durante ejecución de seed", e);
      return sb.toString() + "\nERROR AL EJECUTAR SEED: " + e.getMessage();
    }
  }
}
