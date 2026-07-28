import { useCallback, useEffect, useState } from 'react'
import { useNavigate, useParams, useSearchParams } from 'react-router-dom'
import { api, nonce } from '@/api/client'
import type { ForumPostDto, ForumTagDto, MessageDto } from '@/api/dto'
import { Add, Favorite, FavoriteBorder, Forum, Reply, Send } from '@/components/icons'
import { SpaceChannelRail } from '@/components/spaces/SpaceChannelRail'
import { Avatar } from '@/components/ui/Avatar'
import { Button } from '@/components/ui/Button'
import { IconButton } from '@/components/ui/IconButton'
import { TextField } from '@/components/ui/TextField'
import { Sheet } from '@/components/ui/Overlay'
import { Chip, ChipRow, EmptyState, Screen, ScreenBody, TopBar } from '@/components/ui/Layout'
import { useToast } from '@/components/ui/Toast'
import { relativeTime } from '@/lib/format'
import { renderMarkdown } from '@/lib/markdown'
import { usePrefs } from '@/store/prefs'
import { useChat } from '@/store/chat'
import './Forum.css'

type Sort = 'active' | 'recent' | 'oldest'

function titleOf(post: { metadata?: unknown; content?: string }): string {
  const meta = post.metadata as { title?: string } | null
  return meta?.title || post.content?.slice(0, 60) || 'untitled'
}

export default function ForumScreen() {
  const { channelId = '' } = useParams()
  const [params] = useSearchParams()
  const navigate = useNavigate()
  const toast = useToast()
  const emoji = useChat((s) => s.emoji)
  const loadEmoji = useChat((s) => s.loadEmoji)
  const forumSeen = usePrefs((s) => s.forumSeen)
  const markForumSeen = usePrefs((s) => s.markForumSeen)

  const [posts, setPosts] = useState<ForumPostDto[]>([])
  const [tags, setTags] = useState<ForumTagDto[]>([])
  const [sort, setSort] = useState<Sort>('active')
  const [tag, setTag] = useState<string | null>(null)
  const [composing, setComposing] = useState(false)
  const [title, setTitle] = useState('')
  const [body, setBody] = useState('')
  const [open, setOpen] = useState<ForumPostDto | null>(null)
  const [replies, setReplies] = useState<MessageDto[]>([])
  const [reply, setReply] = useState('')
  const [tagOpen, setTagOpen] = useState(false)
  const [tagName, setTagName] = useState('')
  const [markLabel, setMarkLabel] = useState('')

  const load = useCallback(async () => {
    const [list, tagList] = await Promise.all([
      api.forumPosts(channelId, sort, tag ?? undefined),
      api.forumTags(channelId).catch(() => []),
    ])
    setPosts(list)
    setTags(tagList)
  }, [channelId, sort, tag])

  useEffect(() => {
    void load()
    void loadEmoji()
  }, [load, loadEmoji])

  const openPost = async (post: ForumPostDto) => {
    setOpen(post)
    markForumSeen(post.id, post.reply_count ?? 0)
    const thread = await api.forumThread(channelId, post.id)
    setReplies(thread.replies ?? [])
  }

  const createPost = async () => {
    if (!title.trim()) return
    try {
      await api.createForumPost(channelId, {
        title: title.trim(),
        content: body,
        nonce: nonce(),
        tag: tag ?? undefined,
      })
      setTitle('')
      setBody('')
      setComposing(false)
      await load()
    } catch (err) {
      toast.error(err instanceof Error ? err.message : 'could not post')
    }
  }

  const postReply = async () => {
    if (!open || !reply.trim()) return
    try {
      const created = await api.createForumReply(channelId, open.id, {
        content: reply.trim(),
        nonce: nonce(),
      })
      setReplies(replies.concat(created))
      setReply('')
      markForumSeen(open.id, (open.reply_count ?? 0) + 1)
    } catch (err) {
      toast.error(err instanceof Error ? err.message : 'could not reply')
    }
  }

  const toggleLike = async (post: ForumPostDto) => {
    const res = post.liked ? await api.unlikeMessage(post.id) : await api.likeMessage(post.id)
    setPosts((list) =>
      list.map((p) => (p.id === post.id ? { ...p, like_count: res.like_count, liked: res.liked } : p)),
    )
  }

  const createTag = async () => {
    if (!tagName.trim()) return
    try {
      await api.createForumTag(channelId, tagName.trim(), markLabel.trim() || undefined)
      setTagName('')
      setMarkLabel('')
      setTagOpen(false)
      await load()
      toast.show('forum tag created')
    } catch (err) {
      toast.error(err instanceof Error ? err.message : 'could not create tag')
    }
  }

  return (
    <Screen className="chat chat--workspace">
      <SpaceChannelRail channelId={channelId} spaceId={params.get('spaceId')} />
      <div className="chat__conversation">
      <TopBar
        title={params.get('name') ?? 'forum'}
        onBack={() => navigate(-1)}
        actions={<IconButton label="new forum tag" onClick={() => setTagOpen(true)}><Add /></IconButton>}
      />
      <ChipRow>
        <Chip label="active" active={sort === 'active'} onClick={() => setSort('active')} />
        <Chip label="newest" active={sort === 'recent'} onClick={() => setSort('recent')} />
        <Chip label="oldest" active={sort === 'oldest'} onClick={() => setSort('oldest')} />
        {tags.map((item) => (
          <Chip
            key={item.id}
            label={item.name}
            active={tag === item.id}
            onClick={() => setTag(tag === item.id ? null : item.id)}
          />
        ))}
      </ChipRow>
      <ScreenBody>
        {posts.length === 0 ? (
          <EmptyState
            icon={<Forum size={28} />}
            title="no posts yet"
            subtitle="start the first thread"
            action={
              <Button variant="tonal" onClick={() => setComposing(true)}>
                new post
              </Button>
            }
          />
        ) : (
          posts.map((post) => {
            const seen = forumSeen[post.id] ?? 0
            const fresh = (post.reply_count ?? 0) > seen
            return (
              <button key={post.id} type="button" className="forum__post" onClick={() => void openPost(post)}>
                <Avatar
                  name={post.author?.display_name || post.author?.username || 'someone'}
                  avatarKey={post.author?.avatar_key}
                  size="sm"
                />
                <span className="forum__post-body">
                  <span className="forum__post-title">
                    {titleOf(post)}
                    {fresh ? <span className="forum__dot" /> : null}
                  </span>
                  <span className="forum__post-meta">
                    {post.author?.display_name || post.author?.username} ·{' '}
                    {relativeTime(post.last_activity_at || post.created_at)} · {post.reply_count ?? 0} replies
                    {post.tag ? ` · ${post.tag.name}` : ''}
                  </span>
                </span>
                <span
                  className="forum__like"
                  onClick={(e) => {
                    e.stopPropagation()
                    void toggleLike(post)
                  }}
                >
                  {post.liked ? <Favorite size={18} /> : <FavoriteBorder size={18} />}
                  {post.like_count ?? 0}
                </span>
              </button>
            )
          })
        )}
      </ScreenBody>

      <button type="button" className="ui-fab" onClick={() => setComposing(true)}>
        <Add />
        new post
      </button>
      </div>

      <Sheet open={composing} title="new post" onClose={() => setComposing(false)}>
        <div className="nest__form">
          <TextField label="title" value={title} onChange={setTitle} />
          <TextField label="body (markdown works)" value={body} onChange={setBody} multiline />
          <Button size="cta" fullWidth onClick={() => void createPost()}>
            post
          </Button>
        </div>
      </Sheet>

      <Sheet open={!!open} title={open ? titleOf(open) : ''} onClose={() => setOpen(null)}>
        {open ? (
          <div className="forum__thread">
            <div className="forum__root">{renderMarkdown(open.content ?? '', { emoji })}</div>
            {replies.map((item) => (
              <div className="forum__reply" key={item.id}>
                <Avatar
                  name={item.author?.display_name || item.author?.username || 'someone'}
                  avatarKey={item.author?.avatar_key}
                  size="xs"
                />
                <div>
                  <div className="forum__reply-author">
                    {item.author?.display_name || item.author?.username} · {relativeTime(item.created_at ?? 0)}
                  </div>
                  {renderMarkdown(item.content ?? '', { emoji })}
                </div>
              </div>
            ))}
            <div className="forum__composer">
              <Reply size={18} />
              <input
                className="composer__input"
                placeholder="reply"
                value={reply}
                onChange={(e) => setReply(e.target.value)}
                onKeyDown={(e) => {
                  if (e.key === 'Enter') void postReply()
                }}
              />
              <IconButton label="send reply" tone="accent" filled onClick={() => void postReply()}>
                <Send />
              </IconButton>
            </div>
          </div>
        ) : null}
      </Sheet>

      <Sheet open={tagOpen} title="new forum tag" onClose={() => setTagOpen(false)}>
        <div className="nest__form">
          <TextField label="tag name" value={tagName} onChange={setTagName} />
          <TextField label="optional marked label" value={markLabel} onChange={setMarkLabel} />
          <Button size="cta" fullWidth onClick={() => void createTag()}>create tag</Button>
        </div>
      </Sheet>
    </Screen>
  )
}
