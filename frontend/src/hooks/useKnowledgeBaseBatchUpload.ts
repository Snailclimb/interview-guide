import { useCallback, useEffect, useMemo, useRef, useState } from 'react';

import { knowledgeBaseApi } from '../api/knowledgebase';
import {
  getFileIdentity,
  isVectorizationActive,
  MAX_BATCH_FILES,
  MAX_CONCURRENT_UPLOADS,
  runWithConcurrency,
  toBatchUploadStatus,
  UPLOAD_START_INTERVAL_MS,
  validateKnowledgeBaseFile,
  type BatchUploadItem,
} from '../pages/knowledgeBaseBatchUpload';

export function useKnowledgeBaseBatchUpload() {
  const uploadingRef = useRef(false);
  const [items, setItems] = useState<BatchUploadItem[]>([]);
  const [batchUploading, setBatchUploading] = useState(false);
  const [selectionNotice, setSelectionNotice] = useState('');
  const [pollError, setPollError] = useState('');
  const [revectorizingId, setRevectorizingId] = useState<number | null>(null);

  const hasUploading = batchUploading || items.some(item => item.status === 'UPLOADING');
  const uploadCandidates = items.filter(
    item => item.status === 'QUEUED' || item.status === 'UPLOAD_FAILED',
  );
  const trackedIdsKey = useMemo(
    () => items
      .filter(item => item.knowledgeBaseId && isVectorizationActive(item.status))
      .map(item => item.knowledgeBaseId)
      .sort((a, b) => (a ?? 0) - (b ?? 0))
      .join(','),
    [items],
  );

  const updateItem = useCallback(
    (clientId: string, updater: (item: BatchUploadItem) => BatchUploadItem) => {
      setItems(current => current.map(item => (
        item.clientId === clientId ? updater(item) : item
      )));
    },
    [],
  );

  const addFiles = useCallback((fileList: FileList | File[]) => {
    if (hasUploading) return;

    const incoming = Array.from(fileList);
    const existingIdentities = new Set(items.map(item => getFileIdentity(item.file)));
    const accepted: BatchUploadItem[] = [];
    const rejected: string[] = [];
    let remainingSlots = Math.max(0, MAX_BATCH_FILES - items.length);

    incoming.forEach((file, index) => {
      const identity = getFileIdentity(file);
      const validationError = validateKnowledgeBaseFile(file);

      if (existingIdentities.has(identity)) {
        rejected.push(`${file.name}（已在列表中）`);
        return;
      }
      if (validationError) {
        rejected.push(`${file.name}（${validationError}）`);
        return;
      }
      if (remainingSlots === 0) {
        rejected.push(`${file.name}（单批最多 ${MAX_BATCH_FILES} 个文件）`);
        return;
      }

      existingIdentities.add(identity);
      remainingSlots -= 1;
      accepted.push({
        clientId: `${Date.now()}-${index}-${identity}`,
        file,
        customName: '',
        status: 'QUEUED',
      });
    });

    if (accepted.length > 0) {
      setItems(current => [...current, ...accepted]);
    }
    setSelectionNotice(rejected.length > 0 ? rejected.join('；') : '');
  }, [hasUploading, items]);

  const uploadItem = useCallback(async (item: BatchUploadItem) => {
    updateItem(item.clientId, current => ({
      ...current,
      status: 'UPLOADING',
      error: undefined,
    }));

    try {
      const result = await knowledgeBaseApi.uploadKnowledgeBase(
        item.file,
        item.customName.trim() || undefined,
      );
      updateItem(item.clientId, current => ({
        ...current,
        knowledgeBaseId: result.knowledgeBase.id,
        duplicate: result.duplicate,
        status: 'PENDING',
        error: undefined,
      }));
    } catch (error: unknown) {
      updateItem(item.clientId, current => ({
        ...current,
        status: 'UPLOAD_FAILED',
        error: error instanceof Error ? error.message : '上传失败，请重试',
      }));
    }
  }, [updateItem]);

  const uploadAll = async () => {
    if (uploadCandidates.length === 0 || uploadingRef.current) return;

    uploadingRef.current = true;
    setBatchUploading(true);
    setSelectionNotice('');
    let nextStartAt = 0;

    try {
      await runWithConcurrency(
        uploadCandidates,
        MAX_CONCURRENT_UPLOADS,
        async item => {
          const scheduledAt = Math.max(Date.now(), nextStartAt);
          nextStartAt = scheduledAt + UPLOAD_START_INTERVAL_MS;
          const waitTime = scheduledAt - Date.now();
          if (waitTime > 0) {
            await new Promise(resolve => window.setTimeout(resolve, waitTime));
          }
          await uploadItem(item);
        },
      );
    } finally {
      uploadingRef.current = false;
      setBatchUploading(false);
    }
  };

  const retryUpload = async (item: BatchUploadItem) => {
    if (uploadingRef.current) return;
    uploadingRef.current = true;
    try {
      await uploadItem(item);
    } finally {
      uploadingRef.current = false;
    }
  };

  const revectorize = async (item: BatchUploadItem) => {
    if (!item.knowledgeBaseId || revectorizingId !== null) return;

    setRevectorizingId(item.knowledgeBaseId);
    try {
      await knowledgeBaseApi.revectorize(item.knowledgeBaseId);
      updateItem(item.clientId, current => ({
        ...current,
        status: 'PENDING',
        error: undefined,
      }));
    } catch (error: unknown) {
      updateItem(item.clientId, current => ({
        ...current,
        error: error instanceof Error ? error.message : '重新向量化失败，请重试',
      }));
    } finally {
      setRevectorizingId(null);
    }
  };

  useEffect(() => {
    if (!trackedIdsKey) {
      setPollError('');
      return undefined;
    }

    let cancelled = false;
    const refreshStatuses = async () => {
      try {
        const knowledgeBases = await knowledgeBaseApi.getAllKnowledgeBases();
        if (cancelled) return;

        const statusById = new Map(knowledgeBases.map(kb => [kb.id, kb]));
        setItems(current => current.map(item => {
          if (!item.knowledgeBaseId || !isVectorizationActive(item.status)) {
            return item;
          }

          const knowledgeBase = statusById.get(item.knowledgeBaseId);
          if (!knowledgeBase) return item;

          return {
            ...item,
            status: toBatchUploadStatus(knowledgeBase.vectorStatus),
            error: knowledgeBase.vectorStatus === 'FAILED'
              ? knowledgeBase.vectorError || '向量化失败，请重试'
              : undefined,
          };
        }));
        setPollError('');
      } catch {
        if (!cancelled) {
          setPollError('暂时无法刷新向量化状态，将自动重试');
        }
      }
    };

    void refreshStatuses();
    const timer = window.setInterval(refreshStatuses, 5000);
    return () => {
      cancelled = true;
      window.clearInterval(timer);
    };
  }, [trackedIdsKey]);

  const clearItems = () => {
    setItems([]);
    setSelectionNotice('');
    setPollError('');
  };

  return {
    items,
    hasUploading,
    selectionNotice,
    pollError,
    revectorizingId,
    uploadCandidatesCount: uploadCandidates.length,
    completedCount: items.filter(item => item.status === 'COMPLETED').length,
    failedCount: items.filter(
      item => item.status === 'UPLOAD_FAILED' || item.status === 'VECTOR_FAILED',
    ).length,
    addFiles,
    clearItems,
    removeItem: (clientId: string) => setItems(current => (
      current.filter(item => item.clientId !== clientId)
    )),
    updateCustomName: (clientId: string, customName: string) => updateItem(
      clientId,
      item => ({ ...item, customName }),
    ),
    uploadAll,
    retryUpload,
    revectorize,
  };
}
