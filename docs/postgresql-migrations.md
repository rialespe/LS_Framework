# Migraciones PostgreSQL — LFD-25/LFD-26/LFD-27

**Estado: infraestructura preservada, 29 tablas de dominio V2–V5, integridad compuesta/unicidad V6–V7 y 28 semillas globales V8**, conforme al [comentario 10044](https://lssoluciones.atlassian.net/browse/LFD-25?focusedCommentId=10044) y al [manifiesto aprobado LFD-27](database/seed-catalog.md). Solo se prueban en PostgreSQL 17 desechable. No se ejecutó provisión ni migración en PostgreSQL real; la revisión técnica y el cierre Jira siguen a cargo del propietario.

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
| 6 | V6__add_composite_referential_integrity.sql | 14 candidatas, 29 FK compuestas y sustitución de diez FK simples |
| 7 | V7__add_normalized_uniqueness_and_indexes.sql | 11 checks de presencia, 2 UNIQUE naturales, 10 índices únicos normalizados y 18 de apoyo |
| 8 | V8__seed_approved_global_catalogs.sql | 28 semillas globales aprobadas: LS Core, módulos, permisos, plantillas y asociaciones; sin acceso efectivo |

La preparación administrativa crea los ocho namespaces explícitos. V1 comprueba
ese contrato: no se concede CREATE sobre la base al migrador para crear esquemas
arbitrarios. Ver la decisión **propuesta**, aún no aprobada, en el
[ADR 0004](adr/0004-migraciones-administrativas.md).

Historial: `operations.flyway_schema_history`, creado y mantenido por el migrador,
sin USAGE ni permisos de tabla para runtime. Flyway registra versión, script,
checksum, autor y resultado. `validateOnMigrate=true`, `outOfOrder=false`,
`baselineOnMigrate=false`, `createSchemas=false`, `cleanDisabled=true`.
La segunda ejecución consulta el historial y no reaplica scripts; no usa IF NOT
EXISTS para esconder incompatibilidades. V8 inserta exclusivamente el manifiesto global aprobado, con UUID explícitos y sin upsert. No hay DROP, extensiones ni
SQL de contraseñas literales en las migraciones.

PostgreSQL permite transacciones DDL: el harness verificó rollback de una
migración de prueba fallida y preservación de historial. La creación automática
del historial puede preceder a V1; ante un fallo inspeccionar su estado, no borrarlo.
Nunca editar scripts ya aplicados ni usar repair/baseline/clean para eludir un error.

## Esquemas y modelo aprobado

El [diccionario físico](database/data-dictionary.md) enumera las **29 tablas**, todas sus columnas, tipos, defaults, nulabilidad, PK, claves candidatas, FK compuestas, unicidad e índices implementados. Incluye el diagrama Mermaid físico.

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

`operations.flyway_schema_history` es una tabla técnica adicional: no cuenta entre las 29. V1–V7 permanecen intactas. V8 deja siete tablas con 28 filas y las otras 22 vacías; no hay concesiones al runtime.

Los timestamps técnicos usan CURRENT_TIMESTAMP para la creación y la inicialización de updated_at; no hay trigger y el escritor futuro deberá mantener updated_at. UUID usa gen_random_uuid() nativo, sin extensiones. Estados sin defaults: solo se comprueban los vocabularios aprobados de tenants, habilitaciones y tipos de administrador global. Los demás estados/ciclos funcionales siguen pendientes. users exige username o email no NULL; V7 agrega normalización y unicidad de username/correo verificado, rechazo de blanco y unicidades por ámbito.

LS Soluciones administra permisos/plantillas globales versionadas; cada tenant tendrá copias editables de roles/perfiles por aplicación, sin herencia silenciosa. Se permiten múltiples perfiles por persona/sucursal. La designación empresarial tenant.administrators es independiente del nombre de perfil. No se implementa autorización por insertar registros ni la conservación concurrente de un administrador activo.

Las FK simples apuntan a entidades globales inequívocas o a tenant.tenants como raíz empresarial. V6 exige pertenencia compuesta en sucursales, membresías, roles, perfiles, sesiones/contextos y API Keys. Las diez FK simples directamente sustituidas se retiran tras validar sus referencias compuestas, en una única transacción sin borrar datos. Auditoría conserva identificadores sin FK y sin cascadas. V6 también valida coherencia de aplicación entre módulos, permisos y plantillas.

LFD-26 implementa pertenencia tenant/aplicación/identidad/acceso, claves candidatas, FK compuestas y unicidades normalizadas/empresariales e índices. No comprueba vigencia funcional ni reemplaza RLS. LFD-27 incorpora semillas de catálogos/permisos/plantillas y pruebas ampliadas. PUBLISHED significa definición publicada, TENANT limita al tenant autorizado y BRANCH a la sucursal autorizada; no se agregan CHECK de vocabulario. F10 desarrollará administración RBAC y F11 habilitaciones/navegación. RLS, autenticación, revocación funcional y autorización son posteriores. Tener tenant_id no garantiza aislamiento.

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

Prueba provisión/rechazos, ocho esquemas, las 29 tablas y cada columna/tipo/nulabilidad/PK/default, historial V1–V8, 28 semillas exactas y 22 tablas vacías, checks aprobados, expiración obligatoria de API Keys/credenciales, ausencia de cascadas de auditoría y privilegios PUBLIC/runtime. El contrato de semillas es independiente del SQL. Conserva los 17 positivos y 81 negativos de LFD-26, snapshots de todas las filas incluidos timestamps y rollback de fixtures. Verifica repetición sin cambios físicos, de datos ni de historial, checksum V8 alterado en copia, colisiones UUID/código normalizado antes de V8, colisión tardía con rollback de DML, rollback DDL de V99 y recuperación con scripts originales sin repair/baseline. Incluye arranque JDBC HTTP 200/UP antes y después de migrar, sin migración automática. El job
CI `migrations` prepara PG17 solo en el runner desechable y ejecuta el mismo
harness; backend/frontend conservan sus jobs. El resultado real de CI y los
pendientes se registran históricamente en [LFD-25](verification-lfd-25.md) y en la continuación [LFD-26](verification-lfd-26.md).

## Continuación LFD-26: aplicación y límites

V1–V7 son inmutables; sus checksums históricos se comprueban en el harness. V6 crea primero candidatas no parciales y después FK, todas MATCH SIMPLE / NO ACTION, inmediatas y validadas. V7 crea reglas de presencia, unicidad e índices. V8 solo inserta el manifiesto global. No hay DDL concurrente que impida el rollback transaccional, ni repair/baseline automáticos. Ante datos incompatibles, detener e inspeccionar; el harness elimina únicamente sus fixtures deliberados en su clúster nuevo, lo que no constituye un procedimiento para corregir datos reales. Ver [evidencia LFD-27](verification-lfd-27.md).

Datos existentes incompatibles provocan fallo cerrado: no se normalizan, deduplican ni corrigen automáticamente. Una ejecución real requerirá autorización y una inspección de datos/ventana de mantenimiento: ALTER TABLE e índices no concurrentes toman locks y su duración crecerá con el volumen. Inspeccionar el historial tras un fallo; restaurar scripts publicados si hay checksum distinto, o preparar una migración nueva aprobada. Nunca editar migraciones aplicadas, borrar bases ni desactivar validaciones para continuar.

Username único global normalizado y correo único solo si está verificado, ambos lower(btrim()). Se permiten duplicados no verificados; la segunda verificación equivalente falla. No hay normalización Unicode ni de proveedores; depende de la collation de la base. Código de sucursal es único por empresa; roles/perfiles por empresa/aplicación. Ver [matriz completa e índices](database/data-dictionary.md).

NULL en module_id/source_template/branch_id omite únicamente su FK opcional MATCH SIMPLE; otras referencias obligatorias permanecen. Las pruebas incluyen UUID no nulo de otro ámbito/inexistente y casos NULL legítimos. La existencia de habilitación no implica estado ACTIVE. Administradores vigentes, historial de designaciones, último administrador, autorización y RLS siguen pendientes de sus definiciones funcionales.

El harness agrega 17 positivos y 81 negativos a sus fixtures válidos, con SQLSTATE 23503/23505/23514 y rollback. Separa expectativas literales en scripts/tests/postgres_integrity_contract.py, sin dependencias nuevas. Para lotes largos usa stdin de psql evitando el límite de argumentos de Windows; no archivos de secretos. Los comandos PowerShell/POSIX y el CI de tres jobs siguen siendo los descritos arriba.
