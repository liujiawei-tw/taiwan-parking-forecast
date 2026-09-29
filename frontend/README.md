# Frontend

Vue 3 + Vite frontend for Taiwan Parking Forecast.

## Current scope

This module currently provides:

- Single page app shell.
- Destination search placeholder.
- Google Maps placeholder area.
- Result summary placeholder for ETA, roadside parking, and off-street parking.
- Environment variable entry points for the backend API and Google Maps browser key.
- Mock query creation / refresh flow wired to the current backend endpoint shape.

The browser UI still uses a fixed mock origin and does not load Google Maps JavaScript or Google Places. It does not provide a production location search, rendered map, or real-data UX; the existing backend Google Routes work is not yet surfaced here. The next frontend work is the query contract, Maps/Places integration, and controlled-refresh acceptance flow in Issues #35-#37.

## Environment

Copy `.env.example` to `.env.local` and fill local values.

```bash
cp .env.example .env.local
```

On Windows PowerShell:

```powershell
Copy-Item .env.example .env.local
```

Do not commit `.env.local`.

## Run locally

```bash
npm install
npm run dev
```

## Build

```bash
npm run build
```
