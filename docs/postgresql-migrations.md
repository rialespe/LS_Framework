# Migraciones PostgreSQL — LFD-25

**Estado: infraestructura inicial implementada; tablas de dominio pendientes de
definición. LFD-25 no está lista para cierre.** No se ejecutó nada sobre PostgreSQL
real. El modelo y las migraciones de tablas no se sustituyen por la prueba de V1.

## Arquitectura y compatibilidad

Flyway Community Maven Plugin y `flyway-database-postgresql` **11.14.1**, versión
`flyway.version` gestionada por el parent Spring Boot **4.0.8**. El plugin utiliza
pgJDBC **42.7.13**, también fijado por ese parent. Solo están dentro del perfil
Maven `database-migrations`; no son dependencias runtime ni existe una ejecución
de plugin enlazada a verify/package. JDK **21.0.10** y Maven Wrapper **3.9.14**
se usaron en las pruebas locales con PostgreSQL **17.2**.

La [documentación del plugin](https://documentation.red-gate.com/flyway/reference/usage/maven-goal)
requiere Java 17+ y Maven 3.x; el
[adaptador PostgreSQL 11.14.1](https://github.com/flyway/flyway/blob/flyway-11.14.1/flyway-database/flyway-database-postgresql/src/main/java/org/flywaydb/database/postgresql/PostgreSQLDatabase.java)
admite PostgreSQL 17 y DDL transaccional. La combinación se comprobó ejecutándola,
no solo leyendo requisitos. No se actualizan Java, Spring, JDBC, Node o frontend.

El JAR no contiene Flyway: arrancar con `dev`, `test`, `postgres` u otro perfil
Spring ordinario no ejecuta migraciones. El perfil `postgres` mantiene HikariCP y
el rol aplicativo exclusivamente para salud `SELECT 1`; las credenciales del
migrador nunca se pasan al backend. Los recursos SQL empaquetados no se ejecutan
por el mero hecho de estar en el classpath.

## Ubicación, secuencia e historial

SQL versionado en [db/migration](../backend/src/main/resources/db/migration/V1__verify_authorized_schemas.sql):

| Versión | Archivo | Responsabilidad implementada |
| --- | --- | --- |
| 1 | V1__verify_authorized_schemas.sql | Verificar base, rol, PostgreSQL 17, ocho esquemas y privilegios mínimos |
| Posteriores | Pendientes, no creadas | Tablas globales, empresariales y de apoyo una vez definido el modelo |

La preparación administrativa crea los ocho namespaces explícitos. V1 comprueba
ese contrato: no se concede CREATE sobre la base al migrador para crear esquemas
arbitrarios. Ver la decisión **propuesta**, aún no aprobada, en el
[ADR 0004](adr/0004-migraciones-administrativas.md).

Historial: `operations.flyway_schema_history`, creado y mantenido por el migrador,
sin USAGE ni permisos de tabla para runtime. Flyway registra versión, script,
checksum, autor y resultado. `validateOnMigrate=true`, `outOfOrder=false`,
`baselineOnMigrate=false`, `createSchemas=false`, `cleanDisabled=true`.
La segunda ejecución consulta el historial y no reaplica scripts; no usa IF NOT
EXISTS para esconder incompatibilidades. No hay DROP, semillas, extensiones ni
SQL de contraseñas literales en las migraciones.

PostgreSQL permite transacciones DDL: el harness verificó rollback de una
migración de prueba fallida y preservación de historial. La creación automática
del historial puede preceder a V1; ante un fallo inspeccionar su estado, no borrarlo.
Nunca editar scripts ya aplicados ni usar repair/baseline/clean para eludir un error.

## Esquemas y modelo pendiente

| Esquema | Responsabilidad solicitada | Estado de tablas de dominio |
| --- | --- | --- |
| identity | Identidades globales y cuentas | Pendiente de columnas/estados aprobados |
| platform | Administración y configuración global | Pendiente de estructura de asignaciones globales |
| tenant | Empresas, sucursales y membresías | Pendiente de diccionario físico y estados de membresía/sucursal |
| security | Perfiles/roles y referencias de sesiones | Pendiente de alcance global/tenant/sucursal |
| catalog | Aplicaciones y módulos | Pendiente de columnas y estados |
| integration | Metadatos de API Keys por tenant | Pendiente de referencias y representación de verificadores |
| audit | Auditoría global/empresarial | Pendiente de nulabilidad tenant/actor y eventos mínimos |
| operations | Metadatos operativos | Solo historial Flyway implementado; entidades operativas pendientes |

Jira LFD-1/LFD-3/LFD-25 define esos dominios, pero no un diccionario físico con
columnas y estados para todos ellos. La épica sí define tenants
`Pendiente → Activo/Suspendido`; eso no define automáticamente los estados de
usuarios, sucursales, perfiles o membresías. Se solicitó aclaración antes de fijar
esas decisiones importantes, conforme a la instrucción de detener esa parte si
existen ambigüedades. No hay tablas de dominio creadas ni un diagrama que simule
relaciones aún no aprobadas.

Definiciones necesarias para continuar:

- Confirmar el diccionario aprobado o autorizar un modelo mínimo concreto.
- Distinguir roles globales de roles empresariales y si los perfiles pertenecen al
  tenant o a la sucursal; no elegir tenant_id nullable como solución implícita.
- Definir estados/nulabilidad de usuarios, membresías, perfiles y sucursales, y
  el mínimo de sesiones/API Keys/auditoría/operaciones que debe materializar F02.T01.

Alternativas de alcance pendientes: catálogos globales de roles con asignaciones
por tenant, frente a definiciones de rol propias de cada tenant; perfiles del
tenant asignados por sucursal, frente a perfiles definidos por sucursal. Cambian
las futuras claves y la integridad de LFD-26, por lo que no se decide sin aclaración.

Una vez resuelto el modelo: UUID nativo `gen_random_uuid()` sin extensiones,
TIMESTAMPTZ, PK/NOT NULL pertinentes, tenant_id en objetos empresariales,
created_at/updated_at según entidad y verificadores/hash en lugar de secretos
en claro. No implementar generación/hash/login ni reglas funcionales. Las FK
compuestas, unicidad normalizada/códigos/membresías e índices complementarios se
reservan a LFD-26; semillas y verificaciones ampliadas a LFD-27. Tener tenant_id
no verifica aislamiento: RLS y filtros/autorización siguen fuera de LFD-25.

## Roles y permisos

| Responsabilidad | Runtime ls_core_app_dev | Migrador ls_core_migrator_dev |
| --- | --- | --- |
| LOGIN / límite de conexiones | Existente / 3, sin modificaciones | Nuevo / 3; permite conexiones internas de Flyway |
| CONNECT ls_core_dev | Existente | Concesión explícita |
| CREATE/TEMP en base | No | No |
| Propiedad de base | No; postgres es propietario | No |
| Propiedad/DDL de esquemas autorizados | No | Sí, los ocho esquemas y sus propios objetos |
| Tablas/historial Flyway | Sin concesiones nuevas | Propietario de sus objetos/historial |
| SUPERUSER/CREATEDB/CREATEROLE/REPLICATION/BYPASSRLS | No | No |
| Membresías y herencia de otros roles | No | No |
| Permisos sobre objetos ajenos | No se amplían | No se conceden |

No se concede DML runtime en tablas futuras todavía. El migrador no ejecuta la
aplicación. Los esquemas nuevos no conceden permisos a PUBLIC. La herramienta
de provisión se niega a crear el rol si otras bases permiten CONNECT a PUBLIC:
NOINHERIT no elimina esos privilegios. La observación LFD-23 de cinco bases
sigue abierta y probablemente impida la provisión real hasta una decisión de
seguridad. No se inspeccionaron ni cambiaron sus ACL reales en esta ejecución.

## Operación local: preparada, no autorizada sobre el servidor real

Requiere Python 3, psql 17 existente y JDK 21 para Maven. La herramienta fija
127.0.0.1:5432/ls_core_dev; no acepta URLs arbitrarias ni crea bases. Sin argumentos
solo inspecciona en transacción READ ONLY, usando credencial administrativa local:

```powershell
python scripts/manage_migrations.py
# Equivale a:
python scripts/manage_migrations.py inspect
```

**Los comandos siguientes son procedimientos preparados, no autorización para
ejecutarlos ahora.** El propietario debe autorizar específicamente la provisión
del rol de ámbito clúster y, por separado, la aplicación de migraciones reales.

```powershell
# Solo después de autorización específica del propietario y revisión del plan:
python scripts/manage_migrations.py provision
# Requiere la frase PREPARE LFD-25 ls_core_dev y el nuevo password por psql \password.

# Con el migrador preparado y tras revisar su contrato:
python scripts/manage_migrations.py info
python scripts/manage_migrations.py validate
# Solo después de autorización específica para migrar:
python scripts/manage_migrations.py migrate
# Requiere MIGRATE LFD-25 ls_core_dev.
```

POSIX: usar `python3` en esos comandos. psql se resuelve en PATH (Windows utiliza
la instalación PG17 existente); no se instala nada en el equipo. El wrapper rechaza
stdin redirigido y solicita contraseñas sin eco. Solo las pasa por entorno efímero
al proceso hijo; no por argumentos, archivos persistentes o SQL literal. No usar
transcripciones, -X de Maven, DEBUG de JDBC ni copias de logs privados.

El plugin usa `FLYWAY_URL`, `FLYWAY_USER`, `FLYWAY_PASSWORD` en el proceso del
comando administrativo. El wrapper construye URL/usuario locales y pide el secreto,
sin exigir variables persistentes. `JAVA_HOME`/PATH apuntan a JDK 21. `LS_DB_*`
continúa siendo solo del backend; no configurar allí al migrador.

Comandos Maven de referencia, desde backend, solo para operadores autorizados
con las variables Flyway externas ya preparadas:

```powershell
.\mvnw.cmd -B -ntp -Pdatabase-migrations flyway:info
.\mvnw.cmd -B -ntp -Pdatabase-migrations flyway:validate
.\mvnw.cmd -B -ntp -Pdatabase-migrations flyway:migrate
```

POSIX: `sh ./mvnw` en lugar de `.\mvnw.cmd`. El wrapper es la vía recomendada,
porque acota destinos/operaciones y confirma escrituras. Los comandos Maven
directos no aportan esas confirmaciones y no deben usarse contra la instancia
real sin la autorización exigida.

## Estados incompatibles y recuperación

La provisión rechaza cualquier rol migrador o esquema autorizado ya existente,
incluida una segunda ejecución: no los adopta, restablece contraseñas ni cambia
propietarios. Las consultas y el error son el punto de partida para una revisión
manual. La preparación completa es transaccional, incluida \password: una
cancelación/fallo revierte sus cambios. Un resultado de commit incierto exige
inspección nueva y decisión humana, no reintento reparador.

Checksum distinto: conservar script/historial y recuperar la versión publicada;
un cambio nuevo se expresa en una migración posterior. SQL incompatible o permisos
insuficientes: detenerse y revisar el contrato; no elevar runtime ni borrar bases.
El bootstrap LFD-23 mantiene intacto su contrato y puede rechazar la nueva ACL del
migrador; no volver a aplicarlo como reparador después de extender la base con F02.

## Pruebas desechables y CI

Desde la raíz, con JDK 21 y PostgreSQL 17 ya instalado:

```powershell
$env:JAVA_HOME = 'C:\Program Files\Java\jdk-21.0.10'
$env:Path = "$env:JAVA_HOME\bin;$env:Path"
Set-Location backend
.\mvnw.cmd -B -ntp clean verify
Set-Location ..
python scripts/tests/test_postgres_migrations.py
```

POSIX:

```sh
(cd backend && sh ./mvnw -B -ntp clean verify)
LS_PG_BIN=/usr/lib/postgresql/17/bin python3 scripts/tests/test_postgres_migrations.py
```

`LS_PG_BIN` solo identifica binarios existentes, no servidor. El harness no acepta
host/URL/data directory existentes. Ejecuta initdb en TEMP y usa un puerto efímero
distinto de 5432. Genera contraseñas aleatorias en memoria, autentica por SCRAM y
elimina solo su directorio validado después de detener su propio clúster.
Para el fixture prepara ACL de postgres/template1 dentro del clúster nuevo; nunca
reutiliza ni modifica esas bases de la instalación compartida.

Prueba provisión/rechazos, ocho esquemas, historial V1, repetición, checksum
alterado y rollback de V99 de prueba, permisos y arranque JDBC sin migración.
**Todavía no prueba tablas de dominio**, porque no están implementadas. El job
CI `migrations` prepara PG17 solo en el runner desechable y ejecuta el mismo
harness; backend/frontend conservan sus jobs. El resultado real de CI y los
pendientes se registran en [verificación LFD-25](verification-lfd-25.md).
