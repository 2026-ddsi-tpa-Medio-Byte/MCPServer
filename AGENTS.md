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
ConsultaTools     10 tools de solo lectura
OperacionTools    11 tools que modifican estado
DonaTrackApi      único punto de contacto con los módulos
```

Las descripciones de las tools están escritas **para que las lea un modelo**: dicen cuándo usar
cada una y qué hace falta saber antes. Mantener ese estilo al agregar nuevas.

## Probarlo sin Claude Desktop

Empaquetar y hablarle el protocolo directamente:

```bash
mvn package -DskipTests
java -jar target/donatrack-mcp-1.0-SNAPSHOT.jar
```

Enviando por stdin un `initialize` y después `tools/list` tiene que responder el handshake y las
21 herramientas. Si imprime cualquier otra cosa en pantalla, eso está rompiendo el protocolo.

## Antes de terminar un cambio

```bash
mvn test
```

11 tests, 0 fallos.
