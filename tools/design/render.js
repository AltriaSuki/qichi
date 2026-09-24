// 用法：NODE_PATH=$(npm root -g) node tools/design/render.js design/screens 截图目录 [文件名前缀…]
// 把 .dc.html 画板去掉画布运行时外壳，渲染成截图，并报告内容溢出
const fs = require('fs'), path = require('path');
const { chromium } = require('playwright');
const dir = process.argv[2], out = process.argv[3], only = process.argv.slice(4);
function toPlain(src) {
  const helmet = (src.match(/<helmet>([\s\S]*?)<\/helmet>/) || [,''])[1];
  let body = src.replace(/<helmet>[\s\S]*?<\/helmet>/, '');
  body = body.slice(body.indexOf('<x-dc>') + 6, body.indexOf('</x-dc>'));
  return `<!doctype html><html lang="zh-CN"><head><meta charset="utf-8">${helmet}</head><body>${body}</body></html>`;
}
(async () => {
  const proxy = process.env.HTTPS_PROXY ? { server: process.env.HTTPS_PROXY } : undefined;
  const browser = await chromium.launch({ executablePath: '/opt/pw-browsers/chromium', proxy, args: ['--ignore-certificate-errors'] });
  const ctx = await browser.newContext({ ignoreHTTPSErrors: true, deviceScaleFactor: 1 });
  const files = fs.readdirSync(dir).filter(f => f.endsWith('.dc.html') && (only.length ? only.some(o => f.startsWith(o)) : true));
  for (const f of files) {
    const src = fs.readFileSync(path.join(dir, f), 'utf8');
    const m = src.match(/"\$preview":\{"width":(\d+),"height":(\d+)\}/);
    const w = +m[1], h = +m[2];
    const page = await ctx.newPage();
    await page.setViewportSize({ width: w, height: h });
    await page.setContent(toPlain(src), { waitUntil: 'networkidle', timeout: 60000 }).catch(() => {});
    await page.evaluate(() => document.fonts.ready);
    await page.waitForTimeout(300);
    const issues = await page.evaluate(() => {
      const r = [];
      document.querySelectorAll('[data-fit]').forEach(el => {
        const c = el.firstElementChild; if (!c) return;
        const t = c.firstElementChild ? c.firstElementChild.getBoundingClientRect().top : 0;
        const top = el.getBoundingClientRect().top;
        if (t < top - 2) r.push(`顶部被裁 ${Math.round(top - t)}px`);
      });
      document.querySelectorAll('main, [data-check]').forEach(el => {
        const over = el.scrollHeight - el.clientHeight;
        if (over > 2) r.push(`滚动区超出 ${over}px`);
      });
      const root = document.body.firstElementChild;
      const rb = root.getBoundingClientRect();
      // 文字被根容器裁掉
      document.querySelectorAll('p, h1, h2, span, a, button, div').forEach(el => {
        if (!el.childNodes.length || el.closest('svg')) return;
        const hasText = [...el.childNodes].some(n => n.nodeType === 3 && n.textContent.trim());
        if (!hasText) return;
        const b = el.getBoundingClientRect();
        if (b.width && (b.right > rb.right + 1 || b.left < rb.left - 1)) r.push(`横向出界：${el.textContent.trim().slice(0, 16)}`);
      });
      return [...new Set(r)].slice(0, 8);
    });
    await page.screenshot({ path: path.join(out, f.replace('.dc.html', '.png')) });
    console.log(f.padEnd(28), issues.length ? issues.join(' | ') : 'ok');
    await page.close();
  }
  await browser.close();
})();
