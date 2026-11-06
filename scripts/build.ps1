param(
    [switch]$RunTests
)

$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path -Parent $PSScriptRoot
$sourceRoot = Join-Path $projectRoot 'src\main\java'
$testRoot = Join-Path $projectRoot 'src\test\java'
$mainOutput = Join-Path $projectRoot 'target\classes'
$testOutput = Join-Path $projectRoot 'target\test-classes'
$dependencyOutput = Join-Path $projectRoot 'target\dependency'
$legacyOutput = Join-Path $projectRoot 'build'
$sqliteVersion = '3.54.1.0'
$sqliteJar = Join-Path $dependencyOutput "sqlite-jdbc-$sqliteVersion.jar"
$postgresVersion = '42.7.14'
$postgresJar = Join-Path $dependencyOutput "postgresql-$postgresVersion.jar"

function Reset-BuildDirectory {
    param([Parameter(Mandatory = $true)][string]$Path)
    $rootPath = [System.IO.Path]::GetFullPath($projectRoot)
    $targetPath = [System.IO.Path]::GetFullPath($Path)
    if (-not $targetPath.StartsWith($rootPath + [System.IO.Path]::DirectorySeparatorChar,
            [System.StringComparison]::OrdinalIgnoreCase)) {
        throw "Refusing to clean a path outside the project: $targetPath"
    }
    if (Test-Path -LiteralPath $targetPath) {
        Remove-Item -LiteralPath $targetPath -Recurse -Force
    }
}

if (-not (Get-Command javac -ErrorAction SilentlyContinue)) {
    throw 'javac was not found. Install JDK 17+ and add its bin directory to PATH.'
}

$versionText = & javac -version 2>&1
if ($LASTEXITCODE -ne 0) {
    throw "Cannot execute javac: $versionText"
}

Reset-BuildDirectory -Path $mainOutput
Reset-BuildDirectory -Path $testOutput
# Phase 1 originally used build/classes; target/ is now the single build output.
Reset-BuildDirectory -Path $legacyOutput

New-Item -ItemType Directory -Force -Path $mainOutput | Out-Null
New-Item -ItemType Directory -Force -Path $dependencyOutput | Out-Null
if (-not (Test-Path -LiteralPath $sqliteJar)) {
    $cachedJar = Join-Path $env:USERPROFILE ".m2\repository\org\xerial\sqlite-jdbc\$sqliteVersion\sqlite-jdbc-$sqliteVersion.jar"
    if (Test-Path -LiteralPath $cachedJar) {
        Copy-Item -LiteralPath $cachedJar -Destination $sqliteJar
    } else {
        $download = "$sqliteJar.download"
        $url = "https://repo.maven.apache.org/maven2/org/xerial/sqlite-jdbc/$sqliteVersion/sqlite-jdbc-$sqliteVersion.jar"
        try {
            Invoke-WebRequest -Uri $url -OutFile $download -UseBasicParsing
            Move-Item -LiteralPath $download -Destination $sqliteJar
        } catch {
            if (Test-Path -LiteralPath $download) {
                Remove-Item -LiteralPath $download -Force
            }
            throw "Cannot download SQLite JDBC. Check network access to Maven Central: $_"
        }
    }
}
if (-not (Test-Path -LiteralPath $postgresJar)) {
    $cachedPostgres = Join-Path $env:USERPROFILE ".m2\repository\org\postgresql\postgresql\$postgresVersion\postgresql-$postgresVersion.jar"
    if (Test-Path -LiteralPath $cachedPostgres) {
        Copy-Item -LiteralPath $cachedPostgres -Destination $postgresJar
    } else {
        $download = "$postgresJar.download"
        $url = "https://repo.maven.apache.org/maven2/org/postgresql/postgresql/$postgresVersion/postgresql-$postgresVersion.jar"
        try {
            Invoke-WebRequest -Uri $url -OutFile $download -UseBasicParsing
            Move-Item -LiteralPath $download -Destination $postgresJar
        } catch {
            if (Test-Path -LiteralPath $download) {
                Remove-Item -LiteralPath $download -Force
            }
            throw "Cannot download PostgreSQL JDBC. Check network access to Maven Central: $_"
        }
    }
}
$mainSources = @(Get-ChildItem -LiteralPath $sourceRoot -Filter '*.java' -Recurse |
    ForEach-Object { $_.FullName })
if ($mainSources.Count -eq 0) {
    throw 'No Java source files were found.'
}

& javac --release 17 -encoding UTF-8 -d $mainOutput $mainSources
if ($LASTEXITCODE -ne 0) {
    throw 'Main source compilation failed.'
}
Write-Host "Compiled $($mainSources.Count) main source files."

if ($RunTests) {
    New-Item -ItemType Directory -Force -Path $testOutput | Out-Null
    $testSources = @(Get-ChildItem -LiteralPath $testRoot -Filter '*.java' -Recurse |
        ForEach-Object { $_.FullName })
    & javac --release 17 -encoding UTF-8 -cp "$mainOutput;$sqliteJar;$postgresJar" -d $testOutput $testSources
    if ($LASTEXITCODE -ne 0) {
        throw 'Test source compilation failed.'
    }
    Push-Location $projectRoot
    try {
        & java -ea -cp "$mainOutput;$testOutput;$sqliteJar;$postgresJar" chatp2p.Phase1IntegrationTest
        if ($LASTEXITCODE -ne 0) {
            throw 'Phase 1 integration test failed.'
        }
    } finally {
        Pop-Location
    }
}
