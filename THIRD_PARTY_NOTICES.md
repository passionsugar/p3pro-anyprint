# Third-party components

The MIT license in this repository covers the project's original application code,
protocol implementation, JNI adapters, scripts and documentation. Dependencies
retain their respective licenses.

| Component | Version | License | Source |
| --- | --- | --- | --- |
| Quill | 1.3.7 | BSD-3-Clause | https://github.com/quilljs/quill |
| KaTeX | 0.16.22 | MIT | https://github.com/KaTeX/KaTeX |
| markdown-it | 14.1.0 | MIT | https://github.com/markdown-it/markdown-it |
| DOMPurify | 3.2.7 | MPL-2.0 OR Apache-2.0 | https://github.com/cure53/DOMPurify |
| UTIF.js | 3.1.0 | MIT | https://github.com/photopea/UTIF.js |
| pako, installed through UTIF.js | 1.0.11 | MIT AND Zlib | https://github.com/nodeca/pako |
| Playwright, development only | 1.56.1 | Apache-2.0 | https://github.com/microsoft/playwright |

`npm ci` installs the versions recorded in `package-lock.json`. `npm run vendor`
copies runtime assets and their license texts into the generated APK assets.

## Vendor native libraries

The serial backend and image converter use these libraries from P3ProPrint 3.4.0.

- `libserial_port.so`
- `libmcu_power.so`
- `libmmj_img_alg_core.so`

Their Java class names and signatures in this project preserve the required JNI
ABI. The original libraries and APKs are separate vendor dependencies with their
own applicable terms. The repository contains extraction instructions for a
locally supplied APK. The MIT license does not grant rights to those binaries.

The expected P3ProPrint APK SHA-256 is
`84CDD37F937C80D3A4E12361665719B5CB4313F9EA17FA4213F48F57193318C3`.

The protocol research names the original classes and APK hashes so that the
observations can be reproduced with locally obtained inputs.
