# Backend

Spring Boot Maven backend for Taiwan Parking Forecast.

## Current scope

This module is the Sprint 2 backend skeleton. It currently provides:

- Spring Boot Web MVC application entrypoint.
- Actuator health endpoint.
- YAML configuration via src/main/resources/application.yml.
- Project API health endpoint: `GET /api/health`.
- JPA dependency and profile based datasource configuration.
- H2 datasource for local development and tests.
- MySQL datasource for Docker and future deployed environments.
- Root package: `parking.forecast`.
- Initial package boundaries for `controller`, `service`, `repository`, `domain`, `adapter`, and `config`.

It does not yet implement Redis access, Google Maps Platform, New Taipei APIs, TDX, or ML prediction services. MySQL connectivity is configured for the Docker profile, but no domain tables or repositories are implemented yet.

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


## Profiles

| Profile | Datasource | Purpose |
| --- | --- | --- |
| `local` | H2 in memory | Default IntelliJ and local quick run profile. |
| `test` | H2 in memory | Automated tests and fast JPA feedback. |
| `docker` | MySQL | Docker Compose and future server deployment baseline. |

H2 is used for fast development and test feedback only. MySQL remains the production like database target, and Docker verification is still required when datasource behavior matters.

Docker profile reads these environment variables:

- `SPRING_DATASOURCE_URL`
- `SPRING_DATASOURCE_USERNAME`
- `SPRING_DATASOURCE_PASSWORD`

The committed Docker Compose file uses local development placeholder values. Real deployment secrets must be injected outside Git.
## Test

```bash
./mvnw test
```

On Windows PowerShell:

```powershell
.\mvnw.cmd test
```
