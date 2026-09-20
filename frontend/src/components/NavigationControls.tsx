import type { JSX } from 'react';

interface NavigationControlsProps {
  disabled?: boolean;
  onZoomIn: () => void;
  onZoomOut: () => void;
  onReset: () => void;
}

export function NavigationControls({
  disabled = false,
  onZoomIn,
  onZoomOut,
  onReset,
}: NavigationControlsProps): JSX.Element {
  return (
    <div className="navigation-controls" aria-label="Управление масштабом">
      <button type="button" aria-label="Приблизить" title="Приблизить" disabled={disabled} onClick={onZoomIn}>+</button>
      <button type="button" aria-label="Отдалить" title="Отдалить" disabled={disabled} onClick={onZoomOut}>−</button>
      <button type="button" aria-label="Центрировать" title="Центрировать" disabled={disabled} onClick={onReset}>⌖</button>
    </div>
  );
}
