# ADR 0003 — Calidad, CI y control Git/Jira

- Fecha de registro: 2026-10-16.
- Estado: Aceptado; calidad implementada en LFD-24 y política Git/Jira acordada.
- Trazabilidad: [LFD-24](https://lssoluciones.atlassian.net/browse/LFD-24),
  [LFD-82](https://lssoluciones.atlassian.net/browse/LFD-82).
- Alcance: controles técnicos existentes y protocolo de entrega; sin nuevas reglas GitHub.

## Contexto

La base necesita comprobaciones repetibles sin PostgreSQL real y cambios trazables
por incidencia. Una compilación exitosa no basta para acreditar salud HTTP,
renderizado o lint, y un workflow válido no equivale a una ejecución exitosa.

## Decisión implementada

Backend: Maven Wrapper `clean verify` compila con release 21, ejecuta JUnit/Spring
Boot Test y Checkstyle, y empaqueta. Hay diez casos smoke en perfiles `dev` y
`test`, con HTTP real en puertos aleatorios: contexto sin DataSource PostgreSQL,
salud HTTP 200/UP y env/beans/configprops no expuestos. Checkstyle revisa Java
principal y de pruebas con reglas acotadas e incumplimientos que fallan `verify`.

Frontend: Vitest, React Testing Library y jsdom comprueban identidad LS Core,
Multi-Tenant v4.0, estructura accesible y ausencia de solicitudes al backend.
ESLint aplica reglas de errores y React/hooks. Su parser Babel no depende del
compilador TypeScript 7; la [configuración](../../frontend/eslint.config.js)
delega únicamente `no-undef` y `no-unused-vars` de TS/TSX a TypeScript estricto,
incluido en `lint`. `typecheck` conserva `noUnusedLocals`/`noUnusedParameters`;
`build` verifica tipos antes de Vite. Se conserva el lock sin forzar peers.

El [workflow CI](../../.github/workflows/ci.yml) ejecuta jobs separados backend
Java 21 y frontend Node 24/npm 11 en Ubuntu 24.04. Dispara en PR hacia `main` y
push a `feature/**`; usa cachés, concurrencia por ref, timeout, acciones por SHA,
`contents: read` y checkout sin persistir credenciales. No usa PostgreSQL externo
ni secretos. No hay filtro de rutas: los cambios documentales también disparan CI.

## Política de trabajo acordada

Cada subtarea Jira se trabaja en `feature/LFD-XX-descripcion` desde `origin/main`
actualizado, con árbol limpio y dependencias requeridas fusionadas. Los commits
referencian Jira; se publica exclusivamente la rama de la subtarea. No se escribe
directamente en `main`, no se descarta trabajo ni se altera historial por fuerza.

Codex entrega cambios, pruebas reales, límites y evidencias. ChatGPT y el
propietario revisan criterios antes del cierre: registrar revisión en Jira,
transicionar la subtarea a Listo y, solo después de la aprobación técnica y la
instrucción correspondiente, crear PR hacia `main`. El propietario aprueba y
realiza el merge; Codex no fusiona automáticamente ni cierra tareas con revisión
pendiente. El protocolo completo está en la [guía Codex](../codex/README.md).

Esta política **no es una protección técnica de GitHub**. La consulta de solo
lectura del 2026-10-16 devolvió `protected: false` para `main` y ningún ruleset.
El workflow ejecuta verificaciones, pero no impone por sí solo PR, aprobación o
checks obligatorios para merge. LFD-82 no configura protección de ramas ni la
presenta como existente. Ese estado es una fotografía fechada que debe revisarse
antes de confiar en controles del servidor.

## Justificación y alternativas

Dos jobs permiten identificar fallos por stack y trabajar sin credenciales del
equipo propietario. Los smoke verifican la base entregada con poco mantenimiento.
La rama por ticket y el PR facilitan revisar alcance, evidencia y responsabilidad
sin mezclar cambios de otras incidencias.

| Alternativa no adoptada | Límite frente a la decisión actual |
| --- | --- |
| Solo compilar o revisar manualmente | No verifica comportamiento HTTP/DOM ni estilo de forma repetible |
| CI dependiente de ls_core_dev real | Introduciría credenciales y estado externo en pruebas de la base |
| Un job conjunto | Mezcla diagnóstico y tiempos de ambos stacks |
| Commit directo a main o merge automático | Elimina el paso de revisión y aprobación acordado |

## Consecuencias y pendientes

La suite smoke no acredita lógica de negocio, seguridad multi-tenant ni JDBC
autenticado real. El harness PostgreSQL Windows se mantiene local y separado de
CI Linux; véase [verificación LFD-24](../verification-lfd-24.md).
La cobertura de fronteras modulares y nuevos controles de seguridad requerirán
historias propias. Una revisión humana sigue siendo necesaria aunque CI pase.

LFD-24 quedó integrada mediante [PR #2](https://github.com/rialespe/LS_Framework/pull/2),
merge `2c52c544ce2923de72de63a5adcd67eb53c8efa0`. Su SHA final previo al merge
pasó [CI 38911300657](https://github.com/rialespe/LS_Framework/actions/runs/38911300657).
Ver también [ADR 0001](0001-arquitectura-y-stack.md) y
[ADR 0002](0002-postgresql-y-conectividad.md).
