# LFD-29 D3.7 — Suspensión autoritativa e invalidación en una transacción

Base: `b7ea4742a1b0f938aa3a0cf48c4a6ebce8a7d21c`.
Se amplía el único escenario de `scripts/tests/postgres_operation_evidence_poc.py`,
manteniendo los controles [D3.5](verification-lfd-29-d35.md) y
[D3.6](verification-lfd-29-d36.md). No se añade otra suite, escenario o ejecución.
El paso existente del CI PostgreSQL 17 lo ejecuta una vez, sin cambiar el workflow
ni ejecutar manualmente el caso. `disposable_cluster` conserva su implementación.

## Fuente y privilegios, exclusivamente sintéticos

`d35.tenants.status` es NOT NULL, con CHECK ACTIVE/SUSPENDED; ambos tenants se
crean explícitamente ACTIVE. El emisor consulta la fuente ACTIVE antes de emitir
la evidencia inicial del fixture, sin utilizar GUC para decidir su estado.
Esta consulta y la emisión inicial no están serializadas frente a otros escritores:
no se atribuye a esa lectura una garantía contra carreras de admisión futuras.

La conexión existente `d35_issuer`, NOSUPERUSER/NOBYPASSRLS, sin ownership ni
membresías, realiza el cambio confiable. Recibe SELECT(id,status) y UPDATE(status)
en la fuente, con RLS/FORCE y políticas propias: lectura de fuente y transición
ACTIVE → SUSPENDED. Conserva UPDATE exclusivamente de revoked_at en evidencia;
SELECT se amplía a claims para identificar dependencias por tenant. No puede
modificar claims/vínculos ni leer datos empresariales. No se usa SECURITY DEFINER.

El runtime no recibe privilegios de escritura en fuente o evidencia. Se comprueba
la ausencia tanto de permisos de tabla como de INSERT/UPDATE por columna en la
fuente, y de UPDATE por columna en el registro. Las políticas empresariales no
consultan directamente el estado fuente: la pérdida de acceso depende de que la
evidencia haya sido invalidada. Esto evita atribuir el resultado a otro filtro.

## Coordinación explícita y única transacción

Tras completar los controles D3.5, B vuelve a leer su fila real bajo READ COMMITTED
y se verifica el vínculo original completo. Se sustituye la revocación directa
por esta secuencia en la misma conexión confiable del emisor:

1. BEGIN.
2. Un CTE `suspended` actualiza únicamente B de ACTIVE a SUSPENDED y retorna su id.
   Otro CTE `invalidated` revoca las evidencias no revocadas cuyo tenant en claims
   corresponde a los ids realmente retornados por `suspended`; no se selecciona
   una entrada arbitraria por su UUID ni se borra evidencia. Las dos modificaciones
   comparten sentencia y transacción PostgreSQL.
3. Se exige exactamente un tenant y una evidencia afectados en este fixture. Un
   fallo impide llegar a COMMIT; el cierre de la sesión descarta esa transacción.
4. Desde el canal administrativo independiente, una sola consulta comprueba que
   A/B todavía son visibles ACTIVE y sus evidencias no revocadas: los dos cambios
   pendientes siguen invisibles para otras conexiones.
5. COMMIT único, cuya finalización se confirma con ON_ERROR_STOP activo.

La primera sentencia posterior de B es la consulta empresarial, que debe devolver
cero filas. B conserva conexión, backend, xid8 y READ COMMITTED; no se readmite ni
reinicia su transacción. La consulta administrativa posterior ve conjuntamente B
SUSPENDED y su evidencia revocada, mientras A sigue ACTIVE y su evidencia no se
revoca. Los controles D3.6 confirman datos B presentes, evidencia almacenada sin
expirar y claims intactos; las nueve GUC originales no restituyen acceso y una
consulta normal demuestra que no existe transacción abortada.

Se mantienen las lecturas positivas previas, SQL hostil, 42501 y recuperación,
rechazo de reutilización de A y verificación final de parada/eliminación del
clúster y cierre del listener. Cualquier condición incumplida produce FAIL.

## Garantía y límites

Un PASS acredita la invalidación desde **esta operación confiable**: suspensión B
y revocación de su evidencia dependiente se confirman juntas, y la siguiente
lectura de B bajo READ COMMITTED pierde acceso dentro de la transacción original,
sin suspender A. No demuestra un mecanismo obligatorio para todos los escritores.
Los permisos del emisor permiten escrituras separadas: el fixture las coordina
explícitamente, pero no instala un trigger, procedimiento o protocolo que obligue
a todos los futuros caminos a hacerlo. Esa obligatoriedad, las carreras de emisión
y su aprobación siguen pendientes. Tampoco se ensaya rollback inyectado o caída
durante COMMIT; no se atribuye cobertura de fallos a la observación previa al commit.

No prueba autenticación real, propagación desde otras bases, revocación de consultas
ya iniciadas, cursores retenidos, aislamiento distinto de READ COMMITTED, HA o
revocación fuerte universal. Los identificadores nativos, UUID y GUC siguen siendo
datos, no credenciales. El emisor/coordinador confiables son precondiciones.
No se reabren matrices históricas ni se modifica PostgreSQL real, V1–V9, Java,
React, el ADR 0005 o Jira. Los diseños locales protegidos permanecen fuera de Git.

El resultado se obtiene únicamente del CI del SHA publicado; este documento no
anticipa un PASS antes de consultarlo. Detenerse para auditoría independiente.
