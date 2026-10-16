# Verificación LFD-25 — infraestructura y modelo físico aprobado

Fecha: 2026-10-16. Rama: `feature/LFD-25-migraciones-esquemas-globales-empresariales`.
SHA base: `2d68f66985ae285e4254e51b02fd702158e84acc`.

**Registro histórico de la entrega inicial (V1, anterior a la aprobación 10044): no lista entonces para aprobación/cierre.** Se solicitó
aclaración del modelo físico antes de decidir estados y alcance de roles/perfiles.
Jira define dominios, pero no sus columnas/estados completos. La instrucción del
ticket exige detener esa parte ante decisiones importantes ambiguas. Las pruebas
de infraestructura no sustituyen tablas globales/empresariales pendientes.

## Git y fuentes

Repositorio limpio al inicio, main/origin/main en el SHA esperado, sin commits
pendientes ni rama LFD-25 previa. Rama nueva publicada antes de editar archivos.
Se inspeccionaron README, ADR 0001–0003, guía Codex, módulos, PostgreSQL local,
verificación LFD-23, POM/configuración Spring/JDBC, inicializador/SQL LFD-23 y CI.
Jira corroboró LFD-3/LFD-25 En curso, LFD-2 Listo y LFD-26/27 Backlog; se leyeron
épica y comentarios de preparación sin escribir en Jira.

## Implementación inicial (evidencia histórica V1)

- POM: perfil Maven administrativo, plugin Flyway/soporte PostgreSQL 11.14.1 y
  driver 42.7.13 solo del plugin, versiones del parent existente.
- SQL V1: contrato de esquemas y privilegios; no tablas de dominio.
- `scripts/migrations/inspect.sql` y `provision.sql`: preparación de rol/esquemas
  separada del bootstrap LFD-23, con inspección READ ONLY y aplicación confirmada.
- `scripts/manage_migrations.py`: entrada interactiva explícita, credenciales de
  proceso y comandos acotados; no arranque automático.
- `scripts/tests/test_postgres_migrations.py`: PG17 nuevo/descarte, SCRAM, puertos
  aleatorios y pruebas de historial, permisos, errores y arranque.
- CI: job adicional de migraciones, jobs backend/frontend preservados.
- [Guía](postgresql-migrations.md) y [ADR 0004 Propuesto](adr/0004-migraciones-administrativas.md).

Se crean ocho namespaces mediante provisión administrativa explícita para no
conceder database CREATE al migrador. Flyway verifica esa preparación y mantiene
`operations.flyway_schema_history`. Esta elección se somete a revisión, no se
presenta como un ADR aceptado. El migrador queda impedido de crear otros esquemas.

No hay DDL/GRANT nuevo al runtime, RLS, FK compuestas, unicidades finales, semillas,
auth, sesiones funcionales, DTE/SII ni POS. No se modifica Java/React/configuración
JDBC, ni scripts de bootstrap LFD-23. El frontend no recibe dependencias nuevas.

## Resultados reales

Entorno local: Windows PowerShell, Python 3.13, JDK 21.0.10, Maven Wrapper 3.9.14,
PostgreSQL 17.2 en clúster nuevo TEMP. Node 24.14.0 y npm 11.12.1 para regresión UI.

| Prueba | Comando | Resultado | Evidencia y límite |
| --- | --- | --- | --- |
| Compilación, smoke y Checkstyle | `backend: .\mvnw.cmd -B -ntp clean verify` | OK | 10 tests, 0 fallos/errores/omitidos; 0 Checkstyle violations; BUILD SUCCESS, 17.093 s |
| dev/test sin PostgreSQL | Mismo verify | OK | Contextos existentes y salud UP sin DataSource |
| Instalación limpia frontend | `npm.cmd ci` | OK | 257 paquetes, 0 vulnerabilidades reportadas |
| ESLint y tipos | `npm.cmd run lint` | OK | ESLint y tsc sin diagnósticos |
| Smoke frontend | `npm.cmd run test` | OK | 3 pruebas aprobadas |
| TypeScript | `npm.cmd run typecheck` | OK | Salida 0 |
| Vite | `npm.cmd run build` | OK | 18 módulos, build 129 ms |
| A: esquemas/historial desde vacío | `python scripts/tests/test_postgres_migrations.py` | OK | Solo infraestructura: ocho esquemas y V1 aplicada con checksum; no cubre tablas de dominio pendientes |
| B: segunda ejecución | Mismo harness | OK | Historial idéntico; no reaplica V1 |
| C: checksum | Mismo harness | OK | validate original pasa; copia modificada rechazada sin cambiar historial |
| C: atomicidad | Mismo harness | OK | V99 temporal falla tras DDL; objeto e historial revierten |
| D: permisos | Mismo harness | OK | App sin DDL/admin/historial; migrador sin database CREATE/TEMP ni DDL ajeno |
| D: acceso heredado | Mismo harness | OK | Provisión rechaza PUBLIC CONNECT antes de crear el rol; no repara otras bases |
| E: JDBC sin migrar | Mismo harness | OK | JAR sin Flyway; perfil postgres con app: HTTP 200/UP y sin historial creado |
| Regresión bootstrap LFD-23 | `python scripts/tests/test_postgres_bootstrap.py` | OK | Instalación/adopción/idempotencia y negativos pasaron en clústeres desechables; scripts preservados |
| Workflow | `actionlint -shellcheck= .github/workflows/ci.yml` | OK | actionlint 1.7.12, salida 0; tres jobs configurados |
| Enlaces locales | Comprobador reproducible de verificación LFD-82 | OK | 16 Markdown, 93 enlaces, 0 errores |
| Calidad del diff y sintaxis Python | `git diff --check` y `ast.parse` | OK | Sin diagnósticos; no se agregan dependencias Python |
| A: tablas de dominio | No implementadas | NO EJECUTADA | Pendiente de aclaración de modelo físico |
| PostgreSQL real | No ejecutado | NO EJECUTADA | Sin conexiones, roles, ACL ni migraciones en 127.0.0.1:5432 |
| CI posterior a la corrección Linux | Consulta GitHub Actions | OK | [38945557525](https://github.com/rialespe/LS_Framework/actions/runs/38945557525), SHA 014689d2a726479bcc3904742cef0f0c5b4fcffd: backend, frontend y migraciones en success |

Los fallos negativos del harness son resultados esperados: no se declaran fallos
del despliegue a partir de sus salidas 1. Contraseñas efímeras se comparan en memoria
contra salidas/logs y no se imprimen ni guardan como archivos de credenciales.
El clúster se detiene antes de eliminar solo el directorio temporal validado.

## Incidencias corregidas y límites

- Flyway no tiene goal `help`: consulta inicial falló sin acceder a PostgreSQL;
  `help:describe` confirmó el goal migrate y la versión 11.14.1.
- initdb Windows no interpreta `--pwfile=-` como stdin. Se sustituyó por
  `--pwprompt` con el fallback de prueba MSYS, sin crear archivos de contraseñas.
- Flyway PostgreSQL utiliza más de una conexión: el rol migrador se limita a tres,
  sin cambiar el límite del runtime.
- Primer CI [38945247656](https://github.com/rialespe/LS_Framework/actions/runs/38945247656):
  backend/frontend aprobaron; migraciones falló al arrancar PG17 en Linux. Se
  deshabilitó el socket Unix únicamente en el PGDATA temporal, para usar solo
  TCP privado sin depender del directorio de sockets del sistema. Se agrega
  diagnóstico de arranque acotado del clúster desechable, sin credenciales.
  La [ejecución corregida](https://github.com/rialespe/LS_Framework/actions/runs/38945557525)
  pasó los tres jobs y todo el harness en Linux.
- Para confirmar la causa y prevenir regresiones, el harness Linux consulta el
  `unix_socket_directories` de su PGDATA nuevo. Si el directorio no es escribible,
  reproduce el arranque original y exige el diagnóstico de lock de socket antes
  de arrancar con `unix_socket_directories=''`. No cambia permisos/directorios
  del sistema ni utiliza PGDATA existente. El informe de entrega registra el
  resultado de este diagnóstico y del CI del último commit.
- El contrato LFD-23 rechaza ACL nuevas no contempladas. Se mantiene intacto y se
  documenta no reutilizar su inicializador como reparación tras ampliar la base.
- La provisión real probablemente encuentre PUBLIC CONNECT heredado y se detendrá.
  Resolverlo exige decisión/autorización de seguridad; aquí no se toca otra base.

## Criterios pendientes de la entrega inicial (histórico)

Confirmar diccionario físico, alcance de perfiles/roles y estados de entidades
antes de crear migraciones de tablas. Después ejecutar otra vez el harness con
aserciones de tablas/columnas/PK y publicar la continuación de la misma rama.
LFD-26/27 no se implementan por anticipado. No se abre PR, fusiona, elimina la rama
ni cierra Jira. El servidor real requiere autorizaciones posteriores específicas.


## Seguimiento 2026-10-16 — modelo aprobado e implementación V2–V5

La aprobación del [comentario 10044](https://lssoluciones.atlassian.net/browse/LFD-25?focusedCommentId=10044) resuelve el bloqueo de diccionario físico descrito arriba. No cambia retrospectivamente la evidencia V1. Preflight: árbol limpio, rama requerida y HEAD/remoto baad69a5e31baf396deb07d10ccf985f7a6fb5c3; base origin/main 2d68f66985ae285e4254e51b02fd702158e84acc. No había commits locales sin publicar. El CI previo de infraestructura fue [38953152073](https://github.com/rialespe/LS_Framework/actions/runs/38953152073).

### Cambios de esta continuación

- Nuevos SQL: V2__create_identity_and_global_catalogs.sql (10 tablas), V3__create_tenants_and_entitlements.sql (7), V4__create_tenant_security_and_sessions.sql (7), V5__create_integration_audit_and_operations.sql (5).
- Nuevo [diccionario físico](database/data-dictionary.md): 29 tablas, todas las columnas/tipos/nulabilidad/defaults/PK/FK y diagrama de relaciones reales; mapa de restricciones futuras LFD-26.
- Harness ampliado: contrato literal del modelo aprobado independiente de SQL; esquema/columnas/tipos/PK/defaults, tablas vacías, FK simples seguras, checks aprobados, vencimiento obligatorio, permisos, repetición sin cambios físicos y recuperación tras rollback sin repair.
- README, arquitectura y guía de migraciones actualizados. ADR 0004 conserva estado **Propuesto**, con nota que distingue aprobación del modelo de aprobación de la política administrativa.
- V1, POM/Flyway 11.14.1, provisión/inspección, bootstrap LFD-23, Java, frontend, dependencias y workflow permanecen intactos. El job migrations ejecuta automáticamente las aserciones nuevas al usar el mismo harness.

### Resultados locales de esta continuación

Entorno: Windows, JDK 21.0.10, Maven 3.9.14, PostgreSQL 17.2 instalado usado únicamente para crear clúster temporal nuevo, Python 3.13; Node 24.14.0/npm 11.12.1.

| Prueba | Comando desde raíz salvo indicación | Resultado | Evidencia |
| --- | --- | --- | --- |
| Backend Java 21, smoke dev/test sin DB, Checkstyle | backend: `.\mvnw.cmd -B -ntp clean verify` | OK | 10 tests, 0 fallos/errores/omitidos, 0 violaciones; BUILD SUCCESS |
| Instalación frontend | frontend: `npm.cmd ci` | OK | 257 paquetes instalados; auditoría sin vulnerabilidades reportadas |
| ESLint | frontend: `npm.cmd run lint` | OK | ESLint y typecheck sin diagnósticos |
| React smoke | frontend: `npm.cmd run test` | OK | 3 pruebas aprobadas |
| TypeScript estricto | frontend: `npm.cmd run typecheck` | OK | Salida 0 |
| Vite | frontend: `npm.cmd run build` | OK | 18 módulos; build correcto, UI sin cambios |
| 29 tablas y columnas, tipos, nulabilidad, PK/defaults | `python scripts/tests/test_postgres_migrations.py` | OK | Exactamente 29 tablas de dominio + historial técnico adicional; tablas vacías |
| Flyway V1–V5 | Mismo harness | OK | Versiones en orden, successful y checksum presente; Flyway 11.14.1 |
| Repetición/validate | Mismo harness | OK | Historial y esquema físico idénticos; validate correcto |
| Checksum alterado | Mismo harness, copia temporal | OK | Cambio rechazado sin modificar historial |
| Rollback y recuperación | Mismo harness, V99 temporal fallida | OK | Tabla rollback_probe e historial revierten; scripts originales validan y migran sin repair ni borrado |
| Modelo y privilegios | Mismo harness | OK | Estados inválidos rechazados; expiraciones obligatorias; UUID/timestamps operativos; app sin privilegios nuevos; migrador limitado; PUBLIC sin permisos en esquemas/tablas; auditoría sin cascadas |
| Perfil JDBC sin migración | Mismo harness, antes de aplicar Flyway | OK | JAR sin biblioteca Flyway; HTTP 200 / UP usando app en clúster temporal; historial no creado |
| PostgreSQL real | No se conecta ni ejecuta SQL | NO EJECUTADA | No autorizado; no se modificaron roles, bases, tablas ni ACL compartidas |

Los datos sintéticos de pruebas de contrato se insertan en transacciones que terminan en ROLLBACK. No hay semillas ni secretos auténticos. Se comprueban nuevamente las 29 tablas vacías. Se conserva la corrección Linux exclusivamente en PGDATA temporal: unix_socket_directories=''.

### Alcance y asuntos pendientes

LFD-26: FK compuestas/candidatas de tenant y aplicación, pertenencia, unicidades e índices finales. LFD-27: semillas y verificación ampliada. No hay RLS ni aislamiento garantizado; no hay autenticación/autorización, sesiones/API Keys funcionales ni garantía transaccional de conservar administradores activos. Estados sin vocabulario aprobado permanecen TEXT NOT NULL, sin CHECK funcional. Los timestamps updated_at requerirán mantenimiento explícito por futuros escritores.

El contrato futuro de tenant suspendido se documenta, no se ejecuta: escrituras bloqueadas, lecturas autorizadas y soporte temporal auditado de solo lectura, revocaciones efectivas en operaciones posteriores. Las políticas administrativas ADR 0004 y OWNER/mínimos globales siguen pendientes de revisión. Provisión y migración reales requieren autorizaciones específicas posteriores; PUBLIC CONNECT heredado sigue sin corregirse.

No se abre PR, fusiona, elimina rama ni modifica Jira. La entrega queda sujeta a revisión técnica del propietario.


### Calidad y correcciones de esta continuación

- En una ampliación del test de FK, una consulta de catálogo falló al concatenar TEXT con el tipo interno PostgreSQL "char" (confdeltype/confupdtype). Se reprodujo en un clúster desechable: operator is not unique. La conversión explícita ::text corrigió la consulta; no exigió cambiar migraciones ni permisos. El primer harness sin esa aserción ya había aprobado; la versión final vuelve a ejecutar todas las aserciones.
- V1 comparada byte a byte contra baad69a: intacta. SHA-256 e6a64d26695038b795e1e926d835dab2ee5ad6a0e6136c5fdea52264d392e2f7.
- actionlint 1.7.12: OK, workflow intacto, tres jobs conservados.
- Comprobador de enlaces reproducible documentado en verification-lfd-82.md: OK, 17 Markdown, 99 enlaces locales, 0 errores.
- Sintaxis Python mediante ast.parse y git diff --check: OK. Sin dependencias nuevas ni secretos en SQL; contraseñas efímeras se comprueban contra logs en memoria.
- El CI de esta continuación se verificará para el commit publicado; no debe confundirse con el CI histórico baad69a. Su URL y resultado se incorporan tras la ejecución real.


Historial observado en la ejecución local final corregida (todas success=true):

| Versión | Checksum Flyway |
| --- | --- |
| 1 | -1378463590 |
| 2 | -895708369 |
| 3 | -849841087 |
| 4 | -1697819596 |
| 5 | 1729249324 |

La ejecución final terminó con `All isolated migration checks passed. Shared PostgreSQL was not contacted.` Incluye la aserción de destinos FK/NO ACTION corregida, ausencia de triggers funcionales y recuperación exitosa tras DDL fallido.
