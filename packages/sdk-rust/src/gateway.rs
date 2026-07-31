use std::{collections::HashMap, sync::Arc, time::Duration};

use base64::Engine;
use futures_util::{SinkExt, StreamExt};
use tokio::sync::{mpsc, RwLock};
use tokio_tungstenite::tungstenite::Message as WsMessage;

use crate::{GatewayEvent, Result};

#[derive(Clone, Debug, PartialEq, Eq)]
pub enum GatewayStatus { Connecting, Connected, Disconnected }

pub struct PigeonGateway {
    url: String,
    token: String,
    cursors: Arc<RwLock<HashMap<String, u64>>>,
}

pub struct GatewayHandle {
    pub events: mpsc::Receiver<GatewayEvent>,
    pub status: mpsc::Receiver<GatewayStatus>,
    stop: tokio::sync::oneshot::Sender<()>,
}

impl GatewayHandle {
    pub fn stop(self) { let _ = self.stop.send(()); }
}

impl PigeonGateway {
    pub fn new(url: impl Into<String>, token: impl Into<String>) -> Self {
        Self { url: url.into(), token: token.into(), cursors: Arc::new(RwLock::new(HashMap::new())) }
    }

    pub async fn set_cursor(&self, channel_id: impl Into<String>, seq: u64) {
        self.cursors.write().await.insert(channel_id.into(), seq);
    }

    pub fn start(self) -> GatewayHandle {
        let (events_tx, events) = mpsc::channel(256);
        let (status_tx, status) = mpsc::channel(16);
        let (stop, mut stop_rx) = tokio::sync::oneshot::channel();
        tokio::spawn(async move {
            let mut backoff = Duration::from_millis(500);
            loop {
                let _ = status_tx.send(GatewayStatus::Connecting).await;
                let result = tokio::select! {
                    _ = &mut stop_rx => break,
                    result = self.session(&events_tx) => result,
                };
                let _ = status_tx.send(GatewayStatus::Disconnected).await;
                if result.is_ok() { backoff = Duration::from_millis(500); }
                tokio::select! {
                    _ = &mut stop_rx => break,
                    _ = tokio::time::sleep(backoff) => {}
                }
                backoff = (backoff * 2).min(Duration::from_secs(30));
            }
        });
        GatewayHandle { events, status, stop }
    }

    async fn session(&self, events: &mpsc::Sender<GatewayEvent>) -> Result<()> {
        let mut url = url::Url::parse(&self.url)?;
        if self.token != "cookie" { url.query_pairs_mut().append_pair("token", &self.token); }
        let cursors = self.cursors.read().await;
        if !cursors.is_empty() {
            let bytes = serde_json::to_vec(&*cursors)?;
            let resume = base64::engine::general_purpose::URL_SAFE_NO_PAD.encode(bytes);
            url.query_pairs_mut().append_pair("resume", &resume);
        }
        drop(cursors);
        let (socket, _) = tokio_tungstenite::connect_async(url.as_str()).await?;
        let (mut sink, mut stream) = socket.split();
        let mut heartbeat = tokio::time::interval(Duration::from_secs(25));
        loop {
            tokio::select! {
                _ = heartbeat.tick() => sink.send(WsMessage::Text("ping".into())).await?,
                frame = stream.next() => match frame {
                    Some(Ok(WsMessage::Text(text))) if text != "pong" => {
                        if let Ok(event) = serde_json::from_str(&text) { events.send(event).await.map_err(|_| crate::Error::GatewayClosed)?; }
                    }
                    Some(Ok(WsMessage::Close(_))) | None => return Ok(()),
                    Some(Err(error)) => return Err(error.into()),
                    _ => {}
                }
            }
        }
    }
}
