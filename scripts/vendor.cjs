const fs = require('node:fs');
const path = require('node:path');
const root = path.resolve(__dirname, '..');
const target = path.join(root, 'app/src/main/assets/vendor');
fs.mkdirSync(target, { recursive: true });
for (const [source, destination] of [
  ['quill/dist/quill.min.js', 'quill.js'],
  ['quill/dist/quill.snow.css', 'quill.css'],
  ['katex/dist/katex.min.js', 'katex.js'],
  ['katex/dist/katex.min.css', 'katex.css'],
  ['katex/dist/fonts', 'fonts'],
  ['markdown-it/dist/markdown-it.min.js', 'markdown-it.js'],
  ['dompurify/dist/purify.min.js', 'purify.js'],
  ['utif/UTIF.js', 'utif.js'],
  ['pako/dist/pako.min.js', 'pako.js']
]) {
  fs.cpSync(path.join(root, 'node_modules', source), path.join(target, destination), { recursive: true });
}
const licenses = ['quill/LICENSE', 'katex/LICENSE', 'markdown-it/LICENSE', 'dompurify/LICENSE', 'utif/LICENSE', 'pako/LICENSE'];
fs.writeFileSync(path.join(target, 'LICENSES.txt'), licenses.map(file => file + '\n' + fs.readFileSync(path.join(root, 'node_modules', file), 'utf8')).join('\n\n'));
console.log('Offline assets and third-party licenses copied.');
