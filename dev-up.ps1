#Requires -Version 5.1
<#
.SYNOPSIS
    Поднимает весь локальный стек Order Processing Platform.
.DESCRIPTION
    Windows-native аналог `make dev-up`. Запускает `docker compose up -d`.
#>
[CmdletBinding()]
param()

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

if (-not (Get-Command docker -ErrorAction SilentlyContinue)) {
    Write-Error "docker не найден. Установи Docker Desktop: https://docs.docker.com/desktop/install/windows-install/"
    exit 1
}

Write-Host "Запускаю стек Order Processing Platform..." -ForegroundColor Cyan
docker compose up -d
if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }

Write-Host ""
Write-Host "Стек запущен. Сервисы:" -ForegroundColor Green
Write-Host "  Kafka UI        http://localhost:8080"
Write-Host "  Schema Registry http://localhost:8081"
Write-Host "  MailHog UI      http://localhost:8025"
Write-Host "  Kafka bootstrap 9092, 9093, 9094"
Write-Host "  PostgreSQL      5432 (auth)  5433 (user)  5434 (order)  5435 (inventory)"
Write-Host "  MongoDB         27017"
Write-Host "  Redis           6379"
