# LFD-48 / F09.T03 — Ciclo de vida de accesos individuales

Base: `e9c8f691cf6b398a33e287835212037e49a9ae00`, merge #50 de LFD-47.
V30 es aditiva; no cambia V1–V29. No crea identidades, membresías, asignaciones,
perfiles, roles ni permisos. Suspender acceso no cambia el estado de la identidad,
contraseña, sucursal global o sesiones completas.

## Dependencia y validación previa

Se consultó una sola vez el run LFD-47 `41449611414`: `in_progress`. Conforme al
encargo, no se esperó ni se volvió a consultar. Antes de desarrollar V30 se
aplicó/validó V29 y aprobaron IdentityLinksPostgresTest y TenantUsersPostgresTest
en PostgreSQL 17 desechable. Ese resultado local no sustituye el resultado final
remoto. La revisión de CI de esa dependencia y de LFD-48 queda pendiente para
ChatGPT; no se recomienda fusionar con un fallo o bloqueo pendiente.

## Estados y transiciones

El esquema histórico usa TEXT sin CHECK global para los estados de
`tenant.memberships` y `tenant.branch_memberships`. Los consumidores F05/F06/F08
exigen ACTIVE; las fixtures existentes también utilizan PENDING. V30 no agrega un
catálogo global que pueda invalidar estados históricos: restringe exclusivamente
sus comandos a ACTIVE → SUSPENDED y SUSPENDED → ACTIVE. Cualquier otro estado,
incluido PENDING o valores terminales heredados, produce conflicto sin cambiarlo.
La incorporación PENDING conserva sus mecanismos V28. El consentimiento V29,
accepted_at y registros históricos permanecen intactos.

| Acción | Recurso y efecto | Condiciones adicionales |
| --- | --- | --- |
| MEMBER_SUSPEND | Suspende una membresía; revoca contextos por tenant + membershipId | ACTIVE; sin designaciones administrativas vigentes; OWNER protegido |
| MEMBER_REACTIVATE | Reactiva la misma membresía | SUSPENDED; empresa ACTIVE; identidad ACTIVE y F04 definitivo vigente; sin designaciones vigentes |
| BRANCH_SUSPEND | Suspende una asignación existente; revoca contextos por tenant + membershipId + branchId | Asignación ACTIVE; OWNER protegido |
| BRANCH_REACTIVATE | Reactiva esa misma asignación | Asignación SUSPENDED; tenant, membresía y sucursal ACTIVE; identidad/F04 vigentes |

Todas las mutaciones exigen autoridad vigente del actor y versión exacta
`expectedUpdatedAt`. Un OWNER puede retirar un acceso ACTIVE de un tenant ya
SUSPENDED; ninguna reactivación operativa cruza ese estado. Un administrador de
tenant requiere su empresa ACTIVE para conservar autoridad F09. Suspender el
acceso individual a una sucursal CLOSED/SUSPENDED no cambia la sucursal para otros
usuarios; reactivarlo exige que la sucursal sea ACTIVE.

Reactivar nunca pone revoked_at en NULL ni crea una asignación ausente. Los
contextos históricos siguen revocados. Para operar se requiere una nueva selección
F05 y las autorizaciones existentes; conservar una asignación no sustituye esas
autorizaciones. La interfaz muestra un vacío real cuando no hay asignaciones.

## V24 y último administrador

Precondición explícita: antes de suspender **o reactivar** una membresía con
cualquier designación administrativa no revocada, retirar esa designación por
V24. Se bloquean también designaciones PENDING u otros estados no revocados.
V24 rechaza retirar al último administrador válido. Si hay reemplazo, debe
designarse/activarse primero mediante V24; después puede revocarse la designación
anterior y suspenderse su membresía.

V30 no cambia designaciones y no permite recuperar autoridad administrativa por
reactivar una membresía. La revocación V24 queda revocada incluso tras reactivar.
Además se verifica `tenant.valid_administrators` dentro del comando y un constraint
trigger DEFERRABLE INITIALLY DEFERRED reutiliza
`tenant.guard_administrative_continuity` antes de COMMIT. Se bloquea el conjunto
completo de identidades/credenciales de las membresías del tenant, como V24, para
estabilizar la elegibilidad. Dos intentos concurrentes de suspender al último
administrador se rechazan en PostgreSQL. Un OWNER global vigente no es un objetivo
permitido de estas acciones, aunque tenga una membresía local.

## Canal SQL y autorización

`tenant.access_operations` es el contrato de comandos y recibos inmutables.
Contiene actor, tenant, membresía, identidad, asignación/sucursal opcionales,
acción, estado anterior/resultante, versión esperada/resultante, número de
contextos revocados y fechas PostgreSQL. RequestId es único. Claves compuestas
vinculan tenant/membresía/identidad y tenant/asignación/membresía/sucursal;
V30 agrega una clave candidata compuesta a branch_memberships para ese vínculo.

Roles independientes `ls_core_tenant_access_dev` (3 conexiones) y
`ls_core_tenant_access_log_dev` (2). Provisionar explícitamente tras V29 con
`scripts/migrations/prepare_tenant_access.sql`; rechaza roles existentes, usa
SCRAM y requiere PG17 local. V30 comprueba que sean roles nuevos, sin herencia,
propiedad, membresías SQL, creación, superusuario o BYPASSRLS. No hay SECURITY
DEFINER; funciones INVOKER con search_path fijo. Tabla nueva con FORCE RLS.
El runtime general y el usuario HTTP no reciben privilegios de actualización.
El canal de comandos tiene permisos por columna; RLS y triggers subordinados
impiden DML directo fuera del comando. No hay permisos nuevos sobre sesiones,
perfiles o consentimientos V29.

Se reutiliza `tenant.user_authority` V28: OWNER vigente; SUPERADMIN con READ y
MANAGE específicos en platform.tenant_user_grants; administrador válido de su
tenant. No se infiere autoridad global desde una designación empresarial. El
backend deriva al actor de la sesión LS Core; no hay actorId en el contrato HTTP.
Las consultas exigen autoridad de lectura del tenant. El recibo propio puede
reconciliarse aunque se haya perdido autoridad empresarial.

La cookie, CSRF y validación de contraseña son responsabilidades del coordinador
Java F04/F05. Cada mutación verifica de nuevo la contraseña, fija su elegibilidad
con bloqueos y rechaza una prueba de más de diez segundos o con credencial
modificada durante la comprobación. PostgreSQL vuelve a comprobar autoridad,
recursos y estados bajo sus bloqueos. El rol SQL es una frontera de confianza del
backend, igual que V28/V29; no valida cookies HTTP ni acepta SQL del navegador.

## Concurrencia y revocación efectiva

Orden: F06 `(370019,1)` → V23 `(410023,1)` → tenant → identidades ordenadas →
credenciales ordenadas → membresías/designaciones → acceso de sucursal y sucursal
global → contextos afectados. Bloqueos hasta COMMIT/ROLLBACK. Los escritores usan
las barreras exclusivas existentes; los consumidores F05/F06/F08 conservan sus
barreras compartidas. La suspensión drena operaciones previamente admitidas. Una
selección u operación posterior al COMMIT de suspensión se rechaza.

Versiones antiguas y transiciones incompatibles dan conflicto; no se reintentan
automáticamente. La serialización es deliberadamente conservadora y limita el
paralelismo administrativo. No se redefine el orden de consumidores anteriores.
Los bloqueos y sentencias del canal están acotados a cinco segundos.

Revocar no toca security.sessions. Suspender A conserva membresía, contextos y
sesiones válidas de B. Suspender SUCURSAL 02 conserva CASA MATRIZ y otros usuarios
de SUCURSAL 02. El acceso de sucursal conserva su estado al suspender la empresa,
pero la membresía SUSPENDED impide usarlo. Reactivar vuelve a evaluar todos los
requisitos y no restaura contextos.

## Auditoría, idempotencia y UNKNOWN

Mutación, revocación, recibo y evento SUCCESS son una sola transacción; un fallo
de auditoría revierte todo. Evidencia inmutable con códigos
TENANT_ACCESS_MEMBER_SUSPEND, TENANT_ACCESS_MEMBER_REACTIVATE,
TENANT_ACCESS_BRANCH_SUSPEND y TENANT_ACCESS_BRANCH_REACTIVATE. No hay secretos,
cookies, hashes ni contraseñas en recibos, auditoría o respuestas.

Repetir explícitamente el mismo UUID con actor/ámbito/acción/versión originales
devuelve el recibo sin nueva mutación. Cambiar esos selectores da conflicto. Una
pérdida del reconocimiento de COMMIT produce UNKNOWN/202. React conserva solo el
UUID, bloquea nuevas acciones de acceso y consulta el recibo sin reenviar la
mutación. Un 404 no demuestra rollback. El actor original debe recuperar su sesión
y reconciliar. El canal de log registra DENIED/UNKNOWN con metadatos vacíos.

## API y React

Prefijo `/api/auth/core/tenant-users/`:

| Método / ruta | Contrato |
| --- | --- |
| GET `tenants/{tenant}/users/{membership}/access` | Estado empresarial, F04, protección administrativa, asignaciones existentes, resumen seguro de revocaciones e historial reciente |
| POST `tenants/{tenant}/users/{membership}/access/suspend` | Suspensión empresarial |
| POST `tenants/{tenant}/users/{membership}/access/reactivate` | Reactivación empresarial |
| POST `tenants/{tenant}/users/{membership}/branch-accesses/{branchMembership}/suspend` | Suspensión individual por sucursal |
| POST `tenants/{tenant}/users/{membership}/branch-accesses/{branchMembership}/reactivate` | Reactivación individual por sucursal |
| GET `access-operations/{requestId}` | Recibo exclusivo del actor original |

Mutaciones: `{requestId, expectedUpdatedAt, password}`. BranchMembershipId siempre
pertenece al tenant y membershipId de la ruta. No hay colisiones con activate/reissue
V28, identity-links V29 ni estados globales de sucursal V25. Respuestas: 200
confirmado; 202 UNKNOWN; 400 entrada inválida; 401 sesión; 403 CSRF/F04/autoridad;
404 recurso ausente; 409 estado/versión/designación/OWNER; 422 requisitos de
reactivación incompletos; 503 canal o auditoría. Sin diagnósticos SQL.

Activación opt-in `ls.core.tenant-access.enabled=true`, Core y F04 reales, con
LS_TENANT_ACCESS_DB_* y LS_TENANT_ACCESS_LOG_DB_* en la misma instalación F04.
Se reutilizan lectores, autorización y barreras F09; el servicio nuevo limita su
responsabilidad al ciclo de vida y sus recibos.

Empresas → Usuarios → Detalle mantiene el diseño y navegación horizontal actuales.
Muestra identidad mediante V28 y el panel nuevo de membresía, F04, acceso
individual, estado global de sucursal, historial y conteos de contextos históricos
revocados; no expone cookies ni IDs de sesiones. Acciones con confirmación,
contraseña nueva, carga y reconciliación. Sin cambios optimistas ni datos DEMO.
Historial: últimas 50 operaciones; asignaciones: hasta 1000, rechazando un resultado
mayor en lugar de truncarlo silenciosamente. No se implementa un editor de permisos.

## Pruebas y piloto

Dependencia previa: `python scripts/tests/postgres_identity_links_contract.py`,
fijada explícitamente en V29 para no cambiar su significado al agregar V30.

Validación final focalizada:

```powershell
$env:JAVA_HOME='C:\Program Files\Java\jdk-21.0.10'
python scripts/tests/postgres_tenant_access_contract.py
```

El harness posee y elimina PG17 en puerto efímero, rechaza 5432/55432, valida
V1–V30 y usa copias limpias para cada prueba: TenantAccessPostgresTest,
TenantUsersPostgresTest, IdentityLinksPostgresTest, TenantAdministratorsPostgresTest,
InactiveBranchPostgresTest y SessionContextPostgresTest. Una integración nueva,
con JDBC independientes y sesiones/contextos F05 reales, cubre los veinte casos
del encargo, carreras, bloqueo hasta COMMIT, fallos de auditoría, pérdida de
reconocimiento tras COMMIT real y reconciliación. Los probes operativos F08 son
contratos internos sin DML empresarial, no módulos Fiscal/POS implementados.

```powershell
python scripts/pilot/start_connected_pilot.py --core
```

Abrir `https://127.0.0.1:5174/core`, crear/activar A y B, incorporar o vincular con
consentimiento un titular en ambas, crear CASA MATRIZ y SUCURSAL 02 en A. El vacío
de asignaciones inicial es correcto. Para revisar visualmente asignaciones sin
implementar RBAC posterior, ejecutar explícitamente en la consola del piloto:

```text
fixture-access UUID_EMPRESA UUID_MEMBRESIA UUID_SUCURSAL
```

Solo crea una asignación de desarrollo en ese clúster temporal; exige recursos
ACTIVE, no adopta duplicados y no crea perfiles ni permisos. Actualizar el detalle,
suspender/reactivar membresía y acceso individual con confirmación y contraseña.
Comprobar estado de la otra empresa, historial y separación entre sucursal global
y acceso individual. Designaciones vigentes deben retirarse por Administradores.

`--core --smoke-only` recorre automáticamente dos empresas, consentimiento V29,
asignaciones explícitas Core, tres sesiones HTTPS reales del titular y selecciones
F05 mediante tooling local acotado al clúster. Verifica revocación selectiva,
sesiones conservadas, nueva selección tras reactivar y recibos PostgreSQL. El
tooling recibe la cookie por stdin y no la registra; conserva F06/V23 durante la
selección. No agrega endpoints Core de operación empresarial. El modo Core ya no
instala la fixture empresarial Fiscal/POS del piloto Fiscal. Cerrar el piloto
retira procesos, datos y certificados.

Límites: sin servidores compartidos o evidencia de producción; sin roles/perfiles
nuevos, API Keys, módulos empresariales ni creación funcional de asignaciones.
La aprobación visual humana y los resultados finales de CI son revisiones
posteriores; la publicación de la rama no autoriza una fusión.

Evidencia local 2026-11-02: V1–V30 migrate/validate aprobado. Aprobaron las seis
clases focalizadas anteriores, una integración real por clase, sin omisiones;
Checkstyle sin infracciones. React: 48 pruebas correctas y una omisión existente;
ESLint, TypeScript y build aprobados. `--core --smoke-only` aprobado con las cuatro
acciones, tres sesiones Core, contextos F05 reales, recibos y aislamiento, además
de los recorridos anteriores de empresa/administradores/sucursales/configuración.
El piloto comprobó ausencia de las aplicaciones fixture Fiscal/POS. Clústeres,
procesos y certificados retirados. V1–V29 y los tres documentos locales protegidos
conservan sus hashes originales. La aprobación visual humana y el resultado final
de CI no forman parte de esta evidencia local.
