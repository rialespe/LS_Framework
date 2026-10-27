# Selector de contextos y permisos — LFD-35 / F05.T02

## Activación y contrato HTTP

Requiere la activación HTTPS de [LFD-34](login-lfd-34.md), sus cookies y F04.
Aplicar V17 con el migrador restringido después de V16 y activar explícitamente
`ls.login.contexts.enabled=true`. No se crea otro rol, autenticador ni cookie.
El selector permanece deshabilitado por defecto.

Los endpoints bajo `/api/auth/{application}` (`fiscal` o `pos`) son:

| Método y ruta | Respuesta |
| --- | --- |
| GET `/tenants` | Empresas con alguna sucursal accesible para la sesión/aplicación |
| GET `/tenants/{tenantId}/branches` | Sucursales vigentes de esa empresa |
| GET `/context` | Contexto seleccionado, permisos y módulos; 204 si no hay selección |
| POST `/context` | Selección con JSON `tenantId`, `branchId`; devuelve el contexto recalculado |
| GET `/permissions` | Permisos efectivos de la selección actual |
| GET `/navigation` | Módulos visibles con identificador, código, nombre y permisos aplicables |

Todos requieren sesión vigente. El POST también exige el token CSRF obtenido mediante
el GET `/csrf` de V16. Una sesión inválida responde 401; una selección inaccesible,
revocada o ausente para permisos/navegación responde 403 con mensaje neutro.
Un UUID mal formado responde 400. No se reciben usuario, aplicación UUID, membresía
ni sesión en el cuerpo. Las respuestas no incluyen tokens, identidad del usuario ni
credenciales de ejecución.

## Selección y revalidación

`JdbcApplicationSessions` resuelve el digest de la cookie, propietario y aplicación
desde PostgreSQL, comprobando expiración, revocación, usuario ACTIVE y aplicación
PUBLISHED. Un callback interno comparte esa transacción con el selector; no publica
una identidad reutilizable. Un token emitido para POS no sirve en Fiscal aunque se
renombre su cookie.

`JdbcTenantAuthorization` aporta el grafo común de relaciones; ambos lectores reutilizan
la política de estados `TenantContextPolicy`, sin ampliar los métodos públicos del autenticador:
tenant, membresía, sucursal, acceso a sucursal y habilitación tenant/aplicación ACTIVE;
usuario ACTIVE y aplicación PUBLISHED. `accepted_at` no es condición adicional.
READ_ONLY y cualquier estado no habilitado se deniegan.

Cada petición usa un snapshot REPEATABLE READ. Se bloquea la fila de la sesión antes
de trabajar; la selección revoca el contexto anterior e inserta el nuevo en la misma
transacción. Un índice único parcial sobre `session_id` con `revoked_at IS NULL`
impide dos selecciones activas incluso ante escritores concurrentes. Un conflicto de
serialización/unicidad se deniega sin reintento automático. La confirmación y cierre
de conexión preceden a la respuesta positiva.

El contexto conserva sesión, propietario, aplicación, tenant, membresía, sucursal y
expiración no superior a la sesión. Las FK compuestas existentes preservan coherencia.
Cada consulta posterior vuelve a comprobar sesión, contexto y relaciones vigentes;
no utiliza permisos almacenados en memoria o en la selección anterior.
`TrustedContextSource` y `ContextResolver` validan las relaciones dentro del snapshot;
su evidencia y resultado no salen de esa transacción ni se registran como beans.
Una revocación confirmada antes de una nueva petición se observa en ella. No se promete
cancelación de operaciones que ya leían un snapshot anterior.

## Permisos y navegación

La consulta recorre exclusivamente asignaciones de la membresía de sucursal elegida,
perfiles, asociaciones perfil/rol, roles, asociaciones rol/permiso y catálogo de permisos.
Une los permisos de todos los perfiles activos y elimina duplicados. Asignaciones
revocadas/no ACTIVE, perfiles/roles no ACTIVE y permisos no PUBLISHED se excluyen.
No se recorre ninguna plantilla global para conceder permisos.

Solo se reconocen `TENANT` y `BRANCH`, los ámbitos documentados en el catálogo actual;
otros ámbitos se deniegan. Todas las relaciones se vinculan por tenant y aplicación.
Un permiso TENANT sigue siendo información del tenant seleccionado y de la asignación
en esa sucursal: no se propaga automáticamente a otra sucursal. El modelo admite
`module_id` nulo; ese permiso no crea un módulo de navegación.

Para un permiso asociado a módulo se exige módulo existente de la misma aplicación,
PUBLISHED y habilitación tenant/módulo ACTIVE. READ_ONLY y DISABLED no conceden
capacidades en esta unidad. La navegación contiene solo módulos con al menos un
permiso efectivo. No existe una marca aprobada para módulos de navegación libres de
permisos: no se infiere esa excepción, ni rutas, orden u ocultaciones por tenant.
Un contexto válido sin perfiles efectivos devuelve permisos y navegación vacíos.

## Privilegios y límites

V1–V16 no cambian. `V17__session_context_selection.sql` conserva FORCE RLS y amplía
`ls_core_session_manager_dev` con SELECT por columnas de metadatos y exclusivamente
INSERT de contextos y UPDATE de sus marcas de revocación. La política de inserción
comprueba sesión, identidad, aplicación y relaciones vigentes; no confía en GUC `ls.*`.
No concede escritura administrativa ni SELECT empresarial general.

Ni los DTO de permisos ni un contexto seleccionado habilitan SQL de negocio.
`OperationAdmission.session` y las API Keys siguen deshabilitados. Su integración
requiere garantías adicionales de admisión y revocación; la administración multidevice
sigue fuera de alcance. No hay frontend en esta entrega.

## Verificación

```powershell
# JDK 21 y binarios PostgreSQL 17 disponibles; sin URLs ni credenciales compartidas.
python scripts/tests/postgres_session_context_contract.py
```

El runner reutiliza el harness de V16 y ejecuta únicamente el nuevo método
`SessionContextPostgresTest`, por HTTPS real y PostgreSQL 17 desechable, junto con
Checkstyle en Maven `verify`. No ejecuta manualmente los métodos históricos. El contrato
queda conectado al job estándar de migraciones para la única ejecución de CI de entrega.
