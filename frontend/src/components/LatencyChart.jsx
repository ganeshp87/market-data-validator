import React, { useRef, useEffect, useCallback } from 'react';
import useSSE from '../hooks/useSSE';

/**
 * LatencyChart — Live line chart showing p50/p95/p99 over time.
 *
 * Blueprint Section 6.2:
 *   Three lines: p50 (green), p95 (yellow), p99 (red)
 *   X-axis: time (last 5 minutes, scrolling)
 *   Y-axis: milliseconds
 *   Horizontal threshold line at configured SLA (e.g., 500ms)
 *   Uses canvas (no heavy chart library)
 *   Updates every second via /api/stream/latency SSE
 */

const MAX_POINTS = 300;          // 5 minutes × 60 sec = 300 data points
const SLA_THRESHOLD_MS = 500;    // Horizontal SLA line

const COLORS = {
  p50: '#4caf50',   // green
  p95: '#ff9800',   // yellow/amber
  p99: '#f44336',   // red
  sla: 'rgba(255, 255, 255, 0.3)',
  grid: 'rgba(255, 255, 255, 0.08)',
  text: 'rgba(255, 255, 255, 0.6)',
  bg: '#1a1a2e',
};

export default function LatencyChart() {
  const canvasRef = useRef(null);
  const pointsRef = useRef([]);   // { time, p50, p95, p99 }[]

  const { latest, connected } = useSSE(
    '/api/stream/latency',
    'latency',
    { maxItems: 1 }
  );

  // Append new data point when latest changes
  useEffect(() => {
    if (!latest || latest.p50 == null) return;

    const point = {
      time: Date.now(),
      p50: Number(latest.p50),
      p95: Number(latest.p95),
      p99: Number(latest.p99),
    };

    const pts = pointsRef.current;
    pts.push(point);
    if (pts.length > MAX_POINTS) {
      pts.splice(0, pts.length - MAX_POINTS);
    }
  }, [latest]);

  // Draw the chart on every animation frame
  const drawChart = useCallback(() => {
    const canvas = canvasRef.current;
    if (!canvas) return;

    const ctx = canvas.getContext('2d');
    const { width, height } = canvas;
    const pts = pointsRef.current;

    // Clear
    ctx.fillStyle = COLORS.bg;
    ctx.fillRect(0, 0, width, height);

    const PADDING = { top: 20, right: 20, bottom: 40, left: 60 };
    const chartW = width - PADDING.left - PADDING.right;
    const chartH = height - PADDING.top - PADDING.bottom;

    // Determine Y scale — auto-scale to max value, minimum 100ms
    let yMax = SLA_THRESHOLD_MS;
    for (const pt of pts) {
      if (pt.p99 > yMax) yMax = pt.p99;
    }
    yMax = Math.ceil(yMax * 1.2 / 100) * 100; // Round up to nearest 100, add 20% headroom
    if (yMax < 100) yMax = 100;

    // --- Grid lines & Y labels ---
    const ySteps = 5;
    ctx.strokeStyle = COLORS.grid;
    ctx.fillStyle = COLORS.text;
    ctx.font = '11px monospace';
    ctx.textAlign = 'right';
    ctx.textBaseline = 'middle';

    for (let i = 0; i <= ySteps; i++) {
      const val = (yMax / ySteps) * i;
      const y = PADDING.top + chartH - (val / yMax) * chartH;
      // Grid line
      ctx.beginPath();
      ctx.moveTo(PADDING.left, y);
      ctx.lineTo(PADDING.left + chartW, y);
      ctx.stroke();
      // Label
      ctx.fillText(`${Math.round(val)}ms`, PADDING.left - 8, y);
    }

    // --- SLA threshold line ---
    const slaY = PADDING.top + chartH - (SLA_THRESHOLD_MS / yMax) * chartH;
    ctx.strokeStyle = COLORS.sla;
    ctx.setLineDash([6, 4]);
    ctx.beginPath();
    ctx.moveTo(PADDING.left, slaY);
    ctx.lineTo(PADDING.left + chartW, slaY);
    ctx.stroke();
    ctx.setLineDash([]);
    // SLA label
    ctx.fillStyle = COLORS.sla;
    ctx.textAlign = 'left';
    ctx.fillText('SLA', PADDING.left + chartW + 4, slaY);

    // --- Time labels on X axis ---
    if (pts.length > 1) {
      ctx.fillStyle = COLORS.text;
      ctx.textAlign = 'center';
      ctx.textBaseline = 'top';

      const xLabelCount = Math.min(5, pts.length);
      for (let i = 0; i < xLabelCount; i++) {
        const idx = Math.floor((i / (xLabelCount - 1)) * (pts.length - 1));
        const x = PADDING.left + (idx / (pts.length - 1)) * chartW;
        const d = new Date(pts[idx].time);
        const label = `${String(d.getHours()).padStart(2, '0')}:${String(d.getMinutes()).padStart(2, '0')}:${String(d.getSeconds()).padStart(2, '0')}`;
        ctx.fillText(label, x, PADDING.top + chartH + 8);
      }
    }

    // --- Draw data lines ---
    if (pts.length < 2) {
      ctx.fillStyle = COLORS.text;
      ctx.textAlign = 'center';
      ctx.textBaseline = 'middle';
      ctx.font = '14px sans-serif';
      ctx.fillText(
        connected ? 'Waiting for data…' : 'Disconnected',
        width / 2,
        height / 2,
      );
      return;
    }

    function drawLine(key, color) {
      ctx.strokeStyle = color;
      ctx.lineWidth = 2;
      ctx.beginPath();
      for (let i = 0; i < pts.length; i++) {
        const x = PADDING.left + (i / (pts.length - 1)) * chartW;
        const y = PADDING.top + chartH - (pts[i][key] / yMax) * chartH;
        if (i === 0) ctx.moveTo(x, y);
        else ctx.lineTo(x, y);
      }
      ctx.stroke();
    }

    drawLine('p50', COLORS.p50);
    drawLine('p95', COLORS.p95);
    drawLine('p99', COLORS.p99);

    // --- Legend ---
    const legendY = PADDING.top + 4;
    const legendItems = [
      { label: `p50: ${pts[pts.length - 1].p50}ms`, color: COLORS.p50 },
      { label: `p95: ${pts[pts.length - 1].p95}ms`, color: COLORS.p95 },
      { label: `p99: ${pts[pts.length - 1].p99}ms`, color: COLORS.p99 },
    ];

    ctx.font = '12px monospace';
    ctx.textAlign = 'left';
    ctx.textBaseline = 'top';
    let legendX = PADDING.left + 8;
    for (const item of legendItems) {
      ctx.fillStyle = item.color;
      ctx.fillRect(legendX, legendY, 12, 12);
      ctx.fillStyle = '#fff';
      ctx.fillText(item.label, legendX + 16, legendY);
      legendX += ctx.measureText(item.label).width + 32;
    }
  }, [connected]);

  // Animation loop
  useEffect(() => {
    let animId;
    function loop() {
      drawChart();
      animId = requestAnimationFrame(loop);
    }
    animId = requestAnimationFrame(loop);
    return () => cancelAnimationFrame(animId);
  }, [drawChart]);

  return (
    <div className="latency-chart" data-testid="latency-chart">
      <h2>Latency — p50 / p95 / p99</h2>
      <div className="lc-status">
        <span className={`status-dot ${connected ? 'green' : 'red'}`} />
        <span>{connected ? 'Live' : 'Disconnected'}</span>
        <span className="lc-points">{pointsRef.current.length} samples</span>
      </div>
      <canvas
        ref={canvasRef}
        width={900}
        height={400}
        className="lc-canvas"
        data-testid="latency-canvas"
      />
    </div>
  );
}
