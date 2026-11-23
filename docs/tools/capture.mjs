/**
 * Captures screenshots of every page of the running application.
 *
 * The application requires authentication, so this drives a real Chromium
 * instance through the actual sign-in form rather than trying to inject a
 * cookie. That also means the capture exercises the genuine login flow -- if
 * sign-in breaks, the screenshots fail loudly rather than producing pictures of
 * a redirect page.
 *
 * Usage:
 *   node capture.mjs [baseUrl] [outDir]
 */
import puppeteer from 'puppeteer-core';
import { mkdirSync, existsSync } from 'node:fs';
import { join } from 'node:path';

const BASE = process.argv[2] ?? 'http://localhost:18090';
const OUT = process.argv[3] ?? join(import.meta.dirname, '..', 'screenshots');

/**
 * Theme for the captures.
 *
 * The application now has a light/dark toggle and defaults to the OS preference,
 * which headless Chromium reports as *light* — so without this, the figures would
 * come out light while the rest of the documentation was produced in dark. Dark is
 * the app's canonical theme, so it is pinned explicitly.
 *
 * Pinned via localStorage rather than a DOM poke on the root element: that is
 * exactly what the toggle writes, it survives navigation, and it matches what a
 * returning user would see. `evaluateOnNewDocument` makes it apply before the
 * page's own head script reads it, so no figure catches a flash of the wrong theme.
 */
const THEME = process.env.AJS_CAPTURE_THEME ?? 'dark';

async function applyTheme(page) {
  await page.evaluateOnNewDocument((theme) => {
    try { localStorage.setItem('ajs-theme', theme); } catch (e) { /* ignore */ }
  }, THEME);
}

const CHROME_CANDIDATES = [
  '/usr/bin/chromium',
  '/usr/bin/chromium-browser',
  '/usr/bin/google-chrome',
];

const executablePath = CHROME_CANDIDATES.find((p) => existsSync(p));
if (!executablePath) {
  console.error('No Chromium executable found in:', CHROME_CANDIDATES.join(', '));
  process.exit(1);
}

const ADMIN = {
  email: process.env.AJS_ADMIN_EMAIL ?? 'admin@localhost',
  password: process.env.AJS_ADMIN_PASSWORD ?? 'localdev1234',
};

mkdirSync(OUT, { recursive: true });

/**
 * Pages to capture. `as` controls the account used:
 *   admin    -- the administrator, who sees the navigation admin links
 *   viewer   -- an ordinary user, created on the fly, to show the non-admin view
 *
 * `fullPage` captures the whole scrollable page rather than just the viewport,
 * which matters for the inventory page where the table is tall.
 */
const PAGES = [
  { path: '/sign-in', name: '01-sign-in', as: 'anonymous', fullPage: false },
  { path: '/sign-up', name: '02-sign-up', as: 'anonymous', fullPage: false },
  { path: '/overview', name: '03-overview', as: 'admin', fullPage: true },
  // The timeline is captured with a tall viewport rather than fullPage: its
  // background is `background-attachment: fixed`, which Chromium's full-page
  // capture does not repeat correctly, producing blown-out rows below the fold.
  { path: '/timeline?from=2025-11-19T00:00&to=2025-11-22T00:00',
    name: '17-timeline', as: 'admin', fullPage: false, tall: true },
  { path: '/calendar', name: '04-calendar-due', as: 'admin', fullPage: true },
  { path: '/calendar?date=2025-11-03', name: '05-calendar-a-date', as: 'admin', fullPage: true },
  { path: '/failures', name: '06-failures', as: 'admin', fullPage: true },
  { path: '/jobs', name: '07-inventory', as: 'admin', fullPage: true },
  { path: '/jobs/PAYROLL1', name: '08-job-detail', as: 'admin', fullPage: true },
  { path: '/jobs/REPORT1', name: '09-job-sentinel', as: 'admin', fullPage: true },
  { path: '/jobs/PAYROLL3', name: '10-job-nosbm', as: 'admin', fullPage: true },
  { path: '/attention', name: '11-attention', as: 'admin', fullPage: true },
  { path: '/jobs/NOSUCHJOB', name: '12-not-found', as: 'admin', fullPage: true },
  { path: '/admin/users', name: '13-admin-users', as: 'admin', fullPage: true },
  { path: '/admin/users/2/permissions', name: '14-admin-permissions', as: 'admin', fullPage: true },
  { path: '/admin/apps', name: '15-admin-apps', as: 'admin', fullPage: true },
  { path: '/overview', name: '16-overview-as-user', as: 'viewer', fullPage: true },
];

const VIEWPORT = { width: 1440, height: 900, deviceScaleFactor: 2 };

/**
 * The auth pages are a single centred card on an otherwise empty dark
 * background. At 1440x900 they are mostly dead space, which looks wrong in the
 * PDF. A taller, narrower viewport frames them properly.
 */
const AUTH_VIEWPORT = { width: 900, height: 760, deviceScaleFactor: 2 };

async function signIn(page, credentials) {
  await page.goto(`${BASE}/sign-in`, { waitUntil: 'networkidle0' });
  await page.type('input[name="email"]', credentials.email);
  await page.type('input[name="password"]', credentials.password);
  await Promise.all([
    page.waitForNavigation({ waitUntil: 'networkidle0' }),
    page.click('button[type="submit"]'),
  ]);
}

/**
 * Creates an ordinary user if absent, then signs in as them. Used to show the
 * application from a non-administrator's point of view, where the admin
 * navigation links are absent.
 */
async function ensureViewer(page, adminSession) {
  await signIn(page, ADMIN);

  // CSRF is enabled (design review C1), so a raw fetch() POST is rejected with
  // 403 unless it carries the token. The token is read from a rendered form on
  // an admin page, exactly as a browser would supply it.
  const created = await page.evaluate(async () => {
    const form = document.querySelector('form[action*="/admin/users/create"]')
      || document.querySelector('form');
    const token = form?.querySelector('input[name="_csrf"]')?.value ?? '';
    // redirect:'manual' makes Chromium return status 0 here (the 302 is not
    // surfaced), so follow the redirect and report the final status instead.
    const res = await fetch('/admin/users/create', {
      method: 'POST',
      headers: { 'Content-Type': 'application/x-www-form-urlencoded' },
      body: `_csrf=${encodeURIComponent(token)}&email=viewer@local&password=viewerpass123&name=Viewer&role=user`,
      redirect: 'follow',
    });
    return res.status;
  });
  // 302 to the user list on success; the account may already exist, which also
  // redirects but with an error query parameter.
  console.log(`    viewer create -> ${created}`);

  // Sign-out is a POST and is CSRF-protected, so click the real form's submit
  // button and wait for the navigation in the same step. Calling form.submit()
  // inside evaluate() navigates before the outer wait can be attached.
  await page.goto(`${BASE}/overview`, { waitUntil: 'networkidle0' });
  await Promise.all([
    page.waitForNavigation({ waitUntil: 'networkidle0' }),
    page.click('form[action*="/auth/sign-out"] button[type="submit"], form[action*="/auth/sign-out"] button'),
  ]);

  await signIn(page, { email: 'viewer@local', password: 'viewerpass123' });
}

const browser = await puppeteer.launch({
  executablePath,
  headless: true,
  args: [
    '--no-sandbox',
    '--disable-gpu',
    '--disable-dev-shm-usage',
    '--hide-scrollbars',
    '--force-color-profile=srgb',
  ],
});

const captured = [];
try {
  // --- anonymous pages, in their own context so no session leaks in ---------
  {
    const context = await browser.createBrowserContext();
    const page = await context.newPage();
    await applyTheme(page);
    await page.setViewport(VIEWPORT);
    for (const spec of PAGES.filter((p) => p.as === 'anonymous')) {
      await page.setViewport(AUTH_VIEWPORT);
      await page.goto(`${BASE}${spec.path}`, { waitUntil: 'networkidle0' });
      const file = join(OUT, `${spec.name}.png`);
      await page.screenshot({ path: file, fullPage: spec.fullPage });
      captured.push({ ...spec, file });
      console.log(`  anonymous  ${spec.path} -> ${spec.name}.png`);
    }
    await context.close();
  }

  // --- administrator session -----------------------------------------------
  {
    const context = await browser.createBrowserContext();
    const page = await context.newPage();
    await applyTheme(page);
    await page.setViewport(VIEWPORT);
    await signIn(page, ADMIN);

    for (const spec of PAGES.filter((p) => p.as === 'admin')) {
      await page.goto(`${BASE}${spec.path}`, { waitUntil: 'networkidle0' });
      // A `tall` spec grows the viewport to the document height instead of using
      // fullPage, which is what a fixed background needs (see the timeline entry).
      if (spec.tall) {
        const h = await page.evaluate(() => document.documentElement.scrollHeight);
        await page.setViewport({ ...VIEWPORT, height: Math.min(h, 3000) });
        await new Promise((r) => setTimeout(r, 300));
      }
      const file = join(OUT, `${spec.name}.png`);
      await page.screenshot({ path: file, fullPage: spec.fullPage });
      captured.push({ ...spec, file });
      console.log(`  admin      ${spec.path} -> ${spec.name}.png`);
      if (spec.tall) await page.setViewport(VIEWPORT);
    }

    // --- ordinary user session, in a separate context ----------------------
    const viewerContext = await browser.createBrowserContext();
    const viewerPage = await viewerContext.newPage();
    await applyTheme(viewerPage);
    await viewerPage.setViewport(VIEWPORT);
    await ensureViewer(viewerPage, page);

    for (const spec of PAGES.filter((p) => p.as === 'viewer')) {
      await viewerPage.goto(`${BASE}${spec.path}`, { waitUntil: 'networkidle0' });
      const file = join(OUT, `${spec.name}.png`);
      await viewerPage.screenshot({ path: file, fullPage: spec.fullPage });
      captured.push({ ...spec, file });
      console.log(`  viewer     ${spec.path} -> ${spec.name}.png`);
    }

    await viewerContext.close();
    await context.close();
  }
} finally {
  await browser.close();
}

console.log(`\nCaptured ${captured.length} screenshots into ${OUT}`);
