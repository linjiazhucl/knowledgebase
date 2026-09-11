<script setup>
import { computed, nextTick, onMounted, onUnmounted, reactive, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import {
  Activity, Archive, ArrowDownToLine, ArrowUpRight, BookOpen, Bot, Boxes, Check, ChevronDown,
  ChevronRight, CircleHelp, Database, FileCheck2, FileText, Gauge, KeyRound, LayoutDashboard,
  LogOut, Menu, MessageSquareText, MoreHorizontal, Plus, Search, Settings2, SlidersHorizontal,
  Sparkles, UploadCloud, UserRound, Users, X, Zap, Eye, RotateCcw, Trash2, Pencil, RefreshCw,
  CircleAlert, LoaderCircle, CircleCheck, CircleX, Copy, Send, PanelRightOpen, ShieldCheck
} from '../icons.js'
import { api } from '../api.js'

const route = useRoute()
const router = useRouter()

const navGroups = [
  { label: '总览', items: [{ key: 'dashboard', label: '工作台', icon: LayoutDashboard }] },
  { label: '知识资产', items: [
    { key: 'knowledge', label: '知识库', icon: Database },
    { key: 'documents', label: '文档管理', icon: FileText },
    { key: 'segments', label: '片段管理', icon: Boxes }
  ] },
  { label: '智能能力', items: [
    { key: 'qa', label: '问答验证', icon: MessageSquareText, badge: 'LIVE' },
    { key: 'models', label: '模型配置', icon: Bot }
  ] },
  { label: '系统', items: [
    { key: 'users', label: '用户管理', icon: Users },
    { key: 'settings', label: '系统配置', icon: SlidersHorizontal },
    { key: 'profile', label: '个人信息', icon: UserRound }
  ] }
]

const pageMeta = {
  dashboard: { eyebrow: 'OVERVIEW / 01', title: '工作台', desc: '今天也让知识保持在可被找到的状态。' },
  knowledge: { eyebrow: 'KNOWLEDGE ASSETS / 02', title: '知识库', desc: '按业务语境组织企业知识，并为每一类资料设定检索颗粒度。' },
  documents: { eyebrow: 'KNOWLEDGE ASSETS / 03', title: '文档管理', desc: '上传、解析并检查原始资料，保持知识来源的完整。' },
  segments: { eyebrow: 'KNOWLEDGE ASSETS / 04', title: '片段管理', desc: '切片是检索的最小单元，也是答案可信度的起点。' },
  qa: { eyebrow: 'INTELLIGENCE / 05', title: '问答验证', desc: '用真实问题检验 RAG 链路，让每个回答都带着证据回来。' },
  models: { eyebrow: 'INTELLIGENCE / 06', title: '模型配置', desc: '聊天模型与向量模型彼此独立，兼容本地或自托管接口。' },
  users: { eyebrow: 'SYSTEM / 07', title: '用户管理', desc: '管理访问权限与使用者，让知识只被正确的人使用。' },
  settings: { eyebrow: 'SYSTEM / 08', title: '系统配置', desc: '调节检索与会话体验参数，保存后即时生效。' },
  profile: { eyebrow: 'SYSTEM / 09', title: '个人信息', desc: '维护管理员身份与登录安全。' }
}

const currentSection = computed(() => route.params.section || 'dashboard')
const currentMeta = computed(() => pageMeta[currentSection.value] || pageMeta.dashboard)
const mobileMenu = ref(false)
const showProfileMenu = ref(false)
const modal = ref(null)
const toast = ref('')
const search = ref('')
const selectedKb = ref(null)
const uploadKbId = ref('')
const uploading = ref(false)
const uploadProgress = ref(0)
const isDragOver = ref(false)
const fileInput = ref(null)
const selectedDoc = ref(null)
const parsingDocIds = ref(new Set())
const parseProgressById = ref({})
const parseProgressTargets = ref({})
const parseAnimationFrames = new Map()
const qaInput = ref('')
const qaLoading = ref(false)
const selectedQaKb = ref('')
const currentUser = ref(JSON.parse(localStorage.getItem('kb_admin_user') || '{}'))
const profileNickname = ref(currentUser.value.nickname || '')
const profilePassword = ref({ currentPassword: '', newPassword: '', confirmPassword: '' })
const qaMessages = ref([])
const sourceItems = ref([])
const qaMessageList = ref(null)
const qaSettingsOpen = ref(false)
const qaSettingsSaving = ref(false)
const qaSettingsDraft = ref({ topK: 5, threshold: 0.7 })
let qaRenderQueue = ''
let qaRenderTimer = null
let qaRenderQueueDone = null

const dashboardMetrics = ref({ knowledgeBases: 0, documents: 0, parsedDocuments: 0, segments: 0, vectorized: 0, pendingSegments: 0, failedSegments: 0, users: 0, activeUsers: 0, admins: 0, milvusReady: false, redisCache: false })
const stats = computed(() => {
  const percent = dashboardMetrics.value.segments ? ((dashboardMetrics.value.vectorized / dashboardMetrics.value.segments) * 100).toFixed(1) : '0.0'
  return [
    { label: '知识库', value: formatNumber(dashboardMetrics.value.knowledgeBases), change: '+1 本月', icon: Database, tone: 'green' },
    { label: '已入库文档', value: formatNumber(dashboardMetrics.value.documents), change: '+12 本月', icon: FileText, tone: 'blue' },
    { label: '知识片段', value: formatNumber(dashboardMetrics.value.segments), change: '+1,204 本月', icon: Boxes, tone: 'orange' },
    { label: '已向量化', value: `${percent}%`, change: `${formatNumber(dashboardMetrics.value.vectorized)} / ${formatNumber(dashboardMetrics.value.segments)}`, icon: Activity, tone: 'purple' }
  ]
})

const kbs = ref([])
const documents = ref([])
const segments = ref([])
const users = ref([])
const models = ref({ chat: {}, embedding: {} })
const qaModelReady = computed(() => Boolean(
  models.value.chat?.status === 'active'
  && models.value.chat?.endpoint?.trim()
  && models.value.chat?.model?.trim()
))
const qaStatusText = computed(() => qaModelReady.value ? '聊天模型已就绪' : '待配置聊天模型')
const settings = ref({ topK: 5, threshold: 0.7, history: 6 })
const newUser = ref({ username: '', nickname: '', password: 'Welcome@123' })
const kbForm = ref({ name: '', desc: '', chunk: 800, overlap: 120 })
const modelDraft = ref(null)
const modelTesting = ref(false)
const modelDiscoveryLoading = ref(false)
const modelOptions = ref([])
const modelDiscoveryState = ref({ status: 'idle', message: '' })
const modelDiscoverySignature = ref('')
const modelTestState = computed(() => {
  const draft = modelDraft.value
  if (!draft) return { status: 'idle', message: '' }
  const signature = modelTestSignature(draft)
  if (draft.testStatus === 'testing') return { status: 'testing', message: draft.testMessage || '正在测试接口…', latencyMs: 0 }
  if (draft.testedSignature !== signature) return { status: 'idle', message: '修改配置后请重新测试', responseBody: '' }
  return {
    status: draft.testStatus || 'idle',
    message: draft.testMessage || '',
    latencyMs: draft.testLatencyMs || 0,
    responseBody: draft.testResponseBody || ''
  }
})
const visibleUsers = computed(() => users.value.filter(user => `${user.username} ${user.nickname}`.toLowerCase().includes(search.value.toLowerCase())))
const documentCounts = computed(() => ({
  all: documents.value.length,
  processing: documents.value.filter(doc => doc.status === 'processing').length,
  failed: documents.value.filter(doc => doc.status === 'failed').length
}))
const documentFilterOpen = ref(false)
const documentFilters = ref({ knowledgeBase: '', status: '', type: '' })
const documentStatusTab = ref('')
const hasDocumentFilters = computed(() => Object.values(documentFilters.value).some(Boolean))
const documentFilterCount = computed(() => Object.values(documentFilters.value).filter(Boolean).length)
const filteredDocuments = computed(() => {
  const keyword = search.value.trim().toLowerCase()
  const filters = documentFilters.value
  return documents.value.filter((doc) => {
    const matchesSearch = !keyword || String(doc.name || '').toLowerCase().includes(keyword)
    const matchesKnowledgeBase = !filters.knowledgeBase || doc.kb === filters.knowledgeBase
    const matchesStatus = !filters.status || doc.status === filters.status
    const matchesStatusTab = !documentStatusTab.value || doc.status === documentStatusTab.value
    const matchesType = !filters.type || doc.type === filters.type
    return matchesSearch && matchesKnowledgeBase && matchesStatus && matchesStatusTab && matchesType
  })
})
const segmentSearch = ref('')
const segmentStatusTab = ref('')
const segmentKbFilter = ref('')
const segmentPage = ref(1)
const segmentPageSize = 12
const segmentVectorizing = ref(false)
const segmentVectorizingIds = ref(new Set())
const segmentBatchProgress = ref({ total: 0, completed: 0, success: 0, failed: 0 })
const segmentModelTesting = ref(false)
const segmentModelTestState = ref({ status: 'idle', message: '', latencyMs: 0 })
const selectedSegment = ref(null)
const visibleSegments = computed(() => {
  const keyword = segmentSearch.value.trim().toLowerCase()
  return segments.value.filter((segment) => {
    const searchable = `${segment.doc || ''} ${segment.kb || ''} ${segment.content || ''}`.toLowerCase()
    const matchesSearch = !keyword || searchable.includes(keyword)
    const matchesStatus = !segmentStatusTab.value
      || (segmentStatusTab.value === 'unvectorized' && ['pending', 'processing'].includes(segment.status))
      || (segmentStatusTab.value === 'failed' && segment.status === 'failed')
    const matchesKb = !segmentKbFilter.value || segment.kb === segmentKbFilter.value
    return matchesSearch && matchesStatus && matchesKb
  })
})
const segmentPageCount = computed(() => Math.max(1, Math.ceil(visibleSegments.value.length / segmentPageSize)))
const pagedSegments = computed(() => {
  const start = (segmentPage.value - 1) * segmentPageSize
  return visibleSegments.value.slice(start, start + segmentPageSize)
})
const segmentPageNumbers = computed(() => {
  const total = segmentPageCount.value
  const current = segmentPage.value
  const pages = new Set([1, total])
  for (let page = Math.max(1, current - 2); page <= Math.min(total, current + 2); page++) pages.add(page)
  return [...pages].sort((left, right) => left - right)
})
const segmentPageStart = computed(() => visibleSegments.value.length ? (segmentPage.value - 1) * segmentPageSize + 1 : 0)
const segmentPageEnd = computed(() => Math.min(segmentPage.value * segmentPageSize, visibleSegments.value.length))
const uploadKb = computed(() => kbs.value.find(kb => String(kb.id) === String(uploadKbId.value)) || null)
const activeUserPercent = computed(() => users.value.length ? Math.round(users.value.filter(user => user.status === 'active').length / users.value.length * 100) : 0)
const completionRate = computed(() => dashboardMetrics.value.segments ? Math.round(dashboardMetrics.value.vectorized / dashboardMetrics.value.segments * 100) : 0)
const segmentBatchProgressPercent = computed(() => segmentBatchProgress.value.total ? Math.round(segmentBatchProgress.value.completed / segmentBatchProgress.value.total * 100) : 0)
const segmentBatchProgressVisible = computed(() => segmentBatchProgress.value.total > 0)
function normalizeSources(sources = []) {
  return sources.map((source, index) => ({ ...source, content: source.excerpt, color: ['sage', 'blue', 'orange', 'purple'][index % 4] }))
}

function escapeMarkdownHtml(value) {
  return String(value ?? '')
    .replace(/&/g, '&amp;')
    .replace(/</g, '&lt;')
    .replace(/>/g, '&gt;')
    .replace(/"/g, '&quot;')
    .replace(/'/g, '&#39;')
}

function splitCompactMarkdownListLine(line) {
  const colonSeparated = String(line ?? '').replace(
    /([：:；;])(?=(?:[-*+]|\d{1,3}[.)、]|[一二三四五六七八九十百]+、)\s*\S)/g,
    '$1\n'
  )
  return colonSeparated.split('\n').flatMap((part) => {
    const markerPattern = /(?<!\S)(?:[-*+]\s*(?=\S)|\d{1,3}[.)、]\s*(?=\D)|[一二三四五六七八九十百]+、\s*(?=\S))/g
    const matches = [...part.matchAll(markerPattern)]
    if (!matches.length) return [part]
    const prefix = part.slice(0, matches[0].index).trimEnd()
    const shouldSplit = matches.length > 1 || /[：:；;]$/.test(prefix)
    if (!shouldSplit) return [part]

    const lines = []
    let cursor = 0
    matches.forEach((match) => {
      if (match.index > cursor) {
        const previous = part.slice(cursor, match.index).trim()
        if (previous) lines.push(previous)
      }
      cursor = match.index
    })
    const last = part.slice(cursor).trim()
    if (last) lines.push(last)
    return lines.length ? lines : [part]
  })
}

function mergeStandaloneMarkdownListMarkers(lines) {
  const normalized = []
  for (let index = 0; index < lines.length; index += 1) {
    const line = lines[index]
    const markerOnly = String(line ?? '').match(
      /^(\s*)((?:[-*+])|(?:\d{1,3}[.)、])|(?:[一二三四五六七八九十百]+、))\s*$/
    )
    if (!markerOnly) {
      normalized.push(line)
      continue
    }

    const nextLine = lines[index + 1]
    if (!nextLine || !nextLine.trim()) continue
    const nextText = nextLine.trim()
    const nextMarker = nextText.match(
      /^((?:[-*+])|(?:\d{1,3}[.)、])|(?:[一二三四五六七八九十百]+、))\s*(.+)$/
    )
    const currentIsOrdered = /^\d|、$/.test(markerOnly[2])

    if (nextMarker) {
      if (currentIsOrdered && !/^\d|、$/.test(nextMarker[1])) {
        normalized.push(`${markerOnly[1]}${markerOnly[2]} ${nextMarker[2]}`)
        index += 1
      }
      continue
    }

    normalized.push(`${markerOnly[1]}${markerOnly[2]} ${nextText}`)
    index += 1
  }
  return normalized
}

function separateLeadSentence(source) {
  const lines = source.split('\n')
  const firstIndex = lines.findIndex((line) => line.trim())
  if (firstIndex < 0) return source

  const firstLine = lines[firstIndex]
  if (/^\s*(?:#{1,6}\s|[-*+]\s|\d{1,3}[.)、]\s|[一二三四五六七八九十百]+、\s|>|```)/.test(firstLine)) return source
  if (/[`*_]/.test(firstLine)) return source

  const sentence = firstLine.match(/^(.+?[。！？!?])\s*(.+)$/)
  if (!sentence) return source
  lines.splice(firstIndex, 1, sentence[1].trim(), '', sentence[2].trim())
  return lines.join('\n')
}

function renderMarkdown(source = '') {
  const codeBlocks = []
  const sourceWithCodePlaceholders = String(source ?? '')
    .replace(/\r\n?/g, '\n')
    .replace(/\\n/g, '\n')
    .replace(/```([^\n]*)\n([\s\S]*?)```/g, (_, language, code) => {
      codeBlocks.push({ language: String(language || '').trim().match(/^[\w-]+/)?.[0] || '', code })
      return `\n@@QA_CODE_${codeBlocks.length - 1}@@\n`
    })
  const normalizedLines = mergeStandaloneMarkdownListMarkers(
    sourceWithCodePlaceholders.split('\n').flatMap(splitCompactMarkdownListLine)
  )
  const sourceWithPlaceholders = separateLeadSentence(
    normalizedLines.join('\n')
  )

  const renderInline = (value) => {
    let html = escapeMarkdownHtml(value)
    html = html.replace(/`([^`\n]+)`/g, '<code>$1</code>')
    html = html.replace(/\[([^\]]+)\]\((https?:\/\/[^\s)]+)\)/g, '<a href="$2" target="_blank" rel="noreferrer noopener">$1</a>')
    html = html.replace(/(\*\*|__)(.+?)\1/g, '<strong>$2</strong>')
    html = html.replace(/~~(.+?)~~/g, '<del>$1</del>')
    html = html.replace(/(^|[^*])\*([^*\n]+)\*/g, '$1<em>$2</em>')
    return html
  }

  const lines = sourceWithPlaceholders.split('\n')
  const output = []
  const paragraph = []
  let listType = ''
  const flushParagraph = () => {
    if (!paragraph.length) return
    output.push(`<p>${paragraph.map(renderInline).join('<br>')}</p>`)
    paragraph.length = 0
  }
  const closeList = () => {
    if (listType) output.push(`</${listType}>`)
    listType = ''
  }
  lines.forEach((line) => {
    const trimmed = line.trim()
    if (!trimmed) {
      flushParagraph()
      closeList()
      return
    }
    const codeMatch = trimmed.match(/^@@QA_CODE_(\d+)@@$/)
    if (codeMatch) {
      flushParagraph()
      closeList()
      const block = codeBlocks[Number(codeMatch[1])]
      const languageClass = block?.language ? ` class="language-${block.language}"` : ''
      output.push(`<pre><code${languageClass}>${escapeMarkdownHtml(block?.code || '')}</code></pre>`)
      return
    }
    const heading = line.match(/^\s{0,3}(#{1,6})\s+(.+?)\s*#*$/)
    if (heading) {
      flushParagraph()
      closeList()
      const level = heading[1].length
      output.push(`<h${level}>${renderInline(heading[2])}</h${level}>`)
      return
    }
    const unordered = line.match(/^\s*[-*+]\s*(.+)$/)
    if (unordered) {
      flushParagraph()
      if (listType !== 'ul') {
        closeList()
        output.push('<ul>')
        listType = 'ul'
      }
      output.push(`<li>${renderInline(unordered[1])}</li>`)
      return
    }
    const ordered = line.match(/^\s*(?:(?:\d{1,3}[.)、])|(?:[一二三四五六七八九十百]+、))\s*(.+)$/)
    if (ordered) {
      flushParagraph()
      if (listType !== 'ol') {
        closeList()
        output.push('<ol>')
        listType = 'ol'
      }
      output.push(`<li>${renderInline(ordered[1])}</li>`)
      return
    }
    if (/^\s*>\s?/.test(line)) {
      flushParagraph()
      closeList()
      output.push(`<blockquote>${renderInline(line.replace(/^\s*>\s?/, ''))}</blockquote>`)
      return
    }
    closeList()
    paragraph.push(line)
  })
  flushParagraph()
  closeList()
  return output.join('')
}

function newQaSession() {
  clearQaRenderQueue()
  qaMessages.value = []
  sourceItems.value = []
}

function normalizeRetrievalSettings(source = settings.value) {
  const rawTopK = Number(source.topK)
  const rawThreshold = Number(source.threshold)
  return {
    topK: Number.isFinite(rawTopK) ? Math.min(20, Math.max(1, Math.round(rawTopK))) : 5,
    threshold: Number.isFinite(rawThreshold) ? Number(Math.min(1, Math.max(0.2, rawThreshold)).toFixed(2)) : 0.7
  }
}

function toggleQaSettings() {
  if (!qaSettingsOpen.value) qaSettingsDraft.value = normalizeRetrievalSettings(settings.value)
  qaSettingsOpen.value = !qaSettingsOpen.value
}

function closeQaSettings() {
  qaSettingsOpen.value = false
}

async function persistSettings(payload, message = '系统配置已保存') {
  await api.updateSettings(payload)
  settings.value = { ...settings.value, ...payload }
  qaSettingsDraft.value = normalizeRetrievalSettings(settings.value)
  notify(message)
}

async function applyQaSettings() {
  if (qaSettingsSaving.value) return
  const payload = normalizeRetrievalSettings(qaSettingsDraft.value)
  qaSettingsDraft.value = { ...payload }
  qaSettingsSaving.value = true
  try {
    await persistSettings(payload, '检索参数已保存，新问答立即生效')
    closeQaSettings()
  } catch (error) {
    notify(error.response?.data?.message || '检索参数保存失败')
  } finally {
    qaSettingsSaving.value = false
  }
}

function scrollQaToBottom(force = false) {
  nextTick(() => {
    const element = qaMessageList.value
    if (!element) return
    const distanceFromBottom = element.scrollHeight - element.scrollTop - element.clientHeight
    if (force || distanceFromBottom < 80) element.scrollTop = element.scrollHeight
  })
}

function clearQaRenderQueue() {
  qaRenderQueue = ''
  if (qaRenderTimer) window.clearTimeout(qaRenderTimer)
  qaRenderTimer = null
  if (qaRenderQueueDone) {
    qaRenderQueueDone()
    qaRenderQueueDone = null
  }
}

function renderNextQaCharacter(assistant) {
  const characters = Array.from(qaRenderQueue)
  if (!characters.length) {
    qaRenderTimer = null
    if (qaRenderQueueDone) {
      qaRenderQueueDone()
      qaRenderQueueDone = null
    }
    return
  }
  assistant.content += characters.shift()
  qaRenderQueue = characters.join('')
  scrollQaToBottom()
  qaRenderTimer = window.setTimeout(() => renderNextQaCharacter(assistant), 20)
}

function enqueueQaToken(assistant, token) {
  qaRenderQueue += String(token || '')
  if (!qaRenderTimer) renderNextQaCharacter(assistant)
}

function waitForQaRenderQueue() {
  if (!qaRenderQueue && !qaRenderTimer) return Promise.resolve()
  return new Promise((resolve) => { qaRenderQueueDone = resolve })
}

watch(qaMessages, () => scrollQaToBottom(), { deep: true, flush: 'post' })
watch(currentSection, (section) => {
  if (section === 'qa') scrollQaToBottom(true)
}, { flush: 'post' })

function go(key) { router.push(`/admin/${key}`); mobileMenu.value = false }
function notify(message) { toast.value = message; window.clearTimeout(notify.timer); notify.timer = window.setTimeout(() => { toast.value = '' }, 2600) }
function resetDocumentFilters() {
  documentFilters.value = { knowledgeBase: '', status: '', type: '' }
  documentStatusTab.value = ''
}
function selectDocumentStatus(status = '') {
  documentStatusTab.value = status
  documentFilters.value.status = ''
}
function handleDocumentStatusTabClick(event) {
  if (currentSection.value !== 'documents') return
  const button = event.target instanceof Element ? event.target.closest('.filter-tabs button') : null
  if (!button) return
  const tabGroup = button.parentElement
  const buttons = tabGroup ? Array.from(tabGroup.children).filter(item => item.tagName === 'BUTTON') : []
  const index = buttons.indexOf(button)
  if (index < 0 || index > 2) return
  selectDocumentStatus(['', 'processing', 'failed'][index])
  window.requestAnimationFrame(() => {
    buttons.forEach((item, itemIndex) => item.classList.toggle('active', itemIndex === index))
    document.querySelector('.admin-app .filter-button')?.classList.toggle('active', Boolean(documentStatusTab.value) || hasDocumentFilters.value)
  })
}
watch(() => documentFilters.value.status, (status) => {
  if (status) documentStatusTab.value = ''
})
watch([segmentSearch, segmentStatusTab, segmentKbFilter], () => {
  segmentPage.value = 1
})
watch(segmentPageCount, (count) => {
  if (segmentPage.value > count) segmentPage.value = count
})
function normalizeDocuments(items = []) {
  return items.map(doc => ({ ...doc, type: String(doc.type || '').toUpperCase(), parseProgress: Number(doc.parseProgress || 0) }))
}
function progressFor(doc) {
  if (doc.status === 'success') return 100
  if (doc.status !== 'processing') return 0
  return Math.max(4, Math.min(99, Math.round(Number(parseProgressById.value[String(doc.id)] ?? doc.parseProgress ?? 4))))
}
function animateParseProgress(id, target) {
  const key = String(id)
  const current = Number(parseProgressById.value[key] ?? 0)
  if (target <= current) {
    if (target < current) parseProgressById.value = { ...parseProgressById.value, [key]: target }
    return
  }
  const oldFrame = parseAnimationFrames.get(key)
  if (oldFrame) window.cancelAnimationFrame(oldFrame)
  const started = performance.now()
  const duration = target >= 100 ? 420 : 720
  const tick = (now) => {
    const ratio = Math.min(1, (now - started) / duration)
    const eased = 1 - Math.pow(1 - ratio, 3)
    parseProgressById.value = { ...parseProgressById.value, [key]: current + (target - current) * eased }
    if (ratio < 1) parseAnimationFrames.set(key, window.requestAnimationFrame(tick))
    else parseAnimationFrames.delete(key)
  }
  parseAnimationFrames.set(key, window.requestAnimationFrame(tick))
}
function syncParseProgress(items) {
  const targets = {}
  items.forEach((doc) => {
    const key = String(doc.id)
    const target = doc.status === 'success' ? 100 : doc.status === 'processing' ? Math.max(4, Math.min(99, Number(doc.parseProgress || 4))) : 0
    targets[key] = target
    const previousTarget = parseProgressTargets.value[key]
    if (doc.status !== 'processing' && doc.status !== 'success') {
      const oldFrame = parseAnimationFrames.get(key)
      if (oldFrame) window.cancelAnimationFrame(oldFrame)
      parseAnimationFrames.delete(key)
      parseProgressById.value = { ...parseProgressById.value, [key]: target }
    } else if (previousTarget !== target || !parseAnimationFrames.has(key)) {
      animateParseProgress(key, target)
    }
  })
  parseProgressTargets.value = targets
}
function setDocumentParsing(id, active) {
  const next = new Set(parsingDocIds.value)
  if (active) next.add(String(id))
  else next.delete(String(id))
  parsingDocIds.value = next
}
function isDocParsing(doc) {
  return doc.status === 'processing' || parsingDocIds.value.has(String(doc.id))
}
async function refreshDocuments() {
  const nextDocuments = normalizeDocuments(await api.documents())
  documents.value = nextDocuments
  syncParseProgress(nextDocuments)
  if (selectedDoc.value) {
    const updated = nextDocuments.find(doc => doc.id === selectedDoc.value.id)
    if (updated) selectedDoc.value = updated
  }
  return nextDocuments
}
async function loadBackendData() {
  try {
    const [dashboard, nextKbs, nextDocuments, nextSegments, nextUsers, nextModels, nextSettings, history, me] = await Promise.all([
      api.dashboard(), api.knowledgeBases(), api.documents(), api.segments(), api.users(), api.models(), api.settings(), api.qaHistory(), api.me()
    ])
    dashboardMetrics.value = dashboard
    kbs.value = nextKbs
    if (!nextKbs.some(kb => String(kb.id) === String(uploadKbId.value))) {
      uploadKbId.value = nextKbs[0] ? String(nextKbs[0].id) : ''
    }
    documents.value = normalizeDocuments(nextDocuments)
    syncParseProgress(documents.value)
    if (selectedDoc.value) {
      const updated = documents.value.find(doc => doc.id === selectedDoc.value.id)
      if (updated) selectedDoc.value = updated
    }
    const statusLabels = { pending: '未向量化', processing: '向量化中', done: '已向量化', failed: '向量化失败' }
    segments.value = nextSegments.map(segment => ({ ...segment, statusText: statusLabels[segment.status] || segment.status }))
    users.value = nextUsers
    models.value = {
      chat: { ...nextModels.chat, key: nextModels.chat?.key ?? nextModels.chat?.apiKey ?? '' },
      embedding: { ...nextModels.embedding, key: nextModels.embedding?.key ?? nextModels.embedding?.apiKey ?? '' }
    }
    settings.value = { ...settings.value, ...nextSettings }
    const records = Array.isArray(history) ? history : []
    qaMessages.value = records.flatMap(record => [
      { role: 'user', content: record.question },
      { role: 'assistant', content: record.answer, sources: record.sources || [], noEvidence: record.noEvidence, suggestions: record.suggestions || [] }
    ])
    sourceItems.value = normalizeSources(records.at(-1)?.sources || [])
    scrollQaToBottom(true)
    currentUser.value = me || currentUser.value
    profileNickname.value = currentUser.value.nickname || ''
    localStorage.setItem('kb_admin_user', JSON.stringify(currentUser.value))
  } catch (error) {
    notify(`后端数据加载失败：${error.response?.data?.message || error.message || '请检查服务状态'}`)
  }
}
function openKbForm(kb = null) {
  selectedKb.value = kb
  kbForm.value = kb ? { name: kb.name, desc: kb.desc, chunk: kb.chunk, overlap: kb.overlap } : { name: '', desc: '', chunk: 800, overlap: 120 }
  modal.value = 'kb'
}
async function saveKb() {
  if (!kbForm.value.name.trim()) return notify('请填写知识库名称')
  if (Number(kbForm.value.chunk) <= Number(kbForm.value.overlap) || Number(kbForm.value.overlap) < 0) return notify('切片长度必须大于重叠长度，且重叠长度不能小于 0')
  try {
    const payload = { name: kbForm.value.name, desc: kbForm.value.desc, chunk: kbForm.value.chunk, overlap: kbForm.value.overlap }
    if (selectedKb.value) await api.updateKnowledgeBase(selectedKb.value.id, payload)
    else await api.createKnowledgeBase(payload)
    const wasEditing = Boolean(selectedKb.value)
    modal.value = null; await loadBackendData(); notify(wasEditing ? '知识库配置已更新' : '知识库已创建')
  } catch (error) { notify(error.response?.data?.message || '知识库保存失败') }
}
async function deleteKb(kb) {
  const confirmed = window.confirm(`确认删除知识库“${kb.name}”吗？其中的文档、片段和向量也会一起删除。`)
  if (!confirmed) return
  try {
    await api.deleteKnowledgeBase(kb.id)
    if (selectedKb.value?.id === kb.id) selectedKb.value = null
    await loadBackendData()
    notify(`知识库“${kb.name}”已删除`)
  } catch (error) {
    notify(error.response?.data?.message || '删除知识库失败')
  }
}
function openDoc(doc) { selectedDoc.value = doc; modal.value = 'doc' }
async function downloadDoc(doc) {
  if (!doc?.id) return
  try {
    const response = await api.downloadDocument(doc.id)
    const contentType = response.headers?.['content-type'] || 'application/octet-stream'
    const blob = response.data instanceof Blob ? response.data : new Blob([response.data], { type: contentType })
    const url = window.URL.createObjectURL(blob)
    const link = document.createElement('a')
    link.href = url
    link.download = doc.name || 'document'
    link.style.display = 'none'
    document.body.appendChild(link)
    link.click()
    link.remove()
    window.setTimeout(() => window.URL.revokeObjectURL(url), 1000)
    notify(`原文件“${doc.name}”下载已开始`)
  } catch (error) {
    notify(error.response?.data?.message || '原文件下载失败，请重试')
  }
}
function openKbDocuments(kb) {
  selectedKb.value = kb
  uploadKbId.value = String(kb.id)
  go('documents')
}
async function uploadDocumentFile(file, input = null) {
  if (!file) return
  if (!uploadKb.value) return notify('请先创建知识库，再上传文档')
  const ext = file.name.split('.').pop().toLowerCase()
  if (!['pdf', 'docx', 'txt', 'md'].includes(ext)) return notify('仅支持 PDF、DOCX、TXT、MD 格式')
  if (file.size > 100 * 1024 * 1024) return notify('单个文件不能超过 100MB')
  if (input) input.value = ''
  const targetKbId = uploadKb.value.id
  const targetKbName = uploadKb.value.name
  uploading.value = true
  uploadProgress.value = 0
  try {
    await api.uploadDocument(file, targetKbId, (event) => {
      if (event.total) uploadProgress.value = Math.min(99, Math.round((event.loaded / event.total) * 100))
    })
    uploadProgress.value = 100
    await loadBackendData()
    notify(`文档“${file.name}”已上传到“${targetKbName}”`)
  } catch (error) {
    const status = error.response?.status
    const message = error.response?.data?.message
      || (status === 413 ? '文件超过 100MB 限制' : '')
      || (status === 401 ? '登录状态已失效，请重新登录' : '')
      || (error.code === 'ECONNABORTED' || error.code === 'ETIMEDOUT' ? '上传超时，请检查网络后重试' : '')
      || (!error.response ? '无法连接上传服务，请检查 Docker 服务状态' : '')
      || '文档上传失败'
    notify(message)
  } finally {
    uploading.value = false
    uploadProgress.value = 0
  }
}
function simulateUpload(event) {
  uploadDocumentFile(event.target.files?.[0], event.target)
}
function handleDrop(event) {
  isDragOver.value = false
  uploadDocumentFile(event.dataTransfer.files?.[0])
}
async function deleteDoc(doc) {
  const confirmed = window.confirm(`确认删除文档“${doc.name}”吗？文档、片段和向量也会一起删除。`)
  if (!confirmed) return
  try {
    await api.deleteDocument(doc.id)
    if (selectedDoc.value?.id === doc.id) {
      selectedDoc.value = null
      if (modal.value === 'doc') modal.value = null
    }
    await loadBackendData()
    notify(`文档“${doc.name}”已删除`)
  } catch (error) {
    notify(error.response?.data?.message || '删除文档失败')
  }
}
async function retryDoc(doc) {
  return parseDoc(doc)
}
async function parseDoc(doc) {
  if (!doc.canParse || isDocParsing(doc)) return
  setDocumentParsing(doc.id, true)
  try {
    await api.parseDocument(doc.id)
    await refreshDocuments()
    let latest = documents.value.find(item => item.id === doc.id)
    for (let attempt = 0; latest && latest.status === 'processing' && attempt < 60; attempt++) {
      await new Promise(resolve => window.setTimeout(resolve, 350))
      await refreshDocuments()
      latest = documents.value.find(item => item.id === doc.id)
    }
    await loadBackendData()
    if (latest?.status === 'success') notify(`文档“${doc.name}”解析完成`)
    else notify(latest?.statusText || '文档解析失败')
  } catch (error) {
    notify(error.response?.data?.message || '文档解析失败')
  } finally {
    setDocumentParsing(doc.id, false)
  }
}
function isSegmentVectorizing(segment) {
  return segmentVectorizingIds.value.has(String(segment.id))
}
function setSegmentVectorizing(segment, active) {
  const ids = new Set(segmentVectorizingIds.value)
  const key = String(segment.id)
  if (active) ids.add(key)
  else ids.delete(key)
  segmentVectorizingIds.value = ids
}
function updateSegmentState(id, status) {
  const statusLabels = { pending: '未向量化', processing: '向量化中', done: '已向量化', failed: '向量化失败' }
  segments.value = segments.value.map(segment => segment.id === id
    ? { ...segment, status, statusText: statusLabels[status] || status }
    : segment)
}
function waitForSegmentProgress(milliseconds) {
  return new Promise(resolve => window.setTimeout(resolve, milliseconds))
}
async function vectorizeSegment(segment, { silent = false } = {}) {
  if ((!silent && segmentVectorizing.value) || isSegmentVectorizing(segment)) return false
  if (!silent) segmentBatchProgress.value = { total: 0, completed: 0, success: 0, failed: 0 }
  setSegmentVectorizing(segment, true)
  updateSegmentState(segment.id, 'processing')
  const startedAt = performance.now()
  let success = false
  try {
    const result = await api.vectorizeSegment(segment.id)
    success = Boolean(result.success)
    updateSegmentState(segment.id, success ? 'done' : 'failed')
    if (!silent) notify(success ? `片段 ${segment.index} 已完成向量化` : 'Milvus 暂不可用，片段已标记失败')
  } catch (error) {
    updateSegmentState(segment.id, 'failed')
    if (!silent) notify(error.response?.data?.message || '片段向量化失败')
  } finally {
    const elapsed = performance.now() - startedAt
    if (silent && elapsed < 70) await waitForSegmentProgress(70 - elapsed)
    setSegmentVectorizing(segment, false)
  }
  if (!silent) {
    try { await loadBackendData() } catch { /* the row result is already reflected locally */ }
  }
  return success
}
async function vectorizeAll() {
  if (segmentVectorizing.value) return
  const pending = segments.value.filter(segment => ['pending', 'processing', 'failed'].includes(segment.status))
  if (!pending.length) return
  segmentVectorizing.value = true
  segmentBatchProgress.value = { total: pending.length, completed: 0, success: 0, failed: 0 }
  try {
    for (const segment of pending) {
      const success = await vectorizeSegment(segment, { silent: true })
      segmentBatchProgress.value = {
        ...segmentBatchProgress.value,
        completed: segmentBatchProgress.value.completed + 1,
        success: segmentBatchProgress.value.success + (success ? 1 : 0),
        failed: segmentBatchProgress.value.failed + (success ? 0 : 1)
      }
    }
    await loadBackendData()
    const { success, failed } = segmentBatchProgress.value
    notify(failed ? `向量化完成：成功 ${success} 条，失败 ${failed} 条` : `已完成 ${success} 个片段的向量化`)
  } catch (error) {
    notify(error.response?.data?.message || '批量向量化任务失败')
  } finally {
    segmentVectorizing.value = false
  }
}
function selectSegmentStatus(status = '') {
  segmentStatusTab.value = status
  segmentPage.value = 1
}
function openSegment(segment) {
  selectedSegment.value = segment
  modal.value = 'segment'
}
function openSource(source) {
  const segment = segments.value.find(item => String(item.id) === String(source?.segmentId))
  if (!segment) return notify('来源片段已不存在，请刷新片段列表后重试')
  openSegment(segment)
}
async function copySegmentContent() {
  if (!selectedSegment.value?.content) return
  try {
    await navigator.clipboard.writeText(selectedSegment.value.content)
    notify('片段内容已复制')
  } catch {
    notify('复制失败，请手动选择内容')
  }
}
async function resetUser(user) {
  try { await api.resetPassword(user.id); notify(`已为 ${user.nickname} 生成临时密码 Welcome@123`) }
  catch (error) { notify(error.response?.data?.message || '重置密码失败') }
}
async function deleteUser(user) {
  if (user.username === 'admin') return notify('管理员账号不可删除')
  try { await api.deleteUser(user.id); await loadBackendData(); notify(`已删除用户 ${user.nickname}`) }
  catch (error) { notify(error.response?.data?.message || '删除用户失败') }
}
async function addUser() {
  if (!newUser.value.username || !newUser.value.nickname) return notify('请完善账号和昵称')
  try { await api.createUser(newUser.value); newUser.value = { username: '', nickname: '', password: 'Welcome@123' }; modal.value = null; await loadBackendData(); notify('用户已创建') }
  catch (error) { notify(error.response?.data?.message || '用户创建失败') }
}
function modelTestSignature(draft) {
  return [draft?.type, draft?.endpoint?.trim(), draft?.model?.trim(), draft?.key || ''].join('|')
}
function modelDiscoveryKey(draft) {
  return [draft?.type, draft?.endpoint?.trim(), draft?.key || ''].join('|')
}
function clearModelDiscovery() {
  modelDiscoveryLoading.value = false
  modelOptions.value = []
  modelDiscoverySignature.value = ''
  modelDiscoveryState.value = { status: 'idle', message: '' }
}
function editModel(type) {
  modelDraft.value = { type, ...models.value[type], testStatus: 'idle', testMessage: '', testLatencyMs: 0, testResponseBody: '', testedSignature: '' }
  clearModelDiscovery()
  modal.value = 'model'
}
async function discoverModels() {
  const draft = modelDraft.value
  if (!draft || modelDiscoveryLoading.value) return
  if (!draft.endpoint?.trim()) return
  const signature = modelDiscoveryKey(draft)
  if (modelDiscoverySignature.value === signature && modelDiscoveryState.value.status === 'success') return
  modelDiscoveryLoading.value = true
  modelDiscoverySignature.value = signature
  modelDiscoveryState.value = { status: 'loading', message: '正在获取模型列表…' }
  try {
    const result = await api.discoverModels(draft.type, { endpoint: draft.endpoint, key: draft.key || '' })
    if (modelDraft.value !== draft || modelDiscoveryKey(draft) !== signature) return
    if (!result.success || !Array.isArray(result.models) || !result.models.length) {
      modelOptions.value = []
      modelDiscoveryState.value = { status: 'failed', message: result.message || '未获取到模型列表' }
      return
    }
    modelOptions.value = result.models
    if (!result.models.includes(String(draft.model || '').trim())) draft.model = result.models[0]
    modelDiscoveryState.value = { status: 'success', message: `已获取 ${result.models.length} 个模型` }
  } catch (error) {
    if (modelDraft.value !== draft || modelDiscoveryKey(draft) !== signature) return
    modelOptions.value = []
    modelDiscoveryState.value = { status: 'failed', message: error.response?.data?.message || '模型列表获取失败，请检查 API 地址' }
  } finally {
    if (modelDraft.value === draft && modelDiscoveryKey(draft) === signature) modelDiscoveryLoading.value = false
  }
}
async function testModel() {
  const draft = modelDraft.value
  if (!draft || modelTesting.value) return
  if (!draft.endpoint?.trim()) return notify('请先填写 API 地址')
  if (!draft.model?.trim()) return notify('请先填写模型名称')
  const signature = modelTestSignature(draft)
  draft.testStatus = 'testing'
  draft.testMessage = '正在测试接口…'
  draft.testLatencyMs = 0
  draft.testResponseBody = ''
  draft.testedSignature = signature
  modelTesting.value = true
  try {
    const result = await api.testModel(draft.type, { endpoint: draft.endpoint, model: draft.model, key: draft.key || '' })
    if (modelDraft.value !== draft || modelTestSignature(draft) !== signature) return
    draft.testStatus = result.success ? 'success' : result.status === 'timeout' ? 'timeout' : 'failed'
    draft.testMessage = result.message || (result.success ? '接口响应正常' : '接口测试失败')
    draft.testLatencyMs = Number(result.latencyMs || 0)
    draft.testResponseBody = formatModelResponse(result.responseBody)
  } catch (error) {
    if (modelDraft.value !== draft || modelTestSignature(draft) !== signature) return
    draft.testStatus = error.code === 'ECONNABORTED' ? 'timeout' : 'failed'
    draft.testMessage = error.code === 'ECONNABORTED' ? '接口请求超时，请检查地址或服务状态' : (error.response?.data?.message || '接口无法连接，请检查配置')
    draft.testLatencyMs = 0
    draft.testResponseBody = ''
  } finally {
    if (modelDraft.value === draft) modelTesting.value = false
  }
}
async function testSegmentModel() {
  if (segmentModelTesting.value) return
  const config = models.value.embedding || {}
  if (!config.endpoint?.trim() || !config.model?.trim()) {
    segmentModelTestState.value = { status: 'failed', message: '请先在模型配置中保存向量模型地址和模型名称', latencyMs: 0 }
    return
  }
  segmentModelTesting.value = true
  segmentModelTestState.value = { status: 'testing', message: '正在发送测试文本“你好”…', latencyMs: 0 }
  try {
    const result = await api.testModel('embedding', {
      endpoint: config.endpoint,
      model: config.model,
      key: config.key || ''
    })
    segmentModelTestState.value = {
      status: result.success ? 'success' : result.status === 'timeout' ? 'timeout' : 'failed',
      message: result.message || (result.success ? '向量模型可用' : '向量模型不可用'),
      latencyMs: Number(result.latencyMs || 0)
    }
  } catch (error) {
    segmentModelTestState.value = {
      status: error.code === 'ECONNABORTED' ? 'timeout' : 'failed',
      message: error.code === 'ECONNABORTED' ? '接口请求超时，请检查地址或服务状态' : (error.response?.data?.message || '向量模型不可用'),
      latencyMs: 0
    }
  } finally {
    segmentModelTesting.value = false
  }
}
function formatModelResponse(responseBody) {
  if (!responseBody) return ''
  try {
    return JSON.stringify(JSON.parse(responseBody), null, 2)
  } catch {
    return String(responseBody)
  }
}
async function saveModel() {
  try { await api.updateModel(modelDraft.value.type, modelDraft.value); modal.value = null; await loadBackendData(); notify('模型配置已保存') }
  catch (error) { notify(error.response?.data?.message || '模型配置保存失败') }
}
async function sendQuestion() {
  const question = qaInput.value.trim()
  if (!question || qaLoading.value) return
  clearQaRenderQueue()
  // The assistant is updated one character at a time by the render queue.
  // Keep it reactive so each character invalidates the template immediately
  // instead of waiting for qaLoading to change at the end of the request.
  const assistant = reactive({ role: 'assistant', content: '', sources: [], noEvidence: false, error: false, suggestions: [] })
  qaMessages.value.push({ role: 'user', content: question })
  qaMessages.value.push(assistant)
  scrollQaToBottom(true)
  qaInput.value = ''
  sourceItems.value = []
  qaLoading.value = true
  try {
    await api.streamQuestion({ question, topK: settings.value.topK, threshold: settings.value.threshold, kbId: selectedQaKb.value || null }, {
      onSources: (sources) => {
        assistant.sources = sources || []
        sourceItems.value = normalizeSources(sources || [])
      },
       onToken: (token) => { enqueueQaToken(assistant, token) },
       onSuggestions: (suggestions) => { assistant.suggestions = Array.isArray(suggestions) ? suggestions : [] },
       onError: (message) => {
         clearQaRenderQueue()
         assistant.error = true
         assistant.content = message || '回答生成失败，请检查聊天模型配置。'
       },
       onDone: (result) => {
         assistant.noEvidence = Boolean(result?.noEvidence)
       }
     })
     await waitForQaRenderQueue()
   } catch (error) {
    clearQaRenderQueue()
    assistant.error = true
    assistant.content = error.message || '问答服务暂时不可用，请检查后端与模型服务。'
    qaLoading.value = false
  } finally {
    qaLoading.value = false
  }
}
function askRecommendedQuestion(question) {
  const nextQuestion = String(question || '').trim()
  if (!nextQuestion || qaLoading.value) return
  qaInput.value = nextQuestion
  sendQuestion()
}
async function saveSettings() {
  const payload = { ...normalizeRetrievalSettings(settings.value), history: Math.min(20, Math.max(2, Math.round(Number(settings.value.history) || 6))) }
  settings.value = { ...settings.value, ...payload }
  try {
    await persistSettings(payload)
  } catch (error) {
    notify(error.response?.data?.message || '系统配置保存失败')
  }
}
async function saveProfile() {
  try {
    const result = await api.updateMe({ nickname: profileNickname.value })
    currentUser.value = { ...currentUser.value, nickname: result.nickname || profileNickname.value }
    localStorage.setItem('kb_admin_user', JSON.stringify(currentUser.value))
    notify('个人信息已保存')
  } catch (error) { notify(error.response?.data?.message || '个人信息保存失败') }
}
async function changePassword() {
  try {
    await api.updatePassword(profilePassword.value)
    profilePassword.value = { currentPassword: '', newPassword: '', confirmPassword: '' }
    logout()
  } catch (error) { notify(error.response?.data?.message || '密码修改失败') }
}
function logout() { localStorage.removeItem('kb_admin_session'); localStorage.removeItem('kb_admin_token'); localStorage.removeItem('kb_admin_user'); router.push('/admin'); ElMessage.success('已安全退出') }
function formatNumber(value) { return Number(value).toLocaleString('en-US') }
function handleQaSettingsOutsideClick(event) {
  if (!qaSettingsOpen.value) return
  if (event.target instanceof Element && !event.target.closest('.qa-settings-control')) closeQaSettings()
}
onMounted(() => {
  document.addEventListener('click', handleDocumentStatusTabClick)
  document.addEventListener('click', handleQaSettingsOutsideClick)
  if (!localStorage.getItem('kb_admin_token')) router.push('/admin')
  else loadBackendData()
})
onUnmounted(() => {
  document.removeEventListener('click', handleDocumentStatusTabClick)
  document.removeEventListener('click', handleQaSettingsOutsideClick)
  parseAnimationFrames.forEach((frame) => window.cancelAnimationFrame(frame))
  clearQaRenderQueue()
})
</script>

<template>
  <div class="admin-app">
    <aside class="sidebar" :class="{ 'is-open': mobileMenu }">
      <div class="sidebar-brand" @click="go('dashboard')"><span class="brand-symbol">✳</span><div><b>澄明</b><small>KNOWLEDGE OS</small></div></div>
      <div class="workspace-select"><div class="workspace-icon">C</div><div><small>当前工作区</small><strong>澄明科技 · 主库</strong></div><ChevronDown :size="15" /></div>
      <nav class="main-nav">
        <div v-for="group in navGroups" :key="group.label" class="nav-group">
          <div class="nav-label">{{ group.label }}</div>
          <button v-for="item in group.items" :key="item.key" class="nav-item" :class="{ active: currentSection === item.key }" @click="go(item.key)">
            <component :is="item.icon" :size="17" stroke-width="1.8" /><span>{{ item.label }}</span><i v-if="item.badge">{{ item.badge }}</i>
          </button>
        </div>
      </nav>
      <div class="sidebar-bottom"><div class="pipeline-chip"><span class="pulse-dot"></span><div><b>{{ dashboardMetrics.milvusReady ? 'RAG 链路正常' : 'RAG 链路检查中' }}</b><small>Milvus · {{ dashboardMetrics.milvusReady ? '在线' : '离线' }}</small></div><ChevronRight :size="15" /></div><div class="sidebar-version">v1.1.0 <span>·</span> BUILD 092</div></div>
    </aside>

    <div v-if="mobileMenu" class="mobile-scrim" @click="mobileMenu = false"></div>
    <main class="main-shell">
      <header class="topbar">
        <button class="mobile-menu" @click="mobileMenu = !mobileMenu"><Menu :size="21" /></button>
        <div class="breadcrumbs"><span>澄明知识库</span><ChevronRight :size="14" /><b>{{ currentMeta.title }}</b></div>
        <div class="topbar-actions"><span class="connection"><span class="pulse-dot"></span> {{ dashboardMetrics.milvusReady && dashboardMetrics.redisCache ? '服务在线' : '服务检查中' }}</span><button class="icon-button" title="帮助"><CircleHelp :size="19" /></button><div class="profile-trigger" @click="showProfileMenu = !showProfileMenu"><div class="avatar avatar-small">{{ currentUser.nickname?.slice(0, 1) || '管' }}</div><div class="profile-label"><b>{{ currentUser.nickname || '管理员' }}</b><small>{{ currentUser.role === 'admin' ? '管理员' : currentUser.role || '管理员' }}</small></div><ChevronDown :size="14" /></div><div v-if="showProfileMenu" class="profile-menu"><button @click="go('profile'); showProfileMenu = false"><UserRound :size="15" /> 个人信息</button><button @click="logout"><LogOut :size="15" /> 退出登录</button></div></div>
      </header>

      <div class="page-scroll">
        <section class="page-heading"><div><div class="eyebrow">{{ currentMeta.eyebrow }}</div><h1>{{ currentMeta.title }}</h1><p>{{ currentMeta.desc }}</p></div><div class="heading-actions"><button v-if="currentSection === 'knowledge'" class="secondary-button" @click="openKbForm()"><Plus :size="17" /> 新建知识库</button><button v-if="currentSection === 'segments'" type="button" class="secondary-button segment-model-test-trigger" :class="{ 'is-testing': segmentModelTestState.status === 'testing', 'is-success': segmentModelTestState.status === 'success' }" :disabled="segmentModelTesting" @click="testSegmentModel"><LoaderCircle v-if="segmentModelTestState.status === 'testing'" :size="16" class="spin" /><CircleCheck v-else-if="segmentModelTestState.status === 'success'" :size="16" /><Check v-else :size="16" /> {{ segmentModelTestState.status === 'testing' ? '测试中…' : '测试模型可用性' }}</button><button v-if="currentSection === 'segments'" class="primary-button" :disabled="segmentVectorizing || (!dashboardMetrics.pendingSegments && !dashboardMetrics.failedSegments)" @click="vectorizeAll"><LoaderCircle v-if="segmentVectorizing" :size="17" class="spin" /><Zap v-else :size="17" /> {{ segmentVectorizing ? `向量化中 ${segmentBatchProgressPercent}%` : dashboardMetrics.pendingSegments || dashboardMetrics.failedSegments ? '批量向量化' : '已全部向量化' }}</button><button v-if="currentSection === 'qa'" class="secondary-button" @click="newQaSession"><RotateCcw :size="16" /> 新建会话</button></div></section>

        <!-- dashboard -->
        <template v-if="currentSection === 'dashboard'">
          <section class="stats-grid"><div v-for="stat in stats" :key="stat.label" class="stat-card"><div class="stat-top"><span class="stat-label">{{ stat.label }}</span><span class="stat-icon" :class="stat.tone"><component :is="stat.icon" :size="18" /></span></div><div class="stat-value">{{ stat.value }}</div><div class="stat-foot"><span class="trend-up">{{ stat.change }}</span><span>实时数据</span></div></div></section>
          <section class="dashboard-grid"><div class="panel pipeline-panel"><div class="panel-heading"><div><div class="eyebrow">INGESTION PIPELINE</div><h2>知识入库链路</h2></div><button class="text-button" @click="go('documents')">查看文档 <ArrowUpRight :size="15" /></button></div><div class="pipeline-visual"><div class="pipeline-line" :style="{ '--pipeline-progress': `${completionRate}%` }"></div><div class="pipeline-step"><span class="pipeline-node completed"><Check :size="16" /></span><b>文档上传</b><small>{{ formatNumber(dashboardMetrics.documents) }} 份</small></div><div class="pipeline-step"><span class="pipeline-node completed"><Check :size="16" /></span><b>文本解析</b><small>{{ formatNumber(dashboardMetrics.parsedDocuments) }} 份</small></div><div class="pipeline-step"><span class="pipeline-node completed"><Check :size="16" /></span><b>片段切分</b><small>{{ formatNumber(dashboardMetrics.segments) }} 段</small></div><div class="pipeline-step"><span class="pipeline-node active"><Zap :size="16" /></span><b>向量化</b><small>{{ formatNumber(dashboardMetrics.pendingSegments) }} 待处理</small></div><div class="pipeline-step"><span class="pipeline-node future"><Database :size="16" /></span><b>Milvus 入库</b><small>{{ formatNumber(dashboardMetrics.vectorized) }} 条</small></div></div><div class="pipeline-progress"><div><span>向量化完成度</span><b>{{ completionRate }}%</b></div><div class="progress-track"><span :style="{ width: `${completionRate}%` }"></span></div></div></div>
            <div class="panel activity-panel"><div class="panel-heading"><div><div class="eyebrow">RECENT ACTIVITY</div><h2>最近动态</h2></div><button class="more-button" @click="go('documents')"><MoreHorizontal :size="19" /></button></div><div class="activity-list"><div v-for="doc in documents.slice(0, 4)" :key="doc.id" class="activity-row"><span class="activity-mark" :class="doc.status === 'success' ? 'green' : doc.status === 'failed' ? 'orange' : 'blue'"><FileCheck2 v-if="doc.status === 'success'" :size="15" /><CircleX v-else-if="doc.status === 'failed'" :size="15" /><Zap v-else :size="15" /></span><div><b>{{ doc.statusText }}</b><p>{{ doc.name }}</p></div><time>{{ doc.time }}</time></div><div v-if="!documents.length" class="empty-state">暂无文档活动</div></div></div></section>
          <section class="lower-grid"><div class="panel kb-overview"><div class="panel-heading"><div><div class="eyebrow">KNOWLEDGE BASES</div><h2>知识库概览</h2></div><button class="text-button" @click="go('knowledge')">全部知识库 <ArrowUpRight :size="15" /></button></div><div class="kb-mini-list"><div v-for="kb in kbs.slice(0, 3)" :key="kb.id" class="kb-mini-row"><span class="kb-color" :class="kb.color"></span><div class="kb-mini-name"><b>{{ kb.name }}</b><small>{{ kb.desc }}</small></div><div class="kb-mini-stat"><b>{{ kb.docs }}</b><small>文档</small></div><div class="kb-mini-stat"><b>{{ formatNumber(kb.segments) }}</b><small>片段</small></div><ChevronRight :size="17" /></div></div></div><div class="panel quick-panel"><div class="panel-heading"><div><div class="eyebrow">QUICK ACTIONS</div><h2>快捷操作</h2></div></div><div class="quick-actions"><button @click="go('documents')"><UploadCloud :size="18" /><span>上传文档</span><ArrowUpRight :size="15" /></button><button @click="openKbForm()"><Database :size="18" /><span>创建知识库</span><ArrowUpRight :size="15" /></button><button @click="go('qa')"><MessageSquareText :size="18" /><span>验证问答</span><ArrowUpRight :size="15" /></button></div></div></section>
        </template>

        <!-- knowledge -->
        <template v-else-if="currentSection === 'knowledge'">
          <section class="accent-banner"><div class="banner-icon"><Database :size="23" /></div><div><b>知识库是企业语境的第一层</b><p>为不同业务资料设置独立的切片策略，检索会更接近真实问题的上下文。</p></div><div class="banner-metric"><span>总知识库</span><strong>{{ formatNumber(kbs.length) }}</strong></div></section>
           <section class="kb-card-grid"><article v-for="kb in kbs" :key="kb.id" class="kb-card"><div class="kb-card-top"><span class="kb-color large" :class="kb.color"></span><div class="kb-card-actions"><button class="more-button" title="编辑知识库" @click="openKbForm(kb)"><Pencil :size="15" /></button><button class="more-button danger" title="删除知识库" @click.stop="deleteKb(kb)"><Trash2 :size="15" /></button></div></div><h3>{{ kb.name }}</h3><p>{{ kb.desc }}</p><div class="kb-card-stats"><div><b>{{ kb.docs }}</b><span>文档</span></div><div><b>{{ formatNumber(kb.segments) }}</b><span>片段</span></div><div><b>{{ kb.chunk }}</b><span>Chunk</span></div></div><div class="kb-card-foot"><span>更新于 {{ kb.updated }}</span><button @click="openKbDocuments(kb)">进入知识库 <ChevronRight :size="14" /></button></div></article></section>
          <section class="panel table-panel"><div class="panel-heading"><div><div class="eyebrow">CHUNK STRATEGY</div><h2>切片策略一览</h2></div><span class="heading-note"><ShieldCheck :size="15" /> 所有参数已校验</span></div><div class="strategy-table"><div class="strategy-head"><span>知识库</span><span>chunkSize</span><span>overlapSize</span><span>策略说明</span><span>状态</span></div><div v-for="kb in kbs" :key="`${kb.id}-strategy`" class="strategy-row"><span class="strategy-name"><i class="kb-color" :class="kb.color"></i>{{ kb.name }}</span><span>{{ kb.chunk }} 字符</span><span>{{ kb.overlap }} 字符</span><span class="muted">保留 {{ Math.round((kb.overlap / kb.chunk) * 100) }}% 上下文重叠</span><span><em class="status-pill success"><CircleCheck :size="13" /> 生效中</em></span></div></div></section>
        </template>

        <!-- documents -->
        <template v-else-if="currentSection === 'documents'">
           <section class="upload-zone" :class="{ 'is-drag-over': isDragOver, disabled: !kbs.length }" @dragover.prevent="isDragOver = true" @dragleave.prevent="isDragOver = false" @drop.prevent="handleDrop"><UploadCloud :size="30" /><div class="upload-copy"><div class="upload-kicker">DOCUMENT INGESTION</div><h3>上传文档到指定知识库</h3><p>支持 PDF、DOCX、TXT、MD · 单文件不超过 100MB · 可拖拽文件到此处</p></div><div class="upload-controls"><label class="upload-target"><span>上传到知识库</span><select v-model="uploadKbId" :disabled="uploading || !kbs.length"><option v-if="!kbs.length" value="">暂无知识库</option><option v-for="kb in kbs" :key="kb.id" :value="String(kb.id)">{{ kb.name }}</option></select></label><button type="button" class="primary-button upload-trigger" :disabled="uploading || !uploadKb" @click="fileInput?.click()"><LoaderCircle v-if="uploading" :size="16" class="spin" /><UploadCloud v-else :size="16" /> {{ uploading ? `上传中 ${uploadProgress}%` : '选择文件' }}</button><input ref="fileInput" class="file-input" type="file" accept=".pdf,.docx,.txt,.md" @change="simulateUpload" /></div></section>
           <section class="panel table-panel"><div class="toolbar"><div class="filter-tabs"><button class="active">全部 <span>{{ documentCounts.all }}</span></button><button>解析中 <span>{{ documentCounts.processing }}</span></button><button>解析失败 <span>{{ documentCounts.failed }}</span></button></div><div class="toolbar-right"><div class="search-box"><Search :size="17" /><input v-model="search" placeholder="搜索文档名称" /></div><div class="filter-control"><button type="button" class="filter-button" :class="{ active: hasDocumentFilters }" @click="documentFilterOpen = !documentFilterOpen"><SlidersHorizontal :size="16" /> 筛选<span v-if="documentFilterCount" class="filter-count">{{ documentFilterCount }}</span></button><div v-if="documentFilterOpen" class="document-filter-popover" @click.stop><div class="filter-popover-head"><strong>筛选文档</strong><button type="button" class="filter-reset" :disabled="!hasDocumentFilters" @click="resetDocumentFilters">重置</button></div><label>所属知识库<select v-model="documentFilters.knowledgeBase"><option value="">全部知识库</option><option v-for="kb in kbs" :key="kb.id" :value="kb.name">{{ kb.name }}</option></select></label><label>解析状态<select v-model="documentFilters.status"><option value="">全部状态</option><option value="pending">待解析</option><option value="processing">解析中</option><option value="success">解析成功</option><option value="failed">解析失败</option></select></label><label>文件类型<select v-model="documentFilters.type"><option value="">全部类型</option><option value="PDF">PDF</option><option value="DOCX">DOCX</option><option value="TXT">TXT</option><option value="MD">MD</option></select></label><div class="filter-popover-foot"><span>已找到 {{ filteredDocuments.length }} 份文档</span><button type="button" class="primary-button" @click="documentFilterOpen = false">完成</button></div></div></div></div></div><div class="data-table"><div class="data-head"><span>文档名称</span><span>所属知识库</span><span>类型</span><span>文件大小</span><span>解析状态</span><span>片段</span><span>操作</span></div><div v-for="doc in filteredDocuments" :key="doc.id" class="data-row"><span class="doc-name"><span class="file-type" :class="doc.type.toLowerCase()">{{ doc.type }}</span><b>{{ doc.name }}</b></span><span class="muted">{{ doc.kb }}</span><span class="muted mono">.{{ doc.type.toLowerCase() }}</span><span class="muted">{{ doc.size }}</span><span class="status-cell"><em class="status-pill" :class="doc.status"><LoaderCircle v-if="doc.status === 'processing'" :size="13" class="spin" /><CircleCheck v-else-if="doc.status === 'success'" :size="13" /><CircleX v-else :size="13" />{{ isDocParsing(doc) ? `${doc.statusText} ${progressFor(doc)}%` : doc.statusText }}</em><span v-if="isDocParsing(doc)" class="parse-progress-wrap"><span class="parse-progress-track"><span :style="{ width: `${progressFor(doc)}%` }"></span></span><small>{{ progressFor(doc) }}%</small></span></span><span class="muted">{{ doc.segments ? `${doc.segments} 段` : '—' }}</span><span class="row-actions"><button title="查看文本" @click="openDoc(doc)"><Eye :size="16" /></button><button v-if="doc.canDownload" title="下载原文件" @click.stop="downloadDoc(doc)"><ArrowDownToLine :size="16" /></button><button v-if="doc.canParse && doc.status !== 'success' && !isDocParsing(doc)" class="parse-action" title="解析文档" @click="parseDoc(doc)"><RotateCcw :size="16" />{{ doc.status === 'failed' ? '重新解析' : '解析' }}</button><button v-else-if="doc.canParse && isDocParsing(doc)" class="parse-action is-processing" title="解析中" disabled><LoaderCircle :size="16" class="spin" />解析中</button><button class="danger" title="删除文档" @click.stop="deleteDoc(doc)"><Trash2 :size="16" /></button></span></div></div><div class="table-foot"><span>显示 {{ filteredDocuments.length ? 1 : 0 }}–{{ filteredDocuments.length }}，共 {{ filteredDocuments.length }} 份文档</span><div class="pagination"><button disabled>←</button><button class="active">1</button><button disabled>→</button></div></div></section>
        </template>

        <!-- segments -->
        <template v-else-if="currentSection === 'segments'">
          <section class="segment-summary"><div class="segment-summary-main"><div class="eyebrow">VECTOR INDEX</div><h2>{{ formatNumber(dashboardMetrics.segments) }} <small>知识片段</small></h2><p><span class="pulse-dot"></span> Milvus Collection · enterprise_kb_segments</p></div><div class="segment-summary-stat"><span>已向量化</span><b>{{ formatNumber(dashboardMetrics.vectorized) }}</b><div class="mini-progress"><span :style="{ width: `${completionRate}%` }"></span></div></div><div class="segment-summary-stat"><span>待处理</span><b>{{ formatNumber(dashboardMetrics.pendingSegments) }}</b><p>{{ dashboardMetrics.failedSegments ? `${dashboardMetrics.failedSegments} 条失败待重试` : '当前没有失败任务' }}</p></div></section>
          <section v-if="segmentModelTestState.status !== 'idle'" class="segment-model-test-result" :class="segmentModelTestState.status"><LoaderCircle v-if="segmentModelTestState.status === 'testing'" :size="16" class="spin" /><CircleCheck v-else-if="segmentModelTestState.status === 'success'" :size="16" /><CircleX v-else :size="16" /><span>{{ segmentModelTestState.message }}</span><small v-if="segmentModelTestState.latencyMs">{{ segmentModelTestState.latencyMs }} ms</small></section>
          <section v-if="segmentBatchProgressVisible" class="segment-batch-progress"><div class="segment-batch-progress-copy"><div><span class="eyebrow">VECTORIZE PROGRESS</span><strong>{{ segmentVectorizing ? '正在写入 Milvus' : '本次向量化已结束' }}</strong></div><span class="segment-batch-progress-count">{{ segmentBatchProgress.completed }} / {{ segmentBatchProgress.total }} 条 · {{ segmentBatchProgressPercent }}%</span></div><div class="segment-batch-progress-track"><span :style="{ width: `${segmentBatchProgressPercent}%` }"></span></div><div class="segment-batch-progress-foot"><span>{{ segmentVectorizing ? '逐条处理片段，页面会持续更新状态' : `成功 ${segmentBatchProgress.success} 条${segmentBatchProgress.failed ? `，失败 ${segmentBatchProgress.failed} 条` : ''}` }}</span><span v-if="segmentVectorizing" class="progress-live"><i></i>处理中</span></div></section>
          <section class="panel table-panel segment-table-panel"><div class="toolbar segment-toolbar"><div class="filter-tabs"><button :class="{ active: !segmentStatusTab }" @click="selectSegmentStatus()">全部片段 <span>{{ dashboardMetrics.segments }}</span></button><button :class="{ active: segmentStatusTab === 'unvectorized' }" @click="selectSegmentStatus('unvectorized')">未向量化 <span>{{ dashboardMetrics.pendingSegments }}</span></button><button :class="{ active: segmentStatusTab === 'failed' }" @click="selectSegmentStatus('failed')">向量化失败 <span>{{ dashboardMetrics.failedSegments }}</span></button></div><div class="toolbar-right"><label class="segment-filter-select"><span>知识库</span><select v-model="segmentKbFilter"><option value="">全部知识库</option><option v-for="kb in kbs" :key="kb.id" :value="kb.name">{{ kb.name }}</option></select></label><div class="search-box"><Search :size="17" /><input v-model="segmentSearch" placeholder="搜索片段内容、文档或知识库" /></div></div></div><div v-if="!visibleSegments.length" class="empty-state segment-empty-state"><Boxes :size="25" /><p>没有符合条件的片段</p><button v-if="segmentSearch || segmentStatusTab || segmentKbFilter" class="text-button" @click="segmentSearch = ''; segmentStatusTab = ''; segmentKbFilter = ''">清除筛选</button></div><div v-else class="segment-list"><div v-for="segment in pagedSegments" :key="segment.id" class="segment-row"><div class="segment-index">{{ segment.index }}</div><div class="segment-content"><div><span class="segment-doc"><FileText :size="14" /> {{ segment.doc }}</span><span class="dot-divider">·</span><span class="muted">{{ segment.kb }}</span></div><p>{{ segment.content }}</p></div><em class="status-pill" :class="segment.status === 'done' ? 'success' : segment.status"><LoaderCircle v-if="segment.status === 'processing' || isSegmentVectorizing(segment)" :size="13" class="spin" /><CircleCheck v-else-if="segment.status === 'done'" :size="13" /><CircleAlert v-else-if="segment.status === 'failed'" :size="13" /><span v-else class="status-empty"></span>{{ isSegmentVectorizing(segment) ? '向量化中' : segment.statusText }}</em><button class="segment-vectorize-action" :class="{ 'is-processing': isSegmentVectorizing(segment), 'is-complete': segment.status === 'done' }" :disabled="segmentVectorizing || isSegmentVectorizing(segment)" @click.stop="vectorizeSegment(segment)" :title="segment.status === 'done' ? '重新向量化' : '向量化此片段'"><LoaderCircle v-if="isSegmentVectorizing(segment)" :size="14" class="spin" /><RotateCcw v-else-if="segment.status === 'done'" :size="14" /><Zap v-else :size="14" />{{ isSegmentVectorizing(segment) ? '向量化中' : segment.status === 'done' ? '重新向量化' : '向量化' }}</button><button class="row-detail" @click="openSegment(segment)" title="查看片段详情"><ChevronRight :size="18" /></button></div></div><div class="table-foot"><span>显示 {{ segmentPageStart }}–{{ segmentPageEnd }}，共 {{ visibleSegments.length }} 个片段</span><div class="pagination"><button :disabled="segmentPage <= 1" @click="segmentPage--">←</button><button v-for="page in segmentPageNumbers" :key="page" :class="{ active: segmentPage === page }" @click="segmentPage = page">{{ page }}</button><button :disabled="segmentPage >= segmentPageCount" @click="segmentPage++">→</button></div></div></section>
        </template>

        <!-- qa -->
        <template v-else-if="currentSection === 'qa'">
          <section class="qa-toolbar"><div class="qa-live"><span class="pulse-dot"></span><b>RAG 问答链路</b><span>实时验证环境</span></div><div class="model-selects"><label>聊天模型 <select disabled><option>{{ models.chat?.model || '未配置' }}</option></select></label><label>知识库 <select v-model="selectedQaKb"><option value="">全部知识库</option><option v-for="kb in kbs" :key="kb.id" :value="kb.id">{{ kb.name }}</option></select></label><div class="qa-settings-control" @click.stop><button type="button" class="qa-setting" :class="{ 'is-open': qaSettingsOpen }" :aria-expanded="qaSettingsOpen" aria-label="调整检索参数" @click="toggleQaSettings"><Gauge :size="16" /><span>TopK {{ settings.topK }} · 阈值 {{ Number(settings.threshold).toFixed(2) }}</span><ChevronDown :size="13" class="qa-setting-chevron" /></button><div v-if="qaSettingsOpen" class="qa-settings-popover" role="dialog" aria-label="检索参数设置" @keydown.esc="closeQaSettings"><div class="qa-settings-popover-head"><div><b>检索参数</b><span>调整后保存即可用于新问答</span></div><button type="button" class="qa-settings-close" aria-label="关闭设置" @click="closeQaSettings"><X :size="14" /></button></div><label class="qa-setting-field"><span><b>TopK</b><small>召回片段数量</small></span><div class="number-input qa-number-input"><button type="button" aria-label="减少 TopK" @click="qaSettingsDraft.topK = Math.max(1, qaSettingsDraft.topK - 1)">−</button><input v-model.number="qaSettingsDraft.topK" type="number" min="1" max="20" inputmode="numeric" aria-label="TopK 数量" /><button type="button" aria-label="增加 TopK" @click="qaSettingsDraft.topK = Math.min(20, qaSettingsDraft.topK + 1)">＋</button></div></label><label class="qa-setting-field qa-threshold-field"><span><b>相似度阈值</b><small>可设置 0.20–1.00，低于此分数的片段不参与回答</small></span><div class="qa-threshold-value"><input v-model.number="qaSettingsDraft.threshold" type="number" min="0.2" max="1" step="0.01" inputmode="decimal" aria-label="相似度阈值" /><span>/ 1.00</span></div><input v-model.number="qaSettingsDraft.threshold" type="range" min="0.2" max="1" step="0.01" aria-label="相似度阈值滑块" /></label><div class="qa-settings-popover-foot"><button type="button" class="secondary-button small" @click="closeQaSettings">取消</button><button type="button" class="primary-button small" :disabled="qaSettingsSaving" @click="applyQaSettings"><LoaderCircle v-if="qaSettingsSaving" :size="14" class="spin" /><Check v-else :size="14" />{{ qaSettingsSaving ? '保存中…' : '应用设置' }}</button></div></div></div></div></section>
          <section v-if="!qaModelReady" class="qa-config-note"><CircleAlert :size="18" /><div><b>聊天模型尚未配置</b><span>资料检索仍可执行，但没有聊天模型就无法生成最终回答。</span></div><button class="secondary-button small" @click="go('models')"><Bot :size="14" /> 去配置</button></section>
          <section class="qa-layout"><div class="panel chat-panel"><div class="chat-head"><div><div class="eyebrow">CURRENT SESSION</div><h2>问答验证会话</h2></div><div class="chat-head-status" :class="{ 'is-ready': qaModelReady }"><span class="pulse-dot"></span>{{ qaStatusText }}</div></div><div ref="qaMessageList" class="message-list"><div v-if="!qaMessages.length" class="empty-chat"><Sparkles :size="25" /><p>输入一个真实问题，开始验证。</p></div><div v-for="(message, index) in qaMessages" :key="index" class="message" :class="message.role"><div v-if="message.role === 'assistant'" class="assistant-avatar">✳</div><div class="message-body"><span class="message-role">{{ message.role === 'user' ? '你' : '澄明 AI' }}</span><div class="message-bubble"><div v-if="message.role === 'assistant'" class="markdown-content" v-html="renderMarkdown(message.content)"></div><template v-else>{{ message.content }}</template><span v-if="qaLoading && index === qaMessages.length - 1 && message.role === 'assistant'" class="typing-caret"></span></div><div v-if="message.role === 'assistant' && message.error" class="evidence-tag qa-error"><CircleX :size="13" /> 回答生成失败，请检查聊天模型配置</div><div v-else-if="message.role === 'assistant' && message.sources?.length" class="evidence-tag"><ShieldCheck :size="13" /> 基于 {{ message.sources.length }} 个企业资料片段</div><div v-else-if="message.role === 'assistant' && message.noEvidence" class="evidence-tag"><CircleAlert :size="13" /> 未检索到达到阈值的企业资料</div><div v-if="message.role === 'assistant' && !message.error && message.suggestions?.length" class="qa-recommendations"><div class="qa-recommendations-head"><Sparkles :size="14" /><span>你还可以问</span></div><button v-for="suggestion in message.suggestions" :key="suggestion" type="button" :disabled="qaLoading" @click="askRecommendedQuestion(suggestion)">{{ suggestion }}<ArrowUpRight :size="13" /></button></div></div></div></div><form class="chat-input" @submit.prevent="sendQuestion"><textarea v-model="qaInput" placeholder="输入问题，支持多轮追问…" rows="1" @keydown.enter.exact.prevent="sendQuestion"></textarea><button type="submit" :disabled="qaLoading || !qaInput.trim()"><Send :size="17" /></button></form><div class="chat-foot"><span><KeyRound :size="13" /> 历史上下文 {{ settings.history }} 条（Redis）</span><span>Enter 发送 · Shift + Enter 换行</span></div></div><aside class="panel sources-panel"><div class="sources-head"><div><div class="eyebrow">RETRIEVED SOURCES</div><h2>命中来源</h2></div><span class="source-count">{{ sourceItems.length.toString().padStart(2, '0') }}</span></div><div class="source-filter"><span>相似度排序 · Milvus</span><ChevronDown :size="15" /></div><div v-if="!sourceItems.length" class="empty-state source-empty-state"><span class="source-empty-icon"><CircleAlert :size="18" /></span><strong>暂无达到阈值的命中片段</strong><p>提交问题后，命中的文档片段会显示在这里</p></div><div v-else class="source-list"><article v-for="source in sourceItems" :key="`${source.title}-${source.index}`" class="source-card"><div class="source-card-head"><span class="source-marker" :class="source.color"></span><div><b>{{ source.title }}</b><small>{{ source.type }} · {{ source.index }}</small></div><strong>{{ source.score }}</strong></div><p>{{ source.excerpt }}</p><button @click="openSource(source)" class="source-link">查看片段原文 <ArrowUpRight :size="14" /></button></article></div><div class="source-footer"><CircleCheck :size="15" /> 过滤阈值 {{ settings.threshold }} 以下的片段未进入上下文</div></aside></section>
        </template>

        <!-- models -->
        <template v-else-if="currentSection === 'models'">
          <section class="info-strip"><Bot :size="21" /><div><b>通过 OpenAI 兼容接口连接模型</b><p>支持 LM Studio、Ollama、Llama、vLLM 等本地或自托管服务。API Key 可留空。</p></div><a href="https://platform.openai.com/docs/api-reference" target="_blank">接口规范 <ArrowUpRight :size="14" /></a></section>
          <section class="model-grid"><article v-for="type in ['chat', 'embedding']" :key="type" class="panel model-card"><div class="model-card-head"><div class="model-icon" :class="type"><MessageSquareText v-if="type === 'chat'" :size="20" /><Boxes v-else :size="20" /></div><div><div class="eyebrow">{{ type === 'chat' ? 'CHAT MODEL' : 'EMBEDDING MODEL' }}</div><h2>{{ type === 'chat' ? '聊天模型' : '向量模型' }}</h2></div><em class="status-pill" :class="models[type].status === 'active' ? 'success' : 'disabled'"><CircleCheck v-if="models[type].status === 'active'" :size="13" />{{ models[type].status === 'active' ? '已启用' : '已停用' }}</em></div><div class="model-details"><div><span>配置名称</span><b>{{ models[type].name || '未配置' }}</b></div><div><span>服务提供方</span><b>{{ models[type].provider || '—' }}</b></div><div><span>模型名称</span><b class="mono">{{ models[type].model || '—' }}</b></div><div><span>API 地址</span><b class="mono">{{ models[type].endpoint || '—' }}</b></div><div><span>API Key</span><b class="mono">{{ models[type].key ? '••••••••••••' : '未设置（本地服务）' }}</b></div></div><div class="model-card-foot"><span>更新于 {{ models[type].updated || '—' }}</span><button class="secondary-button small" @click="editModel(type)"><Pencil :size="14" /> 编辑配置</button></div></article></section>
          <section class="panel model-note"><div class="note-icon"><Zap :size="18" /></div><div><h3>两个模型，两个职责</h3><p>聊天模型负责理解上下文并生成回答；向量模型将文档与问题映射到同一语义空间，负责找到真正相关的片段。配置保存后，新的问答与向量化任务会立即使用。</p></div></section>
        </template>

        <!-- users -->
        <template v-else-if="currentSection === 'users'">
          <section class="user-summary"><div><span>当前用户数</span><b>{{ users.length }}</b><small>位系统成员</small></div><div><span>统一权限</span><b>管理员</b><small>所有账号拥有完整权限</small></div><div><span>账号状态</span><b>已启用</b><small>所有账号正常使用</small></div><div class="user-summary-art"><Users :size="72" /></div></section>
          <section class="panel table-panel"><div class="toolbar"><div><div class="eyebrow">MEMBERS / {{ users.length }}</div><h2>系统成员</h2></div><div class="toolbar-right"><div class="search-box"><Search :size="17" /><input v-model="search" placeholder="搜索账号或昵称" /></div><button class="primary-button" @click="modal = 'user'"><Plus :size="17" /> 新增用户</button></div></div><div class="data-table user-table"><div class="data-head"><span>成员</span><span>角色</span><span>最后活跃</span><span>加入时间</span><span></span></div><div v-for="user in visibleUsers" :key="user.id" class="data-row"><span class="member-name"><div class="avatar admin">{{ user.avatar }}</div><div><b>{{ user.nickname }}</b><small>@{{ user.username }}</small></div></span><span><em class="role-pill" title="统一管理员权限"><ShieldCheck :size="12" />管理员</em></span><span class="muted">{{ user.last }}</span><span class="muted">{{ user.joined || '—' }}</span><span class="row-actions"><button title="重置密码" @click="resetUser(user)"><KeyRound :size="16" /></button><button v-if="user.username !== 'admin'" title="删除" @click="deleteUser(user)"><Trash2 :size="16" /></button><span v-else class="protected-user" title="知识库管理员账号不可删除"><ShieldCheck :size="16" /></span></span></div></div><div class="table-foot"><span>显示 {{ visibleUsers.length }} 位成员</span><div class="pagination"><button disabled>←</button><button class="active">1</button><button disabled>→</button></div></div></section>
        </template>

        <!-- settings -->
        <template v-else-if="currentSection === 'settings'">
          <section class="settings-layout"><div class="panel settings-panel"><div class="panel-heading"><div><div class="eyebrow">RETRIEVAL PARAMETERS</div><h2>检索参数</h2></div><span class="settings-icon"><Gauge :size="19" /></span></div><p class="panel-desc">决定系统从向量库召回多少片段，以及哪些片段有资格进入模型上下文。</p><div class="setting-field"><div><label>TopK <span>召回片段数量</span></label><p>建议范围 3–10，数量越多上下文越丰富。</p></div><div class="number-input"><button type="button" @click="settings.topK = Math.max(1, settings.topK - 1)">−</button><input v-model.number="settings.topK" type="number" min="1" max="20" /><button type="button" @click="settings.topK = Math.min(20, settings.topK + 1)">＋</button></div></div><div class="range-field"><div><label>相似度阈值 <span>{{ Number(settings.threshold).toFixed(2) }}</span></label><p>可设置 0.20–1.00，低于该分数的片段不会进入 Prompt。</p></div><input v-model.number="settings.threshold" type="range" min="0.2" max="1" step="0.01" /><div class="range-labels"><span>0.20</span><span>1.00</span></div></div></div><div class="panel settings-panel"><div class="panel-heading"><div><div class="eyebrow">CONVERSATION MEMORY</div><h2>会话体验</h2></div><span class="settings-icon purple"><MessageSquareText :size="19" /></span></div><p class="panel-desc">控制多轮会话中携带的历史消息数量，帮助模型理解“它”“上述”等指代。</p><div class="setting-field"><div><label>历史消息条数 <span>每轮问答携带</span></label><p>超过上限时，系统将优先保留最近的消息。</p></div><div class="number-input"><button type="button" @click="settings.history = Math.max(2, settings.history - 1)">−</button><input v-model.number="settings.history" type="number" min="2" max="20" /><button type="button" @click="settings.history = Math.min(20, settings.history + 1)">＋</button></div></div><div class="memory-preview"><div class="memory-line active"><span>01</span><i></i><b>公司的差旅报销标准？</b></div><div class="memory-line active"><span>02</span><i></i><b>一线城市是多少？</b></div><div class="memory-line faint"><span>03</span><i></i><b>更早的历史消息…</b></div></div></div></section><div class="settings-save"><span><CircleCheck :size="16" /> 修改保存后立即对新问答生效</span><button class="primary-button" @click="saveSettings"><Check :size="17" /> 保存全部配置</button></div>
        </template>

        <!-- profile -->
        <template v-else-if="currentSection === 'profile'">
          <section class="profile-layout"><div class="panel profile-card"><div class="profile-hero"><div class="avatar avatar-large admin">{{ currentUser.nickname?.slice(0, 1) || '管' }}</div><div><div class="eyebrow">ADMINISTRATOR</div><h2>{{ currentUser.nickname || '管理员' }}</h2><p>{{ currentUser.username || 'admin' }} · 管理员</p></div><button class="secondary-button small" @click="notify('头像上传暂未开放')"><UploadCloud :size="14" /> 更换头像</button></div><div class="profile-form"><label><span>显示昵称</span><input v-model="profileNickname" /></label><label><span>登录账号</span><input :value="currentUser.username || 'admin'" disabled /></label><label><span>个人简介</span><textarea rows="3" disabled>负责企业知识资产、模型配置与问答质量验证。</textarea></label><button class="primary-button" @click="saveProfile">保存个人信息</button></div></div><div class="panel password-card"><div class="panel-heading"><div><div class="eyebrow">ACCOUNT SECURITY</div><h2>修改密码</h2></div><KeyRound :size="19" /></div><p class="panel-desc">定期更新密码，保护知识库管理权限。</p><div class="password-fields"><label>当前密码<input v-model="profilePassword.currentPassword" type="password" placeholder="输入当前密码" /></label><label>新密码<input v-model="profilePassword.newPassword" type="password" placeholder="输入新密码" /></label><label>确认新密码<input v-model="profilePassword.confirmPassword" type="password" placeholder="再次输入新密码" /></label></div><button class="secondary-button" @click="changePassword">更新密码</button><div class="security-tip"><ShieldCheck :size="17" /><span>你的账号已开启安全登录保护</span></div></div></section>
        </template>
      </div>
    </main>

    <div v-if="toast" class="toast"><CircleCheck :size="17" /> {{ toast }}</div>
    <div v-if="modal" class="modal-backdrop" @click.self="modal = null"><section class="modal-card" :class="{ 'doc-modal': modal === 'doc', 'segment-modal': modal === 'segment' }">
      <template v-if="modal === 'kb'"><div class="modal-head"><div><div class="eyebrow">KNOWLEDGE BASE</div><h2>{{ selectedKb ? '编辑知识库' : '新建知识库' }}</h2></div><button class="close-button" @click="modal = null"><X :size="19" /></button></div><div class="modal-body"><label>知识库名称 <span class="required">*</span><input v-model="kbForm.name" placeholder="例如：研发技术文档" /></label><label>描述<textarea v-model="kbForm.desc" rows="3" placeholder="描述这个知识库包含的资料范围"></textarea></label><div class="form-row"><label>chunkSize <small>字符数</small><input v-model.number="kbForm.chunk" type="number" min="1" /></label><label>overlapSize <small>字符数</small><input v-model.number="kbForm.overlap" type="number" min="0" /></label></div><div class="modal-tip"><CircleAlert :size="16" /> 修改存量知识库的切片参数后，文档需要重新切分并向量化。</div></div><div class="modal-foot"><button class="secondary-button" @click="modal = null">取消</button><button class="primary-button" @click="saveKb">{{ selectedKb ? '保存修改' : '创建知识库' }}</button></div></template>
      <template v-else-if="modal === 'user'"><div class="modal-head"><div><div class="eyebrow">NEW MEMBER</div><h2>新增用户</h2></div><button class="close-button" @click="modal = null"><X :size="19" /></button></div><div class="modal-body"><label>登录账号 <span class="required">*</span><input v-model="newUser.username" placeholder="例如：zhang.san" /></label><label>显示昵称 <span class="required">*</span><input v-model="newUser.nickname" placeholder="例如：张三" /></label><label>初始密码<input v-model="newUser.password" /></label><div class="modal-tip"><ShieldCheck :size="16" /> 新用户创建后默认拥有管理员权限，账号始终保持启用状态。</div></div><div class="modal-foot"><button class="secondary-button" @click="modal = null">取消</button><button class="primary-button" @click="addUser">创建用户</button></div></template>
      <template v-else-if="modal === 'model'"><div class="modal-head"><div><div class="eyebrow">MODEL CONFIGURATION</div><h2>编辑{{ modelDraft.type === 'chat' ? '聊天' : '向量' }}模型</h2></div><button class="close-button" @click="modal = null"><X :size="19" /></button></div><div class="modal-body"><label>配置名称<input v-model="modelDraft.name" /></label><label>服务提供方<input v-model="modelDraft.provider" /></label><label>API 地址<div class="model-endpoint-control"><input v-model="modelDraft.endpoint" @input="clearModelDiscovery" @blur="discoverModels" placeholder="例如：http://192.168.1.10:1234/v1" /><button type="button" class="secondary-button model-discover-button" :disabled="modelDiscoveryLoading" title="从 API 获取模型列表" @click="discoverModels"><LoaderCircle v-if="modelDiscoveryLoading" :size="15" class="spin" /><RefreshCw v-else :size="15" />获取模型</button></div><small class="model-discovery-state" :class="modelDiscoveryState.status"><LoaderCircle v-if="modelDiscoveryState.status === 'loading'" :size="14" class="spin" /><CircleCheck v-else-if="modelDiscoveryState.status === 'success'" :size="14" /><CircleAlert v-else-if="modelDiscoveryState.status === 'failed'" :size="14" />{{ modelDiscoveryState.message || '填写 API 地址后获取模型列表' }}</small></label><label>模型名称 <small>从 API 自动获取</small><select v-model="modelDraft.model" class="model-select-input" :disabled="modelDiscoveryLoading || !modelOptions.length"><option value="">{{ modelOptions.length ? '请选择模型' : '请先获取模型列表' }}</option><option v-if="modelDraft.model && !modelOptions.includes(modelDraft.model)" :value="modelDraft.model">{{ modelDraft.model }}</option><option v-for="model in modelOptions" :key="model" :value="model">{{ model }}</option></select></label><label>API Key <small>可留空</small><input v-model="modelDraft.key" type="password" placeholder="本地服务可不填写" /></label><div class="model-test-panel"><div class="model-test-copy"><strong>接口连通性</strong><span>使用当前地址、模型和 API Key 发送一次真实请求</span></div><button type="button" class="secondary-button model-test-button" :class="{ 'is-testing': modelTestState.status === 'testing' }" :disabled="modelTesting" @click="testModel"><LoaderCircle v-if="modelTestState.status === 'testing'" :size="15" class="spin" /><Check v-else :size="15" />{{ modelTestState.status === 'testing' ? '测试中…' : '接口测试' }}</button></div><div v-if="modelTestState.status !== 'idle'" class="model-test-result" :class="modelTestState.status"><LoaderCircle v-if="modelTestState.status === 'testing'" :size="15" class="spin" /><CircleCheck v-else-if="modelTestState.status === 'success'" :size="15" /><CircleX v-else :size="15" /><span>{{ modelTestState.message }}</span><small v-if="modelTestState.latencyMs">{{ modelTestState.latencyMs }} ms</small></div><div v-else class="model-test-hint"><CircleAlert :size="15" />修改接口信息后，请重新测试</div><details v-if="modelTestState.responseBody" class="model-response-panel"><summary><span><FileCheck2 :size="15" />返回结果</span><small>JSON</small><ChevronDown :size="15" /></summary><pre>{{ modelTestState.responseBody }}</pre></details></div><div class="modal-foot"><button class="secondary-button" @click="modal = null">取消</button><button class="primary-button" @click="saveModel">保存配置</button></div></template>
      <template v-else-if="modal === 'segment'"><div class="modal-head"><div><div class="eyebrow">SEGMENT DETAIL / VECTOR INDEX</div><h2>片段详情</h2></div><button class="close-button" title="关闭详情" @click="modal = null"><X :size="19" /></button></div><div class="segment-detail-meta"><div><span>来源文档</span><strong>{{ selectedSegment?.doc || '未知文档' }}</strong></div><div><span>所属知识库</span><strong>{{ selectedSegment?.kb || '未知知识库' }}</strong></div><div><span>片段序号</span><strong class="mono">{{ selectedSegment?.index || '—' }}</strong></div><div><span>向量状态</span><em class="status-pill" :class="selectedSegment?.status === 'done' ? 'success' : selectedSegment?.status"><LoaderCircle v-if="selectedSegment?.status === 'processing'" :size="13" class="spin" /><CircleCheck v-else-if="selectedSegment?.status === 'done'" :size="13" /><CircleAlert v-else-if="selectedSegment?.status === 'failed'" :size="13" /><span v-else class="status-empty"></span>{{ selectedSegment?.statusText || '未知状态' }}</em></div></div><div class="segment-detail-content"><div class="segment-detail-content-head"><div><span class="eyebrow">FULL CONTENT</span><h3>片段原文</h3></div><span>{{ (selectedSegment?.content || '').length }} 字</span></div><pre>{{ selectedSegment?.content || '暂无片段内容' }}</pre></div><div class="modal-foot segment-modal-foot"><button class="secondary-button" @click="modal = null">关闭</button><button class="primary-button" :disabled="!selectedSegment?.content" @click="copySegmentContent"><Copy :size="16" />复制片段内容</button></div></template>
      <template v-else-if="modal === 'doc'"><div class="modal-head"><div><div class="eyebrow">PARSED TEXT / {{ selectedDoc.type }}</div><h2>{{ selectedDoc.name }}</h2></div><button class="close-button" @click="modal = null"><X :size="19" /></button></div><div class="doc-meta"><span class="status-pill" :class="selectedDoc.status"><LoaderCircle v-if="selectedDoc.status === 'processing'" :size="13" class="spin" /><CircleCheck v-else-if="selectedDoc.status === 'success'" :size="13" />{{ isDocParsing(selectedDoc) ? `${selectedDoc.statusText} ${progressFor(selectedDoc)}%` : selectedDoc.statusText }}</span><span>{{ selectedDoc.size }}</span><span>{{ selectedDoc.segments || 0 }} 个片段</span></div><div v-if="isDocParsing(selectedDoc)" class="modal-parse-progress"><div><span>解析进度</span><b>{{ progressFor(selectedDoc) }}%</b></div><span class="parse-progress-track"><span :style="{ width: `${progressFor(selectedDoc)}%` }"></span></span></div><pre class="parsed-text">{{ selectedDoc.text || '暂无可查看的解析文本。请在解析完成后重试。' }}</pre><div class="modal-foot"><button v-if="selectedDoc.canDownload" class="secondary-button" @click="downloadDoc(selectedDoc)"><ArrowDownToLine :size="16" /> 下载原文件</button><button class="secondary-button" @click="modal = null">关闭</button><button v-if="selectedDoc.canParse && selectedDoc.status !== 'success' && !isDocParsing(selectedDoc)" class="primary-button" @click="parseDoc(selectedDoc)">{{ selectedDoc.status === 'failed' ? '重新解析' : '解析文档' }}</button><button v-else-if="selectedDoc.canParse && isDocParsing(selectedDoc)" class="primary-button" disabled><LoaderCircle :size="16" class="spin" />解析中</button></div></template>
    </section></div>
  </div>
</template>
