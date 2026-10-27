# LS Fiscal: primer frontend conectado local

Adelanto técnico de F12, sin cerrar F12 ni cambiar Jira. El modo predeterminado sigue
siendo DEMO. El piloto usa F04/V16–V18, incluye sesiones multidevice y revocación
limitada por tenant; no habilita SQL empresarial, API Keys ni administración global.

## Preparar y abrir en Windows

Requisitos: JDK 21, Node 24.14.x/npm 11, Python 3 y binarios PostgreSQL 17 instalados.
Ejecutar en PowerShell del usuario que abrirá Edge o Chrome. Se utiliza el almacén de
certificados CurrentUser, no el del equipo. El puerto 5174 debe estar libre; el servidor
de catálogo/demostración puede continuar en 5173.

Desde la raíz del repositorio:

```powershell
$env:JAVA_HOME = 'C:\Program Files\Java\jdk-21.0.10' # Ajustar a tu JDK 21 instalado
$env:PATH = "$env:JAVA_HOME\bin;$env:PATH"
$env:LS_PG_BIN = 'C:\Program Files\PostgreSQL\17\bin'
Set-Location frontend
npm.cmd ci
Set-Location ..
python scripts/pilot/start_connected_pilot.py
```

1. Introducir y confirmar una nueva contraseña local, sin eco: 8..1024 caracteres,
   con letra, número y símbolo. No hay contraseña predeterminada ni credencial en Git.
2. Esperar `PILOT READY`. Copiar el usuario aleatorio que imprime esa línea y usar la
   contraseña elegida. Mantener la terminal abierta mientras se utiliza el piloto.
3. Abrir **https://127.0.0.1:5174/** en Edge o Chrome del mismo usuario Windows.
   El certificado incluye 127.0.0.1 y localhost. No saltar advertencias TLS: si una
   política corporativa impide confiar la CA, resolverla antes de usar el entorno.
   Firefox puede requerir el uso del almacén de certificados de Windows.
4. Ingresar usuario y contraseña. El formulario obtiene CSRF, hace login y renueva
   CSRF; el navegador conserva la cookie Secure/HttpOnly. No copiar cookies ni tokens.
5. Elegir `Tenant 10` y `Branch 11`; pulsar **Confirmar contexto**. Los nombres son
   los de la fixture local reutilizada, leídos realmente desde PostgreSQL por la API.
6. Ver los permisos `p60`, `p61`, `p69` y el módulo `m50`. Cambiar a `Branch 12` y
   confirmar: solo queda `p62`, con módulo `m51`. Permisos y módulos anteriores
   desaparecen al iniciar el cambio, antes de recibir la nueva selección.
7. Elegir `Tenant 20`, `Branch 21` y confirmar: aparece `p63`. Actualizar la página
   revalida sesión y contexto persistido. **Actualizar sesión** permite reconsultar
   cambios de autorización sin recargar la página.
8. Pulsar **Cerrar sesión**. Se invoca logout con CSRF, se revoca solo esa sesión y
   desaparecen empresa, sucursal, permisos y módulos del estado React.
9. Pulsar **Ctrl+C** en la terminal para detener Vite, backend y PostgreSQL, retirar
   la CA temporal y eliminar datos, claves y logs temporales. Otro arranque crea otra
   cuenta y base. No hay datos de desarrollo en una base compartida.

## Preparación y arranque automatizados

El lanzador reutiliza `disposable_cluster`, `prepare_fixture` y los scripts de roles
existentes. No admite URL, credenciales ni directorio de un PostgreSQL existente.
Inicializa PG17 nuevo en `%TEMP%`, en puerto efímero distinto de 5432 y 55432, y aplica
y valida V1–V18 como migrador restringido. El administrador solo prepara la fixture.

Compila con `-DskipTests package dependency:build-classpath`, sin invocar integraciones
históricas. `PilotAccount.java` usa el registrador y ciclo de contraseña de F04 para
crear y activar la cuenta; no escribe hashes propios ni introduce otro verificador.
Reutiliza `session-context-fixture.sql` de V17 para empresas, membresías, perfiles,
roles, permisos y módulos exclusivamente del entorno desechable.

Configura solo para los procesos hijos:

- `ls.identity.credentials.enabled`, `ls.login.enabled`, `ls.login.contexts.enabled`
  y `ls.login.multidevice.enabled`: true.
- Fiscal `35000000-0000-0000-0000-000000000001` y POS
  `35000000-0000-0000-0000-000000000002`, aplicaciones PUBLISHED de la fixture.
- Canales F04/sesiones hacia el mismo PG17 efímero con los roles fijos
  `ls_core_password_authenticator_dev` y `ls_core_session_manager_dev`.
- Spring Boot HTTPS en loopback, puerto efímero, PKCS12 temporal y contraseña por
  entorno. No activa el perfil general postgres ni operaciones empresariales.
- Vite con `VITE_CONNECTED_PILOT=true`, `VITE_CATALOG_ENABLED=false`, puerto 5174,
  `LS_PILOT_TLS_PFX`, `LS_PILOT_TLS_PASSWORD`, `LS_PILOT_TLS_CA` y
  `LS_PILOT_BACKEND_URL=https://127.0.0.1:<puerto-backend>`.

Vite sirve React con HTTPS y envía `/api/auth/*` al backend usando un `https.Agent`
que confía en la CA suministrada y valida el certificado. No utiliza `secure:false`,
`rejectUnauthorized:false`, CORS ni bypass TLS global. Las contraseñas de
aprovisionamiento no se heredan por las aplicaciones; Vite tampoco recibe contraseñas
PostgreSQL. Nada sensible se expone como `VITE_*`. React usa rutas relativas y
`credentials: 'same-origin'`.

`New-PilotCertificate.ps1` crea una CA temporal y certificado de servidor con SAN
localhost/127.0.0.1. Instala únicamente la CA pública en `CurrentUser\Root`, exporta
la clave de servidor cifrada al directorio temporal y elimina las claves del almacén
My. Al cerrar retira exactamente su CA. La hoja vence en un día y se renueva en cada
arranque. No se versionan certificados, claves ni contraseñas.

Un cierre forzado de Python o del equipo puede impedir la limpieza. La ruta temporal
se imprime al iniciar. Para retirar esa CA concreta y detener exclusivamente ese PGDATA:

```powershell
powershell.exe -NoProfile -File scripts/pilot/New-PilotCertificate.ps1 -Directory '<ruta temporal impresa>' -RemoveTrust
& "$env:LS_PG_BIN\pg_ctl.exe" -D '<ruta temporal impresa>\data' -m fast -w stop
```

## DEMO y alcance de validación

Sin `VITE_CONNECTED_PILOT=true`, `npm.cmd run dev` conserva DEMO en
http://127.0.0.1:5173/. El proxy anterior de catálogo sigue siendo optativo con
`VITE_CATALOG_ENABLED=true`. Las pantallas DEMO, métricas ficticias y catálogo
independiente no forman parte del piloto autenticado. El modo conectado solo está
disponible en desarrollo; `vite build` conserva la salida DEMO predeterminada.

La navegación solo contiene módulos devueltos por el backend, sin rutas inventadas;
sus pantallas figuran como no disponibles. Los permisos son información contextual,
no autoridad de ejecución. Un 401 limpia el estado y vuelve al acceso. Un 403 descarta
el contexto y exige revalidar sesión y empresas antes de elegir nuevamente. Si falla
logout se ocultan los datos y se ofrece reintento, sin afirmar que la sesión se cerró.

El único método nuevo de Vitest/RTL, `ConnectedFiscal.test.tsx`, valida React con HTTP
simulado y no acredita conexión real. Para comprobar además el nuevo camino de proxy
y preparación local, sin navegador ni ejecución de JUnit históricos:

```powershell
python scripts/pilot/start_connected_pilot.py --smoke-only
```

Ese modo crea una contraseña aleatoria que no imprime, comprueba React por HTTPS,
login, CSRF, empresas, contexto, permisos, navegación y logout mediante Vite contra
Spring/PG17 reales, y limpia al terminar. No sustituye la revisión visual manual del
recorrido anterior. Las integraciones existentes siguen siendo la evidencia de
seguridad del backend; el piloto no acredita uso productivo ni completa F12.

El recorrido de **Mis sesiones**, revocación y administración limitada a Tenant 10
está en [sessions-multidevice-lfd-36.md](sessions-multidevice-lfd-36.md).
