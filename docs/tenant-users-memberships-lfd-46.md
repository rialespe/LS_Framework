# LFD-46 / F09.T01 — Usuarios nuevos y membresías iniciales

Base: PR #48, `origin/main` `29e7b24d3d753eb356f3131395a7e8f91e92b437`.
V28 es aditiva; V1–V27 se conservan. Java 21, PostgreSQL 17 y React conectado.

## Identidad global y alcance

Cada alta crea una identidad global nueva con nombre completo y username obligatorio,
sin RUT ni campos adicionales. El correo es opcional y `email_verified_at` permanece
NULL. No se envían invitaciones. Se reutiliza `ManualIdentityInput`, el estado inicial
PENDING de V11 y la unicidad global `lower(btrim(username))` de V7, también bajo
concurrencia. No se consulta disponibilidad antes de insertar ni se hace upsert.

Un username no disponible produce 409 neutro sin UUID de la identidad existente,
empresas, roles, sesiones, correo ni permisos. No crea su membresía. No existe un
endpoint de búsqueda global de usuarios en esta funcionalidad. La vinculación con
solicitud y consentimiento autenticado corresponde a LFD-47.

Se reutiliza `tenant.memberships`, su unicidad tenant/usuario V7 y sus FK compuestas
V6. La fila conserva `invited_by_user_id`, fecha de creación, aceptación y estado.
Las listas y detalles HTTP siempre incluyen tenant autorizado; un UUID de otra
empresa no permite obtener el recurso. Los miembros anteriores también son visibles,
pero solo incorporaciones creadas por V28 admiten sus operaciones de activación y
reemisión. No se modifican membresías suspendidas ni se eliminan filas.

## Transacciones y estados

`JdbcManualIdentityRegistration.register()` y `JdbcPasswordLifecycle.issue()` mantienen
sus transacciones independientes para otros consumidores. **No se encadenan** para
afirmar atomicidad. `TemporaryCredential` extrae la generación F04 compartida: 32
símbolos uniformes de un alfabeto de 64 caracteres (192 bits), SecureRandom y el
encoder PBKDF2 SHA256/600000 existente. El canje, renovación, bloqueo y recuperación
siguen siendo los servicios F04 existentes.

El nuevo canal `ls_core_tenant_users_dev` inserta un comando parametrizado en
`tenant.user_operations`. Un trigger SECURITY INVOKER crea, en **una transacción**:

1. Identidad global PENDING y correo no verificado.
2. Hash F04 temporal con cambio obligatorio, vencimiento PostgreSQL a 72 horas.
3. Membresía PENDING, del tenant del comando y con actor responsable.
4. Recibo correlacionado y auditoría SUCCESS.

Las FK y la auditoría forman parte de ese COMMIT. Un fallo de credencial, membresía,
unicidad o auditoría revierte también identidad y recibo. El hash entra como parámetro
transitorio; el trigger lo borra del comando antes de almacenarlo. Nombre, username
y correo tampoco se duplican en el recibo. Solo `identity.user_credentials` conserva
el hash. El escritor no tiene SELECT de hashes ni permisos para modificar otras
credenciales fuera del trigger.

El secreto solo se entrega cuando el driver reconoce COMMIT. Un COMMIT incierto
produce 202 UNKNOWN sin ficha ni contraseña. No se repite automáticamente ningún
comando. El mismo requestId no puede ejecutarse dos veces; repetirlo da conflicto,
y su resultado se consulta mediante el recibo del actor original. Un 404 durante
reconciliación no demuestra rollback y no autoriza reintentar.

La incorporación tiene etapas separadas explícitas:

| Etapa | Identidad | Membresía | Acceso |
|---|---|---|---|
| Alta confirmada | PENDING | PENDING | Sin login ordinario |
| Canje F04 confirmado por el titular | ACTIVE | PENDING | Login F04 posible; sin acceso empresarial |
| Activación administrativa confirmada | ACTIVE | ACTIVE | Solo permisos efectivos preexistentes, ninguno creado por el alta |

El titular canjea la temporal una vez con `JdbcPasswordLifecycle.exchange()`. F04
bloquea identidad y credencial, impide reutilización concurrente y exige contraseña
definitiva de al menos ocho caracteres con letras, números y símbolos, válida por
90 días. La activación de membresía es **otra transacción**, requiere administrador
reautenticado, tenant ACTIVE, identidad ACTIVE y F04 definitivo vigente y sin bloqueo.
Un canje nunca activa membresías por sí solo. La pantalla permite actualizar el
detalle y ejecutar la activación cuando PostgreSQL confirma los requisitos.

No se crean sucursales, branch_memberships, perfiles, roles, permisos, designaciones
administrativas, API Keys, aplicaciones ni módulos. El login de un usuario incorporado
no concede administración Core. F05/F06/F08 siguen comprobando permisos efectivos.
LFD-48 definirá la administración posterior de acceso; esta unidad no la anticipa.

## Entrega única y entrega fallida

La API transmite la temporal únicamente en la respuesta de CREATE/REISSUE confirmada,
con `Cache-Control: no-store`. El resultado Java oculta su `toString()` y borra su
array después de serializar la respuesta. No hay secreto en URL, auditoría, recibos,
historial, excepciones ni logs de aplicación.

React mantiene el secreto solo en la vista temporal, sin localStorage, sessionStorage,
caché persistente ni historial. Puede copiarse durante esa visualización. Cerrar,
cambiar de pantalla/empresa, ocultar la página o abandonarla destruye la vista; una
respuesta tardía no la restaura. El portapapeles pertenece al usuario: debe limpiarse
después de entregar la contraseña por un medio seguro. JavaScript no permite garantizar
borrado físico de strings inmutables en memoria; se eliminan sus referencias.

Procedimiento de entrega fallida:

1. Conservar requestId y consultar el resultado con la sesión del actor original.
   La consulta nunca devuelve contraseñas. No repetir el alta con otro UUID.
2. Confirmada la incorporación, abrir su detalle con autoridad vigente. Si identidad
   y membresía siguen PENDING y la temporal no está bloqueada, elegir **Reemitir
   temporal por entrega fallida**, con nueva confirmación F04 y requestId nuevo.
3. La reemisión sustituye atómicamente el hash, fecha y vencimiento, con auditoría.
   La contraseña anterior deja de servir. No reinicia contadores ni evade bloqueos.
4. Si esa respuesta también se pierde, reconciliar esa operación y repetir únicamente
   una reemisión administrativa explícita. Nunca recuperar el secreto desde su hash.
5. Si el titular ya completó el canje, la reemisión se rechaza. Activar la membresía
   si corresponde y usar el mecanismo F04 de recuperación vigente; un correo opcional
   no verificado no habilita recuperación por correo. No se inventa un proveedor de
   entrega ni una facultad adicional de recuperación.

## Autorización y concurrencia

La cookie Core identifica al actor desde F05; React no suministra actorId. Mutaciones
administrativas: sesión Core, CSRF, contraseña F04 nueva y autoridad PostgreSQL vigente.
La prueba F04 termina antes de adquirir las barreras administrativas. Se fija después
la elegibilidad del actor y se comprueba ventana PostgreSQL de diez segundos y que
su contraseña no cambió después de comenzar la comprobación.

- OWNER global vigente puede consultar y gestionar.
- SUPERADMIN necesita concesiones específicas `platform.tenant_user_grants`: READ y
  MANAGE para mutaciones. Los grants V22/V23/V24 no conceden F09.
- Un administrador empresarial válido según V24 puede incorporar solo en su empresa
  ACTIVE; su membresía y designación deben estar vigentes y cumplir F04.

Todas las mutaciones requieren tenant ACTIVE, también para actores globales. Un global
puede consultar un tenant suspendido, pero debe resolver su ciclo de vida antes de
incorporar o activar usuarios.

Orden F06/F07: barrera `(370019,1)`, barrera `(410023,1)`, tenant, identidades ordenadas,
credenciales ordenadas, membresías y designaciones. READ COMMITTED. Las consultas usan
barreras compartidas. La suspensión y revocación toman las mismas barreras, por lo que
un registro que espera vuelve a evaluar el estado confirmado; si el alta confirma
primero, la suspensión posterior impide acceso. No se promete paralelismo entre
escritores de diferentes empresas mientras la barrera heredada sea global.

## Privilegios e instalación

Antes de aplicar V28, ejecutar explícitamente `scripts/migrations/prepare_tenant_users.sql`
como operador de instalación local autorizado. Rechaza adopción de roles existentes,
solicita SCRAM por entrada protegida y crea dos LOGINs NOINHERIT, sin ownership,
membresías SQL, superusuario, BYPASSRLS ni permisos DDL. Escritor: tres conexiones;
logger: dos. No hay seeds productivos.

Las tablas nuevas tienen ENABLE/FORCE RLS; las funciones son SECURITY INVOKER con
search_path pg_catalog y sin EXECUTE público. ACL por columnas, RLS y guards de trigger
rechazan DML directo de identidad, credencial y membresía. El escritor no tiene
permisos de sucursales, aplicaciones ni asignaciones. Su lectura interna de columnas
mínimas sirve a la autorización confiable; no se expone como API global.

`grant_tenant_users.sql` permite al operador de instalación instalar READ/MANAGE sobre
una designación SUPERADMIN concreta, bajo la barrera F06 y con evidencia de instalación.
No es una API mutable ni una autorización derivada de un UUID HTTP.

Habilitar explícitamente:

- `ls.login.core.enabled=true`, sesión F05 y autenticación F04 reales.
- `ls.core.tenant-users.enabled=true`.
- `ls.identity.password-lifecycle.enabled=true` para canje F04.
- Familias `LS_TENANT_USERS_DB_*`, `LS_TENANT_USERS_LOG_DB_*`,
  `LS_PASSWORD_ISSUER_DB_*`, `LS_PASSWORD_CHANGER_DB_*`, con HOST/PORT/NAME/PASSWORD.
  Los nuevos canales administrativos deben coincidir con el destino F04 confiable.
- React conectado `/core`, `VITE_CORE_REGISTRATION=true`.

No introducir secretos en VITE, Git, argumentos de proceso o logs SQL con parámetros.
La posesión de los LOGINs internos es una frontera de confianza del backend, conforme
al modelo vigente; no se habilita SQL arbitrario desde HTTP.

## API y pantallas

Prefijo `/api/auth/core/tenant-users/`. Sesión Core en todas las rutas salvo canje;
CSRF en todos los POST, incluido el canje que se prueba con la propia temporal.

| Método | Ruta relativa | Resultado |
|---|---|---|
| GET | `tenants` | Selector autorizado, máximo 1000; falla explícitamente al excederlo |
| GET | `tenants/{tenant}/users?page=0` | Miembros de ese tenant, 50 por página, hasMore y canManage |
| GET | `tenants/{tenant}/users/{membership}` | Datos, estado F04, incorporación y últimos 50 recibos |
| POST | `tenants/{tenant}/users` | requestId, username, displayName, email opcional y password del actor |
| POST | `tenants/{tenant}/users/{membership}/activate` | requestId y password del actor |
| POST | `tenants/{tenant}/users/{membership}/reissue` | requestId y password del actor; nueva temporal |
| GET | `operations/{requestId}` | Solo recibo del actor original, sin secreto, aun tras revocación empresarial |
| POST | `exchange` | username, temporaryPassword y newPassword; delega al canje F04 |

201 alta confirmada; 200 consultas/otras mutaciones; 204 canje; 202 UNKNOWN; 400 cuerpo,
nombre o paginación inválidos; 401 sesión; 403 autoridad/CSRF/prueba F04; 404 recurso
no disponible; 409 conflicto neutro; 422 requisitos incompletos; 503 indisponibilidad.
No se devuelven SQLSTATE, SQL, hashes ni diagnósticos JDBC.

Empresas → Usuarios ofrece selector, lista paginada, detalle, formulario y entrega
única. Nombre, username y correo opcional; confirmación administrativa nueva.
Historial sin botón de recuperación ni secreto. La reemisión explícita aparece solo
en el detalle de una incorporación PENDING. No hay opciones para vincular existentes
ni suspender membresías. No se usa DEMO. UNKNOWN o pérdida de red conserva únicamente
requestId opaco en sessionStorage y bloquea nuevas mutaciones hasta reconciliar.
Las otras pantallas Core también reconocen esa referencia pendiente.

## Validación y piloto

Integración focalizada: `python scripts/tests/postgres_tenant_users_contract.py`.
Usa su propio cluster PG17 con puerto, credenciales, directorio y TLS efímeros; rechaza
5432/55432. `TenantUsersPostgresTest` cubre OWNER, administrador empresarial, aislamiento,
SUPERADMIN sin/con grants, V7 normalizada, correo, temporal, canje concurrente una vez,
activación, ausencia de asignaciones, identidad existente sin vinculación, rollback de
credencial/membresía/auditoría, carreras de username/suspensión/revocación, COMMIT perdido,
recibo sin secreto, FORCE RLS y DML directo rechazado. Los harnesses LFD-40–45 migran
ahora hasta V28 para las regresiones. F04 conserva pruebas de ciclo y recuperación.

Pruebas React: entrega posterior a confirmación, exclusión del almacenamiento y del
historial, activación tras F04, UNKNOWN, conflicto neutro, respuesta tardía y canje.
Ejecutar además Maven verify/Checkstyle, npm test/lint/typecheck/build y CI estándar
de tres jobs para el SHA publicado. La evidencia local no acredita servidores compartidos.

Con Java 21 y dependencias frontend instaladas:

```powershell
python scripts/pilot/start_connected_pilot.py --core
```

1. Elegir contraseña local y abrir `/core` HTTPS con el OWNER indicado.
2. Crear y activar empresa con el administrador inicial del piloto.
3. Abrir Empresas → Usuarios, seleccionar la empresa y crear usuario nuevo.
4. Ver y copiar la temporal una vez; cerrar la entrega y consultar membresía PENDING.
5. En otra sesión del navegador, abrir Core sin login y completar «Cambiar contraseña
   temporal» con el username nuevo y una definitiva válida.
6. Volver como OWNER, actualizar usuarios, abrir detalle y activar membresía tras F04.
7. Ver identidad y membresía ACTIVE. El nuevo usuario puede probar login F04, pero no
   administración ni selección de sucursales sin autorización efectiva.
8. Repetir el username: rechazo neutro y ninguna identidad/membresía adicional.
9. Ctrl+C retira certificados propios, procesos y PostgreSQL desechable.

`--core --smoke-only` comprueba el recorrido por HTTPS/proxy, reconciliación sin
secreto, canje, activación y duplicado, y termina limpiando sus recursos. Esa evidencia
no equivale a aprobación visual humana del diseño.

## Límites

Los rechazos se auditan mediante conexión separada cuando existe actor y canal
disponible; CSRF/sesión inválida no inventan actor y una caída puede impedir registrar
un rechazo. Las mutaciones confirmadas siempre tienen auditoría atómica e inmutable.
Reconciliar permite al actor original leer únicamente su recibo; no restituye autoridad.
El vencimiento o bloqueo posterior sigue controlado por F04. No se implementan LFD-47,
LFD-48, proveedor de correo, recuperación por email no verificado ni concesión de
permisos operativos. El canje conserva el contrato F04: si su respuesta se pierde,
verificar el acceso definitivo o escalar a recuperación; no prometer reintento exitoso.

## Evidencia local del 30 de octubre de 2026

- `postgres_tenant_users_regressions.py`: F04 y LFD-40–46 sobre V28, ocho escenarios
  JUnit, cada uno con un test correcto y cero fallos, errores u omisiones. Bases limpias
  por escenario dentro del cluster propio; canales excepcionales retirados y cluster eliminado.
- `mvnw verify`: 122 tests, cero fallos/errores, 30 omisiones por variables de integración
  ausentes en la ejecución general. Las ocho integraciones anteriores se ejecutaron explícitamente.
- React: 42 pruebas correctas y una omitida de la configuración anterior. ESLint,
  TypeScript y builds normal y conectado correctos. Checkstyle: cero infracciones.
- `start_connected_pilot.py --core --smoke-only`: PILOT READY, recorrido real de alta,
  entrega, reconciliación sin secreto, canje, activación, cero sucursales automáticas
  y duplicado rechazado. Logout, retirada de CA/certificados y eliminación de PG17 confirmados.
- V1–V27 sin cambios; los tres documentos locales protegidos conservan sus hashes.
  La aprobación visual humana y cualquier validación en un servidor compartido no
  se afirman como realizadas. El resultado de CI se informa para el SHA publicado.
