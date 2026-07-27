import type { ReactNode } from 'react'
import { api } from '@/api/client'
import type { SpaceEmojiDto } from '@/api/dto'

export type InlineContext = {
  emoji?: SpaceEmojiDto[]
  onMention?: (username: string) => void
  onChannel?: (name: string) => void
  onInvite?: (code: string) => void
  big?: boolean
}

const TOKEN = /(\*\*[^*]+\*\*|\*[^*]+\*|__[^_]+__|_[^_]+_|~~[^~]+~~|`[^`]+`|::?[a-z0-9_]{2,32}::?|@[a-z0-9_.]{2,32}|#[a-z0-9-_]{1,64}|SPC-[A-Za-z0-9]{4,32}|https?:\/\/\S+)/gi

export function renderInline(text: string, ctx: InlineContext = {}): ReactNode[] {
  const out: ReactNode[] = []
  let index = 0
  let key = 0
  for (const match of text.matchAll(TOKEN)) {
    const start = match.index ?? 0
    if (start > index) out.push(text.slice(index, start))
    const token = match[0]
    index = start + token.length
    key += 1

    if (token.startsWith('**') && token.endsWith('**')) {
      out.push(<strong key={key}>{token.slice(2, -2)}</strong>)
    } else if (token.startsWith('__') && token.endsWith('__')) {
      out.push(<u key={key}>{token.slice(2, -2)}</u>)
    } else if (token.startsWith('~~') && token.endsWith('~~')) {
      out.push(<s key={key}>{token.slice(2, -2)}</s>)
    } else if (token.startsWith('`') && token.endsWith('`')) {
      out.push(
        <code className="md-code" key={key}>
          {token.slice(1, -1)}
        </code>,
      )
    } else if ((token.startsWith('*') && token.endsWith('*')) || (token.startsWith('_') && token.endsWith('_'))) {
      out.push(<em key={key}>{token.slice(1, -1)}</em>)
    } else if (token.startsWith('SPC-')) {
      out.push(
        <button type="button" className="md-invite" key={key} onClick={() => ctx.onInvite?.(token)}>
          {token}
        </button>,
      )
    } else if (token.startsWith('http')) {
      out.push(
        <a className="md-link" key={key} href={token} target="_blank" rel="noreferrer noopener">
          {token}
        </a>,
      )
    } else if (token.startsWith('@')) {
      out.push(
        <button type="button" className="md-mention" key={key} onClick={() => ctx.onMention?.(token.slice(1))}>
          {token}
        </button>,
      )
    } else if (token.startsWith('#')) {
      out.push(
        <button type="button" className="md-channel" key={key} onClick={() => ctx.onChannel?.(token.slice(1))}>
          {token}
        </button>,
      )
    } else {
      const name = token.replace(/^:+|:+$/g, '')
      const found = ctx.emoji?.find((e) => e.name === name)
      if (found) {
        out.push(
          <img
            key={key}
            className={ctx.big ? 'md-emoji md-emoji--big' : 'md-emoji'}
            src={api.mediaUrl(found.media_key ?? '')}
            alt={`:${name}:`}
            title={`:${name}:`}
          />,
        )
      } else {
        out.push(token)
      }
    }
  }
  if (index < text.length) out.push(text.slice(index))
  return out
}

export function renderMarkdown(content: string, ctx: InlineContext = {}): ReactNode {
  const blocks: ReactNode[] = []
  const lines = content.split('\n')
  let fence: string[] | null = null
  let list: ReactNode[] = []
  let key = 0

  const flushList = () => {
    if (list.length === 0) return
    key += 1
    blocks.push(
      <ul className="md-list" key={`ul-${key}`}>
        {list}
      </ul>,
    )
    list = []
  }

  for (const line of lines) {
    key += 1
    if (line.startsWith('```')) {
      if (fence) {
        blocks.push(
          <pre className="md-pre" key={`pre-${key}`}>
            <code>{fence.join('\n')}</code>
          </pre>,
        )
        fence = null
      } else {
        flushList()
        fence = []
      }
      continue
    }
    if (fence) {
      fence.push(line)
      continue
    }
    if (/^\s*[-*]\s+/.test(line)) {
      list.push(<li key={`li-${key}`}>{renderInline(line.replace(/^\s*[-*]\s+/, ''), ctx)}</li>)
      continue
    }
    flushList()
    if (line.startsWith('> ')) {
      blocks.push(
        <blockquote className="md-quote" key={`q-${key}`}>
          {renderInline(line.slice(2), ctx)}
        </blockquote>,
      )
    } else if (/^#{1,3}\s/.test(line)) {
      const level = line.match(/^#+/)?.[0].length ?? 1
      blocks.push(
        <div className={`md-h md-h${level}`} key={`h-${key}`}>
          {renderInline(line.replace(/^#+\s/, ''), ctx)}
        </div>,
      )
    } else if (line.trim() === '') {
      blocks.push(<span className="md-break" key={`br-${key}`} />)
    } else {
      blocks.push(
        <p className="md-p" key={`p-${key}`}>
          {renderInline(line, ctx)}
        </p>,
      )
    }
  }
  flushList()
  if (fence) {
    blocks.push(
      <pre className="md-pre" key="pre-tail">
        <code>{fence.join('\n')}</code>
      </pre>,
    )
  }
  return <>{blocks}</>
}

export function isEmojiOnly(content: string): boolean {
  const stripped = content.replace(/::?[a-z0-9_]{2,32}::?/gi, '').trim()
  if (stripped.length > 0) return false
  return /::?[a-z0-9_]{2,32}::?/i.test(content)
}

export function emojiQueryAt(text: string, caret: number): string | null {
  const upto = text.slice(0, caret)
  const match = upto.match(/:([a-z0-9_]{1,32})$/i)
  return match ? (match[1] ?? null) : null
}
