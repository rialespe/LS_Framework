# Plantilla de prompt por incidencia Jira

Copiar y completar los campos `{{...}}` antes de enviar. No ejecutar comandos con
placeholders. Aplicar la [guía operativa](README.md) y los [ADR](../adr/README.md).
No incluir secretos ni valores de contraseñas, incluso como ejemplos.

## 1. Identificación y precondiciones

- Proyecto/repositorio y URL: {{proyecto_repositorio_url}}.
- Épica: {{epica}}.
- Historia padre, URL y estado comprobado: {{historia_padre}} — En curso.
- Ticket Jira, URL y estado comprobado: {{ticket_jira}} — En curso.
- Título: {{titulo}}.
- Fecha/fuente de la comprobación Jira: {{fecha_fuente}}.
- Rama base: `main`; referencia de arranque: `origin/main` actualizada.
- SHA esperado de referencia: {{sha_base_esperado}}.
- Rama de trabajo: `feature/{{ticket_jira}}-{{descripcion_slug}}`.
- Dependencias, estado y PR/commits ya fusionados: {{dependencias}}.

Confirmar padre y subtarea En curso antes de generar este prompt. Si una dependencia
requerida no está integrada, no comenzar el trabajo que dependa de ella.

## 2. Objetivo y contexto

- Resultado concreto esperado y contexto de negocio: {{objetivo_contexto_negocio}}.
- Estado técnico implementado, stack y versiones que preservar: {{contexto_tecnico}}.
- Documentos/decisiones vigentes y evidencia disponible: {{fuentes}}.
- Incógnitas o propuestas aún no aprobadas: {{incognitas}}.

No describir supuestos ni diseño futuro como implementación o decisión aceptada.

## 3. Primera operación obligatoria: Git

Antes de crear o editar archivos, ejecutar desde la raíz:

```sh
git remote -v
git status --porcelain=v1 --untracked-files=all
git status -sb
git branch --show-current
git fetch origin main
git rev-parse origin/main
git branch --list '*{{ticket_jira}}*'
git ls-remote --heads origin '*{{ticket_jira}}*'
```

Verificar repositorio, cambios/archivos locales, commits sin publicar, divergencias,
SHA y dependencias. Si hay trabajo pendiente o una rama previa con contenido,
detenerse y pedir instrucciones; no descartarlo ni mezclarlo con esta tarea.
No usar automáticamente reset --hard, clean -fd, stash, checkout -f, force-push,
eliminaciones ni merges para resolver la situación.

Solo con el entorno seguro:

```sh
git switch -c feature/{{ticket_jira}}-{{descripcion_slug}} origin/main
git rev-parse HEAD
git rev-parse origin/main
git push -u origin feature/{{ticket_jira}}-{{descripcion_slug}}
```

Este prompt autoriza crear y publicar esa rama y posteriormente sus commits dentro
del alcance. Confirmar igualdad inicial de SHA y rama remota. Si falla el push,
detenerse antes de editar. Nunca commit/push a main ni alteración forzada de historial.

## 4. Alcance

- Trabajo permitido y entregables: {{alcance_permitido}}.
- Archivos que inspeccionar: {{archivos_relevantes}}.
- Archivos que se pueden modificar/crear: {{archivos_autorizados}}.
- Exclusiones funcionales y técnicas: {{exclusiones}}.
- Infraestructura y contratos que preservar: {{invariantes}}.

Inspeccionar lo existente antes de editar. No agregar funcionalidades, dependencias
ni decisiones arquitectónicas ajenas. Si el objetivo exige cambiar una exclusión,
describir el impedimento y solicitar la decisión necesaria antes de ese cambio.

## 5. Criterios de aceptación

Completar una fila por criterio con evidencia verificable:

| ID | Condición observable | Archivo/comando/evidencia esperada |
| --- | --- | --- |
| {{CA_ID}} | {{condicion}} | {{evidencia}} |

## 6. Pruebas obligatorias

- Entorno y versiones requeridos: {{entorno}}.
- Validaciones aplicables a este ticket y motivo: {{plan_validacion}}.
- Regresiones que comprobar: {{regresiones}}.

Referencia técnica LS Core: backend `mvnw clean verify` mediante Wrapper (PowerShell
`.\mvnw.cmd -B -ntp clean verify`; POSIX `sh ./mvnw -B -ntp clean verify`). Frontend
`npm ci`, `npm run lint`, `npm run test`, `npm run typecheck`, `npm run build`
(PowerShell: `npm.cmd`). Ejecutar desde backend/frontend según corresponda.

Para documentación, validar enlaces locales reproduciblemente, consistencia y
`git diff --check`; justificar pruebas técnicas no repetidas. No operar sobre una
base real para justificar un cambio documental. Reportar cada validación como OK,
FALLÓ o NO EJECUTADA con resultados observados, nunca promesas de éxito.

## 7. Seguridad y acciones externas

No imprimir ni versionar secretos, usar credenciales productivas en tests, modificar
bases ajenas o ejecutar acciones destructivas sin autorización concreta. No cambiar
permisos globales ni infraestructura validada fuera del alcance.

- Acciones externas adicionales autorizadas: {{acciones_autorizadas_o_ninguna}}.
- Recursos externos explícitamente fuera del alcance: {{recursos_excluidos}}.

Por defecto, no escribir en Jira, abrir PR, desplegar ni fusionar. Las lecturas
necesarias para corroborar contexto y consultar CI son parte de la verificación.

## 8. Publicación y revisión

Revisar diff, alcance y ausencia de secretos/temporales preparados. Ejecutar
`git diff --check` y `git status --short`. Crear commits descriptivos con prefijo
`{{ticket_jira}}:` y publicar únicamente la rama de trabajo. Consultar GitHub
Actions del SHA final y reportar URL, jobs, errores o pendientes.

No abrir PR ni modificar estados Jira en esta ejecución. No realizar merge a main,
ni activar merge automático. Después de revisión y aprobación técnica, el flujo
acordado es registrar revisión Jira, transicionar subtarea a Listo y crear PR hacia
main por instrucción posterior. Dejarlo abierto para aprobación y merge del propietario.

## 9. Informe final y condiciones de cierre técnico

Entregar:

1. Git: rama local/remota, SHA base/inicial/final, commits, URL y estado del árbol.
2. Archivos creados/modificados y explicación del cambio.
3. Tabla de validaciones con comando, resultado y evidencia; incluir fallidas y no ejecutadas.
4. CI: ejecución del SHA final, URL, jobs y checks pendientes.
5. Seguridad: revisión de secretos y recursos externos afectados o preservados.
6. Decisiones, incompatibilidades, riesgos, límites y tareas pendientes.
7. Correspondencia criterio por criterio y recomendación para revisión.

Solo recomendar cierre técnico si los entregables y validaciones aplicables tienen
evidencia y no hay bloqueos ocultos. Esa recomendación no cierra Jira ni autoriza
el PR: ChatGPT y el propietario revisan antes de las acciones posteriores.
