# DonaTrack — Servidor MCP (Entrega 5)

Permite operar DonaTrack desde Claude Desktop conversando, sin conocer los endpoints.
Corre **local** y se comunica con Claude por entrada y salida estándar.

No tiene base de datos ni lógica de negocio propia: cada herramienta llama al módulo que
corresponde. Las validaciones —cantidad mayor a cero, donador habilitado, producto existente—
siguen viviendo en los módulos, como pide la consigna.

## Requisitos

- Java 21
- Maven
- Los cuatro módulos de DonaTrack corriendo

## Compilar

```bash
mvn clean package
```

Genera `target/donatrack-mcp-1.0-SNAPSHOT.jar`.

## Conectarlo a Claude Desktop

Copiar el contenido de `claude_desktop_config.json` a:

```
%APPDATA%\Claude\claude_desktop_config.json
```

Si el archivo ya existe con otros servidores, agregar solo la entrada `donatrack` dentro de
`mcpServers`. Después **reiniciar Claude Desktop** por completo, no alcanza con cerrar la
ventana.

Cuando conecta, aparece el ícono de herramientas en el campo de texto y se pueden ver las 32
tools disponibles.

## Las herramientas

Están agrupadas por lo que alguien querría hacer, no una por endpoint. Por ejemplo,
`consultar_donadores` sirve tanto para listar todos como para traer uno: desde el punto de
vista de quien pregunta es la misma intención.

### Consulta

| Herramienta | Para qué |
|---|---|
| `consultar_donadores` | Todos o uno, con su estado |
| `consultar_estadisticas_donador` | Categoría, insignias y misión en curso |
| `consultar_quejas_de_donador` | Las quejas que recibió |
| `puede_donar` | Si tiene la cuenta habilitada |
| `consultar_entidades` | Comedores, hogares y demás |
| `consultar_necesidades` | Por número o por producto, con su progreso |
| `consultar_productos` | El catálogo donable |
| `consultar_donaciones` | Todas, las de un donador, o una puntual |
| `consultar_depositos_y_stock` | Depósitos de Logística o stock de un producto |
| `consultar_insignias_y_misiones` | El catálogo de Incentivos |

### Operación

| Herramienta | Para qué |
|---|---|
| `registrar_donacion` | La operación central del sistema |
| `registrar_necesidad` | Lo que necesita una entidad |
| `registrar_queja` | Sobre una donación entregada |
| `crear_donador` | Alta de donador |
| `crear_entidad` | Alta de entidad beneficiaria |
| `crear_producto` | Alta de producto donable |
| `crear_identificador` | Código de barras o QR |
| `modificar_necesidad` | Cambiar urgencia, cantidad o descripción |
| `modificar_entidad` | Cambiar datos de contacto |
| `eliminar_necesidad` | Borrar una necesidad |
| `procesar_donador_en_incentivos` | Forzar la evaluación de una misión |
| `reportar_entrega` | Cerrar el circuito: la donación se da por cumplida |
| `cambiar_estado_donador` | VERIFICADO, SOSPECHOSO o BANEADO |
| `cambiar_categoria_donador` | Dejarlo en una categoría determinada |

### Sesión

| Herramienta | Para qué |
|---|---|
| `iniciar_sesion` | Entrar como ADMIN o como un donador |
| `quien_soy` | Con qué permisos se está operando |
| `cerrar_sesion` | Salir |

### Demostración

| Herramienta | Para qué |
|---|---|
| `guion_demo` | El orden sugerido para mostrar el sistema |
| `despertar_servicios` | Sacar del sueño a los cuatro módulos de Render |
| `reiniciar_sistema` | Vaciar las cuatro bases |
| `preparar_demo` | Cargar las precondiciones de todos los flujos |
| `estado_del_sistema` | Cómo está todo ahora, módulo por módulo |

## Las operaciones cuentan qué provocaron

Registrar una donación no devuelve el JSON que contestó Donaciones, sino un resumen de qué
cambió en cada módulo:

```
**Donación registrada** · traza `mcp-4f21a9`

- **Donaciones** — donación nº 12: 10 unidades de «Arroz», estado INGRESADA.
- **Donadores** — confirmó que el donador nº 1 existe y que está habilitado para donar.
- **Logística** — stock del producto: 0 → 0. No subió, así que la asignó a una necesidad.
- **Donadores** — necesidad nº 7 «Arroz para el comedor» ░░░░░░░░░░ 0/20, sin cambios.

> Ojo: la necesidad **no** se satisface al donar. Recién cambia cuando Logística reporta la
> entrega del paquete.

Siguiente paso sugerido: `reportar_entrega`.
```

Se saca una foto del sistema antes y otra después, y se cuenta la diferencia. Lo que **no**
cambió también se dice: que una necesidad siga en 0/20 después de una donación no es un error,
es la regla del sistema, y es justo lo que más se malinterpreta.

Ninguna de esas consultas puede hacer fallar la operación: si un módulo no contesta, esa línea
dice que no contestó en vez de dar por hecho que no pasó nada.

La **traza** que aparece arriba viaja en el header `X-Trace-Id`. Donaciones y Donadores la
escriben en cada línea de log y la reenvían al módulo siguiente, así que buscándola en Datadog
se ve qué hizo cada uno. Logística e Incentivos todavía no la propagan: si la operación entra por
ellos —una entrega, un procesamiento— el relato lo aclara en vez de prometer el recorrido.

## Cómo se usa

Una vez conectado, se le habla a Claude en lenguaje natural:

> «¿Qué productos se pueden donar?»

> «Registrá una donación de 10 kilos de arroz a nombre del donador 1»

> «¿Por qué el donador 2 no puede donar?»

> «El comedor Los Pinos necesita 30 frazadas con urgencia alta»

> «Mostrame las donaciones de Ana y en qué estado están»

Claude elige la herramienta, la llama y explica el resultado. Si un módulo rechaza algo, el
motivo real llega hasta el usuario en vez de perderse en un stack trace.

## Configuración

Todas tienen valor por defecto apuntando a Render. Se pueden cambiar por variables de entorno:

| Variable | Default |
|---|---|
| `DONACIONES_URL` | `https://donatrack-donaciones.onrender.com` |
| `DONADORES_URL` | `https://donadoresyentidadesv2.onrender.com` |
| `LOGISTICA_URL` | `https://logistica-i4rf.onrender.com` |
| `INCENTIVOS_URL` | `https://incentivos-wtbd.onrender.com` |
| `DEPOSITO_DEFAULT` | `DEP-UTN-01` |

## Notas de diseño

**Por qué los logs van a un archivo.** Con stdio, la salida estándar transporta el protocolo
MCP: cualquier otra cosa escrita ahí lo corrompe y Claude Desktop no puede conectarse. Por eso
no hay banner de Spring, no levanta servidor web, y los logs van a `donatrack-mcp.log`.

**Por qué no valida nada.** Si el MCP repitiera las reglas de negocio, habría dos lugares donde
mantenerlas y tarde o temprano dirían cosas distintas. Cuando un módulo rechaza una operación,
el mensaje sube tal cual para que Claude pueda explicar el motivo real.

**Por qué los timeouts son largos.** Los servicios gratuitos de Render se duermen y el primer
pedido puede tardar casi un minuto. Si un módulo no responde, la herramienta lo dice y sugiere
reintentar, que es lo que suele resolverlo.

## Diagnóstico

Si Claude Desktop no lo detecta:

1. Revisar `donatrack-mcp.log` en la carpeta desde donde arranca.
2. Probar el jar a mano: `java -jar target/donatrack-mcp-1.0-SNAPSHOT.jar`. Tiene que quedar
   esperando sin imprimir nada en pantalla; si imprime algo, eso está rompiendo el protocolo.
3. Verificar que la ruta del jar en la configuración sea absoluta y con las barras escapadas.
4. Reiniciar Claude Desktop del todo.
