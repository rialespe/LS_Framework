export type IconName = 'grid' | 'company' | 'branch' | 'users' | 'apps' | 'arrow' | 'search' | 'menu' | 'close' | 'key' | 'clock' | 'device'
const paths: Record<IconName, string> = {
  device: 'M3 3h18v13H3z M8 21h8 M12 16v5',
  key: 'M15 3a6 6 0 0 0-5.5 8.4L3 18v3h3v-3h3v-3l3.6-3.6A6 6 0 1 0 15 3z M17 7h.01',
  clock: 'M12 3a9 9 0 1 0 0 18 9 9 0 0 0 0-18 M12 7v5l3 2',
  grid: 'M3 3h7v7H3z M14 3h7v7h-7z M3 14h7v7H3z M14 14h7v7h-7z',
  company: 'M5 21V3h14v18 M2 21h20 M9 7h1 M14 7h1 M9 11h1 M14 11h1 M10 21v-6h4v6',
  branch: 'M12 7v3 M5 17v-7h14v7 M2 17h6v4H2z M16 17h6v4h-6z M9 3h6v4H9z',
  users: 'M16 21v-2a4 4 0 0 0-4-4H6a4 4 0 0 0-4 4v2 M9 11a4 4 0 1 0 0-8 4 4 0 0 0 0 8 M20 21v-2a4 4 0 0 0-3-3.87 M16 3.13a4 4 0 0 1 0 7.75',
  apps: 'M3 3h7v7H3z M14 3h7v7h-7z M3 14h7v7H3z M14 17.5h7 M17.5 14v7',
  arrow: 'M5 12h14 M13 6l6 6-6 6',
  search: 'M21 21l-5-5 M10.5 17a6.5 6.5 0 1 0 0-13 6.5 6.5 0 0 0 0 13',
  menu: 'M4 6h16 M4 12h16 M4 18h16',
  close: 'M6 6l12 12 M6 18L18 6',
}
export function Icon({ name, size = 20 }: { name: IconName; size?: number }) {
  return <svg width={size} height={size} viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="1.7" strokeLinecap="round" strokeLinejoin="round" aria-hidden="true"><path d={paths[name]} /></svg>
}
