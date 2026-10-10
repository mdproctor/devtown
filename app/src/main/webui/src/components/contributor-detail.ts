import { LitElement, html, css, nothing } from 'lit';
import { customElement, property, state } from 'lit/decorators.js';
import { renderTrustBar } from './shared/trust-bar.js';
import { renderDimensionGrid } from './shared/dimension-grid.js';
import { renderStatusCard } from './shared/status-card.js';
import { renderBadge } from './shared/badge.js';
import type { DimensionEntry } from './shared/types.js';
import { hostStyles, sectionTitleStyles, narrativeStyles, emptyStyles, trustBarStyles, dimensionGridStyles, statusCardStyles, badgeStyles } from './shared/shared-styles.js';

interface ContributorProfile {
  actorId: string;
  globalScore: number | null;
  capabilityScores: Record<string, number>;
  dimensionScores: Record<string, number>;
  intakeClassification: {
    lane: string; trustScore: number; observationCount: number;
    classificationReason: string; fastTrackThreshold: number; standardThreshold: number;
  };
  recentOutcomes: Array<{ caseId: string; capability: string; outcome: string; timestamp: string }>;
  githubIntelligence: {
    mergedCount: number; closedCount: number; mergeRatio: number;
    tier: string; maturity: string; lastRefreshAt: string; available: boolean; summary: string;
  } | null;
}

@customElement('devtown-contributor-detail')
export class ContributorDetail extends LitElement {
  @property({ type: String }) endpoint = '';
  @property({ type: String, attribute: 'actor-id' }) actorId = '';

  @state() private _data: ContributorProfile | null = null;
  @state() private _loading = false;
  @state() private _prevActorId = '';

  static override styles = [
    hostStyles, sectionTitleStyles, narrativeStyles, emptyStyles,
    trustBarStyles, dimensionGridStyles, statusCardStyles, badgeStyles,
    css`
      .sub-header { font-size: 13px; color: var(--pages-neutral-7, #525252); margin-bottom: 16px; }
      .outcome-list { margin-top: 6px; }
      .outcome-item { display: flex; gap: 12px; align-items: baseline; padding: 4px 0; font-size: 12px; border-bottom: 1px solid var(--pages-neutral-2, #f5f5f5); }
      .outcome-item:last-child { border-bottom: none; }
      .outcome-result { font-weight: 600; min-width: 60px; }
      .outcome-result.merged { color: var(--pages-success-9, #166534); }
      .outcome-result.closed { color: var(--pages-danger-9, #dc2626); }
      .outcome-date { color: var(--pages-neutral-6, #737373); }
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
    try {
      const res = await fetch(`${this.endpoint}/contributors/${this.actorId}`);
      if (res.ok) this._data = await res.json();
    } catch { /* unavailable */ }
    this._loading = false;
  }

  private _buildSummary(d: ContributorProfile): string {
    const ic = d.intakeClassification;
    const gh = d.githubIntelligence;
    const total = gh ? gh.mergedCount + gh.closedCount : ic.observationCount;
    const parts: string[] = [];

    if (gh && total > 0) {
      parts.push(`${d.actorId} has submitted ${total} PRs — ${gh.mergedCount} merged, ${gh.closedCount} closed (${Math.round(gh.mergeRatio * 100)}% merge rate).`);
    } else {
      parts.push(`${d.actorId} has ${ic.observationCount} observed PRs.`);
    }

    const mr = d.dimensionScores['merge-rate'];
    const faq = d.dimensionScores['first-attempt-quality'];
    if (mr != null && faq != null) {
      const mrPct = Math.round(mr * 100);
      const faPct = Math.round(faq * 100);
      if (mrPct >= 90 && faPct >= 80) {
        parts.push(`Strong contributor — ${mrPct}% merge rate with ${faPct}% passing on first attempt.`);
      } else if (mrPct >= 70) {
        parts.push(`Merge rate is ${mrPct}%, but only ${faPct}% pass on first attempt — some PRs need revision.`);
      } else {
        parts.push(`Merge rate (${mrPct}%) and first-attempt quality (${faPct}%) suggest PRs frequently need rework.`);
      }
    }

    return parts.join(' ');
  }

  private _buildProximity(d: ContributorProfile): { text: string; level: 'stable' | 'watch' | 'risk' } {
    const ic = d.intakeClassification;
    const score = ic.trustScore;
    const ft = ic.fastTrackThreshold;
    const std = ic.standardThreshold;
    const lane = ic.lane.toLowerCase();
    const scorePct = Math.round(score * 100);
    const ftPct = Math.round(ft * 100);
    const stdPct = Math.round(std * 100);

    if (lane.includes('fast')) {
      const margin = Math.round((score - ft) * 100);
      if (margin > 10) {
        return { text: `Comfortably in fast-track — ${margin} points above the ${ftPct}% threshold. No action needed.`, level: 'stable' };
      }
      return { text: `In fast-track but only ${margin} points above the ${ftPct}% threshold. A few rejected PRs could demote to standard review.`, level: 'watch' };
    }
    if (lane.includes('enhanced')) {
      const gap = Math.round((std - score) * 100);
      if (gap > 15) {
        return { text: `${gap} points below the standard threshold (${stdPct}%). Needs a sustained run of clean merges to move up.`, level: 'risk' };
      }
      return { text: `${gap} points below standard threshold (${stdPct}%). Close to promotion — ${Math.ceil(gap / 5)} more clean merges could move this contributor to standard lane.`, level: 'watch' };
    }
    // Standard lane
    const toFt = Math.round((ft - score) * 100);
    const aboveStd = Math.round((score - std) * 100);
    if (toFt <= 10) {
      return { text: `${toFt} points below fast-track (${ftPct}%). Close to promotion — a few more clean merges could earn fast-track status.`, level: 'watch' };
    }
    if (aboveStd <= 10) {
      return { text: `Only ${aboveStd} points above the enhanced-review threshold (${stdPct}%). At risk of demotion if quality drops.`, level: 'watch' };
    }
    return { text: `Solidly in standard lane — ${scorePct}% trust, ${toFt} points from fast-track, ${aboveStd} points above enhanced-review.`, level: 'stable' };
  }

  private _buildTrend(outcomes: Array<{ outcome: string }>): string {
    if (outcomes.length === 0) return 'No recent activity to assess trend.';
    const recent = outcomes.slice(0, 5);
    const merged = recent.filter(o => o.outcome === 'MERGED').length;
    const total = recent.length;

    if (merged === total) return `Last ${total} PRs all merged — consistent quality, trust score likely stable or rising.`;
    if (merged === 0) return `Last ${total} PRs all closed/rejected — trust score is declining. Consider whether this contributor needs support.`;
    const rate = Math.round((merged / total) * 100);
    return `Last ${total} PRs: ${merged} merged, ${total - merged} closed (${rate}% success). Mixed recent record — trust score may be shifting.`;
  }

  override render() {
    if (!this.actorId) return html`<div class="empty">Select a contributor to view details</div>`;
    if (this._loading && !this._data) return html`<div class="empty">Loading...</div>`;
    if (!this._data) return html`<div class="empty">No data for ${this.actorId}</div>`;

    const d = this._data;
    const ic = d.intakeClassification;
    const proximity = this._buildProximity(d);

    return html`
      <div class="header">${d.actorId} ${renderBadge(ic.lane, ic.lane === 'FAST_TRACK' ? 'fast-track' : ic.lane === 'ENHANCED_REVIEW' ? 'enhanced' : 'standard')}</div>

      <div class="narrative">${this._buildSummary(d)}</div>

      <div class="section-title">Lane Position</div>
      ${renderTrustBar({
        score: ic.trustScore,
        thresholds: [
          { value: ic.standardThreshold, label: 'standard' },
          { value: ic.fastTrackThreshold, label: 'fast-track' },
        ],
        observationCount: ic.observationCount,
      })}

      ${renderStatusCard({
        tier: proximity.level === 'stable' ? 'good' : proximity.level === 'watch' ? 'watch' : 'concern',
        text: proximity.text,
      })}

      ${Object.keys(d.dimensionScores).length > 0 ? html`
        <div class="section-title">Quality Dimensions</div>
        ${renderDimensionGrid(
          Object.entries(d.dimensionScores).map(([key, value]): DimensionEntry => ({ key, value: value as number }))
        )}
      ` : nothing}

      ${d.recentOutcomes.length > 0 ? html`
        <div class="section-title">Trend</div>
        <div class="narrative">${this._buildTrend(d.recentOutcomes)}</div>
        <div class="outcome-list">
          ${d.recentOutcomes.slice(0, 5).map(o => html`
            <div class="outcome-item">
              <span class="outcome-result ${o.outcome === 'MERGED' ? 'merged' : 'closed'}">${o.outcome}</span>
              <span class="outcome-date">${new Date(o.timestamp).toLocaleDateString()}</span>
            </div>
          `)}
        </div>
      ` : html`
        <div class="section-title">Trend</div>
        <div class="narrative">No recent PR outcomes recorded yet.</div>
      `}
    `;
  }
}
