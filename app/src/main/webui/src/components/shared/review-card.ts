import { html, nothing, type TemplateResult } from 'lit';
import { feedbackClass, feedbackLabel } from './helpers.js';

export interface ReviewOutcome {
  caseId: string;
  capability: string;
  outcome: string;
  timestamp: string;
  pr?: { repo: string; prNumber: number; contributor: string };
  findingCount?: number;
  findingSummary?: string | null;
  feedbackOutcome?: string | null;
  declineReason?: string | null;
}

export function renderReviewCard(o: ReviewOutcome): TemplateResult {
  const pr = o.pr;
  return html`
    <div class="review-card">
      <div class="review-card-header">
        <span class="review-pr">${pr ? `PR #${pr.prNumber} — ${pr.repo}` : o.caseId.substring(0, 8)}</span>
        <span class="review-meta">${new Date(o.timestamp).toLocaleDateString()}</span>
      </div>
      ${pr ? html`<div class="review-capability">${o.capability.replace(/-/g, ' ')} · by ${pr.contributor}</div>` : nothing}
      ${o.outcome === 'DECLINED' && o.declineReason ? html`
        <div class="decline-reason">Declined: ${o.declineReason}</div>
      ` : nothing}
      ${o.findingCount != null && o.findingCount > 0 ? html`
        <div class="review-findings">${o.findingCount} finding${o.findingCount > 1 ? 's' : ''}: ${o.findingSummary}</div>
      ` : o.outcome === 'DONE' ? html`
        <div class="review-findings" style="color:var(--pages-neutral-6,#737373)">No findings — clean review</div>
      ` : nothing}
      ${o.feedbackOutcome ? html`
        <div class="review-feedback">
          <span class="feedback-badge ${feedbackClass(o.feedbackOutcome)}">${feedbackLabel(o.feedbackOutcome)}</span>
        </div>
      ` : nothing}
    </div>
  `;
}
