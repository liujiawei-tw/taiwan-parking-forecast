param(
    [string]$BaseUrl = 'http://127.0.0.1:18088',
    [switch]$RequireFresh
)

$ErrorActionPreference = 'Stop'

function New-ParkingQuery([string]$Label, [double]$Latitude, [double]$Longitude) {
    $body = @{
        requestToken = [guid]::NewGuid().ToString()
        origin = @{ label = '捷運新莊站（O18）'; latitude = 25.03633; longitude = 121.45336 }
        destination = @{ label = $Label; latitude = $Latitude; longitude = $Longitude }
        departure = @{ type = 'NOW' }
    } | ConvertTo-Json -Depth 5
    return Invoke-RestMethod -Method Post -Uri "$BaseUrl/api/v1/parking-queries" `
        -ContentType 'application/json; charset=utf-8' -Body ([Text.Encoding]::UTF8.GetBytes($body))
}

$locations = @(
    @{ label = 'Offstreet sample'; lat = 25.0378576; lon = 121.4582263 },
    @{ label = 'Roadside sample'; lat = 25.0583380; lon = 121.4521160 },
    @{ label = 'Unknown offstreet sample'; lat = 25.0414214; lon = 121.4647633 },
    @{ label = 'Boundary empty catalog'; lat = 25.0749; lon = 121.4899 }
)
$summary = foreach ($location in $locations) {
    $result = New-ParkingQuery $location.label $location.lat $location.lon
    if ($result.area.radiusMeters -ne 500 -or $result.predictions.status -ne 'INSUFFICIENT_DATA' `
        -or $null -ne $result.predictions.modelVersion -or $null -ne $result.predictions.roadside.probability) {
        throw 'Radius or prediction degradation contract failed'
    }
    if ($result.area.coverageStatus -eq 'COMPLETE') { throw 'Unverified complete coverage' }
    if (@($result.predictions.offStreet | Where-Object {
        $null -ne $_.probability -or $null -ne $_.modelVersion
    }).Count) {
        throw 'Unverified offstreet prediction probability'
    }
    if ($location.label -eq 'Boundary empty catalog') {
        if ($result.roadside.status -ne 'NO_CATALOG_RESULTS' `
            -or $null -ne $result.roadside.currentlyAvailableSpaces -or $result.offStreet.parkingLots.Count -ne 0) {
            throw 'Empty catalog must not be converted to zero availability'
        }
    } else {
        if ($null -eq $result.roadside.snapshotId -or $null -eq $result.offStreet.snapshotId) {
        throw 'Missing published snapshot for a sample location'
        }
        if ($RequireFresh -and ('STALE' -in @($result.roadside.status, $result.offStreet.status))) {
            throw 'Snapshot expired; run a controlled refresh before fresh-data acceptance'
        }
    }
    if ($location.label -eq 'Unknown offstreet sample' -and -not @(
        $result.offStreet.parkingLots | Where-Object { $null -eq $_.availableCarSpaces }
    ).Count) { throw 'Unknown availability was not preserved' }
    [pscustomobject]@{
        location = $location.label
        coverage = $result.area.coverageStatus
        roadsideStatus = $result.roadside.status
        availableSpaces = $result.roadside.currentlyAvailableSpaces
        offstreetStatus = $result.offStreet.status
        parkingLots = $result.offStreet.parkingLots.Count
        predictions = $result.predictions.status
    }
}

try {
    $null = New-ParkingQuery 'Outside supported area' 25.033 121.565
    throw 'Outside area was accepted'
} catch {
    if ($_.ErrorDetails.Message -notmatch 'UNSUPPORTED_AREA') { throw }
}
$summary | ConvertTo-Json -Depth 3
