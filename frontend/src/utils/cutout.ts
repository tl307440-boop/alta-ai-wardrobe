import {
  disposeParsedClothesMap,
  extractGarmentBySemanticMask,
  parseClothesSemanticMap,
  type ParsedClothesMap,
} from './segformer';

export type CutoutEngineMode = 'auto' | 'segformer' | 'general';

export interface CutoutOutcome {
  cutoutFile: File | null;
  cutoutDataUrl: string | null;
  fallback: boolean;
  engineUsed: 'segformer' | 'general' | 'none';
}

/**
 * Browser-side general salient-object background removal using @imgly/background-removal (ONNX/WASM).
 * Best for flat-lay / hanging single garments or e-commerce photos without a human model.
 */
export async function tryBrowserCutout(file: File, timeoutMs = 18000): Promise<CutoutOutcome> {
  try {
    const cutoutPromise = (async (): Promise<CutoutOutcome> => {
      const mod = await import('@imgly/background-removal');
      const blob = await mod.removeBackground(file, {
        output: { format: 'image/png', quality: 0.9 },
      });
      const cutoutFile = new File([blob], 'cutout.png', { type: 'image/png' });
      const cutoutDataUrl = await blobToDataUrl(blob);
      return { cutoutFile, cutoutDataUrl, fallback: false, engineUsed: 'general' };
    })();

    const timeoutPromise = new Promise<CutoutOutcome>((resolve) =>
      setTimeout(
        () => resolve({ cutoutFile: null, cutoutDataUrl: null, fallback: true, engineUsed: 'none' }),
        timeoutMs
      )
    );

    return await Promise.race([cutoutPromise, timeoutPromise]);
  } catch {
    return { cutoutFile: null, cutoutDataUrl: null, fallback: true, engineUsed: 'none' };
  }
}

/**
 * Dual-engine single-item cutout:
 * - 'segformer': runs self-hosted SegFormer-B2-Clothes (strips skin/face/hair/limbs).
 * - 'general': runs @imgly/background-removal directly.
 * - 'auto': tries SegFormer when human-worn clothing is detected; falls back to @imgly for flat-lay items.
 */
export async function trySmartSingleCutout(
  file: File,
  mode: CutoutEngineMode = 'auto',
  category = 'top',
  name = '',
  timeoutMs = 24000
): Promise<CutoutOutcome> {
  if (mode === 'general') {
    return tryBrowserCutout(file, timeoutMs);
  }

  let parsed: ParsedClothesMap | null = null;
  try {
    parsed = await parseClothesSemanticMap(file, Math.max(timeoutMs, 35000));
    if (mode === 'segformer' || (mode === 'auto' && parsed.hasHumanWearing)) {
      const extracted = await extractGarmentBySemanticMask(parsed, {
        category,
        name,
        filePrefix: 'single-seg',
      });
      if (extracted) {
        return {
          cutoutFile: extracted.cutoutFile,
          cutoutDataUrl: extracted.cutoutDataUrl,
          fallback: false,
          engineUsed: 'segformer',
        };
      }
    }
  } catch {
    // Gracefully degrade to general foreground cutout below
  } finally {
    disposeParsedClothesMap(parsed);
  }

  return tryBrowserCutout(file, timeoutMs);
}

function blobToDataUrl(blob: Blob): Promise<string> {
  return new Promise((resolve, reject) => {
    const reader = new FileReader();
    reader.onload = () => resolve(String(reader.result));
    reader.onerror = reject;
    reader.readAsDataURL(blob);
  });
}
