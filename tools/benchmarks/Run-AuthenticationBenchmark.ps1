[CmdletBinding()]
param(
    [ValidateSet('Argon2', 'Login')]
    [string]$Mode = 'Login',
    [switch]$Offline,
    [switch]$CompileOnly
)

$ErrorActionPreference = 'Stop'

if ($PSVersionTable.PSVersion.Major -lt 7) {
    throw 'PowerShell 7 ou plus récent est requis.'
}

$repoRoot = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '../..'))
$runId = [DateTime]::UtcNow.ToString('yyyyMMdd-HHmmss') + '-' + [Guid]::NewGuid().ToString('N').Substring(0, 8)
$runRoot = Join-Path $repoRoot ('target/benchmarks/' + $runId)
$buildRoot = Join-Path $runRoot 'project'
$resultsRoot = Join-Path $runRoot 'results'

if ($env:JAVA_HOME) {
    $javaName = if ($IsWindows) { 'java.exe' } else { 'java' }
    $javaExecutable = Join-Path $env:JAVA_HOME ('bin/' + $javaName)
    if (-not (Test-Path -LiteralPath $javaExecutable -PathType Leaf)) {
        throw 'JAVA_HOME ne désigne pas un JDK utilisable.'
    }
} else {
    $javaExecutable = (Get-Command java -ErrorAction Stop).Source
}

$trackedPaths = @(& git -C $repoRoot ls-files -- src/main/java src/main/resources pom.xml mvnw mvnw.cmd .mvn/wrapper/maven-wrapper.properties)
if ($LASTEXITCODE -ne 0 -or $trackedPaths.Count -eq 0) {
    throw 'Impossible de lire les sources suivies : lancer cet outil depuis un clone Git du serveur.'
}

$sourceCommit = & git -C $repoRoot rev-parse HEAD
if ($LASTEXITCODE -ne 0) {
    throw 'Impossible de lire la révision du serveur.'
}
$sourceStatus = @(& git -C $repoRoot status --short)
if ($LASTEXITCODE -ne 0) {
    throw "Impossible de lire l’état des sources."
}

New-Item -ItemType Directory -Path $buildRoot, $resultsRoot -Force | Out-Null
$manifest = @()

foreach ($relativePath in $trackedPaths) {
    $destination = Join-Path $buildRoot $relativePath
    New-Item -ItemType Directory -Path (Split-Path -Parent $destination) -Force | Out-Null
    Copy-Item -LiteralPath (Join-Path $repoRoot $relativePath) -Destination $destination
    $manifest += [ordered]@{
        path = $relativePath
        sha256 = (Get-FileHash -LiteralPath $destination -Algorithm SHA256).Hash
    }
}

$javaSources = @('Argon2LocalBenchmark.java', 'LocalLoginPathBenchmark.java')
$benchmarkSourcesRoot = Join-Path $buildRoot 'src/test/java'
New-Item -ItemType Directory -Path $benchmarkSourcesRoot -Force | Out-Null

foreach ($sourceName in $javaSources) {
    $relativePath = 'tools/benchmarks/java/' + $sourceName
    $destination = Join-Path $benchmarkSourcesRoot $sourceName
    Copy-Item -LiteralPath (Join-Path $repoRoot $relativePath) -Destination $destination
    $manifest += [ordered]@{
        path = $relativePath
        sha256 = (Get-FileHash -LiteralPath $destination -Algorithm SHA256).Hash
    }
}

$manifest += [ordered]@{
    path = 'tools/benchmarks/Run-AuthenticationBenchmark.ps1'
    sha256 = (Get-FileHash -LiteralPath $PSCommandPath -Algorithm SHA256).Hash
}
$manifest | ConvertTo-Json -Depth 4 | Set-Content -LiteralPath (Join-Path $resultsRoot 'source-manifest.json') -Encoding utf8
[ordered]@{
    mode = $Mode
    sourceCommit = $sourceCommit
    sourceStatus = $sourceStatus
    startedAtUtc = [DateTime]::UtcNow.ToString('o')
    operatingSystem = [Runtime.InteropServices.RuntimeInformation]::OSDescription
    logicalProcessors = [Environment]::ProcessorCount
    compileOnly = [bool]$CompileOnly
} | ConvertTo-Json -Depth 4 | Set-Content -LiteralPath (Join-Path $resultsRoot 'source-state.json') -Encoding utf8

Write-Host ('Dossier de cette exécution : ' + $runRoot)
Write-Host 'Compilation de la copie isolée ; aucun test de la suite du projet ne sera lancé.'
$mavenArguments = @('-B', '-ntp', 'test-compile', 'org.apache.maven.plugins:maven-dependency-plugin:3.8.1:build-classpath', '-Dmdep.outputFile=runtime-classpath.txt', '-Dmdep.includeScope=test')
if ($Offline) {
    $mavenArguments = @('-o') + $mavenArguments
}

Push-Location $buildRoot
try {
    if ($IsWindows) {
        & (Join-Path $buildRoot 'mvnw.cmd') @mavenArguments
    } else {
        & sh (Join-Path $buildRoot 'mvnw') @mavenArguments
    }
    if ($LASTEXITCODE -ne 0) {
        throw 'La compilation isolée a échoué ; aucun benchmark ne sera lancé.'
    }

    if ($CompileOnly) {
        Write-Host 'Compilation terminée. Aucun benchmark, serveur, conteneur ou migration lancé.'
        return
    }

    $dependencies = (Get-Content -Raw -LiteralPath (Join-Path $buildRoot 'runtime-classpath.txt')).Trim()
    $classPath = @((Join-Path $buildRoot 'target/test-classes'), (Join-Path $buildRoot 'target/classes'), $dependencies) -join [IO.Path]::PathSeparator

    if ($Mode -eq 'Login') {
        Write-Host 'Mesure HTTP : démarrage du serveur local, de PostgreSQL et de Redis jetables ; migrations et comptes fictifs dans cette base uniquement. Images postgres:16-alpine et redis:8.4-alpine requises en cache.'
        & $javaExecutable '-Xms128m' '-Xmx768m' '-cp' $classPath LocalLoginPathBenchmark $resultsRoot
        if ($LASTEXITCODE -ne 0) {
            throw 'Le benchmark HTTP a échoué. Les éventuelles mesures partielles ne constituent pas un résultat complet.'
        }
    } else {
        foreach ($fork in 1..3) {
            & $javaExecutable '-cp' $classPath Argon2LocalBenchmark ([string]$fork) $resultsRoot
            if ($LASTEXITCODE -ne 0) {
                throw ('Le benchmark Argon2 a échoué à la JVM ' + $fork + '.')
            }
        }

        $samples = @(1..3 | ForEach-Object { Import-Csv -LiteralPath (Join-Path $resultsRoot ('samples-fork-' + $_ + '.csv')) })
        $aggregate = [ordered]@{}
        foreach ($case in @('correct', 'wrong')) {
            $values = @($samples | Where-Object case -eq $case | ForEach-Object { [long]$_.elapsed_nanos } | Sort-Object)
            $middle = [int]($values.Count / 2)
            $aggregate[$case] = [ordered]@{
                count = $values.Count
                minMs = $values[0] / 1000000.0
                medianMs = ($values[$middle - 1] + $values[$middle]) / 2000000.0
                p95Ms = $values[[int][Math]::Ceiling($values.Count * 0.95) - 1] / 1000000.0
                maxMs = $values[-1] / 1000000.0
                meanMs = ($values | Measure-Object -Average).Average / 1000000.0
            }
        }
        $aggregate | ConvertTo-Json -Depth 4 | Set-Content -LiteralPath (Join-Path $resultsRoot 'aggregate.json') -Encoding utf8
    }

    Write-Host ('Mesure terminée. Résultats : ' + $resultsRoot)
} finally {
    Pop-Location
}
