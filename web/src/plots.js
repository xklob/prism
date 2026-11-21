function plotSurface(canvas) {
  const w = canvas.clientWidth,
    h = canvas.clientHeight;
  if (!w || !h) return;
  const ratio = Math.min(devicePixelRatio || 1, 2);
  const width = Math.round(w * ratio),
    height = Math.round(h * ratio);
  if (canvas.width !== width || canvas.height !== height) {
    canvas.width = width;
    canvas.height = height;
  }
  const ctx = canvas.getContext('2d');
  ctx.setTransform(ratio, 0, 0, ratio, 0, 0);
  ctx.clearRect(0, 0, w, h);
  return { ctx, w, h, style: getComputedStyle(canvas) };
}
export function drawWave(canvas, track, at) {
  const surface = plotSurface(canvas);
  if (!surface || !track) return;
  const { ctx, w, h, style } = surface,
    mid = h / 2;
  ctx.fillStyle = style.getPropertyValue('--muted');
  for (let x = 0; x < w; x += 2) {
    const first = Math.floor((x * track.peaks.length) / w);
    const last = Math.min(track.peaks.length, Math.ceil(((x + 2) * track.peaks.length) / w));
    let peak = 0;
    for (let i = first; i < last; i++) peak = Math.max(peak, track.peaks[i]);
    const size = Math.max(1, Math.sqrt(peak) * mid);
    ctx.fillRect(x, mid - size, 1, 2 * size);
  }
  for (const marker of track.markers) {
    const x = (marker.time / track.duration) * w;
    ctx.fillStyle =
      marker.kind === 2 ? '#f28bc8' : marker.kind === 1 ? '#4da7db' : style.getPropertyValue('--muted');
    const tick = [4, 10, 16][marker.kind] || 4;
    ctx.fillRect(x, h - tick, marker.kind === 2 ? 2 : 1, tick);
  }
  ctx.fillStyle = style.getPropertyValue('--green');
  ctx.fillRect((at / track.duration) * w, 0, 2, h);
}
export class Diagnostics {
  rows = [];
  frozen = null;
  clear() {
    this.rows = [];
    this.frozen = null;
  }
  add({ rhythm: r, phrase: p, matchErrorMs }) {
    if (this.rows.at(-1)?.time >= r.timestamp) return;
    this.rows.push({
      time: r.timestamp,
      position: r.position,
      locked: r.locked,
      beat: r.beatProbability,
      down: r.downbeatProbability,
      confidence: r.confidence,
      bar: r.barConfidence,
      phrase: p.confidence,
      bpm: r.bpm,
      error: matchErrorMs,
      status: [
        r.manualTempo ? 'Manual tempo' : '',
        r.manualBar ? 'Manual bar' : '',
        p.manual ? 'Manual phrase' : '',
        r.guided ? 'Guided' : '',
        r.holding ? 'Holding' : '',
        r.coasting ? 'Coasting' : '',
        r.conflict !== 'NONE' ? r.conflict : '',
      ]
        .filter(Boolean)
        .join(' · '),
    });
    while (this.rows.length > 3000 || r.timestamp - this.rows[0].time > 60) this.rows.shift();
  }
  draw(canvas, windowSeconds) {
    const rows = this.frozen || this.rows;
    const surface = plotSurface(canvas);
    if (!surface || !rows.length) return;
    const { ctx, w, h, style } = surface,
      end = rows.at(-1).time,
      start = end - windowSeconds,
      data = rows.filter((row) => row.time >= start);
    const half = h / 2,
      x = (r) => ((r.time - start) / windowSeconds) * w;
    ctx.fillStyle = style.getPropertyValue('--rule');
    ctx.fillRect(0, half, w, 1);
    let beat;
    for (const r of data) {
      const n = Math.floor(r.position);
      if (r.locked && n !== beat) ctx.fillRect(x(r), 0, 1, half);
      beat = n;
    }
    const line = (key, color, y) => {
      ctx.beginPath();
      ctx.strokeStyle = color;
      ctx.lineWidth = 1.5;
      data.forEach((r, i) => {
        const level = y + half - 2 - r[key] * (half - 5);
        if (i) ctx.lineTo(x(r), level);
        else ctx.moveTo(x(r), level);
      });
      ctx.stroke();
    };
    line('beat', style.getPropertyValue('--green'), 0);
    line('down', '#db76ae', 0);
    line('confidence', style.getPropertyValue('--green'), half);
    line('bar', '#619edf', half);
    line('phrase', '#db76ae', half);
  }
}
