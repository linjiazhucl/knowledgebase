import { defineComponent, h } from 'vue'

const paths = {
  Activity: 'M3 12h4l2-7 4 14 2-7h6',
  Archive: 'M4 7h16M5 7l1 12h12l1-12M9 11h6M3 4h18v3H3z',
  ArrowDownToLine: 'M12 3v13m0 0 5-5m-5 5-5-5M5 21h14',
  ArrowUpRight: 'M7 17 17 7M7 7h10v10',
  BookOpen: 'M4 5.5A2.5 2.5 0 0 1 6.5 3H20v15H6.5A2.5 2.5 0 0 0 4 20.5v-15ZM4 20.5A2.5 2.5 0 0 1 6.5 18H20',
  Bot: 'M8 10h.01M16 10h.01M9 15h6M12 2v3M7 6h10a3 3 0 0 1 3 3v8H4V9a3 3 0 0 1 3-3Z',
  Boxes: 'm7 3 5-2 5 2-5 2-5-2ZM3 7l5-2 5 2-5 2-5-2Zm8 0 5-2 5 2-5 2-5-2ZM3 13l5-2 5 2-5 2-5-2Zm8 0 5-2 5 2-5 2-5-2ZM3 19l5-2 5 2-5 2-5-2Zm8 0 5-2 5 2-5 2-5-2Z',
  Check: 'm5 12 4 4L19 6',
  ChevronDown: 'm6 9 6 6 6-6',
  ChevronRight: 'm9 18 6-6-6-6',
  CircleHelp: 'M9.1 9a3 3 0 1 1 5.8 1c0 2-3 2-3 4M12 18h.01M21 12a9 9 0 1 1-18 0 9 9 0 0 1 18 0Z',
  Database: 'M4 5c0-1.1 3.6-2 8-2s8 .9 8 2-3.6 2-8 2-8-.9-8-2Zm0 0v7c0 1.1 3.6 2 8 2s8-.9 8-2V5M4 12v7c0 1.1 3.6 2 8 2s8-.9 8-2v-7',
  FileCheck2: 'M14 2H6a2 2 0 0 0-2 2v16a2 2 0 0 0 2 2h12a2 2 0 0 0 2-2V8Zm0 0v6h6M8 17l2 2 5-5',
  FileText: 'M14 2H6a2 2 0 0 0-2 2v16a2 2 0 0 0 2 2h12a2 2 0 0 0 2-2V8Zm0 0v6h6M8 17h8M8 13h2',
  Gauge: 'm12 14 4-4M21 12a9 9 0 1 1-18 0 9 9 0 0 1 18 0Z',
  KeyRound: 'm21 2-2 2m-7.6 7.6a5 5 0 1 1-7.07-7.07 5 5 0 0 1 7.07 7.07ZM14.5 7.5l3 3m0 0 2-2m-2 2 2 2',
  LayoutDashboard: 'M3 3h7v8H3zM14 3h7v5h-7zM14 12h7v9h-7zM3 15h7v6H3z',
  LogOut: 'M10 17l5-5-5-5m5 5H3m12-7V5a2 2 0 0 0-2-2H6a2 2 0 0 0-2 2v2m0 10v2a2 2 0 0 0 2 2h7a2 2 0 0 0 2-2v-2',
  Menu: 'M4 6h16M4 12h16M4 18h16',
  MessageSquareText: 'M21 11.5a8.4 8.4 0 0 1-9 8.5 9.7 9.7 0 0 1-4-.9L3 21l1.9-4A8.3 8.3 0 0 1 3 11.5 8.4 8.4 0 0 1 12 3a8.4 8.4 0 0 1 9 8.5ZM8 11h8M8 15h5',
  MoreHorizontal: 'M5 12h.01M12 12h.01M19 12h.01',
  Plus: 'M12 5v14M5 12h14',
  Search: 'm21 21-4.35-4.35m2.35-5.65a8 8 0 1 1-16 0 8 8 0 0 1 16 0Z',
  Settings2: 'M4 21v-7M4 10V3M12 21v-9M12 8V3M20 21v-5M20 12V3M1 14h6M9 8h6m2 8h6',
  SlidersHorizontal: 'M3 5h18M3 12h18M3 19h18M7 3v4m10 5v4m-6 3v4',
  Sparkles: 'm12 3-1.3 4.7L6 9l4.7 1.3L12 15l1.3-4.7L18 9l-4.7-1.3L12 3ZM5 16l-.6 2.4L2 19l2.4.6L5 22l.6-2.4L8 19l-2.4-.6L5 16Z',
  UploadCloud: 'M16 16l-4-4-4 4m4-4v9M20 16.7a5 5 0 0 0-1-9.9A7 7 0 0 0 5.2 9.2 4 4 0 0 0 6 17h2',
  UserRound: 'M20 21a8 8 0 0 0-16 0M12 11a4 4 0 1 0 0-8 4 4 0 0 0 0 8Z',
  Users: 'M16 21v-2a4 4 0 0 0-4-4H6a4 4 0 0 0-4 4v2M9 11a4 4 0 1 0 0-8 4 4 0 0 0 0 8Zm7-3a4 4 0 0 1 0 7.7M22 21v-2a4 4 0 0 0-3-3.9',
  X: 'M6 6l12 12M18 6 6 18',
  Zap: 'm13 2-9 12h7l-1 8 9-12h-7l1-8Z',
  Eye: 'M2 12s3.5-6 10-6 10 6 10 6-3.5 6-10 6S2 12 2 12Z M12 15a3 3 0 1 0 0-6 3 3 0 0 0 0 6Z',
  EyeOff: 'm3 3 18 18M10.6 10.6a2 2 0 0 0 2.8 2.8M9.9 5.2A10 10 0 0 1 12 5c6.5 0 10 7 10 7a16 16 0 0 1-3.3 4.2M6.6 6.6C3.6 8.4 2 12 2 12s3.5 7 10 7a9.7 9.7 0 0 0 4.1-.9',
  LockKeyhole: 'M7 10V7a5 5 0 0 1 10 0v3M5 10h14v11H5zM12 15v2',
  RotateCcw: 'M3 12a9 9 0 1 0 3-6.7L3 8m0-5v5h5',
  Trash2: 'M3 6h18M8 6V4h8v2m-9 0 1 15h8l1-15M10 11v6m4-6v6',
  Pencil: 'm4 16-1 5 5-1L19 9l-4-4L4 16ZM13 6l4 4',
  RefreshCw: 'M21 12a9 9 0 0 0-15.3-6.4L3 8m0-5v5h5M3 12a9 9 0 0 0 15.3 6.4L21 16m0 5v-5h-5',
  CircleAlert: 'M12 8v4m0 4h.01M21 12a9 9 0 1 1-18 0 9 9 0 0 1 18 0Z',
  LoaderCircle: 'M12 2a10 10 0 1 0 10 10',
  CircleCheck: 'M22 12a10 10 0 1 1-20 0 10 10 0 0 1 20 0Zm-14 0 3 3 5-6',
  CircleX: 'M15 9l-6 6m0-6 6 6M22 12a10 10 0 1 1-20 0 10 10 0 0 1 20 0Z',
  Copy: 'M8 8h11v13H8zM5 16H4a2 2 0 0 1-2-2V4a2 2 0 0 1 2-2h10a2 2 0 0 1 2 2v1',
  Send: 'm22 2-7 20-4-9-9-4Z M22 2 11 13',
  PanelRightOpen: 'M15 3h4a2 2 0 0 1 2 2v14a2 2 0 0 1-2 2h-4M9 3H5a2 2 0 0 0-2 2v14a2 2 0 0 0 2 2h4m0-18v18m3-12 3 3-3 3',
  ShieldCheck: 'M12 22s8-4 8-10V5l-8-3-8 3v7c0 6 8 10 8 10Zm-3-10 2 2 4-5'
}

function makeIcon(name) {
  return defineComponent({
    name,
    props: { size: { type: [Number, String], default: 18 }, strokeWidth: { type: [Number, String], default: 1.8 } },
    setup(props) {
      return () => h('svg', { width: props.size, height: props.size, viewBox: '0 0 24 24', fill: 'none', stroke: 'currentColor', 'stroke-width': props.strokeWidth, 'stroke-linecap': 'round', 'stroke-linejoin': 'round', 'aria-hidden': 'true' }, (paths[name] || paths.CircleHelp).split(' M').map((part, index) => h('path', { d: `${index ? 'M' : ''}${part}` })))
    }
  })
}

export const Activity = makeIcon('Activity')
export const Archive = makeIcon('Archive')
export const ArrowDownToLine = makeIcon('ArrowDownToLine')
export const ArrowUpRight = makeIcon('ArrowUpRight')
export const BookOpen = makeIcon('BookOpen')
export const Bot = makeIcon('Bot')
export const Boxes = makeIcon('Boxes')
export const Check = makeIcon('Check')
export const ChevronDown = makeIcon('ChevronDown')
export const ChevronRight = makeIcon('ChevronRight')
export const CircleHelp = makeIcon('CircleHelp')
export const Database = makeIcon('Database')
export const FileCheck2 = makeIcon('FileCheck2')
export const FileText = makeIcon('FileText')
export const Gauge = makeIcon('Gauge')
export const KeyRound = makeIcon('KeyRound')
export const LayoutDashboard = makeIcon('LayoutDashboard')
export const LogOut = makeIcon('LogOut')
export const Menu = makeIcon('Menu')
export const MessageSquareText = makeIcon('MessageSquareText')
export const MoreHorizontal = makeIcon('MoreHorizontal')
export const Plus = makeIcon('Plus')
export const Search = makeIcon('Search')
export const Settings2 = makeIcon('Settings2')
export const SlidersHorizontal = makeIcon('SlidersHorizontal')
export const Sparkles = makeIcon('Sparkles')
export const UploadCloud = makeIcon('UploadCloud')
export const UserRound = makeIcon('UserRound')
export const Users = makeIcon('Users')
export const X = makeIcon('X')
export const Zap = makeIcon('Zap')
export const Eye = makeIcon('Eye')
export const EyeOff = makeIcon('EyeOff')
export const LockKeyhole = makeIcon('LockKeyhole')
export const RotateCcw = makeIcon('RotateCcw')
export const Trash2 = makeIcon('Trash2')
export const Pencil = makeIcon('Pencil')
export const RefreshCw = makeIcon('RefreshCw')
export const CircleAlert = makeIcon('CircleAlert')
export const LoaderCircle = makeIcon('LoaderCircle')
export const CircleCheck = makeIcon('CircleCheck')
export const CircleX = makeIcon('CircleX')
export const Copy = makeIcon('Copy')
export const Send = makeIcon('Send')
export const PanelRightOpen = makeIcon('PanelRightOpen')
export const ShieldCheck = makeIcon('ShieldCheck')
