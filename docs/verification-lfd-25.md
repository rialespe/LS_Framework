# Verificación LFD-25 — infraestructura y modelo pendiente

Fecha: 2026-10-16. Rama: `feature/LFD-25-migraciones-esquemas-globales-empresariales`.
SHA base: `2d68f66985ae285e4254e51b02fd702158e84acc`.

**Entrega parcial: no lista para aprobación/cierre de LFD-25.** Se solicitó
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

## Implementación actual

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

## Criterios pendientes

Confirmar diccionario físico, alcance de perfiles/roles y estados de entidades
antes de crear migraciones de tablas. Después ejecutar otra vez el harness con
aserciones de tablas/columnas/PK y publicar la continuación de la misma rama.
LFD-26/27 no se implementan por anticipado. No se abre PR, fusiona, elimina la rama
ni cierra Jira. El servidor real requiere autorizaciones posteriores específicas.
