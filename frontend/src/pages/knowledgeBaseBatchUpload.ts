import type { VectorStatus } from '../api/knowledgebase';

export const MAX_BATCH_FILES = 10;
export const MAX_FILE_SIZE = 50 * 1024 * 1024;
export const MAX_CONCURRENT_UPLOADS = 2;
export const UPLOAD_START_INTERVAL_MS = 400;

const SUPPORTED_EXTENSIONS = new Set(['pdf', 'doc', 'docx', 'txt', 'md']);

export type BatchUploadStatus =
  | 'QUEUED'
  | 'UPLOADING'
  | 'PENDING'
  | 'PROCESSING'
  | 'COMPLETED'
  | 'UPLOAD_FAILED'
  | 'VECTOR_FAILED';

export interface BatchUploadItem {
  clientId: string;
  file: File;
  customName: string;
  status: BatchUploadStatus;
  knowledgeBaseId?: number;
  duplicate?: boolean;
  error?: string;
}

export function getFileIdentity(file: Pick<File, 'name' | 'size' | 'lastModified'>): string {
  return `${file.name}:${file.size}:${file.lastModified}`;
}

export function validateKnowledgeBaseFile(file: Pick<File, 'name' | 'size'>): string | null {
  if (file.size <= 0) {
    return '文件为空';
  }
  if (file.size > MAX_FILE_SIZE) {
    return '文件超过 50MB';
  }

  const extension = file.name.includes('.')
    ? file.name.slice(file.name.lastIndexOf('.') + 1).toLowerCase()
    : '';
  if (!SUPPORTED_EXTENSIONS.has(extension)) {
    return '仅支持 PDF、DOCX、DOC、TXT、MD';
  }
  return null;
}

export function toBatchUploadStatus(status: VectorStatus): BatchUploadStatus {
  return status === 'FAILED' ? 'VECTOR_FAILED' : status;
}

export function isVectorizationActive(status: BatchUploadStatus): boolean {
  return status === 'PENDING' || status === 'PROCESSING';
}

export async function runWithConcurrency<T>(
  values: readonly T[],
  limit: number,
  worker: (value: T, index: number) => Promise<void>,
): Promise<void> {
  if (limit < 1) {
    throw new Error('并发数必须大于 0');
  }

  let nextIndex = 0;
  const workers = Array.from(
    { length: Math.min(limit, values.length) },
    async () => {
      while (nextIndex < values.length) {
        const currentIndex = nextIndex;
        nextIndex += 1;
        await worker(values[currentIndex]!, currentIndex);
      }
    },
  );

  await Promise.all(workers);
}
