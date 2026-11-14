(function (root, factory) {
  var api = factory();
  if (typeof module === 'object' && module.exports) module.exports = api;
  else root.PrintContent = api;
}(typeof window !== 'undefined' ? window : globalThis, function () {
  'use strict';
  function escaped(text, position) {
    var count = 0;
    while (position > 0 && text[--position] === '\\') count++;
    return count % 2 === 1;
  }
  function findMath(text, skipCode) {
    var matches = [];
    for (var position = 0; position < text.length;) {
      if (skipCode && (text[position] === '`' || (text[position] === '~' && (position === 0 || text[position - 1] === '\n')))) {
        var character = text[position], endRun = position;
        while (text[endRun] === character) endRun++;
        var marker = text.slice(position, endRun);
        if (character === '`' || marker.length >= 3) {
          var codeEnd = text.indexOf(marker, endRun);
          position = codeEnd < 0 ? text.length : codeEnd + marker.length;
          continue;
        }
      }
      var open = '', close = '', display = false;
      if (!escaped(text, position)) {
        if (text.slice(position, position + 2) === '$$') { open = close = '$$'; display = true; }
        else if (text.slice(position, position + 2) === '\\[') { open = '\\['; close = '\\]'; display = true; }
        else if (text.slice(position, position + 2) === '\\(') { open = '\\('; close = '\\)'; }
        else if (text[position] === '$' && text[position + 1] && !/\s/.test(text[position + 1])) { open = close = '$'; }
      }
      if (!open) { position++; continue; }
      var end = position + open.length;
      var found = false;
      while ((end = text.indexOf(close, end)) >= 0) {
        if (text.slice(position, end).indexOf('\u0000') >= 0) break;
        if (!escaped(text, end) && (close !== '$' || (!/\s/.test(text[end - 1]) && !/[0-9$]/.test(text[end + 1] || '')))) {
          found = true; break;
        }
        end += close.length;
      }
      if (found && (display || text.slice(position, end).indexOf('\n') < 0)) {
        matches.push({ start: position, end: end + close.length, tex: text.slice(position + open.length, end), display: display });
        position = end + close.length;
      } else position += open.length;
    }
    return matches;
  }

  function mathHTML(match, katex) {
    return '<span class="print-math' + (match.display ? ' display-math' : '') + '">' + katex.renderToString(match.tex, {
      displayMode: match.display, throwOnError: false, strict: 'ignore', trust: false, output: 'html', maxExpand: 1000, maxSize: 20
    }) + '</span>';
  }

  function renderMarkdown(source, parser, katex) {
    var matches = findMath(source, true);
    var protectedSource = source;
    var prefix = 'P3MATHTOKEN';
    while (source.indexOf(prefix) >= 0) prefix += 'X';
    for (var index = matches.length - 1; index >= 0; index--) {
      protectedSource = protectedSource.slice(0, matches[index].start) + prefix + index + 'END' + protectedSource.slice(matches[index].end);
    }
    var html = parser.render(protectedSource);
    matches.forEach(function (match, index) { html = html.split(prefix + index + 'END').join(mathHTML(match, katex)); });
    return html;
  }

  function renderRich(container, katex) {
    var text = '', nodes = [];
    function visit(node) {
      if (node.nodeType === 3) {
        nodes.push({ node: node, start: text.length, end: text.length + node.data.length });
        text += node.data;
        return;
      }
      if (node.nodeType !== 1) return;
      if (/^(CODE|PRE|SCRIPT|STYLE)$/.test(node.tagName) || /(?:^|\s)(?:ql-formula|katex)(?:\s|$)/.test(node.className || '')) {
        text += '\u0000'; return;
      }
      var block = /^(P|DIV|LI|H[1-6]|BLOCKQUOTE|TR)$/.test(node.tagName);
      if (block && text && text[text.length - 1] !== '\n') text += '\n';
      if (node.tagName === 'BR') text += '\n';
      Array.prototype.forEach.call(node.childNodes, visit);
      if (block && text[text.length - 1] !== '\n') text += '\n';
    }
    visit(container);
    var matches = findMath(text, false);
    for (var index = matches.length - 1; index >= 0; index--) {
      var match = matches[index];
      var first = nodes.find(function (entry) { return entry.start <= match.start && entry.end > match.start; });
      var last = nodes.find(function (entry) { return entry.start < match.end && entry.end >= match.end; });
      if (!first || !last) continue;
      var range = container.ownerDocument.createRange();
      range.setStart(first.node, match.start - first.start);
      range.setEnd(last.node, match.end - last.start);
      var wrapper = container.ownerDocument.createElement('span');
      wrapper.innerHTML = mathHTML(match, katex);
      range.deleteContents();
      range.insertNode(wrapper.firstChild);
    }
    return container;
  }

  function cleanHTML(html) {
    var clean = window.DOMPurify.sanitize(html, { USE_PROFILES: { html: true, svg: true, svgFilters: false }, FORBID_TAGS: ['style', 'iframe', 'object', 'embed', 'form'], FORBID_ATTR: ['srcset'] });
    var container = document.createElement('div');
    container.innerHTML = clean;
    container.querySelectorAll('img').forEach(function (image) {
      var src = image.getAttribute('src') || '';
      if (!/^data:image\/(?:png|jpeg|gif|webp|bmp);base64,/i.test(src) && !/^\/(?:files|thumb)\/[a-f0-9-]+$/.test(src)) {
        var note = document.createElement('span');
        note.className = 'missing-image';
        note.textContent = '[图片需嵌入文档或单独选择打印]';
        image.parentNode.replaceChild(note, image);
      }
    });
    return container;
  }

  function prepare(type, source) {
    var parser = window.markdownit({ html: false, breaks: true, linkify: false });
    var container;
    if (type === 'md') container = cleanHTML(renderMarkdown(source, parser, window.katex));
    else {
      container = cleanHTML(source);
      renderRich(container, window.katex);
    }
    return container.innerHTML;
  }

  // Split long documents at line boundaries. Formula boxes and images remain whole when they fit.
  function pageBreaks(container, maximumHeight) {
    var top = container.getBoundingClientRect().top;
    var intervals = [];
    var walker = document.createTreeWalker(container, 4, null, false);
    var node;
    while ((node = walker.nextNode())) {
      if (!node.data.trim()) continue;
      var range = document.createRange();
      range.selectNodeContents(node);
      Array.prototype.forEach.call(range.getClientRects(), function (rect) {
        if (rect.height > 0) intervals.push([Math.floor(rect.top - top), Math.ceil(rect.bottom - top)]);
      });
    }
    container.querySelectorAll('img,.print-math,.ql-formula,tr').forEach(function (element) {
      var rect = element.getBoundingClientRect();
      intervals.push([Math.floor(rect.top - top), Math.ceil(rect.bottom - top)]);
    });
    var height = Math.ceil(container.scrollHeight);
    var breaks = [0], position = 0;
    while (position < height) {
      var end = Math.min(height, position + maximumHeight);
      var changed = true;
      while (changed) {
        changed = false;
        intervals.forEach(function (interval) {
          if (interval[0] < end && interval[1] > end && interval[0] > position + 20) { end = interval[0]; changed = true; }
        });
      }
      if (end <= position) throw new Error('文档分页失败');
      breaks.push(end);
      position = end;
      if (breaks.length > 501) throw new Error('文档超过 500 页，请分段打印');
    }
    return breaks;
  }

  return { findMath: findMath, renderMarkdown: renderMarkdown, renderRich: renderRich, prepare: prepare, pageBreaks: pageBreaks };
}));
