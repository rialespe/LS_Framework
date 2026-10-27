# Login HTTP por aplicación — LFD-34 / F05.T01

El módulo permanece deshabilitado por defecto. Reutiliza `PasswordAuthentication`
transaccional de F04, incluido su bloqueo progresivo. No recibe una identidad
autenticada del cliente ni habilita `OperationAdmission.session` para SQL empresarial.

## Activación explícita

Después de V15, un administrador debe aprovisionar el rol dedicado con
`scripts/migrations/prepare_session_manager.sql` (`psql -X -v apply=true -f ...`,
conexión suministrada por separado y contraseña SCRAM introducida interactivamente).
El script rechaza roles preexistentes y no adopta ni repara permisos. Luego el
migrador restringido aplica **V16__application_http_sessions.sql**; V1–V15 no cambian.
No se ejecutan migraciones al arrancar el backend.

Configurar externamente:

- `ls.identity.credentials.enabled=true` y el canal F04 existente
  `LS_AUTHENTICATION_DB_HOST/PORT/NAME/PASSWORD`.
- `ls.login.enabled=true`.
- `ls.login.fiscal.application-id` y `ls.login.pos.application-id`: UUID distintos,
  correspondientes a aplicaciones globales `PUBLISHED`.
- `LS_LOGIN_DB_HOST/PORT/NAME/PASSWORD`: mismo servidor, puerto y base que F04;
  contraseña del rol fijo `ls_core_session_manager_dev`, sin usuario configurable.
- `ls.login.session-seconds`: 28800 por defecto, entre 60 y 86400 segundos.
- HTTPS en el límite servlet y certificado apropiado para el despliegue. El módulo
  rechaza peticiones que el servidor no identifica como seguras.

No usar el migrador ni un superusuario como identidad funcional. El rol de sesiones
solo lee estado de usuarios/aplicaciones y emite, consulta o revoca sesiones;
no obtiene permisos empresariales ni escribe contextos de sesión. Se conserva FORCE RLS.

## Contrato HTTP

Para `{application}` igual a `fiscal` o `pos`, el servidor resuelve el UUID desde
su configuración. El cliente debe conservar las cookies y operar en el mismo origen.

1. `GET /api/auth/{application}/csrf` devuelve `csrfToken` y `headerName`, con
   `Cache-Control: no-store`. Conservar su cookie CSRF y enviar el token de respuesta
   en `X-CSRF-TOKEN` para cada operación que cambia estado.
2. `POST /api/auth/{application}/login`, JSON con `username` y `password`: responde
   204 con cookie de sesión después del commit; las denegaciones de autenticación
   responden 401 con `Authentication unavailable`. CSRF ausente o inválido responde 403.
3. `GET /api/auth/{application}/session`: responde 200 con `authenticated: true`
   únicamente si PostgreSQL confirma una sesión vigente, no revocada, del usuario
   activo y de la aplicación publicada correspondiente; en otro caso responde 401.
4. `POST /api/auth/{application}/logout`, con CSRF válido: revoca esa sesión en
   PostgreSQL y responde 204 eliminando su cookie con el mismo nombre y ámbito.
   Una cookie revocada no vuelve a autenticar y las otras sesiones siguen vigentes.

Login y logout invalidan la cookie CSRF: obtener otro token con el GET inicial antes
de la siguiente mutación. El filtro CSRF protege todos los métodos que cambian estado
bajo `/api/auth/*`; no se deshabilita CSRF globalmente.

Las cookies de sesión son `__Secure-LS-FISCAL-SESSION` y `__Secure-LS-POS-SESSION`,
con ámbito `/api/auth/fiscal` y `/api/auth/pos`, respectivamente, sin Domain,
`Secure`, `HttpOnly` y `SameSite=Strict`. Las cookies CSRF tienen nombres propios
por aplicación, prefijo `__Host-`, ámbito `/` y los mismos atributos de seguridad.
Los secretos de sesión tienen 256 bits aleatorios; PostgreSQL conserva exclusivamente
su digest SHA-256. No se envían tokens de sesión en JSON ni URL.

## Verificación focalizada

Con JDK 21 y binarios PostgreSQL 17 disponibles:

```powershell
python scripts/tests/postgres_application_login_contract.py
```

Reutiliza un único método `ApplicationLoginPostgresTest` por HTTPS real, con
certificado temporal confiado solo por su cliente y PostgreSQL desechable en puerto
efímero distinto de 5432 y 55432. Cubre emisión y digest, aislamiento entre aplicaciones,
CSRF, contraseña incorrecta mediante F04, logout, revocación persistente, rechazo de
reutilización y conservación de otras sesiones. Maven ejecuta `verify`, incluido
Checkstyle, en esa misma ejecución. El contrato también se integra en el job estándar
de migraciones; el job backend omite esta prueba cuando no existe la fixture explícita.

La activación y validación en servidores compartidos no se ejecutan como parte de
esta entrega. La administración avanzada multidevice queda para F05.T03; la admisión
de sesiones para SQL empresarial continúa pendiente y deshabilitada.
