declare module 'onnxruntime-web' {
  export interface Tensor {
    readonly data: Float32Array | Int32Array | Uint8Array | BigInt64Array;
    readonly dims: readonly number[];
    readonly type: string;
  }

  export const Tensor: {
    new (type: 'float32', data: Float32Array, dims: readonly number[]): Tensor;
    new (type: string, data: ArrayBufferView, dims: readonly number[]): Tensor;
  };

  export interface InferenceSession {
    readonly inputNames: readonly string[];
    readonly outputNames: readonly string[];
    run(feeds: Record<string, Tensor>): Promise<Record<string, Tensor>>;
    release(): Promise<void>;
  }

  export const InferenceSession: {
    create(
      pathOrBuffer: string | Uint8Array | ArrayBuffer,
      options?: {
        executionProviders?: string[];
        graphOptimizationLevel?: 'disabled' | 'basic' | 'extended' | 'all';
      }
    ): Promise<InferenceSession>;
  };

  export const env: {
    wasm: {
      wasmPaths?: string | Record<string, string>;
      numThreads?: number;
      simd?: boolean;
      proxy?: boolean;
    };
    logLevel?: 'verbose' | 'info' | 'warning' | 'error' | 'fatal';
  };
}
