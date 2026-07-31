use serde::Deserialize;
use serde_json::Value;

#[derive(Debug, Deserialize)]
pub(crate) struct ErrorEnvelope {
    pub error: ErrorBody,
    pub request_id: Option<String>,
}

#[derive(Debug, Deserialize)]
pub(crate) struct ErrorBody {
    pub code: String,
    pub message: String,
    pub details: Option<Value>,
}

#[derive(Debug, thiserror::Error)]
pub enum Error {
    #[error("HTTP transport failed: {0}")]
    Transport(#[from] reqwest::Error),
    #[error("websocket failed: {0}")]
    #[cfg(feature = "gateway")]
    WebSocket(#[from] tokio_tungstenite::tungstenite::Error),
    #[error("invalid server URL: {0}")]
    Url(#[from] url::ParseError),
    #[error("server returned {status} {code}: {message}")]
    Api {
        status: u16,
        code: String,
        message: String,
        request_id: Option<String>,
        details: Option<Value>,
    },
    #[error("protocol payload was invalid: {0}")]
    Protocol(#[from] serde_json::Error),
    #[error("gateway task ended")]
    GatewayClosed,
}

pub type Result<T> = std::result::Result<T, Error>;
