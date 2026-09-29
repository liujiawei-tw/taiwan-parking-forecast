# Backend

Spring Boot Maven backend for Taiwan Parking Forecast.

## Current scope

This module currently provides:

- Spring Boot Web MVC application entrypoint, Actuator, and `GET /api/health`.
- Typed API envelope, parking availability types, core JPA entities, and Flyway schema.
- H2 local profile and MySQL Docker profile, including a small seed catalog for 300 m queries.
- New Taipei source client, payload normalization, candidate snapshot publication, and published-snapshot reads.
- MySQL refresh cooldown with Redis short-lock coordination and documented fallback behavior.
- Google Routes `computeRoutes` ETA integration, including disabled, missing-key, and HTTP-failure degradation.

The sample catalog is synthetic and is not a formal New Taipei parking-lot directory. Official source-ID mapping and source-quality measurement remain open work. The query service and frontend workflow also retain mock-oriented seams, while Google Maps/Places browser integration and prediction training/inference have not been implemented.

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
