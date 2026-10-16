[CmdletBinding()]
param(
    [Parameter(Mandatory = $true)][string]$DatabaseHost,
    [Parameter(Mandatory = $true)][ValidateRange(1, 65535)][int]$DatabasePort,
    [Parameter(Mandatory = $true)][string]$DatabaseName,
    [Parameter(Mandatory = $true)][string]$DatabaseUser
)

$ErrorActionPreference = 'Stop'
$jar = Join-Path $PSScriptRoot '../backend/target/ls-core-0.0.1-SNAPSHOT.jar'
if (-not (Test-Path -LiteralPath $jar)) {
    throw 'Build the backend with Maven Wrapper before starting it.'
}
if (-not $env:JAVA_HOME -or -not (Test-Path -LiteralPath "$env:JAVA_HOME/bin/java.exe")) {
    throw 'Set JAVA_HOME to the installed JDK 21 directory.'
}

$settings = @{
    LS_DB_HOST = $DatabaseHost
    LS_DB_PORT = [string]$DatabasePort
    LS_DB_NAME = $DatabaseName
    LS_DB_USERNAME = $DatabaseUser
}
$previous = @{}
foreach ($name in @($settings.Keys) + @('LS_DB_PASSWORD')) {
    $previous[$name] = [Environment]::GetEnvironmentVariable($name, 'Process')
}

$password = Read-Host 'PostgreSQL application password (local input only)' -AsSecureString
$pointer = [IntPtr]::Zero
try {
    foreach ($name in $settings.Keys) {
        [Environment]::SetEnvironmentVariable($name, $settings[$name], 'Process')
    }
    $pointer = [Runtime.InteropServices.Marshal]::SecureStringToBSTR($password)
    $env:LS_DB_PASSWORD = [Runtime.InteropServices.Marshal]::PtrToStringBSTR($pointer)
    [Runtime.InteropServices.Marshal]::ZeroFreeBSTR($pointer)
    $pointer = [IntPtr]::Zero
    & "$env:JAVA_HOME/bin/java.exe" -jar $jar --spring.profiles.active=postgres
} finally {
    foreach ($name in $previous.Keys) {
        [Environment]::SetEnvironmentVariable($name, $previous[$name], 'Process')
    }
    if ($pointer -ne [IntPtr]::Zero) {
        [Runtime.InteropServices.Marshal]::ZeroFreeBSTR($pointer)
    }
    $password.Dispose()
}
