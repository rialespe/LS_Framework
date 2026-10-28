# LFD-41 / F07.T02: ciclo de vida de empresas

Implementación sobre `origin/main` b188c9f, con LFD-40 y V22 integradas. V1–V22 permanecen intactas. No hay eliminación de empresas ni administración general de miembros o administradores F07.T03.

## Estados y preparación administrativa

| Acción | Estado requerido | Estado confirmado |
|---|---|---|
| ACTIVATE | PENDING | ACTIVE |
| SUSPEND | ACTIVE | SUSPENDED |
| REACTIVATE | SUSPENDED | ACTIVE |

El alta LFD-40 sigue creando empresa, membresía y designación PENDING. La activación exige el registro inicial persistido, la misma membresía y designación no revocada en PENDING, identidad ACTIVE y credenciales F04 definitivas: cambio realizado, vigencia futura, sin cambio obligatorio ni bloqueo vigente. Una identidad PENDING debe completar primero emisión temporal y cambio obligatorio F04; el ciclo de vida no crea ni entrega contraseñas. No se leen hashes desde el canal de ciclo de vida.

La activación cambia **en una sola transacción** la membresía a ACTIVE (registra `accepted_at`), la designación inicial a ACTIVE y la empresa a ACTIVE. No cambia identidades ni credenciales. La reactivación exige esos mismos vínculos administrativos ya ACTIVE. Una designación adicional no revocada fuera del registro inicial es una configuración incompatible para estas operaciones: debe resolverse mediante el futuro contrato F07.T03, no se adopta silenciosamente. Empresas anteriores sin registro inicial pueden consultarse y suspenderse, pero no activarse/reactivarse sin ese contrato administrativo.

No se crean sucursales, aplicaciones, módulos, perfiles ni permisos predeterminados. ACTIVE hace al tenant administrativamente elegible; el acceso operativo sigue exigiendo todas las relaciones F03/F05, incluidas aplicación, sucursal, membresías y permisos válidos.

## Autorización y reautenticación

Se reutilizan las cookies Core independientes de V22, vinculadas a la aplicación Core configurada por servidor, F04 y F06. El actor se obtiene únicamente de la sesión persistida validada. Un UUID del cuerpo solo selecciona un recurso, nunca prueba identidad o autoridad. Cookies Fiscal/POS, membresías y permisos empresariales no conceden autoridad global.

OWNER vigente tiene estas facultades. SUPERADMIN requiere concesiones **explícitas por designación** en `platform.tenant_lifecycle_grants`: READ y cada acción ACTIVATE/SUSPEND/REACTIVATE por separado. Tener alta LFD-40 no concede ciclo de vida. Una nueva designación no hereda facultades de una revocada. No existe una API HTTP mutable para concederlas.

El operador de instalación local protegido puede conceder una facultad concreta, sin duplicados ni reparación:

```powershell
psql -X -v apply=true -v designation_id=<designacion-SUPERADMIN> -v capability=READ -f scripts/migrations/grant_tenant_lifecycle.sql
```

Repetir únicamente para cada facultad expresamente aprobada. El script exige `postgres`, loopback, PG17 y `ls_core_dev`, toma el bloqueo global F06 y registra evidencia INSTALLATION_OPERATOR; no suplanta a un humano reautenticado. No usarlo contra bases compartidas durante pruebas.

Cada POST crítico exige CSRF y una **nueva** contraseña definitiva del usuario de la sesión, verificada por F04 real. Se vuelve a validar autoridad después del bloqueo global `(370019,1)`, con límite de diez segundos desde el inicio de verificación. La autoridad no se conserva como capacidad reutilizable. El trigger SQL refuerza autoridad y transición bajo bloqueo de fila de `tenant.tenants`.

## Transacciones, concurrencia y auditoría

V23 agrega solo concesiones específicas y un pequeño registro inmutable de operaciones con referencia única, actor, tenant, acción y estados anterior/resultante. Ambas tablas tienen FORCE RLS. Los cambios se ejecutan mediante triggers SECURITY INVOKER, sin GUC de autoridad, SECURITY DEFINER, BYPASSRLS ni DDL funcional.

Orden: bloqueo global F06, barrera exclusiva `(410023,1)`, fila tenant `FOR UPDATE`, verificación administrativa y bloqueo compartido de las filas de identidad, credenciales, membresía y designación. Los ACL de UPDATE mínimos sobre identidad/credenciales permiten únicamente tomar bloqueos de fila; las políticas `WITH CHECK(false)` impiden almacenar cambios. Los hijos tenant/membresía/designación/contextos solo pueden actualizarse por el escritor desde el trigger de una operación. Duplicados y transiciones inválidas se rechazan; no se repiten efectos ni se adopta otra solicitud.

El trigger inserta SUCCESS en `audit.events` con actor, recurso tenant, acción TENANT_ACTIVATE/TENANT_SUSPEND/TENANT_REACTIVATE, correlación y estados anterior/resultante. La auditoría solo acepta SUCCESS respaldado por el registro y la profundidad de trigger correcta. Es inmutable; V21 mantiene la prohibición de truncar auditoría. Si falla el evento, revierten estado, membresía/designación y revocaciones. Los rechazos DENIED/UNKNOWN usan otra conexión restringida, metadata vacía, sin contraseñas, tokens, hashes, nombres o SQL. Como en V21/V22, una caída entre rollback y evidencia independiente puede impedir registrar el rechazo; entonces el servicio devuelve UNAVAILABLE.

CONFIRMED se emite después del COMMIT reconocido. Un fallo posterior de actividad de la sesión no transforma un cambio confirmado en rechazo. Si el COMMIT es incierto se devuelve UNKNOWN con referencia, sin ficha o estado final supuesto. No hay reintento automático. GET de reconciliación toma los mismos bloqueos, exige READ vigente y solo recupera operaciones del actor actual. Un 404 no demuestra rollback ni autoriza repetir.

## Suspensión y frontera de acceso

La suspensión actualiza `security.session_contexts.revoked_at` únicamente para ese tenant, en la misma transacción. Conserva identidades, membresías, administradores, auditoría, sesiones globales y contextos de otras empresas. No agrega revocaciones permanentes de sesión/tenant: después de reactivar, el usuario puede seleccionar un **nuevo** contexto si sigue autorizado. Los contextos anteriores nunca se restauran.

Las operaciones Fiscal/POS de sesión toman una barrera compartida antes de crear el snapshot REPEATABLE READ; la conexión física propia la libera al cerrarse. La suspensión espera su terminación antes de confirmar. Así no puede terminar una selección autorizada con un snapshot anterior después de una suspensión confirmada. El trigger SQL de emisión también coordina el INSERT con la barrera y exige tenant ACTIVE. Las consultas normales de contexto conservan su validación de estados.

El lector empresarial interno existente de una sucursal toma la misma barrera durante su transacci?n acotada READ COMMITTED y verifica F04 y tenant ACTIVE despu?s de adquirirla. V23 a?ade acceso m?nimo a estado tenant y una pol?tica restrictiva sobre sucursales. Una suspensi?n confirmada impide admitir otra lectura y espera que terminen las lecturas acotadas anteriores. No hay cursores, streaming ni SQL arbitrario en este canal.

La confirmación espera las operaciones acotadas participantes, con timeouts; si no puede adquirir bloqueos, no afirma suspensión confirmada. No se afirma drenaje universal de futuros módulos ni canales SQL privilegiados externos. La ejecución empresarial HTTP por cookies y por API Keys sigue deshabilitada. No existe implementación operativa de API Keys que revocar: no se afirma su revocación ni se habilitan automáticamente al reactivar. Los futuros canales deberán participar en la barrera, revalidar tenant ACTIVE y respetar las revocaciones persistidas.

## API

Habilitación explícita: `ls.login.core.enabled=true`, F04/F05 reales y `ls.core.lifecycle.enabled=true`; el portal reutiliza también `ls.core.registration.enabled=true`. Configurar `LS_TENANT_LIFECYCLE_DB_*` y `LS_TENANT_LIFECYCLE_LOG_DB_*` con HOST/PORT/NAME/PASSWORD al mismo destino confiable F04. Roles fijos: `ls_core_tenant_lifecycle_dev` (3 conexiones) y `ls_core_tenant_lifecycle_log_dev` (2). Prepararlos antes de V23 con `scripts/migrations/prepare_tenant_lifecycle.sql`, mediante el operador local y secretos SCRAM. No hay adopción de roles existentes.

Todas estas rutas exigen sesión Core y autoridad global vigente:

| Método | Ruta bajo `/api/auth/core/` | Resultado |
|---|---|---|
| GET | `tenant-authority` | kind, canRead/canActivate/canSuspend/canReactivate |
| GET | `tenants?page=0` | tenants, hasMore; páginas de 50, orden fecha/UUID |
| GET | `tenants/{id}` | tenant persistido y últimos 50 cambios confirmados |
| POST | `tenants/{id}/activate` | requestId, password nueva |
| POST | `tenants/{id}/suspend` | requestId, password nueva |
| POST | `tenants/{id}/reactivate` | requestId, password nueva |
| GET | `tenant-operations/{requestId}` | reconciliación de una operación propia |

Las mutaciones devuelven `{outcome,correlation,reason,change}`: CONFIRMED/200, UNKNOWN/202, DENIED/403, INVALID_TRANSITION/409, INCOMPLETE_REQUIREMENTS/422, UNAVAILABLE/503. `PREPARE_INITIAL_ADMINISTRATOR` permite corregir requisitos mediante F04 y el vínculo administrativo inicial. Solo CONFIRMED incluye un cambio. La ficha actual debe consultarse después: otra operación concurrente puede haber cambiado nuevamente el estado. Sin sesión: 401; CSRF inválido: 403; cuerpo inválido: 400. No se revelan excepciones SQL o secretos.

## React y piloto

`ConnectedCore` incorpora **Empresas → Administración de empresas**, listado paginado, ficha persistida, administrador inicial, estados visuales, historial y confirmación de contraseña para cada acción permitida. Conserva marca y menú horizontal, separa Core de Fiscal/DEMO y mejora el ancho, formularios, fichas y tablas responsive. No usa datos demo para mutaciones. Actualiza después de CONFIRMED; UNKNOWN muestra una referencia y bloquea nuevos cambios hasta reconciliar. Solo esa referencia opaca se conserva en sessionStorage; nunca tokens o passwords. No se implementa F13.

```powershell
$env:JAVA_HOME='C:\Program Files\Java\jdk-21.0.10'
$env:PATH="$env:JAVA_HOME\bin;$env:PATH"
python scripts/pilot/start_connected_pilot.py --core
```

Elegir contraseña local. El launcher muestra OWNER y administrador inicial separados de la cuenta Fiscal; prepara al administrador con F04 real únicamente en el cluster propio. Entrar en `https://127.0.0.1:5174/core`, crear una empresa PENDING, abrir Administración de empresas y su ficha, activar, suspender y reactivar, introduciendo nuevamente la contraseña OWNER en cada paso. Actualizar ficha para comprobar los estados e historial persistidos en PostgreSQL. No se crean sucursales o permisos operativos para esa empresa. Ctrl+C elimina procesos, CA propia y PG17 temporal. Nunca acepta una base compartida.

Comprobación de HTTP/proxy y persistencia del ciclo completo, con limpieza automática:

```powershell
python scripts/pilot/start_connected_pilot.py --core --smoke-only
```

El smoke valida TLS contra su CA explícita sin instalarla en Root de Windows. El modo visual conserva la confianza temporal para el navegador. Se exporta solo la identidad TLS del servidor, evitando construcción de cadenas externa; se registran las huellas propias para retirar también las claves temporales si falla la preparación.

## Validación y límites F07.T03 / LFD-42

Una integración nueva `TenantLifecyclePostgresTest` usa HTTPS/F04 y PostgreSQL 17 desechable: alta LFD-40, preparación inválida, activación, suspensión, rechazo empresarial, conservación de otro tenant, reactivación sin restaurar contextos, cookies ajenas, autoridad global explícita, transiciones inválidas, dos conexiones escritoras concurrentes, auditoría, rollback de auditoría y FORCE RLS. Verifica también el lector empresarial interno habilitado solo dentro del fixture. El harness no invoca manualmente pruebas históricas. El CI estándar de tres jobs incorpora esta integración a su secuencia habitual.

```powershell
python scripts/tests/postgres_tenant_lifecycle_contract.py
```

Una prueba React focalizada verifica listado, ficha, contraseña/CSRF, estado anterior durante una mutación pendiente y UNKNOWN/reconciliación. Se conserva la prueba de alta. Validaciones adicionales: Checkstyle, lint, TypeScript y build.

Evidencia local: la integración focalizada terminó con un método, cero fallos/errores/omisiones y Checkstyle correcto. Las dos pruebas React de ciclo de vida y alta, lint, TypeScript y build pasaron. `--core --smoke-only` alcanzó PILOT READY, creó PENDING por HTTPS, confirmó ACTIVE → SUSPENDED → ACTIVE, consultó cada estado y referencia persistidos, cerró sesión y retiró procesos, certificados y cluster. Es evidencia real de HTTP/proxy y PostgreSQL; no se afirma una aprobación visual por recorrido automatizado de clics.

El CI identificó una regresión de reutilización física del lector al reservar una conexión antes de rechazar un selector ajeno. Se conserva la comprobación F04 previa a reservar el pool y se exige una nueva comprobación dentro de la barrera. Pasaron nuevamente la integración focalizada y, de forma específica para esa regresión, el único método de `BranchOperationPostgresTest`; no se repitió manualmente toda la suite histórica.

LFD-42 deberá administrar y proteger el conjunto de administradores empresariales, altas/bajas/reemplazos concurrentes y su continuidad; esta entrega solo prepara la designación inicial LFD-40. No hay borrado de empresas, administración general de miembros, recuperación automática de contextos/API Keys, nuevos módulos de negocio ni cambios en Jira. Toda evidencia local corresponde a clusters propios desechables, no a servidores compartidos o producción.
