/**
 * Start a browser download for a Blob.
 *
 * The object URL must stay alive until the browser has started consuming the
 * anchor click. Some browsers race an immediate revokeObjectURL call and
 * silently discard the download, so cleanup is deferred to the next turn.
 */
export function downloadBlob(blob: Blob, filename: string): void {
  const url = window.URL.createObjectURL(blob);
  const anchor = document.createElement('a');

  anchor.href = url;
  anchor.download = filename;
  anchor.style.display = 'none';
  document.body.appendChild(anchor);

  try {
    anchor.click();
  } finally {
    anchor.remove();
    window.setTimeout(() => window.URL.revokeObjectURL(url), 1000);
  }
}
