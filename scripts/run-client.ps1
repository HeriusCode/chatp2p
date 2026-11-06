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
$cameraJars = @(
    'webcam-capture-0.3.12.jar', 'bridj-0.7.0.jar', 'slf4j-api-1.7.36.jar',
    'slf4j-nop-1.7.36.jar',
    'jcodec-0.2.5.jar', 'jcodec-javase-0.2.5.jar'
) | ForEach-Object { Join-Path $projectRoot "target\dependency\$_" }
$clientClasspath = "$classes;$sqliteJar;$(($cameraJars) -join ';')"

& (Join-Path $PSScriptRoot 'build.ps1')

Push-Location $projectRoot
try {
    if ($Console) {
        & java -cp $clientClasspath chatp2p.client.ClientMain $HostName $Port $ClientName $Password
    } else {
        & java -cp $clientClasspath chatp2p.client.ClientMain
    }
} finally {
    Pop-Location
}
