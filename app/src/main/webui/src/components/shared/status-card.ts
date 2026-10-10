import { html, type TemplateResult } from 'lit';

export type StatusTier = 'good' | 'watch' | 'concern';

export interface StatusCardData {
  tier: StatusTier;
  text: string;
}

export function renderStatusCard(data: StatusCardData): TemplateResult {
  return html`<div class="status-card ${data.tier}">${data.text}</div>`;
}
