# Backend

Spring Boot Maven backend for Taiwan Parking Forecast.

## Current scope

This module is the Sprint 2 backend skeleton. It currently provides:

- Spring Boot Web MVC application entrypoint.
- Actuator health endpoint.
- YAML configuration via src/main/resources/application.yml.
- Project API health endpoint: `GET /api/health`.
- Root package: `parking.forecast`.
- Initial package boundaries for `controller`, `service`, `repository`, `domain`, `adapter`, and `config`.

It does not yet connect to MySQL, Redis, Google Maps Platform, New Taipei APIs, TDX, or ML prediction services.

## Run locally

Use Maven Wrapper from this directory:

```bash
./mvnw spring-boot:run
```

On Windows PowerShell:

```powershell
.\mvnw.cmd spring-boot:run
```

Then check:

- `GET http://localhost:8080/api/health`
- `GET http://localhost:8080/actuator/health`

## Test

```bash
./mvnw test
```

On Windows PowerShell:

```powershell
.\mvnw.cmd test
```
