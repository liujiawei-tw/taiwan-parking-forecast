# Frontend

Vue 3 + Vite frontend for Taiwan Parking Forecast.

## Current scope

This module currently provides:

- Single page app shell.
- Google Place Autocomplete Widget (New) mount point when a browser key is configured.
- Google Maps JavaScript API map rendering for the selected destination, 300 meter area, off-street lots, and currently available roadside spots.
- Manual coordinate fallback when no browser key is configured, so local builds and CI do not call Google.
- Result summary for ETA, roadside parking, off-street parking, predictions, and refresh state.
- Environment variable entry points for the backend API and Google Maps browser key.
- Query creation / refresh flow wired to the current backend endpoint shape.

The browser UI does not store Google place details in the repository and does not call government parking APIs directly. It submits selected coordinates and departure settings to the backend only.

For an active result page, the UI pauses progress polling while the page is hidden. When the page becomes visible again, it first reads the existing query state with `GET /api/v1/parking-queries/{queryId}`. If roadside or off-street data is stale or has no published realtime snapshot, it sends one controlled `POST /api/v1/parking-queries/{queryId}/refresh?trigger=VISIBLE_AUTO_REFRESH` for that query/status combination. Manual refresh still uses the same endpoint with `MANUAL_REFRESH` and remains subject to backend cooldown and source limits.

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

`VITE_GOOGLE_MAPS_BROWSER_API_KEY` must be a browser-restricted key for local testing. Leaving it empty keeps the app in manual coordinate mode and avoids Google Maps / Places usage.

## Run locally

```bash
npm install
npm run dev
```

## Build

```bash
npm run build
```
