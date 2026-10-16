# Evidencia LFD-23

## Seguimiento de aprobación — LFD-82, 2026-10-16

Los apartados originales de abajo preservan los resultados y bloqueos observados
en cada etapa por Codex. La validación posterior del propietario quedó registrada
en Jira el 2026-10-16 a las 00:44:57 (America/Santiago),
[comentario de aprobación 10023](https://lssoluciones.atlassian.net/browse/LFD-23?focusedCommentId=10023).
LFD-82 consultó ese registro y el estado Listo de LFD-23; no volvió a ejecutar
operaciones sobre el servidor real ni se atribuye las pruebas del propietario.

| Validación posterior del propietario | Resultado registrado |
| --- | --- |
| PostgreSQL local 17.2 | Disponible en 127.0.0.1:5432 |
| Base y rol | ls_core_dev UTF8, owner postgres; ls_core_app_dev LOGIN, límite 3, sin privilegios administrativos |
| Conectividad de aplicación | Login y SELECT 1 exitosos; CONNECT true y CREATE/TEMP false en la base objetivo |
| Idempotencia real | Segunda ejecución NO CHANGES; contraseña preservada y definiciones/ACL de otras bases sin cambios |
| JDBC / Actuator | Spring Boot 4.0.8 y Java 21, perfil postgres; HTTP 200 y JSON status UP |
| Límite de permisos | CONNECT efectivo en otras cinco bases, posiblemente por PUBLIC; sin remediaciones ajenas |

La exclusividad de conexión no quedó acreditada: NOINHERIT y el GRANT sobre la base
objetivo no eliminan acceso efectivo por PUBLIC. No se afirma RLS, aislamiento
multi-tenant ni aptitud productiva. Véase el [ADR 0002](adr/0002-postgresql-y-conectividad.md).

Los textos «pendiente», «no listo» y «NO EJECUTADA» de abajo pertenecen a sus etapas
históricas y no deben interpretarse como el estado de cierre actual. Se agregan
fuentes posteriores sin cambiar fechas ni resultados originales.

## Corrección de recuperación manual — 2026-10-16

El propietario informó y confirmó por SQL autenticado un rol `ls_core_app_dev`
NOLOGIN, sin privilegios administrativos, comentario, membresías ni propiedad de
bases, y ausencia de `ls_core_dev`. El CREATE ROLE manual deja `rolconnlimit=-1`.
La protección anterior rechazaba tanto la ausencia de marcador como ese límite.

Se modifican Initialize-LocalPostgres.ps1, initialize-local.sql e inspect-bootstrap.sql
y la guía. Se agrega scripts/tests/test_postgres_bootstrap.py. No se modifica Java,
JDBC, configuración del pool, verify-application.sql ni frontend. Cambios previos
del árbol de trabajo preservados; no commit/push/Jira.

### Implementación

- Inspección sin Apply reconoce al candidato compatible sin adoptarlo ni marcarlo.
- Apply sin AdoptExistingRole falla antes de escribir.
- Apply+AdoptExistingRole exige `ADOPT ls_core_app_dev AND APPLY`, con confirmación
  expresa de creación para LS Core y ausencia de uso en otras aplicaciones.
- Revalida catálogos, OID y estado después de confirmar, antes de ajustar límite
  a 3 y marcador exacto `LS Core LFD-23 bootstrap v1`, manteniendo NOLOGIN.
- Password por \password solo si falta; base UTF8 propiedad postgres y CONNECT
  antes de LOGIN. Reintentos inspeccionan estado real; no resetean contraseñas.
- Se endurece la revisión de todas las dependencias pg_shdepend, además de
  membresías, propiedad, parámetros, sesiones, transacciones preparadas y etiquetas.
  No es posible detectar configuraciones de aplicaciones desconectadas: se exige
  la declaración del propietario en la confirmación específica.

### Pruebas ejecutadas

| Verificación | Resultado | Evidencia y límite |
| --- | --- | --- |
| Sintaxis PowerShell | OK | Parser sin errores |
| SQL / metacomandos / PLpgSQL | OK | Ejecutados con PostgreSQL 17.2 en clústeres temporales aislados |
| Instalación desde cero | OK | Bootstrap y permisos de aplicación verificados en fixture aislado |
| Inspección parcial manual | OK | Retorna plan de adopción; snapshots de roles, bases y comentarios idénticos |
| Apply sin flag de adopción | OK | Rechazo y snapshots idénticos |
| Confirmación genérica APPLY | OK | No autoriza adopción; snapshots idénticos |
| Adopción explícita | OK | Conserva OID, marca, limita conexiones y completa instalación aislada |
| Rol incompatible | OK | CREATEDB, comentario ajeno, membresía, ACL sobre otra base y propiedad de esquema rechazados |
| Negativos sin estado residual | OK | Cada fixture incompatible se revierte al cerrar la transacción fallida |
| Segunda ejecución | OK AISLADO | NO CHANGES; igualdad completa de pg_authid, pg_database y pg_shdescription, incluido password |
| Secretos de prueba | OK | Aleatorios en memoria/stdin; ausentes de salida, archivos SQL y server.log |
| Backend Java 21 | OK | clean verify BUILD SUCCESS, release 21, JDK 21.0.10 |
| Frontend | OK | tsc --noEmit y Vite build correctos; sin modificaciones |
| Modo base sin perfil PostgreSQL | OK | Sin perfil explícito, dev por defecto; health UP; env/configprops/beans 404; cierre graceful |
| Adopción en instalación compartida | NO EJECUTADA | El usuario revisará primero el plan local |
| Idempotencia/JDBC positivo compartidos | NO EJECUTADA | Pendientes de revisión, aplicación y credenciales locales |

Comando reproducible: `python scripts/tests/test_postgres_bootstrap.py`.
Usa exclusivamente librería estándar Python y binarios ya instalados. Dos clústeres
descartables separados, puertos efímeros distintos de 5432, datos bajo TEMP, sin
servicios Windows. Copias SQL cambian solo la comprobación del puerto. Autenticación
trust en fixtures vacíos: la comprobación de identidad/permisos no certifica password
authentication en la instalación real. \password sí se ejecuta con entradas aleatorias
por pipe de prueba usando el fallback Windows/MSYS; ninguna contraseña literal SQL.
Las comparaciones de pg_authid se mantienen en memoria y nunca se imprimen/guardan.
Se detienen los clústeres antes de eliminar solo sus directorios temporales validados.

La primera corrida del harness encontró timeouts de handles/consola Windows. Se
corrigieron únicamente en el harness (salida de pg_ctl sin pipes y fallback stdin
de psql); el clúster temporal inicial se detuvo explícitamente. La corrida final
terminó con `All isolated bootstrap scenarios passed. Shared server was not used.`

### Revisión y aplicación posterior

Primero, desde la raíz, sin modificar PostgreSQL:

```powershell
.\scripts\Initialize-LocalPostgres.ps1
```

Después de que el propietario revise el plan:

```powershell
.\scripts\Initialize-LocalPostgres.ps1 -Apply -AdoptExistingRole
```

Exige la frase específica de adopción y contraseñas introducidas solo localmente.
Estado del servidor compartido antes/después de esta corrección: no se ejecutó SQL
de modificación contra él; los hechos de rol/base proceden de la inspección autenticada
aportada por el usuario, no de una nueva autenticación de Codex. No se ha aplicado
el ajuste sobre el rol real. Revisión del plan, adopción real, psql, segunda ejecución
NO CHANGES y JDBC UP siguen pendientes en la instalación real para aprobar LFD-23;
el modo base sin conexión se verificó nuevamente en esta corrección.

## Evidencia anterior a la corrección de adopción

## Actualización: inicializador automatizado

En esta continuación se conservaron todos los cambios anteriores sin commit.
No se modificaron Java, pom, configuración JDBC ni frontend. Se añadieron:

- `scripts/Initialize-LocalPostgres.ps1`: cliente existente, disponibilidad,
  sesión administrativa con psql -W, plan/Apply, verificación como aplicación y
  arranque opcional usando Start-PostgresBackend existente.
- `scripts/postgres/initialize-local.sql`: plan y ejecución condicional; contraseña
  por \password; rol NOLOGIN hasta completar preparación; sin borrados.
- `scripts/postgres/inspect-bootstrap.sql`: contrato de atributos, permisos,
  propiedad, membresías y compatibilidad; compartido por preflight/postflight.
- `scripts/postgres/verify-application.sql`: READ ONLY, identidad, consulta técnica
  y privilegios efectivos, incluido CREATE en esquemas.
- README y guía PostgreSQL actualizados; esta evidencia distingue las etapas.

### Estado local actual

Servicio `postgresql-x64-17` **Running / Automatic**. Cliente **17.2** y
`pg_isready 127.0.0.1:5432`: **accepting connections**. No se cambió el servicio.
El usuario informó autenticación administrativa y ausencia previa de los dos recursos;
no se asume que esa inspección anterior siga vigente. Cada ejecución vuelve a inspeccionar.

Codex intentó la inspección simple con `psql -X -w ... -f inspect-local.sql`:
**no password supplied**. No hay una credencial disponible en esta sesión.
Se solicitó ejecutar el modo de inspección en el terminal local con contraseña oculta.
No se pidió enviar contraseñas por chat ni se generaron archivos de credenciales.

### Verificaciones de esta continuación

| Prueba solicitada | Resultado | Evidencia / límite |
| --- | --- | --- |
| Preflight de disponibilidad | OK | Servicio activo, pg_isready accepting connections; psql 17.2 |
| Autenticación administrativa en Codex | NO EJECUTADA | Intento sin prompt rechazado: no password supplied; requiere entrada local |
| A: creación con inicializador | NO EJECUTADA | Falta ejecución autenticada y confirmación APPLY en terminal local |
| B: segunda ejecución real | NO EJECUTADA | Aún no hay dos ejecuciones autenticadas; no se presenta revisión de código como prueba de idempotencia |
| C: permisos efectivos reales | NO EJECUTADA | SQL preparado; catálogos no accesibles sin autenticación |
| D: SELECT como aplicación | NO EJECUTADA | Requiere rol/base preparados y contraseña local |
| E: JDBC positivo / UP | NO EJECUTADA | Pendiente mismo acceso; -StartBackend delega al script existente, health se comprueba en segunda terminal |
| F: backend compila con Java 21 | OK | clean verify BUILD SUCCESS, release 21, JDK 21.0.10 |
| F: modo base | OK | Dev inicia en 2.949 s; health UP sin LS_DB_* |
| F: Actuator restringido | OK | env, configprops y beans HTTP 404 |
| F/G: puerto incorrecto | OK | Perfil postgres, puerto 1 comprobado libre; HTTP 503, groups y status DOWN sin detalles |
| F/G: variables ausentes | OK | Salida 1; mensaje de LS_DB_HOST sin su valor |
| F: cierre | OK | Ambos procesos: Graceful shutdown complete; sin detener PG |
| F: frontend | OK | tsc --noEmit correcto y Vite build 183 ms; sin cambios en frontend |
| F: Git | OK | diff --check sin errores; staging vacío; sin eliminaciones; cambios anteriores preservados |
| Sintaxis PowerShell | OK | Parser: 0 errores del inicializador y script de arranque |
| Sintaxis SQL / PLpgSQL | OK | pglast 7.12, parser PostgreSQL 17.7, cuatro SQL parseados |
| Modo inválido | OK | -StartBackend sin -Apply: salida 1 antes de acceder a PG |
| Cliente inexistente | OK | -PsqlPath inexistente: salida 1 sin instalar nada |
| G: contraseña incorrecta | NO EJECUTADA | No existe aún una credencial/rol comprobado para una prueba concluyente |
| G: error de permisos / base incompatible | NO EJECUTADA | Sin alterar recursos existentes ni crear bases de ensayo; reglas revisadas en código |

La sintaxis SQL se comprobó fuera del repositorio con un parser, sustituyendo variables
psql por literales de prueba y separando metacomandos. No comprueba resolución de
catálogos, interacción de \password/\prompt, privilegios reales ni ejecución psql.
El parser se instaló bajo TEMP, no como dependencia del proyecto ni dentro de PG.
No se creó ninguna base adicional para probar.

### Evidencia que debe producir la segunda ejecución

Después de una primera ejecución exitosa, repetir `Initialize-LocalPostgres.ps1 -Apply`.
Se esperan todos los indicadores del plan en falso, mensaje `NO CHANGES`, ninguna
confirmación APPLY ni \password, y la verificación psql de identidad/permisos correcta.
Se debe mantener `Other database definitions/ACLs unchanged: true`.
**Ese resultado esperado todavía no ha sido observado.**

### Comandos adicionales ejecutados

```powershell
Get-Service postgresql-x64-17 | Select-Object Name,Status,StartType
& 'C:\Program Files\PostgreSQL\17\bin\pg_isready.exe' -h 127.0.0.1 -p 5432 -t 2
& 'C:\Program Files\PostgreSQL\17\bin\psql.exe' -X -w -h 127.0.0.1 -p 5432 -U postgres -d postgres -v ON_ERROR_STOP=1 -f scripts/postgres/inspect-local.sql
# Desde backend, con JAVA_HOME / PATH seleccionados para Java 21:
.\mvnw.cmd -B -ntp clean verify
java -jar target/ls-core-0.0.1-SNAPSHOT.jar --spring.profiles.active=dev
# Desde frontend:
npm.cmd run build
# Desde raíz, fallos de preflight sin acceso a catálogos:
powershell.exe -NoProfile -File scripts/Initialize-LocalPostgres.ps1 -StartBackend
powershell.exe -NoProfile -File scripts/Initialize-LocalPostgres.ps1 -PsqlPath 'C:\not-installed-ls-core\psql.exe'
git diff --check
git diff --cached --name-only
git diff -- frontend
```

Se repitieron las comprobaciones HTTP, salida por variables ausentes y cierre de
las sesiones. La prueba de puerto erróneo usó un secreto aleatorio efímero sin mostrarlo.
No se ejecutó Apply ni se enviaron CREATE/ALTER/GRANT/REVOKE al servidor desde Codex.

Estado actual: implementación disponible para inspección y prueba local guiada;
**LFD-23 no está listo para cerrar hasta observar A/B/C/D/E**. No falta autorización
general para crear los dos recursos: falta la autenticación segura y la confirmación
del plan concreto en la ejecución. No se requiere ni se solicita permiso para alterar
otros recursos. LFD-25 y LFD-29 siguen fuera de alcance.

## Evidencia histórica: primera etapa JDBC

Verificación local: 2026-10-15, Windows PowerShell. Repositorio confirmado en
`C:\Users\riale\OneDrive\Documentos\Github\LS_Framework`, rama
`LFD-23-—-PostgreSQL-de-desarrollo`, commit inicial `0ff3fa0`, árbol inicialmente limpio.
No se encontraron AGENTS.md aplicables. No se tocó código frontend.

## PostgreSQL detectado y bloqueo

- Binarios de servidor y psql 17.2 en `C:\Program Files\PostgreSQL\17\bin`.
- Servicio `postgresql-x64-17`: Stopped, StartType Disabled.
- Directorio de datos obtenido del servicio; `postgres -D ... -C port`: 5432.
- `pg_isready -h 127.0.0.1 -p 5432 -t 2`: no response.
- No hay listener PostgreSQL detectado; el puerto efectivo por SQL queda pendiente.
- No se modificó el servicio, PATH global, postgresql.conf ni pg_hba.conf.
- No se intentaron credenciales, inspección privada, creación de objetos ni cambios SQL.
- Pendiente decisión del usuario sobre el servicio y acceso local seguro; no se conoce
  todavía si los nombres propuestos existen o pertenecen a otra aplicación.

## Pruebas

| Prueba | Resultado | Evidencia | Observaciones |
| --- | --- | --- | --- |
| A: construcción backend | OK | Wrapper clean verify: BUILD SUCCESS, release 21 | Java 21.0.10, Maven 3.9.14 |
| A: modo base sin PostgreSQL | OK | Dev inicia en 2.763 s; health UP | No se definieron LS_DB_* y el servidor PG estaba detenido |
| A: superficie Actuator | OK | env, configprops, beans y login: HTTP 404 | Solo health expuesto |
| B: conexión real al servidor local | NO EJECUTADA | Servicio detenido y deshabilitado | Requiere acceso y recursos autorizados |
| B: credenciales y SELECT 1 correctos | NO EJECUTADA | No hay credenciales ni base/rol comprobados | No se certifica conexión real exitosa |
| B: credenciales reales ausentes de logs/respuestas | NO EJECUTADA | No se utilizaron credenciales reales | Código suprime excepciones y diagnósticos JDBC; falta prueba positiva |
| C: fallo de conexión | OK | Perfil postgres, 127.0.0.1 puerto 1 sin listener; HTTP 503 y status DOWN | Contraseña aleatoria efímera no impresa; no se contactó el servidor compartido |
| C: respuesta de fallo sin detalles | OK | Solo groups liveness/readiness y status DOWN | Sin URL, credenciales, excepción ni componentes |
| C: logs del fallo | OK | Logs de arranque, servlet y cierre sin excepciones/credenciales | Diagnósticos Hikari/pgJDBC desactivados |
| C: tiempo controlado | OK | Primera respuesta de fallo en 5312 ms | Espera del pool 5 s; no es un SLA general |
| C: configuración ausente | OK | Perfil postgres sin variables: salida 1, mensaje Missing required PostgreSQL setting: LS_DB_HOST | Se verificó solo el nombre, sin volcar logs |
| C: cierre de procesos | OK | Graceful shutdown complete en ambos modos; puerto 8080 libre después | Sin detener PostgreSQL compartido |
| D: Git y exclusiones | OK | diff --check sin errores; staging vacío; frontend sin diff | Se añadieron exclusiones de dumps y pgpass |
| E: frontend | OK | npm run build: tsc --noEmit y Vite correctos | 18 módulos, build 196 ms, sin cambios fuente/lock |
| Script PowerShell | OK | Parser PowerShell: 0 errores | Solo comprobación sintáctica; flujo con contraseña real pendiente |
| POSIX y otros perfiles | NO EJECUTADA | Equivalentes documentados | Sin entorno POSIX ni matriz de entornos |

No se crearon tablas, esquemas empresariales, roles, bases, migraciones ni políticas.
No se hicieron commits, push, despliegues ni cambios de Jira. La suite automatizada
todavía no existe: clean verify no representa cobertura funcional; LFD-24 queda fuera.

## Dependencias resueltas

`dependency:tree` confirmó HikariCP **7.0.2** y pgJDBC **42.7.13**, gestionados por
Spring Boot 4.0.8. No se incorporó el starter JDBC: no se activa autoconfiguración
DataSource en el modo base. Se usa un bean de infraestructura explícito bajo perfil.

## Comandos de verificación

```powershell
Get-CimInstance Win32_Service | Where-Object { $_.Name -match 'postgres' }
Get-Service postgresql-x64-17 | Select-Object Name,Status,StartType
& 'C:\Program Files\PostgreSQL\17\bin\postgres.exe' --version
& 'C:\Program Files\PostgreSQL\17\bin\psql.exe' --version
& 'C:\Program Files\PostgreSQL\17\bin\postgres.exe' -D 'C:\Program Files\PostgreSQL\17\data' -C port
& 'C:\Program Files\PostgreSQL\17\bin\pg_isready.exe' -h 127.0.0.1 -p 5432 -t 2
```

Con JAVA_HOME y PATH seleccionados para JDK 21, desde backend:

```powershell
.\mvnw.cmd -B -ntp clean verify
.\mvnw.cmd -B -ntp dependency:tree '-Dincludes=com.zaxxer:HikariCP,org.postgresql:postgresql'
java -jar target/ls-core-0.0.1-SNAPSHOT.jar
# Tras corregir las comillas del nivel OFF del perfil, se volvió a empaquetar:
.\mvnw.cmd -B -ntp package
```

Para C se seleccionó el perfil postgres y un puerto local comprobado sin listener,
usando LS_DB_* del proceso y una contraseña aleatoria generada en memoria, sin literal
ni almacenamiento. No se registran valores de contraseña en esta evidencia.
La prueba de configuración ausente ejecutó el JAR con `--spring.profiles.active=postgres`
sin LS_DB_* y comprobó código de salida y nombre del parámetro faltante.

```powershell
Invoke-RestMethod http://127.0.0.1:8080/actuator/health
# En el fallo se utilizó HttpClient para inspeccionar status 503 y cuerpo JSON.
git diff --check
git diff --cached --name-only
git diff -- frontend
git check-ignore backend/target/test frontend/.env.local backend/src/main/resources/application-local.yml test.dump test.backup pgpass.conf .pgpass
# Desde frontend:
npm.cmd run build
```

## Pendientes para cerrar

1. Resolver el arranque del servicio con el usuario; no habilitarlo automáticamente.
2. Ejecutar scripts/postgres/inspect-local.sql con psql -W y una cuenta existente,
   introduciendo la contraseña únicamente en el terminal local.
3. Según el resultado, presentar y autorizar creación de base/rol y permisos concretos.
4. Revisar el acceso heredado de PUBLIC a otras bases: no se garantiza exclusividad
   de conexión por el mero hecho de conceder CONNECT solo en ls_core_dev.
5. Ejecutar la prueba positiva JDBC/SELECT 1 y revisar respuestas/logs con el rol real.

Estado recomendado: pendiente de acceso y autorización; **no listo para cierre**.
