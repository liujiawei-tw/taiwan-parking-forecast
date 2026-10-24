param(
    [string]$BaseUrl = 'http://127.0.0.1:18088'
)

$ErrorActionPreference = 'Stop'
$request = @{
    requestToken = [guid]::NewGuid().ToString()
    origin = @{ label = '捷運新莊站（O18）'; latitude = 25.03633; longitude = 121.45336 }
    destination = @{ label = '新莊體育館'; latitude = 25.036; longitude = 121.4505 }
    departure = @{ type = 'NOW' }
} | ConvertTo-Json -Depth 5

$query = Invoke-RestMethod -Method Post -Uri "$BaseUrl/api/v1/parking-queries" `
    -ContentType 'application/json; charset=utf-8' -Body ([Text.Encoding]::UTF8.GetBytes($request))
if ($query.eta.status -eq 'READY') {
    if ($null -eq $query.eta.estimatedArrivalAt -or $null -eq $query.eta.durationSeconds) {
        throw 'READY ETA must include arrival time and duration'
    }
} elseif ($query.eta.status -eq 'FAILED') {
    if ($null -ne $query.eta.estimatedArrivalAt -or $null -ne $query.eta.durationSeconds) {
        throw 'Unavailable ETA must not include an estimated arrival time or duration'
    }
} else {
    throw "Unexpected ETA status: $($query.eta.status)"
}
if ($query.area.radiusMeters -ne 500 -or $query.predictions.status -ne 'INSUFFICIENT_DATA' `
    -or $null -ne $query.predictions.modelVersion -or $null -ne $query.predictions.roadside.probability) {
    throw 'Area or model degradation contract failed'
}

$queryUri = "$BaseUrl/api/v1/parking-queries/$($query.query.queryId)"
$refreshUri = "$queryUri/refresh?trigger=MANUAL_REFRESH"
$refreshed = Invoke-RestMethod -Method Post -Uri $refreshUri
$readBack = Invoke-RestMethod -Method Get -Uri $queryUri
$repeat = Invoke-RestMethod -Method Post -Uri $refreshUri

foreach ($response in @($refreshed, $readBack, $repeat)) {
    if ($response.eta.status -ne $query.eta.status `
        -or $response.eta.estimatedArrivalAt -ne $query.eta.estimatedArrivalAt `
        -or $response.eta.durationSeconds -ne $query.eta.durationSeconds `
        -or $response.predictions.status -ne 'INSUFFICIENT_DATA' `
        -or $null -ne $response.predictions.modelVersion `
        -or $null -ne $response.predictions.roadside.probability) {
        throw 'Refreshing or reading changed ETA or exposed an unvalidated prediction'
    }
}
if ($null -eq $refreshed.roadside.snapshotId -or $null -eq $refreshed.offStreet.snapshotId) {
    throw 'A published source snapshot is missing after refresh'
}
if ($repeat.refresh.roadside.action -ne 'COOLDOWN' -or $repeat.refresh.offStreet.action -ne 'COOLDOWN' `
    -or $null -eq $repeat.refresh.roadside.nextAllowedAt -or $null -eq $repeat.refresh.offStreet.nextAllowedAt) {
    throw 'A repeated click bypassed source cooldown'
}

[pscustomobject]@{
    etaStatus = $query.eta.status
    radiusMeters = $query.area.radiusMeters
    coverageStatus = $refreshed.area.coverageStatus
    roadsideStatus = $refreshed.roadside.status
    offstreetStatus = $refreshed.offStreet.status
    firstRoadsideRefresh = $refreshed.refresh.roadside.action
    firstOffstreetRefresh = $refreshed.refresh.offStreet.action
    repeatedRoadsideRefresh = $repeat.refresh.roadside.action
    repeatedOffstreetRefresh = $repeat.refresh.offStreet.action
    predictionsStatus = $repeat.predictions.status
} | ConvertTo-Json -Depth 3
