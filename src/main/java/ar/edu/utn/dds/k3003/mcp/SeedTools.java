package ar.edu.utn.dds.k3003.mcp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.util.ArrayList;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.beans.factory.annotation.Autowired;
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

  /**
   * Lo que se espera a que despierten los módulos antes de empezar a escribir.
   *
   * <p>Corto a propósito: un servicio de Render que arranca de cero tarda uno o dos minutos, más
   * que el corte de 60 segundos de una herramienta. Si no contestan en este tiempo no se escribe
   * nada y se avisa; el pedido igual los despierta, así que el siguiente intento los encuentra.
   */
  private static final int ESPERA_MAXIMA_SEGUNDOS = 20;

  /**
   * Cuántas veces se pregunta por el paquete antes de reportar su entrega, y cada cuánto.
   *
   * <p>Logística procesa las donaciones en segundo plano: un worker toma el mensaje de la cola y
   * recién ahí crea la asignación. Suele tardar un par de segundos; diez intentos cada dos
   * segundos cubren una cola atrasada sin acercar la herramienta al corte de 60 segundos.
   */
  private static final int INTENTOS_PAQUETE = 10;

  private static final long ESPERA_ENTRE_INTENTOS_MS = 2000;

  /**
   * Lo que se espera cada consulta del paquete. Con Logística despierta contesta en menos de un
   * segundo; sin este límite, una sola consulta con su reintento podría tardar 50.
   */
  private static final int PACIENCIA_PAQUETE_SEGUNDOS = 4;

  private final DonaTrackApi api;
  private final SesionMcp sesion;
  private final String depositoPorDefecto;
  private final long esperaEntreIntentosMs;

  @Autowired
  public SeedTools(
      DonaTrackApi api,
      SesionMcp sesion,
      @Value("${donatrack.deposito-default:DEP-UTN-01}") String depositoPorDefecto) {
    this(api, sesion, depositoPorDefecto, ESPERA_ENTRE_INTENTOS_MS);
  }

  /** Para los tests: con la espera real, probar que el paquete nunca aparece tardaría 18 segundos. */
  SeedTools(
      DonaTrackApi api, SesionMcp sesion, String depositoPorDefecto, long esperaEntreIntentosMs) {
    this.api = api;
    this.sesion = sesion;
    this.depositoPorDefecto = depositoPorDefecto;
    this.esperaEntreIntentosMs = esperaEntreIntentosMs;
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

    // Primero se despiertan los módulos con consultas. Si se arranca escribiendo, el primer POST
    // se pierde despertando al servicio y no se puede reintentar sin arriesgar un duplicado.
    java.util.Set<String> despiertos = despertar();

    // Sin estos tres no hay nada que cargar. Cargar la mitad es peor que no cargar nada: quedan
    // datos sueltos que no sirven para ningún flujo y hay que ir a limpiarlos a mano.
    java.util.List<String> dormidos =
        java.util.stream.Stream.of("Donaciones", "Donadores", "Logística")
            .filter(modulo -> !despiertos.contains(modulo))
            .toList();
    if (!dormidos.isEmpty()) {
      return "**No se cargó nada**\n\nTodavía no contestan: "
          + String.join(", ", dormidos)
          + ".\n\nArrancar de cero le lleva a Render uno o dos minutos. Ejecutá "
          + "`despertar_servicios` hasta que los cuatro digan que responden y volvé a intentar.\n";
    }

    try {
      String identId = crearIdentificador(sb, suf);
      String prodId = crearProducto(sb, suf, identId);
      String donadorId = crearDonador(sb, suf);
      String entidadId = crearEntidad(sb, suf);
      crearDeposito(sb, suf);
      // Si Incentivos no despertó, cada escritura esperaría hasta darse por vencida y la
      // herramienta pasaría el corte de 60 segundos. Mejor avisarlo y seguir: el resto de los
      // flujos no lo necesita.
      String[] incentivos =
          despiertos.contains("Incentivos")
              ? crearInsigniaYMision(sb, suf)
              : saltearIncentivos(sb, suf);
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
      ejecutarFlujo(
          sb, donadorId, prodId, incentivos[0], incentivos[1], suf, despiertos.contains("Incentivos"));
      return sb.toString();

    } catch (Exception e) {
      log.error("Error preparando la demostración", e);
      return sb + "\n⚠️ Se cortó acá: " + e.getMessage();
    }
  }

  /**
   * Consultas baratas a cada módulo, solo para que estén despiertos cuando haya que escribir.
   *
   * <p>En paralelo: si se hicieran una detrás de otra, un módulo caído sumaría su espera completa
   * al tiempo de preparación.
   */
  private java.util.Set<String> despertar() {
    java.util.Map<String, Runnable> consultas = new java.util.LinkedHashMap<>();
    consultas.put("Donaciones", () -> api.getDonaciones("/productos"));
    consultas.put("Donadores", () -> api.getDonadores("/donadores"));
    consultas.put("Logística", () -> api.getLogistica("/depositos"));
    consultas.put("Incentivos", () -> api.getIncentivos("/insignias"));

    java.util.Map<String, java.util.concurrent.CompletableFuture<Boolean>> pendientes =
        new java.util.LinkedHashMap<>();
    consultas.forEach(
        (modulo, consulta) ->
            pendientes.put(
                modulo,
                java.util.concurrent.CompletableFuture.supplyAsync(
                        () -> {
                          try {
                            consulta.run();
                            return true;
                          } catch (Exception e) {
                            log.info("{} dormido o caído al despertar: {}", modulo, e.getMessage());
                            return false;
                          }
                        },
                        Hilos.ESPERA)
                    // Se deja de esperar al llegar al plazo, pero el pedido sigue viajando: aunque no se
                    // vea la respuesta, alcanza para que Render arranque el servicio.
                    .completeOnTimeout(
                        false, ESPERA_MAXIMA_SEGUNDOS, java.util.concurrent.TimeUnit.SECONDS)));

    java.util.Set<String> despiertos = new java.util.HashSet<>();
    pendientes.forEach(
        (modulo, futuro) -> {
          if (futuro.join()) {
            despiertos.add(modulo);
          }
        });
    return despiertos;
  }

  @Tool(
      name = "preparar_modulo",
      description =
          "Carga las precondiciones de UN solo módulo, sin tocar los otros tres: identificador y "
              + "producto en Donaciones; donador, entidad y necesidad en Donadores; el depósito "
              + "por defecto en Logística; insignia y misión en Incentivos. Sirve cuando falta "
              + "solo una parte, o cuando un módulo estaba dormido y 'preparar_demo' no cargó "
              + "nada. Para cargar los cuatro de una vez está 'preparar_demo'. Requiere ADMIN. "
              + "El resultado ya viene redactado: mostrarlo tal cual.")
  public String prepararModulo(
      @ToolParam(description = "Cuál módulo cargar: donaciones, donadores, logistica o incentivos.")
          String modulo,
      @ToolParam(
              required = false,
              description =
                  "Solo para donadores: el producto que va a pedir la necesidad. Si no se indica "
                      + "se usa el primero que tenga Donaciones; si no hay ninguno se cargan el "
                      + "donador y la entidad, y se avisa que la necesidad quedó sin cargar.")
          String productoId) {

    sesion.requerirAdmin("preparar un módulo");
    Modulo m = Modulo.desde(modulo);

    // Igual que en preparar_demo: si se arranca escribiendo, el primer POST se pierde despertando
    // al servicio y no se puede reintentar sin arriesgar un duplicado.
    if (!despierto(m)) {
      return "**No se cargó nada en "
          + m.nombre()
          + "**\n\nTodavía no contesta. Arrancar de cero le lleva a Render uno o dos minutos. "
          + "Ejecutá `despertar_servicios` hasta que responda y volvé a intentar.\n";
    }

    long suf = Instant.now().getEpochSecond();
    StringBuilder sb = new StringBuilder("**Precondiciones de " + m.nombre() + "**\n\n");
    try {
      switch (m) {
        case DONACIONES -> {
          String identId = crearIdentificador(sb, suf);
          String prodId = crearProducto(sb, suf, identId);
          sb.append("\n**Para usar en los flujos**\n\n- Producto nº ").append(prodId).append("\n");
          sb.append(
              "\nSiguiente paso: `preparar_modulo` con donadores, para que haya una necesidad "
                  + "que pida ese producto.\n");
        }
        case DONADORES -> prepararDonadores(sb, suf, productoId);
        case LOGISTICA -> {
          crearDeposito(sb, suf);
          sb.append("\n**Para usar en los flujos**\n\n- Depósito ")
              .append(depositoPorDefecto)
              .append("\n");
        }
        case INCENTIVOS -> {
          String[] ids = crearInsigniaYMision(sb, suf);
          sb.append("\n**Para usar en los flujos**\n\n- Insignia ")
              .append(ids[0])
              .append("\n- Misión ")
              .append(ids[1])
              .append("\n");
        }
      }
      return sb.toString();
    } catch (Exception e) {
      log.error("Error preparando {}", m.nombre(), e);
      return sb + "\n⚠️ Se cortó acá: " + e.getMessage();
    }
  }

  /**
   * El donador, la entidad y la necesidad que les da sentido.
   *
   * <p>La necesidad pide un producto, que vive en Donaciones: por eso se acepta como parámetro y,
   * si no viene, se busca el primero que haya. Si no hay ninguno se cargan igual el donador y la
   * entidad —sirven para otros flujos— y se dice qué quedó sin cargar, en vez de no cargar nada.
   */
  private void prepararDonadores(StringBuilder sb, long suf, String productoId) throws Exception {
    String donadorId = crearDonador(sb, suf);
    String entidadId = crearEntidad(sb, suf);

    String prodId =
        productoId != null && !productoId.isBlank() ? productoId.trim() : primerProducto();
    if (prodId == null) {
      sb.append("- **Donadores** — ⚠️ la necesidad quedó sin cargar: pide un producto y ")
          .append("Donaciones no tiene ninguno, o no contestó.\n")
          .append("\n**Para usar en los flujos**\n\n- Donador nº ")
          .append(donadorId)
          .append("\n- Entidad nº ")
          .append(entidadId)
          .append("\n")
          .append("\nSiguiente paso: `preparar_modulo` con donaciones y volver a correr este, ")
          .append("o pasarle el producto a mano.\n");
      return;
    }

    String necesidadId = crearNecesidad(sb, entidadId, prodId);
    sb.append("\n**Para usar en los flujos**\n\n- Donador nº ")
        .append(donadorId)
        .append("\n- Entidad nº ")
        .append(entidadId)
        .append("\n- Producto nº ")
        .append(prodId)
        .append("\n- Necesidad nº ")
        .append(necesidadId)
        .append(" (20 unidades, EXTRAORDINARIA)\n");
  }

  /** El primer producto que tenga Donaciones, o null si no hay ninguno o no contestó. */
  private String primerProducto() {
    try {
      JsonNode productos = mapper.readTree(api.getDonaciones("/productos"));
      if (productos.isArray() && !productos.isEmpty()) {
        String id = productos.get(0).path("id").asText("");
        return id.isBlank() ? null : id;
      }
    } catch (Exception e) {
      log.info("No se pudieron leer los productos para la necesidad: {}", e.getMessage());
    }
    return null;
  }

  /** Una consulta barata al módulo, con el mismo plazo corto que usa preparar_demo. */
  private boolean despierto(Modulo m) {
    return java.util.concurrent.CompletableFuture.supplyAsync(
            () -> {
              try {
                consultaBarata(m);
                return true;
              } catch (Exception e) {
                log.info("{} dormido o caído: {}", m.nombre(), e.getMessage());
                return false;
              }
            },
            Hilos.ESPERA)
        .completeOnTimeout(false, ESPERA_MAXIMA_SEGUNDOS, java.util.concurrent.TimeUnit.SECONDS)
        .join();
  }

  private void consultaBarata(Modulo m) {
    switch (m) {
      case DONACIONES -> api.getDonaciones("/productos");
      case DONADORES -> api.getDonadores("/donadores");
      case LOGISTICA -> api.getLogistica("/depositos");
      case INCENTIVOS -> api.getIncentivos("/insignias");
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
          .append(". Si ya existía está bien; si no, las donaciones van a fallar. Detalle: ")
          .append(e.getMessage())
          .append("\n");
    }
  }

  private String[] saltearIncentivos(StringBuilder sb, long suf) {
    sb.append("- **Incentivos** — ⚠️ no respondió al despertarlo, así que no se le cargó nada. ")
        .append("El resto de los flujos se puede mostrar igual.\n");
    return new String[] {"ins-" + suf, "mis-" + suf};
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
      sb.append("- **Incentivos** — ⚠️ no respondió, así que el flujo de incentivos no se va a ")
          .append("poder mostrar. Detalle: ")
          .append(e.getMessage())
          .append("\n");
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
   */
  private void ejecutarFlujo(
      StringBuilder sb,
      String donadorId,
      String prodId,
      String insId,
      String misId,
      long suf,
      boolean incentivosDespierto) {
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

    reportarEntregaCuandoExista(sb, donacionId);

    // Mismo criterio que al cargar la insignia y la misión: si Incentivos no despertó, cada POST
    // esperaría hasta darse por vencido y, sumado a la espera del worker, la herramienta pasaría
    // el corte de 60 segundos.
    if (!incentivosDespierto) {
      sb.append("- ⚠️ Incentivos no respondió al despertarlo, así que el donador no se procesó.\n");
      return;
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

  /**
   * Reporta la entrega del paquete de una donación, pero recién cuando Logística lo tiene.
   *
   * <p>Al registrar la donación, Donaciones ya le avisó a Logística, que la encola. El worker la
   * procesa en segundo plano y recién ahí crea la asignación: mientras tanto el paquete da 404, y
   * reportar la entrega antes de que exista falla. Por eso se pregunta hasta que aparezca.
   *
   * <p>Al endpoint se le manda solo el paquete: la donación, el producto y la cantidad Logística
   * los saca de la asignación guardada, y cualquier otro campo lo ignora.
   */
  void reportarEntregaCuandoExista(StringBuilder sb, String donacionId) {
    // Es el nombre que le pone el worker: no hay otra forma de llegar al paquete.
    String paqueteId = "paq-" + donacionId;
    try {
      if (!esperarPaquete(paqueteId)) {
        sb.append("- ⚠️ El paquete ")
            .append(paqueteId)
            .append(" no apareció en Logística después de ")
            .append(INTENTOS_PAQUETE)
            .append(" intentos, así que la entrega no se reportó. El worker que procesa la cola ")
            .append("podría estar caído o atrasado. Cuando el paquete aparezca, se puede reportar ")
            .append("con `reportar_entrega`.\n");
        return;
      }
      api.postLogistica(
          "/api/asignaciones/reportar-entrega", DonaTrackApi.cuerpo("paqueteid", paqueteId));
      sb.append("- Entrega del paquete ").append(paqueteId).append(" reportada\n");
    } catch (InterruptedException e) {
      // Se respeta el pedido de cortar: no se reporta nada y la marca queda para quien siga.
      Thread.currentThread().interrupt();
      sb.append("- ⚠️ Se interrumpió la espera del paquete ")
          .append(paqueteId)
          .append(", así que la entrega no se reportó.\n");
    } catch (Exception e) {
      sb.append("- ⚠️ No se pudo reportar la entrega de ")
          .append(paqueteId)
          .append(": ")
          .append(e.getMessage())
          .append("\n");
    }
  }

  /**
   * Pregunta por el paquete hasta que exista. Devuelve false si después de todos los intentos
   * sigue sin aparecer.
   *
   * <p>Solo un 404 quiere decir «todavía no»: es Logística contestando que el worker no terminó.
   * Cualquier otro error es que Logística no anda, y seguir insistiendo consumiría el plazo de la
   * herramienta sin cambiar nada, así que se corta ahí.
   */
  private boolean esperarPaquete(String paqueteId) throws InterruptedException {
    for (int intento = 1; intento <= INTENTOS_PAQUETE; intento++) {
      if (intento > 1) {
        Thread.sleep(esperaEntreIntentosMs);
      }
      if (existePaquete(paqueteId)) {
        return true;
      }
      log.info(
          "El paquete {} todavía no existe (intento {} de {})", paqueteId, intento, INTENTOS_PAQUETE);
    }
    return false;
  }

  private boolean existePaquete(String paqueteId) throws InterruptedException {
    try {
      java.util.concurrent.CompletableFuture.supplyAsync(
              () -> api.getLogistica("/api/asignaciones/paquetes/" + paqueteId), Hilos.ESPERA)
          .get(PACIENCIA_PAQUETE_SEGUNDOS, java.util.concurrent.TimeUnit.SECONDS);
      return true;
    } catch (java.util.concurrent.ExecutionException e) {
      if (e.getCause() instanceof DonaTrackApi.NoEncontrado) {
        return false;
      }
      throw new IllegalStateException(
          "No se pudo consultar el paquete. " + e.getCause().getMessage(), e.getCause());
    } catch (java.util.concurrent.TimeoutException e) {
      throw new IllegalStateException(
          "Logística no contestó en "
              + PACIENCIA_PAQUETE_SEGUNDOS
              + " segundos al consultar el paquete.");
    }
  }

  private String idDe(String respuesta) throws Exception {
    JsonNode nodo = mapper.readTree(respuesta);
    return nodo.path("id").asText();
  }
}
