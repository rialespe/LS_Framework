# Guía operativa de Codex para LS Core

Protocolo oficial del equipo para ejecutar una subtarea de LS Core con Codex.
Documenta la política acordada en [LFD-82](https://lssoluciones.atlassian.net/browse/LFD-82)
y el [ADR 0003](../adr/0003-calidad-ci-y-flujo-git.md). No concede permisos externos
ni configura protecciones GitHub. Una instrucción de tarea puede autorizar trabajo
concreto dentro de este protocolo; no se deducen permisos para cerrar Jira o fusionar.

## A. Inicio de una tarea

1. Identificar ticket, título, épica e historia padre; leer descripción, criterios,
   subtareas y dependencias. Consultar estados actuales, no solo texto histórico.
2. Confirmar historia padre **En curso** y subtarea **En curso antes de generar el
   prompt**. ChatGPT/propietario gestionan las transiciones con autorización; Codex
   no las realiza implícitamente. Si no puede consultar Jira, declarar la limitación
   y obtener la confirmación necesaria antes del trabajo dependiente.
3. Verificar que los cambios previos requeridos ya están fusionados en `main`.
4. Adaptar la [plantilla](prompt-template.md), completar sus campos y delimitar
   aceptación, exclusiones, archivos, pruebas y acciones externas autorizadas.

Leer [README](../../README.md), [módulos](../architecture/modules.md),
[ADR](../adr/README.md) y las evidencias relevantes. El código/configuración
corroboran lo implementado; los registros fechados conservan hechos históricos.
No convertir propuestas, memoria antigua o módulos previstos en decisiones aceptadas.

## B. Git: preparación antes de editar

Desde la raíz, en PowerShell o POSIX:

```sh
git remote -v
git status --porcelain=v1 --untracked-files=all
git status -sb
git branch --show-current
git fetch origin main
git rev-parse origin/main
git branch --list '*LFD-XX*'
git ls-remote --heads origin '*LFD-XX*'
```

Confirmar `origin` de `rialespe/LS_Framework`, ausencia de cambios/archivos importantes
sin seguimiento y de commits sin publicar. Si hay upstream, comparar la divergencia;
si no hay, identificar qué commits quedarían sin publicar. No cambiar de rama para
ocultar esa situación. Registrar SHA base y verificar dependencias por commits/PR.
`fetch` actualiza la referencia `origin/main`; no exige hacer merge ni alterar `main`
local. Una diferencia respecto del SHA esperado debe explicarse antes de continuar.

Si hay cambios, conflictos, divergencia riesgosa o una rama del ticket con trabajo
previo, detenerse y pedir instrucciones. No usar automáticamente `reset --hard`,
`clean -fd`, `stash`, `checkout -f`, force-push, eliminaciones o descartes.
No reutilizar ni sobrescribir una rama existente sin revisar su contenido.

Solo con el entorno seguro y el nombre real sustituido:

```sh
git switch -c feature/LFD-XX-descripcion origin/main
git rev-parse HEAD
git rev-parse origin/main
git push -u origin feature/LFD-XX-descripcion
```

Los dos SHA deben coincidir inicialmente. Comprobar tracking y existencia remota.
El prompt estándar autoriza esta publicación previa: si el push falla por permisos
o autenticación, detenerse antes de editar; no eludir controles ni imprimir tokens.
Trabajar solo en esa rama. Después de validar y revisar diff, preparar únicamente
archivos del ticket, crear commits `LFD-XX: descripción` y publicar esa misma rama.
No commit/push directo a `main`, merges automáticos ni force-push.

## C. Desarrollo acotado

Inspeccionar archivos antes de editarlos y respetar el alcance del ticket. Mantener
compatibilidad entre módulos, contratos y versiones fijadas. Preservar infraestructura
validada; no rehacer el proyecto ni introducir funciones, paquetes, dependencias o
decisiones ajenas. Documentar un impedimento en vez de ampliar el alcance por cuenta
propia. Para cambios documentales, comprobar los hechos contra sus fuentes.

## D. Seguridad

No registrar contraseñas, tokens, claves o hashes; no publicarlos en prompts,
Git, argumentos o logs. Usar los mecanismos locales de entrada segura ya previstos.
No usar credenciales productivas en tests ni modificar bases ajenas. No ejecutar
acciones destructivas sin autorización explícita y concreta. La autorización de una
subtarea no autoriza cambios de servicios, permisos globales o producción.
No copiar archivos privados ni volcar variables de entorno para diagnosticar.

## E. Pruebas y evidencias

Con JDK 21 seleccionado, desde backend en PowerShell:

```powershell
.\mvnw.cmd -B -ntp clean verify
```

En POSIX: `sh ./mvnw -B -ntp clean verify`. Incluye compilación, smoke y Checkstyle.
Desde frontend:

```powershell
npm.cmd ci
npm.cmd run lint
npm.cmd run test
npm.cmd run typecheck
npm.cmd run build
```

En POSIX usar `npm` en lugar de `npm.cmd`. Mantener Node 24/npm 11 según
[requisitos](../../README.md). No forzar instalación ni degradar versiones para
resolver incompatibilidades. Registrar directorio, entorno, comando, resultado y
evidencia real como **OK**, **FALLÓ** o **NO EJECUTADA**. Un resultado esperado o
una inspección no reemplaza una prueba ejecutada. Ver las
[limitaciones de la suite](../verification-lfd-24.md).

La selección de pruebas responde al alcance: para un cambio exclusivamente
documental, comprobar enlaces locales, consistencia, diff y CI existente; no es
necesario repetir PostgreSQL real. Explicitar qué pruebas técnicas no se repitieron.
Tras el push consultar CI por SHA, URL y jobs; no equiparar workflow válido,
ejecución pendiente y ejecución aprobada.

## F. Informe de Codex

Toda entrega incluye estado Git (rama, tracking, SHA base/final y árbol), archivos
creados/modificados, commits y URL remota; comandos, pruebas ejecutadas, fallidas y
no ejecutadas; riesgos, decisiones, límites, evidencias y tareas pendientes.
Informar URL/resultado de CI y checks pendientes. Separar lo observado por Codex,
lo informado por el propietario y lo registrado históricamente. El informe debe
permitir revisar los criterios sin inferir éxitos ausentes.

## G. Cierre de Jira

**Listo requiere revisión de resultados y criterios de aceptación.** Codex no
cierra tareas mientras ChatGPT y el propietario tengan pendiente la validación.
La entrega técnica no es una transición Jira. Una historia padre tampoco se cierra
solo porque se complete una subtarea: revisar criterios globales e integración.

En F01, LFD-2 permanece En curso hasta validar LFD-82, integrar su PR aprobado y
comprobar la aceptación global. Esta guía no autoriza esas acciones en la ejecución
documental de LFD-82.

## H. Pull Requests

Después de aprobar técnicamente el desarrollo y con la instrucción correspondiente:

1. Registrar la revisión en Jira con criterios y evidencia.
2. Transicionar la subtarea a **Listo**.
3. Crear PR desde su rama hacia `main`.
4. Referenciar Jira en título y descripción; describir archivos, cambios, pruebas,
   riesgos y pendientes.
5. Dejar el PR abierto para revisión y aprobación del propietario.
6. El propietario realiza el merge; **Codex no ejecuta merge automáticamente**.

Si se solicitan correcciones, actualizar la misma rama, validar otra vez y ajustar
Jira según corresponda con autorización. No declarar aprobado un SHA anterior si
hay cambios posteriores sin verificar. Las instrucciones «sin PR» o «sin cambios
Jira» siguen vigentes hasta una autorización posterior expresa.

## Redacción del prompt

Especificar objetivo, archivos de contexto, límites y evidencia esperada; enlazar
fuentes canónicas y marcar incógnitas. La documentación oficial de OpenAI recomienda
delimitar fases y recoger verificaciones observadas en sus ejemplos de
[flujos con Codex](https://developers.openai.com/cookbook/examples/codex/iterating-development-workflows-with-codex).
El orden Jira/PR y las autorizaciones de esta guía son acuerdos propios de LS Core,
no funciones automáticas ni garantías de Codex. No se exige un modelo específico
ni se añaden configuraciones de la herramienta en este ticket.
