# LFD-49 / F10.T01 — Catálogos RBAC empresariales

Base: `e8dbddf07dabbb3021e3d7daadfac8f8c7cdefc2`, merge #51. V31 es aditiva:
reutiliza las tablas V2/V4 y las relaciones compuestas V6. No modifica V1–V30 ni
duplica permisos, plantillas o seeds V8. No implementa asignación de perfiles a
usuarios/sucursales, unión efectiva de perfiles ni matriz consolidada.

## Dependencia y CI

El CI V30 CANCELLED no se considera SUCCESS. El usuario autorizó continuar tras
validar localmente V30, indicando que las cancelaciones anteriores correspondían
al límite de 20 minutos de migraciones, sin fallo funcional confirmado.
Antes de programar V31 aprobaron las seis integraciones de
`postgres_tenant_access_contract.py` sobre V30, sin omisiones, y se retiró su PG17.

CI conserva íntegramente sus controles y el step V30 con sus seis integraciones.
Solo se cambia el timeout de migrations de 20 a 45 minutos y se agrega el step
V31. Backend/frontend conservan sus timeouts anteriores. La publicación no prueba
CI remoto exitoso: la integración futura exige confirmar 3/3, incluido PG17.
Tras el push solo se consulta una vez el enlace del workflow, sin esperar ni polling.

## Publicación, delegación y autoridad

LS Soluciones mantiene el catálogo global. PUBLISHED significa definición
publicada; no habilita una aplicación, no delega un permiso y no concede autoridad
administrativa. Ni siquiera OWNER puede incorporar permisos fuera del techo
explícito de la empresa/aplicación.

V31 agrega dos registros vacíos, administrados por el operador de plataforma:

- `platform.rbac_permission_delegations`: tenant, aplicación, permiso, clasificación
  READ/WRITE y vencimiento. Es un techo para componer catálogos, no una asignación
  operativa. La clasificación es responsabilidad de la plataforma, nunca del tenant.
- `platform.rbac_administrator_grants`: tenant/aplicación, designación global o
  empresarial exacta, capacidad READ/MANAGE y vencimiento. No procede de roles,
  perfiles ni del permiso publicado `roles.manage`.

Puede administrar OWNER vigente, SUPERADMIN vigente con READ y MANAGE explícitos,
o administrador válido V24 del tenant con esas dos capacidades RBAC específicas.
Los grants se vinculan a la designación y no migran automáticamente a un reemplazo.
La autoridad exige tenant ACTIVE y F04 definitivo vigente del actor. Una cookie
Core, una membresía o una asignación que contenga `roles.manage` no reemplaza este
control. Los tests prueban esa independencia incluso con una asignación explícita
de prueba que incluye ese permiso.

No hay endpoint HTTP para cambiar estos límites. Los instaladores controlados son:

```text
scripts/migrations/prepare_tenant_rbac.sql
scripts/migrations/grant_tenant_rbac_permission.sql
scripts/migrations/grant_tenant_rbac_administrator.sql
```

Requieren PG17 local, principal postgres, instalación explícita `apply=true` y
selectores concretos. READ y MANAGE son concesiones separadas. Las cabeceras de
los scripts enumeran sus variables obligatorias, incluido expires_at. No adoptan
roles existentes, sobrescriben grants ni toleran duplicados silenciosamente.
La plataforma puede revocar/actualizar los registros mediante su canal SQL de
instalación; sus triggers toman las barreras y auditan la modificación atómicamente.
No se entregan credenciales PostgreSQL al navegador.

Un permiso es delegable solo si coinciden simultáneamente:

1. Aplicación PUBLISHED y entitlement empresarial ACTIVE o READ_ONLY.
2. Permiso PUBLISHED de esa misma aplicación y scope TENANT o BRANCH.
3. Delegación explícita no vencida para tenant/aplicación/permiso.
4. Si tiene módulo: módulo PUBLISHED y entitlement de módulo habilitado.
5. Cuando aplicación o módulo está READ_ONLY, clasificación READ. WRITE exige ACTIVE.

Un permiso sin módulo se verifica contra su aplicación; GLOBAL u otros scopes no
se admiten. No se infiere semántica de escritura desde nombres aportados por React.
La fixture V8 clasifica roles.manage, profiles.manage y assignments.manage como
WRITE; los otros seis permisos aprobados son READ. No se crean permisos Fiscal,
DTE, ventas, caja o inventario.

## Roles, perfiles y plantillas

Se administran `security.roles`, `role_permissions`, `profiles` y `profile_roles`.
La composición completa se reemplaza atómicamente; cada permiso/rol se valida
contra tenant, aplicación y delegación vigentes. Un perfil puede contener varios
roles, incluidos scopes TENANT y BRANCH declarados por sus permisos; eso no los
convierte en autoridad operativa. La UI muestra cada rol y su propia lista de
permisos, sin calcular una unión efectiva entre perfiles.

Crear, editar metadatos y composición, deshabilitar y consultar historial requieren
operaciones explícitas. Estados nuevos ACTIVE → DISABLED; no hay reactivación ni
eliminación en esta entrega. Los códigos deshabilitados siguen reservados por V7.
La unicidad conserva exactamente `lower(btrim(code))`. Código máximo 80 caracteres,
nombre 200 y descripción 2000; se rechazan controles y valores obligatorios vacíos.
Composición máxima: 200 permisos por rol y 100 roles por perfil; sin nulos ni duplicados.

Cada rol/perfil incorpora revision, inicialmente 1; toda modificación requiere
expectedRevision exacta e incrementa la revisión. Filas anteriores conservan revisión
0 y, cuando no consta, versión de origen desconocida. V31 no fuerza nuevos CHECK
globales sobre estados heredados: las operaciones rechazan estados incompatibles.

Las plantillas V8 conservan sus UUID, versiones y asociaciones originales:

| Plantilla | Versión | Tipo |
| --- | --- | --- |
| branch_reader | 1 | Rol, dos permisos aprobados |
| access_editor | 1 | Rol, siete permisos aprobados |
| lector_sucursal | 1 | Perfil con branch_reader |
| editor_accesos | 1 | Perfil con access_editor |

La materialización copia a recursos empresariales con UUID nuevo, código
`<codigo_plantilla>_v<version>`, source template UUID y source_template_version.
Inicializar un perfil materializa/reutiliza sus roles en la misma transacción.
Toda la composición debe estar publicada y ser delegable; un solo elemento inválido
revierte todo. Una materialización existente solo se reutiliza si coincide la
versión/composición y sigue ACTIVE. No se adopta una copia histórica sin versión,
no se sobrescriben cambios locales y no se reactiva un recurso deshabilitado.
La evidencia de inicialización de perfil incluye también los snapshots de sus
roles materializados, con versión y permisos; el historial por rol los encuentra.

No hay inicialización al activar una empresa. Cambiar el catálogo o retirar una
delegación no reescribe silenciosamente los recursos históricos. Una composición
antigua puede dejar de ser delegable y no podrá incorporarse a nuevos perfiles
hasta corregir sus requisitos. El techo de delegación no es un motor de revocación
de permisos operativos ya asignados; esa coordinación pertenece al flujo posterior.

## Dependencias y límite LFD-50

Deshabilitar un rol referenciado por un perfil ACTIVE produce conflicto. Primero
se modifica/deshabilita el perfil según corresponda; las relaciones históricas se
conservan. Una asignación de sucursal ACTIVE y no revocada bloquea cualquier
actualización/deshabilitación del perfil y de los roles que contiene, incluidos
cambios de metadatos. Es una restricción conservadora hasta un flujo de asignaciones
con revocación coordinada LFD-50. V31 no toca esas asignaciones ni contextos.

LFD-50 debe resolver asignación/revocación efectiva por sucursal. LFD-51 debe
resolver unión de perfiles y matriz consolidada. Los permisos publicados de esas
funcionalidades no hacen que sus endpoints existan en F10.T01.

## PostgreSQL, auditoría y concurrencia

Canales independientes `ls_core_tenant_rbac_dev` (3 conexiones) y
`ls_core_tenant_rbac_log_dev` (2), sin herencia, propiedad, creación, superusuario,
otros roles ni BYPASSRLS. Mismo servidor/base F04 verificado por configuración.
Funciones SECURITY INVOKER, search_path fijo y EXECUTE público revocado. Las
tablas nuevas y las empresariales conservan FORCE RLS. El catálogo global sigue
siendo de solo lectura para el canal empresarial y para el runtime general.

Solo se insertan comandos en `security.rbac_operations`. RLS, permisos por columna
y triggers de profundidad controlan los cambios subordinados; no hay SQL arbitrario
desde HTTP ni función DEFINER para sortear RLS. El helper de materialización rechaza
llamadas fuera del trigger del comando. La frontera SQL sigue siendo el coordinador
confiable: PostgreSQL no verifica una cookie HTTP ni la contraseña del formulario.
Java deriva al actor de F05 y verifica una nueva contraseña F04; fija la credencial
contra cambios concurrentes y rechaza pruebas de más de diez segundos.

Orden: F06 `(370019,1)` → V23 `(410023,1)` → tenant → identidad/credencial del actor
→ membresías/designaciones ordenadas → roles ordenados → perfiles ordenados →
asociaciones → evidencia. Los escritores conservan los bloqueos hasta COMMIT o
ROLLBACK. Los cambios del catálogo global y de políticas toman las mismas barreras
antes de bloquear filas. Por eso suspensión, revocación y deshabilitación de módulo
no pueden atravesarse con una instantánea antigua. No se concede UPDATE sobre el
catálogo global para bloquearlo: las barreras estabilizan esas lecturas.

RequestId único y recibos inmutables. Repetir con el mismo actor/ámbito/acción e
input original devuelve el recibo sin reejecutar; cambiar el input da conflicto.
El input de reconciliación solo contiene metadatos, UUIDs, revisión y composición,
nunca contraseña. La mutación, asociaciones y evento TENANT_RBAC_* SUCCESS confirman
juntos. Los eventos incluyen actor, tenant, aplicación, recurso, fecha PostgreSQL
y snapshots anteriores/resultantes. Fallar auditoría revierte todo. La instalación
de políticas registra RBAC_POLICY_* con actor INSTALLATION_OPERATOR y antes/después.
Ambas evidencias son inmutables. DENIED/UNKNOWN usan un canal separado sin secretos.

Si se pierde el reconocimiento de COMMIT: 202 UNKNOWN y referencia conservada;
solo GET de reconciliación, sin reintento automático. Un 404 no prueba rollback.
El recibo solo pertenece al actor original y puede leerse tras perder su facultad
RBAC si conserva una sesión Core válida. React guarda únicamente el UUID en
sessionStorage y no aplica cambios optimistas. Un rechazo INVALID confirmado se
distingue de UNKNOWN para no dejar bloqueada una operación que fue rechazada.

## API y frontend

Prefijo `/api/auth/core/rbac/`:

| Método y ruta | Uso |
| --- | --- |
| GET tenants | Empresas de lectura autorizada |
| GET tenants/{tenant}/applications | Aplicaciones publicadas configuradas para esa empresa |
| GET tenants/{tenant}/applications/{app} | Vista conjunta autorizada |
| GET …/{modules,permissions,role-templates,profile-templates,roles,profiles} | Secciones del catálogo empresarial |
| GET …/roles/{id}, …/profiles/{id} | Detalle por recurso del tenant/aplicación |
| GET …/roles/{id}/permissions, …/profiles/{id}/roles | Composición declarada |
| GET …/history?resourceId={id} | Últimas 50 operaciones del ámbito/recurso |
| POST …/commands/{role,profile}-{create,update,disable,template} | Mutación explícita |
| GET operations/{requestId} | Recibo del actor original |

Cuerpo: requestId, resourceId para modificar, expectedRevision, templateId para
inicializar, code/name/description para crear/editar, permissionIds para roles o
roleIds para perfiles, y password. El actor no es un parámetro del cliente.
Listas acotadas a 1000 elementos; un resultado mayor se rechaza, sin truncado
silencioso. El filtro HTTP F05 conserva su límite de cuerpo de 8192 bytes.

200 confirmado; 202 UNKNOWN; 400 entrada inválida; 401 sesión; 403 CSRF/F04,
autoridad o composición no delegable; 404 recurso no encontrado; 409 código,
revisión, estado o dependencia; 422 aplicación no habilitada; 503 canal/auditoría.
Sin SQLSTATE ni diagnósticos SQL. No se usa catalog-docker como autoridad.
Habilitación opt-in `ls.core.tenant-rbac.enabled=true`, Core/F04 reales y variables
LS_TENANT_RBAC_DB_* / LS_TENANT_RBAC_LOG_DB_*.

Menú horizontal Empresas → Roles y perfiles. Selección de empresa y aplicación;
vistas Catálogo de permisos, Roles y Perfiles. Se muestran publicación, scope,
módulo y delegación por separado; origen/versión, composición por rol, revisión,
estado e historial. Toda mutación exige confirmación y contraseña nueva. Los
recursos y selecciones proceden exclusivamente de API/PostgreSQL reales, sin DEMO.

## Validación y recorrido visual

```powershell
$env:JAVA_HOME='C:\Program Files\Java\jdk-21.0.10'
python scripts/tests/postgres_tenant_rbac_contract.py
python scripts/pilot/start_connected_pilot.py --core
```

El harness aplica/valida V1–V31 en PG17 propio, puerto efímero y copias limpias:
TenantRbacPostgresTest, TenantAdministratorsPostgresTest, TenantUsersPostgresTest,
IdentityLinksPostgresTest y TenantAccessPostgresTest. Después instala C1/C2 y ejecuta
las siete pruebas Java de catálogo/configuración sobre una copia limpia de V31.
No contacta servidores compartidos. Incluye las 22 condiciones mínimas, carreras
JDBC, revisiones antiguas, READ_ONLY, auditoría/rollback, pérdida de respuesta tras
COMMIT real, autoridad independiente, asignaciones existentes protegidas y ausencia
de asignaciones automáticas. Las asignaciones negativas del test son fixtures
explícitas y temporales, no endpoints nuevos.

Piloto visual en `https://127.0.0.1:5174/core`:

1. Entrar con el OWNER mostrado por el lanzador y crear/activar una empresa mediante
   el flujo existente. No habrá roles, perfiles ni facultades RBAC delegadas por activarla.
2. Ejecutar explícitamente `fixture-rbac UUID_EMPRESA` en la consola del piloto.
   Solo en su clúster desechable, instala los tres módulos Core y el techo de los
   nueve permisos V8; concede READ/MANAGE a administradores válidos seleccionados
   por PostgreSQL, con vencimiento de dos horas. Usa los scripts de instalación
   anteriores y no crea roles/perfiles/asignaciones. Rechaza duplicados y estados
   incompatibles, sin sobrescribirlos.
3. Abrir Roles y perfiles, seleccionar/reseleccionar empresa y aplicación LS Core.
   Verificar PUBLISHED separado de Delegable e inicializar las plantillas permitidas.
4. Crear rol personalizado y seleccionar permisos disponibles. Crear un perfil con
   varios roles y comprobar cada composición declarada, origen, revisión e historial.
5. Probar conflicto de código y dependencias antes de deshabilitar. Confirmar que
   no aparecen asignaciones de perfiles a usuarios o sucursales.
6. Cerrar con `salir`: se retiran procesos, clúster, datos y certificados.

`--core --smoke-only` recorre estos contratos por HTTPS, comprueba relaciones SQL,
recibos y ausencia de asignaciones automáticas; deshabilita temporalmente un módulo
del clúster para probar rechazo y restaura su estado. También transforma el nuevo
componente con Vite HTTPS. La aprobación visual humana es posterior a esta evidencia
técnica; el smoke no la sustituye.

Límites: serialización administrativa global conservadora, sin cambios efectivos
en composiciones ya asignadas, sin publicación/edición global desde tenant, sin
asignaciones LFD-50, sin unión/matriz LFD-51 y sin evidencia de producción.

Evidencia local del 2026-11-02: precondición V30 aprobada con sus seis integraciones
antes de desarrollar. V1–V31 migrate/validate aprobado; integración nueva RBAC y
cuatro regresiones F07/F09 aprobadas, una prueba real por clase sin omisiones, más
siete pruebas de catálogo/configuración C3/C4 sobre V31. Checkstyle sin infracciones.
React: 52 pruebas correctas y una omisión existente; ESLint y TypeScript aprobados;
build aprobado también con la entrada Core habilitada. Piloto HTTPS completo
aprobado con los instaladores de política, plantillas V8, rol personalizado, perfil
de dos roles, persistencia, módulo deshabilitado y ausencia de asignaciones
automáticas. Clústeres, procesos y certificados retirados. V1–V30 y los tres
documentos locales protegidos conservan sus hashes originales. Revisión visual
humana y resultados remotos finales de CI quedan pendientes.
