const rawApiBase = (import.meta.env.VITE_API_URL as string | undefined)?.trim() ?? '';
const apiBase = rawApiBase.replace(/\/+$/, '');

export function apiUrl(path: string): string {
  const normalizedPath = path.startsWith('/') ? path : `/${path}`;
  return apiBase ? `${apiBase}${normalizedPath}` : normalizedPath;
}
