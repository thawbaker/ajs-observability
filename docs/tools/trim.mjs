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
 * Auth pages are left alone: they are a centred card on an intentionally empty
 * background, so trimming would cut the card out of its own composition.
 */
import { execFileSync } from 'node:child_process';
import { readdirSync, statSync } from 'node:fs';
import { join } from 'node:path';

const DIR = process.argv[2] ?? join(import.meta.dirname, '..', 'screenshots');

/** Auth pages keep their framing; everything else is trimmed. */
const SKIP = new Set(['01-sign-in.png', '02-sign-up.png']);

/** Background colour of the application, matches --bg in layout.html. */
const BG = '#0f1419';

const PAD = 24; // px of border re-added after trimming, at 2x scale

let trimmed = 0;
for (const file of readdirSync(DIR).filter((f) => f.endsWith('.png')).sort()) {
  if (SKIP.has(file)) {
    console.log(`  keep   ${file}`);
    continue;
  }
  const path = join(DIR, file);
  const before = statSync(path).size;

  try {
    // -fuzz tolerates the subtle gradient/noise in the background, which
    // otherwise prevents -trim from finding a clean edge.
    execFileSync('magick', [
      path,
      '-fuzz', '6%',
      '-bordercolor', BG,
      '-border', '1',
      '-trim', '+repage',
      '-bordercolor', BG,
      '-border', String(PAD),
      path,
    ], { stdio: 'pipe' });
    trimmed++;
    console.log(`  trim   ${file}  ${(before / 1024).toFixed(0)}K -> ${(statSync(path).size / 1024).toFixed(0)}K`);
  } catch (err) {
    console.error(`  FAIL   ${file}: ${err.message}`);
    process.exitCode = 1;
  }
}

console.log(`\nTrimmed ${trimmed} capture(s) in ${DIR}`);
