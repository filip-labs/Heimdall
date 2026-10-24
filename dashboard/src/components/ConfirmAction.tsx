import { useEffect, useRef } from "react";
export function ConfirmAction({
  title,
  description,
  busy,
  onConfirm,
  onClose,
}: {
  title: string;
  description: string;
  busy: boolean;
  onConfirm: () => void;
  onClose: () => void;
}) {
  const ref = useRef<HTMLDialogElement>(null);
  useEffect(() => {
    const dialog = ref.current;
    dialog?.showModal();
    return () => dialog?.close();
  }, []);
  return (
    <dialog
      ref={ref}
      className="confirm-dialog"
      onCancel={(e) => {
        e.preventDefault();
        if (!busy) onClose();
      }}
      aria-labelledby="confirm-title"
    >
      <p className="eyebrow">OPERATOR ACTION</p>
      <h2 id="confirm-title">{title}</h2>
      <div className="accent-rule" />
      <p>{description}</p>
      <div className="form-actions">
        <button className="light" disabled={busy} onClick={onClose}>
          Keep state
        </button>
        <button className="accent" disabled={busy} onClick={onConfirm}>
          {busy ? "Applying…" : title}
        </button>
      </div>
    </dialog>
  );
}
