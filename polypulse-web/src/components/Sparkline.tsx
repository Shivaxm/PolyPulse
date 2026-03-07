import { memo } from 'react';

interface SparklineProps {
  data: number[];
  width?: number;
  height?: number;
  color?: string;
  id?: string | number;
}

const Sparkline = memo(function Sparkline({
  data,
  width = 80,
  height = 36,
  color = '#6366f1',
  id = 'default',
}: SparklineProps) {
  if (data.length < 2) {
    const y = height / 2;
    return (
      <svg width={width} height={height} viewBox={`0 0 ${width} ${height}`}>
        <line x1={0} y1={y} x2={width} y2={y} stroke={color} strokeWidth={1} strokeOpacity={0.4} strokeDasharray="2 2" />
      </svg>
    );
  }

  const min = Math.min(...data);
  const max = Math.max(...data);
  const range = max - min || 0.01;
  const pad = 2;

  const points = data.map((value, i) => {
    const x = (i / (data.length - 1)) * width;
    const y = pad + ((max - value) / range) * (height - pad * 2);
    return `${x.toFixed(1)},${y.toFixed(1)}`;
  });

  const polylinePoints = points.join(' ');
  const fillPoints = `0,${height} ${polylinePoints} ${width},${height}`;
  const uid = `spark-${id}`;

  return (
    <svg width={width} height={height} viewBox={`0 0 ${width} ${height}`} style={{ flexShrink: 0 }}>
      <defs>
        <linearGradient id={`${uid}-fill`} x1="0" y1="0" x2="0" y2="1">
          <stop offset="0%" stopColor={color} stopOpacity={0.2} />
          <stop offset="100%" stopColor={color} stopOpacity={0} />
        </linearGradient>
      </defs>
      <polygon points={fillPoints} fill={`url(#${uid}-fill)`} />
      <polyline points={polylinePoints} fill="none" stroke={color} strokeWidth={1.5} />
      {/* End dot */}
      <circle
        cx={width}
        cy={pad + ((max - data[data.length - 1]) / range) * (height - pad * 2)}
        r="2" fill={color}
      />
    </svg>
  );
});

export default Sparkline;
