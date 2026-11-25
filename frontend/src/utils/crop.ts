import type { BoundingBox } from '@/types';

export interface CropResult {
  file: File;
  dataUrl: string;
}

function loadImage(src: string): Promise<HTMLImageElement> {
  return new Promise((resolve, reject) => {
    const img = new Image();
    img.onload = () => resolve(img);
    img.onerror = () => reject(new Error('图片解码失败'));
    img.src = src;
  });
}

/**
 * Crops a normalized bounding box (fractions of the image) out of the source photo, with a little padding so the
 * garment edges are not clipped by an imprecise model box. Output is a JPEG capped at `maxEdge` px.
 */
export async function cropImageRegion(
  source: File,
  bbox: BoundingBox,
  options: { padding?: number; maxEdge?: number; name?: string } = {}
): Promise<CropResult> {
  const { padding = 0.06, maxEdge = 1024, name = 'crop' } = options;
  const objectUrl = URL.createObjectURL(source);
  try {
    const img = await loadImage(objectUrl);
    const W = img.naturalWidth;
    const H = img.naturalHeight;
    const padX = bbox.w * padding;
    const padY = bbox.h * padding;
    const x0 = Math.max(0, bbox.x - padX);
    const y0 = Math.max(0, bbox.y - padY);
    const x1 = Math.min(1, bbox.x + bbox.w + padX);
    const y1 = Math.min(1, bbox.y + bbox.h + padY);

    const sx = Math.round(x0 * W);
    const sy = Math.round(y0 * H);
    const sw = Math.max(1, Math.round((x1 - x0) * W));
    const sh = Math.max(1, Math.round((y1 - y0) * H));

    const scale = Math.min(1, maxEdge / Math.max(sw, sh));
    const canvas = document.createElement('canvas');
    canvas.width = Math.max(1, Math.round(sw * scale));
    canvas.height = Math.max(1, Math.round(sh * scale));
    const ctx = canvas.getContext('2d');
    if (!ctx) throw new Error('Canvas 不可用');
    ctx.fillStyle = '#FFFFFF';
    ctx.fillRect(0, 0, canvas.width, canvas.height);
    ctx.drawImage(img, sx, sy, sw, sh, 0, 0, canvas.width, canvas.height);

    const blob = await new Promise<Blob>((resolve, reject) =>
      canvas.toBlob((b) => (b ? resolve(b) : reject(new Error('裁剪导出失败'))), 'image/jpeg', 0.9)
    );
    const file = new File([blob], `${name}.jpg`, { type: 'image/jpeg' });
    const dataUrl = await new Promise<string>((resolve, reject) => {
      const reader = new FileReader();
      reader.onload = () => resolve(String(reader.result));
      reader.onerror = reject;
      reader.readAsDataURL(blob);
    });
    return { file, dataUrl };
  } finally {
    URL.revokeObjectURL(objectUrl);
  }
}
