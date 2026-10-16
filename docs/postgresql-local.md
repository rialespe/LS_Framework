# PostgreSQL local — LFD-23

## Estado de desarrollo validado — seguimiento LFD-82, 2026-10-16

El propietario completó LFD-23: PostgreSQL/psql 17.2, `ls_core_dev` UTF8 con
propietario postgres y `ls_core_app_dev` LOGIN con límite 3 y atributos mínimos.
Validó conexión de aplicación/SELECT 1, CONNECT verdadero y CREATE/TEMP falsos,
segunda inicialización `NO CHANGES` y perfil JDBC `postgres` con HTTP 200 y JSON
`status: UP`. Fuente: [aprobación final, comentario 10023](https://lssoluciones.atlassian.net/browse/LFD-23?focusedCommentId=10023).
No se repitieron operaciones sobre PostgreSQL real en LFD-82.

La verificación observó CONNECT efectivo en otras cinco bases, posiblemente por
PUBLIC. No se remediaron permisos ajenos ni se certificó exclusividad de conexión.
Ver [ADR 0002](adr/0002-postgresql-y-conectividad.md) y
[seguimiento de evidencia](verification-lfd-23.md). El estado debe inspeccionarse
de nuevo antes de cualquier futura aplicación del inicializador.

## Entorno detectado originalmente

Windows, PostgreSQL y psql **17.2**, instalados en
`C:\Program Files\PostgreSQL\17`. Servicio `postgresql-x64-17`, actualmente
**Running / Automatic** (antes estaba detenido). Directorio de datos:
`C:\Program Files\PostgreSQL\17\data`. Puerto configurado **5432**;
`pg_isready` en `127.0.0.1:5432` acepta conexiones. El usuario confirmó acceso
administrativo con psql -W. En la inspección posterior confirmó un rol manual
`ls_core_app_dev` NOLOGIN sin marcador, sin membresías ni propiedad de bases,
y ausencia de `ls_core_dev`. El inicializador vuelve a consultar ese estado.
Codex no dispone de la credencial: su intento no interactivo -w terminó con
`no password supplied`, por lo que todavía no ha verificado esos catálogos.
No se instaló, actualizó ni reconfiguró PostgreSQL.

Backend: Java 21 (verificado con 21.0.10), Boot 4.0.8, Maven Wrapper 3.9.14.
HikariCP 7.0.2 y pgJDBC 42.7.13 usan versiones gestionadas por el BOM de Boot.
No hay JPA, Spring Data, Flyway, Liquibase ni scripts de inicialización automáticos.

## Inspección segura en Windows

Desde la raíz del repositorio:

```powershell
Get-Service postgresql-x64-17 | Select-Object Name,Status,StartType
$pgBin = 'C:\Program Files\PostgreSQL\17\bin'
& "$pgBin/postgres.exe" --version
& "$pgBin/psql.exe" --version
& "$pgBin/postgres.exe" -D 'C:\Program Files\PostgreSQL\17\data' -C port
& "$pgBin/pg_isready.exe" -h 127.0.0.1 -p 5432 -t 2
```

No cambiar PATH global, archivos privados ni configuración del servidor.
Si está Disabled, el usuario debe habilitar/iniciar el servicio conforme a su
configuración local o autorizar expresamente el cambio. Un cambio del servicio
puede requerir PowerShell elevado. No detener un servicio compartido para pruebas.

Cuando esté disponible, introducir el nombre de un administrador local conocido:

```powershell
$adminRole = Read-Host 'Rol administrador PostgreSQL existente'
& "$pgBin/psql.exe" -X -W -h 127.0.0.1 -p 5432 -U $adminRole -d postgres -v ON_ERROR_STOP=1 -f scripts/postgres/inspect-local.sql
```

`-W` solicita la contraseña en el terminal, sin eco ni contraseña en argumentos.
No publicarla en el chat. El archivo inspecciona solo versión, puerto y existencia
de los nombres propuestos; no vuelca bases ajenas ni hashes de contraseña.
La operación es de solo lectura. Si algún nombre existe, detener el aprovisionamiento
y confirmar su propietario/propósito; no reutilizarlo ni cambiarle la contraseña.

## Inicializador: inspección, confirmación y aplicación

Se requiere PowerShell en un terminal interactivo local y acceso administrativo como
`postgres`. No requiere elevar PowerShell ni cambiar servicios/políticas de ejecución.
No ejecutar con transcripciones, depuración, eco SQL ni entrada redirigida. Nunca
enviar contraseñas por chat. El alcance autorizado son solo `ls_core_dev` y
`ls_core_app_dev`; cada plan con cambios requiere confirmación local explícita.

Desde la raíz:

```powershell
# Solo preflight y plan; no crea ni modifica objetos PostgreSQL
.\scripts\Initialize-LocalPostgres.ps1

# Vuelve a inspeccionar, muestra el plan y exige escribir APPLY si hay cambios
.\scripts\Initialize-LocalPostgres.ps1 -Apply

# Opcional: después de verificar psql, inicia el backend con el script existente
$env:JAVA_HOME = 'C:\Program Files\Java\jdk-21.0.10'
.\scripts\Initialize-LocalPostgres.ps1 -Apply -StartBackend
```

`-PsqlPath 'C:\...\psql.exe'` permite seleccionar un cliente 17 existente si no
se encuentra en la instalación conocida o PATH. No descarga ni instala PostgreSQL.
Los nombres, host y puerto son fijos para impedir aprovisionar otro servidor por error.
La versión real y dirección/puerto se validan también mediante SQL, no solo pg_isready.

### Primera ejecución

1. psql pide la contraseña administrativa localmente con `-W`; se omite psqlrc con `-X`.
2. Una inspección de catálogos comprueba servidor 17, administrador, recursos,
   atributos de rol, membresías, propiedad y ACL. Un conflicto detiene el proceso.
3. Se presenta el plan. Sin `-Apply` termina aquí. Con cambios, escribir exactamente
   `APPLY`; otra respuesta cancela antes de crear objetos.
4. Se crea exclusivamente el rol faltante con NOLOGIN, NOINHERIT, NOSUPERUSER,
   NOCREATEDB, NOCREATEROLE, NOREPLICATION, NOBYPASSRLS y límite 3.
5. `\password ls_core_app_dev` solicita dos veces su nueva contraseña localmente;
   se usa SCRAM-SHA-256. No se construye SQL con contraseñas literales ni se guardan
   contraseñas o hashes en archivos. Solo se consulta si existe un password, sin mostrarlo.
6. Se crea exclusivamente la base faltante UTF8, OWNER postgres, TEMPLATE template0,
   evitando copiar posibles objetos personalizados de template1. No se crean tablas.
7. En una transacción se revoca PUBLIC solo sobre esta base y se concede CONNECT
   al rol. Después se habilita LOGIN. La aplicación no es propietaria ni recibe DDL.
8. Se repiten validaciones y se compara una huella de las definiciones/ACL de otras
   bases. Se informa cualquier cambio concurrente; nunca se intenta revertirlo.
9. Una nueva conexión psql -W como aplicación ejecuta
   `SELECT current_database(), current_user, 1`, valida CONNECT y ausencia de
   CREATE/TEMPORARY y CREATE efectivo en esquemas. Esa verificación es READ ONLY.

### Segunda ejecución e instalaciones parciales

Repetir `.\scripts\Initialize-LocalPostgres.ps1 -Apply`. Una instalación válida
debe mostrar `NO CHANGES`, no pedir APPLY ni ejecutar CREATE/ALTER/GRANT/REVOKE,
COMMENT o `\password`. Las solicitudes de contraseñas son autenticaciones para
verificar, no nuevas autorizaciones ni cambios de contraseña.

La primera creación añade el comentario `LS Core LFD-23 bootstrap v1` a cada recurso.
Permite reconocer y completar pasos parciales propios: rol NOLOGIN sin contraseña,
base marcada con permisos aún predeterminados o CONNECT faltante. Cada continuación
con cambios vuelve a presentar el plan y requiere APPLY. Nunca se restablece una
contraseña ya configurada. Recursos no marcados completos se aceptan sin cambios
si ambos cumplen el contrato. Un rol manual parcial requiere la adopción siguiente;
la presencia del marcador nunca sustituye las verificaciones del estado real.

### Recuperación explícita del rol manual sin marcador

Primero inspeccionar, sin efectuar cambios:

```powershell
.\scripts\Initialize-LocalPostgres.ps1
```

Un rol NOLOGIN sin comentario, con atributos mínimos y límite de conexiones `-1`
(predeterminado del CREATE ROLE manual) o `3`, puede identificarse como candidato.
La inspección muestra `Compatible unmarked partial role; explicit adoption required: t`
y el plan: reutilizar su identidad, fijar CONNECTION LIMIT 3, aplicar el marcador
**`LS Core LFD-23 bootstrap v1`**, configurar contraseña solo si falta, crear la base
faltante y configurar CONNECT antes de habilitar LOGIN.

Después de revisar el resultado, el propietario puede ejecutar:

```powershell
.\scripts\Initialize-LocalPostgres.ps1 -Apply -AdoptExistingRole
```

El parámetro por sí solo no aplica nada. Se exige introducir exactamente:

```text
ADOPT ls_core_app_dev AND APPLY
```

Esa confirmación acredita que el propietario creó el rol para LS Core y que no lo
utilizan otras aplicaciones. Los catálogos no permiten demostrar que ninguna
aplicación desconectada conserve ese nombre en su configuración.
`-Apply` sin `-AdoptExistingRole` se detiene antes de cualquier escritura.
`-AdoptExistingRole` sin `-Apply` conserva el modo de inspección.
Una confirmación genérica `APPLY` no autoriza la adopción.

Se rechazan comentarios ajenos, privilegios administrativos, membresías, parámetros
por rol, objetos propios, dependencias compartidas fuera del ACL permitido de la
base objetivo, sesiones activas, transacciones preparadas y etiquetas de seguridad.
Se consulta `pg_shdepend` para detectar referencias de objetos en otras bases sin
abrirlas ni modificarlas. La base existente, si la hay, debe superar las validaciones
normales; la adopción no permite reparar una base incompatible.

Tras la confirmación se repite la inspección dentro de una transacción y se verifica
el mismo OID y el estado NOLOGIN sin comentario. Solo entonces se ajusta el límite
y se agrega el marcador en una transacción. No se recrea ni elimina el rol.
Si falla posteriormente la contraseña o la base, se conserva el estado parcial;
la siguiente ejecución inspecciona de nuevo y continúa únicamente lo necesario.
Un password ya presente se conserva. LOGIN se habilita al finalizar permisos/base.

No ejecutar otros cambios administrativos en paralelo: el bloqueo asesor serializa
estos instaladores, no toda actividad administrativa del clúster.

### Pruebas aisladas de recuperación

```powershell
python scripts/tests/test_postgres_bootstrap.py
```

Prueba opcional: requiere Python 3 y los binarios PostgreSQL 17 ya instalados.
Crea clústeres temporales independientes, con datos descartables y puertos efímeros
distintos de 5432. No conecta al servicio compartido. Las copias temporales del SQL
cambian solo la comprobación del puerto esperado. Usa autenticación trust únicamente
en esos clústeres vacíos y credenciales aleatorias de prueba en memoria para probar
\password por stdin mediante el fallback Windows/MSYS; producción mantiene prompts
interactivos. No es una prueba de autenticación por contraseña del servidor real.

Verifica inspección sin cambios, adopción con doble autorización, rechazo de estados
incompatibles, preservación del OID, permisos y segunda ejecución sin cambios,
incluido el password almacenado. No imprime ni guarda snapshots de pg_authid.
Detiene cada clúster temporal antes de eliminar su directorio; si no puede detenerlo,
retiene los archivos y falla. No modifica servicios ni instala PostgreSQL.

CREATE DATABASE se ejecuta fuera de una transacción; CREATE ROLE+comentario y cambios
de ACL tienen sus propias transacciones. Si hay interrupción entre CREATE DATABASE y
su comentario, la base puede quedar sin marca: el script se detiene y exige revisión
manual. Nunca elimina recursos para recuperar. Un fallo de contraseña deja NOLOGIN.
Un fallo posterior conserva lo creado y no declara éxito.

Un bloqueo asesor de sesión serializa instancias de este inicializador. No impide
cambios de otros administradores: no ejecutar DDL administrativo en paralelo.
Los límites de espera/consulta de esa sesión son locales, no cambios del servidor.

### Contrato y conflictos

Base: UTF8, propietario postgres, habilitada y no template. Rol: atributos anteriores,
sin membresías, objetos propios, parámetros por rol ni privilegios explícitos fuera
del contrato. ACL: administrador propietario y CONNECT sin grant option para la app;
sin PUBLIC al finalizar. La validación de esquemas ocurre al conectar como aplicación.
Cualquier incompatibilidad detiene el proceso sin reparación ni ampliación de privilegios.
Los errores de un servidor compartido no se simulan alterando recursos existentes.

El administrador puede consultar el SQL mantenible en `scripts/postgres/initialize-local.sql`,
`inspect-bootstrap.sql` y `verify-application.sql`. La inspección simple original
`inspect-local.sql` también se conserva para diagnóstico de solo lectura.

### Límite importante de permisos en un servidor compartido

PostgreSQL concede habitualmente CONNECT a PUBLIC en otras bases. Crear un rol y
concederle CONNECT solo en `ls_core_dev` **no elimina** esos permisos heredados de PUBLIC;
NOINHERIT tampoco los elimina. Por ello, no se puede certificar exclusividad de conexión
a su base sin inspeccionar los permisos efectivos existentes. Si otras bases permiten
acceso por PUBLIC, el aislamiento estricto exige una decisión adicional del administrador
(por ejemplo, reglas específicas de autenticación). Aquí no se altera `pg_hba.conf`,
otras bases ni permisos globales para resolverlo. Esa condición deberá revisarse antes
de declarar aprobado el rol limitado exclusivamente a su base.
Referencia: [privilegios PostgreSQL 17](https://www.postgresql.org/docs/17/ddl-priv.html).

## Arranque base: sin PostgreSQL

```powershell
$env:JAVA_HOME = 'C:\Program Files\Java\jdk-21.0.10'
$env:Path = "$env:JAVA_HOME\bin;$env:Path"
Set-Location backend
.\mvnw.cmd -B -ntp clean verify
java -jar target/ls-core-0.0.1-SNAPSHOT.jar --spring.profiles.active=dev
```

No se requieren variables LS_DB ni conexión. La configuración JDBC solo se instancia
con el perfil explícito `postgres`. Otros perfiles conservan el comportamiento base.

## Arranque PostgreSQL: credenciales locales

| Variable obligatoria | Significado |
| --- | --- |
| `LS_DB_HOST` | Host local comprobado, por ejemplo 127.0.0.1 |
| `LS_DB_PORT` | Puerto comprobado; no tiene valor predeterminado |
| `LS_DB_NAME` | Base autorizada y comprobada |
| `LS_DB_USERNAME` | Rol de aplicación separado, no postgres |
| `LS_DB_PASSWORD` | Contraseña introducida localmente; sin valor predeterminado |

Desde la raíz, tras compilar y preparar los recursos autorizados:

```powershell
$env:JAVA_HOME = 'C:\Program Files\Java\jdk-21.0.10'
.\scripts\Start-PostgresBackend.ps1 -DatabaseHost 127.0.0.1 -DatabasePort 5432 -DatabaseName ls_core_dev -DatabaseUser ls_core_app_dev
```

El script pide la contraseña con Read-Host -AsSecureString, inicia el JAR con perfil
postgres y restaura el entorno del proceso al terminar. No crea archivos, no usa setx
ni variables persistentes. No invocarlo bajo transcripciones o depuración de secretos.
Las variables de entorno y la memoria de Java contienen la contraseña durante el
proceso: administradores/depuradores locales pueden acceder a ellas. SecureString no
elimina esa limitación. Cerrar el proceso al finalizar. No se ofrece persistencia local
automática ni se modifica la política de ejecución PowerShell.

Equivalente POSIX, con Bash y JDK 21 en PATH (no verificado en este equipo):

```bash
cd backend
sh ./mvnw -B -ntp clean verify
(
  export LS_DB_HOST=127.0.0.1 LS_DB_PORT=5432
  export LS_DB_NAME=ls_core_dev LS_DB_USERNAME=ls_core_app_dev
  read -r -s -p 'Contraseña PostgreSQL: ' LS_DB_PASSWORD
  printf '\n'
  export LS_DB_PASSWORD
  java -jar target/ls-core-0.0.1-SNAPSHOT.jar --spring.profiles.active=postgres
  unset LS_DB_PASSWORD
)
```

La inspección POSIX equivalente usa `psql -X -W -h 127.0.0.1 -p 5432 -U "$adminRole"
-d postgres -v ON_ERROR_STOP=1 -f scripts/postgres/inspect-local.sql` desde la raíz.
No usar `set -x`. El password solo se solicita localmente. El arranque base equivalente
es `java -jar ... --spring.profiles.active=dev`.

## Verificación y límites

```powershell
Invoke-RestMethod http://127.0.0.1:8080/actuator/health
```

Con `-StartBackend`, Java queda en primer plano y solicita la contraseña de aplicación
con el script existente. Ejecutar la petición health desde una segunda terminal;
HTTP 200 / UP confirma JDBC real. El inicializador no declara ese resultado por haber
validado solamente psql. Detener Java con Ctrl+C y esperar cierre graceful.
No hay un segundo pool ni gestor de configuración. Compilar primero con JDK 21.

Con PostgreSQL correcto: HTTP 200 / UP significa que se obtuvo una conexión y
`SELECT 1` devolvió 1. El indicador usa try-with-resources para cerrar resultado y
sentencia y devolver la conexión al pool. Con fallo real: HTTP 503 / DOWN, sin
excepciones, credenciales, URL ni detalles internos. El proceso HTTP sigue disponible.
Si faltan variables, el perfil falla al iniciar indicando solo el nombre de la variable.

Pool: máximo 3, mínimo inactivo 0, espera 5 s, validación 2 s, conexión del driver 3 s,
lectura de socket 5 s, consulta 3 s, inactividad 60 s, vida máxima 10 min; cierre con
Spring. Conexiones de este ticket en modo read-only. Los límites son por operación,
no una garantía universal de tiempo total bajo cualquier fallo de red.

No hay starter JDBC ni autoinicialización SQL; solo HikariCP y pgJDBC. La configuración
usa PGSimpleDataSource con campos separados para evitar secretos en URLs.
Los loggers de Hikari/pgJDBC están desactivados en el perfil para impedir mensajes
de conexión que contengan datos privados; el indicador no adjunta ni registra excepciones.
No sobrescribir esos niveles con DEBUG/TRACE al trabajar con credenciales reales.
Solo se expone health; `/actuator/env`, `/actuator/configprops` y `/actuator/beans`
continúan fuera de la exposición web. No hay seguridad funcional ni HTTPS productivo.
Este perfil se destina al PostgreSQL local; no certifica TLS para servidores remotos.

## Diagnóstico sin revelar secretos

| Síntoma | Comprobación segura / acción |
| --- | --- |
| Servicio detenido o deshabilitado | Get-Service; habilitar/iniciar solo por el usuario o con autorización |
| Puerto incorrecto | Comparar postgres -C port, pg_isready y puerto SQL real |
| Conexión rechazada | Revisar servicio, listener y host local; no modificar firewall/configuración a ciegas |
| Usuario/contraseña incorrectos | Probar psql -W con el rol de aplicación; no restablecer roles existentes |
| Base inexistente | Inspeccionar nombres; solicitar autorización antes de crear |
| Permisos insuficientes | Administrador revisa CONNECT y privilegios efectivos; no conceder superusuario |
| Variables ausentes | Completar las cinco LS_DB_* en el proceso; no imprimir sus valores |
| Salud DOWN | Verificar con psql localmente; no habilitar logs con credenciales para diagnosticar |
| Contraseña incorrecta | La conexión falla y el inicializador se detiene; no la restablece automáticamente |
| Recurso incompatible | Revisar el conflicto; no cambiar propietario, ACL ni atributos automáticamente |
| Rol manual NOLOGIN sin marcador | Inspeccionar; usar -Apply -AdoptExistingRole y la frase específica solo tras validar propiedad/uso |
| Ejecución interrumpida | Repetir inspección; completar solo pasos propios marcados y confirmados |
| Otra instancia activa | Esperar a que cierre; no matar servicios ni quitar bloqueos de terceros |

`psql -W` puede mostrar nombres de rol/base en errores: revisarlos localmente y compartir
solo el diagnóstico necesario. Nunca copiar archivos privados, logs completos o respaldos
al chat. `.gitignore` excluye .env locales, pgpass, llaves, .dump y .backup.
No se generaron archivos de secretos ni respaldos.

La evidencia original y el seguimiento de aprobación posterior están en
[verificación LFD-23](verification-lfd-23.md). Los bloqueos de acceso de las
primeras ejecuciones son históricos; no invalidan la aprobación posterior del propietario.

## Migraciones posteriores

LFD-25 incorporará Flyway y un rol de migración separado, con permisos DDL definidos
expresamente. El rol runtime seguirá sin propiedad ni CREATE; no elevarlo para migrar.
Este bootstrap prepara la base y CONNECT, no ejecuta ni sustituye migraciones.
LFD-29 abordará RLS y aislamiento tenant. Ninguno se implementa en LFD-23.
Tras cambios de permisos de futuras historias, revisar la compatibilidad del contrato
estricto del inicializador; no usarlo como herramienta genérica de reparación.
