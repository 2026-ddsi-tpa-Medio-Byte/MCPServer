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

Cuando conecta, aparece el ícono de herramientas en el campo de texto y se pueden ver las 21
tools disponibles.

## Las 21 herramientas

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
