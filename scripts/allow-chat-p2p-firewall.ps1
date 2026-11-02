#requires -RunAsAdministrator

$ErrorActionPreference = 'Stop'

function Ensure-PortRule {
    param(
        [Parameter(Mandatory = $true)][string]$DisplayName,
        [Parameter(Mandatory = $true)][ValidateSet('TCP', 'UDP')][string]$Protocol,
        [Parameter(Mandatory = $true)][string]$LocalPort,
        [Parameter(Mandatory = $true)][string]$Description
    )

    $existing = Get-NetFirewallRule -DisplayName $DisplayName -ErrorAction SilentlyContinue
    if ($existing) {
        Set-NetFirewallRule -DisplayName $DisplayName `
            -Enabled True -Direction Inbound -Action Allow -Profile Any | Out-Null
        Get-NetFirewallRule -DisplayName $DisplayName |
            Get-NetFirewallPortFilter |
            Set-NetFirewallPortFilter -Protocol $Protocol -LocalPort $LocalPort
    } else {
        New-NetFirewallRule `
            -DisplayName $DisplayName `
            -Description $Description `
            -Enabled True `
            -Direction Inbound `
            -Action Allow `
            -Profile Any `
            -Protocol $Protocol `
            -LocalPort $LocalPort | Out-Null
    }
}

function Ensure-ProgramRule {
    param(
        [Parameter(Mandatory = $true)][string]$DisplayName,
        [Parameter(Mandatory = $true)][string]$Program,
        [Parameter(Mandatory = $true)][string]$Description
    )

    $existing = Get-NetFirewallRule -DisplayName $DisplayName -ErrorAction SilentlyContinue
    if ($existing) {
        Set-NetFirewallRule -DisplayName $DisplayName `
            -Enabled True -Direction Inbound -Action Allow -Profile Any | Out-Null
        Get-NetFirewallRule -DisplayName $DisplayName |
            Get-NetFirewallApplicationFilter |
            Set-NetFirewallApplicationFilter -Program $Program
    } else {
        New-NetFirewallRule `
            -DisplayName $DisplayName `
            -Description $Description `
            -Enabled True `
            -Direction Inbound `
            -Action Allow `
            -Profile Any `
            -Program $Program | Out-Null
    }
}

Ensure-PortRule `
    -DisplayName 'Chat P2P TCP 5000-5010' `
    -Protocol TCP `
    -LocalPort '5000-5010' `
    -Description 'Central Server, direct P2P chat and file transfer.'

Ensure-PortRule `
    -DisplayName 'Chat P2P UDP Discovery-Multicast 5050-5051' `
    -Protocol UDP `
    -LocalPort '5050-5051' `
    -Description 'UDP Broadcast server discovery and UDP Multicast group chat.'

$javaCommand = Get-Command java.exe -ErrorAction SilentlyContinue
if ($javaCommand) {
    Ensure-ProgramRule `
        -DisplayName 'Chat P2P Java dynamic peer ports' `
        -Program $javaCommand.Source `
        -Description 'Direct P2P chat and file transfer use a dynamic TCP listening port.'
    Write-Host "Allowed dynamic P2P ports for $($javaCommand.Source)." `
        -ForegroundColor Green
} else {
    Write-Warning 'java.exe was not found in PATH. Add Java to PATH, then run this script again.'
}

Write-Host 'ChatP2P Firewall ready: central TCP, UDP discovery/multicast, and Java P2P.' `
    -ForegroundColor Green
