# Documentation tooling

Generates `docs/README.pdf` — the illustrated README — from the running
application. Nothing here is hand-assembled: the screenshots are captures of the
real pages taken through the real sign-in flow, and the sample-run transcript
holds real HTTP status codes and byte counts.

## Prerequisites

| Tool | Purpose | Present on this machine |
|------|---------|-------------------------|
| Node.js 20+ | runs the capture and trim scripts | 26.13.1 |
| Chromium | drives the browser and renders the PDF | `/usr/bin/chromium` |
| ImageMagick | trims dead space from captures | `magick` |
| poppler | `pdfinfo` / `pdfimages` / `pdftoppm` for verification | yes |

Install the one npm dependency:

```bash
cd docs/tools && npm install
```

## Regenerate the PDF

Four steps. The application must be running first, because the captures drive it
over real HTTP rather than mocking it.

```bash
# 1. Start the app with the fabricated fixture (no IBM i needed).
cd ../..
./gradlew bootRun --args='--spring.profiles.active=local --server.port=18090' &

# 2. Capture every page. Signs in through the real form, so a broken login
#    makes this fail loudly instead of capturing redirect pages.
cd docs/tools
node capture.mjs http://localhost:18090

# 3. Trim dead vertical space so the figures are readable at print scale.
node trim.mjs

# 4. Render the PDF. Must be the file:// URL of docs/README.html so the
#    relative screenshot paths resolve.
cd ..
chromium --headless --no-sandbox --disable-gpu --hide-scrollbars \
  --no-pdf-header-footer --virtual-time-budget=20000 \
  --print-to-pdf=README.pdf \
  "file://$(pwd)/README.html"
```

## Verify the output

Do not trust the byte count alone — an early version of this pipeline produced a
266 KB PDF that contained **none** of the screenshots, because the HTML referenced
`../screenshots/` while the images live in `docs/screenshots/`. Chromium dropped
all 16 silently. Always check:

```bash
pdfinfo README.pdf | grep -E "Pages|File size"      # expect ~15 pages, ~2 MB
pdfimages -list README.pdf | awk 'NR>2 && $4>300' | wc -l   # expect 16
```

If the image count is not 16, a `src` path is wrong. A PDF under ~1 MB for this
document almost certainly means images are missing, not that it compressed well.

Spot-check a page as an image:

```bash
pdftoppm -png -r 60 -f 5 -l 5 README.pdf /tmp/page
```

## Files

| File | Role |
|------|------|
| `../README.html` | The document source. Edit this to change wording or add figures. |
| `../README.pdf` | Generated output. Committed so it can be read without a toolchain. |
| `../screenshots/*.png` | Generated captures at 2x scale. |
| `capture.mjs` | Drives Chromium through sign-in and screenshots each page. |
| `trim.mjs` | Crops the empty region below dashboard content. |

## Notes on the capture script

- **It authenticates for real.** Puppeteer submits the actual sign-in form rather
  than injecting a cookie, so the capture also exercises the login path.
- **Admin and ordinary-user views are separate browser contexts.** Figure 16 shows
  the same dashboard as a non-administrator, where the Users and Apps navigation
  links are absent. Capturing that in the admin session would have been wrong.
- **Auth pages use a narrower viewport** (900x760). At 1440x900 the sign-in card
  sits in a large empty field, which looked broken in print.
- **Dashboard pages are trimmed afterwards**, not captured cropped. Page height
  varies with the amount of data, so a fixed crop would clip content on some pages
  and leave dead space on others.

## When pages change

Adding a page or route means updating three places:

1. `PAGES` in `capture.mjs` — add the path and a name.
2. `README.html` — add a `<figure>` referencing the new PNG.
3. `AuthBootstrap.BUILT_IN_APPS` in the application, if the new page is a
   dashboard that should be individually grantable.

The figure numbering in `README.html` is manual. If figures are inserted rather
than appended, renumber the captions and check the cross-reference in Figure 16,
which compares itself to Figure 3.
