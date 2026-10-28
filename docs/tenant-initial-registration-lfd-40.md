# LFD-40 / F07.T01: registro inicial de empresas

## Resultado y limites

Alta real mediante sesion HTTPS independiente de LS Core, formulario React y PostgreSQL 17. Empresa, membresia y administrador inicial se crean **PENDING**. La misma transaccion conserva una solicitud idempotente y auditoria SUCCESS. No hay activacion automatica, sucursales, aplicaciones habilitadas, modulos, perfiles, facturacion, DTE o API Keys. F07.T02/LFD-41 debe definir activacion/suspension; F07.T03/LFD-42 protegera el conjunto de administradores empresariales y su concurrencia. Esta unidad no introduce transiciones ni administracion general de miembros.

## Frontera HTTP y autorizacion

`LoginApplication.CORE` agrega cookies `__Secure-LS-CORE-SESSION` y `__Host-LS-CORE-CSRF`, con Secure, HttpOnly y SameSite=Strict, reutilizando F05. La sesion queda ligada al UUID de aplicacion configurado por servidor y distinto de Fiscal/POS; el UUID de LS Core se obtiene del catalogo PUBLISHED `ls-core`. CSRF se exige en todo POST, incluido login. El token se mantiene exclusivamente en cookie HttpOnly, nunca en URL, JSON o almacenamiento del cliente.

La habilitacion es explicita y por defecto OFF:

- `ls.login.enabled=true`, autenticacion F04 real habilitada y `ls.login.multidevice.enabled=true`.
- `ls.login.core.enabled=true`, `ls.login.core.application-id=<UUID publicado ls-core>`.
- `ls.core.registration.enabled=true`.
- Tiempo absoluto F05 sin cambios; inactividad Core `ls.login.core.idle-seconds=1800`, limite 60..86400 segundos. Vigencia, revocacion, usuario ACTIVE y publicacion se comprueban desde PostgreSQL, con actualizacion de actividad.

Solo las rutas Core explicitamente permitidas entran en su filtro; no se habilita seleccion empresarial, sesiones tenant o SQL empresarial general por tener cookie Core. Fiscal/POS conservan su configuracion de dos aplicaciones cuando Core no esta habilitado. La sesion Core no es una credencial de `OperationAdmission.session`, que sigue deshabilitado.

Cada operacion administrativa revalida una designacion global ACTIVE y usuario ACTIVE. OWNER tiene facultad de alta. Un SUPERADMIN requiere ademas una concesion de `platform.tenant_registration_grants` asociada a su designacion concreta; no basta su condicion global. Un delegado revocado pierde acceso; una nueva designacion no hereda la concesion de otra anterior. Los administradores de tenant, perfiles y membresias no conceden facultades globales.

El control de instalacion puede habilitar expresamente esa facultad a una designacion existente mediante el canal administrativo local protegido, separado del HTTP:

```powershell
psql -X -v apply=true -v designation_id=<UUID-designacion-SUPERADMIN> -f scripts/migrations/grant_tenant_registration.sql
```

El selector no es prueba de autoridad: el script exige el operador de instalacion real `postgres` en PG17 local `ls_core_dev`, no un usuario funcional. Valida SUPERADMIN/usuario ACTIVE, toma el bloqueo global F06, rechaza duplicados y deja evidencia de instalacion atomica con actor tecnico INSTALLATION_OPERATOR (no finge un actor humano reautenticado). Es configuracion excepcional de facultades aprobada por LS Soluciones, no una API ni un mecanismo de delegacion global. Los roles funcionales no tienen INSERT/UPDATE/DELETE sobre concesiones ni privilegios PostgreSQL administrativos. No se habilita una interfaz mutable de concesiones en esta entrega.

## Alta e identidad inicial

El actor se obtiene exclusivamente de la sesion Core validada dentro del servicio F05. Para crear o repetir una solicitud se resuelve su username desde PostgreSQL y se exige **otra verificacion F04 de su contrasena definitiva**, respetando vencimiento, bloqueo progresivo y estado. Se valida que la identidad verificada sea la propietaria de esa sesion. No se acepta actor UUID, username del actor ni autoridad desde el cuerpo HTTP.

El nombre se normaliza NFKC, elimina extremos y compacta espacios; debe contener 1..200 caracteres y no controles. PostgreSQL refuerza longitud, extremos y ausencia de controles. Los UUID de tenant, membresia y designacion los genera PostgreSQL; el cliente solo proporciona selector de administrador y referencia de idempotencia.

La busqueda de administrador es exacta por username, solo para autoridad global habilitada, sin directorio general, contrasenas o emails. Admite identidades ACTIVE o PENDING previamente registradas por F04. No acepta suspendidas u otros estados, no duplica usuarios ni cambia credenciales o membresias existentes.

Para una identidad nueva, usar primero el registro administrativo manual F04 existente (`ManualIdentityRegistrationCommand`), y cuando corresponda el ciclo F04 de emision temporal y cambio obligatorio. Esta unidad no crea un endpoint publico de registro ni entrega passwords por correo. Una identidad PENDING puede quedar designada para preparacion; no puede autenticarse operativamente hasta cumplir F04. No se generan passwords ni se modifica su estado durante el alta de empresa.

## Transaccion, idempotencia y auditoria

La sesion mantiene su bloqueo F05 durante la accion administrativa. El escritor propio abre READ COMMITTED, exige nueva F04 y toma el bloqueo global `(370019,1)` antes del bloqueo por referencia `hashtextextended(request_id,400022)`. Revalida autoridad tras los bloqueos y limita a diez segundos PostgreSQL la verificacion previa a la autorizacion final. El canal no recibe membresias de roles F06 ni acceso a hashes.

La insercion de `tenant.initial_registrations` tiene un guard SQL de autoridad vigente y un trigger SECURITY INVOKER que crea tenant, membresia, designacion y evento. No hay SECURITY DEFINER ni identidad GUC. Los hijos solo pueden insertarse por este rol desde el trigger y con estado PENDING. Las FK diferidas requieren que existan el tenant, la membresia del usuario y el administrador referenciados al COMMIT. Un fallo de asignacion o de auditoria revierte todo, incluyendo la solicitud.

Una referencia repetida del mismo actor y mismos datos devuelve el tenant confirmado, sin repetir inserciones o evidencia de exito. Una referencia existente con otro actor o datos distintos se rechaza; no se adopta. No se reintenta automaticamente despues de COMMIT incierto. La consulta de reconciliacion toma los mismos bloqueos y solo permite recuperar solicitudes del actor actual autorizado.

SUCCESS en `audit.events` incluye actor global, UUID tenant, UUID administrador en metadata, TENANT_REGISTER, hora PostgreSQL, resultado y correlacion=request_id. Su guard exige el trigger y la fila de solicitud correspondiente; INSERT directo no puede fabricar SUCCESS. La evidencia es inmutable y la proteccion de truncado de V21 permanece. Los rechazos se registran en una conexion separada con outcome DENIED/UNKNOWN y metadata vacia; no hay nombres, passwords, hashes, tokens o SQL. La identidad del actor en un rechazo proviene de la sesion valida, sin afirmar que la confirmacion de password tuvo exito.

CONFIRMED solo se devuelve tras COMMIT reconocido del alta. Si el COMMIT de actividad F05 falla despues, no se pierde ese resultado ya confirmado. UNKNOWN no incluye ficha ni UUID tenant como supuesto exito y exige reconciliacion. UNAVAILABLE indica que tampoco pudo confirmarse evidencia de rechazo. Se mantienen las limitaciones F06: una caida entre rollback y transaccion independiente puede perder evidencia de rechazo; el exito si es atomico.

## API y React

Todas las rutas administrativas necesitan sesion Core y autoridad vigente. Todos los POST necesitan CSRF obtenido en GET `/api/auth/core/csrf`.

| Metodo | Ruta | Contrato |
|---|---|---|
| POST | `/api/auth/core/login` | username/password F04; cookie independiente, rotacion CSRF |
| GET | `/api/auth/core/session` | sesion vigente |
| GET | `/api/auth/core/authority` | kind/canRegister; 403 sin facultad |
| POST | `/api/auth/core/initial-administrator` | username exacto; id/displayName/status o 404 neutro |
| POST | `/api/auth/core/registrations` | requestId, name, administratorId, password nueva del actor |
| GET | `/api/auth/core/registrations/{requestId}` | reconciliacion/ficha real; 404 si no disponible para ese actor |
| POST | `/api/auth/core/logout` | revocacion persistente y eliminacion de cookie |

Resultado de alta: `{outcome,correlation,tenant}`. CONFIRMED (200) incluye `{id,name,status,administratorId,createdAt,requestId}`. UNKNOWN (202) no contiene tenant; DENIED (403) y UNAVAILABLE (503) tampoco. Sin sesion 401, CSRF invalido 403. Errores de cuerpo 400 neutros. Una cookie Fiscal/POS, aun renombrada, se rechaza.

`ConnectedCore`/`coreApi` usan rutas relativas, `credentials: same-origin` y CSRF nuevo por POST, incluido despues del login. React muestra el formulario solo despues de comprobar sesion y autorizacion. Reutiliza marca, estilos, navegacion horizontal y responsive existentes. No usa demo.ts. Busca administrador, exige confirmacion y otra password, impide doble envio y utiliza la ficha devuelta por PostgreSQL. UNKNOWN o corte de red bloquea otra alta y ofrece reconciliacion. Solo guarda en sessionStorage una referencia opaca de solicitud pendiente para sobrevivir a un refresh; no guarda identidad, datos de tenant, password ni token. La referencia no es autoridad y se elimina al confirmar. Antes de cerrar una solicitud incierta, conservar su referencia.

El acceso conectado se activa con `VITE_CORE_REGISTRATION=true` y URL `/core`. El modo DEMO permanece separado; la pantalla Fiscal conserva su ruta anterior. El build normal sigue con la funcionalidad conectada deshabilitada hasta configuracion explicita. No se implementa el dashboard completo F13.

## Migracion y privilegios

V22 es la unica migracion nueva; V1-V21 permanecen intactas. Se reutilizan las tablas tenant e identity. Las dos tablas pequenas nuevas son el registro idempotente y la concesion especifica de facultad. Ambas tienen FORCE RLS. No hay semillas de empresas/personas ni roles superusuario/BYPASSRLS/DDL funcionales.

Antes de V22 el operador local prepara mediante `prepare_tenant_registration.sql` dos LOGINs SCRAM separados, sin adopcion, membresias ni ownership. `ls_core_tenant_registrar_dev` (limite 3 conexiones) solo lee columnas minimas de identidad/designacion y solicitudes, e inserta registro y sus hijos PENDING/auditoria. No tiene UPDATE/DELETE/TRUNCATE, hashes, sucursales, perfiles, modulos ni operaciones generales. `ls_core_tenant_registration_log_dev` (limite 2) solo inserta rechazos. La cuenta de sesiones no obtiene privilegios globales. La cuenta HTTP dispone de conexiones especificas, no de privilegios amplios en su identidad SQL general.

Configurar familias `LS_TENANT_REGISTRATION_DB_*` y `LS_TENANT_REGISTRATION_LOG_DB_*` con HOST/PORT/NAME/PASSWORD, mismo destino confiable que `LS_AUTHENTICATION_DB_*`. Los roles son fijos del servidor y sus secretos solo se inyectan al backend autorizado. No usar bases compartidas ni registrar secretos en Git, argv, logs o VITE variables.

## Recorrido local de Windows

Preparar JDK 21, Python, PostgreSQL 17 binaries y `npm ci` en frontend como en [el piloto existente](frontend-connected-pilot.md). No se usa Docker ni 5432/55432.

```powershell
$env:JAVA_HOME='C:\Program Files\Java\jdk-21.0.10'
$env:PATH="$env:JAVA_HOME\bin;$env:PATH"
python scripts/pilot/start_connected_pilot.py --core
```

1. Elegir la password local solicitada (sin valor predeterminado). El launcher crea cluster PG17 propio, V1-V22, certificados HTTPS temporales confiables y las cuentas aleatorias del piloto.
2. `--core` autoriza expresamente, solo en este cluster desechable, bootstrap F04 de una cuenta OWNER separada de la cuenta Fiscal; sella el bootstrap. No es un inicializador Spring ni una semilla de migracion. El modo Fiscal sin `--core` no crea un propietario.
3. Abrir `https://127.0.0.1:5174/core` en Chrome. La terminal muestra el username OWNER y el username del administrador inicial PENDING. Usar la password elegida para el OWNER; la identidad inicial no recibe password predeterminada.
4. Ingresar, buscar el username inicial, introducir nombre de empresa y confirmar nuevamente la password OWNER. Confirmar la creacion.
5. Ver nombre normalizado, UUID, fecha y estado PENDIENTE reales. No hay acceso operativo, aplicaciones o sucursales habilitadas.
6. Cerrar sesion. Fiscal sigue disponible en `https://127.0.0.1:5174/` con su cuenta local distinta. No hay SSO.
7. Ctrl+C detiene procesos, retira la CA propia y elimina el cluster temporal. No reutiliza servidores o credenciales de otro ambiente.

Comprobacion nueva del proxy/flujo Core, sin ejecutar metodos historicos:

```powershell
python scripts/pilot/start_connected_pilot.py --core --smoke-only
```

## Evidencia focalizada

Un metodo nuevo `TenantRegistrationPostgresTest.coreHttpsRegistrationAtomicPendingAdministratorIdempotencyAndIsolation` usa HTTPS y PG17 desechable con F04 real: OWNER, SUPERADMIN con/sin facultad, denegacion empresarial, aislamiento de cookies, CSRF, membresia/administrador inicial PENDING, no duplicacion de identidad, tenant sin acceso operativo, rollback de asignacion/auditoria, idempotencia, revocacion y FORCE RLS. No se invocan manualmente metodos historicos. Una prueba React unica verifica contrato real simulado, envio duplicado, ficha solo confirmada, UNKNOWN/reconciliacion y sesion expirada.

La integracion focalizada paso (un metodo, cero fallos/errores/omisiones). Se corrigio una infraccion de formato de Checkstyle sin repetir esa integracion; Checkstyle aislado paso. La prueba React paso tras corregir hooks y codificacion de textos de Windows; lint, TypeScript y build pasaron. El CI estandar conserva sus tres jobs e incluye la nueva integracion.

El piloto `--core --smoke-only` alcanzo PILOT READY y verifico entrada React por HTTPS, transformacion del formulario, login Core, facultad global, alta PENDING real, reconciliacion del UUID persistido y logout. La CA, procesos y cluster propios se retiraron al terminar. Es evidencia HTTP/proxy real; no se afirma un recorrido automatizado de clics en Chrome.
