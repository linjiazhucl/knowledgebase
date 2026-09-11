import axios from 'axios'

const REQUEST_TIMEOUT = 15000
const UPLOAD_REQUEST_TIMEOUT = 15000

const http = axios.create({
  baseURL: import.meta.env.VITE_API_BASE || '/api',
  timeout: REQUEST_TIMEOUT
})

http.interceptors.request.use((config) => {
  const token = localStorage.getItem('kb_admin_token')
  if (token) config.headers['X-Admin-Token'] = token
  return config
})

http.interceptors.response.use((response) => response, (error) => {
  if (error.response?.status === 401) {
    localStorage.removeItem('kb_admin_session')
    localStorage.removeItem('kb_admin_token')
  }
  return Promise.reject(error)
})

const unwrap = (response) => response.data?.data ?? response.data

export const api = {
  async login(username, password) { return unwrap(await http.post('/auth/login', { username, password })) },
  async me() { return unwrap(await http.get('/auth/me')) },
  async updateMe(payload) { return unwrap(await http.put('/auth/me', payload)) },
  async updatePassword(payload) { return unwrap(await http.put('/auth/password', payload)) },
  async dashboard() { return unwrap(await http.get('/dashboard')) },
  async knowledgeBases() { return unwrap(await http.get('/knowledge-bases')) },
  async createKnowledgeBase(payload) { return unwrap(await http.post('/knowledge-bases', payload)) },
  async updateKnowledgeBase(id, payload) { return unwrap(await http.put(`/knowledge-bases/${id}`, payload)) },
  async deleteKnowledgeBase(id) { return unwrap(await http.delete(`/knowledge-bases/${id}`)) },
  async documents(kbId) { return unwrap(await http.get('/documents', { params: kbId ? { kbId } : {} })) },
  async uploadDocument(file, kbId, onUploadProgress) {
    const form = new FormData()
    form.append('file', file)
    return unwrap(await http.post('/documents/upload', form, {
      params: { kbId },
      timeout: UPLOAD_REQUEST_TIMEOUT,
      onUploadProgress
    }))
  },
  async deleteDocument(id) { return unwrap(await http.delete(`/documents/${id}`)) },
  async downloadDocument(id) {
    return http.get(`/documents/${id}/download`, { responseType: 'blob' })
  },
  async parseDocument(id) { return unwrap(await http.post(`/documents/${id}/parse`)) },
  async retryDocument(id) { return unwrap(await http.post(`/documents/${id}/retry`)) },
  async segments() { return unwrap(await http.get('/segments')) },
  async vectorize() { return unwrap(await http.post('/segments/vectorize')) },
  async vectorizeSegment(id) { return unwrap(await http.post(`/segments/${id}/vectorize`)) },
  async users() { return unwrap(await http.get('/users')) },
  async createUser(payload) { return unwrap(await http.post('/users', payload)) },
  async deleteUser(id) { return unwrap(await http.delete(`/users/${id}`)) },
  async resetPassword(id) { return unwrap(await http.post(`/users/${id}/reset-password`)) },
  async models() { return unwrap(await http.get('/models')) },
  async updateModel(type, payload) { return unwrap(await http.put(`/models/${type}`, payload)) },
  async testModel(type, payload) { return unwrap(await http.post(`/models/${type}/test`, payload, { timeout: 15000 })) },
  async discoverModels(type, payload) { return unwrap(await http.post(`/models/${type}/discover`, payload, { timeout: 15000 })) },
  async settings() { return unwrap(await http.get('/settings')) },
  async updateSettings(payload) { return unwrap(await http.put('/settings', payload)) },
  async qaHistory() { return unwrap(await http.get('/qa/history')) },

  async streamQuestion(payload, { onSources, onToken, onSuggestions, onError, onDone }) {
    const token = localStorage.getItem('kb_admin_token')
    const response = await fetch(`${import.meta.env.VITE_API_BASE || '/api'}/qa/stream`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json', 'X-Admin-Token': token || '' },
      body: JSON.stringify(payload)
    })
    if (!response.ok) {
      const raw = await response.text()
      let message = raw
      try {
        const parsed = JSON.parse(raw)
        message = parsed.message || parsed.error || raw
      } catch { /* keep the plain response text */ }
      throw new Error(message || '问答服务暂时不可用')
    }
    if (!response.body) throw new Error('问答服务未建立数据流，请稍后重试')
    const reader = response.body.getReader()
    const decoder = new TextDecoder()
    let buffer = ''
    let eventName = 'message'
    const consume = (block) => {
      const lines = block.split(/\r?\n/)
      let data = ''
      lines.forEach((line) => {
        if (line.startsWith('event:')) eventName = line.slice(6).trim()
        if (line.startsWith('data:')) data += `${data ? '\n' : ''}${line.slice(5).replace(/^ /, '')}`
      })
      if (!data) return
      let parsed = data
      try { parsed = JSON.parse(data) } catch { /* token is plain text */ }
      if (eventName === 'sources') onSources?.(parsed)
      if (eventName === 'token') onToken?.(String(parsed))
      if (eventName === 'suggestions') onSuggestions?.(Array.isArray(parsed) ? parsed : [])
      if (eventName === 'error') onError?.(typeof parsed === 'object' ? parsed?.message : String(parsed))
      if (eventName === 'done') onDone?.(parsed)
      eventName = 'message'
    }
    while (true) {
      const { value, done } = await reader.read()
      buffer += decoder.decode(value || new Uint8Array(), { stream: !done })
      const blocks = buffer.split(/\r?\n\r?\n/)
      buffer = blocks.pop() || ''
      blocks.forEach(consume)
      if (done) break
    }
    if (buffer.trim()) consume(buffer)
  }
}
