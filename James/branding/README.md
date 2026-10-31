# Azarah logo

The AZ badge: a rounded hexagon shield with a metallic bezel, an "A" built as a road (dashed lane marker, horizon curve) and a "Z" with an orange speedometer arc and acceleration lines.

**Master artwork: `azarah-logo-original.png`** (the designer's transparent PNG). Everything below is made from it.

| File | Use |
| --- | --- |
| `azarah-logo-original.png` | The designer's original, transparent background. |
| `azarah-logo-transparent-1024.png` | Splash and loading screens (square, transparent). |
| `azarah-app-icon-1024.png` | Square 1024 x 1024 app icon on charcoal. |
| `azarah-play-store-512.png` | Google Play listing icon (512 x 512). |
| `azarah-icon-preview.png` | How the launcher icon looks in different launcher shapes and sizes. |
| `azarah-logo.svg`, `azarah-logo-flat.svg` | A traced vector approximation, for print or large signage until the designer supplies a vector master. Not pixel-identical to the PNG. |
| `azarah-concept-reference.jpg` | The concept image. |

The 1024 icons are slightly upscaled (the badge in the original PNG is about 860 px tall). For a perfectly sharp 1024, export the badge at 2048 px and regenerate.

## Colours

| Part | Hex |
| --- | --- |
| Shield face | `#2B2E33` to `#1E2024` |
| Bezel | `#9AA0A8` / `#7D838C` / `#5E636B` |
| Letters | `#FFFFFF` |
| Accent | `#FF5500` to `#FF8800` |

## In the app (`app/src/main/res`)

- `mipmap-*/ic_launcher_foreground.png`: the badge, 64 dp tall on a 108 dp canvas, at every screen density.
- `drawable/ic_launcher_background.xml`: charcoal behind it.
- `drawable/ic_launcher_monochrome.xml`: Android 13+ themed icon (single colour, traced outline).
- `drawable/ic_stat_james.xml`: status-bar icon (white AZ silhouette; Android only allows one colour there).
- `drawable/ic_launcher_foreground.xml` is the older traced vector and is no longer used; it can be deleted.
