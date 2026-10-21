# Lector global C3/C4

`GlobalCatalogReader` expone `applications()` y `modules(UUID applicationId)` con
resultados inmutables y SQL fijo. El lector no publica endpoints por sí mismo. Solo lee columnas públicas
de `catalog.applications` y `catalog.modules`; no otorga autorización empresarial.

Contrato paginado interno: `applications(int limit, int offset)` devuelve
`List<Application>` y `modules(UUID applicationId, int limit, int offset)` devuelve
`List<Module>`. Ambas listas y sus registros son inmutables. `limit` debe estar
entre 1 y 100 y `offset` entre 0 y 10000, inclusivos; valores inválidos generan
`IllegalArgumentException` antes de abrir conexión. `applicationId` es obligatorio
(`NullPointerException` si es nulo). SQL usa `ORDER BY code, id` y parámetros JDBC
para UUID, `LIMIT` y `OFFSET`. Una página sin coincidencias es una lista vacía.
No se devuelve total ni se conserva una instantánea entre llamadas: cambios
concurrentes pueden desplazar filas entre páginas. Los métodos originales siguen
disponibles sin límite; los consumidores futuros deben preferir las sobrecargas.

Deshabilitado por defecto. Para registrar el bean, configurar externamente:

```text
LS_CATALOG_ENABLED=true
LS_CATALOG_DB_HOST=<host>
LS_CATALOG_DB_PORT=<puerto>
LS_CATALOG_DB_NAME=<base>
LS_CATALOG_DB_PASSWORD=<secreto externo>
```

La identidad es fija: `ls_core_catalog_service_dev`. Cada consulta abre una
conexión física independiente, verifica su identidad, inicia una transacción
read-only y ejecuta `SET LOCAL ROLE ls_core_catalog_reader_dev`. Materializa los
resultados, hace commit (rollback ante error) y cierra la conexión incluso si
falla la limpieza. No usa el pool empresarial ni `LS_DB_*`. Los errores públicos
no contienen diagnósticos JDBC ni secretos.

Prueba reproducible, sin configurar credenciales reales:

```powershell
$env:JAVA_HOME = 'C:\Program Files\Java\jdk-21.0.10'
$env:LS_PG_BIN = 'C:\Program Files\PostgreSQL\17\bin'
python scripts/tests/postgres_catalog_backend_contract.py
```

El arnés crea PGDATA/puerto/contraseñas efímeros, aplica V1–V9 y C1/C2 sin
modificarlos, autentica Java realmente como C2 y elimina exclusivamente su clúster.
También se integra al arnés CI existente. No ejecutarlo contra PostgreSQL compartido.

Límites: conexiones por llamada (coste de autenticación; C2 limita a tres sesiones),
sin reintentos automáticos. La configuración remota/TLS, entrega y
rotación de secretos requieren revisión operativa antes de un despliegue real.

## API y vista de desarrollo Docker (LFD-29, En curso)

El transporte HTTP reutiliza el lector, sin modificar C1/C2 ni V1–V9. Está desactivado
por defecto: exige simultáneamente perfil `catalog-docker`, `LS_CATALOG_API_ENABLED=true`
y `LS_CATALOG_ENABLED=true`. Rechaza `server.address` distinto de `127.0.0.1` y destinos
distintos de `127.0.0.1:55432/ls_core_dev`. No activa el perfil empresarial `postgres`.

- `GET /api/global-catalog/applications?limit=100&offset=0`
- `GET /api/global-catalog/applications/{applicationId}/modules?limit=100&offset=0`

Devuelven únicamente los registros públicos del lector. UUID/paginación inválidos
producen 400; indisponibilidad produce 503 con mensaje fijo, sin detalles JDBC. No
hay endpoints de escritura, autenticación ficticia ni configuración CORS abierta.
Las otras tablas accesibles a C1 por contrato no se publican mediante HTTP.

Preparación Docker explícitamente autorizada, desde una terminal local visible:

```powershell
$env:JAVA_HOME = 'C:\Program Files\Java\jdk-21.0.10'
python scripts/docker_catalog_demo.py prepare-c2-only --report "$env:TEMP\catalog-c2-only.json"
```

Comprueba imagen, volumen, puerto localhost, identificador físico/SQL
`7698808171985612839`, historial V1–V9, C1 existente y C2 ausente. La operación original
`prepare` se retiró del helper: nunca vuelve a crear C1. Usa la herramienta Docker
integrada y adapta C2 en memoria, conservando los grants y las restricciones originales
salvo la excepción Docker aprobada a continuación. Los archivos originales no cambian.
C1 es NOLOGIN y C2 es LOGIN SCRAM independiente,
sin herencia, solo CONNECT a la base objetivo y membresía SET TRUE / INHERIT FALSE /
ADMIN FALSE. `psql \password` solicita la contraseña localmente. No repara ACL ni
adopta roles; un conflicto obliga a detenerse. La transacción C1 ya confirmada se
conserva. El preflight C2 se ejecuta primero en lectura y se repite dentro de la
transacción de creación de C2; un fallo previo a COMMIT revierte solo esa transacción.

### Excepción Docker C2 aprobada (Jira LFD-29, comentario 10130)

Solo en este clúster se admite TEMPORARY procedente de PUBLIC en `postgres` y
`ls_core_docker_dev`, sin CONNECT/CREATE, concesiones directas, grant option ni sesiones
de C1/runtime. El otorgante debe ser `ls_core_docker`. Toda otra base, rol personalizado,
membresía, privilegio CREATE o acceso CONNECT ajeno se rechaza. TEMPORARY sigue prohibido
en `ls_core_dev`. Se verifican propietarios, disponibilidad de bases, atributos y
configuración de roles, privilegios de esquema y las columnas C1 exactas. C1 no puede
tener propiedad de objetos ni membresías previas. La huella de sus atributos y grants
se compara antes/después, sin incluir contraseñas ni verificadores.

Esto es una adaptación exclusiva del Docker local aprobado, **no una relajación del
contrato C2 original**. No revoca TEMPORARY ni repara ACL. Los scripts originales C1/C2,
V1–V9 y sus pruebas generales permanecen intactos.

Pruebas puras positivas/negativas, sin conexiones ni cambios de permisos:

```powershell
python scripts/tests/test_docker_catalog_c2.py
```

La operación requiere una terminal **integrada de VS Code**, PowerShell, abierta por
el propietario en Terminal → Nuevo terminal. Ejecutar el comando directamente allí;
no utilizar Start-Process, ventanas adicionales ni procesos en segundo plano esperando
contraseñas. C2 solicita la contraseña administrativa oculta y luego `psql \password`
pide dos veces la nueva contraseña de servicio.

Para abrir la demo, compilar el backend y activar Vite solo para esta ejecución:

```powershell
.\backend\mvnw.cmd -B -ntp -f backend/pom.xml -DskipTests package
Set-Location frontend
$env:VITE_CATALOG_ENABLED = 'true'
npm.cmd run dev -- --host 127.0.0.1
```

En otra terminal, desde la raíz:

```powershell
python scripts/docker_catalog_demo.py serve --report "$env:TEMP\catalog-e2e.json"
```

Este comando pide **la contraseña de C2**, inicia la API en `127.0.0.1:8080`, comprueba
lectura HTTP de aplicaciones y módulos a través del proxy Vite y mantiene el proceso
hasta Ctrl+C. No guarda secretos ni diagnósticos del driver. La conexión administrativa
solo se usa para preparar roles, nunca para atender consultas de la aplicación.
Antes de iniciar HTTP autentica realmente C2, comprueba denegación sin SET ROLE,
ejecuta SET LOCAL ROLE C1 y verifica lectura de aplicaciones/módulos y denegación de
`catalog.applications.created_at` y `tenant.tenants` (SQLSTATE 42501), sin escrituras.

### Evidencia E2E reproducible, sin mocks

`serve` prueba secuencialmente el mismo jar en loopback 8080: configuración por defecto
(catálogo 404), catálogo habilitado con una entrada de autenticación deliberadamente
inválida solo en el entorno del proceso (503 controlado) y finalmente C2 correcto.
No cambia la contraseña almacenada, grants ni disponibilidad del contenedor. Las
pruebas negativas esperadas no se confunden con un fallo del procedimiento. Se comprueban
HTTP 200, comparación completa contra las lecturas SQL C2, páginas/offset, filtro de
aplicación, respuestas vacías, 400 sanitizado y ausencia de CORS abierto.

Después ejecuta `ConnectedCatalog.real.test.tsx` con opt-in `LS_CATALOG_E2E=true` y
`VITE_CATALOG_ENABLED=true`: renderiza React en jsdom y usa fetch de Node contra
Vite/Spring Boot reales. El único adaptador resuelve URLs relativas al loopback 5173;
no hay servidor simulado, respuestas inventadas ni fallback de fixtures. Contrasta
nombres, códigos y estados visibles en el DOM con las respuestas HTTP que el helper
ya comparó contra SQL. La prueba emite `REAL_REACT_DOM_HTTP_MATCH` con los registros
públicos; los diagnósticos y las credenciales no se guardan. En CI y pruebas normales
este caso se omite explícitamente y las veinte pruebas React unitarias siguen separadas.
La validación DOM no sustituye una revisión gráfica manual en navegador.

El informe local `--report` permite revisar resultados SQL/HTTP/DOM y la identidad
del contenedor sin secretos. No incluir los informes locales en Git. Si aparece una
desviación inesperada, se detiene el procedimiento y no se repite provisión.

Abrir `http://127.0.0.1:5173/` → **Aplicaciones** → **Abrir catálogo global conectado**.
La sección **Catálogo global (BD) · Solo lectura** muestra una advertencia específica
de conexión real a Docker y sustituye el aviso «sin conexión» solo dentro de esa vista.
Los directorios ficticios permanecen separados. Hay carga, vacío, error y reintento
manual, sin reemplazo por fixtures. No se envían credenciales de navegador ni se usan
cookies o almacenamiento. Peticiones canceladas al salir no actualizan la vista.
La vista existe únicamente en desarrollo con `VITE_CATALOG_ENABLED=true`; los builds
de producción no la habilitan. Vite solo redirige `/api/global-catalog` al loopback
8080 y no entrega secretos al cliente. No usar `--host 0.0.0.0` ni publicar el proxy.

Aplicaciones se paginan de 100 en 100 hasta offset 10000. Módulos muestra los primeros
100 de la aplicación seleccionada e informa el límite si se alcanza. No se promete
un total ni una instantánea compartida. Esto no resuelve autorización multi-tenant;
LFD-29 continúa En curso. CI usa su propio PG17 desechable; no sustituye la evidencia
local de Docker.
