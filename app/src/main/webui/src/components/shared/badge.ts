import { html, type TemplateResult } from 'lit';

export type BadgeVariant = 'fast-track' | 'standard' | 'enhanced' | 'active' | 'emerging' | 'bootstrap';

export function renderBadge(label: string, variant: BadgeVariant): TemplateResult {
  return html`<span class="badge ${variant}">${label}</span>`;
}
