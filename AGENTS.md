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

**La contraseña de admin no tiene valor por defecto** (`DONATRACK_ADMIN_PASSWORD`): este
repositorio es público. Vacía, el ingreso de admin queda deshabilitado y la tool dice cómo
habilitarlo. No agregarle un default, ni escribirla en un log o en una respuesta: hay tests que
lo vigilan.

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

155 tests, 0 fallos.
