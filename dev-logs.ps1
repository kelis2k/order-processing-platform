#Requires -Version 5.1
<#
.SYNOPSIS
    Выводит логи контейнеров стека Order Processing Platform.
.DESCRIPTION
    Windows-native аналог `make dev-logs`.
    Без параметров показывает логи всех сервисов.
    С параметром -Service — только указанного контейнера.
.PARAMETER Service
    Имя сервиса из docker-compose.yaml, например: kafka-1, mongo, redis
.EXAMPLE
    ./dev-logs.ps1
    ./dev-logs.ps1 kafka-1
    ./dev-logs.ps1 -Service mongo
#>
[CmdletBinding()]
param(
    [Parameter(Position = 0)]
    [string]$Service = ""
)

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

if (-not (Get-Command docker -ErrorAction SilentlyContinue)) {
    Write-Error "docker не найден."
    exit 1
}

if ($Service) {
    docker compose logs -f $Service
} else {
    docker compose logs -f
}
