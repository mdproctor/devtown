import { LitElement, html, css, nothing } from 'lit';
import { customElement, property, state } from 'lit/decorators.js';
import { trustFillClass } from './shared/helpers.js';
import { renderDimensionGrid } from './shared/dimension-grid.js';
import { renderStatusCard } from './shared/status-card.js';
import { renderReviewCard } from './shared/review-card.js';
import { renderBadge } from './shared/badge.js';
import type { DimensionEntry, ReviewOutcome } from './shared/types.js';
import { hostStyles, sectionTitleStyles, narrativeStyles, emptyStyles, dimensionGridStyles, statusCardStyles, reviewCardStyles, badgeStyles } from './shared/shared-styles.js';

interface ReviewerHealth {
  reviewerId: string;
  openCommitments: number;
  trustByCapability: Record<string, number>;
  trustByDimension: Record<string, number>;
  totalDecisions: number;
  recentOutcomes: ReviewOutcome[];
}

@customElement('devtown-reviewer-detail')
export class ReviewerDetail extends LitElement {
  @property({ type: String }) endpoint = '';
  @property({ type: String, attribute: 'actor-id' }) actorId = '';
  @property({ type: String, attribute: 'maturity-phase' }) maturityPhase = '';

  @state() private _data: ReviewerHealth | null = null;
  @state() private _loading = false;
  @state() private _prevActorId = '';

  static override styles = [
    hostStyles, sectionTitleStyles, narrativeStyles, emptyStyles,
    dimensionGridStyles, statusCardStyles, reviewCardStyles, badgeStyles,
    css`
      .sub-header { font-size: 12px; color: var(--pages-neutral-7, #525252); margin-bottom: 16px; }
      .cap-list { margin: 6px 0; }
      .cap-item { display: flex; align-items: center; gap: 10px; padding: 6px 0; border-bottom: 1px solid var(--pages-neutral-2, #f5f5f5); }
      .cap-item:last-child { border-bottom: none; }
      .cap-name { flex: 1; font-size: 13px; }
      .cap-bar { width: 120px; height: 6px; background: var(--pages-neutral-3, #e5e5e5); border-radius: 3px; overflow: hidden; }
      .cap-fill { height: 100%; border-radius: 3px; }
      .cap-fill.high { background: var(--pages-success-9, #16a34a); }
      .cap-fill.mid { background: var(--pages-primary-9, #1d4ed8); }
      .cap-fill.low { background: var(--pages-warning-9, #d97706); }
      .cap-fill.very-low { background: var(--pages-danger-9, #dc2626); }
      .cap-score { font-size: 13px; font-weight: 600; min-width: 36px; text-align: right; }
    `,
  ];

  override willUpdate(changed: Map<PropertyKey, unknown>): void {
    if (changed.has('actorId') && this.actorId && this.actorId !== this._prevActorId) {
      this._prevActorId = this.actorId;
      this._fetch();
    }
  }

  private async _fetch(): Promise<void> {
    if (!this.actorId) return;
    this._loading = true;
    this._data = null;
    try {
      const res = await fetch(`${this.endpoint}/reviewers/${this.actorId}`);
      if (res.ok) this._data = await res.json();
    } catch { /* unavailable */ }
    this._loading = false;
  }

  private _buildNarrative(d: ReviewerHealth): string {
    const caps = Object.keys(d.trustByCapability);
    if (caps.length === 0) return `${d.reviewerId} has no capability trust scores recorded yet.`;
    const capList = caps.map(c => c.replace(/-/g, ' ')).join(', ');
    const topCap = caps.reduce((a, b) => (d.trustByCapability[a] ?? 0) >= (d.trustByCapability[b] ?? 0) ? a : b, caps[0]);
    const topScore = Math.round((d.trustByCapability[topCap] ?? 0) * 100);

    const parts: string[] = [];
    parts.push(`${d.reviewerId} is qualified for ${caps.length} ${caps.length === 1 ? 'capability' : 'capabilities'}: ${capList}.`);

    if (this.maturityPhase === 'Bootstrap') {
      parts.push(`In bootstrap phase with only ${d.totalDecisions} decisions — trust scores are provisional and will stabilise as more reviews complete.`);
    } else if (this.maturityPhase === 'Emerging') {
      parts.push(`Emerging reviewer with ${d.totalDecisions} decisions. Strongest capability is ${topCap.replace(/-/g, ' ')} at ${topScore}%.`);
    } else {
      parts.push(`Active reviewer with ${d.totalDecisions} decisions across ${caps.length === 1 ? 'its capability' : 'all capabilities'}. Strongest at ${topCap.replace(/-/g, ' ')} (${topScore}%).`);
    }

    return parts.join(' ');
  }

  private _buildStatus(d: ReviewerHealth): { text: string; level: 'healthy' | 'busy' | 'concern' } {
    const hasLowTrust = Object.values(d.trustByCapability).some(v => v < 0.50);
    const recentDeclines = d.recentOutcomes.filter(o => o.outcome === 'DECLINED').length;

    if (d.openCommitments >= 3) {
      return { text: `${d.openCommitments} open commitments — this reviewer is heavily loaded. New assignments should prefer other agents.`, level: 'busy' };
    }
    if (hasLowTrust) {
      const lowCaps = Object.entries(d.trustByCapability).filter(([, v]) => v < 0.50).map(([k]) => k.replace(/-/g, ' '));
      return { text: `Trust score below 50% for ${lowCaps.join(', ')}. Routing should prefer higher-trust agents for these capabilities.`, level: 'concern' };
    }
    if (recentDeclines > 0) {
      return { text: `${recentDeclines} recent DECLINED outcome${recentDeclines > 1 ? 's' : ''} — this reviewer is turning down work. May indicate overload or capability mismatch.`, level: 'busy' };
    }
    if (d.openCommitments === 0) {
      return { text: `No open commitments — available for new assignments.`, level: 'healthy' };
    }
    return { text: `${d.openCommitments} open commitment${d.openCommitments > 1 ? 's' : ''}, trust scores healthy. Operating normally.`, level: 'healthy' };
  }

  override render() {
    if (!this.actorId) return html`<div class="empty">Select a reviewer to view details</div>`;
    if (this._loading && !this._data) return html`<div class="empty">Loading...</div>`;
    if (!this._data) return html`<div class="empty">No data for ${this.actorId}</div>`;

    const d = this._data;
    const status = this._buildStatus(d);

    return html`
      <div class="header">${d.reviewerId} ${renderBadge(this.maturityPhase, this.maturityPhase.toLowerCase() === 'active' ? 'active' : this.maturityPhase.toLowerCase() === 'emerging' ? 'emerging' : 'bootstrap')}</div>
      <div class="sub-header">${d.totalDecisions} decisions · ${d.openCommitments} open commitments</div>

      <div class="narrative">${this._buildNarrative(d)}</div>

      ${renderStatusCard({
        tier: status.level === 'healthy' ? 'good' : status.level === 'busy' ? 'watch' : 'concern',
        text: status.text,
      })}

      <div class="section-title">Capability Trust</div>
      <div class="cap-list">
        ${Object.entries(d.trustByCapability).map(([cap, score]) => html`
          <div class="cap-item">
            <span class="cap-name">${cap.replace(/-/g, ' ')}</span>
            <div class="cap-bar"><div class="cap-fill ${trustFillClass(score)}" style="width:${Math.round(score * 100)}%"></div></div>
            <span class="cap-score">${Math.round(score * 100)}%</span>
          </div>
        `)}
      </div>

      ${Object.keys(d.trustByDimension).length > 0 ? html`
        <div class="section-title">Quality Dimensions</div>
        ${renderDimensionGrid(
          Object.entries(d.trustByDimension).map(([key, value]): DimensionEntry => ({ key, value: value as number })),
          { columns: 3 },
        )}
      ` : nothing}

      <div class="section-title">Review History</div>
      ${d.recentOutcomes.length > 0 ? d.recentOutcomes.map(o => renderReviewCard(o)) : html`
        <div class="narrative">No recent review outcomes recorded.</div>
      `}
    `;
  }
}
