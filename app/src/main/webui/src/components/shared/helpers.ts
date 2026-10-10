export function trustFillClass(score: number): 'high' | 'mid' | 'low' | 'very-low' {
  if (score >= 0.80) return 'high';
  if (score >= 0.60) return 'mid';
  if (score >= 0.40) return 'low';
  return 'very-low';
}

export function formatDimension(key: string): string {
  return key.replace(/-/g, ' ').replace(/\b\w/g, c => c.toUpperCase());
}

export function feedbackClass(fb: string): 'accepted' | 'rejected' | 'partial' {
  const lower = fb.toLowerCase();
  if (lower.includes('accept')) return 'accepted';
  if (lower.includes('reject')) return 'rejected';
  return 'partial';
}

export function feedbackLabel(fb: string): string {
  if (fb === 'ACCEPTED') return 'Findings accepted';
  if (fb === 'REJECTED') return 'Findings rejected (false positives)';
  if (fb === 'PARTIALLY_ACCEPTED') return 'Some findings accepted';
  return fb;
}
