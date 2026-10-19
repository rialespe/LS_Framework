# Verificación LFD-28 — F03.T01

Fecha: 2026-10-19. Base verificada: `8c34867f9bb94d90c0b4cdbc78077a3a520d1e46`.
Rama: `feature/LFD-28-contexto-seguro-por-transaccion`.
Estados Jira y dependencias: informados por el propietario en la instrucción; no se modificó Jira.
No se abrió PR, fusionó ni eliminó ninguna rama. La publicación y el resultado CI por SHA exacto
se entregan en el informe final posterior al push; este registro documenta verificación local.

## Git y alcance

Preflight ejecutado desde raíz: remote -v, status --porcelain=v1 --untracked-files=all,
status -sb, branch --show-current, fetch origin main, rev-parse origin/main,
log origin/main..HEAD, búsqueda local/remota de ramas LFD-28. Árbol limpio, sin commits
pendientes, origin correcto y sin rama previa. Se creó desde origin/main y publicó antes de
editar; HEAD, origin/main y rama remota coincidieron con el SHA base indicado.

Se agregan cuatro clases productivas sin beans ni endpoints: TrustedContextSource (puerto y
evidencia), ContextResolver (coherencia y contexto autorizado privado), ContextRejectedException
(rechazo sin diagnóstico privado), ContextTransaction (ciclo JDBC acotado). No hay adaptador
productivo, autenticador, protocolo de tokens, repositorio empresarial ni dependencias nuevas.
Ver [diseño y matriz completa de 29 tablas](multi-tenant-context.md).

## Pruebas locales

Entorno: Windows PowerShell, JDK 21.0.10 seleccionado explícitamente con JAVA_HOME y PATH;
Node 24.14.0, npm 11.12.1; PostgreSQL 17 mediante binarios existentes y harness desechable.

| Directorio / comando | Resultado / evidencia |
| --- | --- |
| backend: `.\mvnw.cmd -B -ntp clean verify` | OK: 43 ejecutadas, cero fallos/errores; 5 PostgreSQL omitidas deliberadamente fuera del harness; Checkstyle 0 infracciones |
| frontend: `npm.cmd ci` | OK: 257 paquetes instalados; auditoría reportó 0 vulnerabilidades |
| frontend: `npm.cmd run lint` | OK, incluye typecheck |
| frontend: `npm.cmd run test` | OK: 3 pruebas |
| frontend: `npm.cmd run typecheck` | OK |
| frontend: `npm.cmd run build` | OK |
| raíz: `python scripts/tests/test_postgres_migrations.py` | OK: suite completa; cinco JDBC sin omisiones, regresión de modelo/semillas/checksums/privilegios/startup aprobada |
| raíz: `git diff --check` | OK sobre archivos e índice final |
| raíz: `git status --short` | Revisado: únicamente archivos LFD-28; limpieza tras publicación se comprueba en informe final |

Las 43 ejecutadas del backend son 29 del resolutor con fuente sintética, 4 de fallos JDBC con
mocks y las 10 smoke anteriores. Ninguna prueba del resolutor autentica credenciales reales.
Las cinco PostgreSQL se ejecutaron satisfactoriamente durante diagnóstico acotado y durante
la suite completa final, siempre con PGDATA nuevo y rol runtime limitado. El harness finalizó
con: `All isolated migration checks passed. Shared PostgreSQL was not contacted.`
V8 conservó checksum -1947984735 y se validaron los checksums de V1–V7. CI conserva los tres jobs: Backend Java 21, Frontend Node 24 y Migrations
PostgreSQL 17. El harness invoca Maven para ContextTransactionPostgresTest y exige cinco casos
con cero errores/fallos/omisiones; no permite un verde por omisión silenciosa de integración.

## Cobertura y fallos observados

Resolutor sintético: sesión coherente con aplicación/tenant/membresía/sucursal/vínculo explícito;
API Key previamente autenticada por doble, incluida sucursal nula sin comodín; claims exactos,
constructor privado y consumo único. Negativos: identidad/contexto ausentes, prueba ajena,
contextId y tenant ajenos, membresía de otro usuario/tenant, sucursal de otro tenant, acceso
faltante o de otra membresía/sucursal, aplicación incompatible, sesión/contexto revocados o
expirados (incluido límite exacto), API Key revocada/expirada/ámbito incoherente, decisión
funcional UNKNOWN/DENIED e indisponibilidad. La fuente simulada de solo sesiones rechaza el
método de API Key; no se implementa autenticación real con esa prueba.

Mocks JDBC: rechazo antes de adquirir conexión, fallo de adquisición, fallo al inspeccionar
baseline, fallo al establecer contexto y fallo de rollback. Se verifica intento de rollback,
expulsión, no commit y ausencia de diagnósticos privados en el rechazo.

PostgreSQL real aislado: tres tenants consecutivos usan el mismo pg_backend_pid con pool de
una conexión; valores correctos dentro de transacción read-only y sin valores residuales tras
commit. Rechazo de fachada retenida, otro hilo, anidamiento, COMMIT por callback, cambio de
tenant por SQL, división por cero incluso atrapada por callback, error de trabajo,
indisponibilidad antes/durante operación y conexión con contexto residual. La contaminación
experimental usa set_config(..., false) exclusivamente en fixture adversarial: se rechaza y
se verifica sustitución del pg_backend_pid. No se modifica la configuración productiva.

La primera integración FALLÓ: expulsar físicamente la conexión y cerrar después su proxy
provocaba NullPointerException interna de Hikari 7.0.2 durante resetConnectionState. Se corrigió
usando la expulsión como cierre físico definitivo en caminos rechazados; el cierre normal se
reserva para retorno seguro. La suite JDBC de cinco casos pasó tras la corrección. Este fallo
no afectó ningún servidor externo ni fue presentado como una validación aprobada.

## PostgreSQL, regresión y límites

Exclusivamente disposable_cluster del harness existente: directorio temporal nuevo con prefijo
ls-core-migrations-test-, puerto TCP aleatorio distinto de 5432, contraseñas efímeras por stdin/
entorno de proceso, SCRAM en conexiones host, runtime/migrador separados y limpieza validada
solo del clúster propio tras pg_ctl stop. La suite Java comprueba PG_VERSION=17, directorio
bajo temp, ruta PGDATA y puerto de postmaster.pid antes de conectar a 127.0.0.1. No acepta URL
externa ni usa LS_DB_* del servidor real. No se conceden roles ni permisos adicionales para
LFD-28, ni siquiera en el fixture; set_config y SELECT de funciones técnicas usan el runtime.
El harness conserva sus preparaciones administrativas anteriores solo dentro del clúster creado.

Comparación binaria con git show del SHA base: V1–V8, provision.sql, PostgresConfiguration y
ADR 0004 intactos. Configuración application*.yml y pom.xml sin cambios. Modelo de 29 tablas,
29 FK compuestas, 73 índices y manifiesto de 28 semillas siguen bajo las regresiones previas.
ADR 0004 sigue Propuesto, ADR 0002 conserva el pool productivo máximo tres y readOnly=true,
HealthIndicator y perfil postgres opcional. Los checksums históricos no se reescriben.

NO EJECUTADA: autenticación real de sesiones/API Keys, resolución SQL contra security/tenant/
catalog con permisos aprobados, validación sobre servidor compartido y aislamiento RLS integral.
No son resultados inferibles de mocks ni de las consultas técnicas contra PostgreSQL aislado.

## Evaluación y decisiones pendientes

Entregada la infraestructura mínima inactiva y las pruebas del contrato autorizado para LFD-28.
Para activarla se necesita F04/F05 (identidad/sesiones), F16 (autenticación API Key) y aprobación
de políticas funcionales de vigencia y del protocolo confiable de lectura/frescura/revocación.
No se propone ampliar privilegios aquí. Introducir un adaptador SQL o autenticador real se
detiene en esa frontera y requiere revisión específica; no existe un bypass productivo.

LFD-29 debe revisar permisos mínimos, propietarios/BYPASSRLS, rutas globales/administrativas
auditadas y políticas por ámbito. La matriz propuesta distingue 13 globales, 14 tenant_id
obligatorio, tenant.tenants como raíz por id y audit.events nullable/histórica. No se ejecuta DDL.
Los parámetros SQL son manipulables por código con capacidades de sesión; las comprobaciones
son defensivas y no prueban una frontera criptográfica. El SQL del callback debe pertenecer a
código confiable y no puede proceder del cliente. La revocación concurrente necesita protocolo
futuro; los efectos externos del callback no son reversibles por JDBC. F10 resolverá permisos
funcionales; LFD-30 validará fugas y acceso directo después de RLS. Ninguna de estas fases se
inicia o declara terminada por esta entrega. Revisión del propietario pendiente para cierre Jira.
