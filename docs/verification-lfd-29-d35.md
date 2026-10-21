# LFD-29 D3.5 — Evidencia protegida por operación, caso reproducible

Base: `6c04a774e3e42e4aff4ed8e8f884b152f60368a4`.
Un único escenario en `scripts/tests/postgres_operation_evidence_poc.py`, ejecutado
una sola vez por el job existente `Migrations - isolated PostgreSQL 17` de CI.
No se ejecuta manualmente para esta entrega ni se modifica `disposable_cluster`.
Se reutiliza exclusivamente su ciclo de creación, parada y eliminación; importar
el módulo no ejecuta sus suites ni aplica migraciones.

## Autoridad y vínculo físico

El fixture crea identidades independientes: `d35_owner` NOLOGIN, propietario;
`d35_issuer` LOGIN SCRAM, emisor con INSERT solo en el registro; y `d35_runtime`
LOGIN SCRAM, sin ownership, membresías, DDL ni bypass, con SELECT real sobre datos
y evidencia. Todos los objetos/ACL/políticas existen solo en el PG17 desechable.
A y B usan **el mismo LOGIN empresarial** en dos conexiones, para no atribuir el
aislamiento a dos roles tenant. Las identidades separadas son responsabilidades.

Un coordinador confiable conserva ambas conexiones físicas `psql` y la credencial
del emisor. Un identificador opaco local selecciona únicamente una conexión que
él creó; la emisión no acepta PID/XID o tuplas enviados por el solicitante.
La credencial bearer efímera del fixture se contrasta en memoria y selecciona un
ámbito sintético precargado. No es un autenticador productivo ni demuestra F04/F05.

Antes de liberar SQL adversarial, el coordinador inicia READ COMMITTED y obtiene
directamente del motor: base, LOGIN original, inicio de instancia, PID, inicio del
backend y xid8. El emisor publica una entrada única para ese vínculo y confirma
antes de la lectura empresarial. PID, XID, UUID y GUC son datos públicos, no secretos
ni credenciales. Conocerlos no otorga INSERT, otra identidad ni acceso al emisor.

RLS del registro solo permite al runtime consultar evidencia de su vínculo nativo
actual, no de un UUID elegido ni de `ls.*`. Comprueba aislamiento y vencimiento.
No consulta tablas empresariales ni a sí mismo. RLS empresarial consulta esa
evidencia visible y compara tenant/aplicación/sucursal y las nueve GUC; estas solo
restringen una autorización ya emitida. La evidencia identifica recurso y comando.
El runtime no puede insertar/modificarla ni asumir al emisor. No hay funciones
SECURITY DEFINER, extensiones o claves verificadoras accesibles al runtime.

La custodia y separación del coordinador/emisor son precondiciones explícitas.
El compromiso de esos componentes o del administrador queda fuera del atacante SQL.
No se deduce custodia física de la sola igualdad de PID/XID ni de objetos Java.

## Un escenario y controles contra falsos positivos

1. El administrador comprueba dos tenants y dos filas físicas no vacías. El runtime
   verifica sus privilegios SELECT, RLS activo y ausencia de ownership/bypass o
   capacidad de asumir otro rol. La operación A devuelve exactamente `A-only-marker`;
   la operación B devuelve `B-only-marker`, acreditando evidencia ajena vigente.
2. En la conexión A se establecen y verifican las nueve `ls.*` de B. La consulta
   general y la dirigida a B no devuelven filas; conocer el UUID de la entrada B
   tampoco la hace visible. Se intenta fabricar evidencia B para el vínculo A:
   debe fallar con SQLSTATE **42501**, no por constraint o preparación defectuosa.
   Un savepoint recupera ese error; al restaurar A ambas lecturas positivas deben
   seguir funcionando. Se usa READ WRITE para no confundir ACL con modo read-only.
3. COMMIT y BEGIN sobre la misma conexión conservan backend e inicio, pero cambian
   xid8. Se restauran las nueve GUC de A. La entrada anterior sigue almacenada y
   sin expirar, pero no es visible ni habilita lectura. Una consulta final normal
   confirma que el rechazo no procede de una transacción abortada.

Las nueve claves son tenant_id, application_id, branch_id, origin, actor_id,
credential_id, session_context_id, membership_id y branch_membership_id, con
prefijo `ls.`. Este único caso usa SESSION sintético; no añade otro escenario API_KEY.

`psql` usa ON_ERROR_STOP salvo durante el INSERT adversarial esperado; se captura
su SQLSTATE antes de recuperar el savepoint y se reactiva el control estricto.
Cualquier fallo de preparación, protocolo, lectura o recuperación falla el caso;
cero filas en todas las consultas nunca produce PASS. Los valores esperados son
independientes de los predicados de RLS.

## Ejecución y evidencia

CI añade un único paso `python3 scripts/tests/postgres_operation_evidence_poc.py`
con `LS_PG_BIN=/usr/lib/postgresql/17/bin`; conserva las verificaciones estándar.
No añade paquetes Python ni ejecuta D1/D2/D3.1, B2-R, R07, R01–R38 o G2/G3 desde
este caso. Los contratos estándar preexistentes conservan su ejecución habitual.

El arnés genera PGDATA y puerto efímeros, credenciales nuevas y SCRAM por TCP
loopback. Antes del SQL del fixture se verifica identidad de PGDATA/puerto/versión
y se rechazan 5432/55432. No acepta un endpoint o directorio externo.
Al salir se cierran las sesiones y `disposable_cluster` detiene/elimina solo su
clúster; se comprueban directorios ausentes y listener cerrado antes de emitir PASS.
Las credenciales no se imprimen. El resultado y SHA se verifican en los logs de CI;
este documento no anticipa un resultado antes de esa ejecución.

## Alcance de la conclusión

Un PASS acredita estas tres propiedades en este fixture y bajo su frontera de
confianza: lectura positiva exacta, rechazo de los ataques descritos y no
reutilización entre transacciones de una conexión. No acredita resistencia a
todas las capacidades SQL, integración productiva, autenticación real, frescura
de fuentes externas, revocación concurrente, snapshots/cursores retenidos, pool
entre tenants, HA ni garantías G2/G3. No ejecuta ni reabre sus matrices históricas.

El [ADR 0005](adr/0005-frontera-confianza-rls.md) permanece **Propuesto**. No se
cambian contratos Java, V1–V9, autenticadores, C1/C2, Jira ni diseños protegidos.
El siguiente paso es revisión independiente del código y de sus precondiciones;
esta evidencia no autoriza PostgreSQL productivo ni cierra F03/LFD-29.
