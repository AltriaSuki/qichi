// 用法：NODE_PATH=$(npm root -g) node tools/design/sheet.js 拼图.png 1.png 2.png …（几张截图并排拼成一张）
const fs=require('fs'),path=require('path');const {chromium}=require('playwright');
(async()=>{const [out,...imgs]=process.argv.slice(2);
const b=await chromium.launch({executablePath:'/opt/pw-browsers/chromium'});const p=await b.newPage();
const html='<body style="margin:0;background:#888;display:flex;gap:10px;align-items:flex-start">'+imgs.map(i=>`<img src="data:image/png;base64,${fs.readFileSync(i).toString('base64')}" style="width:390px">`).join('')+'</body>';
await p.setViewportSize({width:imgs.length*400,height:844});await p.setContent(html);await p.screenshot({path:out,fullPage:true});await b.close();})();
