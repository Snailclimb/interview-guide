import { request } from './request';

export interface CategoryDTO {
  key: string;
  label: string;
  priority: 'CORE' | 'NORMAL' | 'ALWAYS_ONE';
  ref?: string;
  shared?: boolean;
}

export interface DisplayDTO {
  icon: string;
  gradient: string;
  iconBg: string;
  iconColor: string;
}

export interface SkillDTO {
  id: string;
  name: string;
  description: string;
  categories: CategoryDTO[];
  isPreset: boolean;
  sourceJd: string | null;
  persona?: string;
  display?: DisplayDTO;
}

export const skillApi = {
  async listSkills(): Promise<SkillDTO[]> {
    return request.get<SkillDTO[]>('/api/interview/skills');
  },

  async getSkill(id: string): Promise<SkillDTO> {
    return request.get<SkillDTO>(`/api/interview/skills/${id}`);
  },

  async parseJd(jdText: string): Promise<CategoryDTO[]> {
    return request.post<CategoryDTO[]>('/api/interview/skills/parse-jd', { jdText });
  },
};
