# LFD-42 / F07.T03 — Administradores de empresa

Base: `origin/main` `85ea3a2b2a6d437b69096b2a3eda80f925c3cabb`, PR #44, V22 y V23.
V24 amplía F07 sobre F04, F05 y F06 existentes. V1–V23 permanecen intactas.

## Invariantes y estados

Una mutación administrativa no puede confirmar una empresa ACTIVE sin al menos un
administrador válido. La validación se realiza bajo bloqueo, después de modificar
el conjunto y de nuevo mediante una restricción diferida al finalizar la transacción.
No se calcula un conteo en React para autorizar escrituras.

`tenant.valid_administrators(tenant_id)` exige simultáneamente:

- Identidad ACTIVE y credencial F04 definitiva, con fecha de cambio, no expirada,
  sin cambio obligatorio y sin bloqueo vigente, evaluados con reloj PostgreSQL.
- Membresía ACTIVE del mismo tenant y usuario.
- Designación ACTIVE, sin `revoked_at`, enlazada a esa membresía y tenant.

La pertenencia administrativa no concede facultades OWNER/SUPERADMIN ni permisos
de aplicaciones, módulos, sucursales, perfiles o API Keys.

Las designaciones se preparan PENDING si F04 no está listo o si la empresa está
PENDING. Esa preparación no activa la empresa ni concede autoridad operativa.
La activación posterior de la designación exige F04 completo, una membresía
PENDING/ACTIVE y una empresa que ya haya superado la activación empresarial.
No se rehabilitan membresías suspendidas o revocadas por este flujo.

Revocar conserva la fila, autor, fechas y membresía; la designación pasa a REVOKED.
Una nueva designación posterior crea otra fila trazable. No se borra la identidad.
El índice parcial impide dos designaciones no revocadas de la misma membresía.
V7 ya impide duplicar una membresía tenant/usuario y V6 impone las referencias compuestas.

## Autorización y fronteras

La cookie Core independiente identifica al actor desde la sesión persistida. Ningún
UUID enviado por HTTP sirve como actor o prueba de autoridad.

- OWNER vigente puede consultar y gestionar.
- SUPERADMIN requiere `platform.tenant_administrator_grants` READ; las mutaciones
  requieren además MANAGE. Ser SUPERADMIN o tener facultades V22/V23 no basta.
- Un administrador empresarial válido puede gestionar solo su empresa ACTIVE.
  Su selección en Core es un selector de tenant; cada solicitud vuelve a comprobar
  su identidad, membresía y designación en esa empresa. No habilita la API global
  de empresas ni el registro empresarial.

Las facultades delegadas se instalan explícitamente mediante
`scripts/migrations/grant_tenant_administrators.sql`, con designación existente y
capacidad concreta. No existe una ruta HTTP para conceder facultades de plataforma.

Todas las mutaciones necesitan CSRF y una nueva comprobación de la contraseña F04
del actor obtenido del servidor. La contraseña no se almacena, registra ni devuelve.
La comprobación tiene una ventana máxima de diez segundos hasta la admisión del
comando; la autorización y elegibilidad se vuelven a comprobar en PostgreSQL.
Las cookies Fiscal/POS no autentican estas rutas.

La reconciliación tiene una autorización específica: sesión Core vigente y actor
original del recibo. Permite recuperar exclusivamente ese recibo después de una
autorrevocación, sin restituir consultas, búsquedas o facultades empresariales.

## SQL y concurrencia

Roles separados `ls_core_tenant_admin_dev` (tres conexiones) y
`ls_core_tenant_admin_log_dev` (dos). Se aprovisionan antes de V24 con
`scripts/migrations/prepare_tenant_administrators.sql`; se rechaza adoptar roles
existentes. SCRAM por entrada protegida, sin secretos en SQL o argumentos.

La funcionalidad se habilita explícitamente con `ls.core.administrators.enabled=true`,
Core y F04 reales habilitados, y `LS_TENANT_ADMIN_DB_{HOST,PORT,NAME,PASSWORD}` /
`LS_TENANT_ADMIN_LOG_DB_{HOST,PORT,NAME,PASSWORD}`. Host, puerto y base deben coincidir
con el canal F04; los roles son constantes del servidor.

No tienen propiedad, membresías SQL, BYPASSRLS, superusuario, CREATE, TEMP, DDL ni
permisos generales. Todas las funciones nuevas son SECURITY INVOKER, con
`search_path=pg_catalog`; se revoca EXECUTE público. Las tablas nuevas usan FORCE
RLS y se conserva el aislamiento existente.

El backend solo inserta comandos parametrizados en `tenant.administrator_operations`.
Los triggers validan y escriben las tablas hijas. ACL por columnas, políticas RLS y
profundidad de trigger rechazan escrituras directas incluso desde el escritor.
Los privilegios UPDATE de identidad/credenciales/tenant sirven para bloquear filas;
sus políticas `WITH CHECK(false)` impiden almacenar cambios en ellas.

Orden único para estos comandos y ciclo de vida:

1. Coordinación F06 `(370019,1)`.
2. Barrera de admisión V23 `(410023,1)`, exclusiva para cambios de autoridad.
3. Fila del tenant `FOR UPDATE`.
4. Identidades y credenciales relevantes en orden UUID, fijando su elegibilidad.
5. Membresías y designaciones del tenant, también ordenadas y bloqueadas.
6. Revalidación del actor, escritura y conteo válido posterior.
7. Auditoría, validación diferida y COMMIT.

Las consultas toman las dos barreras en modo compartido y fijan la elegibilidad
del actor; pueden coexistir entre sí y con lecturas empresariales.
Se usa READ COMMITTED. No se agregan bloqueos globales distintos a los requeridos
por F06/V23. El protocolo heredado serializa estos comandos entre empresas;
no se promete paralelismo de escritores mientras esa barrera sea global.
F04 mantiene sus bloqueos identidad→credencial y finaliza antes de adquirir las
barreras de administración. Los contadores/bloqueos F04 no se deshabilitan.

Dos designaciones de un usuario se serializan y solo una confirma. Dos retiradas
no pueden confirmar dejando cero válidos. Un actor revocado mientras espera ya
no puede admitir su comando. El reemplazo crea primero la designación ACTIVE,
después revoca la anterior, en la misma transacción. No existe una ventana
confirmada sin administrador.

## Compatibilidad con V23

V24 reemplaza `tenant.apply_lifecycle()` sin editar V23:

- PENDING→ACTIVE conserva la preparación íntegra del inicial V22. Se permiten
  otras designaciones preparadas; estas permanecen PENDING hasta activación explícita.
- El inicial PENDING no puede retirarse antes de activar la empresa.
- SUSPENDED→ACTIVE acepta cualquier administrador válido, aunque el inicial haya
  sido reemplazado o revocado; bloquea su elegibilidad y rechaza cero válidos.
- ACTIVE→SUSPENDED conserva las reglas V23 y revoca los contextos del tenant.

Las empresas SUSPENDED solo admiten gestión global. Pueden prepararse responsables
allí; la reactivación exige al menos uno válido. Los permisos de aplicación no se
recuperan ni crean por estas operaciones.

## Invalidación y auditoría

Revocar o reemplazar invalida los contextos de la membresía retirada en ese tenant.
La nueva autoridad ACTIVE también invalida sus contextos previos de ese tenant.
No se revocan sesiones globales ni contextos de otras empresas. Los permisos de
aplicación existentes siguen requiriendo F05 y una selección autorizada nueva.
La autorización administrativa nunca se toma de permisos almacenados en un contexto.

La barrera V23 drena transacciones de contexto/lectura empresarial admitidas antes
del cambio. Después del COMMIT no se admite otra operación administrativa usando
una designación retirada. No se afirma revocación de API Keys: todavía no están
implementadas y su gestión sigue deshabilitada.

Cada comando exitoso tiene UUID único, actor, tenant, acción, designación resultante,
objetivo previo, estado y fecha PostgreSQL. El trigger escribe atómicamente
`TENANT_ADMIN_DESIGNATE`, `TENANT_ADMIN_ACTIVATE`, `TENANT_ADMIN_REVOKE` o
`TENANT_ADMIN_REPLACE` en `audit.events`. El éxito no puede fabricarse mediante el
canal de rechazo. Se prohíbe modificar o borrar esa evidencia y el guard V21 impide
TRUNCATE de auditoría. Ningún canal funcional puede truncar o borrar el registro
de operaciones. Si la auditoría falla, todo el cambio se revierte.

El canal separado registra DENIED/UNKNOWN cuando el actor y servicio están
disponibles. No se prometen registros de solicitudes rechazadas antes de identificar
una sesión (por ejemplo, CSRF o cookie inválida). No se registran username de
búsqueda, contraseña, tokens ni material de credenciales.

## API Core

Prefijo `/api/auth/core/`. Todas las rutas requieren sesión Core.

| Método | Ruta | Resultado |
|---|---|---|
| GET | `administrator-tenants` | Selector de empresas autorizadas, hasta 1000; falla explícitamente si se excede |
| GET | `tenants/{tenant}/administrators` | Lista persistida, validez, capacidad de gestión y últimos 50 cambios |
| GET | `tenants/{tenant}/administrators/{id}` | Designación dentro del tenant autorizado |
| POST | `tenants/{tenant}/administrators/candidate` | `{username}` exacto; solo identidad disponible y preparación, con CSRF |
| POST | `tenants/{tenant}/administrators/designate` | `{requestId,username,password}` |
| POST | `tenants/{tenant}/administrators/{id}/activate` | `{requestId,password}` |
| POST | `tenants/{tenant}/administrators/{id}/revoke` | `{requestId,password}` |
| POST | `tenants/{tenant}/administrators/{id}/replace` | `{requestId,username,password}` |
| GET | `administrator-operations/{requestId}` | Recibo del actor original; no repite la mutación |

200 CONFIRMED solo después del COMMIT; 202 UNKNOWN; 401 sesión; 403 autoridad,
CSRF o confirmación; 404 recurso/candidato no disponible; 409 duplicado, conflicto
o último administrador protegido; 422 requisitos incompletos; 503 indisponibilidad.
Un cuerpo mal formado recibe 400. No se devuelven diagnósticos SQL.

Un UUID repetido no repite efectos: se rechaza y se consulta el recibo original.
Una pérdida de conexión durante COMMIT devuelve UNKNOWN incluso si el servidor
confirmó. La ausencia temporal de recibo no autoriza reintentar automáticamente.

## React y piloto

ConnectedCore ofrece `Empresas / Administradores` y acceso desde la ficha de empresa.
La lista muestra identidad, username, membresía, designación, vigencia y fecha.
Incluye búsqueda exacta, preparación, activación, revocación, reemplazo y revalidación.
La advertencia del último administrador es informativa; PostgreSQL impone la regla.
Cada mutación exige contraseña nueva y confirmación, limpia el formulario y evita
doble envío. Solo se recargan datos confirmados por el servidor. UNKNOWN conserva
únicamente una referencia opaca en sessionStorage y bloquea nuevas mutaciones hasta
reconciliar. No se usan datos DEMO ni se guardan secretos.

En Windows con JDK 21 (`JAVA_HOME`) y dependencias frontend instaladas:

```powershell
python scripts/pilot/start_connected_pilot.py --core
```

El lanzador muestra la URL `/core`, OWNER, inicial y segundo candidato, todos
desechables. Usa la contraseña elegida al iniciar:

1. Entrar como OWNER y crear la empresa con el username inicial indicado.
2. Abrir su ficha y activarla; entrar a Administradores.
3. Buscar el segundo candidato, agregarlo y confirmar con contraseña.
4. Revocar el inicial; verificar un válido y el historial persistido.
5. Intentar retirar el último: el servidor devuelve conflicto y conserva al administrador.
6. Revalidar la ficha. Ctrl+C detiene procesos y elimina cluster/certificados propios.

`--core --smoke-only` ejecuta además ese flujo por HTTPS real, verifica recibos y
el conteo directamente en PostgreSQL; emplea validación de la CA propia sin instalar
confianza de usuario. Nunca acepta una URL PostgreSQL compartida o productiva.

## Verificación y límites

`postgres_tenant_administrators_contract.py` ejecuta una integración JUnit sobre su
PG17 propio: F04 real, HTTPS/CSRF, permisos globales/empresariales, inicial PENDING,
preparación y activación, varios administradores, retiro del último, reemplazo,
reactivación, aislamiento, contextos, auditoría/rollback, carreras JDBC reales,
idempotencia, pérdida de respuesta tras COMMIT, FORCE RLS y DML directo rechazado.
Las pruebas focalizadas LFD-40 y LFD-41 ahora migran hasta V24 para detectar
regresiones. CI conserva los tres jobs estándar y añade la nueva integración al
job de migraciones. React prueba designación, revocación, reemplazo, último válido,
conflicto concurrente, autoridad y UNKNOWN; ESLint, TypeScript y build siguen vigentes.

La elegibilidad F04 es temporal: una contraseña puede expirar o una identidad puede
bloquearse después de un COMMIT. V24 no congela credenciales, no desactiva la
protección F04 ni promete un administrador autenticable perpetuamente por reloj.
Las consultas y cada cambio reevalúan esa condición; el global autorizado puede
reemplazar al responsable, y la reactivación se rechaza mientras no haya uno válido.
La administración general de identidades/membresías, automatización por expiración,
F08/F09, API Keys, entrega de credenciales y dashboard F13 están fuera de esta unidad.
