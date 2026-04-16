import type { IconType } from 'react-icons';
import {
  SiOpenjdk,
  SiReact,
  SiPython,
  SiBytedance,
  SiAlibabadotcom,
} from 'react-icons/si';
import {
  TbMathFunction,
  TbTopologyStarRing3,
  TbSparkles,
  TbTestPipe,
  TbRobot,
} from 'react-icons/tb';

/**
 * Skill ID → react-icons 图标映射
 * 优先使用品牌图标（Si*），通用类使用 Tabler Icons（Tb*）
 * 未命中的 skill 使用后端返回的 emoji 作为兜底
 */
const SKILL_ICON_MAP: Record<string, IconType> = {
  'java-backend': SiOpenjdk,
  'frontend': SiReact,
  'python-backend': SiPython,
  'bytedance-backend': SiBytedance,
  'ali-backend': SiAlibabadotcom,
  'algorithm': TbMathFunction,
  'system-design': TbTopologyStarRing3,
  'test-development': TbTestPipe,
  'ai-agent-dev': TbRobot,
  'custom': TbSparkles,
};

/**
 * 根据 skillId 获取对应的 react-icons 图标组件
 * 返回 null 表示未命中，调用方应使用后端返回的 emoji 兜底
 */
export function getSkillIcon(skillId: string): IconType | null {
  return SKILL_ICON_MAP[skillId] ?? null;
}
