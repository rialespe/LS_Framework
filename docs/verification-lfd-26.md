# Verificación LFD-26 — integridad compuesta y unicidad

Fecha: 2026-10-16. Ticket [LFD-26](https://lssoluciones.atlassian.net/browse/LFD-26), padre LFD-3. Preparación y alcance contrastados con [comentario 10052](https://lssoluciones.atlassian.net/browse/LFD-26?focusedCommentId=10052), descripción Jira, épica LFD-1 y esquema real.

## Git y alcance

Base origin/main: 9e23a99f882dbc7d36a8ea4a987863ef634b2e2d, merge PR #4 con V1–V5 y 29 tablas. Árbol inicial limpio, sin archivos sin seguimiento, commits locales sin publicar ni rama LFD-26 anterior. Se creó y publicó antes de editar feature/LFD-26-claves-foraneas-compuestas-y-unicidad desde ese SHA exacto.

No se modifica main, V1–V5, Flyway 11.14.1, POM, workflow, provisión/inspección, bootstrap LFD-23, Java, React ni dependencias. Sin PR, merge, borrado de ramas ni cambios Jira. ADR 0004 sigue Propuesto. La aprobación de LFD-26 no autoriza provisión ni migraciones en PostgreSQL real.

## Archivos

Creados:
- backend/src/main/resources/db/migration/V6__add_composite_referential_integrity.sql.
- backend/src/main/resources/db/migration/V7__add_normalized_uniqueness_and_indexes.sql.
- scripts/tests/postgres_integrity_contract.py: contratos explícitos del catálogo y casos sintéticos.
- docs/verification-lfd-26.md.

Modificados:
- scripts/tests/test_postgres_migrations.py: FK completas por nombre/orden/columnas/padre, índices/UNIQUE/checks, historial V1–V7, snapshots que incluyen constraints e índices y lotes largos por stdin.
- docs/database/data-dictionary.md: referencias físicas actuales por tabla, candidatas, matriz completa de FK, unicidades, inventario/justificación de índices y Mermaid.
- docs/postgresql-migrations.md: secuencia V6/V7, comandos existentes, normalización, errores y recuperación.
- docs/architecture/modules.md y README.md: alcance implementado y referencias.

La evidencia histórica LFD-25 se conserva sin modificar. No hay tablas nuevas: siguen exactamente 29 de dominio más operations.flyway_schema_history.

## Cambios físicos

V6: 14 UNIQUE candidatos, 29 FK compuestas, sustitución transaccional de diez FK simples del mismo padre que quedan cubiertas por referencias más fuertes. Primero se añade/valida el reemplazo y después se retira la FK simple; no se borran filas ni tablas, ni se cambia ownership.

V7: 11 CHECK de presencia/coherencia, 2 UNIQUE naturales, 10 índices únicos normalizados y 18 índices no únicos de apoyo. Inventario de dominio esperado y comprobado: 73 índices (29 PK + 16 UNIQUE + 10 normalizados + 18 apoyo). No hay duplicados exactos.

Ver la [matriz de 29 FK y claves candidatas](database/data-dictionary.md), que identifica tabla hija, columnas en orden, padre/candidata y casos válidos/negativos. Las FK se validan inmediatamente, MATCH SIMPLE / NO ACTION en actualización y eliminación. Nulabilidad opcional comprobada para module_id, fuentes de plantillas y branch_id de API Keys. Auditoría no recibe FK ni cascadas.

## Unicidad y decisiones

Username global normalizado y correo verificado global normalizado: lower(btrim()). El espacio eliminado es U+0020; no se normalizan otros espacios, Unicode, acentos ni reglas de proveedores de correo. Resultado dependiente de collation/LC_CTYPE; el fixture se inicia con locale C y UTF8 y prueba casos ASCII especificados.

Jira confirma correo verificado, sin exigir unicidad de todo correo no verificado. Se permiten duplicados no verificados, incluso frente a uno ya verificado; verificar el segundo equivalente falla. Username/email informados y códigos deben ser no blancos. No se añade validación de sintaxis de correo/username.

Membresía es única por tenant/user; acceso por tenant/membership/branch; habilitaciones por tenant/application y tenant/application/module. Códigos globales por ámbito: aplicación global; módulo/permiso por aplicación; plantillas por aplicación/código/versión; roles/perfiles por tenant/aplicación; sucursal por tenant. PK de asociaciones preservadas.

No se agrega índice de designaciones vigentes en platform.administrators, tenant.administrators ni branch_profile_assignments: falta definición aprobada de vigencia/estados e historial. Esto no se presenta como bloqueo de las FK aprobadas ni como garantía funcional implementada. Se permiten múltiples perfiles distintos por sucursal. Último administrador activo, protección OWNER y concurrencia siguen fuera de este ticket.

## Pruebas locales ejecutadas

Entorno Windows PowerShell, Python 3.13, JDK 21.0.10, Maven Wrapper 3.9.14, Node 24.14.0, npm 11.12.1. PostgreSQL 17.2: solamente binarios existentes para initdb de clústeres nuevos TEMP, puerto aleatorio distinto de 5432, SCRAM y contraseñas efímeras. Nunca PostgreSQL compartido.

| Prueba | Comando y directorio | Resultado | Evidencia |
| --- | --- | --- | --- |
| Java 21, smoke dev/test y Checkstyle | backend: .\mvnw.cmd -B -ntp clean verify | OK | 10 tests, 0 fallos/errores/omitidos; 0 violaciones, BUILD SUCCESS |
| Instalación limpia | frontend: npm.cmd ci | OK | 257 paquetes; 0 vulnerabilidades reportadas |
| ESLint | frontend: npm.cmd run lint | OK | Salida 0, incluye tipos |
| React smoke | frontend: npm.cmd run test | OK | 3 pruebas aprobadas |
| TypeScript | frontend: npm.cmd run typecheck | OK | Salida 0 |
| Vite | frontend: npm.cmd run build | OK | 18 módulos; compilación correcta |
| Estructura de 29 tablas/columnas/PK/defaults | raíz: python scripts/tests/test_postgres_migrations.py | OK | Sin alteración del contrato de columnas ni tablas inesperadas |
| Candidatas/FK/índices | Mismo harness | OK | 14 candidatas, 29 FK compuestas, nombres/orden/destinos, 16 UNIQUE, 10 índices normalizados, 18 apoyo; catálogo exacto |
| Relaciones entre ámbitos | Mismo harness | OK | Fixture válido A/B + X/Y + A1/A2/B1; negativos con UUID existentes ajenos e inexistentes |
| Unicidad y NULL opcionales | Mismo harness | OK | 17 positivos adicionales y 81 negativos totales, SQLSTATE 23503/23505/23514, incluyendo INSERT y UPDATE |
| Snapshots/repetición | Mismo harness | OK | Segunda aplicación sin cambios de columnas, constraints, índices ni historial |
| Historial/checksum/rollback | Mismo harness | OK | V1–V7 en orden; validación correcta, copia alterada rechazada, V99 fallida revierte y recuperación sin repair/baseline |
| Permisos | Mismo harness | OK | App sin DDL/DML nuevos/historial; migrador limitado; PUBLIC sin concesiones; sentinel ajeno preservado |
| JDBC sin migrar | Mismo harness | OK | JAR sin Flyway, perfil postgres HTTP 200/UP sobre fixture antes de migrar; sin historial creado |
| PostgreSQL real | No ejecutado | NO EJECUTADA | Prohibido por alcance: sin conexiones, DDL, roles, contraseñas ni ACL reales |

Fixtures válidos, positivos y negativos se ejecutan dentro de transacciones que terminan en ROLLBACK; las 29 tablas quedan vacías. Los fallos esperados se capturan por SQLSTATE; un éxito inesperado produce P0001 y hace fallar el harness. No hay semillas permanentes ni secretos auténticos.

### Incidencia corregida durante pruebas

El primer harness ampliado aprobó migraciones/catálogos y las regresiones previas, pero no pudo lanzar el lote nuevo: WinError 206, argumentos demasiado largos para CreateProcess. Se cambió únicamente el transporte de SQL grande a psql -f - por stdin; no se escriben fixtures ni secretos en archivos. La prueba dirigida y la suite completa posterior aprobaron. No se cambió el modelo para eludir fallos.

Los smoke Java mantienen los avisos previos de Mockito/Byte Buddy sobre carga dinámica de agentes; no provocan fallos y no se cambiaron dependencias fuera de alcance.

## Historial observado

Todas las versiones success=true. Los checksums V1–V5 coinciden con LFD-25.

| Versión | Checksum Flyway |
| --- | --- |
| V1 | -1378463590 |
| V2 | -895708369 |
| V3 | -849841087 |
| V4 | -1697819596 |
| V5 | 1729249324 |
| V6 | -1148166384 |
| V7 | -1527763311 |

El harness terminó: All isolated migration checks passed. Shared PostgreSQL was not contacted.

## Rendimiento e índices

El [diccionario](database/data-dictionary.md) enumera cada índice y los descartados por redundancia. PK/candidatas se reutilizan cuando sus prefijos acotan eficazmente los hijos: acceso tenant/membership/branch, asociaciones por UUID global de plantilla, o por tenant/UUID del rol/perfil/API Key. El índice de acceso de contextos también sirve a la membresía, cuyo user queda fijado por FK.

EXPLAIN con enable_seqscan=off dentro de la transacción sintética verifica elegibilidad de índices de búsqueda por sucursal; no mide rendimiento productivo ni demuestra selección óptima con datos reales. Aumentan coste de escritura/almacenamiento y requieren futuras estadísticas/planes representativos. Los FK simples históricos sin índice específico deberán revisarse antes de habilitar eliminaciones administrativas.

La migración no usa CREATE INDEX CONCURRENTLY: índices y constraints son transaccionales. Un despliegue real requerirá revisión de datos incompatibles, volumen y locks; no se corrigen duplicados o referencias automáticamente.

## CI y calidad documental

Se conservan tres jobs: Backend - Java 21, Frontend - Node 24 y Migrations - isolated PostgreSQL 17. El tercero ejecuta el harness ampliado y mantiene unix_socket_directories='' exclusivamente en PGDATA desechable Linux. No consume secretos reales ni servidores externos.

El resultado por SHA se registra después del push; el CI anterior no se presenta como evidencia del código nuevo. Validaciones locales de diff, enlaces y workflow registradas en la sección final.

## Límites y pendientes

La base ahora exige pertenencia referencial para las relaciones listadas; esto NO es aislamiento de lectura/escritura. FK no sustituyen RLS, propagación de contexto ni autorización runtime. Existencia de habilitación no prueba estado ACTIVE: el fixture demuestra que cambiar tenant a SUSPENDED, habilitación a DISABLED o revocar sesión no invalida por sí solo las referencias.

LFD-27 conserva semillas y verificación ampliada. Pendientes: RLS, autenticación/autorización, estados/revocaciones funcionales, administrador activo y políticas de designaciones históricas. ADR 0004 conserva Propuesto; provisión y migraciones reales requieren autorizaciones específicas. PUBLIC CONNECT heredado sigue abierto, sin tocar bases ajenas.

No se cierra Jira ni se abre PR. El cierre técnico corresponde a revisión posterior del propietario, con CI del SHA final.

## Revisión final local

- git diff --check y sintaxis Python mediante ast.parse: OK.
- actionlint 1.7.12 con -shellcheck=: OK; workflow preservado, tres jobs.
- Comprobador reproducible de enlaces LFD-82: OK, 18 Markdown, 107 enlaces locales, 0 errores.
- Revisión byte a byte de V1–V5 y ocho archivos de infraestructura/evidencia protegidos: OK; 13 archivos sin cambios.
- Revisión de las nueve rutas modificadas/nuevas y búsqueda de firmas de claves privadas, tokens y URLs con contraseña: sin hallazgos. SQL revisado sin semillas, GRANT, cambios de rol, DROP destructivos ni IF NOT EXISTS.
- EXPLAIN local observó Index Scan usando branch_memberships_branch_scope_idx y session_contexts_branch_scope_idx. Solo es elegibilidad sintética con seqscan deshabilitado en la transacción; no un benchmark. El parser admite también Bitmap Index Scan para no fijar una elección de plan dependiente del entorno.
- La ejecución local final comprobó además los nombres exactos de todas las FK y preservación de checksums históricos. Su log termina correctamente; todas las filas sintéticas fueron revertidas.

Las referencias del catálogo por tabla y el diagrama corresponden al estado después de V7, incluyendo los diez reemplazos de FK simples.

## GitHub Actions comprobado

[Run 38988357015](https://github.com/rialespe/LS_Framework/actions/runs/38988357015), commit de implementación **59cc6e5473c5f5903f4c132130158d1e3c4e2318**, completed / success. Se consultaron run/jobs y los logs completos descargados de GitHub; no se infiere éxito de un workflow bien formado.

| Job | Resultado | Evidencia |
| --- | --- | --- |
| Backend - Java 21 | OK / success | Compilación, 10 smoke tests y Checkstyle |
| Frontend - Node 24 | OK / success | Instalación limpia, lint, 3 tests, tipos y build |
| Migrations - isolated PostgreSQL 17 | OK / success | Nombres/columnas/orden de las 29 FK, 73 índices, 17 positivos/81 negativos, tablas vacías, V1–V7, repetición, checksum, rollback/recuperación, permisos y JDBC sin migrar |

V6 checksum -1148166384 y V7 -1527763311 coincidieron con Windows. El job reprodujo el fallo esperado del socket del sistema en su intento de arranque desechable; luego operó con unix_socket_directories='' solo en su PGDATA nuevo. Terminó con All isolated migration checks passed. Shared PostgreSQL was not contacted. No se cambiaron permisos de directorios globales ni PostgreSQL compartido.

Esta actualización solo registra evidencia de la implementación ya probada. El informe de entrega identifica además el SHA final de la rama y su propio run CI, consultado después de publicar este registro. La implementación está técnicamente preparada para revisión del propietario, con las decisiones funcionales pendientes expresamente indicadas; no se abre PR ni se modifica Jira.
