/**
 * Browser-side AI background removal using @imgly/background-removal (ONNX/WASM).
 * Lazy-loaded on demand and protected by a strict timeout so failures gracefully fall back
 * to the original image without blocking wardrobe ingestion.
 */
export async function tryBrowserCutout(file: File, timeoutMs = 18000): Promise<{
  cutoutFile: File | null;
  cutoutDataUrl: string | null;
  fallback: boolean;
}> {
  try {
    const cutoutPromise = (async () => {
      const mod = await import('@imgly/background-removal');
      const blob = await mod.removeBackground(file, {
        output: { format: 'image/png', quality: 0.9 },
      });
      const cutoutFile = new File([blob], 'cutout.png', { type: 'image/png' });
      const cutoutDataUrl = await blobToDataUrl(blob);
      return { cutoutFile, cutoutDataUrl, fallback: false };
    })();

    const timeoutPromise = new Promise<{
      cutoutFile: null;
      cutoutDataUrl: null;
      fallback: true;
    }>((resolve) =>
      setTimeout(() => resolve({ cutoutFile: null, cutoutDataUrl: null, fallback: true }), timeoutMs)
    );

    return await Promise.race([cutoutPromise, timeoutPromise]);
  } catch {
    return { cutoutFile: null, cutoutDataUrl: null, fallback: true };
  }
}

function blobToDataUrl(blob: Blob): Promise<string> {
  return new Promise((resolve, reject) => {
    const reader = new FileReader();
    reader.onload = () => resolve(String(reader.result));
    reader.onerror = reject;
    reader.readAsDataURL(blob);
  });
}
