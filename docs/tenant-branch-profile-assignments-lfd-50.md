# LFD-50 — Perfiles múltiples y autorización contextual

Base: PR #52, `origin/main` 9f670089b01fe3ed11d0a35f609f26f236b4a30c. Migración aditiva V32; V1–V31 permanecen intactas. PostgreSQL 17, Java 21 y React conectado.

## Modelo y transiciones

Se reutilizan `tenant.branch_memberships` y `security.branch_profile_assignments`. No existe una segunda matriz. Incorporar un miembro ACTIVE a una sucursal ACTIVE es un comando explícito; no asigna perfiles. Un vínculo existente ACTIVE puede devolver su mismo identificador. Un vínculo suspendido debe reactivarse mediante V30; la incorporación nunca lo reemplaza.

Una asignación nueva tiene estado ACTIVE, revisión 1 y `revoked_at IS NULL`. Revocarla conserva la fila con estado REVOKED, autor, fecha PostgreSQL y revisión incrementada. Volver a asignar crea otra fila histórica. El índice parcial impide duplicar una asignación activa por tenant, aplicación, vínculo de sucursal y perfil. Las claves compuestas comprueban las relaciones completas. Una base con duplicados activos preexistentes requiere revisión: la migración falla, no repara ni adopta silenciosamente.

Cada ASSIGN o REVOKE cambia `branch_memberships.updated_at`; es el sello de concurrencia común del vínculo, incluso entre aplicaciones. REVOKE también exige la revisión de la asignación. Una selección obsoleta produce CONFLICT. El lote ASSIGN admite hasta 50 UUID distintos y confirma todos o ninguno.

## Autoridad y límites de delegación

Los comandos utilizan el actor autenticado por la cookie Core. Nunca aceptan actor por HTTP. Requieren CSRF, contraseña F04 recién verificada, tenant ACTIVE y revalidación PostgreSQL mediante `security.rbac_authority`. OWNER conserva la frontera V31; SUPERADMIN y administrador empresarial necesitan concesiones específicas READ/MANAGE de la plataforma. Un perfil con `roles.manage` o `assignments.manage` no constituye autoridad administrativa.

La asignación comprueba el techo independiente V31 de cada permiso y que su clasificación coincida con la plataforma. La autoasignación se rechaza, también si el actor tiene autoridad administrativa. No se activan membresías PENDING ni se modifican identidad, contraseña, designaciones administrativas o consentimiento V29. La revocación permite reducir privilegios de vínculos o recursos inactivos mientras el actor conserve autoridad y el tenant esté ACTIVE.

`platform.permission_execution_rules` separa la semántica operativa de la delegación administrativa. Sus valores son READ/WRITE y SINGLE_BRANCH/RESERVED. Está vacía tras migrar: la publicación del catálogo no crea reglas ni asignaciones. Solo el operador de instalación puede configurarla mediante `scripts/migrations/set_permission_execution_rule.sql`, con `apply=true`, en la instalación local PG17 controlada. Cada cambio se audita atómicamente. El script exige una fila publicada válida y rechaza duplicados. No ofrece actualización genérica desde HTTP.

Una delegación administrativa expirada impide nuevas concesiones; no equivale a revocar automáticamente una asignación operativa existente. La disponibilidad operativa se comprueba mediante estados, catálogo, reglas de ejecución y asignaciones. El piloto instala explícitamente las clasificaciones de los nueve permisos V8; `consolidated.read` queda RESERVED.

## Unión y ámbitos

Para una identidad autenticada y el contexto exacto `(tenant, aplicación, sucursal, membresía, vínculo)`:

`E(contexto) = DISTINCT(unión de permisos de roles ACTIVE de perfiles ACTIVE de asignaciones ACTIVE no revocadas del vínculo) ∩ disponibilidad operativa`.

La disponibilidad exige identidad y F04 vigentes, tenant y membresía ACTIVE, sucursal y acceso individual ACTIVE, aplicación publicada y habilitada, permiso publicado con ámbito TENANT/BRANCH y regla SINGLE_BRANCH. Si hay módulo, debe estar publicado y habilitado. Aplicación o módulo READ_ONLY elimina WRITE; DISABLED elimina el acceso correspondiente. No hay herencia entre sucursales, empresas ni aplicaciones. Un perfil vacío no otorga permisos.

Un permiso TENANT devuelto sigue vinculado a la sucursal y evidencia verificadas. No concede selección de otras sucursales ni consultas consolidadas. RESERVED nunca aparece como permiso efectivo. No se implementan LFD-51, Fiscal/POS empresarial, menú RBAC ni API Keys.

## Resolución y coordinación transaccional

`security.context_permission_set` es la proyección común. `resolve_context_permissions` comprueba sesión, contexto, identidad, todos los vínculos, expiración, revocaciones y F04. F05 la ejecuta en su propia conexión, bajo las barreras compartidas adquiridas antes del snapshot repetible. Detecta V32 en PostgreSQL: una instalación V32 no puede optar por el lector histórico sin clasificación. Las instalaciones anteriores conservan el comportamiento previo.

`JdbcContextPermissions` es una utilidad interna para coordinadores confiables. Exige una conexión propia del rol `ls_core_context_permissions_dev`, transacción READ COMMITTED no autocommit y una operación READ/WRITE concreta. `require_context_permission` verifica el código exacto y mantiene barreras y protección F04 hasta COMMIT/ROLLBACK. La conexión debe permanecer abierta durante la operación admitida. Su binding no es una credencial ni una entrada HTTP. No existe un endpoint de SQL empresarial o de ejecución arbitraria.

La validez de sesión/contexto se comprueba al admitir la operación; logout impide admisiones posteriores. No se bloquea la fila de sesión desde una segunda conexión mientras el coordinador Core la retiene. Las barreras serializan revocaciones de asignación, acceso, tenant y configuración frente a la operación admitida. Una operación admitida antes de la revocación puede terminar; una iniciada después del COMMIT de revocación se rechaza. El resolvedor interno usa además un máximo conservador de 15 minutos desde la última actividad persistida.

Orden: barrera F06 `(370019,1)` → V23 `(410023,1)` → tenant → identidades/credenciales ordenadas → membresías/designaciones → vínculos → roles/perfiles y asignaciones → contextos. Los comandos mantienen los bloqueos hasta terminar. La barrera exclusiva serializa los cambios con V30/V31; no se modifica su protección de composiciones ya asignadas. F06 conserva su consumidor de lectura acotada preexistente y su admisión nativa; esta entrega no lo convierte en un canal empresarial general. Los futuros consumidores operativos deben usar la comprobación contextual en su transacción, nunca el JSON de React.

ASSIGN y REVOKE revocan únicamente contextos de la identidad, tenant, membresía, sucursal y aplicación afectados. Conservar sesiones globales, otras sucursales y otras empresas es una invariante. La incorporación sin perfiles no revoca contextos. Volver a asignar o reactivar nunca pone `revoked_at` a NULL. La nueva selección F05 recompone los permisos desde PostgreSQL.

## Instalación y configuración

Después de V31, ejecutar `scripts/migrations/prepare_profile_assignments.sql` con `apply=true` como operador de instalación y suministrar las tres contraseñas SCRAM por el prompt de psql. Aplicar V32 con el migrador restringido. No conceder membresías entre los roles de servicio.

Habilitar `ls.core.profile-assignments.enabled`, los endpoints V31 `ls.core.tenant-rbac.enabled`, Core, F04 y `ls.login.contexts.enabled`. Configurar `LS_PROFILE_ASSIGNMENTS_DB_{HOST,PORT,NAME,PASSWORD}` y `LS_PROFILE_ASSIGNMENTS_LOG_DB_{HOST,PORT,NAME,PASSWORD}` sobre la misma base autoritativa F04; el nombre de rol es fijo en Java. El resolvedor interno usa su propio canal y una conexión administrada por el coordinador confiable, sin credenciales en HTTP. La clasificación y los techos de delegación requieren instalación explícita antes de asignar perfiles con permisos.

## API

Base administrativa: `/api/auth/core/rbac/assignments/`.

| Método y ruta relativa | Resultado |
| --- | --- |
| GET `tenants/{tenant}/applications/{app}/members` | Miembros visibles a la autoridad RBAC |
| GET `tenants/{tenant}/applications/{app}/members/{member}` | Estado empresarial, sucursales, perfiles asignables, asignaciones e historial |
| GET `tenants/{tenant}/applications/{app}/members/{member}/branches/{branchMember}/preview` | Proyección administrativa de una sola sucursal; no acredita sesión operativa |
| POST `tenants/{tenant}/applications/{app}/commands/join-branch` | Incorporación explícita sin perfiles |
| POST `tenants/{tenant}/applications/{app}/commands/assign` | Lote atómico de perfiles |
| POST `tenants/{tenant}/applications/{app}/commands/revoke` | Revocación histórica |
| GET `operations/{requestId}` | Recibo exclusivo del actor original, incluso tras perder autoridad empresarial |

Comandos: `requestId`, `membershipId`, `branchId`, `branchMembershipId`, `assignmentId`, `expectedUpdatedAt`, `expectedRevision`, `profileIds`, `password`. JOIN omite vínculo/sellos/asignación; ASSIGN utiliza vínculo/sello/perfiles; REVOKE utiliza vínculo/sello/asignación/revisión y lista vacía. Los campos ajenos a cada acción se rechazan en PostgreSQL.

Proyección propia del titular: GET `/api/auth/core/rbac/context/tenants`, GET `.../tenants/{tenant}/branches`, POST `/api/auth/core/rbac/context` con selectores tenant/sucursal y CSRF, GET `.../context/permissions`. La identidad, aplicación y vínculo proceden de la sesión y relaciones verificadas. Esta respuesta no autoriza operaciones posteriores por sí sola.

La consulta propia de contexto sin selección vigente devuelve 204; React lo normaliza a ausencia de contexto.

Estados: 200 CONFIRMED, 202 UNKNOWN, 400 entrada inválida, 401 sesión inválida, 403 autoridad/CSRF/F04 inválidos, 404 recurso no visible, 409 conflicto, 422 precondiciones incompletas, 503 indisponibilidad. No se exponen SQLSTATE ni diagnósticos SQL. Las listas administrativas están acotadas a 1000 filas, historial a 50; superar el límite falla de forma neutral. La paginación ampliada queda pendiente.

## Auditoría, RLS y resultado incierto

`security.profile_assignment_operations` conserva requestId único, actor, empresa, aplicación, miembro, sucursal, vínculo, acción, entrada normalizada, estado anterior, resultado y fecha PostgreSQL. Los eventos `TENANT_PROFILE_JOIN_BRANCH`, `TENANT_PROFILE_ASSIGN` y `TENANT_PROFILE_REVOKE` se confirman con el cambio y las revocaciones. Si falla la auditoría, se revierte todo. Eventos y recibos son inmutables.

La repetición autenticada de un requestId con la misma entrada devuelve el recibo original; otra entrada produce CONFLICT. Se comparan UUID, timestamp tipado, revisión y lista de perfiles; el orden de la lista forma parte del comando. Una pérdida del reconocimiento de COMMIT devuelve UNKNOWN. Se consulta el UUID, no se repite automáticamente. Un 404 de reconciliación no demuestra que sea seguro volver a ejecutar.

Roles SQL independientes: administrador de asignaciones (límite 3), log mínimo (2), resolvedor interno (3). NOINHERIT, sin membresías de roles, sin ownership, sin BYPASSRLS. Tablas nuevas ENABLE/FORCE RLS; funciones SECURITY INVOKER, EXECUTE público revocado. Los permisos de escritura de hijos solo sirven dentro del trigger del comando; no habilitan DML empresarial arbitrario. No se registran contraseñas, cookies, hashes ni tokens.

## Validación local y CI

Ejecutar con JAVA_HOME de JDK 21 y binarios PostgreSQL 17:

```powershell
python scripts/tests/postgres_profile_assignments_contract.py
cd frontend
npm run test
npm run lint
npm run typecheck
npm run build
```

El contrato crea un clúster propio con puerto efímero, aplica/valida V1–V32, conserva una base prístina y restablece los datos entre `ProfileAssignmentsPostgresTest`, `TenantAccessPostgresTest`, `TenantRbacPostgresTest`, `SessionContextPostgresTest` e `InactiveBranchPostgresTest`. Las conexiones JDBC son independientes. Incluye unión/distinct, consentimiento V29, separación de empresas/sucursales con cookies reales, CSRF/F04, conflicto de versión, DML/RLS, revocación frente a operación admitida, rollback de auditoría, UNKNOWN y recibos. Las regresiones cubren acceso V30, techo/composiciones V31, sesiones F05 y lectura acotada F06/F08. La fixture sintética de F05 instala clasificaciones explícitas únicamente cuando corre sobre V32.

CI mantiene Backend Java 21, Frontend Node 24 y Migrations PostgreSQL 17 con timeout 45 minutos. Conserva íntegros los pasos V30/V31 y agrega el contrato V32. El CI publicado requiere revisión posterior de sus tres jobs; su inicio no equivale a SUCCESS.

Evidencia local de esta entrega: las cinco clases anteriores aprobaron en clústeres PG17 desechables sobre V32. Después de corregir la clasificación WRITE de la fixture F05, se ejecutó el bloque `--sessions` (F05, F06/F08 y asignaciones finales); las regresiones V30/V31 ya aprobadas no se repitieron. `mvnw verify` aprobó con 126 casos, 34 integraciones omitidas por requerir su entorno propio; estas omisiones no sustituyen las cinco ejecuciones PG17 explícitas. React aprobó 54 pruebas y una omisión preexistente; después se aprobaron las tres pruebas focalizadas de asignaciones, incluida ausencia de contexto HTTP 204. ESLint, TypeScript, build y Checkstyle aprobaron. El piloto `--core --smoke-only` aprobó y retiró sus recursos. V1–V31 y los tres documentos locales se verificaron por SHA-256.

## Piloto y recorrido visual

```powershell
python scripts/pilot/start_connected_pilot.py --core
# Verificación HTTPS automatizada con retiro inmediato:
python scripts/pilot/start_connected_pilot.py --core --smoke-only
```

El piloto utiliza exclusivamente su clúster desechable y certificados/procesos temporales. Incorpora por comando V32, materializa plantillas V8 expresamente y asigna dos perfiles en CASA MATRIZ, otro conjunto en SUCURSAL 02 y perfiles propios en la empresa B. Usa tres cookies reales del titular para comprobar permisos distintos y revocación selectiva. Comprueba recibos y conserva las filas revocadas. No genera registros empresariales Fiscal/POS.

1. Usar la URL HTTPS e identidades que muestra el piloto. En modo interactivo, crear y activar las empresas y sucursales desde Core. Ejecutar `fixture-rbac <UUID_EMPRESA>` en la consola del piloto para instalar explícitamente los techos, habilitaciones y clasificaciones V8 del clúster desechable. En **Empresas → Roles y perfiles**, materializar las plantillas aprobadas y crear los perfiles adicionales. Incorporar el usuario a A y vincularlo consentidamente con B mediante F09. Abrir **Empresas → Usuarios → Detalle → Sucursales y perfiles**. El recorrido automatizado de dos empresas se ejecuta con `--smoke-only` y se retira al terminar.
2. Consultar aplicaciones, seleccionar LS Core y una sucursal. Distinguir estado global de sucursal, acceso individual y asignaciones históricas.
3. En un usuario sin vínculo, elegir **Vincular usuario a sucursal**, confirmar y escribir contraseña F04 nueva. Comprobar que no aparecen perfiles automáticamente.
4. Seleccionar varios perfiles delegables, confirmar y comprobar historial y fecha persistidos. Los perfiles ya asignados no pueden duplicarse.
5. Iniciar sesión como titular y abrir **Mis permisos del contexto**. Seleccionar empresa/sucursal. La lista contiene la unión sin duplicados y no añade permisos de otra sucursal.
6. Mantener otras sesiones en SUCURSAL 02 y empresa B; revocar un perfil en CASA MATRIZ. Su contexto queda inválido, las otras sesiones conservan sus contextos. Volver a seleccionar y comprobar la nueva unión.
7. Volver a asignar el perfil: el contexto histórico sigue revocado. Probar un perfil ajeno o un permiso de módulo no habilitado: el backend lo rechaza.
8. Ante UNKNOWN conservar UUID y reconciliar. Si se pierde la autoridad administrativa, recargar e iniciar sesión con el actor original para consultar su recibo.
9. Cerrar el piloto con su salida normal para retirar clúster, certificados y procesos.

Límites: no hay matriz consolidada, reportes multi-sucursal, API Keys, asignación automática, autorización por menú ni módulos empresariales completos. La revisión visual humana y el resultado definitivo de CI son controles posteriores a la entrega local.
