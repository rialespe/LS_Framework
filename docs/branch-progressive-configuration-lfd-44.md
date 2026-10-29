# LFD-44 / F08.T02 — Configuración progresiva por sucursal y módulo

Base: `origin/main` con PR #46, merge
`ee2804befe1d7b66544c098afdef55f87eebffa2`. V1–V25 permanecen intactas.

## Catálogo real y alcance

V8 publica LS Core y los módulos `branches`, `memberships`, `access`. No publica
un catálogo productivo completo de LS Fiscal/POS ni contratos de configuración
especializada. V26 instala estructuras vacías: no crea aplicaciones, módulos,
contratos, habilitaciones, permisos, configuraciones ni datos de negocio ficticios.

Crear una sucursal sigue requiriendo únicamente código y nombre, como en LFD-43.
La ausencia de configuración especializada nunca bloquea su alta, consulta o
administración general. Los estados ACTIVE/SUSPENDED/CLOSED, la unicidad V7,
protección de códigos referenciados, cierre terminal e historial no cambian.

La configuración no activa tenants, aplicaciones o módulos ni concede acceso a
sucursales, perfiles, sesiones o API Keys. READY solo indica que unos valores
cumplieron un contrato concreto; no constituye autorización de uso empresarial.

## Modelo seleccionado

Se usa un registro relacional de contratos y campos tipados con valores JSONB
acotados. Esto permite incorporar contratos nuevos sin agregar columnas fiscales
o de caja a `tenant.branches` ni alterar su ABM. No existe un editor de JSON
arbitrario, esquema libre o código de validación suministrado por el navegador.

| Estructura | Responsabilidad |
|---|---|
| `catalog.branch_configuration_definitions` | Aplicación, módulo, versión, estado, decisión de delegación y referencia de aprobación |
| `catalog.branch_configuration_fields` | Esquema explícito por versión: claves, etiquetas, tipos, obligatoriedad y límites |
| `platform.branch_configuration_grants` | Facultad global READ/MANAGE por módulo para una designación existente |
| `tenant.branch_configuration_grants` | Facultad READ/MANAGE por módulo para una designación del tenant |
| `tenant.branch_configurations` | Valores, DRAFT/READY, revisión, actor, correlación y fecha por alcance completo |
| `tenant.branch_configuration_operations` | Registro inmutable del comando y su instantánea confirmada |

La clave completa es `(tenant_id, branch_id, application_id, module_id,
definition_version)`. FKs compuestas enlazan tenant/sucursal de V6, habilitación
tenant/aplicación, habilitación tenant/aplicación/módulo de V7 y contrato
aplicación/módulo/versión. No se puede asociar una sucursal ajena ni un módulo de
otra aplicación. No se inventan UUID: los recursos se consultan del catálogo.

Cada versión puede mantener su configuración e historial propios. No se migra
automáticamente una configuración a una versión nueva ni se elige una versión
para futuras operaciones de negocio. El consumidor futuro deberá exigir el
contrato aprobado exacto que corresponda a su operación.

## Esquema y validación

El instalador controlado define versiones DRAFT, con referencia explícita de
aprobación y hasta dieciséis campos. Debe instalar los campos antes de publicar.
Una versión publicada y sus campos son inmutables; puede retirarse a RETIRED,
conservando referencias e historial. Los contratos no se publican por HTTP.

Tipos admitidos:

- TEXT: máximo explícito de 1–200 caracteres, sin caracteres de control.
- INTEGER: límites mínimo/máximo explícitos dentro de -1.000.000 a 1.000.000.
- BOOLEAN: valores booleanos, sin coerción desde texto o números.

Las claves cumplen `[a-z][a-z0-9_]{0,39}`. No se admiten claves desconocidas,
null, arrays u objetos anidados como valores. El objeto completo admite como
máximo dieciséis campos y 4096 bytes de JSONB representado por PostgreSQL; Java
también limita su representación UTF-8 antes de enviarla. La frontera HTTP
existente conserva su límite de cuerpo de 8192 bytes.

Java valida tipos, tamaño, límites y requisitos usando el esquema persistido.
PostgreSQL vuelve a aplicar la validación estricta en el trigger del comando.
Las pruebas invocan también el canal SQL con valores inválidos para comprobar
que la protección no depende de React o del validador Java.

No se aprueban campos fiscales, tributarios, SII, DTE, cajas ni ventas en esta
unidad. Ningún campo especializado de LS Fiscal/POS puede considerarse definido
por este mecanismo o por los fixtures. Esas definiciones y sus facultades requieren
contratos futuros autorizados. Contraseñas, tokens, certificados privados y
secretos tributarios requieren un almacén seguro específico, fuera de estas tablas.
El registro tipado es exclusivamente para datos administrativos no secretos.

## Preparación y disponibilidad

- ABSENT: no existe fila para esa versión; es un estado derivado, no una
  configuración creada por defecto.
- DRAFT: valores presentes válidos; pueden faltar campos obligatorios.
- READY: validación explícita del último borrador persistido, con todos los
  requisitos del contrato satisfechos.
- RESTRICTED: el actor puede consultar la sucursal, pero carece de facultad para
  leer esa configuración; no se revelan valores, revisión o existencia de fila.

PREPARE crea DRAFT. UPDATE guarda DRAFT e incrementa la revisión, incluso si
antes estaba READY. VALIDATE comprueba los valores almacenados, ignora los valores
del cuerpo y marca READY. Nunca valida cambios no guardados del formulario.
Una petición incompleta recibe 422 y no cambia el estado ni la revisión.

La API separa aplicación publicada, módulo publicado, habilitación de aplicación,
habilitación de módulo, contrato publicado, contexto activo, preparación y
facultades del actor. `available` reúne solo publicación/contrato/habilitaciones;
`contextActive` informa sobre tenant y sucursal. READY permanece históricamente
almacenado al deshabilitar un módulo, sin convertirse en permiso operativo.

Las mutaciones especializadas requieren tenant y sucursal ACTIVE. La suspensión
conserva todas las configuraciones; el cierre es terminal para mutaciones, pero
los actores autorizados pueden consultar configuración e historial, incluso si
el contrato se retiró o la aplicación/módulo se deshabilitó.

## Autorización

Se reutiliza la frontera LFD-43 mediante `tenant.branch_administration_authority`.
La cookie Core identifica al actor persistido; los IDs de la ruta son selectores
de recursos y nunca pruebas de autoridad. Fiscal/POS no autentican estas rutas.

- OWNER vigente puede gestionar contratos instalados dentro de las demás reglas.
- SUPERADMIN necesita READ/MANAGE de administración de sucursales y además
  READ/MANAGE específico del módulo en `platform.branch_configuration_grants`.
- Un administrador empresarial válido solo actúa en su propio tenant y necesita
  facultad específica en `tenant.branch_configuration_grants`. Además, el contrato
  debe permitir expresamente delegación empresarial para esa versión.

READ no concede MANAGE. La administración general de sucursales no concede
configuración universal de módulos. Las facultades específicas tampoco activan
aplicaciones o módulos ni sustituyen su disponibilidad.

`scripts/migrations/grant_branch_configuration.sql` requiere instalación explícita,
designación existente, aplicación/módulo existentes y capacidad concreta. Con
`tenant_id` instala una facultad empresarial; sin él selecciona SUPERADMIN.
No existe concesión HTTP ni facultades sembradas por V26.

Cada mutación necesita CSRF y contraseña F04 nueva del actor obtenido del servidor.
La autoridad se vuelve a comprobar bajo los bloqueos transaccionales y la prueba
F04 debe ser reciente. Se conserva la reautenticación, caducidad y protección de
bloqueos F04; no se almacenan secretos en formularios persistentes o auditoría.

## V26, privilegios y concurrencia

Roles nuevos separados: `ls_core_branch_config_dev` (tres conexiones) y
`ls_core_branch_config_log_dev` (dos). Se aprovisionan con
`scripts/migrations/prepare_branch_configuration.sql` antes de V26, sin adoptar
roles existentes, con SCRAM y sin propiedad, membresías SQL, herencia, BYPASSRLS,
superusuario funcional, CREATE, TEMP o DDL. Todas las tablas nuevas usan FORCE RLS.
Todas las funciones son SECURITY INVOKER, con search_path fijo y EXECUTE público revocado.

El backend solo inserta comandos parametrizados. Las escrituras de valores se
permiten exclusivamente desde el trigger del comando, con ACL, RLS y guardas de
profundidad. Los permisos UPDATE mínimos usados para FOR SHARE/FOR UPDATE no
habilitan cambios de identidad, credenciales, sucursal, catálogo o habilitaciones.
Las tablas globales de catálogo conservan su carácter global; una guarda impide
que el escritor de configuración use sus privilegios de bloqueo para modificarlas.

Se habilita explícitamente con `ls.core.branch-configuration.enabled=true`,
Core/F04 reales y `LS_BRANCH_CONFIG_DB_{HOST,PORT,NAME,PASSWORD}` /
`LS_BRANCH_CONFIG_LOG_DB_{HOST,PORT,NAME,PASSWORD}`. Los endpoints deben coincidir
con F04. En el piloto se usa `LS_CORE_BRANCHCONFIGURATION_ENABLED=true`;
los procesos de aplicación no reciben credenciales de aprovisionamiento.

Orden: coordinación F06 `(370019,1)`, barrera V23 `(410023,1)`, tenant, identidad y
credencial del actor, sucursal, catálogo/habilitaciones, contrato/campos y fila de
configuración. Las lecturas usan barreras compartidas; los cambios, exclusivas,
con READ COMMITTED. Instaladores de contratos/facultades y cambios de catálogo o
habilitación adquieren las barreras antes de bloquear filas. No se agrega otro
mutex global diferente del protocolo vigente.

PREPARE rechaza una fila existente. UPDATE/VALIDATE requieren `expectedRevision`
igual a la fila bloqueada y generan una revisión nueva. Dos escrituras sobre la
misma revisión no pueden sobrescribirse. Suspensión/cierre, cambios de tenant,
revocación administrativa y deshabilitación de módulo se serializan con el comando;
la segunda operación reevalúa el estado confirmado antes de continuar.

La configuración no otorga accesos: por sí sola no crea ni recupera contextos.
La revocación operativa por suspensión/cierre continúa en LFD-43. LFD-45 deberá
conectar los futuros consumidores de negocio con todos los predicados actuales,
incluyendo contrato exacto, preparación, habilitación y autoridad.

## Auditoría, idempotencia y UNKNOWN

El éxito registra atómicamente actor, alcance, versión de definición, revisión,
estado anterior/nuevo, fecha PostgreSQL y correlación. Audit usa
`BRANCH_CONFIG_PREPARE`, `BRANCH_CONFIG_UPDATE` y `BRANCH_CONFIG_VALIDATE`.
Su metadata contiene únicamente identificadores, versión, revisión y estados,
sin valores del formulario, contraseñas, tokens o hashes.

El registro de operaciones conserva una instantánea de los valores administrativos
no secretos, accesible solo con autorización de configuración o mediante el recibo
propio. El registro rechaza UPDATE/DELETE/TRUNCATE; la evidencia exitosa es inmutable.
Un fallo de auditoría revierte valores, revisión y preparación.

Un requestId repetido no reaplica efectos. CONFIRMED se devuelve después de COMMIT.
La pérdida de confirmación de COMMIT devuelve UNKNOWN con UUID opaco, sin afirmar
estado final ni reintentar. El actor original con sesión Core vigente puede
reconciliar su recibo aunque haya perdido facultades, sin recuperar acceso al tenant.
Un 404 temporal del recibo no permite repetir automáticamente el comando.

## API y React

Prefijo `/api/auth/core/branch-administration/`. No se reutilizan selectores F05.

| Método | Ruta relativa | Contrato |
|---|---|---|
| GET | `tenants/{t}/branches/{b}/module-configurations` | Contratos publicados o con historial propio; flags y estado, sin valores restringidos |
| GET | `tenants/{t}/branches/{b}/module-configurations/{app}/{module}/{version}` | Esquema, configuración opcional, faltantes e historial autorizado |
| POST | Ruta anterior + `/prepare` | `{requestId,values,password}`; ausencia esperada |
| POST | Ruta anterior + `/update` | `{requestId,expectedRevision,values,password}`; siempre vuelve a DRAFT |
| POST | Ruta anterior + `/validate` | `{requestId,expectedRevision,password}`; valida datos persistidos |
| GET | `configuration-operations/{requestId}` | Recibo del actor original, sin repetir mutación |

200 confirmado; 202 UNKNOWN; 400 formato inválido; 401 sesión; 403 autoridad/CSRF/
contraseña; 404 recurso inexistente; 409 conflicto o MODULE_UNAVAILABLE;
422 requisitos o valores inválidos; 503 indisponibilidad. No se exponen errores SQL.
La lista está acotada a cien contratos; si se supera, falla explícitamente.
La ficha muestra los últimos cincuenta cambios de la versión seleccionada.

En Empresas / Sucursales, abrir una ficha y pulsar Configuración por módulos.
Sin contratos aplicables aparece un estado vacío real; no hay formularios fiscales
o POS simulados. Los campos tipados se obtienen del esquema autorizado, con guardar
borrador y validar por separado. La interfaz muestra faltantes, preparación,
publicación/habilitación y facultades sin equipararlas.

Cada mutación pide contraseña nueva, limpia el campo y evita doble envío. Solo se
recargan datos después de confirmación. UNKNOWN persiste únicamente el UUID opaco
en sessionStorage y bloquea nuevas mutaciones críticas de las secciones Core hasta
reconciliar. Se mantienen identidad visual, navegación horizontal y diseño responsive.

## Piloto y pruebas

Con JDK 21 y dependencias frontend instaladas:

```powershell
python scripts/pilot/start_connected_pilot.py --core
```

1. Entrar como OWNER indicado; crear y activar empresa; crear CASA MATRIZ.
2. Abrir Configuración por módulos y comprobar el estado vacío del catálogo real.
3. En la consola del piloto ejecutar `fixture-config <UUID_EMPRESA>` para instalar
   explícitamente un ejemplo NO PRODUCTIVO, solo en esa empresa del cluster propio.
4. Revalidar módulos. El fixture tiene etiqueta TEXT, capacidad INTEGER 1–10 y un
   booleano opcional; no son campos de LS Fiscal/POS ni reglas tributarias.
5. Guardar borrador vacío; validar y comprobar el rechazo por faltantes. Completar
   etiqueta/capacidad, guardar y validar READY. Consultar historial persistido.
6. `salir` o Ctrl+C limpia procesos, certificados y PostgreSQL desechables.

`--core --smoke-only` prueba también ese flujo por HTTPS real, primero sin fixture,
después con instalación explícita y comprobaciones SQL de persistencia y aislamiento.
No admite bases compartidas; el fixture comprueba puerto efímero y directorio propio.
El piloto instala una sola vez el ejemplo y nunca adopta o sobrescribe contratos.

```powershell
python scripts/tests/postgres_branch_configuration_contract.py
```

Ejecuta una integración focalizada PG17 con HTTPS/F04 reales: alta general sin
configuración, vacío real, fixture separado, borradores/READY, tipos/límites en Java
y SQL, permisos específicos, aislamiento/FKs, estados, versiones, escrituras
concurrentes, cambios de módulo/tenant/autoridad/cierre, auditoría/rollback,
UNKNOWN/reconciliación, FORCE RLS y DML directo denegado.
Las regresiones LFD-40/41/42/43 se ejecutan sobre V26. React prueba vacío, borradores,
requisitos, READY, edición, estados/autoridad y UNKNOWN. CI conserva tres jobs; el
job PG17 dispone de veinte minutos para la integración adicional y las regresiones.

## Límites y decisiones pendientes

Los contratos productivos y campos concretos de LS Fiscal y LS POS siguen pendientes
de definición y aprobación; esta entrega no los inventa. Futuros tipos complejos,
reglas entre campos, migración de valores entre versiones y secretos requieren
contratos/almacenes especializados nuevos. No hay administración HTTP de contratos
o facultades ni habilitación automática de aplicaciones/módulos.
LFD-45 debe imponer los requisitos exactos de las futuras operaciones empresariales,
sin usar READY como sustituto de autorización o disponibilidad. No se implementan
emisión DTE, cajas, ventas, tributación ni protección de API Keys operativas.
