# Verificación documental LFD-82

Fecha: 2026-10-16. Ticket: [LFD-82](https://lssoluciones.atlassian.net/browse/LFD-82).
Rama: `feature/LFD-82-documentar-adr-y-guia-codex-f01`.
SHA inicial/base `origin/main`: `2c52c544ce2923de72de63a5adcd67eb53c8efa0`.

## Fuentes y trazabilidad

Se inspeccionaron README, arquitectura modular, guía PostgreSQL, verificaciones
LFD-22/23/24, POM, Wrapper, package.json, tsconfig, configuración Spring/JDBC y CI.
Se consultó Jira solo en lectura: LFD-2/LFD-82 En curso y LFD-22/23/24 Listo.
La [aprobación final LFD-23](https://lssoluciones.atlassian.net/browse/LFD-23?focusedCommentId=10023)
corrobora resultados del propietario, sin repetir operaciones ni solicitar secretos.

Git y la API GitHub confirmaron [PR #2 fusionado](https://github.com/rialespe/LS_Framework/pull/2)
en el SHA base y [CI final LFD-24 exitosa](https://github.com/rialespe/LS_Framework/actions/runs/38911300657).
Consulta fechada de configuración: `branches/main` devolvió `protected: false` y
`rulesets` devolvió lista vacía. No se crearon protecciones ni se confundieron
con la política humana de revisión. La guía consultó la
[documentación oficial OpenAI](https://developers.openai.com/cookbook/examples/codex/iterating-development-workflows-with-codex)
para contexto, alcance y evidencia; el protocolo Jira/PR es propio del equipo.

## Archivos y alcance

Nuevos: [índice ADR](adr/README.md), [0001](adr/0001-arquitectura-y-stack.md),
[0002](adr/0002-postgresql-y-conectividad.md), [0003](adr/0003-calidad-ci-y-flujo-git.md),
[guía Codex](codex/README.md), [plantilla](codex/prompt-template.md) y este documento.
Modificados: [README](../README.md), [módulos](architecture/modules.md),
[guía PostgreSQL](postgresql-local.md) y verificaciones
[LFD-22](verification-lfd-22.md), [LFD-23](verification-lfd-23.md),
[LFD-24](verification-lfd-24.md).

Todos los cambios son Markdown. No se modifican Java, React, estilos, SQL, PS1,
JDBC, dependencias, versiones ni workflow. Los documentos históricos reciben
seguimientos claramente fechados; su evidencia original permanece intacta.

## Correspondencia con los criterios F01

La cobertura documental no sustituye la aprobación global de la historia.

| Criterio LFD-2 | Evidencia y ubicación |
| --- | --- |
| 1. Arranque backend/frontend reproducible | README y evidencia original LFD-22; comandos conservados |
| 2. PostgreSQL dev y configuración sin secretos | Guía PostgreSQL, aprobación del propietario LFD-23 y ADR 0002; límite CONNECT explícito |
| 3. CI de compilación, tests y lint | Workflow existente, verificación LFD-24, PR #2 y ADR 0003 |
| 4. Arquitectura, ADR y guía de prompts | Arquitectura actualizada, tres ADR, guía y plantilla enlazados desde README |

LFD-82 queda pendiente de revisión. LFD-2 no se declara Listo: requiere validar
esta subtarea, integrar su futuro PR aprobado y comprobar aceptación global.

## Comprobación reproducible de enlaces locales

Requiere solo Git y Python 3; no usa red, credenciales ni paquetes adicionales.
Ejecutar desde la raíz. El bloque Python siguiente se puede copiar a stdin.
Para ejecutar directamente el bloque guardado en este documento en PowerShell:

```powershell
@'
from pathlib import Path
import re
text = Path('docs/verification-lfd-82.md').read_text(encoding='utf-8')
code = re.search(r'```python\n(.*?)\n```', text, re.S).group(1)
exec(compile(code, 'markdown-link-check', 'exec'))
'@ | python -
```

En POSIX se puede usar el mismo contenido entre `python3 - <<'PY'` y `PY`, o
copiar el bloque Python siguiente a stdin de `python3`. No se afirma haber probado
ese envoltorio POSIX localmente.

```python
from pathlib import Path
from urllib.parse import unquote, urlsplit
import re
import subprocess

root = Path.cwd().resolve()
names = subprocess.check_output(
    ['git', 'ls-files', '--cached', '--others', '--exclude-standard', '--', '*.md'],
    text=True, encoding='utf-8',
).splitlines()
files = sorted({root / name for name in names})
errors = []
checked = 0

def prose(text):
    text = re.sub(r'^```[^\n]*\n.*?^```[^\n]*$', '', text, flags=re.M | re.S)
    return re.sub(r'`[^`\n]*`', '', text)

def anchors(path):
    counts = {}
    result = set()
    for heading in re.findall(r'^#{1,6}\s+(.+?)\s*#*$', prose(path.read_text(encoding='utf-8')), re.M):
        slug = re.sub(r'[^\w\- ]', '', heading.lower()).replace(' ', '-')
        count = counts.get(slug, 0)
        counts[slug] = count + 1
        result.add(slug if count == 0 else f'{slug}-{count}')
    return result

for source in files:
    text = prose(source.read_text(encoding='utf-8'))
    refs = {key.strip().casefold(): target for key, target in re.findall(
        r'^\s*\[([^\]]+)\]:\s*(\S+)', text, re.M)}
    targets = re.findall(r'!?\[[^\]\n]*\]\(\s*(<[^>\n]+>|[^)\s]+)(?:\s+[^)]*)?\)', text)
    for label, key in re.findall(r'!?\[([^\]\n]+)\]\[([^\]\n]*)\]', text):
        key = (key or label).strip().casefold()
        if key not in refs:
            errors.append(f'{source.relative_to(root)}: referencia sin definir: {key}')
        else:
            targets.append(refs[key])
    for target in targets:
        url = urlsplit(target.strip('<>'))
        if url.scheme or url.netloc:
            continue
        path = (source.parent / unquote(url.path)).resolve() if url.path else source
        checked += 1
        if not path.is_relative_to(root) or not path.exists():
            errors.append(f'{source.relative_to(root)}: destino inexistente/fuera del repo: {target}')
        elif url.fragment and path.suffix.lower() == '.md' and unquote(url.fragment) not in anchors(path):
            errors.append(f'{source.relative_to(root)}: ancla inexistente: {target}')

print(f'Markdown: {len(files)} archivos; {checked} enlaces locales comprobados; {len(errors)} errores')
for error in errors:
    print(error)
raise SystemExit(bool(errors))
```

Alcance del comprobador: enlaces inline y referencias explícitas/completas o
colapsadas, destinos locales y anclas de encabezados Markdown ATX, ignorando
bloques de código y código inline. No comprueba URLs externas, autolinks, HTML ni
referencias abreviadas implícitas; los documentos del repositorio usan enlaces
inline. La revisión manual complementa ortografía, significado y consistencia.

## Validaciones

| Comprobación | Comando o procedimiento | Resultado | Evidencia |
| --- | --- | --- | --- |
| Preparación Git | remote/status/fetch, historial y push inicial | OK | Árbol limpio, sin rama LFD-82 previa ni commits locales pendientes; base del PR #2 |
| ADR, guía y plantilla | Inspección de archivos e índice | OK | Tres ADR con contexto/decisión/alternativas/consecuencias y guía/plantilla completas |
| Versiones y comportamiento | Contraste con POM/Wrapper/package.json/tsconfig/JDBC/CI | OK | Sin nuevas decisiones ni cambios al stack |
| Trazabilidad Jira/GitHub | Consultas de solo lectura | OK | Estados, comentario 10023, merge PR #2 y CI LFD-24 corroborados |
| Enlaces locales | Bloque reproducible anterior | OK | 13 archivos Markdown, 80 enlaces locales comprobados, 0 errores |
| Espacios y conflictos | `git diff --check` | OK | Salida 0, sin diagnósticos |
| Alcance y secretos | `git diff`, nombres y revisión del contenido añadido | OK | 13 archivos Markdown; sin cambios funcionales ni patrones de credenciales; revisión de contenido realizada |
| Evidencia histórica | Comparación con archivos de origin/main | OK | Los tres documentos conservan íntegro su contenido original después del seguimiento añadido |
| Estado local | `git status --short` | OK | Cambios del ticket identificados, sin temporales ni archivos ajenos |
| Pruebas backend/frontend locales | No repetidas | NO EJECUTADA | Cambio solo documental; las ejecuta CI existente tras push |
| PostgreSQL real | No ejecutado | NO EJECUTADA | Evidencia del propietario referenciada; ningún acceso DB nuevo |
| CI LFD-82 | Consulta GitHub Actions tras push por SHA | OK | [Run 38915475137](https://github.com/rialespe/LS_Framework/actions/runs/38915475137), commit 95200adb056a8cb0e660893e54460d2b55e3606a; completed/success, jobs backend y frontend y todos sus pasos en success |

La ejecución CI anterior comprobó `sh ./mvnw -B -ntp clean verify` en backend y
`npm ci`, lint, test, typecheck y build en frontend con el workflow existente,
sin cambios a su configuración. El commit que registra esta evidencia vuelve a
disparar CI; el informe de entrega identifica su SHA final y su ejecución para
evitar una referencia circular del documento a su propio commit.

## Riesgos y pendientes

Los ADR aceptados son registros retrospectivos, no aprobación de funcionalidades
futuras. La política de PR/revisión no es protección técnica de main. La limitación
CONNECT sigue abierta para una fase de seguridad; no bloquea esta corrección
documental ni se oculta como aislamiento resuelto. No se certifica producción.

Pendientes del flujo: revisión de ChatGPT/propietario, aprobación técnica y posterior
gestión autorizada de Jira/PR. Esta ejecución no abre PR, no cambia estados Jira,
no hace merge ni modifica recursos externos distintos de la rama de trabajo.
