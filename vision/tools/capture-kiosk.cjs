// npm install --prefix .local playwright@1.65.0
// node vision/tools/capture-kiosk.cjs <original index.html>
// Only browser presentation is transformed; the supplied HTML is never modified.
const { chromium } = require('../../.local/node_modules/playwright');
const fs = require('node:fs/promises');
const path = require('node:path');
const { pathToFileURL } = require('node:url');
const crypto = require('node:crypto');
const sharp = require('../../.local/node_modules/sharp');

(async () => {
  const source = path.resolve(process.argv[2]);
  const holdout = process.argv.includes('--holdout');
  const output = path.resolve(holdout ? '.local/kiosk-holdout' : '.local/kiosk-sim');
  await fs.mkdir(output, { recursive: true });
  const browser = await chromium.launch();
  try {
    const page = await browser.newPage({ viewport: { width: 720, height: 1280 } });
    await page.clock.install({ time: new Date('2026-01-01T00:00:00Z') });
    await page.clock.pauseAt(new Date('2026-01-01T00:00:00Z'));
    await page.goto(pathToFileURL(source).href);
    await page.evaluate(() => document.fonts.ready);
    const variants = holdout ? [
      {name:'held-left', angle:-2.1, dx:-16, dy:12, blur:0.45, skew:1.3},
      {name:'held-right', angle:2.4, dx:18, dy:-11, blur:0.7, skew:-1.1},
      {name:'motion', angle:-1.7, dx:12, dy:14, blur:0.4, skew:0.9, motion:3},
      {name:'held-recovery', angle:1.1, dx:-13, dy:6, blur:0.2, skew:-0.9},
    ] : [
      {name:'clear', angle:0, dx:0, dy:0, blur:0, skew:0},
      {name:'clear-repeat', angle:0, dx:0, dy:0, blur:0, skew:0},
      {name:'shake-left', angle:-1.2, dx:-9, dy:7, blur:0.6, skew:0.7},
      {name:'shake-right', angle:1.5, dx:10, dy:-6, blur:0.9, skew:-0.8},
      {name:'defocus', angle:-0.7, dx:3, dy:9, blur:1.4, skew:0.4},
      {name:'recovered', angle:0.6, dx:-4, dy:2, blur:0.3, skew:-0.4},
    ];
    const frames = [];
    async function capture(stage, expected) {
      for (const v of variants) {
        await page.evaluate(v => {
          const k = document.querySelector('#kiosk');
          k.style.transform = `translate(calc(-50% + ${v.dx}px),calc(-50% + ${v.dy}px)) scale(${Math.min(innerWidth/1080,innerHeight/1920)*0.92}) rotate(${v.angle}deg) skewX(${v.skew}deg)`;
          k.style.filter = `blur(${v.blur}px)`;
        }, v);
        const file = `${String(frames.length).padStart(3,'0')}-${stage}-${v.name}.png`;
        const raster = await page.screenshot({ animations:'disabled' });
        if (v.motion) {
          // Approximate a short horizontal exposure smear, in addition to frame-to-frame movement.
          await sharp(raster).convolve({ width:3,height:3,kernel:[0,0,0,1,1,1,0,0,0],scale:3 })
            .png().toFile(path.join(output,file));
        } else await fs.writeFile(path.join(output,file),raster);
        frames.push({file,stage,expected,...v});
      }
    }
    async function press(locator) {
      await locator.click();
      // Let the original kiosk clear its pointer-drag state; keep receipt timers controlled.
      await page.clock.runFor(30);
    }
    const click = act => press(page.locator(`[data-act="${act}"]`));
    await capture('menu','menu-cart');
    await press(page.locator('[data-act="item"]').filter({has: page.locator('.nm', {hasText:/^\(ICE\)아메리카노$/})}).first());
    await capture('options','product-options');
    await click('opt-add');
    await capture('cart','menu-cart');
    await click('pay');
    await capture('review','order-review');
    await click('takeout');
    await capture('disposable','disposable');
    // Exercise every option that actually exists, rather than inventing cupholder.
    const choices = await page.locator('[data-act="disp"]').allTextContents();
    for (const button of await page.locator('[data-act="disp"]').all()) await press(button);
    await capture('disposable-selected','disposable');
    await click('disp-done');
    await capture('payment','payment');
    await press(page.locator('[data-act="method"][data-v="카드결제"]'));
    await capture('loyalty','loyalty');
    // Synthetic number, used only in this offline exercise. No personal data.
    for (let i=0;i<8;i++) await press(page.locator('[data-act="key"][data-k="0"]'));
    await click('earn');
    await capture('card','card-approval');
    await click('approve');
    await capture('processing','card-processing');
    await page.clock.runFor(1600);
    await capture('receipt','receipt');
    await click('print');
    await capture('done','complete');
    const manifest = { sourceSha256:crypto.createHash('sha256').update(await fs.readFile(source)).digest('hex'),
      profile:holdout?'holdout':'development', viewport:{width:720,height:1280}, choices, frames };
    await fs.writeFile(path.join(output,'manifest.json'), JSON.stringify(manifest,null,2));
    console.log(`Captured ${frames.length} real browser screenshots across 12 stages to ${output}`);
  } finally { await browser.close(); }
})().catch(e => {console.error(e);process.exitCode=1;});
