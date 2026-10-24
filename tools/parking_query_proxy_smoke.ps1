param(
    [string]$BaseUrl = 'http://127.0.0.1:18088'
)

$ErrorActionPreference = 'Stop'

function New-QueryPayload {
    @{
        requestToken = 'proxy-smoke-' + [guid]::NewGuid().ToString()
        origin = @{ label = 'Smoke 起點'; latitude = 25.03633; longitude = 121.45336 }
        destination = @{ label = 'Smoke 目的地'; latitude = 25.036; longitude = 121.4505 }
        departure = @{ type = 'NOW' }
    } | ConvertTo-Json -Depth 5
}

function Send-CreateRequest([string]$Origin) {
    $headers = @{}
    if ($Origin) {
        $headers['Origin'] = $Origin
    }

    Invoke-WebRequest -Method Post -Uri "$BaseUrl/api/v1/parking-queries" `
        -Headers $headers -ContentType 'application/json; charset=utf-8' `
        -Body ([Text.Encoding]::UTF8.GetBytes((New-QueryPayload))) `
        -TimeoutSec 15 -SkipHttpErrorCheck
}

function Get-JsonBody($Response) {
    $content = $Response.Content
    if ($content -is [byte[]]) {
        $content = [Text.Encoding]::UTF8.GetString($content)
    }
    if ($content -isnot [string] -or [string]::IsNullOrWhiteSpace($content)) {
        return $null
    }
    try {
        return $content | ConvertFrom-Json -AsHashtable
    } catch {
        return $null
    }
}

$withoutOrigin = Send-CreateRequest ''
if ([int]$withoutOrigin.StatusCode -ne 202 -or -not (Get-JsonBody $withoutOrigin).query.queryId) {
    throw "POST without Origin should return 202 with queryId; got $([int]$withoutOrigin.StatusCode)."
}

$browserOrigin = Send-CreateRequest 'http://127.0.0.1:18088'
if ([int]$browserOrigin.StatusCode -ne 202 -or -not (Get-JsonBody $browserOrigin).query.queryId) {
    throw "Same-origin browser POST should return 202 with queryId; got $([int]$browserOrigin.StatusCode)."
}

$vitePreflight = Invoke-WebRequest -Method Options -Uri "$BaseUrl/api/v1/parking-queries" `
    -Headers @{
        Origin = 'http://127.0.0.1:5173'
        'Access-Control-Request-Method' = 'POST'
        'Access-Control-Request-Headers' = 'content-type'
    } -TimeoutSec 10 -SkipHttpErrorCheck
if ([int]$vitePreflight.StatusCode -ne 200 `
        -or $vitePreflight.Headers['Access-Control-Allow-Origin'] -ne 'http://127.0.0.1:5173') {
    throw "Vite preflight should be allowed; got $([int]$vitePreflight.StatusCode)."
}

$rejectedOrigin = Invoke-WebRequest -Method Options -Uri "$BaseUrl/api/v1/parking-queries" `
    -Headers @{
        Origin = 'https://attacker.invalid'
        'Access-Control-Request-Method' = 'POST'
        'Access-Control-Request-Headers' = 'content-type'
    } -TimeoutSec 10 -SkipHttpErrorCheck
if ([int]$rejectedOrigin.StatusCode -ne 403) {
    throw "Unapproved external Origin should return 403; got $([int]$rejectedOrigin.StatusCode)."
}

$forgedForwardedOrigin = Invoke-WebRequest -Method Post `
    -Uri "$BaseUrl/api/v1/parking-queries/proxy-smoke-missing/refresh" `
    -Headers @{
        Origin = 'https://attacker.invalid'
        'X-Forwarded-Proto' = 'https'
        'X-Forwarded-Host' = 'attacker.invalid'
        Forwarded = 'proto=https;host=attacker.invalid'
    } -TimeoutSec 10 -SkipHttpErrorCheck
if ([int]$forgedForwardedOrigin.StatusCode -ne 403) {
    throw "Client-supplied forwarded headers must not make an external Origin same-origin; got $([int]$forgedForwardedOrigin.StatusCode)."
}

$missingRefresh = Invoke-WebRequest -Method Post `
    -Uri "$BaseUrl/api/v1/parking-queries/proxy-smoke-missing/refresh" `
    -Headers @{ Origin = 'http://127.0.0.1:18088' } -TimeoutSec 10 -SkipHttpErrorCheck
$missingRefreshBody = Get-JsonBody $missingRefresh
if ([int]$missingRefresh.StatusCode -ne 404 -or $missingRefreshBody.error.code -ne 'QUERY_NOT_FOUND') {
    throw "Same-origin refresh should reach the API and return 404 QUERY_NOT_FOUND; got $([int]$missingRefresh.StatusCode)."
}

$httpsProxyHandler = [System.Net.Http.HttpClientHandler]::new()
$httpsProxyHandler.UseProxy = $false
$httpsProxyClient = [System.Net.Http.HttpClient]::new($httpsProxyHandler)
$httpsProxyRequest = [System.Net.Http.HttpRequestMessage]::new(
    [System.Net.Http.HttpMethod]::Post,
    "$BaseUrl/api/v1/parking-queries/proxy-smoke-missing-https/refresh")
$httpsProxyRequest.Headers.Host = 'parking.example.com:8443'
[void]$httpsProxyRequest.Headers.TryAddWithoutValidation('Origin', 'https://parking.example.com:8443')
[void]$httpsProxyRequest.Headers.TryAddWithoutValidation('X-Forwarded-Proto', 'https')
[void]$httpsProxyRequest.Headers.TryAddWithoutValidation('X-Forwarded-Host', 'attacker.invalid')
[void]$httpsProxyRequest.Headers.TryAddWithoutValidation('X-Forwarded-Port', '443')
[void]$httpsProxyRequest.Headers.TryAddWithoutValidation('Forwarded', 'proto=http;host=attacker.invalid')
$httpsProxyResponse = $null
$httpsProxyBody = $null
try {
    $httpsProxyResponse = $httpsProxyClient.Send($httpsProxyRequest)
    $httpsProxyBody = $httpsProxyResponse.Content.ReadAsStringAsync().GetAwaiter().GetResult() | ConvertFrom-Json
    if ([int]$httpsProxyResponse.StatusCode -ne 404 -or $httpsProxyBody.error.code -ne 'QUERY_NOT_FOUND') {
        throw "Trusted HTTPS proxy metadata with Host port should reach the API and return 404 QUERY_NOT_FOUND; got $([int]$httpsProxyResponse.StatusCode)."
    }
} finally {
    if ($httpsProxyResponse) {
        $httpsProxyResponse.Dispose()
    }
    $httpsProxyRequest.Dispose()
    $httpsProxyClient.Dispose()
    $httpsProxyHandler.Dispose()
}

[pscustomobject]@{
    postWithoutOrigin = [int]$withoutOrigin.StatusCode
    postWithSameOrigin = [int]$browserOrigin.StatusCode
    vitePreflight = [int]$vitePreflight.StatusCode
    viteAllowOrigin = [string]$vitePreflight.Headers['Access-Control-Allow-Origin']
    unapprovedOriginPreflight = [int]$rejectedOrigin.StatusCode
    forgedForwardedOrigin = [int]$forgedForwardedOrigin.StatusCode
    sameOriginRefreshMissingQuery = [int]$missingRefresh.StatusCode
    refreshErrorCode = $missingRefreshBody.error.code
    forwardedHttpsNonDefaultPort = [int]$httpsProxyResponse.StatusCode
    forwardedHttpsErrorCode = $httpsProxyBody.error.code
} | ConvertTo-Json -Depth 3
