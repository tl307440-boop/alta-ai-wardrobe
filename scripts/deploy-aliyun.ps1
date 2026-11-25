param(
    [string]$SshTarget = "smart_campus",
    [string]$RemoteDir = "/opt/alta_wardrobe",
    [int]$AppPort = 8680
)

$ErrorActionPreference = "Stop"
$RepoRoot = Split-Path -Parent $PSScriptRoot
Push-Location $RepoRoot

try {
    Write-Host "==> [1/4] Building Docker images locally (zero build load on 2GB ECS)..." -ForegroundColor Cyan
    docker build -t alta-wardrobe-backend:latest ./backend
    if ($LASTEXITCODE -ne 0) { throw "Backend Docker build failed" }

    docker build -t alta-wardrobe-web:latest ./frontend
    if ($LASTEXITCODE -ne 0) { throw "Frontend Docker build failed" }

    Write-Host "==> [2/4] Compressing & streaming Docker images to $SshTarget..." -ForegroundColor Cyan
    $TmpTarGz = Join-Path $env:TEMP "alta-wardrobe-images.tar.gz"
    try {
        python -c "import subprocess, gzip; p = subprocess.Popen(['docker','save','alta-wardrobe-backend:latest','alta-wardrobe-web:latest'], stdout=subprocess.PIPE); f = gzip.open(r'$TmpTarGz', 'wb', compresslevel=5); [f.write(chunk) for chunk in iter(lambda: p.stdout.read(1048576), b'')]; f.close(); exit(p.wait())"
        if ($LASTEXITCODE -ne 0) { throw "docker save + gzip failed" }
        $SizeMB = [math]::Round((Get-Item $TmpTarGz).Length / 1MB, 1)
        Write-Host "    Compressed bundle size: ${SizeMB} MB" -ForegroundColor Gray
        cmd /c "ssh -o BatchMode=yes $SshTarget `"gunzip | docker load`" < `"$TmpTarGz`""
        if ($LASTEXITCODE -ne 0) { throw "Remote docker load failed" }
    } finally {
        Remove-Item -Force -ErrorAction SilentlyContinue $TmpTarGz
    }

    Write-Host "==> [3/4] Syncing docker-compose.prod.yml and starting stack on port $AppPort..." -ForegroundColor Cyan
    scp -o BatchMode=yes ./docker-compose.prod.yml "${SshTarget}:${RemoteDir}/docker-compose.prod.yml"
    if ($LASTEXITCODE -ne 0) { throw "scp docker-compose.prod.yml failed" }

    ssh -o BatchMode=yes -o ServerAliveInterval=15 -o ServerAliveCountMax=6 $SshTarget "set -e; cd $RemoteDir; mkdir -p uploads; if docker compose version >/dev/null 2>&1; then docker compose -f docker-compose.prod.yml up -d --no-build --remove-orphans; else docker-compose -f docker-compose.prod.yml up -d --no-build --remove-orphans; fi; docker image prune -f"
    if ($LASTEXITCODE -ne 0) { throw "docker compose up failed" }

    Write-Host "==> [4/4] Verifying health on $SshTarget :$AppPort..." -ForegroundColor Cyan
    ssh -o BatchMode=yes -o ServerAliveInterval=15 $SshTarget "for i in `$(seq 1 30); do if curl -fsS --max-time 5 http://127.0.0.1:${AppPort}/api/health; then echo ''; exit 0; fi; sleep 3; done; exit 1"
    if ($LASTEXITCODE -ne 0) { throw "Health check timed out on port $AppPort" }

    Write-Host "==> Deployment succeeded!" -ForegroundColor Green
} finally {
    Pop-Location
}
