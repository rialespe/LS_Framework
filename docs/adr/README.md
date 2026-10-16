# Registros de decisiones arquitectónicas (ADR)

Los ADR conservan el contexto, la decisión y sus consecuencias para LS Core.
Permiten entender por qué existe la base técnica sin confundirla con el diseño
futuro. Este índice se incorpora en [LFD-82](https://lssoluciones.atlassian.net/browse/LFD-82),
criterio 4 de [LFD-2](https://lssoluciones.atlassian.net/browse/LFD-2).

## Convención y estados

Usar `NNNN-descripcion.md`, con cuatro dígitos consecutivos desde `0001`, sin
reutilizar números ni renumerar documentos publicados. Cada ADR contiene ID/título,
fecha de registro, estado, alcance, referencias Jira, contexto, decisión,
justificación, alternativas y consecuencias, incluidos límites y trabajo pendiente.

| Estado | Significado |
| --- | --- |
| Propuesto | Requiere revisión y aprobación; no habilita implementación por sí mismo |
| Aceptado | Decisión ya adoptada y respaldada por evidencia o acuerdo explícito |
| Reemplazado | Otro ADR aceptado cambia la decisión; enlazar ambos documentos |
| Obsoleto | El contexto dejó de aplicar, sin sustitución directa; registrar motivo y fecha |

Los ADR 0001–0003 son registros retrospectivos de LFD-22/23/24 y de la política
Git/Jira acordada para LFD-82. Su fecha es la de documentación, no una fecha
inventada de aprobación original. `Aceptado` no aprueba funciones futuras ni
certifica producción, aislamiento tenant o protección de ramas.

## Mantenimiento

Corregir erratas o enlaces en la misma rama documental con trazabilidad Jira.
Una modificación sustancial comienza como un nuevo ADR `Propuesto`, vinculado
al anterior y al ticket que solicita el cambio. Tras revisión del propietario,
registrar fecha/motivo de aceptación y, cuando corresponda, marcar el anterior
`Reemplazado`; no borrar su contexto ni reescribir sus resultados históricos.
Para declarar `Obsoleto`, registrar también revisión, fecha y motivo. Una propuesta
no se convierte en acuerdo por aparecer en un prompt o por generar código.

Separar siempre lo implementado, las convenciones acordadas aún no comprobadas
automáticamente y los trabajos futuros. Actualizar este índice y las referencias
de [arquitectura modular](../architecture/modules.md) cuando cambie una decisión.

## Índice

| ADR | Estado | Fecha de registro | Alcance |
| --- | --- | --- | --- |
| [0001 — Arquitectura y stack](0001-arquitectura-y-stack.md) | Aceptado | 2026-10-16 | Base técnica y fronteras modulares |
| [0002 — PostgreSQL y conectividad](0002-postgresql-y-conectividad.md) | Aceptado | 2026-10-16 | Desarrollo local y JDBC opcional |
| [0003 — Calidad, CI y flujo Git](0003-calidad-ci-y-flujo-git.md) | Aceptado | 2026-10-16 | Verificación implementada y política Git/Jira acordada |

Ver también la [guía Codex](../codex/README.md) y el [README del proyecto](../../README.md).
