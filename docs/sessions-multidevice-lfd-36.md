# Sesiones multidevice — LFD-36 / F05.T03

## Activación y expiración

Requiere F04, login HTTP de V16, contextos de V17 y migración
`V18__multidevice_sessions.sql`, aplicada como migrador restringido.
Las migraciones V1–V17 no cambian. Activar explícitamente
`ls.login.multidevice.enabled=true`, además de las opciones de login y contexto.
Sin esa opción se conserva el flujo anterior y no se publican las rutas nuevas.

| Propiedad | Predeterminado | Rango |
| --- | --- | --- |
| `ls.login.fiscal.idle-seconds` | 1800 | 60..86400 segundos |
| `ls.login.pos.idle-seconds` | 900 | 60..86400 segundos |
| `ls.login.session-seconds` (vencimiento absoluto existente) | 28800 | 60..86400 segundos |

Configurar de forma consistente todos los procesos que atienden una aplicación.
No combinar procesos con la política nueva habilitada y procesos antiguos cuando
se exija expiración por inactividad. Fiscal y POS conservan tokens, cookies y UUID de
aplicación independientes; no hay SSO ni renovación automática de tokens.

Cada login crea otra sesión aleatoria de 256 bits y persiste solamente su digest.
El vencimiento efectivo es el primero entre `expires_at` y
`coalesce(last_seen_at,created_at) + idle-seconds`. PostgreSQL evalúa ambos con
`clock_timestamp()`. Un `last_seen_at` nulo conserva la antigüedad de `created_at`;
no se reemplaza por la hora de consulta para decidir si aún es válida.

Solo después de validar vigencia y antes de una operación autenticada se escribe
`last_seen_at` con el reloj PostgreSQL. Se confirma junto con la operación; una
denegación revierte también esa actividad. `expires_at` nunca se amplía. El nuevo
rol no puede actualizarlo. Un timeout de inactividad no elimina ni reactiva filas.

## API

Todas las rutas están bajo `/api/auth/{application}` (`fiscal` o `pos`), con la cookie
HttpOnly/Secure existente. El backend resuelve usuario y aplicación desde el digest;
no recibe identidad de usuario por HTTP. Todos los POST requieren CSRF de V16.

| Método y ruta | Comportamiento |
| --- | --- |
| GET `/sessions` | Hasta 100 sesiones del usuario/aplicación; actual primero y luego más recientes |
| POST `/sessions/{sessionId}/revoke` | Revoca una sesión del mismo usuario y aplicación; UUID ajeno o desconocido se deniega |
| POST `/sessions/revoke-others` | Revoca las otras sesiones de esa cuenta/aplicación y conserva la actual |
| POST `/logout` | Cierre actual de V16, revocación persistente y eliminación de cookie |
| GET `/tenants/{tenantId}/session-administration` | Solo informa si el backend reconoce autoridad administrativa en ese tenant/aplicación |
| GET `/tenants/{tenantId}/sessions` | Metadatos de sesiones con contexto histórico o vigente vinculado a ese tenant/aplicación |
| POST `/tenants/{tenantId}/sessions/{sessionId}/revoke` | Revoca exclusivamente el acceso de esa sesión a ese tenant |

El listado devuelve `id`, `current`, `createdAt`, `lastSeenAt`, `expiresAt`,
`idleExpiresAt`, `status` y `tenantRevoked`. Los estados son `ACTIVE`, `REVOKED`,
`ABSOLUTE_EXPIRED` e `IDLE_EXPIRED`. `tenantRevoked` se refiere solo al tenant de una
consulta administrativa; no significa revocación global de la sesión.
No se devuelven usuario, tokens, digests, credenciales, IP, navegador ni dispositivos.
Revocación individual/múltiple devuelve el número de filas revocadas. Para cerrar la
actual y borrar su cookie se utiliza `/logout`. Si se revoca su UUID mediante la
ruta individual, ese token igualmente deja de autenticar en la siguiente operación.

Una sesión inválida responde 401; una operación sin alcance responde 403 con mensaje
neutro. Los selectores UUID no prueban autoridad. Ni sesiones, contexto ni permisos
informativos se convierten en credenciales para SQL empresarial.

## Tenant y barrera persistente

La autoridad acotada de esta unidad procede de `tenant.administrators`: designación
ACTIVE, sin `revoked_at`, vinculada a la membresía ACTIVE del usuario autenticado,
tenant ACTIVE y habilitación tenant/aplicación ACTIVE. La sesión exige además usuario
ACTIVE y aplicación PUBLISHED. No se usa un rol global ni un tenantId aislado.
La designación del modelo es por tenant; cada petición se limita a la aplicación de
su cookie y a su habilitación vigente. No permite operar sobre otra aplicación.

El administrador solo ve sesiones que tengan una relación de contexto con su tenant;
no se enumeran sesiones vinculadas únicamente a empresas ajenas. Se conservan esos
vínculos históricos para poder retirar también accesos antes seleccionados.
No se exponen los otros tenants que la sesión pueda utilizar.

Revocar acceso inserta una barrera inmutable en
`security.session_tenant_revocations`, limitada a `(session_id,tenant_id)`, con FK a
la identidad/aplicación de esa sesión. En la misma transacción revoca únicamente sus
contextos de esa empresa. No modifica `sessions.revoked_at`, membresías ni otras
empresas. Tanto los listados/consultas/selección de V17 como una política restrictiva
de INSERT impiden volver a activar ese tenant con el mismo token. Una nueva sesión
requiere otro login y vuelve a evaluarse normalmente.

Cada siguiente operación abre una transacción nueva y observa la revocación ya
confirmada. No se promete cancelar transacciones que ya ejecutaban un snapshot,
recuperar datos entregados ni revocación universal G3.

## Privilegios y concurrencia

Se conserva el LOGIN técnico separado `ls_core_session_manager_dev`, sin ownership,
membresías, BYPASSRLS ni privilegios administrativos PostgreSQL. V18 añade:

- SELECT/INSERT/UPDATE de `sessions.last_seen_at`, sin UPDATE de identidad o vencimiento.
- SELECT por columnas de designaciones administrativas.
- SELECT e INSERT por columnas de la nueva barrera, sin UPDATE ni DELETE.
- FORCE RLS sobre la barrera y políticas limitadas al canal técnico, incluida la
  comprobación de designación y vínculos al insertar una barrera.

Las escrituras de contextos siguen usando las columnas y FK compuestas de V17.
No se conceden escrituras administrativas, SELECT empresarial general, SECURITY
DEFINER ni autoridad basada en GUC `ls.*`.

Se conserva REPEATABLE READ. Antes del snapshot, un bloqueo asesor PostgreSQL por
digest serializa peticiones de una misma cookie que ahora registran actividad. Lo
mantiene la conexión física PGSimple exclusiva y lo libera al cerrarse, incluso ante
error. La fila de sesión también se bloquea antes de validar/trabajar; una revocación
remota bloquea su destino antes de insertar la barrera. Conflictos o deadlocks se
deniegan con rollback, sin reintentar commits inciertos. Las consultas React de
permisos/navegación/capacidad administrativa se realizan secuencialmente.

## Recorrido visual en Windows

Seguir los requisitos de [frontend-connected-pilot.md](frontend-connected-pilot.md).
En la misma terminal PowerShell, elegir JDK 21 antes de iniciar:

```powershell
$env:JAVA_HOME = 'C:\Program Files\Java\jdk-21.0.10' # Ajustar al JDK 21 instalado
$env:PATH = "$env:JAVA_HOME\bin;$env:PATH"
python scripts/pilot/start_connected_pilot.py
```

El piloto conserva PG17 desechable, certificado local confiable y contraseña elegida
al iniciar; ahora migra hasta V18 y activa multidevice. La cuenta de fixture tiene
designación administrativa únicamente en `Tenant 10`, dentro de esa base temporal.

1. Esperar `PILOT READY` y abrir https://127.0.0.1:5174/ en Chrome.
2. Ingresar con el usuario aleatorio impreso y la contraseña elegida.
3. Abrir otro navegador o una ventana privada e iniciar sesión con la misma cuenta.
   Otra pestaña del mismo perfil comparte cookie y no representa otra sesión.
4. Abrir **Mis sesiones** y pulsar **Actualizar sesiones**: aparecen dos UUID
   independientes, uno marcado como actual, con fechas y estados reales.
5. En la primera ventana elegir **Cerrar otra sesión** sobre la segunda, leer y
   confirmar la revocación. También puede cancelarse sin hacer petición HTTP.
6. En la segunda ventana actualizar o intentar consultar el backend. Su siguiente
   operación responde 401; React descarta contexto, permisos, navegación y sesiones,
   y vuelve al login. No hay sondeo periódico que mantenga actividad artificialmente.
7. La primera sesión continúa funcionando. **Cerrar todas las demás sesiones** exige
   confirmación y conserva la actual. **Cerrar sesión actual** usa logout de V16.
8. Para comprobar tenant: con otra sesión nueva, seleccionar Tenant 10 y luego
   Tenant 20. Desde una sesión con Tenant 10 seleccionado aparece **Administrar
   sesiones de empresa**, solo tras confirmación de capacidad por el backend.
   Revocar el acceso de la otra sesión a Tenant 10: puede seguir en Tenant 20, pero
   no volver a seleccionar Tenant 10 con ese token. Tenant 20 no concede el botón.
9. Ctrl+C en la terminal detiene procesos, retira exactamente la CA creada y elimina
   PostgreSQL, archivos y datos temporales. No se usan 5432, 55432 ni bases compartidas.

No se inventa identificación de dispositivos. DEMO permanece separado e intacto.
Si el navegador advierte de TLS, no omitir la advertencia; resolver la confianza
local conforme a la guía. La revisión visual requiere un navegador del usuario.

## Validación y límites

El único método nuevo de integración es `MultidevicePostgresTest`, mediante
`python scripts/tests/postgres_multidevice_contract.py`, con HTTPS, F04 y PG17
desechable reales. Cubre usuario/aplicación, actividad, referencia NULL, timeouts
independientes, revocación remota/múltiple, barrera por tenant, otras empresas y
privilegios. Maven `verify` incluye Checkstyle. React amplía exclusivamente el método
existente de `ConnectedFiscal.test.tsx`; sus respuestas simuladas no son evidencia
de PostgreSQL. No se ejecutan manualmente integraciones históricas.

`OperationAdmission.session`, API Keys, SQL empresarial general y administración
global siguen deshabilitados. No hay renovación de tokens, inventario de dispositivos,
desbloqueo de barreras, paginación del historial completo ni cancelación de operaciones
ya en curso. Esta entrega no cierra otras historias ni altera Jira.
