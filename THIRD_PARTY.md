# Third-party code and assets

## FilmKit — USB/PTP protocol

The Fujifilm PTP layer (`data/ptp/`) is derived from [FilmKit](https://github.com/mgostIH/FilmKit),
MIT licensed. Property identifiers, the custom-preset command sequence and the
X RAW Studio profile layout come from that work.

## FujiSync — film simulation badges

The film-box badges in `res/drawable-nodpi/film_sim_badge_*.webp` come from
[FujiSync](https://github.com/ILFforever/FujiSync):

```
MIT License
Copyright (c) 2026 FujiSync Contributors
```

The full MIT text is at https://github.com/ILFforever/FujiSync/blob/main/LICENSE.

The artwork depicts Fujifilm film packaging. Fujifilm, Provia, Velvia, Astia,
Acros, Reala and the film simulation names are trademarks of FUJIFILM
Corporation, used here descriptively to identify the setting each recipe
targets. Reshipi is an unofficial, non-commercial project with no affiliation
to FUJIFILM Corporation.

## Bundled RAF container

`res/raw/donor_x_t30_iii.gz` is the structural shell of an X-T30 III RAF: file
header, the CFA-header tag list, and nothing else. It exists so a synthetic
colour chart has a valid container to be written into, and the app overwrites
every photosite in it before use.

It was made from a frame shot for the purpose, uncompressed and at DR400 — a
RAF converts at the dynamic range it was shot at or below, never above, so the
container has to sit at the top for a recipe at any range to be reproducible
from it. The photograph itself does not remain: the sensor data is not stored
at all, and the embedded preview's image is a rendering of the chart. The
preview's metadata segments (EXIF and maker notes) are kept as-is — the camera
refuses to convert a file without them — with the body, lens and internal
serial numbers zeroed in place. What is left is the format scaffolding plus
FUJIFILM's sensor description tags — the readout size and the X-Trans mosaic —
which the app reads rather than hard-codes so that other bodies work too.
