# LFD-45 / F08.T03 - Proteccion de sucursales inactivas

Base: PR #47 en `e439ded97c0871c90dff257928725d08f8fa7f5b`. V1-V26 intactas.
V27 es aditiva: no cambia datos historicos, no publica catalogos, no concede
permisos de negocio y no crea configuraciones ni empresas.

## Matriz de estados

| Tipo de solicitud | ACTIVE | SUSPENDED | CLOSED |
|---|---|---|---|
| Lectura operativa F04/V15 existente | Solo con F04, membresias, aplicacion y prueba nativa vigentes | Denegada | Denegada |
| Escritura empresarial por sesion/API Key | Sigue denegada | Denegada | Denegada |
| Prueba interna de admision futura | Guardas completas, sin DML empresarial | Denegada | Denegada |
| Nueva seleccion F05 | Solo contexto autorizado vigente | Denegada | Denegada |
| Historial administrativo V25 de una ficha | Autoridad administrativa V25 | Misma autoridad | Misma autoridad; solo lectura |
| Canal historico V27 | Permisos explicitos del recurso | Mismos permisos | Mismos permisos; no reabre |

Tenant distinto de ACTIVE impide el canal operativo y el canal historico V27.
La consulta administrativa de soporte V25 mantiene su contrato existente. No se
confunden esa autoridad de soporte, los permisos historicos y los operativos.
ACTIVE no concede acceso. Una reactivacion no recupera contextos revocados;
es necesario seleccionar nuevamente un contexto permitido. CLOSED sigue terminal.

## Barreras, concurrencia y consumidores futuros

Orden: coordinacion F06 `(370019,1)`; ciclo de vida V23 `(410023,1)`;
tenant; sucursal; identidad/credencial/membresias; contexto y sesion cuando aplican.
Las admisiones usan locks compartidos; V23/V25/V26 y los instaladores de permisos
usan exclusivos antes de bloquear filas. El coordinador F04 y las sesiones F05
ahora adquieren ambos locks en ese orden. La guarda SQL de seleccion sigue el
mismo protocolo. No se amplia el lector restrictivo de V25.

`BranchReadCoordinator` no devuelve JDBC, cursores ni SQL diferido. Mantiene la
barrera hasta que `ContextTransaction` materializa el SELECT fijo, revalida la
prueba V15 y confirma. La evidencia esta ligada a LOGIN/base/PID/backend-start/XID,
caduca y se elimina al cerrar. Los GUC no otorgan autoridad.

`BranchOperationGuard.probe()` es una herramienta interna, sin bean ni endpoint,
que inserta un comando `security.branch_write_probes` en una transaccion propia
del consumidor, READ COMMITTED y de escritura. SQL comprueba tenant y sucursal
ACTIVE, identidad/credencial F04, membresia y acceso a esa sucursal, permiso
WRITE_PROBE vigente, aplicacion y modulo publicados/habilitados y contexto activo
con su sesion/actor/tenant/aplicacion/sucursal exactos. Los locks sobreviven hasta
COMMIT/ROLLBACK del consumidor. No concede INSERT/UPDATE/DELETE de datos de negocio.

El parametro opcional `requiredVersion` exige SOLO el contrato publicado exacto
y una configuracion READY de esa sucursal/modulo. Sin parametro, no se exige
READY. READY no sustituye habilitacion, contexto ni permisos. DRAFT, contrato
retirado o version distinta se rechazan cuando ese contrato se requiere.

Esta herramienta NO es un verificador aprobado de `OperationAdmission.session()`
ni de API Keys. Esas rutas continuan denegadas incluso con candidatos coherentes.
Un modulo futuro debera integrar verificacion confiable de su actor/contexto,
permisos del recurso, sus guardas y auditoria de la operacion real, en una unica
transaccion hasta COMMIT; debera repetir las pruebas de carreras de su consumidor.
No puede hacer una comprobacion previa y ejecutar luego fuera de la barrera.

## Permisos historicos y multisucursal

Se agrega un canal separado `ls_core_branch_history_dev`; el operativo
`ls_core_branch_operation_dev` conserva su RLS de sucursal ACTIVE. Las nuevas
tablas FORCE RLS son:

- `security.branch_operation_grants`: permiso por tenant/membresia/sucursal/
  aplicacion/modulo, HISTORY_READ o WRITE_PROBE y vencimiento obligatorio.
- `security.branch_multihistory_grants`: permiso adicional vigente por membresia
  del tenant para conjuntos de mas de una sucursal.
- `security.branch_history_requests`: evidencia inmutable nativa PID/XID de una
  seleccion completa autorizada, con UUID, actor y TTL de diez segundos.
- `security.branch_write_probes`: evidencia inmutable de admision de prueba.
- `security.branch_history_rejections`: evidencia minima de rechazos identificados.

Las FKs compuestas impiden cruzar empresa/sucursal/membresia/modulo/aplicacion.
Las tablas de permisos se crean vacias: ni OWNER ni administrador tenant ni
SUPERADMIN reciben automaticamente permiso historico o multisucursal. F09 debe
definir su concesion productiva; esta unidad no ofrece una UI para otorgarlos.
Las demostraciones usan fixtures explicitamente identificados y desechables.

La solicitud historica comprueba identidad ACTIVE, credencial vigente, membresia
y branch_membership ACTIVE, permiso HISTORY_READ no vencido de CADA sucursal y
habilitacion/publicacion vigente. Para mas de una exige ademas MULTIHISTORY.
Una seleccion mixta, extranjera, duplicada o parcialmente autorizada se rechaza
completa: no se devuelven filas parciales. La pertenencia administrativa no sustituye
permisos del recurso. El contrato historico actual es exclusivamente el modulo
`branches` de `ls-core` y sus registros persistidos `tenant.branch_operations`.
No es un lector generico de otros modulos ni de datos comerciales.

Solo se permite INSERT de solicitudes, SELECT acotado mediante RLS y auditoria.
Ninguna ruta historica permite mutaciones, seleccion de contexto ni SQL arbitrario.
Un request deja de autorizar lecturas al cambiar XID o vencer; no es un bearer.
No hay SECURITY DEFINER, BYPASSRLS, DDL, roles heredados ni escrituras HTTP directas.
Los SELECT de columnas de metadatos y los ACL UPDATE para row locks son tecnicos;
las politicas WITH CHECK(false) impiden almacenar cambios con esos privilegios.

## API y React

Activacion explicita: `ls.core.branch-history.enabled=true`, Core y F04 reales.
Canal `LS_BRANCH_HISTORY_DB_{HOST,PORT,NAME,PASSWORD}` con rol fijo
`ls_core_branch_history_dev`, mismo endpoint confiable que F04. Provisionamiento
explicito previo a V27 mediante `scripts/migrations/prepare_branch_operation_guards.sql`.
El rol guard no se configura ni expone como servicio HTTP.

`GET /api/auth/core/branch-administration/tenants/{tenant}/historical-changes?branches={uuid},{uuid}&page=0`

Requiere cookie Core autentica; el actor siempre procede del servidor. Limite de
20 sucursales distintas por seleccion y 50 cambios por pagina, orden estable
fecha/UUID descendentes, page 0..100000, indicador hasMore y referencia UUID.
Devuelve referencia original, branchId, accion, estado previo/resultante y fecha
PostgreSQL; no nombres de usuarios, secretos ni diagnosticos SQL. Respuestas
200, 400, 401, 403 y 503. Cookies Fiscal/POS no autentican este canal.
El GET no es una mutacion de negocio ni requiere contrasena nueva; todas las
mutaciones administrativas existentes mantienen Core + CSRF + reautenticacion F04.

Empresas > Sucursales conserva su ficha e historial V25 conectado a API real.
El nuevo resumen refleja estado persistido de tenant/sucursal, bloqueo de nuevas
operaciones, cierre terminal y necesidad de nueva seleccion despues de reactivar.
Aclara que historial administrativo no concede acceso de negocio o multisucursal.
No agrega botones simulados, datos demo ni reportes. Conserva UNKNOWN/reconciliacion
de las mutaciones V25/V26: ningun estado se anticipa al servidor.

## Auditoria

La emision V15 registra BRANCH_READ_PROOF_ISSUED; autorizar historia registra
BRANCH_HISTORY_ADMITTED con seleccion y referencia; la prueba interna registra
BRANCH_WRITE_PROBE_ADMITTED. Estos eventos significan admision/emision, NO una
escritura comercial ejecutada. Se confirman atomicamente con su evidencia.
BRANCH_SUSPEND/CLOSE existentes siguen siendo atomicos con estado y revocacion.
La prueba concurrente demuestra la espera con pg_locks y la confirmacion posterior.

Los eventos son inmutables y solo sus triggers reservados pueden emitirlos.
Un fallo de auditoria revierte la evidencia; un UUID repetido no reaplica la
admision. El GET historico solo devuelve datos tras COMMIT reconocido y cierre;
un COMMIT incierto produce 503 y no muestra datos ni exito. No hay mutacion de
negocio que repetir o reconciliar en ese GET. Los UNKNOWN de V25/V26 mantienen
su ledger y reconciliacion por UUID sin reenvio automatico.

Para rechazos historicos con actor Core identificado se intenta registrar, tras
ROLLBACK y en otra transaccion, BRANCH_HISTORY_REJECTED, distinguiendo TENANT_STATE,
MODULE_UNAVAILABLE y AUTHORITY. Fallar esa evidencia no convierte un rechazo en
exito. Las guardas internas distinguen P2701 tenant, P2702 sucursal, P2703 modulo,
P2704 preparacion y 42501 autoridad/contexto; los consumidores futuros deben
registrar rechazos identificados de forma segura, sin revivir una transaccion
abortada. No se registra contrasena, cookie, hash, token o dato de negocio.

## Verificacion y piloto desechable

Prueba focalizada: `python scripts/tests/postgres_inactive_branch_contract.py`.
Usa JDK21, PG17 propio, puerto efimero distinto de 5432/55432 y limpieza automatica.
Un unico metodo de integracion HTTPS/JDBC crea datos marcados como fixtures,
prueba lectura F04 ACTIVE, suspension/cierre y contextos, permisos historicos y
multisucursal, integridad, rechazo de usuarios y cookies ajenas, expiracion de
permisos, habilitaciones, READY condicional, auditoria/rollback, RLS y DML directo.
Dos carreras con conexiones fisicas distintas prueban operacion/suspension y
operacion/cierre: pg_locks demuestra espera antes de confirmar la admision;
tras confirmar el cambio la operacion nueva se rechaza.
Se ejecutan las regresiones LFD40/41/42/43/44 sobre V27 y las pruebas pertinentes
F04/F05/F06 del CI estandar, con sus clusters propios.

`python scripts/pilot/start_connected_pilot.py --core`

1. Login OWNER con la cuenta y contrasena locales del piloto.
2. Crear/activar empresa y crear CASA MATRIZ y SUCURSAL 02.
3. En consola, `fixture-history <UUID_EMPRESA>` instala SOLO en este cluster
   permisos historicos y membresias de demostracion para el OWNER. No da WRITE_PROBE.
4. `check-branch <UUID_EMPRESA> <UUID_SUCURSAL>` ejecuta el lector F04 real por
   consola con autenticacion; no expone un endpoint operativo nuevo.
5. Suspender SUCURSAL 02 desde la UI; ejecutar el check y observar DENIED.
   Consultar su ficha e historial reales; CASA MATRIZ debe seguir ALLOWED.
6. Cerrar SUCURSAL 02, confirmar terminalidad e historial. La API historica V27
   puede probarse con la cookie Core y la seleccion explicita autorizada anterior.
7. `salir` elimina procesos, cluster, archivos de certificados y confianza propia.

`--core --smoke-only` automatiza el recorrido HTTPS, las consultas historicas,
la prueba del lector protegido y la persistencia/aislamiento PG17. Los fixtures
no se instalan en bases compartidas y nunca se convierten en seeds productivos.

## Limites y trabajo siguiente

F08 no habilita operaciones comerciales LS Fiscal/POS. No hay DTE, ventas, cajas,
reportes tributarios ni API Keys operativas. No se afirma su revocacion.
F09 debe aprobar permisos y mecanismos definitivos de concesion/revocacion;
los nuevos grants tecnicos no se asignan desde una pantalla de produccion.
Cada futuro modulo debe adoptar el contrato transaccional, permisos del recurso,
preparacion especifica cuando aplique y auditoria de su operacion real; no obtiene
seguridad por mostrar ACTIVE/READY en React. La consulta historica de recursos
futuros necesita un contrato y lector propio; el canal V27 solo lee cambios
administrativos de sucursales. Las evidencias nativas se conservan sin borrado
automatico; una politica de retencion futura debe preservar auditoria e integridad.
