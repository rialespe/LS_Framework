# Propietario de plataforma — LFD-37 / F06.T01

LS Soluciones SpA es el propietario de la plataforma. Una cuenta global existente,
autorizada por la empresa durante la instalación, queda vinculada a la única
designación `OWNER`. El nombre visible o username no demuestra propiedad.
Se reutilizan `identity.users`, credenciales definitivas F04 y
`platform.administrators`; no hay otra identidad, tenant o cuenta propietaria sembrada.

## Canales y alcance

| Canal | Principal PostgreSQL fijo | Capacidad |
| --- | --- | --- |
| Autenticación | `ls_core_password_authenticator_dev` | F04 existente, con estados, KDF y bloqueo progresivo |
| Instalación excepcional | `ls_core_platform_bootstrap_dev` | SELECT de metadatos mínimos e INSERT inicial de OWNER; límite de una conexión |
| Lectura interna | `ls_core_platform_reader_dev` | SELECT por columnas de condición global y usuarios ACTIVE; límite de tres conexiones |

Ninguno de los nuevos LOGIN tiene ownership, membresías, superusuario, BYPASSRLS,
CREATE de esquema/base ni credenciales de migrador. El instalador no lee hashes.
No existe un rol escritor para delegaciones. V19 conserva FORCE RLS.

La posesión de la credencial SCRAM del canal de instalación es una autoridad
administrativa excepcional: solo el operador de instalación autorizado por
LS Soluciones SpA debe custodiarla. No se entrega a usuarios registrados, al frontend,
al servicio HTTP ni al operador empresarial. Las variables de entorno transportan
configuración/secretos de ese operador; por sí mismas no sustituyen la autenticación
PostgreSQL ni la verificación F04 de la cuenta candidata. No usar transcripciones de
terminal o herramientas que registren secretos al realizar la instalación.

## Bootstrap explícito

Esta unidad no ejecuta cambios en servidores compartidos. Los comandos siguientes
documentan una instalación local controlada en PostgreSQL 17, `ls_core_dev`, sobre
un endpoint elegido y autorizado por su operador. No apuntarlos a otros ambientes.
En las pruebas y el piloto se utilizan exclusivamente instancias desechables.

1. Preparar V1–V18 con sus canales existentes. La cuenta candidata debe estar creada
   por el registrador y haber completado el ciclo F04: usuario ACTIVE, contraseña
   definitiva vigente, sin obligación de cambio ni bloqueo vigente. Este proceso no
   crea ni restablece su contraseña. Obtener fuera del sistema la autorización de
   LS Soluciones SpA para que esa cuenta represente al propietario.
2. El operador PostgreSQL local autorizado ejecuta con `psql -X -W`, conexión
   suministrada por separado y `-v apply=true`, el archivo
   `scripts/migrations/prepare_platform_channels.sql`. Las dos contraseñas SCRAM se
   introducen mediante los intercambios interactivos `\password`, nunca en argv/SQL.
   El script rehúsa adoptar roles preexistentes o reparar permisos incompatibles.
3. Aplicar `V19__platform_owner_authorization.sql` con el migrador restringido y
   validar Flyway. V19 exige que `platform.administrators` esté vacía: si contiene
   designaciones previas, falla sin adoptarlas ni borrarlas. No usar repair/baseline.
4. En una terminal privada con JDK 21, configurar los canales F04 e instalación
   hacia exactamente el mismo host, puerto y base. Las identidades LOGIN están
   fijadas en código y verificadas desde PostgreSQL; no hay username técnico elegible.

Ejemplo de configuración, sustituyendo únicamente el puerto local autorizado:

```powershell
$env:JAVA_HOME = 'C:\Program Files\Java\jdk-21.0.10' # Ajustar al JDK 21 instalado
$env:PATH = "$env:JAVA_HOME\bin;$env:PATH"
$env:LS_AUTHENTICATION_DB_HOST = '127.0.0.1'
$env:LS_AUTHENTICATION_DB_PORT = '<puerto local autorizado>'
$env:LS_AUTHENTICATION_DB_NAME = 'ls_core_dev'
$env:LS_PLATFORM_BOOTSTRAP_DB_HOST = $env:LS_AUTHENTICATION_DB_HOST
$env:LS_PLATFORM_BOOTSTRAP_DB_PORT = $env:LS_AUTHENTICATION_DB_PORT
$env:LS_PLATFORM_BOOTSTRAP_DB_NAME = $env:LS_AUTHENTICATION_DB_NAME
try {
    $secret = Read-Host 'Contraseña del canal F04 autorizado' -AsSecureString
    $env:LS_AUTHENTICATION_DB_PASSWORD = [Net.NetworkCredential]::new('', $secret).Password
    $secret = Read-Host 'Contraseña del canal excepcional de instalación' -AsSecureString
    $env:LS_PLATFORM_BOOTSTRAP_DB_PASSWORD = [Net.NetworkCredential]::new('', $secret).Password
    .\backend\mvnw.cmd -B -ntp -f backend/pom.xml -DskipTests package
    if ($LASTEXITCODE -ne 0) { throw 'No se generó el ejecutable' }
    java '-Dloader.main=com.lssoluciones.lscore.platform.PlatformOwnerBootstrapCommand' `
        -cp backend/target/ls-core-0.0.1-SNAPSHOT.jar `
        org.springframework.boot.loader.launch.PropertiesLauncher --initialize-owner
    if ($LASTEXITCODE -ne 0) { throw 'Bootstrap no confirmado' }
} finally {
    Remove-Item Env:LS_AUTHENTICATION_DB_PASSWORD,Env:LS_PLATFORM_BOOTSTRAP_DB_PASSWORD -ErrorAction SilentlyContinue
    Remove-Variable secret -ErrorAction SilentlyContinue
}
```

5. Escribir exactamente `INITIALIZE LS SOLUCIONES OWNER` cuando se solicite.
   Introducir el username existente y su contraseña definitiva, esta última sin eco.
   El comando no inicia Spring Boot ni escucha HTTP. Usa `JdbcPasswordAuthentication`
   real; no acepta UUID, GUC, cookie, encabezado ni una prueba de identidad fabricada.
6. Solo después del commit reconocido y del cierre físico se confirma la instalación.
   El bootstrap comprueba ausencia de toda designación y vuelve a comprobar estado
   activo y metadatos de credenciales en PostgreSQL. Un fallo no imprime causas SQL,
   credenciales ni identidad. Un segundo candidato o una repetición se deniega.
7. Cerrar el proceso de bootstrap. El operador PostgreSQL local ejecuta
   `scripts/migrations/seal_platform_bootstrap.sql`, con `psql -X -W`, conexión
   separada y `-v apply=true`. Este script exige un OWNER activo y ninguna conexión
   restante del canal; establece `NOLOGIN` y retira CONNECT. No mata otras sesiones.
   Retirar la credencial excepcional de los secretos operativos del servicio.

El sellado evita reconexiones. Incluso antes de sellar, la unicidad permanente y los
guards impiden repetir la instalación, sustituir al propietario o usar ese LOGIN
para delegar. No hay reinicio/reset de bootstrap en esta entrega.
La autoridad del DBA para alterar DDL, desactivar triggers o reemplazar una base es
una frontera administrativa distinta; nunca se otorga a los servicios funcionales.

## Autorización y propuestas internas

`JdbcPlatformAuthorization.evaluate(username,password)` verifica F04 y lee únicamente
la condición de esa identidad: `OWNER`, `SUPERADMIN` o `NONE`. Requiere usuario ACTIVE,
designación ACTIVE y sin revocación. Credenciales inválidas, un canal incorrecto o
datos no disponibles dan `NONE`. No se acepta un UUID como identidad autenticada.
El resultado es información, no una `AuthenticatedIdentity`, contexto tenant,
admisión SQL, sesión global o recibo reutilizable de reautenticación reciente.

El bean lector está OFF por defecto. Para composición interna controlada se requiere
`ls.platform.authorization.enabled=true`, F04 activado y
`LS_PLATFORM_DB_HOST/PORT/NAME/PASSWORD`, con mismo destino que F04 y el rol lector fijo.
No se configura ese canal en el HTTP del piloto.

Solo OWNER verificado puede usar las lecturas `delegates` y `designation`, o preparar
una propuesta `DESIGNATE_SUPERADMIN`/`REVOKE_SUPERADMIN`. La preparación comprueba
usuario existente ACTIVE, estado actual compatible y que el objetivo no sea OWNER.
El listado se limita a 100 delegaciones. Un usuario empresarial no puede enumerar
esas identidades. Los perfiles, roles, permisos y membresías de tenant no se consultan
para conceder autoridad global. Ser OWNER no crea membresías ni acceso a sucursales.

`execute(Proposal)` siempre falla con `MutationDisabled`, incluso para OWNER y aun
si se fabrica una propuesta. No hay flag que lo habilite. No modifica credenciales,
usuarios ni permisos. Las propuestas son descriptivas y deberán revalidarse dentro
del futuro coordinador transaccional; no son una autorización para ejecutar cambios.
No se crean SUPERADMIN en esta unidad: su lectura está preparada para designaciones
válidas cuando las historias siguientes habiliten un canal protegido.

## Protecciones efectivas V19

- Un solo OWNER, también contando cualquier historia; solo puede estar ACTIVE y
  carece de marcas de revocación o de otorgante delegado.
- Una sola designación activa por identidad; estados y marcas compatibles para
  SUPERADMIN, sin modificar los vocabularios empresariales.
- Columna generada `protected_user_status='ACTIVE'` y FK compuesta a
  `identity.users(id,status)`: impiden borrar, cambiar de identidad o suspender a
  un usuario designado. No duplican la identidad y funcionan aunque RLS oculte filas.
  Esta protección conservadora incluye registros de delegación revocada; cualquier
  futura relajación requiere una política explícita, no una mutación ordinaria.
- Guards SECURITY INVOKER, sin lectura de tablas ni SECURITY DEFINER, bloquean
  UPDATE, DELETE y TRUNCATE de designaciones. INSERT solo acepta OWNER por el canal
  excepcional y además exige las comprobaciones RLS/credenciales y unicidad.
- No se conceden UPDATE/DELETE/TRUNCATE a servicios de plataforma. Los roles de
  sesión/tenant tampoco reciben privilegios globales. FORCE RLS permanece activo.

Antes del bootstrap hay una fase de instalación sin propietario y sin administración
global habilitada. Después, las protecciones conservan al menos el OWNER ACTIVE;
no se afirma haber implementado el algoritmo completo de concurrencia/último
superadministrador de LFD-38. Expiración o bloqueo de contraseña siguen sujetos a
F04; la designación no permite eludir esas políticas de acceso.

## Conexión futura y piloto

No existe aún un contexto HTTP confiable del portal LS Core: las aplicaciones HTTP
actuales son Fiscal y POS. Por eso no se añaden endpoints ni interfaz global. Sus
cookies, UUID y permisos React no sirven al lector de plataforma. El futuro adaptador
de LS Core deberá resolver su aplicación e identidad por un canal confiable antes de
consultar condición/autoridad; no reutilizar cookies de Fiscal como autoridad global.

El piloto conserva su recorrido HTTPS y cuenta empresarial desechable. Solo se añade
el aprovisionamiento de canales necesario para aplicar V19; no se crea OWNER, SUPERADMIN
ni cuenta global predeterminada. Las credenciales de instalación no se heredan por
Spring/Vite. La designación administrativa de Tenant 10 sigue siendo exclusivamente
empresarial y no adquiere condición global.

## Validación y dependencias

```powershell
# JDK 21 y binarios PG17. Sin parámetros de servidores compartidos.
python scripts/tests/postgres_platform_owner_contract.py
```

Un único método nuevo `PlatformOwnerPostgresTest` reutiliza el harness PG17 y F04:
bootstrap válido, rechazos, protección SQL, separación tenant/global, propuestas
bloqueadas, privilegios y FORCE RLS. El runner sella el canal y comprueba que no puede
reconectar. Maven `verify` incluye Checkstyle. No invoca métodos históricos F03–F05.

LFD-38 debe implementar la concurrencia y conservación del último superadministrador
en el futuro canal mutable. LFD-39 debe aportar reautenticación reciente y auditoría
de cambios críticos. Hasta disponer de ambas no hay escrituras de delegación, rutas
mutables, CLI alternativo ni interfaz privilegiada. API Keys y `OperationAdmission.session`
siguen deshabilitados para SQL empresarial. No se cambia Jira ni se cierran otras historias.
