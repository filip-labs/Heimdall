export function time(value: string | null): string { if (!value) return 'Never seen'; const date = new Date(value); return Number.isNaN(date.getTime()) ? 'Unavailable' : date.toLocaleString(undefined, {month:'short',day:'2-digit',hour:'2-digit',minute:'2-digit',second:'2-digit'}); }
export const number = (value:number) => value.toLocaleString();
export const percent = (value:number) => `${value.toFixed(2)}%`;
export const shortId = (value:string) => value.slice(0,8);
