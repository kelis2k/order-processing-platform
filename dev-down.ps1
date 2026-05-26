#Requires -Version 5.1
<#
.SYNOPSIS
    Останавливает локальный стек Order Processing Platform.
.DESCRIPTION
    Windows-native аналог `make dev-down`.
    По умолчанию данные в volumes сохраняются.
    С флагом -Volumes выполняет `docker compose down -v` — УДАЛЯЕТ все данные.
.PARAMETER Volumes
    Удалить все Docker volumes (полный сброс данных). Необратимо.
#>
[CmdletBinding()]
param(
    [switch]$Volumes
)

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

if (-not (Get-Command docker -ErrorAction SilentlyContinue)) {
    Write-Error "docker не найден."
    exit 1
}

if ($Volumes) {
    Write-Host "Останавливаю стек и УДАЛЯЮ все volumes..." -ForegroundColor Yellow
    docker compose down -v
} else {
    Write-Host "Останавливаю стек (данные сохраняются)..." -ForegroundColor Cyan
    docker compose down
}

if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }
Write-Host "Стек остановлен." -ForegroundColor Green
