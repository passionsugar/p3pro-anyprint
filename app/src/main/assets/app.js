(function () {
  'use strict';
  var $ = function (id) { return document.getElementById(id); };
  var gapProbe = document.createElement('div');
  gapProbe.style.cssText = 'display:flex;flex-direction:column;row-gap:1px;position:absolute;visibility:hidden;';
  gapProbe.innerHTML = '<div style="height:1px"></div><div style="height:1px"></div>';
  document.body.appendChild(gapProbe);
  if (gapProbe.scrollHeight !== 3) document.documentElement.classList.add('no-flex-gap');
  document.body.removeChild(gapProbe);
  var state = { screen: 'home', images: [], currentImage: null, document: null, pdfPage: 0, editorMode: 'rich', previewing: false, pending: null, mode: 'auto', lastRange: null };
  var toastTimer;
  var nativeApp = typeof window.Android !== 'undefined';
  function toast(message) { $('toast').textContent = message; $('toast').classList.add('visible'); clearTimeout(toastTimer); toastTimer = setTimeout(function () { $('toast').classList.remove('visible'); }, 3800); }
  function call(method) {
    var args = Array.prototype.slice.call(arguments, 1);
    if (!nativeApp) { toast('这是浏览器界面预览，请在 P3 Pro 上安装 APK 后操作'); return; }
    try { return window.Android[method].apply(window.Android, args); } catch (error) { toast(error.message); }
  }
  function openDialog(dialog) { if (dialog.showModal) dialog.showModal(); else { dialog.setAttribute('open', ''); dialog.classList.add('fallback-dialog'); } }
  function closeDialog(dialog) { if (dialog.close) dialog.close(); else { dialog.removeAttribute('open'); dialog.classList.remove('fallback-dialog'); } }
  document.querySelectorAll('dialog form[method=dialog]').forEach(function (form) { form.addEventListener('submit', function (event) { event.preventDefault(); closeDialog(form.parentNode); }); });

  var quill = new Quill('#editor', {
    theme: 'snow', placeholder: '写下文字，或粘贴含 LaTeX 公式的内容…',
    modules: {
      toolbar: { container: '#toolbar', handlers: { insertmath: showFormula, undo: function () { this.quill.history.undo(); }, redo: function () { this.quill.history.redo(); } } },
      history: { delay: 500, maxStack: 100, userOnly: true }
    },
    formats: ['header', 'size', 'bold', 'italic', 'underline', 'strike', 'list', 'align', 'formula', 'indent', 'blockquote', 'code-block']
  });
  quill.on('selection-change', function (range) { if (range) state.lastRange = range; });
  var saveTimer;
  function saveDraft() {
    clearTimeout(saveTimer);
    saveTimer = setTimeout(function () {
      try {
        localStorage.setItem('p3-rich-draft', JSON.stringify(quill.getContents()));
        localStorage.setItem('p3-md-draft', $('markdownSource').value);
        $('draftStatus').textContent = (state.editorMode === 'rich' ? '富文本' : 'Markdown') + '草稿已保存';
      } catch (error) { $('draftStatus').textContent = '草稿空间不足，请及时打印'; }
    }, 300);
  }
  quill.on('text-change', saveDraft);
  $('markdownSource').addEventListener('input', saveDraft);
  try {
    var richDraft = localStorage.getItem('p3-rich-draft');
    if (richDraft) quill.setContents(JSON.parse(richDraft));
    $('markdownSource').value = localStorage.getItem('p3-md-draft') || '';
  } catch (error) { toast('草稿读取失败，可以重新编辑'); }

  function showScreen(name) {
    if (['home', 'images', 'documents', 'text'].indexOf(name) < 0) return;
    document.querySelectorAll('.screen').forEach(function (screen) { screen.classList.toggle('active', screen.id === name); });
    state.screen = name;
    $('screenTitle').textContent = { home: '选择打印内容', images: '图片打印', documents: '文档打印', text: '文本打印' }[name];
  }
  $('homeButton').onclick = function () { showScreen('home'); };
  document.querySelectorAll('[data-screen]').forEach(function (button) { button.onclick = function () { showScreen(button.dataset.screen); }; });
  window.goHome = function () { if (state.screen !== 'home') { showScreen('home'); return true; } return false; };
  $('pickImages').onclick = function () { call('chooseFiles', 'images'); };
  $('pickDocument').onclick = function () { call('chooseFiles', 'documents'); };
  $('importText').onclick = function () { call('chooseFiles', 'text'); };

  function redrawImages() {
    var list = $('imageList'); list.innerHTML = '';
    state.images.forEach(function (item, index) {
      var row = document.createElement('div'); row.className = 'file-row' + (state.currentImage === item.id ? ' current' : '');
      var check = document.createElement('input'); check.type = 'checkbox'; check.checked = item.selected; check.setAttribute('aria-label', '选择 ' + item.name);
      check.onclick = function (event) { event.stopPropagation(); item.selected = check.checked; updateImageCount(); };
      var thumb = document.createElement('img'); thumb.src = item.thumbnail; thumb.alt = '';
      var label = document.createElement('div'); var name = document.createElement('span'); name.className = 'file-name'; name.textContent = item.name; label.appendChild(name);
      var dimensions = document.createElement('small'); dimensions.textContent = (index + 1) + ' · ' + item.width + '×' + item.height; label.appendChild(dimensions);
      var order = document.createElement('span'); order.className = 'order';
      [-1, 1].forEach(function (direction) {
        var move = document.createElement('button'); move.textContent = direction < 0 ? '↑' : '↓'; move.setAttribute('aria-label', (direction < 0 ? '上移 ' : '下移 ') + item.name);
        move.disabled = index + direction < 0 || index + direction >= state.images.length;
        move.onclick = function (event) { event.stopPropagation(); var other = state.images[index + direction]; state.images[index + direction] = item; state.images[index] = other; redrawImages(); };
        order.appendChild(move);
      });
      row.appendChild(check); row.appendChild(thumb); row.appendChild(label); row.appendChild(order);
      row.onclick = function () { state.currentImage = item.id; redrawImages(); };
      list.appendChild(row);
    });
    var selected = state.images.find(function (item) { return item.id === state.currentImage; });
    if (selected) { $('imagePreview').innerHTML = ''; var preview = document.createElement('img'); preview.src = selected.thumbnail; preview.alt = selected.name; $('imagePreview').appendChild(preview); }
    updateImageCount();
  }
  function updateImageCount() {
    var count = state.images.filter(function (item) { return item.selected; }).length;
    $('imageCount').textContent = '已选 ' + count + ' / ' + state.images.length + ' 张';
    $('printImages').disabled = count === 0;
  }
  function copyCount(section) {
    var input = $(section).querySelector('.copies'); var value = Number(input.value);
    if (!Number.isInteger(value) || value < 1 || value > 20) { toast('份数应为 1 到 20 的整数'); input.focus(); return null; }
    return value;
  }
  function confirmPrint(job, summary) {
    state.pending = job;
    $('confirmation').textContent = summary;
    openDialog($('confirmDialog'));
  }
  $('printImages').onclick = function () {
    var copies = copyCount('images'); if (!copies) return;
    var ids = state.images.filter(function (item) { return item.selected; }).map(function (item) { return item.id; });
    confirmPrint({ kind: 'images', ids: ids, copies: copies, mode: state.mode }, ids.length + ' 张图片，' + copies + ' 份。按列表顺序等比例缩放到 864 点纸宽。');
  };
  $('cancelConfirm').onclick = function () { closeDialog($('confirmDialog')); };
  $('startPrint').onclick = function () {
    closeDialog($('confirmDialog'));
    if (!nativeApp) { toast('请在 P3 Pro 上安装 APK 后打印'); return; }
    $('progressTitle').textContent = '准备打印'; $('progressMessage').textContent = '正在准备内容'; $('progress').value = 0;
    $('stopPrint').disabled = false; $('stopPrint').classList.remove('hidden'); $('closeProgress').classList.add('hidden');
    openDialog($('progressDialog')); call('printJob', JSON.stringify(state.pending)); state.pending = null;
  };
  $('stopPrint').onclick = function () { call('cancelPrint'); $('stopPrint').disabled = true; $('progressMessage').textContent = '正在停止后续打印。系统已接收的当前页可能继续打印。'; };
  $('closeProgress').onclick = function () { closeDialog($('progressDialog')); };
  $('progressDialog').addEventListener('cancel', function (event) { if ($('closeProgress').classList.contains('hidden')) event.preventDefault(); });

  function paperHTML(type, source) { return PrintContent.prepare(type, source); }
  function putPaper(target, html, size) {
    target.innerHTML = ''; var article = document.createElement('article'); article.className = 'paper'; article.style.fontSize = size + 'px'; article.innerHTML = html; target.appendChild(article);
    setTimeout(function () {
      article.querySelectorAll('.katex-display,.ql-formula').forEach(function (math) { if (math.scrollWidth > article.clientWidth - 24) math.style.fontSize = ((article.clientWidth - 24) / math.scrollWidth * 100) + '%'; });
    }, 150);
    return article;
  }
  function documentLoaded(item) {
    state.document = item; state.pdfPage = 0; $('documentName').textContent = item.name; $('documentEmpty').classList.add('hidden'); $('documentPreview').classList.remove('hidden');
    $('pdfControls').classList.toggle('hidden', item.kind !== 'pdf'); $('documentEdit').classList.toggle('hidden', item.kind === 'pdf'); $('printDocument').disabled = false;
    if (item.kind === 'pdf') { $('pdfRange').value = ''; requestPdf(); $('documentHint').textContent = item.pages + ' 页 · 逐页转成 864 点宽图片'; }
    else { var html = item.kind === 'md' ? paperHTML('md', item.text) : paperHTML('rich', plainHTML(item.text)); putPaper($('documentPreview'), html, 18); $('documentHint').textContent = '已离线排版 · 请检查内容与公式'; }
  }
  function plainHTML(text) { var element = document.createElement('div'); element.textContent = text; return '<div style="white-space:pre-wrap">' + element.innerHTML + '</div>'; }
  function requestPdf() { $('pdfPageLabel').textContent = (state.pdfPage + 1) + ' / ' + state.document.pages; $('pdfPrevious').disabled = state.pdfPage === 0; $('pdfNext').disabled = state.pdfPage === state.document.pages - 1; call('previewPdf', state.document.id, state.pdfPage); }
  $('pdfPrevious').onclick = function () { if (state.pdfPage > 0) { state.pdfPage--; requestPdf(); } };
  $('pdfNext').onclick = function () { if (state.pdfPage < state.document.pages - 1) { state.pdfPage++; requestPdf(); } };
  $('documentEdit').onclick = function () { editImportedText(state.document); };
  $('printDocument').onclick = function () {
    if (!state.document) return; var copies = copyCount('documents'); if (!copies) return;
    var item = state.document;
    if (item.kind === 'pdf') confirmPrint({ kind: 'pdf', id: item.id, range: $('pdfRange').value, copies: copies, mode: state.mode }, item.name + '，页码 ' + ($('pdfRange').value.trim() || '全部') + '，' + copies + ' 份。');
    else {
      var html = item.kind === 'md' ? paperHTML('md', item.text) : paperHTML('rich', plainHTML(item.text));
      if (!validateHTML(html)) return;
      confirmPrint({ kind: 'html', html: html, fontSize: 18, copies: copies, mode: state.mode }, item.name + '，' + copies + ' 份。长文档会在行间自动分页。');
    }
  };
  function editImportedText(item) {
    if (item.kind === 'md') { $('markdownSource').value = item.text; setEditorMode('md'); }
    else { quill.setText(item.text); setEditorMode('rich'); }
    showScreen('text'); saveDraft();
  }

  function setEditorMode(mode) {
    state.editorMode = mode; state.previewing = false;
    $('richArea').classList.toggle('hidden', mode !== 'rich'); $('markdownSource').classList.toggle('hidden', mode !== 'md'); $('textPreview').classList.add('hidden');
    $('richTab').classList.toggle('selected', mode === 'rich'); $('markdownTab').classList.toggle('selected', mode === 'md'); $('textPreviewTab').classList.remove('selected');
    $('draftStatus').textContent = (mode === 'rich' ? '富文本' : 'Markdown') + '独立草稿 · 自动保存';
    $('printText').textContent = '预览并打印';
  }
  $('richTab').onclick = function () { setEditorMode('rich'); };
  $('markdownTab').onclick = function () { setEditorMode('md'); };
  function editorHTML() { return paperHTML(state.editorMode === 'md' ? 'md' : 'rich', state.editorMode === 'md' ? $('markdownSource').value : quill.root.innerHTML); }
  function validateHTML(html) {
    var test = document.createElement('div'); test.innerHTML = html;
    if (!test.textContent.trim() && !test.querySelector('img,.ql-formula')) { toast('请先输入内容'); return false; }
    if (test.querySelector('.katex-error')) { toast('存在无法渲染的公式，请在预览中检查并修正'); return false; }
    return true;
  }
  function showTextPreview() {
    var html = editorHTML(); putPaper($('textPreview'), html, Number($('printFont').value)); state.previewing = true;
    $('richArea').classList.add('hidden'); $('markdownSource').classList.add('hidden'); $('textPreview').classList.remove('hidden');
    $('richTab').classList.remove('selected'); $('markdownTab').classList.remove('selected'); $('textPreviewTab').classList.add('selected');
    $('printText').textContent = '打印文本'; return html;
  }
  $('textPreviewTab').onclick = showTextPreview;
  $('printFont').onchange = function () { if (state.previewing) showTextPreview(); };
  $('printText').onclick = function () {
    var wasPreview = state.previewing; var html = showTextPreview();
    if (!validateHTML(html) || !wasPreview) return;
    var copies = copyCount('text'); if (!copies) return;
    confirmPrint({ kind: 'html', html: html, fontSize: Number($('printFont').value), copies: copies, mode: state.mode }, (state.editorMode === 'md' ? 'Markdown' : '富文本') + '内容，' + copies + ' 份。长内容会在行间自动分页。');
  };
  $('sampleText').onclick = function () {
    var sample = '# 公式打印示例\n\n行内公式 $E=mc^2$，括号公式 \\(a^2+b^2=c^2\\)。\n\n$$\\frac{-b\\pm\\sqrt{b^2-4ac}}{2a}$$\n\n\\[\\LaTeX\\]\n\n\\[\n\\int_0^1 x^2\\,dx=\\frac{1}{3}\n\\]\n\n矩阵\n\n$$\\begin{pmatrix}1&2\\\\3&4\\end{pmatrix}$$\n';
    $('markdownSource').value = sample; setEditorMode('md'); saveDraft();
  };
  function showFormula() {
    state.lastRange = quill.getSelection() || state.lastRange || { index: quill.getLength() - 1, length: 0 };
    $('formulaSource').value = ''; $('formulaPreview').innerHTML = ''; openDialog($('formulaDialog')); $('formulaSource').focus();
  }
  $('formulaSource').oninput = function () { try { window.katex.render($('formulaSource').value, $('formulaPreview'), { throwOnError: false, trust: false, displayMode: $('displayFormula').checked }); } catch (error) { $('formulaPreview').textContent = error.message; } };
  $('displayFormula').onchange = $('formulaSource').oninput;
  $('cancelFormula').onclick = function () { closeDialog($('formulaDialog')); };
  $('insertFormula').onclick = function () {
    var source = $('formulaSource').value.trim(); if (!source) return;
    var matches = PrintContent.findMath(source, false); if (matches.length === 1 && matches[0].start === 0 && matches[0].end === source.length) source = matches[0].tex;
    try { window.katex.renderToString(source, { throwOnError: true, trust: false }); } catch (error) { toast('公式语法错误，请检查'); return; }
    var range = state.lastRange || { index: quill.getLength() - 1, length: 0 };
    closeDialog($('formulaDialog')); quill.focus();
    if (range.length) quill.deleteText(range.index, range.length, 'user');
    var index = range.index;
    if ($('displayFormula').checked) {
      if (index > 0 && quill.getText(index - 1, 1) !== '\n') { quill.insertText(index, '\n', 'user'); index++; }
      quill.insertEmbed(index, 'formula', source, 'user'); quill.insertText(index + 1, '\n', 'user'); quill.formatLine(index, 1, 'align', 'center', 'user'); quill.setSelection(index + 2, 0);
    } else { quill.insertEmbed(index, 'formula', source, 'user'); quill.insertText(index + 1, ' ', 'user'); quill.setSelection(index + 2, 0); }
    saveDraft();
  };

  $('deviceButton').onclick = function () { openDialog($('deviceDialog')); call('inspectDevice'); };
  $('refreshDevice').onclick = function () { call('inspectDevice'); };
  $('exportLog').onclick = function () { call('exportDiagnostics'); };
  $('copyLog').onclick = $('copyPrintLog').onclick = function () { call('copyDiagnostics'); };
  $('backendMode').onchange = function () { state.mode = $('backendMode').value; call('setMode', state.mode); };

  function loadBinary(url) {
    return new Promise(function (resolve, reject) {
      var request = new XMLHttpRequest(); request.open('GET', url); request.responseType = 'arraybuffer';
      request.onload = function () { if (request.status >= 200 && request.status < 300) resolve(request.response); else reject(new Error('读取图片失败')); };
      request.onerror = function () { reject(new Error('读取图片失败')); }; request.send();
    });
  }
  function convertSpecial(item) {
    return loadBinary(item.url).then(function (bytes) {
      if (item.mime === 'image/tiff') {
        var pages = UTIF.decode(bytes); if (!pages.length) throw new Error('TIFF 没有图像');
        var width = Number(pages[0].t256 && pages[0].t256[0]), height = Number(pages[0].t257 && pages[0].t257[0]);
        if (!(width > 0 && height > 0) || width * height > 32000000 || height * 864 / width > 20000) throw new Error('TIFF 图片过大，请缩小或分割');
        UTIF.decodeImage(bytes, pages[0]); var rgba = UTIF.toRGBA8(pages[0]);
        var original = document.createElement('canvas'); original.width = width; original.height = height;
        var context = original.getContext('2d'); var pixels = context.createImageData(width, height); pixels.data.set(rgba); context.putImageData(pixels, 0, 0);
        var result = document.createElement('canvas'); result.width = 864; result.height = Math.max(1, Math.round(height * 864 / width)); result.getContext('2d').drawImage(original, 0, 0, result.width, result.height);
        call('commitRaster', item.id, result.toDataURL('image/png'));
        if (pages.length > 1) toast('多页 TIFF 当前导入第 1 页');
        original.width = original.height = 0; result.width = result.height = 0;
        return;
      }
      var text = new TextDecoder('utf-8').decode(bytes);
      var clean = DOMPurify.sanitize(text, { USE_PROFILES: { svg: true }, FORBID_TAGS: ['foreignObject', 'script', 'style'] });
      var doc = new DOMParser().parseFromString(clean, 'image/svg+xml'); var svg = doc.documentElement;
      if (svg.tagName.toLowerCase() !== 'svg' || doc.querySelector('parsererror')) throw new Error('SVG 格式无效');
      svg.querySelectorAll('[href],[xlink\\:href]').forEach(function (element) {
        ['href', 'xlink:href'].forEach(function (attribute) { var value = element.getAttribute(attribute); if (value && value[0] !== '#' && !/^data:image\/(png|jpeg|webp);base64,/i.test(value)) element.removeAttribute(attribute); });
      });
      return new Promise(function (resolve, reject) {
        var blob = new Blob([new XMLSerializer().serializeToString(svg)], { type: 'image/svg+xml' }); var url = URL.createObjectURL(blob); var image = new Image();
        image.onload = function () {
          try {
            var width = image.naturalWidth || 300, height = image.naturalHeight || 150; var targetHeight = Math.max(1, Math.round(height * 864 / width));
            if (targetHeight > 20000) throw new Error('SVG 图片过长，请分割');
            var canvas = document.createElement('canvas'); canvas.width = 864; canvas.height = targetHeight;
            var context = canvas.getContext('2d'); context.fillStyle = '#fff'; context.fillRect(0, 0, canvas.width, canvas.height); context.drawImage(image, 0, 0, canvas.width, canvas.height);
            call('commitRaster', item.id, canvas.toDataURL('image/png')); canvas.width = canvas.height = 0; resolve();
          } catch (error) { reject(error); }
          finally { URL.revokeObjectURL(url); }
        };
        image.onerror = function () { URL.revokeObjectURL(url); reject(new Error('SVG 无法渲染')); }; image.src = url;
      });
    });
  }

  window.onNativeEvent = function (name, payload) {
    if (name === 'ready') { state.mode = payload.mode || 'auto'; $('backendMode').value = state.mode; }
    if (name === 'device') {
      $('deviceStatus').textContent = payload.available ? '已发现打印接口' : '待连接 P3 Pro'; $('deviceDot').classList.toggle('available', payload.available); $('diagnostics').textContent = payload.details;
    }
    if (name === 'imported') {
      payload.items.forEach(function (item) {
        if (item.kind === 'special') { toast('正在转换 ' + item.name); convertSpecial(item).catch(function (error) { toast(error.message); }); }
        else if (item.kind === 'image') { item.selected = true; state.images.push(item); state.currentImage = item.id; redrawImages(); showScreen('images'); }
        else if (payload.target === 'text') editImportedText(item);
        else { documentLoaded(item); showScreen('documents'); }
      });
      if (payload.errors && payload.errors.length) toast(payload.errors.join('；'));
    }
    if (name === 'rasterReady') { payload.selected = true; state.images.push(payload); state.currentImage = payload.id; redrawImages(); showScreen('images'); }
    if (name === 'pdfPreview' && state.document && payload.id === state.document.id && payload.index === state.pdfPage) {
      $('documentPreview').innerHTML = ''; var image = document.createElement('img'); image.src = payload.url; image.className = 'pdf-image'; image.alt = '第 ' + (payload.index + 1) + ' 页'; $('documentPreview').appendChild(image);
    }
    if (name === 'progress') { $('progressTitle').textContent = payload.title || '正在打印'; $('progressMessage').textContent = payload.message; $('progress').value = payload.percent || 0; }
    if (name === 'finished') {
      $('progressTitle').textContent = payload.ok ? '打印任务已结束' : '打印已停止'; $('progressMessage').textContent = payload.message; $('progressMessage').classList.toggle('error-message', !payload.ok); $('progress').value = payload.ok ? 100 : 0;
      $('stopPrint').classList.add('hidden'); $('closeProgress').classList.remove('hidden');
    }
    if (name === 'error') toast(payload.message);
  };
  if (nativeApp) call('ready');
  else { $('deviceStatus').textContent = '浏览器预览'; $('diagnostics').textContent = '浏览器预览模式\n实际打印接口需要 P3 Pro 安卓系统'; }
  // Exposed only within the bundled offline page for integration testing.
  window.PrintApp = { state: state, quill: quill, showScreen: showScreen, editorHTML: editorHTML, showTextPreview: showTextPreview, setEditorMode: setEditorMode };
}());
