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

It was derived from a sample frame published by
[PhotographyBlog](https://www.photographyblog.com/reviews/fujifilm_x_t30_iii_review).
Nothing of that photograph remains: the embedded preview has been replaced by a
rendering of the chart itself, the sensor data is not stored at all, and the
body serial number is zeroed. What is left is the format scaffolding plus
FUJIFILM's sensor description tags — the readout size and the X-Trans mosaic —
which the app reads rather than hard-codes so that other bodies work too.
