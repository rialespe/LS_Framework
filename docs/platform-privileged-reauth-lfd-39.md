# LFD-39 / F06.T03: reautenticacion y auditoria privilegiada

## Canal disponible y frontera de confianza

Se implementa un ejecutor interno real `JdbcPlatformExecutor`, accesible mediante `PlatformPrivilegedCommand` desde una consola controlada. No es un bean HTTP ni utiliza Spring Boot para iniciar un servidor. El portal LS Core independiente aun no existe: **no hay endpoints mutables globales**. Fiscal/POS, sus cookies, perfiles, contextos, roles SQL y React no obtienen autoridad de plataforma. El metodo legado `JdbcPlatformAuthorization.execute(Proposal)` sigue rechazando; una propuesta o un resultado previo READY nunca ejecuta un cambio.

La autoridad excepcional de instalacion pertenece al operador autorizado de LS Soluciones SpA que controla el servidor PostgreSQL local y la cuenta del sistema operativo del proceso de consola. Los secretos de los canales se entregan exclusivamente a ese proceso, no al backend HTTP/Vite, usuarios finales, scripts SQL empresariales o bases compartidas. El principal escritor es una identidad tecnica de confianza, no prueba por si solo una identidad OWNER. F04 prueba la identidad personal y PostgreSQL revalida su designacion. Un operador con control de DDL o de los secretos del escritor esta dentro de la frontera administrativa de confianza; no se afirma resistir su compromiso. No hay SECURITY DEFINER, BYPASSRLS, propietario de tablas o identidad declarada por GUC.

## Instalacion y activacion explicitas

Conservar el procedimiento de [bootstrap de LFD-37](platform-owner-lfd-37.md), incluida su cuenta existente, definitiva y ACTIVE y el sellado del bootstrap. No se modifica, reactiva ni automatiza ese procedimiento. V1-V20 permanecen intactas.

Antes de V21, el operador local autorizado ejecuta con psql y conexion administrativa separada (host loopback, puerto autorizado, `ls_core_dev`, PostgreSQL 17):

```powershell
psql -X -v apply=true -f scripts/migrations/prepare_platform_audit.sql
```

El script crea solamente `ls_core_platform_security_log_dev` NOLOGIN, sin password, sin membresias y sin CONNECT. El migrador restringido aplica `V21__platform_privileged_reauthentication_audit.sql`. No se crean personas, designaciones ni secretos. El piloto aplica este prerrequisito/V21 pero **no activa canales ni crea OWNER/SUPERADMIN**.

Cuando el operador haya protegido el proceso interno y su almacenamiento de secretos, puede activar ambos canales expresamente:

```powershell
psql -X -v apply=true -f scripts/migrations/activate_platform_executor.sql
```

Exige roles dormidos, aislados y sin sesiones previas; no adopta roles ni restablece contrasenas existentes. Usa `\password` interactivo SCRAM, nunca secretos en SQL, argumentos o Git. Se activan LOGIN y CONNECT solo a `ls_core_platform_mutator_dev` y `ls_core_platform_security_log_dev`, limite dos conexiones cada uno, NOINHERIT y sin membresias. No hay un parametro HTTP o bandera Spring que realice la activacion.

Configurar exclusivamente en el entorno protegido del proceso de consola las familias `LS_AUTHENTICATION_DB_*`, `LS_PLATFORM_EXECUTOR_DB_*` y `LS_PLATFORM_SECURITY_LOG_DB_*`, con sufijos HOST, PORT, NAME y PASSWORD. Las tres conexiones deben usar el mismo host, puerto y base; los usuarios son fijos (autenticador F04, mutator, security_log). Introducir cada password de canal desde el almacen de secretos del sistema o con `Read-Host -AsSecureString` y un mecanismo de inyeccion protegido; no escribir valores literales en historial, archivos de repositorio o comandos. Retirar las variables del proceso al terminar. Para una instalacion remota se requiere ademas una politica TLS y distribucion de secretos aprobada; los scripts de esta unidad solo admiten instalacion local.

Con JDK 21 y el jar construido:

```powershell
java -Dloader.main=com.lssoluciones.lscore.platform.PlatformPrivilegedCommand -cp backend/target/ls-core-0.0.1-SNAPSHOT.jar org.springframework.boot.loader.launch.PropertiesLauncher --designate-superadmin <UUID-objetivo>
java -Dloader.main=com.lssoluciones.lscore.platform.PlatformPrivilegedCommand -cp backend/target/ls-core-0.0.1-SNAPSHOT.jar org.springframework.boot.loader.launch.PropertiesLauncher --revoke-superadmin <UUID-objetivo>
```

Cada proceso ejecuta una sola accion, solicita username OWNER y nueva contrasena definitiva con `Console.readPassword` sin eco. El UUID es exclusivamente el selector del destinatario. La contrasena personal no se recibe mediante env/argv y no se conserva entre operaciones. El proceso limpia el arreglo de entrada; el ejecutor limpia su copia privada. Nunca se imprimen identidad, password, hashes, SQL o tokens; solo resultado, correlacion y confirmacion de evidencia.

Tras cerrar las conexiones propias, el operador puede retirar los dos canales:

```powershell
psql -X -v apply=true -f scripts/migrations/deactivate_platform_executor.sql
```

El script exige ausencia de conexiones; retira LOGIN, password y CONNECT sin terminar sesiones ajenas ni tocar identidades/membresias. La instancia HTTP no necesita ninguno de esos secretos y no debe recibirlos.

## Ejecucion por operacion

1. Conexion fisica de escritor restringido, transaccion READ COMMITTED, timeouts acotados y validacion del principal PostgreSQL real, atributos, membresias, propiedad y base.
2. `PlatformMutationCoordination.inspect` llama nuevamente al `JdbcPasswordAuthentication` concreto. F04 conserva sus transacciones de intentos, bloqueo progresivo, credenciales definitivas, expiracion y usuario ACTIVE. Un intento incorrecto se confirma en F04 aunque la operacion global se rechace.
3. La coordinacion adquiere el advisory lock de V20 `(370019,1)`, bloquea designaciones por id en orden estable y vuelve a consultar OWNER, estado objetivo y numero de delegados. El resultado solo sirve dentro de esa transaccion. No se aceptan autoridad UUID, cookies, prueba cacheada o propuestas de otra llamada.
4. El ejecutor exige que hayan transcurrido como maximo diez segundos PostgreSQL desde antes de la verificacion hasta despues de adquirir los bloqueos. No existe token reutilizable de reautenticacion ni renovacion automatica.
5. Ejecuta solo INSERT SUPERADMIN ACTIVE o UPDATE ACTIVE a REVOKED. V20 vuelve a comprobar las invariantes aun si se omite la comprobacion Java. OWNER es inmutable; la instalacion OWNER-only es valida y nunca se revoca al ultimo delegado. Las FK conservan identidades ACTIVE incluso para historial revocado (politica conservadora de V19). Ningun cambio toca credenciales o membresias de tenant.
6. Un trigger AFTER inserta auditoria SUCCESS en la misma transaccion. El ejecutor obtiene su correlacion PostgreSQL y solo entonces intenta COMMIT. Si falla el INSERT de evidencia, tambien revierte la designacion/revocacion. No hay confirmacion sin evidencia.

## Evidencia persistente y resultados

Se reutiliza `audit.events` con FORCE RLS. Cada exito tiene actor OWNER verificado, accion PLATFORM_DESIGNATE_SUPERADMIN/PLATFORM_REVOKE_SUPERADMIN, usuario objetivo, fecha `clock_timestamp()`, resultado SUCCESS, correlacion UUID PostgreSQL y metadata exclusiva `designation_id`. No hay tenant, aplicacion, sesion o API key asociados a autoridad global.

El canal escritor solo puede insertar SUCCESS desde el trigger de designaciones: el guard exige anidamiento de trigger y formato canonico. INSERT directo no puede falsificar un exito. Los roles de servicio no tienen CREATE/TRIGGER para fabricar ese anidamiento. La evidencia reservada es inmutable y no truncable mediante DML ordinario. El escritor puede leer solo las columnas y eventos de exito necesarios para recuperar la correlacion. El canal de rechazos solo tiene INSERT, nunca lectura ni mutaciones de plataforma.

Una operacion rechazada primero revierte completamente la transaccion de plataforma y luego registra DENIED en una conexion/transaccion independiente. La identidad del actor se registra solo cuando F04 y la coordinacion la devolvieron; con password incorrecta o fallo anterior a esa confirmacion es NULL. El objetivo de un rechazo es un selector intentado, no una identidad declarada autorizada. Metadata solo contiene `reason=REJECTED`; no se almacena username, password, hash, mensaje SQL o solicitud libre.

Resultados del ejecutor:

- CONFIRMED: COMMIT reconocido y exito/auditoria atomicos.
- DENIED: sin COMMIT de mutacion, rechazo persistido separadamente.
- UNAVAILABLE: no se pudo persistir el rechazo; no se declara evidencia inexistente. Se requiere atencion operativa, no continuar como si el cambio hubiera sido autorizado.
- UNKNOWN: se intento COMMIT pero no hubo confirmacion segura. Se intenta evidencia independiente UNKNOWN con `reason=COMMIT_UNCERTAIN` y la misma correlacion conocida. `evidenceRecorded=false` indica que tampoco pudo confirmarse ese registro. Nunca se reintenta automaticamente; el operador debe reconciliar la correlacion y el estado PostgreSQL antes de otra operacion.

No hay forma de hacer atomicos un rollback y una transaccion independiente de rechazos. Si el proceso o PostgreSQL cae entre ambos, puede faltar evidencia de rechazo; no se afirma entrega garantizada ante caidas. Los exitos si estan protegidos atomicamente por PostgreSQL. El bootstrap excepcional anterior se conserva sin convertirlo en un endpoint ni en delegacion permanente.

## Validacion focalizada

Un unico metodo nuevo `PlatformPrivilegedPostgresTest.recentOwnerPasswordAtomicAuditRejectedEvidenceAndConcurrentRevocations` usa F04 real y PostgreSQL 17 desechable. Cubre OWNER/password correcta, incorrecta y contador F04, usuario empresarial y delegado sin autoridad, designacion/revocacion y evidencia, ultimo delegado y OWNER, fallo real de trigger de auditoria con rollback completo, dos ejecutores concurrentes esperando el bloqueo de V20 mediante barrera/observacion de `pg_locks`, evidencia sin secretos, roles HTTP rechazados y FORCE RLS. No invoca los metodos historicos.

```powershell
$env:JAVA_HOME='C:\Program Files\Java\jdk-21.0.10'
$env:PATH="$env:JAVA_HOME\bin;$env:PATH"
python scripts/tests/postgres_platform_privileged_contract.py
```

El runner usa puertos efimeros (nunca 5432/55432), activa solo canales propios del cluster temporal, los desactiva en finally, sella bootstrap y elimina el cluster. El CI estandar ejecuta tambien esta prueba; no se agrega otro metodo de prueba ni un servidor compartido.

## Conexion futura con F13

El frontend global necesitara una sesion HTTP independiente de LS Core con frontera confiable, CSRF, manejo efimero de confirmacion de password por operacion y adaptacion al ejecutor sin exponer secretos tecnicos. Debe ofrecer resultados y correlaciones con manejo explicito de UNKNOWN. Esta unidad entrega el backend interno utilizable por consola, no ese portal ni rutas publicas. El piloto Fiscal y DEMO conservan sus capacidades previas; `OperationAdmission.session`, API Keys y SQL empresarial general no se habilitan.

## Resultado local de entrega

El unico metodo focalizado paso con cero fallos/errores/omisiones y Checkstyle en el mismo Maven verify. Hubo una ejecucion correctiva por un defecto real de tipado: el parametro de fecha del chequeo de recencia necesita `::timestamptz` antes de sumar el intervalo. Se reutilizo el mismo metodo, sin pruebas exploratorias ni ejecucion manual de integraciones historicas. Lint, TypeScript y build React pasaron. La activacion/desactivacion controladas y la limpieza del cluster quedaron verificadas; no se repitio el recorrido visual del piloto ni se declara un portal global disponible.
