[CmdletBinding()]
param(
    [switch]$Apply,
    [switch]$AdoptExistingRole,
    [switch]$StartBackend,
    [string]$PsqlPath
)

$ErrorActionPreference = 'Stop'
if ($StartBackend -and -not $Apply) {
    throw '-StartBackend requires -Apply. The default mode only inspects the plan.'
}
if ([Console]::IsInputRedirected) {
    throw 'Run in a local interactive terminal. Passwords and confirmation must not be piped.'
}
if ($StartBackend) {
    if (-not (Test-Path -LiteralPath (Join-Path $PSScriptRoot '../backend/target/ls-core-0.0.1-SNAPSHOT.jar'))) {
        throw 'Build the backend before using -StartBackend.'
    }
    if (-not $env:JAVA_HOME -or -not (Test-Path -LiteralPath "$env:JAVA_HOME/bin/java.exe")) {
        throw 'Set JAVA_HOME to JDK 21 before using -StartBackend.'
    }
}

if (-not $PsqlPath) {
    $installed = 'C:\Program Files\PostgreSQL\17\bin\psql.exe'
    if (Test-Path -LiteralPath $installed) {
        $PsqlPath = $installed
    } else {
        $command = Get-Command psql.exe -ErrorAction SilentlyContinue
        if ($command) { $PsqlPath = $command.Source }
    }
}
if (-not $PsqlPath -or -not (Test-Path -LiteralPath $PsqlPath -PathType Leaf)) {
    throw 'Existing psql not found. Specify -PsqlPath; PostgreSQL will not be installed.'
}
$PsqlPath = (Resolve-Path -LiteralPath $PsqlPath).Path
$version = & $PsqlPath --version
if ($LASTEXITCODE -ne 0 -or $version -notmatch '\b17\.') {
    throw 'A PostgreSQL 17 psql client is required.'
}
Write-Host $version
$ready = Join-Path (Split-Path -Parent $PsqlPath) 'pg_isready.exe'
if (-not (Test-Path -LiteralPath $ready)) {
    throw 'pg_isready.exe is missing from the existing client directory.'
}
& $ready -h 127.0.0.1 -p 5432 -t 3
if ($LASTEXITCODE -ne 0) {
    throw 'PostgreSQL is not accepting connections at 127.0.0.1:5432. No service changes made.'
}

# Explicit host, port, role and database; no connection strings or password arguments.
# Ignore inherited libpq settings for this invocation, restoring them on every exit.
$previous = @{}
Get-ChildItem Env: | Where-Object { $_.Name -like 'PG*' } | ForEach-Object {
    $previous[$_.Name] = $_.Value
}
try {
    foreach ($name in $previous.Keys) {
        [Environment]::SetEnvironmentVariable($name, $null, 'Process')
    }
    $env:PGCONNECT_TIMEOUT = '5'
    $env:PGAPPNAME = 'ls-core-bootstrap'
    $env:PGCLIENTENCODING = 'UTF8'
    Write-Host 'Admin password: enter locally in psql. Never paste it into chat.'
    $applyValue = if ($Apply) { 'true' } else { 'false' }
    $adoptValue = if ($AdoptExistingRole) { 'true' } else { 'false' }
    & $PsqlPath -X -W -h 127.0.0.1 -p 5432 -U postgres -d postgres `
        -v ON_ERROR_STOP=1 -v "apply=$applyValue" -v "adopt=$adoptValue" `
        -f (Join-Path $PSScriptRoot 'postgres/initialize-local.sql')
    if ($LASTEXITCODE -ne 0) {
        throw 'Bootstrap stopped. Resources may be partially configured; rerun inspection. Nothing will be deleted or repaired automatically.'
    }
    if (-not $Apply) {
        Write-Host 'Plan only: no PostgreSQL objects changed. Use -Apply to confirm and apply the displayed plan.'
        return
    }

    Write-Host 'Verify as application role; enter its password locally (no password reset).'
    & $PsqlPath -X -W -h 127.0.0.1 -p 5432 -U ls_core_app_dev -d ls_core_dev `
        -v ON_ERROR_STOP=1 -f (Join-Path $PSScriptRoot 'postgres/verify-application.sql')
    if ($LASTEXITCODE -ne 0) {
        throw 'Application connection/permissions failed. Existing resources retained; no password reset attempted.'
    }
    Write-Host 'PostgreSQL verification OK. Run again with -Apply to verify idempotence.'
    Write-Host 'JDBC verification: in another terminal request http://127.0.0.1:8080/actuator/health (expect HTTP 200 / UP).'
    if ($StartBackend) {
        & (Join-Path $PSScriptRoot 'Start-PostgresBackend.ps1') `
            -DatabaseHost 127.0.0.1 -DatabasePort 5432 `
            -DatabaseName ls_core_dev -DatabaseUser ls_core_app_dev
        if ($LASTEXITCODE -ne 0) {
            throw 'Backend process exited with a nonzero code; check its local startup/health results.'
        }
    } else {
        Write-Host 'To start JDBC verification, use -Apply -StartBackend or the existing Start-PostgresBackend.ps1.'
    }
} finally {
    Get-ChildItem Env: | Where-Object { $_.Name -like 'PG*' } | ForEach-Object {
        [Environment]::SetEnvironmentVariable($_.Name, $null, 'Process')
    }
    foreach ($name in $previous.Keys) {
        [Environment]::SetEnvironmentVariable($name, $previous[$name], 'Process')
    }
}
