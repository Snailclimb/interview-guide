import type { SkillDTO } from '../api/skill';

export function getTemplateName(skillId: string, skills: SkillDTO[]): string {
  return skills.find(s => s.id === skillId)?.name || skillId;
}
