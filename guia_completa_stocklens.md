# Guía de Comprensión de StockLens: Frontend, Backend y Pruebas

Esta guía explica en detalle cómo funciona el proyecto **StockLens**, qué significan sus conceptos financieros, cómo está estructurado su sistema de pruebas (los 20 tests actuales) y cómo está configurado Gradle.

---

## 1. Lo Básico: ¿Qué hace y qué muestra StockLens?

### ¿Qué hace el programa?
**StockLens** es un *stock screener* (o filtrador de acciones) para empresas de EE. UU. que cotizan en bolsa. Su función principal es permitir a un usuario buscar y filtrar una lista de 18 empresas de gran capitalización (configurable en el backend) según ciertos criterios financieros, y ordenar los resultados según una métrica elegida. 

No guarda datos en una base de datos persistente (como PostgreSQL). En su lugar, consume los datos en tiempo real desde la API externa de **Finnhub** y los guarda temporalmente en una caché en memoria en el servidor (con una duración o *TTL* de 10 minutos) para evitar consumir la cuota de la API externa de forma excesiva.

### ¿Qué muestra el Frontend y qué son esos términos de bolsa?
El frontend muestra un formulario de controles en la parte superior y una tabla con los resultados en la parte inferior. Estos son los términos clave:

1. **Company (Nombre de la Empresa):** El nombre comercial de la compañía (ej. *Microsoft Corporation*).
2. **Ticker (Símbolo de cotización):** La abreviatura única de 1 a 5 letras que identifica a una acción en la bolsa (ej. `MSFT` para Microsoft, `AAPL` para Apple, `GOOGL` para Alphabet/Google).
3. **Sector:** La industria o área general de la economía a la que pertenece la empresa (ej. *Technology*, *Financial Services*, *Healthcare*).
4. **Price (Precio de la acción):** Cuánto cuesta comprar una sola acción de esa empresa en dólares estadounidenses ($).
5. **P/E Ratio (Price-to-Earnings / Relación Precio-Ganancia):** 
   - **Qué es:** Es una métrica de valoración que se calcula dividiendo el precio actual de la acción por su beneficio por acción (EPS) anual.
   - **Para qué sirve:** Indica cuántos dólares están dispuestos a pagar los inversores hoy por cada dólar de ganancia anual que genera la empresa. Un P/E bajo puede sugerir que la acción está "barata" o infravalorada (o que tiene problemas), mientras que un P/E alto puede significar que está "cara" o que los inversores esperan un gran crecimiento a futuro.
6. **Market Cap (Market Capitalization / Capitalización de Mercado):**
   - **Qué es:** Es el valor total de todas las acciones de la empresa en circulación. Se calcula multiplicando el precio actual de una acción por el número total de acciones existentes (`Precio * Acciones en circulación`).
   - **Para qué sirve:** Representa el tamaño de la empresa en dólares. En el frontend se expresa en miles de millones (B de *Billions*, ej. `$3.10 B`) o billones en inglés (T de *Trillions*, ej. `$3.10 T`).

---

## 2. El Sistema de Pruebas: Explicación de los 20 Tests

El proyecto cuenta con un total de **20 pruebas automáticas** que validan tanto la lógica del negocio como el contrato HTTP de extremo a extremo. La suite se redujo intencionalmente para concentrarse en la clase más crítica (`StockService`) y en el flujo HTTP completo, quedando en exactamente **10 tests unitarios + 10 tests de integración** en solo dos archivos. Todo el acceso a Finnhub está simulado o "mockeado", de modo que no se hacen llamadas reales a Internet.

A continuación se detalla la distribución de los 20 tests:

| Clase de Test | Cantidad | Tipo | Qué Prueba |
| :--- | :---: | :---: | :--- |
| [`StockServiceTest`](backend/src/test/java/com/stocklens/service/StockServiceTest.java) | 10 | **Unitario** | La lógica de negocio de `StockService` con el proveedor mockeado con Mockito y un reloj falso (`MutableClock`): búsqueda por nombre, filtros con límites y métricas ausentes, ordenamiento con nulos al final, caché con TTL, servir datos viejos (*stale*) ante un *rate-limit*, y la búsqueda de un solo ticker. |
| [`StockScreenerIntegrationTest`](backend/src/test/java/com/stocklens/StockScreenerIntegrationTest.java) | 10 | **Integración** | Levanta todo el contexto de Spring Boot (`@SpringBootTest` + `MockMvc` + `@DirtiesContext(AFTER_EACH)`) y recorre el stack completo HTTP → controlador → servicio, mockeando únicamente `FinancialDataProvider`. Cubre el orden por defecto y el filtrado, búsqueda + detalle, caída total con caché fría → 503, ticker desconocido → 404 y el contrato de validación 400. También cubre el contrato HTTP de la capa web que antes vivía en un *slice* `@WebMvcTest` aparte. |

> **Nota:** existe además una utilidad de documentación, `OpenApiSpecExporter` (anotada con `@Tag("docgen")` y `@SpringBootTest`), que exporta el spec OpenAPI a `build/openapi/`. Está **excluida** de `./gradlew test` y se ejecuta solo con `./gradlew generateOpenApiSpec` (del cual depende `./gradlew exportOpenApiSpec`); por eso **no cuenta** entre los 20 tests.

---

## 3. ¿Cómo funciona un Unit Test? ¿Qué prueba y qué muestra?

### ¿Cómo funciona?
Un **test unitario** aísla por completo una única clase bajo prueba. Para lograr esto, se eliminan todas las dependencias externas (bases de datos, servidores de red, el reloj del sistema, etc.) y se reemplazan por "dobles de prueba" (fakes, stubs o mocks).
- En este proyecto, se utiliza la librería **Mockito** para crear e inyectar estos componentes falsos. Por ejemplo, en [`StockServiceTest`](backend/src/test/java/com/stocklens/service/StockServiceTest.java), simulamos el proveedor de datos financieros (`FinancialDataProvider`) para que devuelva empresas de juguete prediseñadas en el test en vez de hacer llamadas reales a Internet.

### ¿Qué prueba?
Prueba la **lógica y las reglas de negocio**:
- Si busco "micro", ¿encuentra "Microsoft"?
- Si filtro por un P/E máximo de 30, ¿excluye a las empresas con P/E de 31? ¿Incluye a las de 30 exactamente (límite inclusivo)?
- Si una empresa no tiene P/E registrado (valor nulo), ¿se muestra al final de la ordenación?

### ¿Qué muestra?
Al ejecutarse las pruebas mediante Gradle con `./gradlew test`, JUnit ejecuta los métodos de prueba y genera:
1. **Resultado en Consola:** Te muestra si las pruebas pasaron exitosamente o fallaron.
2. **Reporte HTML interactivo:** Se genera automáticamente en la carpeta `backend/build/reports/tests/test/index.html`. Al abrirlo en un navegador, muestra el nombre detallado de cada test, el tiempo que tardó, y si falló, la línea exacta del fallo junto con el mensaje explicativo.
3. **Reporte de Cobertura (JaCoCo):** Generado en `backend/build/reports/jacoco/test/html/index.html`. Muestra qué porcentaje del código fuente real fue recorrido y probado (actualmente cuenta con una cobertura de instrucciones del 63.0% y 49.0% en ramas). La cobertura bajó respecto a versiones anteriores porque la suite se redujo; el principal hueco es que el cliente Finnhub (`FinnhubStockProvider`) ya no tiene ningún test automático.

---

## 4. El Patrón AAA (Arrange, Act, Assert)

El patrón **AAA** es el estándar de oro para estructurar pruebas de manera que sean legibles y fáciles de entender. Divide el test en tres fases consecutivas claramente separadas por comentarios:

1. **Arrange (Organizar / Preparar):** Configura el escenario inicial. Se crean los datos de prueba, se definen los stubs (respuestas simuladas de los mocks) y se alista el estado del sistema.
2. **Act (Actuar):** Ejecuta la acción o método específico que se quiere evaluar. **Debe ser idealmente una sola línea de código.**
3. **Assert (Asegurar / Verificar):** Compara el resultado obtenido con el resultado esperado utilizando aserciones.

### Ejemplo Real del Código: [`StockServiceTest`](backend/src/test/java/com/stocklens/service/StockServiceTest.java)

Aquí vemos cómo se aplica este patrón en un test que verifica que al filtrar por P/E máximo, sólo se devuelven las compañías que cumplen con la condición:

```java
@Test
void maxPeKeepsOnlyCheaperCompaniesAndTreatsBoundaryAsInclusive() {
    // 1. ARRANGE (Organizar)
    // Definimos tres acciones en nuestro universo de pruebas con diferentes P/E
    Stock cheap = stock("CHEAP", 10.0, 1e9);      // P/E = 10.0
    Stock boundary = stock("LIMIT", 25.0, 1e9);   // P/E = 25.0 (exactamente en el límite)
    Stock expensive = stock("PRICEY", 25.1, 1e9); // P/E = 25.1 (por encima del límite)
    
    // Configuramos nuestro servicio con estas tres acciones simuladas
    serviceWith(cheap, boundary, expensive);
    
    // Preparamos la consulta con un P/E máximo de 25.0
    StockQuery query = query(null, 25.0, null);

    // 2. ACT (Actuar)
    // Ejecutamos la búsqueda que queremos probar
    List<Stock> results = service.search(query);

    // 3. ASSERT (Asegurar)
    // Verificamos que se devuelvan exactamente las dos que están en o por debajo de 25.0
    assertThat(tickers(results)).containsExactlyInAnyOrder("CHEAP", "LIMIT");
}
```

---

## 5. Nota sobre Pruebas de Rendimiento (Performance Tests)

El proyecto **ya no incluye tests de rendimiento**. Se eliminaron para mantener la suite acotada en 10 tests unitarios + 10 de integración.

A nivel conceptual, sigue siendo válido usar el método `assertTimeout` de JUnit 5 como **guarda contra regresiones algorítmicas**: se ejecuta la operación dentro de un presupuesto de tiempo holgado para detectar si un cambio dañó la eficiencia (por ejemplo, convertir una búsqueda lineal $O(n)$ en una cuadrática $O(n^2)$), en lugar de medir milisegundos exactos bajo cargas masivas (para eso se usarían herramientas como JMH o k6). Aun así, esta técnica no está presente actualmente en la suite del proyecto.

---

## 6. Configuración de Gradle

El archivo [`build.gradle`](backend/build.gradle) es el corazón de la configuración del backend. En él se definen los compiladores, dependencias y tareas automatizadas.

### Componentes Clave de `build.gradle`:

1. **Plugins (Líneas 1-6):**
   ```groovy
   plugins {
       id 'java'                                 // Habilita el soporte para compilar y probar código Java.
       id 'jacoco'                               // Genera los reportes de cobertura de código.
       id 'org.springframework.boot' version '4.1.0'  // Administra el ciclo de vida y empaquetado de Spring Boot.
       id 'io.spring.dependency-management' version '1.1.7' // Maneja las versiones de dependencias automáticamente.
   }
   ```
2. **Compatibilidad de Java:**
   Configura el proyecto para utilizar Java 21:
   ```groovy
   java {
       sourceCompatibility = '21'
       targetCompatibility = '21'
   }
   ```
3. **Dependencias Principales:**
   - **`spring-boot-starter-webmvc`:** Permite construir la API REST.
   - **`springdoc-openapi-starter-webmvc-ui`:** Genera la documentación interactiva en `/swagger-ui.html`.
   - **`therapi-runtime-javadoc`:** Permite que Springdoc lea los comentarios del código (Javadoc) en tiempo de ejecución para documentar los endpoints automáticamente sin llenar el código de anotaciones ruidosas.
4. **Dependencias de Prueba (`testImplementation`):**
   - **`spring-boot-starter-webmvc-test`:** Trae JUnit 5, Mockito y AssertJ en un solo paquete.
   - *(Nota: la dependencia `mockwebserver` se eliminó del `build.gradle`, ya que ningún test la usa después de reducir la suite.)*
5. **Configuración de la Tarea de Test:**
   ```groovy
   tasks.named('test') {
       useJUnitPlatform() // Indica a Gradle que ejecute las pruebas usando JUnit 5 (Jupiter).
       finalizedBy tasks.named('jacocoTestReport') // Asegura que siempre se cree el reporte de cobertura al finalizar los tests.
   }
   ```
6. **Tarea Personalizada `exportOpenApiSpec` (Líneas 82-99):**
   Es una tarea de tipo `Copy`. Cuando se corre `./gradlew exportOpenApiSpec`, inicia el contexto de la aplicación, extrae el archivo de definición de la API OpenAPI en YAML generado automáticamente por los tests y lo copia a la carpeta [`docs/openapi.yaml`](docs/openapi.yaml). Esto evita que la documentación de la API REST se desfase respecto al código real.
