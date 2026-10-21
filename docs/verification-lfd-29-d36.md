# LFD-29 D3.6 — Revocación entre sentencias en una transacción abierta

Base: `37861d26d1fdbbfeaf24cf3f0f14294cd2ec2029`.
Ampliación del único escenario [D3.5](verification-lfd-29-d35.md), en el mismo
`scripts/tests/postgres_operation_evidence_poc.py`. Reutiliza sus conexiones y su
clúster PG17 nuevo mediante `disposable_cluster`, sin modificar el arnés.
No se cambia el workflow: el paso existente D3.5 ejecuta el escenario ampliado
una sola vez en CI. No hay ejecución manual ni matrices históricas adicionales.

## Cambio mínimo del fixture

- `d35.evidence.revoked_at`, TIMESTAMPTZ nullable, identifica revocación persistida.
- La política SELECT del runtime añade `revoked_at IS NULL` a las condiciones
  existentes de vínculo físico, aislamiento y vencimiento. Las consultas de
  negocio siguen dependiendo de esa evidencia visible; no se altera su permiso.
- El emisor existente `d35_issuer` recibe exclusivamente SELECT de `grant_id` y
  `revoked_at`, y UPDATE de `revoked_at`, además de su INSERT previo. SELECT permite
  filtrar/retornar el identificador de la actualización; no se concede lectura de
  claims ni datos empresariales. Sus políticas SELECT/UPDATE son independientes
  de las del runtime. UPDATE solo admite pasar de no revocado a revocado.
- El runtime no recibe UPDATE de tabla ni de columnas, ownership o membresías.
  La prueba comprueba expresamente ausencia de UPDATE incluso por columna.
  Claims y vínculo no reciben nuevos privilegios de modificación.

Todos estos objetos y permisos existen solo dentro del fixture desechable. No se
modifican migraciones, roles reales, contratos Java, autenticación o ACL productivas.

## Secuencia verificable dentro del mismo escenario

Se conservan primero todos los controles D3.5: A/B positivas, SQL hostil, nueve GUC,
fabricación de evidencia con 42501 y recuperación, y rechazo de reutilización A
en una nueva transacción pese a conservar su evidencia sin expirar.

Después, mientras B sigue en su transacción original:

1. B vuelve a obtener `B-only-marker`. Se comprueban READ COMMITTED y la igualdad
   de su vínculo nativo completo con el obtenido al admitirla.
2. Se verifica que el emisor usa otro PID y el LOGIN `d35_issuer`. Esa conexión
   existente ejecuta BEGIN, UPDATE de la entrada B con `RETURNING grant_id`, cuenta
   exactamente una fila y confirma COMMIT. El marcador de finalización de `psql`
   llega después del COMMIT, con ON_ERROR_STOP activo.
3. La primera sentencia posterior de B es una consulta de negocio: debe devolver
   cero filas. B no ejecuta COMMIT, ROLLBACK, BEGIN, reconexión o nueva admisión
   durante esta fase. Se vuelven a comprobar conexión/backend, xid8 y aislamiento.
4. El administrador verifica por otro canal que la fila empresarial B permanece
   físicamente presente y que su evidencia original sigue almacenada, revocada,
   sin expirar y con claims sin cambios. No se atribuye el rechazo a borrado o TTL.
5. B establece y verifica de nuevo sus nueve GUC originales. Ni la lectura de
   negocio ni seleccionar su propio grant recuperan acceso. Su vínculo sigue
   siendo idéntico y `SELECT 1` finaliza normalmente: no hay transacción abortada.

Solo después de esas comprobaciones se cierra B con ROLLBACK. Se conserva la
verificación final de parada, eliminación de PGDATA y listener cerrado antes de
imprimir `PASS D3.6`. Cualquier error de preparación, actualización, COMMIT,
consulta, comprobación o limpieza falla CI; cero filas sin positivo previo no basta.

## Alcance y resultado

Un PASS acredita únicamente que la siguiente lectura bajo READ COMMITTED observa
la revocación de esta evidencia confirmada por otra conexión, manteniendo abierta
la transacción empresarial y presentes los datos. Presupone el emisor y el
coordinador confiables del fixture; los identificadores nativos y las GUC no son
credenciales. No integra autenticación real ni propagación desde fuentes externas.

No demuestra cancelación de consultas ya iniciadas, cierre de cursores retenidos,
comportamiento bajo REPEATABLE READ/SERIALIZABLE, revocación fuerte universal,
disponibilidad o recuperación ante fallos. No reabre B2-R, R07, R01–R38 o G2/G3.
El ADR 0005 permanece Propuesto y no se cambian estados Jira.

La evidencia del resultado corresponde exclusivamente a la ejecución del CI del
SHA publicado. Este documento describe el criterio y no anticipa un PASS antes de
consultar ese resultado. El siguiente paso es revisión independiente.
