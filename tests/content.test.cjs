const { test } = require('node:test');
const assert = require('node:assert/strict');
const content = require('../app/src/main/assets/content.js');
const markdownit = require('markdown-it');
const katex = require('katex');

test('all requested math delimiters, including multiline brackets', () => {
  const source = '$\\LaTeX$ $$\\LaTeX$$ \\[ \\LaTeX \\] \\( \\LaTeX \\)\n\\[\n\\LaTeX\n\\]';
  const matches = content.findMath(source, true);
  assert.equal(matches.length, 5);
  assert.deepEqual(matches.map(x => x.display), [false, true, true, false, true]);
  const html = content.renderMarkdown(source, markdownit(), katex);
  assert.equal((html.match(/class="katex"/g) || []).length, 5);
  assert.ok(!html.includes('katex-error'));
});

test('markdown code remains literal and escaped delimiters remain literal', () => {
  const text = '`$x$`\n\n```tex\n$$x$$\n```\n\n\\$x\\$ and $y$';
  const html = content.renderMarkdown(text, markdownit(), katex);
  assert.equal((html.match(/class="katex"/g) || []).length, 1);
  assert.ok(html.includes('<code>$x$</code>'));
  assert.ok(html.includes('$$x$$'));
});

test('multiline matrices and bracket math preserve backslashes', () => {
  const source = '\\[\n\\begin{aligned}\nx &= \\frac{1}{2} \\\\\ny &= \\sqrt{2}\n\\end{aligned}\n\\]';
  const html = content.renderMarkdown(source, markdownit(), katex);
  assert.ok(html.includes('class="katex"'));
  assert.ok(!html.includes('katex-error'));
});

test('unclosed math and ordinary currency are retained', () => {
  assert.equal(content.findMath('$unclosed', true).length, 0);
  assert.equal(content.findMath('Price $5 and $10', true).length, 0);
  assert.equal(content.findMath('\\[unclosed', true).length, 0);
});

test('placeholder-like user text does not collide with protected equations', () => {
  const html = content.renderMarkdown('P3MATHTOKEN0END and $x_1$', markdownit(), katex);
  assert.ok(html.includes('P3MATHTOKEN0END'));
  assert.equal((html.match(/class="katex"/g) || []).length, 1);
});
