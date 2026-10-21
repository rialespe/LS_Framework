# LFD-29 D3.1: contexto real falsificado, denegación conservada

Base: `e37e57b3932767e635961e0d00d6461559f10c60`. Extensión exclusiva de
`scripts/tests/postgres_rls_contract.py`; D1/D2 se conservan sin cambios.

## Variables y escenarios

Se falsifican las nueve claves de `ContextTransaction`: `ls.tenant_id`,
`ls.application_id`, `ls.branch_id`, `ls.origin`, `ls.actor_id`,
`ls.credential_id`, `ls.session_context_id`, `ls.membership_id` y
`ls.branch_membership_id`. Son entradas adversariales, nunca evidencia de identidad.
Los señuelos D2 `ls.user_id`, `ls.session_id` y `ls.is_admin` no pertenecen a ese
contrato y no sustituyen ninguna de sus claves; sus pruebas originales permanecen.

| Escenario nuevo | Tablas | SELECT / INSERT / UPDATE / DELETE |
| --- | --- | --- |
| SESSION completo aparentemente coherente: tenant A, sucursal A, aplicación del fixture y cinco UUID sintéticos | Las 22 protegidas | Los cuatro comandos, como runtime y propietario |
| API_KEY: actor y credencial iguales; sucursal y vínculos SESSION vacíos | tenant.tenants y tenant.branches | Los cuatro comandos, ambos roles |
| SESSION contradictorio: tenant A, sucursal B | Las dos tablas D1 | Los cuatro comandos, ambos roles |
| SESSION incompleto: membership_id ausente, no establecido | Las dos tablas D1 | Los cuatro comandos, ambos roles |
| Cambio exclusivo de origin a API_KEY, conservando vínculos SESSION | Las dos tablas D1 | Los cuatro comandos, ambos roles |

Se reutilizan los tenants/sucursales no vacíos A/B de D1 y la aplicación sintética
de V8. Los UUID de actor, credencial y vínculos no crean usuarios, sesiones,
membresías ni claves. La coherencia aparente de los valores no acredita sus relaciones.
API_KEY representa ausencias como cadenas vacías, igual que LFD-28; no convierte
la sucursal vacía en acceso global. El escenario incompleto verifica NULL real
para la clave omitida en una conexión nueva.

Cada intento usa BEGIN explícito, set_config local y comprobación de las nueve
claves en la misma sesión/transacción que ejecuta el comando. Captura el SQLSTATE
del comando antes del cierre: los errores esperados requieren ROLLBACK correcto;
los comandos sin error requieren COMMIT y cero filas. El control de errores solo
se relaja para el comando adversarial; fallos de preparación/cierre no son éxitos.

Runtime: ACL 42501. Propietario: INSERT rechazado por RLS 42501; SELECT y
UPDATE/DELETE no exponen ni cambian filas. Se rechazan falsos positivos por
constraints o transacción abortada (25P02). DEFAULT VALUES prueba la barrera RLS,
no la validez de un alta empresarial. Las otras veinte tablas siguen vacías.
Snapshots administrativos de las 29 tablas se comparan después de cada escenario;
ownership, ACL y flags RLS también deben permanecer iguales. Las comprobaciones
existentes de roles, políticas, semillas, limpieza exacta e historial se conservan.

## Validación focalizada

Con JDK 21 y binarios PostgreSQL 17 disponibles, desde la raíz:

```powershell
python scripts/tests/postgres_rls_contract.py
```

El arnés existente crea y verifica su propio PGDATA, puerto efímero y servidor
PG17, y detiene/elimina solo ese clúster. No utiliza PostgreSQL Windows compartido
ni Docker de desarrollo. CI estándar integra el mismo contrato en su job aislado.

Resultado focalizado: PASS en una única ejecución local del incremento; salida 0
y confirmación del arnés de clúster propio detenido y eliminado.
Cobertura nueva: 240 intentos, 486 comprobaciones correctas; total: 1317
comprobaciones correctas, incluidas las 831 anteriores. No son 1317 pruebas
independientes. D1/D2, datos, ACL, ownership, flags RLS, roles y checksums V1–V9
pasaron sus verificaciones. No se ejecutaron otras suites manuales.

## Límites y decisiones pendientes

El resultado sigue demostrando exclusivamente denegación total: NO autenticación,
autorización positiva, G2 ni G3. No se ejecutan B2-R, R07 ni R01–R38. F03 y LFD-29
no se declaran terminados; LFD-30 permanece en Backlog.

Siguen pendientes la frontera de evidencia no falsificable, autenticación real,
lectura previa sin circularidad RLS, reglas funcionales, ACL mínimas y semántica
de frescura/revocación/expiración. Ningún UUID o SET ls.* resuelve esas decisiones.
No se agregan políticas, roles, grants, migraciones ni adaptadores productivos.
