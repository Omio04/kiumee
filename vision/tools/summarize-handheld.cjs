const fs = require('node:fs');
const report = JSON.parse(fs.readFileSync(process.argv[2], 'utf8'));
for (const stage of [...new Set(report.frames.map(f=>f.stage))]) {
  const frames = report.frames.filter(f=>f.stage===stage);
  console.log(stage, frames.map(f=>`${f.name}:${f.scene||'?'}:${f.snapshot?.screenId||'-'}${f.changed?'*':''}`).join(' '));
}
console.log(JSON.stringify({frames:report.frames.length,
  correct:report.frames.filter(f=>f.scene===f.expected).length,
  wrong:report.frames.filter(f=>f.scene && f.scene!==f.expected).length,
  unknown:report.frames.filter(f=>!f.scene).length,
  published:report.frames.filter(f=>f.snapshot).length,
  changes:report.frames.filter(f=>f.changed).length},null,2));
