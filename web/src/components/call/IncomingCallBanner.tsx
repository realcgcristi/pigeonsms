import { useNavigate } from 'react-router-dom'
import { api } from '@/api/client'
import { Call, CallEnd, Videocam } from '@/components/icons'
import { useChat } from '@/store/chat'

export function IncomingCallBanner() {
  const call = useChat((s) => s.incomingCall)
  const clear = useChat((s) => s.clearIncomingCall)
  const navigate = useNavigate()

  if (!call) return null

  const answer = () => {
    clear()
    navigate(`/call/${call.channelId}?video=${call.mode === 'video'}`)
  }

  const decline = () => {
    clear()
    void api.declineCall(call.channelId).catch(() => undefined)
  }

  return (
    <div className="incoming-call-banner" role="status" aria-live="assertive">
      {call.mode === 'video' ? <Videocam size={18} /> : <Call size={18} />}
      <span>@{call.callerUsername} is calling{call.mode === 'video' ? ' (video)' : ''}</span>
      <div className="incoming-call-banner__actions">
        <button type="button" className="incoming-call-banner__decline" onClick={decline} aria-label="decline call">
          <CallEnd size={18} />
        </button>
        <button type="button" className="incoming-call-banner__answer" onClick={answer} aria-label="answer call">
          <Call size={18} />
        </button>
      </div>
    </div>
  )
}

export default IncomingCallBanner
