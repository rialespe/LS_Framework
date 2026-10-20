# LFD-25: preparación exclusiva para PostgreSQL Docker

Base de herramientas: `ef6953d3ce2c702e24b36294bf55c50e0b744ce6`.

## Alcance y uso

`scripts/provision_docker_postgres.py` separa el **preflight de lectura** (predeterminado)
de la aplicación explícita `--apply`. Esta última implementa la autorización específica
de LFD-25, comentario 10125, para las tres revocaciones Docker y la provisión previamente
autorizada. No tiene reparación, adopción o borrado. No ejecuta los scripts existentes
de Windows ni cambia sus destinos.

En una consola local interactiva, desde la raíz del repositorio:

```powershell
python scripts/provision_docker_postgres.py
# Opcional: informe sin secretos fuera del repositorio
python scripts/provision_docker_postgres.py --report "$env:TEMP\ls-core-docker-preflight.json"
# Aplicación explícita, solo con las autorizaciones descritas y JDK 21 seleccionado:
$env:JAVA_HOME = 'C:\Program Files\Java\jdk-21.0.10'
python scripts/provision_docker_postgres.py --apply --report "$env:TEMP\ls-core-docker-apply.json"
```

Requiere Docker Desktop y el cliente existente
`C:\Program Files\PostgreSQL\17\bin\psql.exe`. La contraseña de `ls_core_docker`
se solicita con entrada oculta; no se acepta por argumentos, archivos ni stdin
redirigido. Nunca introducirla en el chat. Se pasa a libpq solo en el entorno del
proceso hijo; no se imprime ni se escribe en el informe.

La herramienta verifica nombre/ID, imagen `postgres:17`, estado activo, volumen
`ls-core-pg17-data`, publicación exclusiva `127.0.0.1:55432` y versión 17. Compara
el identificador físico de `pg_controldata` con `pg_control_system()` leído por SQL.
No imprime `Config.Env` de Docker. Fija el identificador autorizado
`7698808171985612839`. El preflight SQL usa `ls_core_docker_dev` y lectura; la aplicación
usa exclusivamente esa base y la nueva `ls_core_dev`, mediante `psql -X -w` con timeout
y entorno libpq saneado. Nunca conecta al puerto 5432 del host.

Ante errores, conserva el diagnóstico depurado, clasifica autenticación/conexión/
permisos/SQL y muestra el SQLSTATE cuando psql lo expone. Algunos fallos libpq
anteriores a la sesión SQL no lo incluyen; no se inventa un código. El preflight
no reintenta. Un error, conflicto o ACL incompatible termina con código 1.

## Evidencia del preflight inicial, 20 de octubre de 2026

- Contenedor: `ls-core-pg17`, ID
  `03dfeb029dd7f36c8aa8e1af0a685f478249c0097243e72f51aa43095780d83e`.
- PostgreSQL **17.11**, volumen y publicación localhost correctos.
- Identificador físico y SQL coincidentes: `7698808171985612839`.
- Conexión autenticada como `ls_core_docker`, superusuario y propietario de
  `ls_core_docker_dev`. Base bootstrap conservada.
- `ls_core_dev` inexistente; `ls_core_migrator_dev` y `ls_core_app_dev` inexistentes.
  Por tanto, tampoco hay esquemas existentes que adoptar en la base objetivo.
- **BLOQUEADO**: `ls_core_docker_dev`, `postgres` y `template1` permiten `CONNECT`
  a `PUBLIC`. No se ejecutó DDL ni se cambiaron ACL.

El intento anterior descartó stderr y su SQLSTATE. Su causa histórica exacta no
es reconstruible a partir del informe guardado. La nueva inspección sí pasó SQL;
no hay evidencia para atribuir el fallo anterior a una contraseña incorrecta.
El bloqueo actual es una validación de seguridad de la herramienta, no un error
SQL del servidor, y por ello no tiene SQLSTATE.

## Resolución ACL autorizada posteriormente

La comprobación existente en `scripts/migrations/provision.sql` rechaza otras bases
con `PUBLIC CONNECT`. `NOINHERIT` no elimina privilegios recibidos por `PUBLIC` y
PostgreSQL no permite un `DENY CONNECT` individual que los anule.

El propietario autorizó específicamente cambiar estas ACL en el comentario 10125
de LFD-25. La fase `--apply` ejecuta estas sentencias en una única transacción:

```sql
-- Únicamente dentro del clúster Docker verificado.
REVOKE CONNECT ON DATABASE ls_core_docker_dev FROM PUBLIC;
REVOKE CONNECT ON DATABASE postgres FROM PUBLIC;
REVOKE CONNECT ON DATABASE template1 FROM PUBLIC;
```

Esto no borra bases ni datos, pero impide nuevas conexiones de roles que dependan
exclusivamente de `PUBLIC CONNECT`; los propietarios conservan sus privilegios.
Cualquier acceso adicional necesario deberá revisarse explícitamente. Se rechazan
roles personalizados preexistentes, conexiones cliente de usuarios ajenos y
transacciones preparadas. Las conexiones administrativas existentes se informan y
no dependen de PUBLIC CONNECT. No se puede demostrar ausencia de clientes dormidos
solo con catálogos: la autorización del propietario delimita el clúster de desarrollo.

Dentro de la transacción ACL se comparan todos los metadatos de bases y sus ACL
efectivas antes y después: solo pueden desaparecer esas tres entradas CONNECT de
PUBLIC. TEMP y todas las demás concesiones permanecen iguales; cualquier diferencia
revierte la transacción antes de COMMIT. No se revocan sesiones ni se borran datos.

Después se crea la base nueva con propietario administrativo (CREATE DATABASE exige
una operación fuera de transacción), y en otra transacción los dos roles sin
membresías ni privilegios elevados (límite 3), CONNECT únicamente a `ls_core_dev`
y sin CREATE/TEMP, ocho esquemas propiedad del migrador y sin privilegios PUBLIC/
runtime. `psql \password` establece las dos contraseñas con SCRAM mediante la consola;
se habilita LOGIN solo tras comprobar su configuración. Se conserva la base bootstrap.

Finalmente se ejecuta Flyway V1–V9 original como migrador restringido contra
`jdbc:postgresql://127.0.0.1:55432/ls_core_dev`, verificar historial/checksums,
29 tablas de dominio más historial técnico, siete catálogos con 28 semillas V8,
22 tablas con ENABLE/FORCE RLS V9 y sin políticas, ausencia de acceso empresarial
del runtime y conexión JDBC/SELECT 1 con `ls_core_app_dev` sin endpoints de negocio.
Los bytes de V1–V9 se comparan con la base Git autorizada y sus SHA256 antes/después.
La verificación administrativa usa solo una tabla temporal de sesión. No inserta
fixtures empresariales ni ejecuta suites B2-R/G2/G3.

`scripts/docker/VerifyDockerJdbc.java` carga la configuración y el pool JDBC existentes
del backend mediante un contexto Spring sin servidor web. Comprueba SELECT 1, usuario,
base, PostgreSQL 17 y conexión de lectura, y cierra el pool al terminar. Se compilan
las clases existentes y se resuelve su classpath sin modificar código de aplicación
ni ejecutar suites completas. Las credenciales van solo en entornos de procesos hijos;
los diagnósticos Maven/JDBC capturados no se guardan ni se imprimen.

Cada fase guarda únicamente progreso y resultados no secretos en el informe opcional.
Ante fallo se detiene sin repetir, adoptar, limpiar o reparar. Las fases ya confirmadas
permanecen aplicadas; el conjunto ACL/base/roles/Flyway no es una única transacción.

## Resultado ejecutado de la aplicación autorizada

La ejecución interactiva alcanzó `COMPLETE`. No se reinició la herramienta al recibir
el siguiente mensaje del propietario: el proceso había finalizado entre mensajes y
la base ya estaba creada. Se comprobó que no quedaban procesos Python/Flyway/Maven
de migración ni otras conexiones cliente en `pg_stat_activity`.

- Identidad Docker física/SQL: `7698808171985612839`, PostgreSQL 17.11,
  publicación exclusiva `127.0.0.1:55432`, volumen `ls-core-pg17-data`.
- Preflight: sin otros roles personalizados, conexiones cliente ni transacciones
  preparadas. No se identificó impacto ajeno inesperado.
- Tres revocaciones CONNECT atómicas confirmadas y ACL restantes comparadas dentro
  de la transacción. La comprobación final confirmó cero concesiones PUBLIC CONNECT
  en las tres bases; `ls_core_docker_dev` sigue existiendo.
- Creada `ls_core_dev`, con propietario `ls_core_docker`; creados los dos roles
  restringidos con límite 3, sin membresías, CREATE/TEMP, acceso a otras bases ni
  atributos elevados; ocho esquemas propiedad de `ls_core_migrator_dev`.
- Flyway `migrate` y `validate` correctos. Historial **V1, V2, V3, V4, V5, V6, V7,
  V8 y V9**, todas exitosas e instaladas por `ls_core_migrator_dev`.
- **29 tablas de dominio**, siete catálogos globales y **28 semillas**; **22 tablas
  con ENABLE/FORCE RLS** y cero políticas. El runtime no tiene USAGE ni privilegios
  empresariales de tabla/columna; solo puede conectar a la base objetivo.
- Verificación SQL del runtime correcta y conexión con el **pool real del backend**
  como `ls_core_app_dev`: `SELECT 1` correcto, conexión de lectura, sin iniciar HTTP.
- V1–V9 originales y sus SHA256 se conservaron antes/después. La lectura final
  independiente dentro del contenedor confirmó historial, recuentos, identidad y ACL.

No se accedió al puerto 5432 del host, no se borraron datos ni se modificaron los
tres documentos locales de diseño. No se ejecutaron suites B2-R/G2/G3 ni endpoints
de negocio. Este resultado valida la infraestructura Docker de desarrollo; no
declara autenticación, autorización de negocio ni revocación productivas.

No se modifican V1–V9, PostgreSQL de Windows, documentos locales de diseño ni
los destinos de `manage_migrations.py`. No se ejecutan B2-R ni pruebas G2/G3.
