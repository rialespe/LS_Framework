# LFD-47 / F09.T02 — Vinculación consentida de identidades

V29 extiende V28 sin modificar V1–V28. Una solicitud administrativa no crea una
membresía. Solo la aceptación del titular autenticado en LS Core crea una nueva
membresía ACTIVE con `accepted_at` PostgreSQL. No se conceden designaciones,
sucursales, perfiles, roles, permisos ni API Keys; una membresía por sí sola no
habilita operaciones empresariales. No hay invitaciones ni dependencia de email.

## Estados y consentimiento

`PENDING → ACCEPTED | REJECTED | EXPIRED`. No hay transición de regreso a PENDING.
La vigencia es de siete días desde PostgreSQL. La bandeja muestra EXPIRED en cuanto
vence el plazo, aunque no exista actividad posterior. La transición persistida se
materializa al decidir o al solicitar otra vinculación para el mismo par
empresa/identidad; ambas vías conservan evidencia de expiración. No se instala un
proceso periódico. Las solicitudes rechazadas o expiradas nunca se reutilizan:
una nueva solicitud requiere un nuevo UUID y una nueva decisión explícita.

La aceptación exige identidad ACTIVE, credencial definitiva F04 vigente y no
bloqueada, empresa ACTIVE y autoridad vigente del administrador solicitante. Si
ya existe cualquier membresía, incluso PENDING o SUSPENDED, la aceptación se
rechaza con conflicto: no la activa ni la reactiva. LFD-48 queda fuera de alcance.
El rechazo conserva la solicitud y no crea membresía. Los demás tenants y las
credenciales del titular permanecen intactos.

## Autorización, privacidad y confianza

Se reutiliza `tenant.user_authority` de V28 y la elegibilidad de V24: OWNER,
SUPERADMIN con capacidades explícitas READ/MANAGE en `platform.tenant_user_grants`,
o administrador válido del tenant. No se asignan nuevas facultades globales.
La instalación controlada de esas capacidades sigue el procedimiento V28; no hay
endpoint HTTP que las conceda.

El actor procede de la cookie LS Core validada por F05. Todas las mutaciones
requieren CSRF y una nueva comprobación de contraseña F04, válida durante un máximo
de diez segundos y protegida frente a cambios concurrentes de credenciales hasta
COMMIT. El titular puede iniciar sesión y leer su bandeja sin ninguna membresía.
Para aceptar/rechazar, el backend deriva el tenant de la solicitud del titular,
sin aceptar un actor ni un tenant de decisión enviados por React.

Un username se compara según la normalización V7. El solicitante recibe siempre
un recibo SUBMITTED sin identidad, linkId ni resultado de búsqueda, tanto si existe
como si no existe, ya tiene membresía o tiene una solicitud PENDING. Las listas
enviadas mantienen ese estado neutro y no revelan decisiones del destinatario.
El username de búsqueda es material transitorio del comando, borrado antes de
persistir el recibo. No hay búsqueda global ni listado de otras empresas.
Esta neutralidad corresponde al contenido y códigos HTTP; no se afirma tiempo
constante frente a mediciones de latencia.

El canal SQL es un coordinador de confianza del backend, igual que V28. Sus
credenciales no se entregan al navegador. Las funciones INVOKER revalidan actor,
titular, tenant y autoridad; PostgreSQL no comprueba por sí mismo una cookie HTTP
ni la contraseña presentada a F04. Acceder arbitrariamente al canal de comandos
rompería esa frontera de confianza.

## PostgreSQL, bloqueos y auditoría

`tenant.identity_link_requests` conserva el consentimiento y su estado;
`tenant.identity_link_operations` conserva recibos inmutables por requestId.
Las claves compuestas enlazan tenant, solicitud, identidad y membresía. Un índice
único impide dos solicitudes PENDING del mismo par. Los recibos REQUEST no contienen
destinatario; los recibos de decisión pertenecen exclusivamente al titular.

Orden de bloqueos: F06 `370019,1` → V23 `410023,1` → tenant → identidades →
credenciales → membresías → designaciones → solicitud. Los escritores mantienen
los bloqueos hasta COMMIT/ROLLBACK. La barrera compartida de lecturas es compatible
con los consumidores previos; la barrera exclusiva serializa decisiones y cambios
administrativos. No se modifica la continuidad del último administrador V24:
el flujo no retira ni suspende membresías o designaciones existentes.

Los canales `ls_core_identity_links_dev` (límite 3) y
`ls_core_identity_links_log_dev` (límite 2) son LOGIN independientes, sin herencia,
propiedad de objetos, privilegios de creación, BYPASSRLS ni membresías SQL.
`scripts/migrations/prepare_identity_links.sql` exige instalación local explícita
tras V28 y rechaza adoptar roles existentes. V29 valida la configuración antes de
conceder privilegios. No hay SECURITY DEFINER. Las tablas nuevas tienen FORCE RLS;
el runtime general carece de acceso y los cambios subordinados del coordinador
solo se permiten dentro del trigger del comando. Los bloqueos tienen permisos
de fila con WITH CHECK false: no habilitan DML arbitrario.

Auditoría SUCCESS, recibo, decisión y membresía confirman en la misma transacción.
Un fallo de auditoría revierte todo. REQUEST/ACCEPT/REJECT registran actor, tenant,
fecha PostgreSQL, UUID y estado; la expiración automática usa actor SYSTEM y el UUID
de solicitud como correlación. Evidencia, recibos y decisiones terminales son
inmutables. El canal de log solo registra DENIED/UNKNOWN sin datos de identidad
buscada ni secretos. No se registran contraseñas, cookies o tokens.

Una repetición explícita del mismo requestId devuelve su recibo al actor original
si coincide el ámbito/acción; no ejecuta otra mutación. Cambiar el username en una
repetición no crea otra solicitud. Un requestId usado para otro ámbito o acción
da conflicto. No hay reintentos automáticos. Si falla la confirmación de COMMIT,
la respuesta es 202 UNKNOWN y se consulta el recibo por UUID. Un 404 de reconciliación
no prueba rollback: se conserva la referencia y no se reenvía automáticamente.

## API LS Core

Prefijo `/api/auth/core/identity-links/`:

| Método y ruta | Resultado / autorización |
| --- | --- |
| POST `tenants/{tenant}/requests` | `{requestId, username, password}`; administrador autorizado; recibo neutro |
| GET `tenants/{tenant}/sent?page=0` | Recibos enviados del tenant autorizado |
| GET `received?page=0` | Solicitudes de la identidad de la sesión, sin membresía requerida |
| POST `received/{id}/accept` | `{requestId, password}`; únicamente el titular |
| POST `received/{id}/reject` | Mismo contrato; no crea membresía |
| GET `operations/{requestId}` | Recibo del actor original, incluso sin autoridad empresarial posterior |

Páginas de 50 elementos con hasMore; máximo de página 100000. Respuestas 200
confirmado, 202 UNKNOWN, 400 entrada inválida, 401 sesión inválida, 403 CSRF/F04 o
autoridad, 404 solicitud/recibo ajeno o ausente, 409 decisión/membresía en conflicto,
422 empresa no activa y 503 canal o auditoría no disponible. Sin diagnósticos SQL.
La activación de la API es opt-in: `ls.core.identity-links.enabled=true`, Core/F04
reales y variables `LS_IDENTITY_LINKS_DB_*` / `LS_IDENTITY_LINKS_LOG_DB_*`, con
HOST/PORT/NAME iguales a la instalación F04.

## React y recorrido HTTPS

En Empresas → Usuarios, seleccionar una empresa autorizada y usar «Solicitar
vinculación de usuario existente». La bandeja «Mis solicitudes de vinculación» se
muestra también a titulares sin autoridad administrativa. Cada decisión muestra
empresa, vencimiento, estado, confirmación y una nueva entrada de contraseña.
React solo cambia el estado tras el recibo PostgreSQL. UNKNOWN conserva únicamente
el UUID en sessionStorage, bloquea nuevas vinculaciones y permite consultar el
recibo con el actor original. No persiste contraseñas ni usa datos DEMO.

Ejecutar con JDK 21 y PostgreSQL 17 disponibles:

```powershell
$env:JAVA_HOME='C:\Program Files\Java\jdk-21.0.10'
python scripts/pilot/start_connected_pilot.py --core
```

El piloto crea una instalación temporal, credenciales interactivas y HTTPS local.
Abrir `https://127.0.0.1:5174/core`. Con el OWNER, crear/activar otra empresa y
solicitar vinculación del segundo candidato existente. Cerrar sesión y entrar con
ese candidato: verificar empresa y vencimiento, aceptar con su propia contraseña;
volver como administrador y comprobar la nueva membresía en Usuarios. Repetir con
una solicitud nueva y Rechazar. Verificar ausencia de membresía antes del
consentimiento y conservación de las existentes. No copiar credenciales al código
ni usar servidores compartidos. Cerrar el piloto para retirar el clúster.

`--core --smoke-only` incorpora un recorrido HTTP real de solicitud, cambio de
sesión y aceptación, con comprobación SQL de ausencia de membresía previa.
La aprobación visual del usuario es independiente de esa prueba técnica.

## Validación focalizada y límites

```powershell
python scripts/tests/postgres_identity_links_contract.py
```

El harness posee y elimina su PostgreSQL 17 en puerto efímero, rechaza 5432/55432,
aplica/valida V1–V29 y ejecuta una integración nueva `IdentityLinksPostgresTest`:
sesiones HTTPS reales; existencia/no existencia; CSRF/F04; titular incorrecto;
administrador ajeno; SUPERADMIN sin facultad; duplicados; aceptación/rechazo;
expiración; tenant suspendido; pérdida de autoridad del solicitante; membresías
PENDING/SUSPENDED protegidas; conservación de otra empresa; ausencia de permisos
automáticos; dos conexiones JDBC decidiendo simultáneamente; auditoría atómica y
rollback; COMMIT confirmado en servidor con respuesta perdida; reconciliación por
actor; FORCE RLS y DML directo denegado. Luego ejecuta la regresión focalizada
LFD-46 sobre una copia limpia de V29 en el mismo clúster.

Las pruebas React cubren contraseña nueva, ausencia de cambio anticipado, recibos
neutros y UNKNOWN sin repetición. ESLint, TypeScript, build y Checkstyle completan
la validación local. CI añade el harness focalizado; su resultado remoto se revisa
posteriormente, sin polling tras publicar.

La barrera exclusiva es deliberadamente conservadora y limita concurrencia de
escrituras administrativas. No se ejecutan pruebas en servidores compartidos ni
se afirma validación de producción. No se implementan módulos empresariales,
RBAC posterior, API Keys ni suspensión/reactivación LFD-48.

Evidencia local del 2026-11-02: Flyway migrate/validate V1–V29 aprobado; integración
IdentityLinksPostgresTest y regresión TenantUsersPostgresTest sobre V29 aprobadas,
una prueba real por clase, sin omisiones. Maven verify adicional: 92 pruebas
ejecutadas correctas y 29 integraciones históricas omitidas por sus condiciones
de entorno; Checkstyle sin infracciones. React: 45 correctas y una omisión
existente; ESLint, TypeScript y build aprobados. Piloto `--core --smoke-only`
aprobado, incluido consentimiento HTTPS y regresiones de empresa, administradores,
sucursales e historial. Clústeres, datos temporales y certificados retirados.
V1–V28 y los tres documentos locales protegidos conservan sus SHA-256 originales.
La revisión visual humana y el resultado final de CI quedan pendientes.
