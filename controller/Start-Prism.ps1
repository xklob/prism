[CmdletBinding()]
param(
    [switch]$Inspect,
    [switch]$NoHotspot,
    [switch]$NoFirewall,
    [switch]$NoBrowser,
    [ValidateRange(1024,65535)][int]$Port = 48761
)
$ErrorActionPreference = 'Stop'
$controllerRoot = $PSScriptRoot
Add-Type -AssemblyName System.Runtime.WindowsRuntime
[Windows.Networking.Connectivity.NetworkInformation,Windows.Networking.Connectivity,ContentType=WindowsRuntime] > $null
[Windows.Networking.NetworkOperators.NetworkOperatorTetheringManager,Windows.Networking.NetworkOperators,ContentType=WindowsRuntime] > $null
function Get-HotspotManager {
    $profile = [Windows.Networking.Connectivity.NetworkInformation]::GetInternetConnectionProfile()
    if (-not $profile) {
        $profile = [Windows.Networking.Connectivity.NetworkInformation]::GetConnectionProfiles() | Select-Object -First 1
    }
    if (-not $profile) { throw 'Windows needs an active connection profile to create Mobile Hotspot. Connect the laptop to Wi-Fi or Ethernet, then retry. The Prism show itself does not require Internet access.' }
    [Windows.Networking.NetworkOperators.NetworkOperatorTetheringManager]::CreateFromConnectionProfile($profile)
}
function Wait-WinRT($operation, [Type]$resultType) {
    $method = [System.WindowsRuntimeSystemExtensions].GetMethods() | Where-Object {
        $_.Name -eq 'AsTask' -and $_.IsGenericMethod -and $_.GetParameters().Count -eq 1 -and
        $_.GetParameters()[0].ParameterType.Name -eq ('IAsyncOperation' + [char]96 + '1')
    } | Select-Object -First 1
    $task = $method.MakeGenericMethod($resultType).Invoke($null, @($operation))
    if (-not $task.Wait(30000)) { throw 'Windows took too long to update the hotspot.' }
    $task.Result
}
function Get-HotspotIPv4 {
    # Recent drivers expose the AP interface under the physical adapter's name,
    # rather than "Microsoft Wi-Fi Direct Virtual Adapter". The local AP has a
    # preferred private address and no default route; the upstream Wi-Fi does.
    $adapters = Get-NetAdapter -IncludeHidden | Where-Object {
        $_.Status -eq 'Up' -and
        ($_.NdisPhysicalMedium -in @(1,9) -or $_.InterfaceDescription -match 'Wi-Fi Direct|Wi-Fi|WiFi|Wireless')
    }
    $addresses = @($adapters | ForEach-Object {
        $index = $_.ifIndex
        if (-not (Get-NetRoute -InterfaceIndex $index -AddressFamily IPv4 -DestinationPrefix '0.0.0.0/0' -ErrorAction SilentlyContinue)) {
            Get-NetIPAddress -InterfaceIndex $index -AddressFamily IPv4 -ErrorAction SilentlyContinue |
                Where-Object { $_.AddressState -eq 'Preferred' -and
                    $_.IPAddress -match '^(10\.|192\.168\.|172\.(1[6-9]|2[0-9]|3[01])\.)' }
        }
    } | Select-Object -ExpandProperty IPAddress -Unique)
    if ($addresses.Count -eq 1) { $addresses[0] }
}
if ($Inspect) {
    $manager = Get-HotspotManager
    [PSCustomObject]@{
        Computer = $env:COMPUTERNAME
        HotspotState = $manager.TetheringOperationalState.ToString()
        MaxClients = $manager.MaxClientCount
        WiFi = @(Get-NetAdapter -Physical | Where-Object { $_.InterfaceDescription -match 'Wi-Fi|WiFi|Wireless' -and $_.Status -ne 'Not Present' } | Select-Object Name, InterfaceDescription, Status)
    } | ConvertTo-Json -Depth 3
    exit
}
$isAdmin = ([Security.Principal.WindowsPrincipal][Security.Principal.WindowsIdentity]::GetCurrent()).IsInRole([Security.Principal.WindowsBuiltInRole]::Administrator)
if (-not $NoFirewall -and -not $isAdmin) {
    $arguments = @('-NoProfile','-ExecutionPolicy','Bypass','-File',('"' + $PSCommandPath + '"'),'-Port',$Port)
    if ($NoHotspot) { $arguments += '-NoHotspot' }
    if ($NoBrowser) { $arguments += '-NoBrowser' }
    Start-Process powershell.exe -Verb RunAs -ArgumentList $arguments -Wait
    exit
}
$runtime = Join-Path $controllerRoot 'runtime'
New-Item -ItemType Directory -Path $runtime -Force | Out-Null
$node = Join-Path $runtime 'node.exe'
if (-not (Test-Path $node)) {
    $installed = Get-Command node.exe -ErrorAction SilentlyContinue
    if ($installed -and [int]((& $installed.Source --version) -replace '^v(\d+).*','$1') -ge 24) {
        $node = $installed.Source
    } else {
        $version = 'v24.18.0'
        $name = "node-$version-win-x64.zip"
        $base = "https://nodejs.org/dist/$version"
        $archive = Join-Path $runtime $name
        Write-Host 'Downloading the portable Node runtime for the controller.'
        Invoke-WebRequest "$base/$name" -OutFile $archive -UseBasicParsing
        $checksums = (Invoke-WebRequest "$base/SHASUMS256.txt" -UseBasicParsing).Content
        $line = ($checksums -split "\r?\n" | Where-Object { $_ -match ([regex]::Escape($name) + '$') } | Select-Object -First 1)
        if (-not $line) { throw 'Runtime checksum was not published.' }
        $expected = ($line -split '\s+')[0]
        if ((Get-FileHash $archive -Algorithm SHA256).Hash.ToLowerInvariant() -ne $expected.ToLowerInvariant()) { throw 'Runtime checksum did not match.' }
        Expand-Archive $archive -DestinationPath $runtime -Force
        Copy-Item (Join-Path $runtime "node-$version-win-x64/node.exe") $node
    }
}
if (-not (Test-Path (Join-Path $controllerRoot 'node_modules/qrcode'))) {
    throw 'Controller dependencies are missing. Use the complete controller ZIP from the Prism release, or run npm ci in the controller directory.'
}
if (-not $NoFirewall) {
    Get-NetFirewallRule -Name 'Prism-Crowd-UDP' -ErrorAction SilentlyContinue | Remove-NetFirewallRule
    New-NetFirewallRule -Name 'Prism-Crowd-UDP' -DisplayName 'Prism crowd timing' -Direction Inbound -Action Allow -Protocol UDP -LocalPort $Port -RemoteAddress LocalSubnet -Program $node -Profile Any | Out-Null
}
$startedHotspot = $false
$manager = $null
$nodeProcess = $null
$savedEnvironment = @{}
foreach ($name in @('PRISM_UDP_PORT','PRISM_HOST','PRISM_WIFI_SSID','PRISM_WIFI_PASSWORD','PRISM_HOTSPOT_LIMIT','PRISM_LAUNCH_FILE')) {
    $savedEnvironment[$name] = [Environment]::GetEnvironmentVariable($name)
}
try {
    if (-not $NoHotspot) {
        $manager = Get-HotspotManager
        if ($manager.TetheringOperationalState.ToString() -ne 'On') {
            $resultType = [Windows.Networking.NetworkOperators.NetworkOperatorTetheringOperationResult,Windows.Networking.NetworkOperators,ContentType=WindowsRuntime]
            $result = Wait-WinRT ($manager.StartTetheringAsync()) $resultType
            if ($result.Status.ToString() -ne 'Success') { throw ('Windows could not start Mobile Hotspot: ' + $result.Status + '. ' + $result.AdditionalErrorMessage) }
            $startedHotspot = $true
        }
        $configuration = $manager.GetCurrentAccessPointConfiguration()
        $env:PRISM_WIFI_SSID = $configuration.Ssid
        $env:PRISM_WIFI_PASSWORD = $configuration.Passphrase
        $env:PRISM_HOTSPOT_LIMIT = [string]$manager.MaxClientCount
        $hotspotIp = $null
        for ($attempt = 0; $attempt -lt 30 -and -not $hotspotIp; $attempt++) {
            $hotspotIp = Get-HotspotIPv4
            if (-not $hotspotIp) { Start-Sleep -Milliseconds 500 }
        }
        if (-not $hotspotIp) { throw 'The hotspot started, but its IP address is not ready. Close this window and retry.' }
        $env:PRISM_HOST = $hotspotIp
        Write-Host ("Hotspot ready. Windows allows {0} clients, including the audio input phone." -f $manager.MaxClientCount)
    }
    $env:PRISM_UDP_PORT = [string]$Port
    $launchFile = Join-Path $runtime 'launch-url.txt'
    Remove-Item $launchFile -ErrorAction SilentlyContinue
    $env:PRISM_LAUNCH_FILE = $launchFile
    $nodeProcess = Start-Process -FilePath $node -ArgumentList @('src/server.ts') -WorkingDirectory $controllerRoot -RedirectStandardOutput (Join-Path $runtime 'controller.log') -RedirectStandardError (Join-Path $runtime 'controller-error.log') -PassThru
    for ($attempt = 0; $attempt -lt 100 -and -not (Test-Path $launchFile); $attempt++) {
        if ($nodeProcess.HasExited) { throw 'The controller stopped. See runtime/controller-error.log.' }
        Start-Sleep -Milliseconds 100
    }
    if (-not (Test-Path $launchFile)) { throw 'The controller did not finish starting.' }
    if (-not $NoBrowser) { Start-Process (Get-Content $launchFile -Raw) }
    Write-Host 'Prism is running. Keep this window open. Press Ctrl+C here to end the show.'
    Wait-Process -Id $nodeProcess.Id
} finally {
    if ($nodeProcess -and -not $nodeProcess.HasExited) { Stop-Process -Id $nodeProcess.Id -ErrorAction SilentlyContinue }
    if ($startedHotspot -and $manager) {
        try {
            $resultType = [Windows.Networking.NetworkOperators.NetworkOperatorTetheringOperationResult,Windows.Networking.NetworkOperators,ContentType=WindowsRuntime]
            Wait-WinRT ($manager.StopTetheringAsync()) $resultType | Out-Null
        } catch { Write-Warning 'Prism could not stop the hotspot. Turn it off in Windows Settings if it is no longer needed.' }
    }
    foreach ($name in $savedEnvironment.Keys) { [Environment]::SetEnvironmentVariable($name, $savedEnvironment[$name]) }
    Remove-Item (Join-Path $runtime 'launch-url.txt') -ErrorAction SilentlyContinue
}
