# Third-Party Notices

## Legado

This project vendors and adapts selected components from the Legado project:

- `modules/rhino`
- the source-rule and local-web-service design
- `web/legado-admin`
- `web/reader-react`

The vendored Legado distribution is licensed under GNU GPL version 3. The
license text is available at `third_party/legado/LICENSE`. Original file
headers are preserved where source files are copied verbatim.

## ConverterGraphDrDuc

The Kotlin translation runtime in `modules/drduc-engine` is an independent
mobile implementation informed by the MIT-licensed ConverterGraphDrDuc
pipeline. The Python project remains a build-time and QA oracle and is not
packaged in the APK.

## Translation Data

No DrDuc graph database or Legado VietPhrase dictionary is bundled by
default. These data sets must be audited separately before redistribution.
The Android app supports user-managed imports.
