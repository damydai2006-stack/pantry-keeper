import { Button, Modal } from "antd"
import { useEffect, useState } from "react"
import { isApiError } from "../api/types"

export type PhotoLoad = (photoId: string, signal: AbortSignal) => Promise<Blob>

type Phase =
  | { kind: "loading" }
  | { kind: "ready"; url: string }
  | { kind: "failed" }
  | { kind: "missing" }

const IMAGE_TYPES = new Set(["image/jpeg", "image/webp"])

export function classifyPhotoError(error: unknown): "missing" | "failed" {
  if (isApiError(error) && (error.status === 404 || error.code === "not_found")) return "missing"
  return "failed"
}

function acceptedImage(blob: Blob): boolean {
  return IMAGE_TYPES.has(blob.type.toLowerCase()) && blob.size > 0
}

function isAbortError(error: unknown): boolean {
  return typeof error === "object" && error !== null && "name" in error && (error as { name: unknown }).name === "AbortError"
}

export function PhotoEntry({
  name,
  photoId,
  onOpen,
}: {
  name: string
  photoId?: string | null
  onOpen: () => void
}) {
  const id = photoId?.trim() ?? ""
  if (!id) return <span className="photo-empty">无照片</span>
  return (
    <Button
      className="photo-entry"
      aria-haspopup="dialog"
      aria-label={`查看${name}的照片`}
      onClick={(event) => {
        event.preventDefault()
        event.stopPropagation()
        onOpen()
      }}
    >
      查看照片
    </Button>
  )
}

export function PhotoDialog({
  name,
  photoId,
  loadPhoto,
  onClose,
}: {
  name: string
  photoId: string
  loadPhoto: PhotoLoad
  onClose: () => void
}) {
  const [attempt, setAttempt] = useState(0)
  const [phase, setPhase] = useState<Phase>({ kind: "loading" })

  useEffect(() => {
    const controller = new AbortController()
    let cancelled = false
    let url: string | null = null
    setPhase({ kind: "loading" })
    loadPhoto(photoId, controller.signal).then(
      (blob) => {
        if (cancelled) return
        try {
          if (!acceptedImage(blob)) {
            setPhase({ kind: "failed" })
            return
          }
          const next = URL.createObjectURL(blob)
          if (cancelled) {
            URL.revokeObjectURL(next)
            return
          }
          url = next
          setPhase({ kind: "ready", url: next })
        } catch {
          if (!cancelled) setPhase({ kind: "failed" })
        }
      },
      (error: unknown) => {
        if (cancelled || isAbortError(error)) return
        setPhase({ kind: classifyPhotoError(error) })
      },
    )
    return () => {
      cancelled = true
      controller.abort()
      if (url) {
        URL.revokeObjectURL(url)
        url = null
      }
    }
  }, [attempt, loadPhoto, photoId])

  return (
    <Modal
      open
      title="查看照片"
      onCancel={onClose}
      footer={<Button onClick={onClose}>关闭</Button>}
      closable={false}
      transitionName=""
      maskTransitionName=""
    >
      <div className="photo-view">
        {phase.kind === "loading" ? <div role="status">正在加载照片</div> : null}
        {phase.kind === "ready" ? <img className="photo-view-img" src={phase.url} alt={`${name}的照片`} /> : null}
        {phase.kind === "failed" ? (
          <div className="photo-view-status">
            <div role="alert">照片加载失败</div>
            <Button onClick={() => setAttempt((current) => current + 1)}>重新加载</Button>
          </div>
        ) : null}
        {phase.kind === "missing" ? <div role="alert">照片已不存在</div> : null}
      </div>
    </Modal>
  )
}
