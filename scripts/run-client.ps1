param(
    [string]$HostName = 'localhost',
    [ValidateRange(1, 65535)]
    [int]$Port = 5000,
    [string]$ClientName = 'client-a',
    [string]$Password = 'chatp2p123',
    [switch]$Console
)

$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path -Parent $PSScriptRoot
$classes = Join-Path $projectRoot 'target\classes'
$sqliteJar = Join-Path $projectRoot 'target\dependency\sqlite-jdbc-3.54.1.0.jar'

& (Join-Path $PSScriptRoot 'build.ps1')

Push-Location $projectRoot
try {
    if ($Console) {
        & java -cp "$classes;$sqliteJar" chatp2p.client.ClientMain $HostName $Port $ClientName $Password
    } else {
        & java -cp "$classes;$sqliteJar" chatp2p.client.ClientMain
    }
} finally {
    Pop-Location
}
