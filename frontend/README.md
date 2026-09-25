# Frontend

Vue 3 + Vite frontend for Taiwan Parking Forecast.

## Current scope

This module is the Sprint 2 frontend skeleton. It currently provides:

- Single page app shell.
- Destination search placeholder.
- Google Maps placeholder area.
- Result summary placeholder for ETA, roadside parking, and off-street parking.
- Environment variable entry points for the backend API and Google Maps browser key.

It does not yet load Google Maps JavaScript API, Places Autocomplete, Routes API, or real parking data.

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