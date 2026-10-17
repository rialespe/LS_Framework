# Verificación LFD-27 — semillas globales aprobadas

Ejecución local iniciada 2026-10-16, America/Santiago. Fuente: [LFD-27, comentario 10060](https://lssoluciones.atlassian.net/browse/LFD-27?focusedCommentId=10060), consultado antes de implementar. Padre LFD-3 y subtarea LFD-27 permanecen En curso; no se escribe ni transiciona Jira.

## Git y alcance

Repositorio rialespe/LS_Framework. Rama existente `feature/LFD-27-semillas-catalogos-validacion-migraciones`, local y remota inicialmente en `2f217725e5daf9c0603e92e5240c2453a0915d7a`, igual a origin/main después de fetch. Ese commit es el merge del PR #5 de LFD-26. Árbol inicial limpio, sin archivos sin seguimiento ni commits divergentes. Se trabajó directamente sobre esa rama existente. Sin reset, clean, stash, force-push, PR, merge, borrado de ramas ni modificaciones a main.

Se autorizaron exclusivamente semillas LS Core, validación PostgreSQL 17 desechable, documentación, commits y publicación de esta rama. ADR 0004 conserva Propuesto. No se modifican modelo, V1–V7, workflow, provisión, bootstrap, roles/ACL reales, Java, dependencias ni frontend.

## Archivos y contenido

Creados:

- [V8__seed_approved_global_catalogs.sql](../backend/src/main/resources/db/migration/V8__seed_approved_global_catalogs.sql).
- [Contrato independiente de semillas](../scripts/tests/postgres_seed_contract.py).
- [Manifiesto exhaustivo](database/seed-catalog.md).
- Este informe.

Modificados: [harness](../scripts/tests/test_postgres_migrations.py), [diccionario](database/data-dictionary.md), [guía de migraciones](postgresql-migrations.md), [arquitectura](architecture/modules.md) y [README](../README.md).

El manifiesto registra los 28 registros con códigos, UUID, nombres, descripciones, estados, versiones, scopes y todas las columnas de las once relaciones. Totales: applications 1, modules 3, permissions 9, role_templates 2, profile_templates 2, role_template_permissions 9, profile_template_roles 2. No se insertan otras filas de dominio.

branch_reader v1 contiene exactamente branches.read y assignments.read. access_editor v1 contiene memberships.read, roles.read, roles.manage, profiles.read, profiles.manage, assignments.read y assignments.manage. lector_sucursal v1 incluye solo branch_reader v1; editor_accesos v1 incluye solo access_editor v1. consolidated.read permanece sin asignación.

Los 17 UUID se validaron antes de DML con Python stdlib: formato canónico válido, distintos, UUIDv5 exactos para namespace y claves del comentario 10060. Los valores literales y asociaciones se comparan contra el contrato explícito, sin leer V8. La migración usa INSERT ordinario con FK compuestas y unicidad normalizada existentes, sin DDL, grants, upserts ni recuperación silenciosa.

## Pruebas locales

Entorno: Windows PowerShell, Java 21.0.10 seleccionado mediante JAVA_HOME del proceso, Maven Wrapper, Python y binarios PostgreSQL 17 existentes. El JAVA_HOME global no se cambia. Ninguna variable ni URL de conexión a servidor real se usa: el harness elimina del entorno heredado PG*, FLYWAY*, LS_DB_*, LS_MIGRATION_* y SPRING_*.

| Verificación | Comando | Resultado local |
| --- | --- | --- |
| Backend, desde backend | `.\mvnw.cmd -B -ntp clean verify` | OK: 10 pruebas, 0 errores/fallos/omitidos, 0 violaciones Checkstyle, BUILD SUCCESS |
| Instalación frontend | `npm.cmd ci` | OK: 257 paquetes, 0 vulnerabilidades reportadas |
| Lint frontend | `npm.cmd run lint` | OK |
| Pruebas frontend | `npm.cmd run test` | OK: 3 pruebas |
| Tipos frontend | `npm.cmd run typecheck` | OK |
| Build frontend | `npm.cmd run build` | OK: 18 módulos |
| Migraciones, desde raíz | `python scripts/tests/test_postgres_migrations.py` | OK: ejecución final completa, incluidas colisiones/rollback, semillas, regresiones y JDBC antes/después de V8 |
| Calidad y revisión | `git diff --check`, enlaces, secretos, comparación V1–V7 | OK: sintaxis Python, 125 enlaces Markdown locales, sin firmas de secretos, V1–V7 idénticas byte a byte |

Se conservan íntegros postgres_integrity_contract.py y sus 17 positivos/81 negativos de LFD-26, incluidos ataques de referencias multi-tenant/multi-aplicación y normalización. Solo se adapta la expectativa anterior de 29 tablas vacías a siete con manifiesto y 22 vacías. Los fixtures continúan terminando en ROLLBACK; snapshots de todas las filas, incluidos timestamps, exigen preservación exacta de semillas.

El harness también conserva las 29 tablas originales, todas las columnas/PK/defaults, 29 FK compuestas validadas, los 73 índices exactos, 16 CHECK existentes y ausencia de cascadas destructivas en auditoría. Cada FK/índice sigue contrastado con el contrato independiente de LFD-26; la semilla no agrega constraints ni índices.

Pruebas V8 nuevas: colisión de UUID de aplicación, colisión de código normalizado LS-CORE y colisión tardía del segundo perfil. Cada intento usa Flyway real sobre V7 dentro del clúster nuevo y debe fallar con unicidad, dejando iguales datos, esquema e historial V7. El último comprueba rollback de los INSERT previos de V8. Solo se retiran las filas deliberadamente creadas por el test; luego V8 original recupera sin repair/baseline. No es un procedimiento de reparación de datos reales.

Además: historial V1–V8, checksums históricos, segunda migración sin cambios de datos/índices/historial, validate, checksum V8 alterado en copia rechazado, V99 con DDL fallido y recuperación. Se mantiene JDBC HTTP 200/UP sin migración automática antes de Flyway y se comprueba también después de V8 con historial y semillas preservados. JAR sin dependencia Flyway; runtime sin permisos sobre tablas, migrador limitado a ocho esquemas, PUBLIC sin privilegios, sentinel ajeno sin cambios.

La ejecución final terminó con `All isolated migration checks passed. Shared PostgreSQL was not contacted.` El snapshot posterior al segundo arranque Java coincide exactamente con el manifiesto persistido, incluidos timestamps. POM, workflow, contrato LFD-26 y ADR 0004 también se compararon byte a byte sin cambios. Se revisaron las nueve rutas cambiadas y el contenido SQL completo; sin firmas de claves privadas, tokens GitHub ni URLs PostgreSQL con contraseña. Los avisos existentes de Mockito/Byte Buddy sobre carga dinámica de agentes no provocaron fallos; no se alteraron dependencias por esos avisos.

## Historial Flyway observado

Todas las filas observadas success=true. La suite contrasta los checksums V1–V7 históricos.

| Versión | Checksum |
| --- | --- |
| V1 | -1378463590 |
| V2 | -895708369 |
| V3 | -849841087 |
| V4 | -1697819596 |
| V5 | 1729249324 |
| V6 | -1148166384 |
| V7 | -1527763311 |
| V8 | -1947984735 |

## PostgreSQL real y límites

No se conecta ni modifica PostgreSQL real. La única base llamada ls_core_dev de esta ejecución pertenece al clúster que initdb crea bajo TEMP, con PGDATA nuevo, SCRAM y puerto aleatorio distinto de 5432. El harness no acepta URL, credenciales ni directorio de datos externos. Toda provisión, roles/ACL sintéticos, migración y fixture ocurren exclusivamente ahí; pg_ctl detiene ese clúster y después se elimina su directorio temporal verificado. No se ejecutan inicializador ni herramientas de migración contra infraestructura compartida, servicios ni pg_hba.conf reales.

PUBLISHED publica definiciones. TENANT y BRANCH expresan el alcance autorizado futuro; no implementan aislamiento, autorización ni estados funcionales. No hay tenants, usuarios, propietarios, superadministradores ni permisos efectivos creados. F10 desarrollará administración RBAC y delegación sin escalada; F11 habilitaciones/navegación. El riesgo pendiente es interpretar el catálogo como acceso efectivo: ningún backend evalúa todavía estos permisos. Una futura instalación real requerirá autorización propia y revisión de datos/locks; queda fuera de esta entrega.

## CI y revisión técnica

Se publicará únicamente la rama LFD-27. El informe de entrega identificará commits/SHA final y enlace al run con los tres jobs del SHA final terminados. No se presentará un run pendiente como aprobado. Recomendación: revisión técnica de manifiesto, SQL, contrato y evidencia antes de cualquier PR, merge, cierre Jira o aplicación real; estas acciones no están autorizadas en esta etapa.
