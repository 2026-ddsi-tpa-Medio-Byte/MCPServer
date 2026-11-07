# Servidor MCP — DonaTrack

Componente de la Entrega 5 del TP anual de DDS (K3003, UTN 2026). Permite operar DonaTrack desde
Claude Desktop conversando en lenguaje natural.

Corre **local** y se comunica con Claude por entrada y salida estándar (stdio). Es un cliente
HTTP de los cuatro módulos: **no tiene base de datos ni lógica de negocio propia**.

## Reglas que no se rompen

- **El MCP no valida nada.** Las reglas de negocio viven en los módulos y ahí se quedan. Si el
  MCP las repitiera, habría dos lugares donde mantenerlas y tarde o temprano dirían cosas
  distintas. Cuando un módulo rechaza algo, el mensaje real sube al usuario.
- Commits con la identidad del alumno, sin `Co-Authored-By`.

## Lo que rompe el protocolo

**Con stdio, la salida estándar transporta el protocolo MCP.** Cualquier otra cosa escrita ahí
lo corrompe y Claude Desktop no puede conectarse. Por eso en `application.properties`:

```properties
spring.main.banner-mode=off
spring.main.web-application-type=none
logging.file.name=donatrack-mcp.log
logging.pattern.console=
```

No agregar nada que imprima por consola.

## El SDK

Spring AI **1.0.0**. Las anotaciones son `@Tool` y `@ToolParam` de
`org.springframework.ai.tool.annotation`. Parte de la documentación menciona `@McpTool` y
`@McpToolParam`, que **no existen en esta versión**: verificar contra el JAR antes de escribir.

Las tools se registran con `MethodToolCallbackProvider` en `McpServerApplication`.

## Cómo están organizadas las tools

Agrupadas por **intención**, no una por endpoint. `consultar_donadores` sirve para listar todos
o traer uno: para quien pregunta es la misma intención, y dar dos herramientas casi iguales hace
que el modelo elija mal más seguido.

```
ConsultaTools     18 tools de solo lectura (5 del modelo de lectura)
OperacionTools    22 tools que modifican estado
AuthTools          3 tools de sesión
SeedTools          2 tools que cargan precondiciones (todo, o un módulo solo)
DemoTools          6 tools para conducir la demostración
Modulo            resuelve el nombre del módulo en las tools que trabajan sobre uno
DonaTrackApi      único punto de contacto con los módulos
```

Las descripciones de las tools están escritas **para que las lea un modelo**: dicen cuándo usar
cada una y qué hace falta saber antes. Mantener ese estilo al agregar nuevas.

## El gateway, la contraseña y los servicios suspendidos (Entrega 6)

**El gateway vive solo en `DonaTrackApi`.** Con `donatrack.gateway-url` (variable
`DONATRACK_GATEWAY_URL`) la base de cada módulo es el gateway más su prefijo (`/donaciones`,
`/donadores`, `/logistica`, `/incentivos`); vacía, es la URL directa de siempre. Las tools arman
sus paths sin enterarse: no poner el prefijo en ninguna. Con gateway hay dos arranques en frío
encadenados, así que `despertar_servicios` despierta primero el gateway y después los módulos a
través de él; los dos plazos suman 40 segundos, por debajo del corte de 60.

**El ADMIN entra por configuración local, no por contraseña** (`DONATRACK_ADMIN_LOCAL=true`,
propiedad `donatrack.admin-local`, apagada por defecto). Con el modo prendido, `iniciar_sesion`
con rol ADMIN entra sin contraseña y `DONATRACK_ADMIN_PASSWORD` **no se usa** (se puede borrar
de la configuración); si el modelo igual manda una, se ignora: no se compara, no se loguea, no se
repite. La configuración recomendada para el equipo del administrador es solo
`DONATRACK_GATEWAY_URL` y `DONATRACK_ADMIN_LOCAL=true`. Por qué: el MCP corre local con la cuenta
del usuario, así que quien puede tipear en ese Claude Desktop también puede leer la configuración
(la contraseña por chat no protegía nada), y así el modelo nunca ve una credencial. Además, Claude
se niega a escribir contraseñas para autenticarse: por el chat el ingreso no funcionaba. La
descripción de la tool le dice al modelo que llame sin contraseña y que **nunca** se la pida al
usuario; `quien_soy` informa si el modo está activo. Solo `true` lo prende: se lee como texto para
que un valor mal escrito lo deje apagado en vez de impedir que Spring arranque.

**La contraseña de admin no tiene valor por defecto** (`DONATRACK_ADMIN_PASSWORD`): este
repositorio es público. Sin modo admin local, vale lo de antes: vacía, el ingreso de admin queda
deshabilitado y la tool dice cómo habilitarlo; llamada sin contraseña, la respuesta aclara que no
se escribe en el chat y cómo prender el modo local. No agregarle un default, ni escribirla en un
log o en una respuesta: hay tests que lo vigilan.

**Dónde está la configuración de Claude Desktop.** Instalado desde la **Microsoft Store**, lee
`%LOCALAPPDATA%\Claude-Data\claude_pzs8sxrjxfjjc\claude_desktop_config.json`, **no**
`%APPDATA%\Claude\claude_desktop_config.json` (ese puede existir igual y editarlo no hace nada,
sin dar error). La forma segura de abrir el archivo correcto es Settings → Developer → Edit
Config. El log que Claude Desktop lleva del MCP queda en
`%LOCALAPPDATA%\Claude\logs\mcp-server-donatrack.log`.

**Dormido no es suspendido.** Un servicio dormido de Render despierta con el primer pedido. Uno
suspendido por su dueño contesta 503 con el header `x-render-routing: suspend-by-user` y no
despierta nunca hasta que lo reanudan desde el panel: `DonaTrackApi.Suspendido`, que
`despertar_servicios` marca aparte (⛔) en vez de pedir que se repita.

## El modelo de lectura: consistencia eventual (Entrega 6)

Las cinco tools de `ConsultaTools` que terminan en el Servicio de Consultas
(`consultar_ficha_donador`, `_necesidades_urgentes`, `_estado_depositos`, `_calidad_donaciones` y
`consultar_modelo_lectura`) leen una base que se actualiza **por eventos**: justo después de una
operación pueden no reflejarla. Sus descripciones lo dicen y mandan a esperar un par de segundos y
reintentar una vez; **no meter esa espera dentro de la tool**, porque solo el modelo sabe si
acaba de operar. El contrato está en `guias-entrega-6/CONTRATO-CONSULTAS.md`.

Dos cuidados que ya se tomaron y hay que conservar: un **404 de la ficha no es «no existe»** (puede
no estar sincronizado, y la tool lo dice), y a la ficha se le agrega `avisoDelMcp` cuando
`fuentes` trae un `false`, porque un `null` suelto se lee como «no tiene» cuando es «todavía no
llegó». Los parámetros de URL se codifican uno por uno (`DonaTrackApi.getConsultas`): una categoría
con `&` no se corta ni se codifica dos veces.

`reiniciar_sistema` vacía también Mongo (`DELETE {gw}/consultas/admin/reset`), y
`resincronizar_modelo_lectura` llama a `POST /admin/republicar-eventos` en los cuatro módulos. Ese
endpoint contesta 404 si el módulo no lo implementó (no es una falla) y, sin `RABBITMQ_URL`,
503 con texto (Donadores) o 200 con `eventosHabilitados: false` (Donaciones): las dos formas se
informan como «no tiene configurado RabbitMQ».

## El relato de registrar una necesidad

Desde noviembre de 2026 Donadores **no suma** a la necesidad cuando Logística le aparta stock al
crearla: nace en 0 y sube recién cuando se entrega el paquete `paq-solicitud-…`. Por eso el relato
**no mira `cantidadActual`** para saber si se le asignó stock (daría siempre 0 y culparía a
Logística): busca en `GET /api/asignaciones?estado=ASIGNADA&necesidadid=<id>` las de origen
`SOLICITUD_DONADORES` (se vuelve a filtrar por necesidad de este lado) y compara el stock antes y
después. Dice qué paquete armó Logística, cuánto bajó el stock y que la necesidad sigue en 0 hasta
la entrega, que es la regla. Si esa consulta no contesta, la baja del stock se cuenta como indicio,
no como asignación confirmada.

Al entregar ese `paq-solicitud-…`, el relato de `reportar_entrega` **no busca donación** (no la
tiene: `donacionDe` devuelve vacío y no se pide `/donaciones/solicitud-…`). Producto, cantidad y
necesidad salen de la asignación, y es ahí donde la necesidad sube.

## Probarlo sin Claude Desktop

Empaquetar y hablarle el protocolo directamente:

```bash
mvn package -DskipTests
java -jar target/donatrack-mcp-1.0-SNAPSHOT.jar
```

Enviando por stdin un `initialize` y después `tools/list` tiene que responder el handshake y las
51 herramientas. Si imprime cualquier otra cosa en pantalla, eso está rompiendo el protocolo.

## Antes de terminar un cambio

```bash
mvn test
```

168 tests, 0 fallos.
