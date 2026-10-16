# ADR 0002 — PostgreSQL y conectividad de desarrollo

- Fecha de registro: 2026-10-16.
- Estado: Aceptado; registro retrospectivo de LFD-23.
- Trazabilidad: [LFD-23](https://lssoluciones.atlassian.net/browse/LFD-23),
  [LFD-82](https://lssoluciones.atlassian.net/browse/LFD-82).
- Alcance: PostgreSQL local y comprobación JDBC; sin migraciones ni modelo tenant.

## Contexto

El equipo dispone de PostgreSQL 17.2 en Windows. Debe preparar exclusivamente los
recursos de desarrollo LS Core, preservar instalaciones parciales compatibles y
evitar que compilar, probar o abrir la pantalla requiera una base externa.

## Decisión

Utilizar `ls_core_dev` UTF8, con propietario administrativo `postgres`, y el rol
runtime `ls_core_app_dev`: LOGIN, NOINHERIT, sin SUPERUSER, CREATEDB, CREATEROLE,
REPLICATION ni BYPASSRLS, con límite de tres conexiones. En la base objetivo se
revocan privilegios de PUBLIC y se concede CONNECT a la aplicación, sin CREATE,
TEMPORARY, propiedad ni permisos DDL. El administrador se usa para el bootstrap
manual, nunca como identidad runtime. No se crean tablas de negocio.

El [inicializador](../../scripts/Initialize-LocalPostgres.ps1) inspecciona primero,
presenta el plan y exige confirmación antes de escribir. Solo crea lo faltante o
completa estados compatibles; una instalación válida devuelve `NO CHANGES` sin
restablecer contraseñas. La recuperación de un rol manual parcial exige
`-Apply -AdoptExistingRole` y la confirmación específica documentada en la
[guía PostgreSQL](../postgresql-local.md). Revalida estado y OID, conserva la
identidad y no elimina ni recrea roles. Un estado incompatible detiene el proceso.
El marcador de bootstrap no sustituye las comprobaciones de catálogos y permisos.

Mantener JDBC opcional mediante el perfil explícito `postgres`, con pgJDBC y
HikariCP gestionados por Spring Boot. El
[bean JDBC](../../backend/src/main/java/com/lssoluciones/lscore/operations/infrastructure/jdbc/PostgresConfiguration.java)
usa conexiones de solo lectura y consulta técnica `SELECT 1` para salud. El pool
limita conexiones y esperas, y cierra con Spring. No se añade el starter JDBC ni
autoconfiguración que obligue a un DataSource en el modo base `dev`.

Credenciales exclusivamente externas: `LS_DB_HOST`, `LS_DB_PORT`, `LS_DB_NAME`,
`LS_DB_USERNAME` y `LS_DB_PASSWORD` en el proceso. Las contraseñas se introducen
localmente sin eco, no en prompts, argumentos, SQL literal, archivos o logs. El
[script de arranque](../../scripts/Start-PostgresBackend.ps1) restaura el entorno
al terminar. Actuator expone solo salud sin detalles ni componentes.

## Justificación y alternativas

Separar bootstrap administrativo de runtime mantiene acotados los privilegios
necesarios para comprobar conexión. Un perfil optativo conserva el desarrollo y
CI sin PostgreSQL. Reutilizar la instalación 17.2 evita intervenir el servidor
compartido más allá de los dos recursos autorizados.

| Alternativa no adoptada | Consecuencia evitada |
| --- | --- |
| PostgreSQL obligatorio para cada arranque o prueba | Dependencia de credenciales y disponibilidad externa |
| Runtime con el rol postgres o propietario de la base | Privilegios administrativos innecesarios |
| Recrear un rol parcial o restablecer siempre su contraseña | Pérdida de identidad, configuración o trabajo existente |
| Reparar automáticamente estados ajenos/incompatibles | Cambios fuera del alcance autorizado |
| Introducir Flyway o RLS en el bootstrap | Confundir preparación local con migraciones y aislamiento aún pendientes |

Estas alternativas explican la solución adoptada; no representan pruebas de
implementaciones alternativas ni decisiones nuevas para futuras historias.

## Evidencia, límites y consecuencias

La [aprobación final LFD-23, comentario 10023](https://lssoluciones.atlassian.net/browse/LFD-23?focusedCommentId=10023)
del 2026-10-16 registra evidencia aportada por el propietario: conexión de
aplicación y SELECT 1, segunda ejecución `NO CHANGES`, y Spring Boot perfil
`postgres` con HTTP 200 y JSON `status: UP`. No fue una nueva ejecución de Codex
durante LFD-82. El [seguimiento de verificación](../verification-lfd-23.md)
conserva la distinción respecto de las pruebas originales aisladas.

Esa validación observó CONNECT efectivo en otras cinco bases por permisos
heredados, posiblemente de PUBLIC. Conceder CONNECT solo sobre `ls_core_dev` y
usar NOINHERIT **no garantiza exclusividad de conexión**. No se modificaron
permisos ajenos ni `pg_hba.conf`; resolver aislamiento requiere una decisión y
validación posteriores. Los privilegios mínimos descritos se refieren al contrato
de la base objetivo y atributos del rol, no a un aislamiento absoluto del clúster.

La idempotencia no vuelve atómica toda la preparación: CREATE DATABASE y otras
etapas tienen límites transaccionales distintos; una interrupción exige nueva
inspección, no borrados de recuperación. El pool read-only tampoco sustituye
permisos PostgreSQL ni RLS. Flyway, rol migrador definitivo, esquema multi-tenant
y RLS pertenecen a historias posteriores. No se certifica producción ni TLS remoto.

Ver [ADR 0001](0001-arquitectura-y-stack.md) y
[ADR 0003](0003-calidad-ci-y-flujo-git.md).
