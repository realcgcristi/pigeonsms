import { useCallback, useEffect, useRef, useState } from 'react'
import { createPortal } from 'react-dom'
import { BlurOn, Close, Crop, Draw, Edit, RotateRight, Send, Undo } from '@/components/icons'
import { Button } from '@/components/ui/Button'

type Point = { x: number; y: number }

export function ImageEditor({
  file,
  onCancel,
  onSave,
}: {
  file: File
  onCancel: () => void
  onSave: (file: File) => void
}) {
  const canvasRef = useRef<HTMLCanvasElement>(null)
  const imageRef = useRef<HTMLImageElement | null>(null)
  const drawingRef = useRef(false)
  const lastPointRef = useRef<Point | null>(null)
  const historyRef = useRef<ImageData[]>([])
  const [rotation, setRotation] = useState(0)
  const [square, setSquare] = useState(false)
  const [blur, setBlur] = useState(0)
  const [drawing, setDrawing] = useState(false)
  const [ready, setReady] = useState(false)

  const render = useCallback(() => {
    const canvas = canvasRef.current
    const image = imageRef.current
    if (!canvas || !image) return
    const sideways = rotation % 180 !== 0
    const sourceWidth = sideways ? image.naturalHeight : image.naturalWidth
    const sourceHeight = sideways ? image.naturalWidth : image.naturalHeight
    const cropSize = Math.min(sourceWidth, sourceHeight)
    const scale = Math.min(1, 1600 / Math.max(sourceWidth, sourceHeight))
    canvas.width = Math.max(1, Math.round((square ? cropSize : sourceWidth) * scale))
    canvas.height = Math.max(1, Math.round((square ? cropSize : sourceHeight) * scale))
    const context = canvas.getContext('2d')
    if (!context) return
    context.clearRect(0, 0, canvas.width, canvas.height)
    context.save()
    context.translate(canvas.width / 2, canvas.height / 2)
    context.rotate((rotation * Math.PI) / 180)
    context.filter = blur ? `blur(${blur}px)` : 'none'
    const drawWidth = image.naturalWidth * scale
    const drawHeight = image.naturalHeight * scale
    context.drawImage(image, -drawWidth / 2, -drawHeight / 2, drawWidth, drawHeight)
    context.restore()
    historyRef.current = []
  }, [blur, rotation, square])

  useEffect(() => {
    const url = URL.createObjectURL(file)
    const image = new Image()
    image.onload = () => {
      imageRef.current = image
      setReady(true)
    }
    image.src = url
    return () => URL.revokeObjectURL(url)
  }, [file])

  useEffect(() => {
    if (ready) render()
  }, [ready, render])

  useEffect(() => {
    const previous = document.activeElement instanceof HTMLElement ? document.activeElement : null
    const onKeyDown = (event: KeyboardEvent) => {
      if (event.key === 'Escape') onCancel()
    }
    document.addEventListener('keydown', onKeyDown)
    return () => {
      document.removeEventListener('keydown', onKeyDown)
      previous?.focus()
    }
  }, [onCancel])

  const point = (event: React.PointerEvent<HTMLCanvasElement>): Point => {
    const canvas = event.currentTarget
    const rect = canvas.getBoundingClientRect()
    return {
      x: ((event.clientX - rect.left) / rect.width) * canvas.width,
      y: ((event.clientY - rect.top) / rect.height) * canvas.height,
    }
  }

  const beginDraw = (event: React.PointerEvent<HTMLCanvasElement>) => {
    if (!drawing) return
    const context = event.currentTarget.getContext('2d')
    if (!context) return
    historyRef.current.push(context.getImageData(0, 0, event.currentTarget.width, event.currentTarget.height))
    if (historyRef.current.length > 12) historyRef.current.shift()
    drawingRef.current = true
    lastPointRef.current = point(event)
    event.currentTarget.setPointerCapture(event.pointerId)
  }

  const moveDraw = (event: React.PointerEvent<HTMLCanvasElement>) => {
    if (!drawingRef.current || !drawing) return
    const context = event.currentTarget.getContext('2d')
    const previous = lastPointRef.current
    const next = point(event)
    if (!context || !previous) return
    context.save()
    context.strokeStyle = getComputedStyle(document.documentElement).getPropertyValue('--accent').trim()
    context.lineWidth = Math.max(4, event.currentTarget.width / 180)
    context.lineCap = 'round'
    context.lineJoin = 'round'
    context.beginPath()
    context.moveTo(previous.x, previous.y)
    context.lineTo(next.x, next.y)
    context.stroke()
    context.restore()
    lastPointRef.current = next
  }

  const endDraw = () => {
    drawingRef.current = false
    lastPointRef.current = null
  }

  const addText = () => {
    const canvas = canvasRef.current
    const context = canvas?.getContext('2d')
    const text = window.prompt('text to add')
    if (!canvas || !context || !text?.trim()) return
    historyRef.current.push(context.getImageData(0, 0, canvas.width, canvas.height))
    context.save()
    context.font = `700 ${Math.max(24, canvas.width / 14)}px system-ui`
    context.textAlign = 'center'
    context.textBaseline = 'middle'
    context.lineWidth = Math.max(3, canvas.width / 300)
    context.strokeStyle = 'rgba(0,0,0,.72)'
    context.fillStyle = getComputedStyle(document.documentElement).getPropertyValue('--text-primary').trim()
    context.strokeText(text.trim(), canvas.width / 2, canvas.height / 2)
    context.fillText(text.trim(), canvas.width / 2, canvas.height / 2)
    context.restore()
  }

  const undo = () => {
    const canvas = canvasRef.current
    const context = canvas?.getContext('2d')
    const image = historyRef.current.pop()
    if (context && image) context.putImageData(image, 0, 0)
  }

  const save = () => {
    const canvas = canvasRef.current
    if (!canvas) return
    canvas.toBlob(
      (blob) => {
        if (!blob) return
        const name = file.name.replace(/\.[^.]+$/, '') || 'image'
        onSave(new File([blob], `${name}-edited.jpg`, { type: 'image/jpeg' }))
      },
      'image/jpeg',
      0.92,
    )
  }

  return createPortal(
    <div className="image-editor" role="dialog" aria-modal="true" aria-label="edit image">
      <header className="image-editor__header">
        <button className="image-editor__close" type="button" onClick={onCancel} aria-label="close image editor">
          <Close />
        </button>
        <strong className="image-editor__title">edit image</strong>
        <Button leading={<Send size={17} />} onClick={save} disabled={!ready}>send</Button>
      </header>
      <div className="image-editor__stage">
        <canvas
          ref={canvasRef}
          className={drawing ? 'image-editor__canvas image-editor__canvas--drawing' : 'image-editor__canvas'}
          onPointerDown={beginDraw}
          onPointerMove={moveDraw}
          onPointerUp={endDraw}
          onPointerCancel={endDraw}
        />
      </div>
      <div className="image-editor__tools" role="toolbar" aria-label="image editing tools">
        <button type="button" title="rotate image" onClick={() => setRotation((value) => (value + 90) % 360)}>
          <RotateRight /><span>rotate</span>
        </button>
        <button
          type="button"
          className={square ? 'is-on' : ''}
          aria-pressed={square}
          title="toggle square crop"
          onClick={() => setSquare((value) => !value)}
        >
          <Crop /><span>square</span>
        </button>
        <button
          type="button"
          className={drawing ? 'is-on' : ''}
          aria-pressed={drawing}
          title="draw on image"
          onClick={() => setDrawing((value) => !value)}
        >
          <Draw /><span>draw</span>
        </button>
        <button type="button" title="add text" onClick={addText}>
          <Edit /><span>text</span>
        </button>
        <button type="button" title="undo last edit" onClick={undo}>
          <Undo /><span>undo</span>
        </button>
        <label title="blur image">
          <BlurOn />
          <span>blur</span>
          <input
            aria-label="blur amount"
            type="range"
            min="0"
            max="10"
            value={blur}
            onChange={(event) => setBlur(Number(event.target.value))}
          />
        </label>
      </div>
    </div>,
    document.body,
  )
}

export default ImageEditor
