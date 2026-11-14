const { chromium } = require('playwright');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
process.env.PLAYWRIGHT_BROWSERS_PATH ||= path.resolve(__dirname, '../tools/playwright');
(async () => {
  fs.mkdirSync(path.resolve(__dirname, '../qa/screenshots'), { recursive: true });
  const browser = await chromium.launch({ headless: true });
  const page = await browser.newPage({ viewport: { width: 534, height: 320 }, deviceScaleFactor: 1 });
  const errors = [];
  page.on('pageerror', error => errors.push(error.message));
  await page.goto('http://127.0.0.1:4173');
  await page.waitForFunction(() => !!window.PrintApp);
  await page.screenshot({ path: 'qa/screenshots/home-534x320.png' });
  assert.equal(await page.locator('.entry').count(), 3);
  for (const button of await page.locator('.entry').all()) {
    const box = await button.boundingBox();
    assert.ok(box.x >= 0 && box.y >= 0 && box.x + box.width <= 534 && box.y + box.height <= 320);
  }
  await page.locator('[data-screen=text]').click();
  await page.evaluate(() => { PrintApp.quill.setText('第一行\n第二行\n第三行\n'); PrintApp.quill.setSelection(0, 8); });
  await page.locator('button.ql-align[value=center]').click();
  assert.deepEqual(await page.evaluate(() => [...document.querySelectorAll('#editor .ql-editor p')].map(p => p.className)), ['ql-align-center', 'ql-align-center', '']);
  await page.evaluate(() => PrintApp.quill.setSelection(8, 0));
  await page.locator('button.ql-align[value=right]').click();
  assert.ok(await page.evaluate(() => document.querySelectorAll('#editor p')[2].classList.contains('ql-align-right')));
  await page.locator('button.ql-align[value=""]').click();
  assert.equal(await page.evaluate(() => document.querySelectorAll('#editor p')[2].className), '');
  await page.locator('button.ql-align[value=right]').click();
  await page.screenshot({ path: 'qa/screenshots/rich-alignment-534x320.png' });
  await page.evaluate(() => PrintApp.quill.setText('$\\LaTeX$\n$$\\LaTeX$$\n\\[ \\LaTeX \\]\n\\( \\LaTeX \\)\n\\[\n\\LaTeX\n\\]\n'));
  await page.locator('#textPreviewTab').click();
  assert.equal(await page.locator('#textPreview .katex').count(), 5);
  assert.equal(await page.locator('#textPreview .katex-error').count(), 0);
  await page.screenshot({ path: 'qa/screenshots/rich-math-534x320.png' });
  await page.locator('#sampleText').click();
  await page.locator('#textPreviewTab').click();
  assert.equal(await page.locator('#textPreview .katex-error').count(), 0);
  assert.equal(await page.locator('#textPreview .katex').count(), 6);
  await page.screenshot({ path: 'qa/screenshots/markdown-math-534x320.png' });
  await page.locator('#richTab').click();
  await page.locator('.ql-insertmath').click();
  await page.locator('#formulaSource').fill('\\frac{1}{2}');
  await page.locator('#insertFormula').click();
  assert.equal(await page.locator('#editor .ql-formula').count(), 1);
  await page.reload();
  await page.waitForFunction(() => !!window.PrintApp);
  await page.waitForTimeout(450);
  await page.locator('#deviceButton').click();
  assert.equal(await page.locator('#copyLog').isVisible(), true);
  await page.screenshot({ path: 'qa/screenshots/settings-534x320.png' });
  assert.equal(await page.locator('#deviceDialog').evaluate(el => el.getBoundingClientRect().right <= 534), true);
  await page.locator('#deviceDialog .primary').click();
  await page.evaluate(() => PrintApp.showScreen('text'));
  await page.setViewportSize({ width: 534, height: 230 });
  await page.screenshot({ path: 'qa/screenshots/compact-534x230.png' });
  assert.equal(await page.locator('#printText').isVisible(), true);
  assert.equal(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth), true);
  await page.setViewportSize({ width: 534, height: 320 });
  // Exercise image ordering and the exact payload submitted to the native bridge.
  await page.addInitScript(() => { window.Android = { ready() {}, printJob(value) { window.submittedJob = JSON.parse(value); } }; });
  await page.reload(); await page.waitForFunction(() => !!window.PrintApp);
  await page.evaluate(() => { Android.copyDiagnostics = () => { window.diagnosticsCopied = true; }; });
  await page.locator('#deviceButton').click();
  await page.locator('#copyLog').click();
  assert.equal(await page.evaluate(() => window.diagnosticsCopied), true);
  await page.locator('#deviceDialog .primary').click();
  await page.evaluate(() => onNativeEvent('imported', { target: 'images', items: [
    { id: 'a', name: 'one.png', kind: 'image', width: 640, height: 480, thumbnail: 'data:image/png;base64,iVBORw0KGgo=', url: 'data:image/png;base64,iVBORw0KGgo=' },
    { id: 'b', name: 'two.png', kind: 'image', width: 480, height: 640, thumbnail: 'data:image/png;base64,iVBORw0KGgo=', url: 'data:image/png;base64,iVBORw0KGgo=' }
  ] }));
  await page.getByRole('button', { name: '上移 two.png', exact: true }).click();
  await page.locator('#printImages').click(); await page.locator('#startPrint').click();
  assert.deepEqual(await page.evaluate(() => window.submittedJob.ids), ['b', 'a']);
  await page.evaluate(() => { window.diagnosticsCopied = false; onNativeEvent('finished', { ok: false, message: 'QA simulated serial error' }); });
  await page.locator('#copyPrintLog').click();
  assert.equal(await page.evaluate(() => window.diagnosticsCopied), true);
  await page.locator('#closeProgress').click();
  // Exercise actual SVG and TIFF decoding, then inspect the PNG handed to Android.
  const UTIF = require('utif');
  const rgba = new Uint8Array(4 * 8 * 4).fill(255);
  const tiff = Buffer.from(UTIF.encodeImage(rgba.buffer, 8, 4));
  await page.route('**/files/qa-tiff', route => route.fulfill({ contentType: 'image/tiff', body: tiff }));
  await page.route('**/files/qa-svg', route => route.fulfill({ contentType: 'image/svg+xml', body: '<svg xmlns="http://www.w3.org/2000/svg" width="80" height="40"><rect width="80" height="40" fill="black"/></svg>' }));
  await page.evaluate(() => { window.rasterResults = {}; Android.commitRaster = (id, data) => { window.rasterResults[id] = data; }; });
  for (const [id, mime] of [['qa-svg', 'image/svg+xml'], ['qa-tiff', 'image/tiff']]) {
    await page.evaluate(({ id, mime }) => onNativeEvent('imported', { target: 'images', items: [{ id, mime, name: id, kind: 'special', url: '/files/' + id }] }), { id, mime });
    await page.waitForFunction(id => !!window.rasterResults[id], id);
    const dimensions = await page.evaluate(async id => {
      const image = new Image(); image.src = window.rasterResults[id]; await image.decode(); return [image.width, image.height];
    }, id);
    assert.deepEqual(dimensions, [864, 432]);
  }
  // Verify real document pagination and formula resources without a device.
  const render = await browser.newPage({ viewport: { width: 432, height: 1000 } });
  render.on('pageerror', error => errors.push(error.message));
  await render.goto('http://127.0.0.1:4173/render.html');
  const metrics = await render.evaluate(async () => {
    const html = PrintContent.prepare('md', '# 分页与公式\n\n' + Array.from({ length: 120 }, (_, i) => '第 ' + i + ' 行 $\\frac{1}{2}$。\n\n').join(''));
    loadPrintDocument(html, 18);
    while (!window.printReady && !window.printFailure) await new Promise(resolve => setTimeout(resolve, 100));
    return { breaks: printBreaks, errors: document.querySelectorAll('.katex-error').length, formulas: document.querySelectorAll('.katex').length, width: document.querySelector('.paper').getBoundingClientRect().width };
  });
  assert.equal(metrics.errors, 0); assert.equal(metrics.formulas, 120); assert.equal(metrics.width, 432);
  assert.ok(metrics.breaks.length > 3);
  for (let index = 1; index < metrics.breaks.length; index++) assert.ok(metrics.breaks[index] - metrics.breaks[index - 1] <= 1400);
  await render.screenshot({ path: 'qa/screenshots/render-pagination.png' });
  assert.deepEqual(errors, []);
  const report = { result: 'PASS', viewport: '534×320', mathDelimiters: 5, richAlignment: 'single and multiple paragraphs', tested: ['home', 'rich text', 'Markdown math', 'multiline math', 'formula insert', 'settings', 'copy diagnostics from settings and a failed print', 'compact layout', 'image order and native bridge payload', 'SVG rasterization', 'TIFF decoding', 'document pagination'], pagination: metrics };
  fs.writeFileSync('qa/ui-results.json', JSON.stringify(report, null, 2));
  console.log(JSON.stringify(report));
  await browser.close();
})().catch(error => { console.error(error); process.exit(1); });
