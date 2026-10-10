import { css } from 'lit';

export const hostStyles = css`
  :host { display: block; height: 100%; overflow-y: auto; padding: 16px;
    font-family: var(--pages-font-family, system-ui); }
  .header { font-size: 18px; font-weight: 600; margin-bottom: 8px; }
`;

export const sectionTitleStyles = css`
  .section-title {
    font-size: 13px; font-weight: 600; margin: 16px 0 6px;
    color: var(--pages-neutral-9, #404040);
    text-transform: uppercase; letter-spacing: 0.5px;
  }
`;

export const narrativeStyles = css`
  .narrative {
    font-size: 13px; line-height: 1.5;
    color: var(--pages-neutral-8, #404040); margin: 6px 0 12px;
  }
`;

export const emptyStyles = css`
  .empty {
    display: flex; align-items: center; justify-content: center;
    height: 100%; color: var(--pages-neutral-7, #525252); font-size: 13px;
  }
`;

export const trustBarStyles = css`
  .trust-bar { display: flex; align-items: center; gap: 8px; margin: 8px 0 4px; }
  .trust-track {
    flex: 1; height: 8px;
    background: var(--pages-neutral-3, #e5e5e5);
    border-radius: 4px; overflow: hidden; position: relative;
  }
  .trust-fill { height: 100%; border-radius: 4px; transition: width 0.3s; }
  .trust-fill.high { background: var(--pages-success-9, #16a34a); }
  .trust-fill.mid { background: var(--pages-primary-9, #1d4ed8); }
  .trust-fill.low { background: var(--pages-warning-9, #d97706); }
  .trust-fill.very-low { background: var(--pages-danger-9, #dc2626); }
  .trust-threshold {
    position: absolute; top: -2px; bottom: -2px; width: 2px;
    background: var(--pages-neutral-7, #525252);
  }
  .trust-value { font-size: 13px; font-weight: 600; min-width: 40px; }
  .threshold-legend {
    display: flex; gap: 12px; font-size: 11px;
    color: var(--pages-neutral-6, #737373); margin: 4px 0 8px;
  }
`;

export const dimensionGridStyles = css`
  .dim-grid { display: grid; gap: 6px; margin-top: 6px; }
  .dim-grid.cols-2 { grid-template-columns: 1fr 1fr; }
  .dim-grid.cols-3 { grid-template-columns: 1fr 1fr 1fr; }
  .dim-item {
    font-size: 12px; padding: 4px 8px;
    background: var(--pages-neutral-2, #f5f5f5); border-radius: 3px;
  }
  .dim-grid.cols-2 .dim-item { display: flex; justify-content: space-between; }
  .dim-grid.cols-3 .dim-item { text-align: center; padding: 6px 8px; }
  .dim-label { color: var(--pages-neutral-8, #404040); }
  .dim-grid.cols-3 .dim-label {
    display: block; font-size: 10px; text-transform: uppercase;
    margin-bottom: 2px; color: var(--pages-neutral-7, #525252);
  }
  .dim-val { font-weight: 600; }
  .dim-grid.cols-3 .dim-val { font-size: 16px; }
`;

export const statusCardStyles = css`
  .status-card {
    margin: 10px 0; padding: 10px 14px; border-radius: 6px;
    font-size: 13px; line-height: 1.5; border-left: 3px solid;
  }
  .status-card.good {
    background: var(--pages-success-2, #f0fdf4);
    border-color: var(--pages-success-9, #16a34a);
  }
  .status-card.watch {
    background: var(--pages-warning-2, #fffbeb);
    border-color: var(--pages-warning-9, #d97706);
  }
  .status-card.concern {
    background: var(--pages-danger-2, #fef2f2);
    border-color: var(--pages-danger-9, #dc2626);
  }
`;

export const reviewCardStyles = css`
  .review-card {
    margin: 8px 0; padding: 10px 14px; border-radius: 6px;
    border: 1px solid var(--pages-neutral-3, #e5e5e5);
    font-size: 12px; line-height: 1.5;
  }
  .review-card-header {
    display: flex; justify-content: space-between;
    align-items: center; margin-bottom: 4px;
  }
  .review-pr { font-weight: 600; font-size: 13px; }
  .review-meta { color: var(--pages-neutral-6, #737373); font-size: 11px; }
  .review-capability { color: var(--pages-neutral-7, #525252); }
  .review-findings { margin-top: 6px; color: var(--pages-neutral-8, #404040); }
  .review-feedback { margin-top: 4px; font-size: 11px; }
  .feedback-badge {
    display: inline-block; padding: 1px 6px; border-radius: 3px;
    font-size: 10px; font-weight: 600;
  }
  .feedback-badge.accepted {
    background: var(--pages-success-3, #dcfce7);
    color: var(--pages-success-9, #166534);
  }
  .feedback-badge.rejected {
    background: var(--pages-danger-3, #fee2e2);
    color: var(--pages-danger-9, #dc2626);
  }
  .feedback-badge.partial {
    background: var(--pages-warning-3, #fef3c7);
    color: var(--pages-warning-9, #92400e);
  }
  .decline-reason {
    margin-top: 4px; font-style: italic;
    color: var(--pages-neutral-7, #525252);
  }
`;

export const badgeStyles = css`
  .badge {
    display: inline-block; padding: 2px 8px; border-radius: 3px;
    font-size: 11px; font-weight: 600;
  }
  .badge.fast-track { background: var(--pages-success-3, #dcfce7); color: var(--pages-success-9, #166534); }
  .badge.standard { background: var(--pages-primary-3, #dbeafe); color: var(--pages-primary-9, #1d4ed8); }
  .badge.enhanced { background: var(--pages-warning-3, #fef3c7); color: var(--pages-warning-9, #92400e); }
  .badge.active { background: var(--pages-success-3, #dcfce7); color: var(--pages-success-9, #166534); }
  .badge.emerging { background: var(--pages-primary-3, #dbeafe); color: var(--pages-primary-9, #1d4ed8); }
  .badge.bootstrap { background: var(--pages-neutral-3, #e5e5e5); color: var(--pages-neutral-7, #525252); }
`;
