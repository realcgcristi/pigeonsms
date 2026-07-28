import { Component, type ErrorInfo, type ReactNode } from 'react'
import { Button } from '@/components/ui/Button'

export class ErrorBoundary extends Component<{ children: ReactNode }, { error: Error | null }> {
  state: { error: Error | null } = { error: null }

  static getDerivedStateFromError(error: Error) {
    return { error }
  }

  componentDidCatch(error: Error, info: ErrorInfo) {
    console.error('PigeonSMS web crashed', error, info.componentStack)
  }

  render() {
    if (!this.state.error) return this.props.children
    return (
      <main className="fatal-error" role="alert">
        <div>
          <span>🕊️</span>
          <h1>this screen hit a wall</h1>
          <p>{this.state.error.message || 'Something unexpected happened.'}</p>
          <Button onClick={() => window.location.reload()}>reload PigeonSMS</Button>
        </div>
      </main>
    )
  }
}

export default ErrorBoundary
