# LFD-43 / F08.T01 — Administración de sucursales

Base verificada: `origin/main`, merge PR #45
`02a64e5318cea29f49ebc817863cff3dc784e88f`, con V24 integrada.
V25 amplía el portal ConnectedCore y reutiliza F04/F05/F06/F07. V1–V24 no se editan.

## Modelo y reglas

Se reutiliza `tenant.branches`: UUID, tenant propietario, código, nombre, estado,
creación y actualización. No hay borrado físico, datos especializados de módulos
ni creación automática de membresías, permisos o aplicaciones.

| Acción | Estado requerido | Estado confirmado |
|---|---|---|
| Crear | Tenant existente | ACTIVE |
| Editar datos | ACTIVE o SUSPENDED | Sin cambio de estado |
| Suspender | ACTIVE | SUSPENDED |
| Reactivar | SUSPENDED | ACTIVE |
| Cerrar | ACTIVE o SUSPENDED | CLOSED |

CLOSED es terminal, incluso para OWNER: no permite editar, suspender, reactivar
ni cerrar otra vez. La fila y su código permanecen reservados. No existe una
excepción para reapertura ni una operación de eliminación.

Crear en un tenant PENDING es preparación administrativa global: una sucursal
ACTIVE allí no concede acceso operativo. F05 exige también tenant ACTIVE,
membresía, acceso de sucursal y aplicación autorizados. En un tenant SUSPENDED
solo se admite soporte global expresamente autorizado.

El código nuevo o reemplazado se recorta con `btrim`, tiene 1–40 caracteres ASCII,
empieza por letra o número y admite después letras, números, espacios, punto,
guion y guion bajo. Ejemplos: `CASA MATRIZ`, `SUCURSAL 02`, `SUC-03`.
El nombre se recorta, es obligatorio, tiene 1–200 caracteres y no admite controles.

Se conserva exclusivamente `branches_tenant_code_normalized_uq` de V7:
`tenant_id + lower(btrim(code))`. Un código se puede repetir en otra empresa,
pero no dentro de la misma, aunque cambien mayúsculas o espacios exteriores.
El índice no excluye CLOSED; sus códigos no se reutilizan automáticamente.

El cambio explícito de código solo se permite si no hay referencias de
`branch_memberships`, `session_contexts` ni `api_keys`, incluso históricas.
En otro caso se devuelve 409 y el código no se modifica. El nombre puede editarse
sin cambiar el código. Los códigos históricos que se envían literalmente sin
cambios se conservan, aunque no satisfagan el formato de altas nuevas.
Una futura renumeración de códigos referenciados necesita una decisión y flujo
específicos; esta unidad no la simula ni elimina referencias para permitirla.

## Autorización y sesiones

La API acepta exclusivamente la sesión Core independiente. El actor procede de
su registro persistido, nunca de `actorId` ni de otro UUID del cuerpo HTTP.
Los identificadores de ruta seleccionan recursos; no son prueba de autorización.

- OWNER vigente con elegibilidad F04 puede consultar y gestionar.
- SUPERADMIN requiere la facultad específica READ de
  `platform.branch_administration_grants`; cada mutación requiere además MANAGE.
  Las facultades de empresas o administradores de V22–V24 no la sustituyen.
- Un administrador empresarial válido de V24 solo administra su tenant ACTIVE:
  identidad, credencial F04, membresía y designación vigentes y coherentes.
  No recibe facultades globales ni acceso a otra empresa.

La distinción global/empresarial se evalúa en cada solicitud. El soporte de un
tenant PENDING o SUSPENDED requiere la autoridad global anterior. La pertenencia
a un tenant suspendido no habilita administración empresarial normal.

Cada mutación necesita CSRF y una nueva contraseña F04 del actor identificado
por el servidor. Se comprueba con el verificador real, sin almacenar el secreto.
La admisión requiere una prueba reciente (máximo diez segundos) y PostgreSQL
vuelve a comprobar autoridad y elegibilidad con sus bloqueos adquiridos.
Las cookies Fiscal/POS no autentican estas rutas Core.

El recibo de reconciliación tiene autorización propia y acotada: sesión Core
vigente y actor original del comando. Puede consultar solo ese recibo después
de perder facultades; no recupera acceso al tenant o a nuevas mutaciones.

## V25, privilegios y RLS

V25 añade únicamente facultades delegadas, registro de comandos, funciones
invoker, políticas y guardas de la administración. Valida con una restricción
CHECK que todas las sucursales existentes tengan ACTIVE/SUSPENDED/CLOSED.
Un estado histórico incompatible hace fallar la migración transaccionalmente;
no se convierte ni corrige silenciosamente. Los códigos y nombres históricos
no se reescriben masivamente para adaptarlos al formato nuevo.

`scripts/migrations/prepare_branch_administration.sql` aprovisiona roles frescos
`ls_core_branch_admin_dev` (tres conexiones) y `ls_core_branch_admin_log_dev`
(dos), con SCRAM y sin adoptar roles existentes. Se ejecuta explícitamente antes
de V25 en la instalación controlada, sin semillas de empresas o contraseñas.

No tienen propiedad, herencia, membresías SQL, BYPASSRLS, superusuario, CREATE,
TEMP, DDL, DELETE ni TRUNCATE. El canal de rechazo solo puede insertar evidencia
DENIED/UNKNOWN. Todas las funciones son SECURITY INVOKER con
`search_path=pg_catalog`; se revoca EXECUTE público. Se conserva FORCE RLS en las
tablas existentes y se exige en las nuevas.

El backend inserta comandos parametrizados en `tenant.branch_operations`.
Los triggers validan, modifican las tablas hijas y auditan. ACL por columnas,
RLS y profundidad de trigger impiden escrituras directas incluso desde el rol
funcional de ejecución. Los permisos UPDATE mínimos sobre identidad, credencial
y tenant permiten bloquear filas; `WITH CHECK(false)` prohíbe cambiarlas.
No se concede escritura sobre membresías, usuarios, credenciales o API Keys.
La lectura mínima de referencias de API Keys protege códigos históricos y no
supone que exista un canal operativo de API Keys.

Activación explícita: `ls.core.branches.enabled=true`, Core y F04 reales habilitados,
con `LS_BRANCH_ADMIN_DB_{HOST,PORT,NAME,PASSWORD}` y
`LS_BRANCH_ADMIN_LOG_DB_{HOST,PORT,NAME,PASSWORD}`. Los endpoints PostgreSQL deben
coincidir con F04. Los roles son fijos, no seleccionables mediante HTTP.

Las facultades se instalan con `scripts/migrations/grant_branch_administration.sql`,
indicando una designación SUPERADMIN existente y READ/MANAGE. La instalación
controlada registra `BRANCH_ADMINISTRATION_CAPABILITY_INSTALLED`; no hay un
endpoint de concesión global ni concesión automática a todos los superadministradores.

## Concurrencia y frontera operativa mínima

Orden de escritura compatible con F06/F07:

1. Bloqueo F06 `(370019,1)`.
2. Barrera V23 `(410023,1)`, exclusiva para mutaciones.
3. Fila del tenant `FOR UPDATE`.
4. Identidad y credencial del actor `FOR SHARE`, en orden F04.
5. Fila de sucursal `FOR UPDATE`, si ya existe.
6. Revalidación, escritura, revocación de contextos, auditoría y COMMIT.

Las consultas toman ambas barreras en modo compartido y fijan la elegibilidad
del actor. Se usa READ COMMITTED. No se añaden bloqueos globales distintos de los
exigidos por el protocolo vigente; sus escritores siguen serializados entre tenants.

Toda mutación de una sucursal existente exige `expectedUpdatedAt`, obtenido de
la ficha persistida. PostgreSQL lo compara con la fila bloqueada. Dos cambios
sobre la misma versión no pueden sobrescribirse: uno confirma y el otro recibe
409. Dos altas del mismo código normalizado dependen del índice V7: solo una confirma.
El requestId es único; repetirlo no reaplica efectos.

La suspensión y el cierre revocan únicamente contextos no revocados de
`tenant_id + branch_id`. Se conservan sesiones globales, otras sucursales y otros
tenants. Reactivar no recupera contextos: el usuario debe seleccionar de nuevo
un contexto autorizado. La edición de nombre no revoca contextos ni modifica permisos.

Se revisaron `SessionContextController`, `JdbcSessionContexts`, `TenantContextPolicy`
y `BranchReadCoordinator`. F05 ya exige branch ACTIVE y toma la barrera compartida
antes del snapshot de sesión, manteniéndola hasta COMMIT. El coordinador de lectura
también mantiene esa barrera y reevalúa F04/V14 después de adquirirla.
V25 refuerza la emisión SQL de contextos con la comprobación de sucursal ACTIVE
y añade una política restrictiva de estado al lector empresarial existente.
Por tanto, una suspensión confirmada drena las lecturas ya admitidas y rechaza
nuevas selecciones y lecturas del canal implementado. No se afirma cubrir canales
de negocio futuros, cursores arbitrarios o API Keys.

## Auditoría y reconciliación

Cada éxito persiste requestId, actor, tenant, sucursal, acción, datos y estado
anteriores/nuevos, versión esperada y fechas PostgreSQL. El trigger inserta
`BRANCH_CREATE`, `BRANCH_EDIT`, `BRANCH_SUSPEND`, `BRANCH_REACTIVATE` o `BRANCH_CLOSE`
en `audit.events` en la misma transacción. El tenant queda registrado en metadata
y el recurso es la sucursal. No se confía en estados anteriores enviados por el cliente.

La evidencia exitosa se valida contra el comando persistido, es inmutable y no
puede fabricarse con el canal de rechazo. El registro de comandos rechaza
UPDATE/DELETE/TRUNCATE y el guard V21 conserva la protección de auditoría frente
a TRUNCATE. Si falla la auditoría, se revierte la escritura y la revocación de contextos.
Las solicitudes identificadas rechazadas o inciertas se registran cuando el canal
se encuentra disponible. No se prometen eventos para cookies/CSRF rechazados antes
de identificar una sesión. No se registran contraseñas, cookies, tokens o secretos.

CONFIRMED se devuelve después del COMMIT. Ante incertidumbre durante COMMIT se
devuelve UNKNOWN con referencia y sin inventar una sucursal o estado final.
La consulta del recibo original no repite el comando. Un 404 temporal de recibo no
autoriza un reintento automático. Los historiales anteriores a V25 no se reconstruyen.

## API

Prefijo `/api/auth/core/branch-administration`, separado de las rutas operativas
`/api/auth/{application}/tenants/{tenant}/branches` de `SessionContextController`.

| Método | Ruta relativa | Uso |
|---|---|---|
| GET | `tenants` | Selector de empresas autorizadas, hasta 1000; falla explícitamente si se excede |
| GET | `tenants/{tenant}/branches?page=0` | Páginas de 50, hasMore y facultad de gestión |
| GET | `tenants/{tenant}/branches/{branch}` | Ficha, versión, código editable y últimos 50 cambios |
| POST | `tenants/{tenant}/branches` | Alta con `{requestId,code,name,password}` |
| POST | `tenants/{tenant}/branches/{branch}/edit` | `{requestId,code,name,expectedUpdatedAt,password}` |
| POST | `tenants/{tenant}/branches/{branch}/suspend` | `{requestId,expectedUpdatedAt,password}` |
| POST | `tenants/{tenant}/branches/{branch}/reactivate` | Mismo contrato de confirmación y versión |
| POST | `tenants/{tenant}/branches/{branch}/close` | Cierre terminal con confirmación y versión |
| GET | `operations/{requestId}` | Recibo persistido del actor original |

201 para alta confirmada; 200 para consultas y cambios confirmados; 202 UNKNOWN;
400 petición mal formada; 401 sesión inválida; 403 CSRF, autoridad o contraseña;
404 recurso no disponible; 409 código ocupado/protegido, requestId repetido,
versión obsoleta o transición inválida; 422 datos incompletos; 503 indisponibilidad.
Las respuestas no incluyen diagnósticos SQL. El recibo contiene una instantánea
confirmada; la ficha vuelve a consultar los datos actuales, que podrían haber
cambiado mediante otra operación posterior.

## React y piloto

ConnectedCore incorpora `Empresas / Sucursales`. El selector usa las empresas
autorizadas del servidor, el listado es paginado y la ficha muestra datos,
fechas, estados e historial. Solo ofrece acciones acordes al estado y autoridad
devueltos por la API. El código referenciado aparece de solo lectura.
Todas las mutaciones tienen contraseña nueva y confirmación explícita.

Se bloquean dobles envíos; se limpia el campo de contraseña antes de enviar.
Los datos se recargan después de CONFIRMED. Si falla esa relectura se distingue
una operación ya confirmada de una mutación incierta. Un resultado estructurado
UNAVAILABLE corresponde al rechazo anterior a COMMIT y permite un nuevo intento
manual después de revalidar; un error de transporte o proxy no ofrece esa garantía.
UNKNOWN conserva únicamente
un UUID opaco en sessionStorage y bloquea nuevas mutaciones críticas entre las
secciones Core hasta reconciliar. Se mantienen logo, navegación horizontal,
componentes y tablas responsive; no se usan datos DEMO.

Con JDK 21 en `JAVA_HOME` y `npm ci` realizado en frontend:

```powershell
python scripts/pilot/start_connected_pilot.py --core
```

1. Elegir una contraseña desechable y abrir la URL `/core` mostrada.
2. Entrar como OWNER indicado, crear una empresa con el inicial disponible y activarla.
3. Ir a Empresas / Sucursales y elegir esa empresa.
4. Crear código/nombre `CASA MATRIZ` y luego `SUCURSAL 02`.
5. Abrir la ficha de la segunda, suspender, reactivar y cerrar, confirmando cada vez.
6. Revalidar las fichas y consultar historial: la matriz permanece ACTIVE y la segunda CLOSED.
7. Ctrl+C detiene procesos y elimina certificados y PG17 propios.

`--core --smoke-only` recorre el flujo por HTTPS real, consulta recibos y verifica
directamente en PostgreSQL el tenant propietario de ambas sucursales. El piloto
usa un cluster propio con puerto efímero, certificados desechables y limpieza
automática. No acepta conexiones a PostgreSQL compartido o productivo.

## Pruebas y límites siguientes

`python scripts/tests/postgres_branch_administration_contract.py` ejecuta una sola
integración JUnit focalizada en PG17 desechable con F04 y HTTPS reales. Cubre
preparación PENDING, alta, lectura, edición, unicidad normalizada por tenant,
transiciones y cierre terminal, autorización global/empresarial, contraseña/CSRF,
revocación selectiva y rechazo de selección/lectura posterior, tres carreras con
conexiones JDBC físicas distintas, auditoría y rollback, COMMIT incierto,
reconciliación, idempotencia, FORCE RLS y DML directo rechazado.

Las integraciones focalizadas LFD-40/LFD-41/LFD-42 se ejecutan ahora sobre V25.
La prueba React recorre altas/edición/estados, código protegido, confirmación,
conflicto, autoridad, UNKNOWN y el acceso delegado específico de sucursales.
CI conserva los tres jobs estándar y añade la integración al job de migraciones.

LFD-44 queda a cargo de datos especializados por módulo. LFD-45 debe extender la
protección transversal a los futuros canales de negocio y sus límites de admisión;
esta entrega cubre únicamente los canales de contexto y lectura ya implementados.
API Keys siguen sin implementación operativa. No se otorgan ni revocan llaves,
permisos, roles, membresías o sesiones globales al administrar sucursales.
