# Deck Gravity Theme - Session Context

## What This Is

A **CSS-only Docker overlay** that applies the Red Bull Gravity design system to Spinnaker's Deck UI **without modifying any source code**. It lives in `deck-gravity/` within the Spinnaker monorepo.

## How It Works

1. Multi-stage Docker build: builds Deck from local source (Node 24 + pnpm), then sets up Apache2 runtime
2. Copies `gravity-theme.css` into the built webpack output at `/opt/deck/html/`
3. Injects `<link rel="stylesheet" href="/gravity-theme.css" />` at end of `</body>` via `sed` — this ensures it loads **after** webpack's dynamically-injected inline styles, winning the CSS cascade
4. Loads the Bull font family from Gravity's CDN (`rbds-static.redbull.com`)
5. Uses a `CACHEBUST` build arg to force Docker to pick up CSS changes: `docker build --build-arg CACHEBUST=$(date +%s) -f deck-gravity/Dockerfile -t jgrumboe/deck-gravity:v1 .`

## Key Technical Decisions

### CSS Variable Override Strategy
- Deck's `@spinnaker/styleguide` defines CSS custom properties (e.g., `--color-primary`, `--color-accent`, `--color-success`) on `:root`
- These are injected as inline `<style>` elements by webpack at runtime
- Our theme remaps these variables to Gravity tokens with `!important` (required because webpack's inline styles load dynamically)
- The `<link>` placement at end of `<body>` ensures our stylesheet wins on source order for equal-specificity rules

### Icon Font Preservation
- Typography override targets specific element types (`.navbar-inverse a`, `.navbar-inverse span`, etc.) — NOT `*` wildcard
- Explicit restore rules for `.glyphicon` and `.fa` icon fonts ensure they always use their correct font families

### Gravity Guidelines Compliance
- **Radius:** `--gravity-radius-large` (8px) is the default per Gravity guidelines — used on buttons, panels, modals
- **Colors:** Primary blue used scarcely for key actionable elements; surface colors for backgrounds; glass for borders; accent for semantics
- **Elevation:** Used sparingly (navbar + panels only)
- **Font:** Bull font loaded from CDN with all three weights (Regular 400, Medium 500, Bold 700)

## Current Color Mappings

| Deck Variable | Gravity Token | Purpose |
|---|---|---|
| `--color-primary` | `surface-dark` (#00162B) | Navbar background |
| `--color-primary-g1` | `surface-dark-lighter` (#001C39) | Navbar active/hover |
| `--color-accent` | `primary` (#1B6AEE) | Links, buttons, interactive |
| `--color-accent-g2` | `surface-primary-lighter` (#F4F8FE) | Subtle highlight backgrounds |
| `--color-accent-g3` | `surface-light-darker` (#EFEFEF) | Tertiary backgrounds |
| `--color-success` | `accent-positive` (#159D48) | Success status |
| `--color-danger` | `accent-negative` (#DB0A40) | Error status |
| `--color-warning` | `accent-informative` (#FF9000) | Warning status |

## Known Issues / Current State

### Resolved
- Old Spinnaker teal colors (#149CB5, #D7E8ED, #4B7293) fully overridden
- Icon fonts (Glyphicons, Font Awesome) render correctly
- Loading placeholder: dark Gravity background, blue bars, logo brightened with CSS filter
- Navbar: Gravity dark surface, Bull font, proper active state highlighting

### Pending / Could Be Improved
- **Pipeline execution bars** now use proper Gravity accent tokens (`--gravity-color-accent-positive`, `--gravity-color-accent-negative`) — previously used softened custom colors, now aligned to design system
- **Left sidebar navigation** now uses dark text with blue active state + left border accent — might need further refinement after seeing it deployed
- **Logo brightness** on loading screen set to `brightness(2.0) saturate(0.5)` — couldn't properly test interactively due to CSS cascade issues with the deployed version
- **Pipeline graph** component styling (connected nodes/stages) uses internal styles not reachable by CSS overlay
- **Tab bars** (secondary navigation) — now styled with underline indicator per Gravity Tabs pattern (implemented)
- **Stage detail tables** — styled to match Gravity Table visual pattern (implemented)
- **Alert banners** — styled to match Gravity Callout with left-border accent (implemented)
- **CSS selectors** — actual DOM class names may need verification against deployed Deck to fine-tune selectors

## File Structure

```
deck-gravity/
├── Dockerfile                          # Multi-stage build from local source
├── README.md                           # Build/run instructions
├── SESSION-CONTEXT.md                  # This file
├── gravity-assets/
│   └── gravity-theme.css               # CSS custom property overrides + component styles
└── apache/
    └── inject-gravity-theme.conf       # Reference Apache mod_substitute config (not used)
```

## Build & Deploy Commands

```bash
# Build from repo root
docker build --build-arg CACHEBUST=$(date +%s) -f deck-gravity/Dockerfile -t jgrumboe/deck-gravity:v1 .

# Run locally (with remote API)
docker run --rm -p 9000:9000 -e API_HOST=https://spinnaker-api.redbullmediahouse.com jgrumboe/deck-gravity:v1

# Staging URL
https://spinnaker-staging.redbullmediahouse.com
```

## Gravity MCP

The Gravity design system MCP is configured in `~/.kiro/settings/mcp.json` under `powers.mcpServers.power-gravity-design-system-gravity`. It provides:
- `gravity_get_component` — Component docs
- `gravity_get_guide` — Design system guides (categories: setup, patterns, primitives, principles, migrations)
- `gravity_get_icons` — Icon names
- `gravity_get_tokens` — Design tokens (css, scss, less, styl)

## Dependencies & Context

- Deck requires `deck-kayenta/` at `../deck-kayenta` relative to `deck/` (webpack alias)
- Build requires: `pnpm install` → `pnpm modules` (builds workspace packages) → `pnpm build` (webpack)
- Node 24 + python3 + build-essential needed in Docker for native modules (bufferutil, utf-8-validate)
