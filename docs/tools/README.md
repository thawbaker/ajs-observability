# Documentation tooling

Generates the screenshots in `docs/screenshots/`, which are the figures in
`NOTES.md` §4a. Nothing here is hand-assembled: the captures are of the real
pages, taken through the real sign-in flow.

There is no generated HTML or PDF any more. The figures are embedded in
`NOTES.md` directly, so there is exactly one document to keep in step. The
capture-and-trim steps below are unchanged; the render step is gone.

## Prerequisites

| Tool | Purpose | Present on this machine |
|------|---------|-------------------------|
| Node.js 20+ | runs the capture and trim scripts | 26.13.1 |
| Chromium | drives the browser for the captures | `/usr/bin/chromium` |
| ImageMagick | trims dead space from captures | `magick` |

Install the one npm dependency:

```bash
cd docs/tools && npm install
```

## Regenerate the figures

Two steps. The application must be running first, because the captures drive it
over real HTTP rather than mocking it.

```bash
# 1. Start the app with the fabricated fixture (no IBM i needed).
cd ../..
./gradlew bootRun --args='--spring.profiles.active=local --server.port=18090' &

# 2. Capture every page. Signs in through the real form, so a broken login
#    makes this fail loudly instead of capturing redirect pages.
cd docs/tools
node capture.mjs http://localhost:18090

# 3. Trim dead vertical space so the figures are readable at document scale.
node trim.mjs
```

## Verify the output

Do not trust the exit code alone. An early version of this pipeline referenced
`../screenshots/` while the images live in `docs/screenshots/`, and the result
was silently image-less. Run the checks from `docs/tools/`:

```bash
# 17 files, each non-trivial in size.
ls -1 ../screenshots/*.png | wc -l
identify -format '%f %wx%h\n' ../screenshots/*.png

# Every image referenced by NOTES.md exists.
grep -o 'screenshots/[0-9a-z-]*\.png' ../../NOTES.md | sort -u | while read -r f; do
  test -f "../$f" || echo "MISSING: $f"
done
```

A dashboard capture that is a flat band of colour means the timeline's
`background-attachment: fixed` hit a `fullPage` capture; see the notes below.

## Files

| File | Role |
|------|------|
| `../../NOTES.md` | The document. §4a holds the figures and their captions. |
| `../screenshots/*.png` | Generated captures at 2x scale. |
| `capture.mjs` | Drives Chromium through sign-in and screenshots each page. |
| `trim.mjs` | Crops the empty region below dashboard content. |

## Notes on the capture script

- **It authenticates for real.** Puppeteer submits the actual sign-in form rather
  than injecting a cookie, so the capture also exercises the login path.
- **Admin and ordinary-user views are separate browser contexts.** Figure 17 shows
  the same dashboard as a non-administrator, where the Users and Apps navigation
  links are absent. Capturing that in the admin session would have been wrong.
- **Auth pages use a narrower viewport** (900x760). At 1440x900 the sign-in card
  sits in a large empty field, which looked broken at document scale.
- **Dashboard pages are trimmed afterwards**, not captured cropped. Page height
  varies with the amount of data, so a fixed crop would clip content on some pages
  and leave dead space on others.
- **The timeline uses a tall viewport, not `fullPage`.** Its background is
  `background-attachment: fixed`, which Chromium's full-page capture renders as a
  solid band that hides everything below the fold. The `tall` flag in its `PAGES`
  entry grows the viewport instead. Check the image, not the exit code.
- **Raw `fetch()` POSTs need the CSRF token.** Since CSRF was enabled (design
  review C1), the create-user and sign-out calls in the script read `_csrf` from a
  rendered form and submit it. A token-less POST returns 403, and `redirect:
  'manual'` surfaces that as status 0 rather than a status code.
- **The theme is pinned, not inherited.** `AJS_CAPTURE_THEME` (default `dark`) is
  written to `localStorage.ajs-theme` before every page loads, which is the key the
  app's own toggle writes. Without it the figures came out light while the rest of
  the documentation was dark, because headless Chromium reports a light
  `prefers-color-scheme`. Set `AJS_CAPTURE_THEME=light` to regenerate the whole set
  in the light theme; every figure then renders correctly, including the trim.
- **`trim.mjs` reads the background colour from each image.** It used to hardcode
  `#0f1419`, which matched neither theme's `--bg`, and that silently failed on
  light captures: `-trim` matched nothing and the script reported success on an
  image it had not resized. It now samples the corner pixel, and warns when a file
  shows no vertical dead space (already trimmed, or a background that did not
  match). The crop is a fixed point, so re-running it does not grow the image.

## When pages change

Adding a page or route means updating four places:

1. `PAGES` in `capture.mjs` — add the path and a name.
2. `NOTES.md` §4a — add the image and its caption, and renumber the figures.
3. `AuthBootstrap.BUILT_IN_APPS` in the application, if the new page is a
   dashboard that should be individually grantable.
4. `AppPermissionInterceptor.ROUTE_TO_SLUG` **and** `WebMvcConfig.addPathPatterns`
   — the route must be in both or the interceptor never runs. Missing the third
   and fourth entries is how the timeline ended up ungovernable (design review C8).

The figure numbering in `NOTES.md` §4a is manual. If figures are inserted rather
than appended, renumber the captions and check the cross-reference in Figure 17,
which compares itself to Figure 3.
