import { apiUrl } from './config'
import type { UserFileRecord } from '../types/fileUpload'

const userId = () => localStorage.getItem('user_id')

interface BackendFileItem {
  id: number
  fileName: string   // 对应 original_file_name
  fileType: string
  fileSize: number
  uploadTime: string | null
}

export const fileService = {
  async listFiles(): Promise<UserFileRecord[]> {
    const res = await fetch(apiUrl('/api/files'), {
      headers: { 'X-User-Id': userId() || '' }
    })
    if (!res.ok) throw new Error(`获取文件列表失败: ${res.status}`)
    const body = await res.json() as { files?: BackendFileItem[] }
    return (body.files || []).map(f => ({
      id: f.id,
      userId: '',
      originalFileName: f.fileName,
      fileName: f.fileName,
      fileSize: f.fileSize,
      fileType: f.fileType,
      uploadTime: f.uploadTime || '',
    }))
  },

  async downloadFile(id: number): Promise<void> {
    const url = apiUrl(`/api/files/download/${id}`)
    const res = await fetch(url, {
      headers: { 'X-User-Id': userId() || '' }
    })
    if (!res.ok) throw new Error(`下载失败: ${res.status}`)
    const blob = await res.blob()
    const blobUrl = URL.createObjectURL(blob)
    const a = document.createElement('a')
    a.href = blobUrl
    a.download = 'download'
    document.body.appendChild(a)
    a.click()
    document.body.removeChild(a)
    URL.revokeObjectURL(blobUrl)
  },

  async deleteFile(id: number): Promise<void> {
    const res = await fetch(apiUrl(`/api/files/${id}`), {
      method: 'DELETE',
      headers: { 'X-User-Id': userId() || '' }
    })
    if (!res.ok) throw new Error(`删除失败: ${res.status}`)
  },

  async uploadFile(file: File): Promise<UserFileRecord> {
    const formData = new FormData()
    formData.append('file', file)
    const res = await fetch(apiUrl('/api/files/upload'), {
      method: 'POST',
      headers: { 'X-User-Id': userId() || '' },
      body: formData
    })
    if (!res.ok) throw new Error(`上传失败: ${res.status}`)
    return res.json()
  }
}
