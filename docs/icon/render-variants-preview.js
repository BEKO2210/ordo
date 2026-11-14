const sharp = require('sharp');
const path = require('path');

const dir = __dirname;
const variants = ['a', 'b', 'c'];
const width = 1540;
const height = 950;
const panels = [
  { y: 50, fill: '#F8FAFC', stroke: '#CBD5E1', text: '#0F172A', tag: 'HELL' },
  { y: 500, fill: '#0F172A', stroke: '#334155', text: '#F8FAFC', tag: 'DUNKEL' },
];

function mask(size, shape) {
  const radius = shape === 'round' ? size / 2 : size * 0.23;
  return Buffer.from(`<svg width="${size}" height="${size}"><rect width="${size}" height="${size}" rx="${radius}" fill="#fff"/></svg>`);
}

async function icon(letter, size, shape) {
  const rendered = await sharp(path.join(dir, `variant-${letter}.svg`))
    .resize(size, size)
    .png()
    .toBuffer();
  return sharp(rendered)
    .composite([{ input: mask(size, shape), blend: 'dest-in' }])
    .png()
    .toBuffer();
}

function backgroundSvg() {
  const panelWidth = 460;
  const xs = [40, 540, 1040];
  let markup = `<svg xmlns="http://www.w3.org/2000/svg" width="${width}" height="${height}">`;
  markup += '<rect width="100%" height="100%" fill="#E2E8F0"/>';
  for (const panel of panels) {
    markup += `<text x="40" y="${panel.y - 16}" fill="#475569" font-family="Inter,Arial,sans-serif" font-size="18" font-weight="700" letter-spacing="2">${panel.tag}</text>`;
    xs.forEach((x, index) => {
      markup += `<rect x="${x}" y="${panel.y}" width="${panelWidth}" height="400" rx="28" fill="${panel.fill}" stroke="${panel.stroke}" stroke-width="2"/>`;
      markup += `<text x="${x + 122}" y="${panel.y + 315}" text-anchor="middle" fill="${panel.text}" opacity=".65" font-family="Inter,Arial,sans-serif" font-size="15" font-weight="700" letter-spacing="1.5">RUND</text>`;
      markup += `<text x="${x + 338}" y="${panel.y + 315}" text-anchor="middle" fill="${panel.text}" opacity=".65" font-family="Inter,Arial,sans-serif" font-size="15" font-weight="700" letter-spacing="1.5">ABGERUNDET</text>`;
      markup += `<text x="${x + 122}" y="${panel.y + 337}" text-anchor="middle" fill="${panel.text}" opacity=".45" font-family="Inter,Arial,sans-serif" font-size="12" font-weight="700" letter-spacing="1">192 PX + 48 PX</text>`;
      markup += `<text x="${x + 338}" y="${panel.y + 337}" text-anchor="middle" fill="${panel.text}" opacity=".45" font-family="Inter,Arial,sans-serif" font-size="12" font-weight="700" letter-spacing="1">192 PX + 48 PX</text>`;
      markup += `<text x="${x + 230}" y="${panel.y + 382}" text-anchor="middle" fill="${panel.text}" font-family="Inter,Arial,sans-serif" font-size="42" font-weight="800">${String.fromCharCode(65 + index)}</text>`;
    });
  }
  return Buffer.from(markup + '</svg>');
}

async function main() {
  const composites = [];
  const xs = [40, 540, 1040];
  for (const panel of panels) {
    for (let i = 0; i < variants.length; i += 1) {
      const x = xs[i];
      composites.push({ input: await icon(variants[i], 192, 'round'), left: x + 14, top: panel.y + 28 });
      composites.push({ input: await icon(variants[i], 192, 'rounded'), left: x + 254, top: panel.y + 28 });
      composites.push({ input: await icon(variants[i], 48, 'round'), left: x + 98, top: panel.y + 242 });
      composites.push({ input: await icon(variants[i], 48, 'rounded'), left: x + 314, top: panel.y + 242 });
    }
  }
  await sharp(backgroundSvg())
    .composite(composites)
    .png({ compressionLevel: 9 })
    .toFile(path.join(dir, 'variants-preview.png'));
}

main().catch((error) => {
  console.error(error);
  process.exitCode = 1;
});
