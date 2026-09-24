# Start from this project's directory so the short JVM socket path resolves consistently.
param(
    [string]$MavenRepository = (Join-Path ([Environment]::GetFolderPath('UserProfile')) '.m2/repository')
)

$ErrorActionPreference = 'Stop'
Push-Location $PSScriptRoot
try {
    New-Item -ItemType Directory -Path target -Force | Out-Null
    & mvn.cmd "-Dmaven.repo.local=$MavenRepository" spring-boot:run '-Dspring-boot.run.profiles=local' '-Dspring-boot.run.jvmArguments=-Djdk.net.unixdomain.tmpdir=target'
    if ($LASTEXITCODE -ne 0) { throw "Backend exited with code $LASTEXITCODE" }
} finally {
    Pop-Location
}
