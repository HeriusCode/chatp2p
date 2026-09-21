param(
    [string]$HostName = 'localhost',
    [ValidateRange(1, 65535)]
    [int]$Port = 5000,
    [string]$ClientName = 'client-a',
    [switch]$Console
)

$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path -Parent $PSScriptRoot
$classes = Join-Path $projectRoot 'target\classes'

& (Join-Path $PSScriptRoot 'build.ps1')

Push-Location $projectRoot
try {
    if ($Console) {
        & java -cp $classes chatp2p.client.ClientMain $HostName $Port $ClientName
    } else {
        & java -cp $classes chatp2p.client.ClientMain
    }
} finally {
    Pop-Location
}
