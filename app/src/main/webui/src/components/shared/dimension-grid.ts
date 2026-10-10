import { html, type TemplateResult } from 'lit';
import { formatDimension } from './helpers.js';

export interface DimensionEntry {
  key: string;
  value: number;
}

export function renderDimensionGrid(
  dimensions: DimensionEntry[],
  options?: { columns?: 2 | 3 },
): TemplateResult {
  const cols = options?.columns ?? 2;
  return html`
    <div class="dim-grid cols-${cols}">
      ${dimensions.map(d => html`
        <div class="dim-item">
          <span class="dim-label">${formatDimension(d.key)}</span>
          <span class="dim-val">${typeof d.value === 'number' ? Math.round(d.value * 100) + '%' : '—'}</span>
        </div>
      `)}
    </div>
  `;
}
