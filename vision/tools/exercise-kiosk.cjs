// Additional real browser paths through the unmodified supplied kiosk.
const { chromium } = require('../../.local/node_modules/playwright');
const assert = require('node:assert/strict');
const fs = require('node:fs/promises');
const { pathToFileURL } = require('node:url');
const path = require('node:path');

(async()=>{
  const browser = await chromium.launch();
  const checks=[];
  try {
    const page=await browser.newPage({viewport:{width:720,height:1280}});
    await page.clock.install({time:new Date('2026-01-01T00:00:00Z')});
    await page.clock.pauseAt(new Date('2026-01-01T00:00:00Z'));
    const url=pathToFileURL(path.resolve(process.argv[2])).href;
    async function press(selector) {await page.locator(selector).click();await page.clock.runFor(30);}
    const click=act=>press(`[data-act="${act}"]`);
    async function stage(expected,label) {
      const actual=await page.evaluate(()=>cur()?.type || 'menu');
      assert.equal(actual,expected,label); checks.push({label,stage:actual});
    }
    async function addDrink() {
      await page.locator('[data-act="item"]').filter({has:page.locator('.nm',{hasText:/^\(ICE\)아메리카노$/})}).first().click();
      await page.clock.runFor(30);
      await stage('options','ICE options');
      await click('opt-add');await stage('menu','add to cart');
    }
    await page.goto(url);
    await click('pay');await stage('alert','empty cart blocked');
    await click('al-no');await stage('menu','dismiss empty cart');
    await page.locator('[data-act="item"]').filter({has:page.locator('.nm',{hasText:/^\(HOT\)아메리카노$/})}).first().click();
    await page.clock.runFor(30);
    await stage('options','wrong drink opened');
    await press('.btn[data-act="opt-close"]');await stage('menu','cancel wrong drink');
    assert.equal(await page.evaluate(()=>S.cart.length),0);
    await addDrink();await click('pay');await stage('review','review');
    await click('takeout');await stage('disposable','takeout');
    await click('disp-none');await stage('payment','no disposables');
    await press('[data-act="method"][data-v="카드결제"]');await stage('stamp','loyalty');
    await click('stamp-skip');await stage('card','skip loyalty');
    await press('.btn[data-act="card-cancel"]');await stage('payment','cancel payment');
    await press('[data-act="method"][data-v="카드결제"]');
    await click('earn');await stage('stamp','incomplete number stays on loyalty');
    assert.match(await page.locator('#layer').innerText(),/11자리/);
    for(let i=0;i<8;i++) await press('[data-act="key"][data-k="0"]');
    await click('earn');await stage('card','correct synthetic number accepted');
    await click('approve');await stage('card','approval pending');
    await page.clock.runFor(1600);await stage('receipt','approved receipt');
    await click('noprint');await stage('done','no receipt still issues order number');
    await page.clock.runFor(6100);await stage('menu','completed order resets');
    assert.equal(await page.evaluate(()=>S.cart.length),0);
    await addDrink();await click('pay');await click('dine');await stage('payment','eat in skips disposable dialog');
    await press('[data-act="method"][data-v="카드결제"]');await click('stamp-skip');await click('approve');
    await page.clock.runFor(1600);await stage('receipt','second receipt');
    await page.clock.runFor(9100);await stage('done','receipt timeout issues order number');
    await fs.writeFile('.local/kiosk-alternate-paths.json',JSON.stringify(checks,null,2));
    console.log(`${checks.length} browser state assertions passed, including cancel/back, loyalty errors, skip and timeout.`);
  } finally {await browser.close();}
})().catch(e=>{console.error(e);process.exitCode=1;});
