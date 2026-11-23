/**
 * Trims the dead vertical space beneath the content of each dashboard capture.
 *
 * The dashboard pages are short but the viewport is tall, so full-page captures
 * carry a large empty region below the last row. In the PDF that empty space
 * dominates the figure and forces the actual content to be scaled down to the
 * point of being hard to read.
 *
 * Cropping uses ImageMagick's `-trim` against the page background colour, then
 * re-adds a small uniform border so the figure keeps breathing room rather than
 * butting against its frame.
 *
 * THE BACKGROUND IS SAMPLED, NOT HARDCODED. This script used to carry a single
 * constant (`#0f1419`) described as "matches --bg in layout.html", which was not
 * true of either theme: the dark `--bg` is `#06080f` and the light one is
 * `#f6f8fc`. It appeared to work because the constant is only ever used as the
 * border colour that `-trim` crops against, so it merely had to be within
 * `-fuzz` of the page. That broke the moment a light-theme capture was fed in:
 * no edge matched, `-trim` was a no-op, and the script reported success on an
 * image it had not modified. The corner pixel is sampled per file instead, so a
 * capture trims correctly in either theme.
 *
 * INPUT SHOULD BE A FRESH CAPTURE, and re-running is harmless anyway. The script
 * crops to the content and adds `PAD` back, taking the restored width from the
 * crop itself rather than from the input image. That makes the operation a fixed
 * point: a second pass trims the previous border off, restores the same width and
 * adds the same border again, so the image does not grow. The earlier version
 * derived width from the input and grew by 2*PAD on every pass.
 *
 * Auth pages are left alone: they are a centred card on an intentionally empty
 * background, so trimming would cut the card out of its own composition.
 */
import { execFileSync } from 'node:child_process';
import { readdirSync, statSync } from 'node:fs';
import { join } from 'node:path';

const DIR = process.argv[2] ?? join(import.meta.dirname, '..', 'screenshots');

/** Auth pages keep their framing; everything else is trimmed. */
const SKIP = new Set(['01-sign-in.png', '02-sign-up.png']);

/** Pixel of the image to sample for the page background. */
const SAMPLE_AT = '0,0';

/**
 * Background colour of one image, read from its top-left pixel.
 *
 * The dashboard background is a vertical gradient, so no single colour matches
 * the whole page — but the top-left pixel is representative of the flat region
 * the trim has to crop, and sampling per file is what lets one script serve both
 * themes. Returned as `rgb(r,g,b)` for ImageMagick.
 */
function backgroundOf(path) {
  const out = execFileSync('magick', [
    path, '-format', `%[pixel:p{${SAMPLE_AT}}]`, 'info:',
  ], { encoding: 'utf8' }).trim();
  // ImageMagick may report `srgb(6,8,15)` or `rgb(...)`; normalise the label.
  return out.replace(/^s?rgba?/i, 'rgb');
}

const PAD = 24; // px of border re-added after trimming, at 2x scale

let trimmed = 0;
for (const file of readdirSync(DIR).filter((f) => f.endsWith('.png')).sort()) {
  if (SKIP.has(file)) {
    console.log(`  keep   ${file}`);
    continue;
  }
  const path = join(DIR, file);
  const before = statSync(path).size;
  const beforeDim = execFileSync('magick', ['identify', '-format', '%wx%h', path],
    { encoding: 'utf8' }).trim();

  try {
    const BG = backgroundOf(path);
    /*
      Crop the trailing dead space below the content and re-frame it.

      `-trim` cannot be told to consider one axis, so the sequence is: flatten the
      gradient/aurora to the sampled colour (otherwise no edge is found), trim,
      restore the *crop's own* width with `-extent`, then add the border once.

      Width comes from the crop, not from the input image. Taking it from the input
      would make every pass grow the image by 2*PAD, because the previous pass's
      border is part of the input. Anchoring to the crop makes the operation a
      fixed point, so re-running is harmless.
    */
    const croppedW = execFileSync('magick', [
      path, '-fuzz', '8%', '-fill', BG, '-opaque', BG,
      '-trim', '+repage', '-format', '%w', 'info:',
    ], { encoding: 'utf8' }).trim();
    const width = Number(croppedW) || Number(beforeDim.split('x')[0]);
    execFileSync('magick', [
      path,
      '-fuzz', '8%', '-fill', BG, '-opaque', BG,
      '-trim', '+repage',
      '-background', BG, '-gravity', 'northwest',
      '-extent', `${width}x`,
      '-bordercolor', BG, '-border', String(PAD),
      path,
    ], { stdio: 'pipe' });

    const afterDim = execFileSync('magick', ['identify', '-format', '%wx%h', path],
      { encoding: 'utf8' }).trim();

    // A capture with no vertical dead space is either already trimmed or was
    // never tall enough to need it. Both are worth saying out loud, because the
    // failure mode this replaced was silence: the old script reported "trimmed"
    // on images it had not touched.
    const inH = Number(beforeDim.split('x')[1]);
    const outH = Number(afterDim.split('x')[1]);
    if (outH >= inH) {
      console.warn(`  warn   ${file}: no vertical crop (${beforeDim} -> ${afterDim})`
        + ' - already trimmed, or background did not match');
    }

    trimmed++;
    console.log(`  trim   ${file}  ${beforeDim} -> ${afterDim}  `
      + `${(before / 1024).toFixed(0)}K -> ${(statSync(path).size / 1024).toFixed(0)}K`);
  } catch (err) {
    console.error(`  FAIL   ${file}: ${err.message}`);
    process.exitCode = 1;
  }
}

console.log(`\nTrimmed ${trimmed} capture(s) in ${DIR}`);
