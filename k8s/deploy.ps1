param(
    [Parameter(Mandatory = $true)]
    [ValidatePattern('^[0-9a-f]{40}$')]
    [string]$ImageTag,
    [ValidatePattern('^ghcr\.io/[a-z0-9][a-z0-9-]*$')]
    [string]$RegistryNamespace = 'ghcr.io/ragulrpk',
    [string]$Context = 'docker-desktop'
)

$ErrorActionPreference = 'Stop'

# The environment and registry Secrets are deliberately provisioned separately.
& kubectl --context $Context -n messenger get secret messenger-secrets ghcr-pull -o name
if ($LASTEXITCODE -ne 0) { throw 'Create messenger-secrets and ghcr-pull in the messenger namespace first.' }

$taskManifest = & kubectl kustomize $PSScriptRoot
if ($LASTEXITCODE -ne 0) { throw 'Kustomize rendering failed.' }
$taskManifestText = $taskManifest -join "`n"
$taskBackendImage = "$RegistryNamespace/messenger-backend:$ImageTag"
$taskFrontendImage = "$RegistryNamespace/messenger-frontend:$ImageTag"
if (!$taskManifestText.Contains('image: messenger-backend:k8s-v1') -or
    !$taskManifestText.Contains('image: messenger-frontend:k8s-v1')) {
    throw 'Expected base image names were not found; update deploy.ps1 to match deployment.yaml.'
}
$taskManifestText = $taskManifestText.Replace('image: messenger-backend:k8s-v1', "image: $taskBackendImage")
$taskManifestText = $taskManifestText.Replace('image: messenger-frontend:k8s-v1', "image: $taskFrontendImage")

$taskManifestText | & kubectl --context $Context apply -f -
if ($LASTEXITCODE -ne 0) { throw 'Kubernetes apply failed.' }

foreach ($taskWorkload in @('statefulset/messenger-redis', 'deployment/messenger-backend', 'deployment/messenger-frontend')) {
    & kubectl --context $Context -n messenger rollout status $taskWorkload --timeout=300s
    if ($LASTEXITCODE -ne 0) { throw "Rollout failed for $taskWorkload. Check pod events and logs." }
}
& kubectl --context $Context -n messenger get deployments,pods,svc,pvc
if ($LASTEXITCODE -ne 0) { throw 'Failed to fetch deployment status.' }
