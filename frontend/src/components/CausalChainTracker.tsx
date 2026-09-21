import type { JSX } from 'react';
import type { CausalChainStep } from '../domain/types';

interface CausalChainTrackerProps {
  steps: readonly CausalChainStep[];
  emptyText?: string;
  onFocus: (step: CausalChainStep) => void;
}

export function CausalChainTracker({ steps, emptyText = 'Активных физических аварий нет.', onFocus }: CausalChainTrackerProps): JSX.Element {
  if (steps.length === 0) return <section className="causal-chain"><h3>Логика каскада событий</h3><p className="muted">{emptyText}</p></section>;
  return <section className="causal-chain"><h3>Логика каскада событий</h3><div className="causal-timeline">
    {steps.map((step, index) => <button type="button" className={`causal-step causal-${step.kind}`} key={step.id} onClick={() => onFocus(step)}>
      <span className="causal-index">{index + 1}</span><span><strong>{step.title}</strong><small>{step.detail}</small></span><span className="causal-target">{step.focus === 'graph' ? 'G6' : 'MAP'}</span>
    </button>)}
  </div></section>;
}
