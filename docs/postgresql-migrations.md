# Migraciones PostgreSQL — LFD-25

**Estado: infraestructura preservada y 29 tablas de dominio implementadas en V2–V5**, conforme al [comentario 10044](https://lssoluciones.atlassian.net/browse/LFD-25?focusedCommentId=10044). Solo se prueban en PostgreSQL 17 desechable. No se ejecutó provisión ni migración en PostgreSQL real; la revisión técnica y el cierre Jira siguen a cargo del propietario.

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
| 2 | V2__create_identity_and_global_catalogs.sql | 10 tablas de identidad, administración global, catálogo y plantillas |
| 3 | V3__create_tenants_and_entitlements.sql | 7 tablas de empresas, membresías, administradores y habilitaciones |
| 4 | V4__create_tenant_security_and_sessions.sql | 7 tablas de roles/perfiles empresariales, sesiones y contextos |
| 5 | V5__create_integration_audit_and_operations.sql | 5 tablas de API Keys, auditoría y operaciones |

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

## Esquemas y modelo aprobado

El [diccionario físico](database/data-dictionary.md) enumera las **29 tablas**, todas sus columnas, tipos, defaults, nulabilidad, PK, FK simples implementadas y relaciones compuestas pendientes. Incluye el diagrama Mermaid físico.

| Esquema | Responsabilidad | Tablas de dominio |
| --- | --- | --- |
| identity | Identidades globales y verificadores de contraseña | 2 |
| platform | Designación explícita OWNER/SUPERADMIN | 1 |
| tenant | Empresas, sucursales, membresías y administradores empresariales | 5 |
| catalog | Catálogo global y habilitaciones empresariales | 4 |
| security | Permisos/plantillas globales, roles/perfiles empresariales y sesiones | 12 |
| integration | API Keys con expiración y permisos explícitos | 2 |
| audit | Evidencia histórica global o empresarial | 1 |
| operations | Metadatos de mantenimiento y ejecuciones | 2 |

`operations.flyway_schema_history` es una tabla técnica adicional: no cuenta entre las 29. V1 permanece intacta. No hay semillas ni concesiones al runtime.

Los timestamps técnicos usan CURRENT_TIMESTAMP para la creación y la inicialización de updated_at; no hay trigger y el escritor futuro deberá mantener updated_at. UUID usa gen_random_uuid() nativo, sin extensiones. Estados sin defaults: solo se comprueban los vocabularios aprobados de tenants, habilitaciones y tipos de administrador global. Los demás estados/ciclos funcionales siguen pendientes. users exige username o email no NULL; normalización, blancos y unicidad se reservan a LFD-26.

LS Soluciones administra permisos/plantillas globales versionadas; cada tenant tendrá copias editables de roles/perfiles por aplicación, sin herencia silenciosa. Se permiten múltiples perfiles por persona/sucursal. La designación empresarial tenant.administrators es independiente del nombre de perfil. No se implementa autorización por insertar registros ni la conservación concurrente de un administrador activo.

Las FK simples apuntan a entidades globales inequívocas o a tenant.tenants como raíz empresarial. Referencias a sucursales, membresías, roles, perfiles y API Keys se documentan para FK compuestas futuras; no se reemplazan por referencias simples inseguras. Auditoría conserva identificadores sin FK y sin cascadas. Las FK/globales existentes tampoco validan aún coherencia de aplicación entre módulos/permisos/plantillas.

LFD-26: pertenencia tenant/aplicación, claves candidatas y FK compuestas, unicidades normalizadas y empresariales e índices complementarios. LFD-27: semillas de catálogos/permisos/plantillas y pruebas ampliadas. RLS, autenticación, revocación funcional y autorización son posteriores. Tener tenant_id no garantiza aislamiento.

Contrato futuro para tenant suspendido: bloquear escrituras; lecturas sujetas a autorización vigente y políticas del tenant; soporte temporal autorizado solo lectura y auditado. Revocación/suspensión afectarán operaciones posteriores aun con sesión previa. **Este DDL no aplica esas reglas.** La política sobre si OWNER cuenta para mínimos de SUPERADMIN y las garantías transaccionales de administración quedan pendientes.

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

No se concede USAGE ni DML runtime en las tablas nuevas. Las futuras funciones requerirán concesiones mínimas revisadas por esquema/tabla, excluyendo el historial; no permisos globales. El migrador no ejecuta la
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

La inspección muestra atributos no sensibles, propietarios de esquemas, el número
de otras bases con PUBLIC CONNECT y, si existe, versiones/checksums/resultados del
historial. No imprime contraseñas ni verificadores. Repetirla después de una
operación autorizada permite revisar el resultado que el wrapper Maven resume.

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

Prueba provisión/rechazos, ocho esquemas, las 29 tablas y cada columna/tipo/nulabilidad/PK/default, historial V1–V5, tablas vacías, checks aprobados, expiración obligatoria de API Keys/credenciales, ausencia de cascadas de auditoría y privilegios PUBLIC/runtime. Verifica repetición sin cambio físico ni de historial, checksum alterado en copia, rollback DDL de V99 y recuperación con scripts originales sin repair. Incluye arranque JDBC HTTP 200/UP sin historial ni migraciones. El job
CI `migrations` prepara PG17 solo en el runner desechable y ejecuta el mismo
harness; backend/frontend conservan sus jobs. El resultado real de CI y los
pendientes se registran en [verificación LFD-25](verification-lfd-25.md).
