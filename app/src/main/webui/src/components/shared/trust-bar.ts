import { html, nothing, type TemplateResult } from 'lit';
import { trustFillClass } from './helpers.js';

export interface TrustBarData {
  score: number;
  thresholds?: Array<{ value: number; label: string }>;
  observationCount?: number;
}

export function renderTrustBar(data: TrustBarData): TemplateResult {
  const pct = Math.round(data.score * 100);
  return html`
    <div class="trust-bar">
      <div class="trust-track">
        <div class="trust-fill ${trustFillClass(data.score)}" style="width:${pct}%"></div>
        ${(data.thresholds ?? []).map(t => html`
          <div class="trust-threshold" style="left:${Math.round(t.value * 100)}%"
               title="${t.label} (${Math.round(t.value * 100)}%)"></div>
        `)}
      </div>
      <div class="trust-value">${pct}%</div>
    </div>
    ${data.thresholds || data.observationCount != null ? html`
      <div class="threshold-legend">
        ${(data.thresholds ?? []).map(t => html`
          <span>▮ ${Math.round(t.value * 100)}% ${t.label}</span>
        `)}
        ${data.observationCount != null ? html`
          <span>${data.observationCount} observations</span>
        ` : nothing}
      </div>
    ` : nothing}
  `;
}
