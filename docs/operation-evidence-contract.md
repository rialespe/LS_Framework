# LFD-29 D3.4 — Contratos inactivos de admisión y evidencia

Primer incremento acotado del [ADR 0005, Propuesto](adr/0005-frontera-confianza-rls.md#10-primer-incremento-implementable-después-de-aprobar-el-adr).
La autorización de este incremento no acepta la arquitectura ni cierra F03/LFD-29.

- `OperationIdentity` identifica por referencia un intento local y permite un solo
  intento atómico de admisión. También un rechazo lo consume. Crearlo no autentica
  ni demuestra conexión, backend o transacción PostgreSQL; no tiene PID/XID o UUID
  que el solicitante pueda presentar como credencial.
- `OperationEvidence.Session` y `.ApiKey` son descripciones candidatas distintas,
  con operación, claims y vencimiento. No son los records de entrada de
  `TrustedContextSource`, no se deserializan desde HTTP y no acreditan autenticidad.
- `OperationAdmission` ofrece métodos separados para ambos orígenes, comprueba
  correspondencia local, selector, origen y vencimiento y **rechaza siempre** con
  `ContextRejectedException`. No existe emisor/verificador instalado ni vía positiva.
  `AuthorizedAdmission` reserva la forma de salida; ninguna ruta lo construye.

No se modifica `TrustedContextSource`, `ContextResolver.AuthorizedContext` ni
`ContextTransaction`. El ejecutor continúa aceptando exclusivamente el contexto
existente; estos tipos no se conectan a su ciclo ni consumen ese contexto.
No hay beans, adaptadores, autenticadores, endpoints, SQL o acceso a bases de datos.

## Frontera pendiente

El futuro mecanismo deberá acreditar identidad real, evidencia autoritativa,
ámbito funcional, frescura y vínculo físico mediante un coordinador confiable.
La igualdad de objetos Java y el consumo local no sustituyen esas pruebas ni
impiden replay entre procesos. No se decide aquí formato del vínculo, roles,
registro PostgreSQL ni protocolo de revocación. Su diseño e integración requieren
revisión y autorización independientes antes de cualquier admisión positiva.

## Prueba focalizada

Una prueba JUnit, `OperationAdmissionTest`, cubre ausencia, origen/selector/vínculo
incoherentes, vencimiento y reutilización local para SESSION/API_KEY. También
exige rechazo de candidatos coherentes sin emisor y verifica separadamente el
consumo del intento para no atribuir antirreplay a la denegación incondicional.
No utiliza autenticadores simulados, Spring ni PostgreSQL; no demuestra integridad
de evidencia en base, autorización positiva, aislamiento o revocación concurrente.

```powershell
$env:JAVA_HOME = 'C:\Program Files\Java\jdk-21.0.10'
cd backend
.\mvnw.cmd -B -ntp '-Dtest=OperationAdmissionTest' test checkstyle:check
```

El CI estándar conserva sus suites existentes; no se agregan ejecuciones de
D1/D2/D3.1, B2-R, R07 o G2/G3 a esta prueba ni se cambia el workflow.
