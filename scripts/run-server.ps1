param(
    [ValidateRange(1, 65535)]
    [int]$Port = 5000,
    [switch]$Console
)

$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path -Parent $PSScriptRoot
$classes = Join-Path $projectRoot 'target\classes'
$postgresJar = Join-Path $projectRoot 'target\dependency\postgresql-42.7.14.jar'
$sqliteJar = Join-Path $projectRoot 'target\dependency\sqlite-jdbc-3.54.1.0.jar'

& (Join-Path $PSScriptRoot 'build.ps1')

Push-Location $projectRoot
try {
    if ($Console) {
        & java -cp "$classes;$postgresJar;$sqliteJar" chatp2p.server.ServerMain $Port
    } else {
        & java -cp "$classes;$postgresJar;$sqliteJar" chatp2p.server.ServerMain
    }
} finally {
    Pop-Location
}
