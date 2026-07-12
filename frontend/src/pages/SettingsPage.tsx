import { useState, useEffect, useCallback, useMemo } from 'react';
import type { ReactNode } from 'react';
import { motion, AnimatePresence } from 'framer-motion';
import {
  Settings, Plus, Trash2, Plug, CheckCircle, XCircle,
  Loader2, Eye, EyeOff, RefreshCw, Server, Edit2, Mic, Volume2, ChevronDown, Database,
} from 'lucide-react';
import { llmProviderApi } from '../api/llmProvider';
import ConfirmDialog from '../components/ConfirmDialog';
import type {
  ProviderItem, CreateProviderRequest, UpdateProviderRequest,
  ProviderTestResult, AsrConfig, TtsConfig, AsrConfigRequest, TtsConfigRequest,
} from '../types/llmProvider';

// Provider 预设：已知 Provider 的 Base URL、推荐模型和向量模型
const PROVIDER_PRESETS: Record<string, {
  baseUrl: string;
  models: { value: string; label: string }[];
  embeddingModels?: { value: string; label: string }[];
  embeddingDimensions?: number;
  supportsEmbedding: boolean;
}> = {
  siliconflow: {
    baseUrl: 'https://api.siliconflow.cn/v1',
    models: [
      { value: 'Pro/zai-org/GLM-4.7', label: 'GLM-4.7 Pro - SiliconFlow' },
      { value: 'Pro/zai-org/GLM-5', label: 'GLM-5 Pro - SiliconFlow' },
      { value: 'deepseek-ai/DeepSeek-V3.2', label: 'DeepSeek V3.2 - SiliconFlow' },
      { value: 'Pro/deepseek-ai/DeepSeek-V3.2', label: 'DeepSeek V3.2 Pro - SiliconFlow' },
      { value: 'Qwen/Qwen3-32B', label: 'Qwen3 32B - SiliconFlow' },
    ],
    embeddingModels: [
      { value: 'Qwen/Qwen3-Embedding-0.6B', label: 'Qwen3 Embedding 0.6B - 1024维' },
      { value: 'Qwen/Qwen3-Embedding-4B', label: 'Qwen3 Embedding 4B - 1024维' },
      { value: 'Qwen/Qwen3-Embedding-8B', label: 'Qwen3 Embedding 8B - 1024维' },
    ],
    embeddingDimensions: 1024,
    supportsEmbedding: true,
  },
  dashscope: {
    baseUrl: 'https://dashscope.aliyuncs.com/compatible-mode/v1',
    models: [
      { value: 'qwen3.6-flash', label: 'Qwen3.6 Flash — 最新旗舰' },
      { value: 'qwen3.5-plus', label: 'Qwen3.5 Plus — 高性能' },
      { value: 'qwen3.5-flash', label: 'Qwen3.5 Flash — 性价比' },
      { value: 'qwen3-max', label: 'Qwen3 Max — 旗舰' },
      { value: 'qwen-max', label: 'Qwen Max — 稳定版' },
      { value: 'qwen-plus', label: 'Qwen Plus — 均衡' },
      { value: 'qwen-flash', label: 'Qwen Flash — 经济' },
      { value: 'qwq-32b', label: 'QwQ-32B — 推理专用' },
    ],
    embeddingModels: [
      { value: 'text-embedding-v3', label: 'text-embedding-v3 — 推荐' },
    ],
    embeddingDimensions: 1024,
    supportsEmbedding: true,
  },
  deepseek: {
    baseUrl: 'https://api.deepseek.com',
    models: [
      { value: 'deepseek-v4-flash', label: 'DeepSeek V4 Flash — 最新·快速' },
      { value: 'deepseek-v4-pro', label: 'DeepSeek V4 Pro — 最强推理' },
      { value: 'deepseek-chat', label: 'DeepSeek V3.2 — 旧版对话（即将弃用）' },
      { value: 'deepseek-reasoner', label: 'DeepSeek R1 — 旧版推理（即将弃用）' },
    ],
    supportsEmbedding: false,
  },
  glm: {
    baseUrl: 'https://open.bigmodel.cn/api/coding/paas/v4',
    models: [
      { value: 'glm-5.1', label: 'GLM-5.1 — 最新旗舰' },
      { value: 'glm-5', label: 'GLM-5 — 旗舰' },
      { value: 'glm-4.7', label: 'GLM-4.7 — Coding 强' },
      { value: 'glm-4.7-flash', label: 'GLM-4.7 Flash — 免费' },
      { value: 'glm-4.6', label: 'GLM-4.6 — 200K 上下文' },
      { value: 'glm-4-plus', label: 'GLM-4 Plus — 高性能' },
      { value: 'glm-4-air-250414', label: 'GLM-4 Air — 高性价比' },
      { value: 'glm-4-flash-250414', label: 'GLM-4 Flash — 免费' },
    ],
    embeddingModels: [
      { value: 'embedding-3', label: 'embedding-3 — 推荐' },
    ],
    embeddingDimensions: 1024,
    supportsEmbedding: true,
  },
  kimi: {
    baseUrl: 'https://api.moonshot.cn/v1',
    models: [
      { value: 'kimi-k2.6', label: 'Kimi K2.6 — 最新最智能' },
      { value: 'kimi-k2.5', label: 'Kimi K2.5 — 多模态' },
      { value: 'kimi-k2', label: 'Kimi K2 — MoE 基座' },
      { value: 'kimi-k2-thinking', label: 'Kimi K2 Thinking — 深度推理' },
      { value: 'kimi-latest', label: 'kimi-latest — 自动最新' },
    ],
    supportsEmbedding: false,
  },
};

type ConfigRowProps = {
  label: string;
  value: ReactNode;
  title?: string;
  monospace?: boolean;
  emphasis?: boolean;
};

type StatusBadgeProps = {
  icon: ReactNode;
  children: ReactNode;
};

const CARD_CLASS = `flex h-full min-h-[330px] flex-col rounded-xl border border-slate-200
  bg-white p-5 shadow-sm transition-shadow hover:shadow-md dark:border-slate-700
  dark:bg-slate-800`;

const ICON_WRAP_CLASS = `flex h-9 w-9 flex-shrink-0 items-center justify-center rounded-lg
  bg-primary-50 text-primary-600 dark:bg-primary-900/30 dark:text-primary-300`;

const DETAILS_CLASS = `mb-4 flex-1 space-y-1 rounded-lg border border-slate-100 bg-slate-50/70
  p-3 dark:border-slate-700/80 dark:bg-slate-900/30`;

const ACTION_BAR_CLASS = `mt-auto flex min-h-12 flex-wrap items-center gap-2 border-t
  border-slate-100 pt-3 dark:border-slate-700`;

const ACTION_BUTTON_CLASS = `inline-flex h-8 items-center gap-1.5 rounded-lg px-3 text-xs
  font-medium transition-colors disabled:cursor-not-allowed disabled:opacity-50`;

function StatusBadge({ icon, children }: StatusBadgeProps) {
  return (
    <span className="inline-flex h-6 items-center gap-1.5 rounded-full bg-primary-50 px-2.5 text-xs font-semibold text-primary-700 dark:bg-primary-900/30 dark:text-primary-300">
      {icon}
      {children}
    </span>
  );
}

function ConfigRow({ label, value, title, monospace = false, emphasis = false }: ConfigRowProps) {
  return (
    <div
      className={`grid grid-cols-[108px_minmax(0,1fr)] items-start gap-3 rounded-md px-2 py-2 text-xs ${
        emphasis ? 'bg-white shadow-sm ring-1 ring-slate-100 dark:bg-slate-800/80 dark:ring-slate-700' : ''
      }`}
    >
      <dt className="whitespace-nowrap text-slate-500 dark:text-slate-400">{label}</dt>
      <dd
        className={`min-w-0 truncate text-right font-medium text-slate-700 dark:text-slate-200 ${
          monospace ? 'font-mono' : ''
        }`}
        title={title}
      >
        {value}
      </dd>
    </div>
  );
}

export default function SettingsPage() {
  const [providers, setProviders] = useState<ProviderItem[]>([]);
  const [defaultProviderId, setDefaultProviderId] = useState('');
  const [defaultEmbeddingProviderId, setDefaultEmbeddingProviderId] = useState('');
  const [loading, setLoading] = useState(true);

  // Modal state
  const [showModal, setShowModal] = useState(false);
  const [editingProvider, setEditingProvider] = useState<ProviderItem | null>(null);
  const [saving, setSaving] = useState(false);

  // Form fields
  const [formId, setFormId] = useState('');
  const [formBaseUrl, setFormBaseUrl] = useState('');
  const [formApiKey, setFormApiKey] = useState('');
  const [formModel, setFormModel] = useState('');
  const [formEmbeddingModel, setFormEmbeddingModel] = useState('');
  const [formEmbeddingDimensions, setFormEmbeddingDimensions] = useState('1024');
  const [formSupportsEmbedding, setFormSupportsEmbedding] = useState(false);
  const [formTemperature, setFormTemperature] = useState('');
  const [showApiKey, setShowApiKey] = useState(false);
  const [showModelDropdown, setShowModelDropdown] = useState(false);
  const [showEmbeddingDropdown, setShowEmbeddingDropdown] = useState(false);
  const [discoveredModels, setDiscoveredModels] = useState<string[]>([]);
  const [discoveringModels, setDiscoveringModels] = useState(false);
  const [modelDiscoveryError, setModelDiscoveryError] = useState<string | null>(null);

  // 当前表单 Provider ID 匹配的预设
  const currentPreset = useMemo(
    () => PROVIDER_PRESETS[formId.toLowerCase()],
    [formId],
  );

  const discoveredModelOptions = useMemo(
    () => discoveredModels.map(model => ({ value: model, label: model })),
    [discoveredModels],
  );
  const availableModelOptions = discoveredModelOptions.length > 0
    ? discoveredModelOptions
    : (currentPreset?.models ?? []);
  const availableEmbeddingModelOptions = discoveredModelOptions.length > 0
    ? discoveredModelOptions
    : (currentPreset?.embeddingModels ?? []);

  // Test state
  const [testingId, setTestingId] = useState<string | null>(null);
  const [testResults, setTestResults] = useState<Record<string, ProviderTestResult>>({});

  // Delete confirmation
  const [deleteConfirmId, setDeleteConfirmId] = useState<string | null>(null);
  const [deleting, setDeleting] = useState(false);
  const [pendingDefaultProviderId, setPendingDefaultProviderId] = useState<string | null>(null);
  const [pendingDefaultEmbeddingProviderId, setPendingDefaultEmbeddingProviderId] = useState<string | null>(null);
  const [settingDefault, setSettingDefault] = useState(false);
  const [settingEmbeddingDefault, setSettingEmbeddingDefault] = useState(false);

  const pendingEmbeddingProvider = useMemo(
    () => providers.find(provider => provider.id === pendingDefaultEmbeddingProviderId) ?? null,
    [pendingDefaultEmbeddingProviderId, providers],
  );

  // Voice config state
  const [asrConfig, setAsrConfig] = useState<AsrConfig | null>(null);
  const [ttsConfig, setTtsConfig] = useState<TtsConfig | null>(null);
  const [showVoiceModal, setShowVoiceModal] = useState<'asr' | 'tts' | null>(null);
  const [testingAsr, setTestingAsr] = useState(false);
  const [asrTestResult, setAsrTestResult] = useState<ProviderTestResult | null>(null);
  const [voiceSaving, setVoiceSaving] = useState(false);

  // ASR/TTS form fields
  const [asrForm, setAsrForm] = useState<AsrConfigRequest>({});
  const [ttsForm, setTtsForm] = useState<TtsConfigRequest>({});

  // Toast notification
  const [toast, setToast] = useState<{ message: string; type: 'success' | 'error' } | null>(null);

  const showToast = useCallback((message: string, type: 'success' | 'error' = 'success') => {
    setToast({ message, type });
    setTimeout(() => setToast(null), 3000);
  }, []);

  const isGlobalDefaultProvider = useCallback((providerId: string) => (
    defaultProviderId === providerId
  ), [defaultProviderId]);

  const isDefaultEmbeddingProvider = useCallback((providerId: string) => (
    defaultEmbeddingProviderId === providerId
  ), [defaultEmbeddingProviderId]);

  const loadData = useCallback(async () => {
    try {
      const [providerList, defaultProvider, asr, tts] = await Promise.all([
        llmProviderApi.list(),
        llmProviderApi.getDefaultProvider(),
        llmProviderApi.getAsrConfig(),
        llmProviderApi.getTtsConfig(),
      ]);
      setProviders(providerList);
      setDefaultProviderId(defaultProvider.defaultProvider);
      setDefaultEmbeddingProviderId(defaultProvider.defaultEmbeddingProvider);
      setAsrConfig(asr);
      setTtsConfig(tts);
    } catch (err) {
      console.error('Failed to load settings:', err);
      showToast('加载数据失败', 'error');
    } finally {
      setLoading(false);
    }
  }, [showToast]);

  useEffect(() => {
    loadData();
  }, [loadData]);

  // --- Modal helpers ---
  const openCreateModal = () => {
    setEditingProvider(null);
    setFormId('');
    setFormBaseUrl('');
    setFormApiKey('');
    setFormModel('');
    setFormEmbeddingModel('');
    setFormEmbeddingDimensions('1024');
    setFormSupportsEmbedding(false);
    setShowApiKey(false);
    setDiscoveredModels([]);
    setModelDiscoveryError(null);
    setShowModal(true);
  };

  const openEditModal = (provider: ProviderItem) => {
    setEditingProvider(provider);
    setFormId(provider.id);
    setFormBaseUrl(provider.baseUrl);
    setFormApiKey('');
    setFormModel(provider.model);
    setFormEmbeddingModel(provider.embeddingModel || '');
    setFormEmbeddingDimensions(provider.embeddingDimensions != null ? String(provider.embeddingDimensions) : '1024');
    setFormSupportsEmbedding(provider.supportsEmbedding);
    setFormTemperature(provider.temperature != null ? String(provider.temperature) : '');
    setShowApiKey(false);
    setDiscoveredModels([]);
    setModelDiscoveryError(null);
    setShowModal(true);
  };

  const closeModal = () => {
    setShowModal(false);
    setEditingProvider(null);
    setDiscoveredModels([]);
    setModelDiscoveryError(null);
  };

  const handleDiscoverModels = async () => {
    const baseUrl = formBaseUrl.trim();
    const apiKey = formApiKey.trim();
    if (!baseUrl || !apiKey) return;

    setDiscoveringModels(true);
    setModelDiscoveryError(null);
    try {
      const response = await llmProviderApi.discoverModels({ baseUrl, apiKey });
      setDiscoveredModels(response.models);
    } catch (err) {
      setDiscoveredModels([]);
      setModelDiscoveryError(err instanceof Error ? err.message : '获取模型列表失败，请稍后重试');
    } finally {
      setDiscoveringModels(false);
    }
  };

  // --- CRUD handlers ---
  const handleCreate = async () => {
    if (!formId.trim() || !formBaseUrl.trim() || !formApiKey.trim() || !formModel.trim()) {
      showToast('请填写必填字段', 'error');
      return;
    }
    if (formSupportsEmbedding && !formEmbeddingModel.trim()) {
      showToast('支持向量化时需要填写向量模型，例如 GLM 填 embedding-3', 'error');
      return;
    }
    const embeddingDimensions = parseInt(formEmbeddingDimensions.trim(), 10);
    if (formSupportsEmbedding && (!Number.isFinite(embeddingDimensions) || embeddingDimensions <= 0)) {
      showToast('向量维度必须为正整数，当前 pgvector 表为 1024 维', 'error');
      return;
    }
    setSaving(true);
    try {
      const data: CreateProviderRequest = {
        id: formId.trim(),
        baseUrl: formBaseUrl.trim(),
        apiKey: formApiKey.trim(),
        model: formModel.trim(),
        supportsEmbedding: formSupportsEmbedding,
      };
      if (formEmbeddingModel.trim()) {
        data.embeddingModel = formEmbeddingModel.trim();
        data.embeddingDimensions = embeddingDimensions;
      }
      if (formTemperature.trim()) {
        const temp = parseFloat(formTemperature.trim());
        if (!isNaN(temp)) data.temperature = temp;
      }
      await llmProviderApi.create(data);
      showToast('Provider 创建成功');
      closeModal();
      await loadData();
    } catch (err) {
      console.error('Failed to create provider:', err);
      showToast(err instanceof Error ? err.message : '创建失败', 'error');
    } finally {
      setSaving(false);
    }
  };

  const handleUpdate = async () => {
    if (!editingProvider) return;
    if (!formBaseUrl.trim() || !formModel.trim()) {
      showToast('请填写必填字段', 'error');
      return;
    }
    if (formSupportsEmbedding && !formEmbeddingModel.trim()) {
      showToast('支持向量化时需要填写向量模型，例如 GLM 填 embedding-3', 'error');
      return;
    }
    const embeddingDimensions = parseInt(formEmbeddingDimensions.trim(), 10);
    if (formSupportsEmbedding && (!Number.isFinite(embeddingDimensions) || embeddingDimensions <= 0)) {
      showToast('向量维度必须为正整数，当前 pgvector 表为 1024 维', 'error');
      return;
    }
    setSaving(true);
    try {
      const data: UpdateProviderRequest = {
        baseUrl: formBaseUrl.trim(),
        model: formModel.trim(),
        embeddingModel: formEmbeddingModel.trim(),
        supportsEmbedding: formSupportsEmbedding,
      };
      if (formSupportsEmbedding) {
        data.embeddingDimensions = embeddingDimensions;
      }
      if (formApiKey.trim()) {
        data.apiKey = formApiKey.trim();
      }
      if (formTemperature.trim()) {
        const temp = parseFloat(formTemperature.trim());
        if (!isNaN(temp)) data.temperature = temp;
      }
      await llmProviderApi.update(editingProvider.id, data);
      showToast('Provider 更新成功');
      closeModal();
      await loadData();
    } catch (err) {
      console.error('Failed to update provider:', err);
      showToast(err instanceof Error ? err.message : '更新失败', 'error');
    } finally {
      setSaving(false);
    }
  };

  const handleDelete = async () => {
    if (!deleteConfirmId) return;
    setDeleting(true);
    try {
      await llmProviderApi.delete(deleteConfirmId);
      showToast('Provider 已删除');
      setDeleteConfirmId(null);
      await loadData();
    } catch (err) {
      console.error('Failed to delete provider:', err);
      showToast(err instanceof Error ? err.message : '删除失败', 'error');
    } finally {
      setDeleting(false);
    }
  };

  const handleTest = async (id: string) => {
    setTestingId(id);
    setTestResults(prev => {
      const next = { ...prev };
      delete next[id];
      return next;
    });
    try {
      const result = await llmProviderApi.test(id);
      setTestResults(prev => ({ ...prev, [id]: result }));
    } catch (err) {
      console.error('Test failed:', err);
      setTestResults(prev => ({
        ...prev,
        [id]: {
          success: false,
          message: err instanceof Error ? err.message : '连接测试失败',
          model: '',
        },
      }));
    } finally {
      setTestingId(null);
    }
  };

  const handleSetDefault = async (providerId: string) => {
    setPendingDefaultProviderId(providerId);
  };

  const handleConfirmSetDefault = async () => {
    if (!pendingDefaultProviderId) {
      return;
    }
    setSettingDefault(true);
    try {
      await llmProviderApi.updateDefaultProvider({
        defaultProvider: pendingDefaultProviderId,
        defaultEmbeddingProvider: defaultEmbeddingProviderId,
      });
      showToast(`已将 "${pendingDefaultProviderId}" 设为默认聊天服务`);
      setPendingDefaultProviderId(null);
      await loadData();
    } catch (err) {
      console.error('Failed to set default:', err);
      showToast(err instanceof Error ? err.message : '设置默认 Provider 失败', 'error');
    } finally {
      setSettingDefault(false);
    }
  };

  const handleSetEmbeddingDefault = async (provider: ProviderItem) => {
    if (!provider.supportsEmbedding || !provider.embeddingModel) {
      showToast('该 Provider 不支持 Embedding，不能作为知识库向量服务', 'error');
      return;
    }
    setPendingDefaultEmbeddingProviderId(provider.id);
  };

  const handleConfirmSetEmbeddingDefault = async () => {
    if (!pendingDefaultEmbeddingProviderId) {
      return;
    }
    setSettingEmbeddingDefault(true);
    try {
      await llmProviderApi.updateDefaultEmbeddingProvider({
        defaultProvider: defaultProviderId,
        defaultEmbeddingProvider: pendingDefaultEmbeddingProviderId,
      });
      showToast(`已将 "${pendingDefaultEmbeddingProviderId}" 的 ${pendingEmbeddingProvider?.embeddingModel ?? '向量模型'} (${pendingEmbeddingProvider?.embeddingDimensions ?? 1024}维) 设为默认向量服务`);
      setPendingDefaultEmbeddingProviderId(null);
      await loadData();
    } catch (err) {
      console.error('Failed to set embedding default:', err);
      showToast(err instanceof Error ? err.message : '设置默认向量 Provider 失败', 'error');
    } finally {
      setSettingEmbeddingDefault(false);
    }
  };

  const handleSaveModal = () => {
    if (editingProvider) {
      handleUpdate();
    } else {
      handleCreate();
    }
  };

  // --- Voice config handlers ---
  const openAsrModal = () => {
    if (!asrConfig) return;
    setAsrForm({
      url: asrConfig.url,
      model: asrConfig.model,
      language: asrConfig.language,
      format: asrConfig.format,
      sampleRate: asrConfig.sampleRate,
      enableTurnDetection: asrConfig.enableTurnDetection,
      turnDetectionType: asrConfig.turnDetectionType,
      turnDetectionThreshold: asrConfig.turnDetectionThreshold,
      turnDetectionSilenceDurationMs: asrConfig.turnDetectionSilenceDurationMs,
    });
    setShowVoiceModal('asr');
  };

  const openTtsModal = () => {
    if (!ttsConfig) return;
    setTtsForm({
      model: ttsConfig.model,
      voice: ttsConfig.voice,
      format: ttsConfig.format,
      sampleRate: ttsConfig.sampleRate,
      mode: ttsConfig.mode,
      languageType: ttsConfig.languageType,
      speechRate: ttsConfig.speechRate,
      volume: ttsConfig.volume,
    });
    setShowVoiceModal('tts');
  };

  const handleSaveAsr = async () => {
    setVoiceSaving(true);
    try {
      await llmProviderApi.updateAsrConfig(asrForm);
      showToast('ASR 配置已更新');
      setShowVoiceModal(null);
      await loadData();
    } catch (err) {
      showToast(err instanceof Error ? err.message : '更新失败', 'error');
    } finally {
      setVoiceSaving(false);
    }
  };

  const handleSaveTts = async () => {
    setVoiceSaving(true);
    try {
      await llmProviderApi.updateTtsConfig(ttsForm);
      showToast('TTS 配置已更新');
      setShowVoiceModal(null);
      await loadData();
    } catch (err) {
      showToast(err instanceof Error ? err.message : '更新失败', 'error');
    } finally {
      setVoiceSaving(false);
    }
  };

  const handleTestAsr = async () => {
    setTestingAsr(true);
    setAsrTestResult(null);
    try {
      const result = await llmProviderApi.testAsr();
      setAsrTestResult(result);
    } catch (err) {
      setAsrTestResult({
        success: false,
        message: err instanceof Error ? err.message : '连接测试失败',
        model: '',
      });
    } finally {
      setTestingAsr(false);
    }
  };

  // --- Render ---
  return (
    <div className="max-w-4xl mx-auto">
      {/* Page header */}
      <div className="mb-8">
        <div className="flex items-center gap-4 mb-2">
          <div className="p-3 rounded-xl bg-gradient-to-r from-primary-500 to-primary-600 shadow-lg shadow-primary-500/25">
            <Settings className="w-6 h-6 text-white" />
          </div>
          <div>
            <h1 className="text-2xl font-bold text-slate-800 dark:text-white">系统设置</h1>
            <p className="text-slate-500 dark:text-slate-400 mt-0.5 text-sm">管理聊天模型、向量模型和模块配置</p>
          </div>
        </div>
      </div>

      {/* Loading state */}
      {loading ? (
        <div className="flex items-center justify-center py-20">
          <Loader2 className="w-8 h-8 text-primary-500 animate-spin" />
        </div>
      ) : (
        <AnimatePresence mode="wait">
          <motion.div
            key="providers"
            initial={{ opacity: 0, y: 10 }}
            animate={{ opacity: 1, y: 0 }}
            exit={{ opacity: 0, y: -10 }}
            transition={{ duration: 0.15 }}
          >
              {/* Provider header */}
              <div className="flex items-center justify-between mb-4">
                <h2 className="text-lg font-bold text-slate-800 dark:text-white">
                  模型服务
                </h2>
                <motion.button
                  onClick={openCreateModal}
                  whileHover={{ scale: 1.02 }}
                  whileTap={{ scale: 0.98 }}
                  className="flex items-center gap-2 px-4 py-2.5 rounded-xl font-medium text-sm
                    bg-gradient-to-r from-primary-500 to-primary-600 text-white shadow-lg shadow-primary-500/25
                    hover:from-primary-600 hover:to-primary-700 transition-all"
                >
                  <Plus className="w-4 h-4" />
                  新增 Provider
                </motion.button>
              </div>

              {/* Provider grid */}
              {providers.length === 0 ? (
                <div className="text-center py-16 bg-white dark:bg-slate-800 rounded-xl border border-slate-200 dark:border-slate-700">
                  <Server className="w-12 h-12 text-slate-300 dark:text-slate-600 mx-auto mb-3" />
                  <p className="text-slate-500 dark:text-slate-400 text-sm">暂无 Provider，点击上方按钮新增</p>
                </div>
              ) : (
                <div className="grid grid-cols-1 items-stretch gap-4 md:grid-cols-2">
                  {providers.map((provider, index) => {
                    const isGlobalDefault = isGlobalDefaultProvider(provider.id);
                    const isEmbeddingDefault = isDefaultEmbeddingProvider(provider.id);
                    const canUseEmbedding = provider.supportsEmbedding && !!provider.embeddingModel;

                    return (
                    <motion.div
                      key={provider.id}
                      initial={{ opacity: 0, y: 10 }}
                      animate={{ opacity: 1, y: 0 }}
                      transition={{ delay: index * 0.05 }}
                      className={CARD_CLASS}
                    >
                      {/* Card header */}
                      <div className="mb-4 flex items-start justify-between gap-3">
                        <div className="flex min-w-0 items-center gap-3">
                          <div className={ICON_WRAP_CLASS}>
                            <Server className="h-4 w-4" />
                          </div>
                          <div className="min-w-0">
                            <h3 className="truncate text-sm font-semibold text-slate-800 dark:text-white">
                              {provider.id}
                            </h3>
                            <p className="mt-1 text-xs text-slate-500 dark:text-slate-400">聊天/向量 Provider</p>
                          </div>
                        </div>
                        <div className="flex flex-col items-end gap-1">
                          {isGlobalDefault && (
                            <StatusBadge icon={<Plug className="h-3 w-3" />}>文字默认</StatusBadge>
                          )}
                          {isEmbeddingDefault && (
                            <StatusBadge icon={<Database className="h-3 w-3" />}>向量默认</StatusBadge>
                          )}
                        </div>
                      </div>

                      {/* Card details */}
                      <dl className={DETAILS_CLASS}>
                        <ConfigRow label="Base URL" value={provider.baseUrl} title={provider.baseUrl} emphasis />
                        <ConfigRow label="聊天模型" value={provider.model} title={provider.model} emphasis />
                        <ConfigRow
                          label="向量模型"
                          value={canUseEmbedding ? '支持' : '不支持'}
                          title={canUseEmbedding ? provider.embeddingModel ?? '' : '不能用于知识库向量化'}
                        />
                        {provider.embeddingModel && (
                          <ConfigRow label="实际向量" value={provider.embeddingModel} title={provider.embeddingModel} emphasis={isEmbeddingDefault} />
                        )}
                        {canUseEmbedding && (
                          <ConfigRow label="向量维度" value={`${provider.embeddingDimensions ?? 1024} 维`} emphasis={isEmbeddingDefault} />
                        )}
                        {provider.temperature != null && (
                          <ConfigRow label="温度" value={provider.temperature} />
                        )}
                        <ConfigRow
                          label="API Key"
                          value={provider.maskedApiKey}
                          title={provider.maskedApiKey}
                          monospace
                          emphasis
                        />
                      </dl>

                      {/* Test result */}
                      {testResults[provider.id] && (
                        <motion.div
                          initial={{ opacity: 0, height: 0 }}
                          animate={{ opacity: 1, height: 'auto' }}
                          className={`mb-3 px-3 py-2 rounded-lg text-xs font-medium ${
                            testResults[provider.id].success
                              ? 'bg-emerald-50 dark:bg-emerald-900/20 text-emerald-700 dark:text-emerald-300'
                              : 'bg-red-50 dark:bg-red-900/20 text-red-700 dark:text-red-300'
                          }`}
                        >
                          <div className="flex items-center gap-1.5">
                            {testResults[provider.id].success
                              ? <CheckCircle className="w-3.5 h-3.5 flex-shrink-0" />
                              : <XCircle className="w-3.5 h-3.5 flex-shrink-0" />
                            }
                            <span>{testResults[provider.id].message}</span>
                          </div>
                        </motion.div>
                      )}

                      {/* Card actions */}
                      <div className={ACTION_BAR_CLASS}>
                        <button
                          onClick={() => openEditModal(provider)}
                          className={`${ACTION_BUTTON_CLASS} text-slate-600 hover:bg-slate-100 dark:text-slate-300 dark:hover:bg-slate-700`}
                          title="编辑"
                        >
                          <Edit2 className="w-3.5 h-3.5" />
                          编辑
                        </button>
                        <button
                          onClick={() => handleTest(provider.id)}
                          disabled={testingId === provider.id}
                          className={`${ACTION_BUTTON_CLASS} text-blue-600 hover:bg-blue-50 dark:text-blue-400 dark:hover:bg-blue-900/20`}
                          title="测试连接"
                        >
                          {testingId === provider.id
                            ? <Loader2 className="w-3.5 h-3.5 animate-spin" />
                            : <RefreshCw className="w-3.5 h-3.5" />
                          }
                          测试
                        </button>
                        <button
                          onClick={() => handleSetDefault(provider.id)}
                          disabled={isGlobalDefault || settingDefault}
                          className={`${ACTION_BUTTON_CLASS} text-primary-600 hover:bg-primary-50 dark:text-primary-400 dark:hover:bg-primary-900/20 disabled:hover:bg-transparent dark:disabled:hover:bg-transparent`}
                          title="设为默认文字服务"
                        >
                          <Plug className="w-3.5 h-3.5" />
                          设为文字
                        </button>
                        <button
                          onClick={() => handleSetEmbeddingDefault(provider)}
                          disabled={!canUseEmbedding || isEmbeddingDefault || settingEmbeddingDefault}
                          className={`${ACTION_BUTTON_CLASS} text-emerald-600 hover:bg-emerald-50 dark:text-emerald-400 dark:hover:bg-emerald-900/20 disabled:hover:bg-transparent dark:disabled:hover:bg-transparent`}
                          title={canUseEmbedding ? '设为默认向量服务' : '该 Provider 不支持 Embedding'}
                        >
                          <Database className="w-3.5 h-3.5" />
                          设为向量
                        </button>
                        <button
                          onClick={() => setDeleteConfirmId(provider.id)}
                          className={`${ACTION_BUTTON_CLASS} ml-auto text-slate-400 hover:bg-red-50 hover:text-red-500 dark:text-slate-500 dark:hover:bg-red-900/20 dark:hover:text-red-300`}
                          title="删除"
                        >
                          <Trash2 className="w-3.5 h-3.5" />
                        </button>
                      </div>
                    </motion.div>
                    );
                  })}
                </div>
              )}

              {/* Voice service cards */}
              <div className="mt-6">
                <h2 className="text-lg font-bold text-slate-800 dark:text-white mb-4">
                  语音服务
                </h2>
                <div className="grid grid-cols-1 items-stretch gap-4 md:grid-cols-2">
                  {/* ASR Card */}
                  {asrConfig && (
                    <motion.div
                      initial={{ opacity: 0, y: 10 }}
                      animate={{ opacity: 1, y: 0 }}
                      className={CARD_CLASS}
                    >
                      <div className="mb-4 flex items-start justify-between gap-3">
                        <div className="flex min-w-0 items-center gap-3">
                          <div className={ICON_WRAP_CLASS}>
                            <Mic className="h-4 w-4" />
                          </div>
                          <div className="min-w-0">
                            <h3 className="truncate text-sm font-semibold text-slate-800 dark:text-white">
                              ASR 语音识别
                            </h3>
                            <p className="mt-1 text-xs text-slate-500 dark:text-slate-400">实时语音转写配置</p>
                          </div>
                        </div>
                        <StatusBadge icon={<Mic className="h-3 w-3" />}>语音服务</StatusBadge>
                      </div>

                      <dl className={DETAILS_CLASS}>
                        <ConfigRow label="WebSocket URL" value={asrConfig.url} title={asrConfig.url} emphasis />
                        <ConfigRow label="识别模型" value={asrConfig.model} title={asrConfig.model} emphasis />
                        <ConfigRow label="识别语言" value={asrConfig.language} />
                        <ConfigRow label="采样率" value={`${asrConfig.sampleRate}Hz`} />
                        <ConfigRow
                          label="API Key"
                          value={asrConfig.maskedApiKey}
                          title={asrConfig.maskedApiKey}
                          monospace
                          emphasis
                        />
                      </dl>

                      {asrTestResult && (
                        <div className={`mb-3 px-3 py-2 rounded-lg text-xs font-medium ${
                          asrTestResult.success
                            ? 'bg-emerald-50 dark:bg-emerald-900/20 text-emerald-700 dark:text-emerald-300'
                            : 'bg-red-50 dark:bg-red-900/20 text-red-700 dark:text-red-300'
                        }`}>
                          <div className="flex items-center gap-1.5">
                            {asrTestResult.success
                              ? <CheckCircle className="w-3.5 h-3.5 flex-shrink-0" />
                              : <XCircle className="w-3.5 h-3.5 flex-shrink-0" />
                            }
                            <span>{asrTestResult.message}</span>
                          </div>
                        </div>
                      )}

                      <div className={ACTION_BAR_CLASS}>
                        <button
                          onClick={openAsrModal}
                          className={`${ACTION_BUTTON_CLASS} text-slate-600 hover:bg-slate-100 dark:text-slate-300 dark:hover:bg-slate-700`}
                        >
                          <Edit2 className="w-3.5 h-3.5" />
                          编辑
                        </button>
                        <button
                          onClick={handleTestAsr}
                          disabled={testingAsr}
                          className={`${ACTION_BUTTON_CLASS} text-blue-600 hover:bg-blue-50 dark:text-blue-400 dark:hover:bg-blue-900/20`}
                        >
                          {testingAsr
                            ? <Loader2 className="w-3.5 h-3.5 animate-spin" />
                            : <RefreshCw className="w-3.5 h-3.5" />
                          }
                          测试
                        </button>
                      </div>
                    </motion.div>
                  )}

                  {/* TTS Card */}
                  {ttsConfig && (
                    <motion.div
                      initial={{ opacity: 0, y: 10 }}
                      animate={{ opacity: 1, y: 0 }}
                      transition={{ delay: 0.05 }}
                      className={CARD_CLASS}
                    >
                      <div className="mb-4 flex items-start justify-between gap-3">
                        <div className="flex min-w-0 items-center gap-3">
                          <div className={ICON_WRAP_CLASS}>
                            <Volume2 className="h-4 w-4" />
                          </div>
                          <div className="min-w-0">
                            <h3 className="truncate text-sm font-semibold text-slate-800 dark:text-white">
                              TTS 语音合成
                            </h3>
                            <p className="mt-1 text-xs text-slate-500 dark:text-slate-400">文本转语音输出配置</p>
                          </div>
                        </div>
                        <StatusBadge icon={<Volume2 className="h-3 w-3" />}>语音服务</StatusBadge>
                      </div>

                      <dl className={DETAILS_CLASS}>
                        <ConfigRow label="合成模型" value={ttsConfig.model} title={ttsConfig.model} emphasis />
                        <ConfigRow label="音色" value={ttsConfig.voice} title={ttsConfig.voice} emphasis />
                        <ConfigRow label="采样率" value={`${ttsConfig.sampleRate}Hz`} />
                        <ConfigRow label="音量" value={ttsConfig.volume} />
                        <ConfigRow
                          label="API Key"
                          value={ttsConfig.maskedApiKey}
                          title={ttsConfig.maskedApiKey}
                          monospace
                          emphasis
                        />
                      </dl>

                      <div className={ACTION_BAR_CLASS}>
                        <button
                          onClick={openTtsModal}
                          className={`${ACTION_BUTTON_CLASS} text-slate-600 hover:bg-slate-100 dark:text-slate-300 dark:hover:bg-slate-700`}
                        >
                          <Edit2 className="w-3.5 h-3.5" />
                          编辑
                        </button>
                      </div>
                    </motion.div>
                  )}
                </div>
              </div>
          </motion.div>
        </AnimatePresence>
      )}

      {/* Create / Edit Modal */}
      <AnimatePresence>
        {showModal && (
          <>
            <motion.div
              initial={{ opacity: 0 }}
              animate={{ opacity: 1 }}
              exit={{ opacity: 0 }}
              onClick={closeModal}
              className="fixed inset-0 bg-black/50 backdrop-blur-sm z-50"
            />
            <div className="fixed inset-0 z-50 flex items-center justify-center p-4">
              <motion.div
                initial={{ opacity: 0, scale: 0.95, y: 20 }}
                animate={{ opacity: 1, scale: 1, y: 0 }}
                exit={{ opacity: 0, scale: 0.95, y: 20 }}
                onClick={(e) => e.stopPropagation()}
                className="bg-white dark:bg-slate-800 rounded-2xl shadow-2xl max-w-lg w-full p-6"
              >
                <h3 className="text-xl font-bold text-slate-900 dark:text-white mb-5">
                  {editingProvider ? '编辑 Provider' : '新增 Provider'}
                </h3>

                <div className="space-y-4">
                  {/* Provider ID */}
                  <div>
                    <label className="block text-sm font-medium text-slate-700 dark:text-slate-300 mb-1.5">
                      Provider ID <span className="text-red-500">*</span>
                    </label>
                    <input
                      type="text"
                      value={formId}
                      onChange={(e) => {
                        const newId = e.target.value;
                        setFormId(newId);
                        // 新建时自动填充已知 Provider 的 Base URL
                        if (!editingProvider) {
                          const preset = PROVIDER_PRESETS[newId.toLowerCase()];
                          if (preset) {
                            setFormBaseUrl(preset.baseUrl);
                            setFormSupportsEmbedding(preset.supportsEmbedding);
                            setFormEmbeddingModel(preset.embeddingModels?.[0]?.value ?? '');
                            setFormEmbeddingDimensions(String(preset.embeddingDimensions ?? 1024));
                          }
                        }
                      }}
                      disabled={!!editingProvider}
                      placeholder="例如: siliconflow, dashscope, deepseek, glm, kimi"
                      className="w-full px-4 py-2.5 rounded-xl border border-slate-200 dark:border-slate-600
                        bg-white dark:bg-slate-700 text-sm text-slate-900 dark:text-white
                        placeholder:text-slate-400 focus:outline-none focus:ring-2
                        focus:ring-primary-500/50 focus:border-primary-400 transition-shadow
                        disabled:opacity-50 disabled:cursor-not-allowed"
                    />
                  </div>

                  {/* Base URL */}
                  <div>
                    <label className="block text-sm font-medium text-slate-700 dark:text-slate-300 mb-1.5">
                      Base URL <span className="text-red-500">*</span>
                    </label>
                    <input
                      type="text"
                      value={formBaseUrl}
                      onChange={(e) => setFormBaseUrl(e.target.value)}
                      placeholder="例如: https://api.openai.com/v1"
                      className="w-full px-4 py-2.5 rounded-xl border border-slate-200 dark:border-slate-600
                        bg-white dark:bg-slate-700 text-sm text-slate-900 dark:text-white
                        placeholder:text-slate-400 focus:outline-none focus:ring-2
                        focus:ring-primary-500/50 focus:border-primary-400 transition-shadow"
                    />
                  </div>

                  {/* API Key */}
                  <div>
                    <label className="block text-sm font-medium text-slate-700 dark:text-slate-300 mb-1.5">
                      API Key{' '}
                      {editingProvider && (
                        <span className="text-slate-400 font-normal">(留空则不修改)</span>
                      )}
                      {!editingProvider && <span className="text-red-500">*</span>}
                    </label>
                    <div className="relative">
                      <input
                        type={showApiKey ? 'text' : 'password'}
                        value={formApiKey}
                        onChange={(e) => setFormApiKey(e.target.value)}
                        placeholder={editingProvider ? '留空则保持原值' : '输入 API Key'}
                        className="w-full px-4 py-2.5 pr-10 rounded-xl border border-slate-200 dark:border-slate-600
                          bg-white dark:bg-slate-700 text-sm text-slate-900 dark:text-white
                          placeholder:text-slate-400 focus:outline-none focus:ring-2
                          focus:ring-primary-500/50 focus:border-primary-400 transition-shadow"
                      />
                      <button
                        type="button"
                        onClick={() => setShowApiKey(!showApiKey)}
                        className="absolute right-3 top-1/2 -translate-y-1/2 text-slate-400
                          hover:text-slate-600 dark:hover:text-slate-300 transition-colors"
                      >
                        {showApiKey ? <EyeOff className="w-4 h-4" /> : <Eye className="w-4 h-4" />}
                      </button>
                    </div>
                  </div>

                  <div>
                    <button
                      type="button"
                      onClick={handleDiscoverModels}
                      disabled={!formBaseUrl.trim() || !formApiKey.trim() || discoveringModels}
                      className="inline-flex items-center gap-2 rounded-xl border border-primary-200 px-4 py-2 text-sm font-medium text-primary-700 transition-colors hover:bg-primary-50 disabled:cursor-not-allowed disabled:opacity-50 dark:border-primary-800 dark:text-primary-300 dark:hover:bg-primary-900/30"
                    >
                      <RefreshCw className={`h-4 w-4 ${discoveringModels ? 'animate-spin' : ''}`} />
                      {discoveringModels ? '获取中...' : '获取模型'}
                    </button>
                    {!formApiKey.trim() && editingProvider && (
                      <p className="mt-1.5 text-xs text-slate-500 dark:text-slate-400">输入 API Key 后可获取模型</p>
                    )}
                    {modelDiscoveryError && (
                      <p className="mt-1.5 text-xs text-red-600 dark:text-red-400">{modelDiscoveryError}</p>
                    )}
                  </div>

                  {/* Chat Model */}
                  <div>
                    <label className="block text-sm font-medium text-slate-700 dark:text-slate-300 mb-1.5">
                      聊天模型 <span className="text-red-500">*</span>
                    </label>
                    <div className="relative">
                      <input
                        type="text"
                        value={formModel}
                        onChange={(e) => {
                          setFormModel(e.target.value);
                          setShowModelDropdown(false);
                        }}
                        onFocus={() => availableModelOptions.length > 0 && setShowModelDropdown(true)}
                        onBlur={() => setTimeout(() => setShowModelDropdown(false), 150)}
                        placeholder={currentPreset ? '从下拉列表选择或输入自定义聊天模型名' : '例如: qwen3.5-flash, deepseek-v4-flash, glm-5'}
                        className="w-full px-4 py-2.5 rounded-xl border border-slate-200 dark:border-slate-600
                          bg-white dark:bg-slate-700 text-sm text-slate-900 dark:text-white
                          placeholder:text-slate-400 focus:outline-none focus:ring-2
                          focus:ring-primary-500/50 focus:border-primary-400 transition-shadow"
                      />
                      {availableModelOptions.length > 0 && (
                        <button
                          type="button"
                          onClick={() => setShowModelDropdown(!showModelDropdown)}
                          className="absolute right-3 top-1/2 -translate-y-1/2 text-slate-400
                            hover:text-slate-600 dark:hover:text-slate-300 transition-colors"
                        >
                          <ChevronDown className="w-4 h-4" />
                        </button>
                      )}
                      {showModelDropdown && availableModelOptions.length > 0 && (
                        <div className="absolute z-10 mt-1 w-full bg-white dark:bg-slate-700
                          border border-slate-200 dark:border-slate-600 rounded-xl shadow-lg
                          max-h-60 overflow-auto">
                          {availableModelOptions.map((m) => (
                            <button
                              key={m.value}
                              type="button"
                              onClick={() => {
                                setFormModel(m.value);
                                setShowModelDropdown(false);
                              }}
                              className={`w-full px-4 py-2.5 text-left text-sm hover:bg-primary-50
                                dark:hover:bg-slate-600 transition-colors flex justify-between items-center
                                ${formModel === m.value
                                  ? 'text-primary-600 dark:text-primary-400 font-medium bg-primary-50 dark:bg-slate-600'
                                  : 'text-slate-700 dark:text-slate-200'}`}
                            >
                              <span className="font-mono">{m.value}</span>
                              <span className="text-xs text-slate-400 dark:text-slate-500 ml-2 whitespace-nowrap">{m.label}</span>
                            </button>
                          ))}
                        </div>
                      )}
                    </div>
                  </div>

                  {/* Embedding Model */}
                  <div>
                    <div className="mb-1.5 flex items-center justify-between gap-3">
                      <label className="block text-sm font-medium text-slate-700 dark:text-slate-300">
                        向量模型 <span className="text-slate-400 font-normal">(知识库向量化，例如 GLM 填 embedding-3)</span>
                      </label>
                      <label className="inline-flex items-center gap-2 text-xs font-medium text-slate-600 dark:text-slate-300">
                        <input
                          type="checkbox"
                          checked={formSupportsEmbedding}
                          onChange={(e) => {
                            setFormSupportsEmbedding(e.target.checked);
                            if (!e.target.checked) {
                              setFormEmbeddingModel('');
                              setFormEmbeddingDimensions('1024');
                            }
                          }}
                          className="h-4 w-4 rounded border-slate-300 text-primary-600 focus:ring-primary-500"
                        />
                        支持 Embedding
                      </label>
                    </div>
                    <div className="relative">
                      <input
                        type="text"
                        value={formEmbeddingModel}
                        onChange={(e) => {
                          setFormEmbeddingModel(e.target.value);
                          setShowEmbeddingDropdown(false);
                        }}
                        onFocus={() => formSupportsEmbedding && availableEmbeddingModelOptions.length > 0 && setShowEmbeddingDropdown(true)}
                        onBlur={() => setTimeout(() => setShowEmbeddingDropdown(false), 150)}
                        disabled={!formSupportsEmbedding}
                        placeholder={formSupportsEmbedding
                          ? (currentPreset?.embeddingModels ? '从下拉列表选择或输入自定义向量模型名' : '例如: text-embedding-v3, embedding-3')
                          : 'DeepSeek / Kimi 等 Provider 通常不支持 Embedding'}
                        className="w-full px-4 py-2.5 rounded-xl border border-slate-200 dark:border-slate-600
                          bg-white dark:bg-slate-700 text-sm text-slate-900 dark:text-white
                          placeholder:text-slate-400 focus:outline-none focus:ring-2
                          focus:ring-primary-500/50 focus:border-primary-400 transition-shadow
                          disabled:cursor-not-allowed disabled:opacity-60"
                      />
                      {formSupportsEmbedding && availableEmbeddingModelOptions.length > 0 && (
                        <button
                          type="button"
                          onClick={() => setShowEmbeddingDropdown(!showEmbeddingDropdown)}
                          className="absolute right-3 top-1/2 -translate-y-1/2 text-slate-400
                            hover:text-slate-600 dark:hover:text-slate-300 transition-colors"
                        >
                          <ChevronDown className="w-4 h-4" />
                        </button>
                      )}
                      {formSupportsEmbedding && showEmbeddingDropdown && availableEmbeddingModelOptions.length > 0 && (
                        <div className="absolute z-10 mt-1 w-full bg-white dark:bg-slate-700
                          border border-slate-200 dark:border-slate-600 rounded-xl shadow-lg
                          max-h-60 overflow-auto">
                          {availableEmbeddingModelOptions.map((m) => (
                            <button
                              key={m.value}
                              type="button"
                              onClick={() => {
                                setFormEmbeddingModel(m.value);
                                setShowEmbeddingDropdown(false);
                              }}
                              className={`w-full px-4 py-2.5 text-left text-sm hover:bg-primary-50
                                dark:hover:bg-slate-600 transition-colors flex justify-between items-center
                                ${formEmbeddingModel === m.value
                                  ? 'text-primary-600 dark:text-primary-400 font-medium bg-primary-50 dark:bg-slate-600'
                                  : 'text-slate-700 dark:text-slate-200'}`}
                            >
                              <span className="font-mono">{m.value}</span>
                              <span className="text-xs text-slate-400 dark:text-slate-500 ml-2 whitespace-nowrap">{m.label}</span>
                            </button>
                          ))}
                        </div>
                      )}
                    </div>
                  </div>

                  {formSupportsEmbedding && (
                    <div>
                      <label className="block text-sm font-medium text-slate-700 dark:text-slate-300 mb-1.5">
                        向量维度 <span className="text-slate-400 font-normal">(必须与 pgvector 表一致，当前为 1024)</span>
                      </label>
                      <input
                        type="number"
                        min={1}
                        value={formEmbeddingDimensions}
                        onChange={(e) => setFormEmbeddingDimensions(e.target.value)}
                        placeholder="1024"
                        className="w-full px-4 py-2.5 rounded-xl border border-slate-200 dark:border-slate-600
                          bg-white dark:bg-slate-700 text-sm text-slate-900 dark:text-white
                          placeholder:text-slate-400 focus:outline-none focus:ring-2
                          focus:ring-primary-500/50 focus:border-primary-400 transition-shadow"
                      />
                    </div>
                  )}

                  {/* Temperature */}
                  <div>
                    <label className="block text-sm font-medium text-slate-700 dark:text-slate-300 mb-1.5">
                      Temperature <span className="text-slate-400 font-normal">(可选, 默认 0.2)</span>
                    </label>
                    <input
                      type="text"
                      value={formTemperature}
                      onChange={(e) => setFormTemperature(e.target.value)}
                      placeholder="例如: 0.2, 0.7, 1"
                      className="w-full px-4 py-2.5 rounded-xl border border-slate-200 dark:border-slate-600
                        bg-white dark:bg-slate-700 text-sm text-slate-900 dark:text-white
                        placeholder:text-slate-400 focus:outline-none focus:ring-2
                        focus:ring-primary-500/50 focus:border-primary-400 transition-shadow"
                    />
                  </div>
                </div>

                {/* Modal actions */}
                <div className="flex gap-3 justify-end mt-6">
                  <motion.button
                    onClick={closeModal}
                    disabled={saving}
                    className="px-5 py-2.5 border border-slate-200 dark:border-slate-600
                      text-slate-600 dark:text-slate-300 rounded-xl font-medium text-sm
                      hover:bg-slate-50 dark:hover:bg-slate-700 transition-all
                      disabled:opacity-50 disabled:cursor-not-allowed"
                    whileHover={{ scale: 1.02 }}
                    whileTap={{ scale: 0.98 }}
                  >
                    取消
                  </motion.button>
                  <motion.button
                    onClick={handleSaveModal}
                    disabled={saving}
                    className="px-5 py-2.5 text-white rounded-xl font-semibold text-sm
                      bg-gradient-to-r from-primary-500 to-primary-600
                      shadow-lg shadow-primary-500/25
                      hover:from-primary-600 hover:to-primary-700
                      transition-all disabled:opacity-50 disabled:cursor-not-allowed"
                    whileHover={{ scale: 1.02 }}
                    whileTap={{ scale: 0.98 }}
                  >
                    {saving ? (
                      <span className="flex items-center gap-2">
                        <Loader2 className="w-4 h-4 animate-spin" />
                        保存中...
                      </span>
                    ) : (
                      '保存'
                    )}
                  </motion.button>
                </div>
              </motion.div>
            </div>
          </>
        )}
      </AnimatePresence>

      {/* Voice Edit Modal */}
      <AnimatePresence>
        {showVoiceModal && (
          <>
            <motion.div
              initial={{ opacity: 0 }}
              animate={{ opacity: 1 }}
              exit={{ opacity: 0 }}
              onClick={() => setShowVoiceModal(null)}
              className="fixed inset-0 bg-black/50 backdrop-blur-sm z-50"
            />
            <div className="fixed inset-0 z-50 flex items-center justify-center p-4">
              <motion.div
                initial={{ opacity: 0, scale: 0.95, y: 20 }}
                animate={{ opacity: 1, scale: 1, y: 0 }}
                exit={{ opacity: 0, scale: 0.95, y: 20 }}
                onClick={(e) => e.stopPropagation()}
                className="bg-white dark:bg-slate-800 rounded-2xl shadow-2xl max-w-lg w-full p-6 max-h-[85vh] overflow-y-auto"
              >
                <h3 className="text-xl font-bold text-slate-900 dark:text-white mb-5">
                  {showVoiceModal === 'asr' ? '编辑 ASR 语音识别' : '编辑 TTS 语音合成'}
                </h3>

                {showVoiceModal === 'asr' ? (
                  <div className="space-y-4">
                    <p className="text-xs font-semibold text-slate-500 dark:text-slate-400 uppercase tracking-wider">连接配置</p>
                    <div>
                      <label className="block text-sm font-medium text-slate-700 dark:text-slate-300 mb-1.5">WebSocket URL</label>
                      <input type="text" value={asrForm.url || ''} onChange={(e) => setAsrForm(f => ({ ...f, url: e.target.value }))}
                        className="w-full px-4 py-2.5 rounded-xl border border-slate-200 dark:border-slate-600 bg-white dark:bg-slate-700 text-sm text-slate-900 dark:text-white focus:outline-none focus:ring-2 focus:ring-primary-500/50 focus:border-primary-400 transition-shadow" />
                    </div>
                    <div className="grid grid-cols-2 gap-3">
                      <div>
                        <label className="block text-sm font-medium text-slate-700 dark:text-slate-300 mb-1.5">Model</label>
                        <input type="text" value={asrForm.model || ''} onChange={(e) => setAsrForm(f => ({ ...f, model: e.target.value }))}
                          className="w-full px-4 py-2.5 rounded-xl border border-slate-200 dark:border-slate-600 bg-white dark:bg-slate-700 text-sm text-slate-900 dark:text-white focus:outline-none focus:ring-2 focus:ring-primary-500/50 focus:border-primary-400 transition-shadow" />
                      </div>
                      <div>
                        <label className="block text-sm font-medium text-slate-700 dark:text-slate-300 mb-1.5">API Key <span className="text-slate-400 font-normal">(留空不改)</span></label>
                        <input type="password" value={asrForm.apiKey || ''} onChange={(e) => setAsrForm(f => ({ ...f, apiKey: e.target.value }))}
                          placeholder="留空则保持原值"
                          className="w-full px-4 py-2.5 rounded-xl border border-slate-200 dark:border-slate-600 bg-white dark:bg-slate-700 text-sm text-slate-900 dark:text-white focus:outline-none focus:ring-2 focus:ring-primary-500/50 focus:border-primary-400 transition-shadow" />
                      </div>
                    </div>
                    <div>
                      <label className="block text-sm font-medium text-slate-700 dark:text-slate-300 mb-1.5">Language</label>
                      <input type="text" value={asrForm.language || ''} onChange={(e) => setAsrForm(f => ({ ...f, language: e.target.value }))}
                        className="w-full px-4 py-2.5 rounded-xl border border-slate-200 dark:border-slate-600 bg-white dark:bg-slate-700 text-sm text-slate-900 dark:text-white focus:outline-none focus:ring-2 focus:ring-primary-500/50 focus:border-primary-400 transition-shadow" />
                    </div>

                    <p className="text-xs font-semibold text-slate-500 dark:text-slate-400 uppercase tracking-wider pt-2">音频参数</p>
                    <div className="grid grid-cols-2 gap-3">
                      <div>
                        <label className="block text-sm font-medium text-slate-700 dark:text-slate-300 mb-1.5">Format</label>
                        <input type="text" value={asrForm.format || ''} onChange={(e) => setAsrForm(f => ({ ...f, format: e.target.value }))}
                          className="w-full px-4 py-2.5 rounded-xl border border-slate-200 dark:border-slate-600 bg-white dark:bg-slate-700 text-sm text-slate-900 dark:text-white focus:outline-none focus:ring-2 focus:ring-primary-500/50 focus:border-primary-400 transition-shadow" />
                      </div>
                      <div>
                        <label className="block text-sm font-medium text-slate-700 dark:text-slate-300 mb-1.5">Sample Rate</label>
                        <input type="number" value={asrForm.sampleRate || 0} onChange={(e) => setAsrForm(f => ({ ...f, sampleRate: Number(e.target.value) }))}
                          className="w-full px-4 py-2.5 rounded-xl border border-slate-200 dark:border-slate-600 bg-white dark:bg-slate-700 text-sm text-slate-900 dark:text-white focus:outline-none focus:ring-2 focus:ring-primary-500/50 focus:border-primary-400 transition-shadow" />
                      </div>
                    </div>

                    <p className="text-xs font-semibold text-slate-500 dark:text-slate-400 uppercase tracking-wider pt-2">VAD 参数</p>
                    <div className="grid grid-cols-2 gap-3">
                      <div>
                        <label className="block text-sm font-medium text-slate-700 dark:text-slate-300 mb-1.5">Turn Detection</label>
                        <select value={asrForm.enableTurnDetection ? 'true' : 'false'} onChange={(e) => setAsrForm(f => ({ ...f, enableTurnDetection: e.target.value === 'true' }))}
                          className="w-full px-4 py-2.5 rounded-xl border border-slate-200 dark:border-slate-600 bg-white dark:bg-slate-700 text-sm text-slate-900 dark:text-white focus:outline-none focus:ring-2 focus:ring-primary-500/50 focus:border-primary-400 transition-shadow">
                          <option value="true">Enabled</option>
                          <option value="false">Disabled</option>
                        </select>
                      </div>
                      <div>
                        <label className="block text-sm font-medium text-slate-700 dark:text-slate-300 mb-1.5">Detection Type</label>
                        <input type="text" value={asrForm.turnDetectionType || ''} onChange={(e) => setAsrForm(f => ({ ...f, turnDetectionType: e.target.value }))}
                          className="w-full px-4 py-2.5 rounded-xl border border-slate-200 dark:border-slate-600 bg-white dark:bg-slate-700 text-sm text-slate-900 dark:text-white focus:outline-none focus:ring-2 focus:ring-primary-500/50 focus:border-primary-400 transition-shadow" />
                      </div>
                    </div>
                    <div className="grid grid-cols-2 gap-3">
                      <div>
                        <label className="block text-sm font-medium text-slate-700 dark:text-slate-300 mb-1.5">Threshold</label>
                        <input type="number" step="0.1" value={asrForm.turnDetectionThreshold || 0} onChange={(e) => setAsrForm(f => ({ ...f, turnDetectionThreshold: Number(e.target.value) }))}
                          className="w-full px-4 py-2.5 rounded-xl border border-slate-200 dark:border-slate-600 bg-white dark:bg-slate-700 text-sm text-slate-900 dark:text-white focus:outline-none focus:ring-2 focus:ring-primary-500/50 focus:border-primary-400 transition-shadow" />
                      </div>
                      <div>
                        <label className="block text-sm font-medium text-slate-700 dark:text-slate-300 mb-1.5">Silence Duration (ms)</label>
                        <input type="number" value={asrForm.turnDetectionSilenceDurationMs || 0} onChange={(e) => setAsrForm(f => ({ ...f, turnDetectionSilenceDurationMs: Number(e.target.value) }))}
                          className="w-full px-4 py-2.5 rounded-xl border border-slate-200 dark:border-slate-600 bg-white dark:bg-slate-700 text-sm text-slate-900 dark:text-white focus:outline-none focus:ring-2 focus:ring-primary-500/50 focus:border-primary-400 transition-shadow" />
                      </div>
                    </div>
                  </div>
                ) : (
                  <div className="space-y-4">
                    <p className="text-xs font-semibold text-slate-500 dark:text-slate-400 uppercase tracking-wider">连接配置</p>
                    <div className="grid grid-cols-2 gap-3">
                      <div>
                        <label className="block text-sm font-medium text-slate-700 dark:text-slate-300 mb-1.5">Model</label>
                        <input type="text" value={ttsForm.model || ''} onChange={(e) => setTtsForm(f => ({ ...f, model: e.target.value }))}
                          className="w-full px-4 py-2.5 rounded-xl border border-slate-200 dark:border-slate-600 bg-white dark:bg-slate-700 text-sm text-slate-900 dark:text-white focus:outline-none focus:ring-2 focus:ring-primary-500/50 focus:border-primary-400 transition-shadow" />
                      </div>
                      <div>
                        <label className="block text-sm font-medium text-slate-700 dark:text-slate-300 mb-1.5">API Key <span className="text-slate-400 font-normal">(留空不改)</span></label>
                        <input type="password" value={ttsForm.apiKey || ''} onChange={(e) => setTtsForm(f => ({ ...f, apiKey: e.target.value }))}
                          placeholder="留空则保持原值"
                          className="w-full px-4 py-2.5 rounded-xl border border-slate-200 dark:border-slate-600 bg-white dark:bg-slate-700 text-sm text-slate-900 dark:text-white focus:outline-none focus:ring-2 focus:ring-primary-500/50 focus:border-primary-400 transition-shadow" />
                      </div>
                    </div>

                    <p className="text-xs font-semibold text-slate-500 dark:text-slate-400 uppercase tracking-wider pt-2">语音参数</p>
                    <div className="grid grid-cols-2 gap-3">
                      <div>
                        <label className="block text-sm font-medium text-slate-700 dark:text-slate-300 mb-1.5">Voice</label>
                        <input type="text" value={ttsForm.voice || ''} onChange={(e) => setTtsForm(f => ({ ...f, voice: e.target.value }))}
                          className="w-full px-4 py-2.5 rounded-xl border border-slate-200 dark:border-slate-600 bg-white dark:bg-slate-700 text-sm text-slate-900 dark:text-white focus:outline-none focus:ring-2 focus:ring-primary-500/50 focus:border-primary-400 transition-shadow" />
                      </div>
                      <div>
                        <label className="block text-sm font-medium text-slate-700 dark:text-slate-300 mb-1.5">Format</label>
                        <input type="text" value={ttsForm.format || ''} onChange={(e) => setTtsForm(f => ({ ...f, format: e.target.value }))}
                          className="w-full px-4 py-2.5 rounded-xl border border-slate-200 dark:border-slate-600 bg-white dark:bg-slate-700 text-sm text-slate-900 dark:text-white focus:outline-none focus:ring-2 focus:ring-primary-500/50 focus:border-primary-400 transition-shadow" />
                      </div>
                    </div>
                    <div className="grid grid-cols-3 gap-3">
                      <div>
                        <label className="block text-sm font-medium text-slate-700 dark:text-slate-300 mb-1.5">Sample Rate</label>
                        <input type="number" value={ttsForm.sampleRate || 0} onChange={(e) => setTtsForm(f => ({ ...f, sampleRate: Number(e.target.value) }))}
                          className="w-full px-4 py-2.5 rounded-xl border border-slate-200 dark:border-slate-600 bg-white dark:bg-slate-700 text-sm text-slate-900 dark:text-white focus:outline-none focus:ring-2 focus:ring-primary-500/50 focus:border-primary-400 transition-shadow" />
                      </div>
                      <div>
                        <label className="block text-sm font-medium text-slate-700 dark:text-slate-300 mb-1.5">Mode</label>
                        <input type="text" value={ttsForm.mode || ''} onChange={(e) => setTtsForm(f => ({ ...f, mode: e.target.value }))}
                          className="w-full px-4 py-2.5 rounded-xl border border-slate-200 dark:border-slate-600 bg-white dark:bg-slate-700 text-sm text-slate-900 dark:text-white focus:outline-none focus:ring-2 focus:ring-primary-500/50 focus:border-primary-400 transition-shadow" />
                      </div>
                      <div>
                        <label className="block text-sm font-medium text-slate-700 dark:text-slate-300 mb-1.5">Language</label>
                        <input type="text" value={ttsForm.languageType || ''} onChange={(e) => setTtsForm(f => ({ ...f, languageType: e.target.value }))}
                          className="w-full px-4 py-2.5 rounded-xl border border-slate-200 dark:border-slate-600 bg-white dark:bg-slate-700 text-sm text-slate-900 dark:text-white focus:outline-none focus:ring-2 focus:ring-primary-500/50 focus:border-primary-400 transition-shadow" />
                      </div>
                    </div>

                    <p className="text-xs font-semibold text-slate-500 dark:text-slate-400 uppercase tracking-wider pt-2">输出控制</p>
                    <div className="grid grid-cols-2 gap-3">
                      <div>
                        <label className="block text-sm font-medium text-slate-700 dark:text-slate-300 mb-1.5">Speech Rate</label>
                        <input type="number" step="0.1" value={ttsForm.speechRate || 0} onChange={(e) => setTtsForm(f => ({ ...f, speechRate: Number(e.target.value) }))}
                          className="w-full px-4 py-2.5 rounded-xl border border-slate-200 dark:border-slate-600 bg-white dark:bg-slate-700 text-sm text-slate-900 dark:text-white focus:outline-none focus:ring-2 focus:ring-primary-500/50 focus:border-primary-400 transition-shadow" />
                      </div>
                      <div>
                        <label className="block text-sm font-medium text-slate-700 dark:text-slate-300 mb-1.5">Volume</label>
                        <input type="number" value={ttsForm.volume || 0} onChange={(e) => setTtsForm(f => ({ ...f, volume: Number(e.target.value) }))}
                          className="w-full px-4 py-2.5 rounded-xl border border-slate-200 dark:border-slate-600 bg-white dark:bg-slate-700 text-sm text-slate-900 dark:text-white focus:outline-none focus:ring-2 focus:ring-primary-500/50 focus:border-primary-400 transition-shadow" />
                      </div>
                    </div>
                  </div>
                )}

                {/* Modal actions */}
                <div className="flex gap-3 justify-end mt-6">
                  <motion.button
                    onClick={() => setShowVoiceModal(null)}
                    disabled={voiceSaving}
                    className="px-5 py-2.5 border border-slate-200 dark:border-slate-600 text-slate-600 dark:text-slate-300 rounded-xl font-medium text-sm hover:bg-slate-50 dark:hover:bg-slate-700 transition-all disabled:opacity-50 disabled:cursor-not-allowed"
                    whileHover={{ scale: 1.02 }}
                    whileTap={{ scale: 0.98 }}
                  >
                    取消
                  </motion.button>
                  <motion.button
                    onClick={showVoiceModal === 'asr' ? handleSaveAsr : handleSaveTts}
                    disabled={voiceSaving}
                    className="px-5 py-2.5 text-white rounded-xl font-semibold text-sm bg-gradient-to-r from-primary-500 to-primary-600 shadow-lg shadow-primary-500/25 hover:from-primary-600 hover:to-primary-700 transition-all disabled:opacity-50 disabled:cursor-not-allowed"
                    whileHover={{ scale: 1.02 }}
                    whileTap={{ scale: 0.98 }}
                  >
                    {voiceSaving ? (
                      <span className="flex items-center gap-2">
                        <Loader2 className="w-4 h-4 animate-spin" />
                        保存中...
                      </span>
                    ) : (
                      '保存'
                    )}
                  </motion.button>
                </div>
              </motion.div>
            </div>
          </>
        )}
      </AnimatePresence>

      <ConfirmDialog
        open={pendingDefaultProviderId !== null}
        title="设为默认聊天服务"
        message={`确定要将 "${pendingDefaultProviderId ?? ''}" 设为默认聊天服务吗？该操作不会改变知识库使用的向量模型。`}
        confirmText="确认设置"
        cancelText="取消"
        loading={settingDefault}
        onConfirm={handleConfirmSetDefault}
        onCancel={() => {
          if (!settingDefault) {
            setPendingDefaultProviderId(null);
          }
        }}
      />

      <ConfirmDialog
        open={pendingDefaultEmbeddingProviderId !== null}
        title="设为默认向量服务"
        message={`确定要将 "${pendingDefaultEmbeddingProviderId ?? ''}" 的向量模型 "${pendingEmbeddingProvider?.embeddingModel ?? ''}"（${pendingEmbeddingProvider?.embeddingDimensions ?? 1024}维）设为知识库默认向量服务吗？后续上传和重新向量化会使用这个向量模型，不会使用聊天模型。`}
        confirmText="确认设置"
        cancelText="取消"
        loading={settingEmbeddingDefault}
        onConfirm={handleConfirmSetEmbeddingDefault}
        onCancel={() => {
          if (!settingEmbeddingDefault) {
            setPendingDefaultEmbeddingProviderId(null);
          }
        }}
      />

      {/* Delete confirmation dialog */}
      <AnimatePresence>
        {deleteConfirmId && (
          <>
            <motion.div
              initial={{ opacity: 0 }}
              animate={{ opacity: 1 }}
              exit={{ opacity: 0 }}
              onClick={() => setDeleteConfirmId(null)}
              className="fixed inset-0 bg-black/50 backdrop-blur-sm z-50"
            />
            <div className="fixed inset-0 z-50 flex items-center justify-center p-4">
              <motion.div
                initial={{ opacity: 0, scale: 0.95, y: 20 }}
                animate={{ opacity: 1, scale: 1, y: 0 }}
                exit={{ opacity: 0, scale: 0.95, y: 20 }}
                onClick={(e) => e.stopPropagation()}
                className="bg-white dark:bg-slate-800 rounded-2xl shadow-2xl max-w-md w-full p-6"
              >
                <h3 className="text-xl font-bold text-slate-900 dark:text-white mb-4">
                  删除 Provider
                </h3>
                <p className="text-slate-600 dark:text-slate-300 mb-6">
                  确定要删除 Provider &ldquo;{deleteConfirmId}&rdquo; 吗？删除后无法恢复。
                  如果有模块正在使用此 Provider，请先切换到其他 Provider。
                </p>
                <div className="flex gap-3 justify-end">
                  <motion.button
                    onClick={() => setDeleteConfirmId(null)}
                    disabled={deleting}
                    className="px-5 py-2.5 border border-slate-200 dark:border-slate-600
                      text-slate-600 dark:text-slate-300 rounded-xl font-medium text-sm
                      hover:bg-slate-50 dark:hover:bg-slate-700 transition-all
                      disabled:opacity-50 disabled:cursor-not-allowed"
                    whileHover={{ scale: 1.02 }}
                    whileTap={{ scale: 0.98 }}
                  >
                    取消
                  </motion.button>
                  <motion.button
                    onClick={handleDelete}
                    disabled={deleting}
                    className="px-5 py-2.5 text-white rounded-xl font-semibold text-sm
                      bg-gradient-to-r from-red-500 to-red-600
                      hover:from-red-600 hover:to-red-700
                      transition-all disabled:opacity-50 disabled:cursor-not-allowed"
                    whileHover={{ scale: 1.02 }}
                    whileTap={{ scale: 0.98 }}
                  >
                    {deleting ? (
                      <span className="flex items-center gap-2">
                        <Loader2 className="w-4 h-4 animate-spin" />
                        删除中...
                      </span>
                    ) : (
                      '确定删除'
                    )}
                  </motion.button>
                </div>
              </motion.div>
            </div>
          </>
        )}
      </AnimatePresence>

      {/* Toast notification */}
      <AnimatePresence>
        {toast && (
          <motion.div
            initial={{ opacity: 0, y: 50, x: '-50%' }}
            animate={{ opacity: 1, y: 0, x: '-50%' }}
            exit={{ opacity: 0, y: 50, x: '-50%' }}
            className={`fixed bottom-6 left-1/2 px-5 py-3 rounded-xl shadow-lg text-sm font-medium
              flex items-center gap-2 z-[60] ${
                toast.type === 'success'
                  ? 'bg-emerald-600 text-white'
                  : 'bg-red-600 text-white'
              }`}
          >
            {toast.type === 'success'
              ? <CheckCircle className="w-4 h-4" />
              : <XCircle className="w-4 h-4" />
            }
            {toast.message}
          </motion.div>
        )}
      </AnimatePresence>
    </div>
  );
}
