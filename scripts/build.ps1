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
$cameraDependencies = @(
    @{ Group = 'com/github/sarxos'; Artifact = 'webcam-capture'; Version = '0.3.12' },
    @{ Group = 'com/nativelibs4java'; Artifact = 'bridj'; Version = '0.7.0' },
    @{ Group = 'org/slf4j'; Artifact = 'slf4j-api'; Version = '1.7.36' },
    @{ Group = 'org/slf4j'; Artifact = 'slf4j-nop'; Version = '1.7.36' },
    @{ Group = 'org/jcodec'; Artifact = 'jcodec'; Version = '0.2.5' },
    @{ Group = 'org/jcodec'; Artifact = 'jcodec-javase'; Version = '0.2.5' }
)

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
foreach ($dependency in $cameraDependencies) {
    $name = "$($dependency.Artifact)-$($dependency.Version).jar"
    $jar = Join-Path $dependencyOutput $name
    if (Test-Path -LiteralPath $jar) {
        continue
    }
    $groupPath = $dependency.Group -replace '/', '\'
    $cached = Join-Path $env:USERPROFILE ".m2\repository\$groupPath\$($dependency.Artifact)\$($dependency.Version)\$name"
    if (Test-Path -LiteralPath $cached) {
        Copy-Item -LiteralPath $cached -Destination $jar
        continue
    }
    $download = "$jar.download"
    $url = "https://repo.maven.apache.org/maven2/$($dependency.Group)/$($dependency.Artifact)/$($dependency.Version)/$name"
    try {
        Invoke-WebRequest -Uri $url -OutFile $download -UseBasicParsing
        Move-Item -LiteralPath $download -Destination $jar
    } catch {
        if (Test-Path -LiteralPath $download) {
            Remove-Item -LiteralPath $download -Force
        }
        throw "Cannot download $name from Maven Central: $_"
    }
}
$cameraClasspath = ($cameraDependencies | ForEach-Object {
    Join-Path $dependencyOutput "$($_.Artifact)-$($_.Version).jar"
}) -join ';'
$mainSources = @(Get-ChildItem -LiteralPath $sourceRoot -Filter '*.java' -Recurse |
    ForEach-Object { $_.FullName })
if ($mainSources.Count -eq 0) {
    throw 'No Java source files were found.'
}

& javac --release 17 -encoding UTF-8 -cp $cameraClasspath -d $mainOutput $mainSources
if ($LASTEXITCODE -ne 0) {
    throw 'Main source compilation failed.'
}
Write-Host "Compiled $($mainSources.Count) main source files."

if ($RunTests) {
    New-Item -ItemType Directory -Force -Path $testOutput | Out-Null
    $testSources = @(Get-ChildItem -LiteralPath $testRoot -Filter '*.java' -Recurse |
        ForEach-Object { $_.FullName })
    & javac --release 17 -encoding UTF-8 -cp "$mainOutput;$sqliteJar;$postgresJar;$cameraClasspath" -d $testOutput $testSources
    if ($LASTEXITCODE -ne 0) {
        throw 'Test source compilation failed.'
    }
    Push-Location $projectRoot
    try {
        & java -ea -cp "$mainOutput;$testOutput;$sqliteJar;$postgresJar;$cameraClasspath" chatp2p.Phase1IntegrationTest
        if ($LASTEXITCODE -ne 0) {
            throw 'Phase 1 integration test failed.'
        }
    } finally {
        Pop-Location
    }
}
