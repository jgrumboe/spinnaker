# Deck Gravity Theme (Docker Overlay)

A lightweight Docker image that applies the [Gravity Design System](https://gravity.redbull.design) visual identity to Spinnaker Deck **without modifying any source code**.

## How It Works

This overlay:
1. Builds Deck from local source in a multi-stage Docker build (Node 24 + pnpm)
2. Sets up the standard Deck Apache2 runtime
3. Adds a `gravity-theme.css` that remaps Deck's CSS custom properties (from `@spinnaker/styleguide`) to Gravity design tokens
4. Injects a `<link>` tag into `index.html` at build time via `sed`
5. Loads the Bull font family from Gravity's CDN (`rbds-static.redbull.com`)

No upstream image dependency, no source code modifications to Deck itself.

## Quick Start

```bash
# Build from the repo root (builds Deck from local source + applies Gravity theme)
docker build -f deck-gravity/Dockerfile -t jgrumboe/deck-gravity:v1 .

# Run it
docker run -p 9000:9000 jgrumboe/deck-gravity:v1
```

Then open http://localhost:9000.

## What Changes Visually

| Area | Before | After |
|------|--------|-------|
| Navbar | Spinnaker teal (#003a52) | Gravity dark surface (#00162B) |
| Buttons | Teal accent | Gravity primary blue (#1B6AEE) |
| Links | Teal | Gravity primary blue |
| Typography | Source Sans 3 | Bull (Gravity font family) |
| Border radius | Mixed (0-3px) | Gravity tokens (4-8px) |
| Elevation/shadows | Minimal | Gravity elevation system |
| Status colors | Custom palette | Gravity semantic colors |

## Font Availability

The Gravity font family ("Bull") is loaded from Red Bull's static CDN at `rbds-static.redbull.com`. The theme includes all three weights (Regular, Medium, Bold) with `font-display: swap` for fast rendering.

If the CDN is unreachable from your deployment network, you can self-host the font files by downloading them and updating the `@font-face` `src` URLs in `gravity-theme.css`.

## Customization

Edit `gravity-assets/gravity-theme.css` to:
- Adjust color mappings
- Add/remove component-level overrides
- Add `@font-face` declarations for the Bull font

## Limitations

This is a **CSS-only overlay** (Option C). It can change colors, typography, spacing, and shadows but:
- Cannot replace component **structure** (e.g., swap Bootstrap dropdowns for Gravity dropdowns)
- Cannot add Gravity web components
- Cannot change component behavior

For deeper integration, consider evolving to a source-level fork with `@gravity/web-components-react`.

## File Structure

```
deck-gravity/
├── Dockerfile                          # Derives from upstream Deck
├── README.md                           # This file
├── gravity-assets/
│   └── gravity-theme.css               # CSS custom property overrides
└── apache/
    └── inject-gravity-theme.conf       # Apache mod_substitute config
```
