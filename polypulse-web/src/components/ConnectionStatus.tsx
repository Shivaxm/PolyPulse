interface Props {
  isConnected: boolean;
}

export default function ConnectionStatus({ isConnected }: Props) {
  return (
    <div className="flex items-center gap-2 text-sm">
      <span
        className={`inline-block w-2.5 h-2.5 rounded-full ${
          isConnected
            ? 'bg-green-400 animate-pulse'
            : 'bg-red-500'
        }`}
      />
      <span className={isConnected ? 'text-green-400' : 'text-red-400'}>
        {isConnected ? 'Live' : 'Reconnecting...'}
      </span>
    </div>
  );
}
