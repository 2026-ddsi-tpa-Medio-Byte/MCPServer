# DonaTrack — Servidor MCP (Entregas 5 y 6)

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

Cuando conecta, aparece el ícono de herramientas en el campo de texto y se pueden ver las 51
tools disponibles.

### Contraseña de administrador

Las operaciones que modifican datos piden iniciar sesión como ADMIN (`iniciar_sesion`). La
contraseña **no tiene valor por defecto**: este repositorio es público y una clave escrita acá la
conocería cualquiera. Hasta que se define, el ingreso como ADMIN está deshabilitado y la
herramienta lo dice (como DONADOR se puede entrar igual).

Se pasa por el bloque `"env"` del servidor, en el mismo `claude_desktop_config.json`:

```json
{
  "mcpServers": {
    "donatrack": {
      "command": "java",
      "args": ["-jar", "C:\\ruta\\al\\donatrack-mcp-1.0-SNAPSHOT.jar"],
      "env": {
        "DONATRACK_ADMIN_PASSWORD": "elegir-una-clave-propia"
      }
    }
  }
}
```

Reemplazar el valor por una clave propia (no copiar el ejemplo tal cual) y **reiniciar Claude
Desktop del todo**. Ese archivo vive en la máquina de quien lo usa y no se sube al repositorio:
no pegarle ahí una clave real al `claude_desktop_config.json` de ejemplo que está acá.

La contraseña no se escribe en ningún log del servidor ni aparece en ninguna respuesta. La
escribe quien la tipea en la conversación, así que Claude Desktop puede guardarla en sus propios
registros: conviene una clave que no se use para nada más.

### Usar el gateway

Desde la Entrega 6 el MCP consume el sistema **a través del API Gateway**. Se activa con una
variable en el mismo bloque `"env"`:

```json
"env": {
  "DONATRACK_GATEWAY_URL": "https://<el-gateway>.onrender.com",
  "DONATRACK_ADMIN_PASSWORD": "elegir-una-clave-propia"
}
```

Con la variable definida, **todos** los pedidos van por el gateway y las URLs directas de cada
módulo no se usan. El gateway reenvía cada módulo por un prefijo que quita antes de llegar:

| Módulo | Prefijo | Ejemplo |
|---|---|---|
| Donaciones | `/donaciones` | `{gw}/donaciones/productos/1` → `/productos/1` |
| Donadores y Entidades | `/donadores` | `{gw}/donadores/necesidades/1` → `/necesidades/1` |
| Logística | `/logistica` | `{gw}/logistica/api/depositos` → `/api/depositos` |
| Incentivos | `/incentivos` | `{gw}/incentivos/donadores/3/insignias` → `/donadores/3/insignias` |
| Servicio de Consultas | `/consultas` | (lo usan las consultas del modelo de lectura) |

Sin la variable, el MCP llama a cada módulo por su URL directa, como antes: sirve mientras el
gateway no exista. `quien_soy` y `estado_del_sistema` dicen cuál de los dos modos está activo.
Con el gateway delante hay dos arranques en frío encadenados en Render (el del gateway y el del
módulo): `despertar_servicios` despierta primero el gateway y después cada módulo a través de él.

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
| `consultar_identificadores` | Los identificadores (CODIGODEBARRAS o QR) que hacen falta para crear un producto |
| `consultar_donaciones` | Todas, las de un donador, o una puntual |
| `consultar_depositos_y_stock` | Depósitos con su stock real, el de uno por producto, o el de un producto |
| `consultar_asignaciones` | Paquetes de Logística: por paquete, donación, estado o necesidad |
| `consultar_insignias_y_misiones` | El catálogo de Incentivos |
| `consultar_mision_de_donador` | La misión en curso de un donador y sus insignias |
| `consultar_ficha_donador` | **Modelo de lectura.** La ficha integral: categoría, misión con lo que le falta, quejas, entidades |
| `consultar_necesidades_urgentes` | **Modelo de lectura.** Necesidades sin cubrir, con el stock de esos productos |
| `consultar_estado_depositos` | **Modelo de lectura.** Ocupación, asignaciones, tiempo de entrega y entidades atendidas |
| `consultar_calidad_donaciones` | **Modelo de lectura.** Proporción de quejas por categoría y ranking de donadores |
| `consultar_modelo_lectura` | **Modelo de lectura.** Cómo viene la sincronización: último evento por módulo, atraso, DLQ |

### Operación

| Herramienta | Para qué |
|---|---|
| `registrar_donacion` | La operación central del sistema |
| `registrar_necesidad` | Lo que necesita una entidad |
| `registrar_queja` | Sobre una donación entregada (requiere ADMIN) |
| `crear_donador` | Alta de donador |
| `crear_entidad` | Alta de entidad beneficiaria |
| `crear_producto` | Alta de producto donable |
| `crear_identificador` | Código de barras o QR |
| `modificar_necesidad` | Cambiar urgencia, cantidad o descripción |
| `modificar_entidad` | Cambiar datos de contacto |
| `eliminar_necesidad` | Borrar una necesidad |
| `procesar_donador_en_incentivos` | Forzar la evaluación de una misión |
| `reportar_entrega` | Cerrar el circuito: la donación se da por cumplida |
| `cambiar_estado_donacion` | INGRESADA, ACEPTADA o CONQUEJA, a mano |
| `crear_insignia` | Alta de insignia en Incentivos |
| `crear_mision` | Alta de misión en Incentivos |
| `asignar_mision_a_donador` | Dejarle una misión en curso a un donador |
| `crear_deposito` | Alta de depósito en Logística |
| `modificar_deposito` | Cambiar nombre, dirección, capacidad o algoritmo |
| `eliminar_deposito` | Borrar un depósito sin stock |
| `configurar_algoritmo_deposito` | SUBATENDIDOS o PRIOSCORE |
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
| `reiniciar_sistema` | Vaciar las cuatro bases y el modelo de lectura |
| `reiniciar_modulo` | Vaciar un módulo solo, dejando los otros tres como estaban |
| `preparar_demo` | Cargar las precondiciones de todos los flujos |
| `preparar_modulo` | Cargar las precondiciones de un módulo solo |
| `estado_del_sistema` | Cómo está todo ahora, módulo por módulo, y la sincronización del modelo de lectura |
| `resincronizar_modelo_lectura` | Que los cuatro módulos vuelvan a publicar su estado al modelo de lectura |

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

## El modelo de lectura (CQRS)

Desde la Entrega 6 hay un **Servicio de Consultas**: una base MongoDB que combina los cuatro
módulos y se actualiza por eventos (RabbitMQ), sin llamar a los módulos en el momento. Resuelve
las consultas que cruzan varios módulos. Solo se alcanza **a través del gateway**
(`{gw}/consultas/...`): sin `DONATRACK_GATEWAY_URL`, las cinco herramientas del modelo de lectura
lo dicen y no fallan.

| Pedido | Herramienta |
|---|---|
| «Armame un resumen del donador 12: categoría, cuánto le falta para su misión y quejas» | `consultar_ficha_donador` |
| «¿Qué necesidades urgentes siguen sin cubrir y cuánto stock tenemos de esos productos?» | `consultar_necesidades_urgentes` |
| «¿Cómo están los depósitos? ¿Cuánto tardan en entregar?» | `consultar_estado_depositos` |
| «¿Qué categorías reciben más quejas? ¿Qué donadores acumulan más?» | `consultar_calidad_donaciones` |
| «¿La sincronización viene al día?» | `consultar_modelo_lectura` |

**Consistencia eventual.** Justo después de una operación (una donación, una entrega, una queja)
la consulta puede no reflejarla todavía: lo normal es menos de un segundo, pero con el servicio
dormido pueden ser unos segundos. Si falta algo recién hecho, se espera un par de segundos y se
reintenta una vez; si sigue faltando, se dice «el modelo de lectura todavía no lo refleja», no que
falló. Un 404 de la ficha tampoco prueba que el donador no exista: puede no estar sincronizado.

`reiniciar_sistema` también vacía Mongo, para que arranque vacío junto con los módulos.
`resincronizar_modelo_lectura` le pide a cada módulo que vuelva a publicar su estado
(`POST /admin/republicar-eventos`); un módulo que todavía no lo implementó se informa como tal y
no cuenta como falla.

## Configuración

Se pasan como variables de entorno, normalmente por el bloque `"env"` del servidor en
`claude_desktop_config.json`. Salvo la contraseña de admin, todas tienen valor por defecto:

| Variable | Default |
|---|---|
| `DONACIONES_URL` | `https://donatrack-donaciones.onrender.com` |
| `DONADORES_URL` | `https://donadoresyentidadesv2.onrender.com` |
| `LOGISTICA_URL` | `https://logistica-i4rf.onrender.com` |
| `INCENTIVOS_URL` | `https://incentivos-wtbd.onrender.com` |
| `DEPOSITO_DEFAULT` | `DEP-UTN-01` |
| `DONATRACK_GATEWAY_URL` | vacía: sin gateway, cada módulo por su URL directa |
| `DONATRACK_PREFIJO_DONACIONES` | `/donaciones` |
| `DONATRACK_PREFIJO_DONADORES` | `/donadores` |
| `DONATRACK_PREFIJO_LOGISTICA` | `/logistica` |
| `DONATRACK_PREFIJO_INCENTIVOS` | `/incentivos` |
| `DONATRACK_PREFIJO_CONSULTAS` | `/consultas` |
| `DONATRACK_ADMIN_PASSWORD` | **sin valor por defecto**: vacía, el ingreso como ADMIN está deshabilitado |

Los prefijos solo se usan si hay gateway, y se pueden escribir con o sin barra. Si
`DONATRACK_GATEWAY_URL` tiene valor, las cuatro URLs directas de arriba se ignoran.

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
