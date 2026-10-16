# ADR 0004 — Migraciones administrativas y privilegios por esquema

- Fecha: 2026-10-16.
- Estado: Propuesto; implementación de infraestructura sometida a revisión LFD-25.
- Trazabilidad: [LFD-25](https://lssoluciones.atlassian.net/browse/LFD-25),
  [LFD-3](https://lssoluciones.atlassian.net/browse/LFD-3).
- Amplía [ADR 0002](0002-postgresql-y-conectividad.md), sin reescribir su evidencia.

## Contexto

F02 necesita DDL versionado sin conceder DDL al runtime ni migrar al arrancar
Spring Boot. PostgreSQL CREATE sobre una base permite crear cualquier esquema
en ella; no ofrece un GRANT CREATE limitado a una lista de nombres de esquemas.
Además, el servidor compartido conserva CONNECT heredado a otras bases.

## Propuesta implementada para evaluar

Usar el plugin Maven Flyway 11.14.1 (versión del parent Boot 4.0.8) exclusivamente
en el perfil Maven `database-migrations`, sin dependencias Flyway en el runtime
ni ejecuciones enlazadas al ciclo Maven. Los perfiles Spring no activan ese perfil
Maven. No añadir starter Flyway ni autoconfiguración JDBC.

Separar la preparación administrativa de namespaces del DDL versionado:
el administrador crea el rol nuevo `ls_core_migrator_dev` y exactamente los ocho
esquemas autorizados, propiedad del migrador, en una transacción confirmada.
La contraseña se establece mediante `psql \password` antes de LOGIN/commit.
El migrador recibe CONNECT, propiedad de esos esquemas y de los objetos que cree;
no CREATE/TEMP en base, propiedad de base, membresías ni atributos administrativos.
El rol aplicativo y su límite de tres conexiones se conservan sin cambios.

Flyway usa `operations.flyway_schema_history` y `createSchemas=false`. V1 verifica
identidad, PostgreSQL 17, los ocho namespaces y privilegios. El diccionario físico
recibió aprobación separada en el comentario 10044 de LFD-25 y V2–V5 incorporan sus
29 tablas. Esa aprobación no acepta este ADR ni autoriza provisión/migraciones
reales; su estado permanece Propuesto. La idempotencia depende del historial y
de sus checksums.
Se deshabilita clean y no se automatizan baseline, repair ni out-of-order.

La provisión rechaza roles/esquemas preexistentes, sin adoptarlos o modificarlos.
También rechaza PUBLIC CONNECT en otras bases antes de crear el rol: no puede
garantizar un migrador limitado a la base objetivo si hereda ese acceso. No cambia
ACL ajenas; la instalación real requiere revisión de seguridad y autorización
específica. Esta negativa no modifica ni remedia la observación LFD-23.

## Alternativas

| Alternativa | Motivo para no seleccionarla en esta propuesta |
| --- | --- |
| Migraciones al arranque Spring | Mezcla runtime y credenciales DDL; contradice LFD-25 |
| Flyway como postgres | Permisos excesivos para las migraciones |
| Conceder CREATE sobre la base al migrador | Permite crear namespaces no autorizados; incluso revocándolo después añade una ventana de privilegio |
| Crear los esquemas mediante una función SECURITY DEFINER | Introduce código privilegiado persistente y superficie adicional innecesaria |
| Continuar pese a PUBLIC CONNECT en otras bases | No cumple el contrato de separación solicitado para el nuevo rol |

## Consecuencias

La creación inicial de esquemas está en la preparación administrativa explícita,
no en una migración Flyway con CREATE SCHEMA. Así se evita conceder database CREATE
al migrador. Ese es un ajuste consciente a la secuencia orientativa del ticket,
pendiente de revisión técnica. Los ocho nombres y su creación quedan en SQL
versionado por Git y las precondiciones en V1; Flyway versionará el DDL posterior.

No hay autorización para ejecutar provisión/migraciones reales. El bootstrap
LFD-23 conserva su contrato estricto y puede rechazar ACL ampliadas por un migrador
futuro: no usarlo como reparador después de F02. RLS, permisos funcionales,
restricciones finales y semillas pertenecen a sus historias. Ver la
[guía de migraciones](../postgresql-migrations.md).
