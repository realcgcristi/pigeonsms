use std::sync::Arc;

use reqwest::{Method, Response};
use serde::{de::DeserializeOwned, Deserialize, Serialize};
use serde_json::{json, Value};
use tokio::sync::RwLock;

use crate::error::{ErrorEnvelope, Result};
use crate::models::*;
use crate::Error;

#[derive(Clone)]
pub struct PigeonClient {
    base_url: String,
    token: Arc<RwLock<Option<String>>>,
    http: reqwest::Client,
}

impl PigeonClient {
    pub fn new(base_url: impl Into<String>) -> Result<Self> {
        Self::with_http(base_url, reqwest::Client::builder().user_agent("pigeonsms-rust/1.0").build()?)
    }

    pub fn with_http(base_url: impl Into<String>, http: reqwest::Client) -> Result<Self> {
        let base_url = base_url.into().trim_end_matches('/').to_string();
        url::Url::parse(&base_url)?;
        Ok(Self { base_url, token: Arc::new(RwLock::new(None)), http })
    }

    pub fn base_url(&self) -> &str { &self.base_url }

    pub async fn set_token(&self, token: impl Into<String>) {
        *self.token.write().await = Some(token.into());
    }

    pub async fn clear_token(&self) { *self.token.write().await = None; }
    pub async fn token(&self) -> Option<String> { self.token.read().await.clone() }

    pub async fn discover(&self) -> Result<Discovery> { self.get("/.well-known/pigeon", false).await }
    pub async fn health(&self) -> Result<Health> { self.get("/health", false).await }

    pub async fn login(&self, login: &str, password: &str, totp: Option<&str>, device_name: Option<&str>) -> Result<Auth> {
        let auth: Auth = self.send(Method::POST, "/auth/login", false, Some(json!({
            "login": login, "password": password, "totp": totp, "device_name": device_name.unwrap_or("rust-sdk")
        })), None).await?;
        self.set_token(auth.token.clone()).await;
        Ok(auth)
    }

    pub async fn me(&self) -> Result<User> { Ok(self.get::<Me>("/auth/me", true).await?.user) }
    pub async fn dms(&self) -> Result<Vec<DirectMessage>> { Ok(self.get::<Dms>("/dms", true).await?.dms) }
    pub async fn open_dm(&self, user_id: &str) -> Result<Snowflake> {
        Ok(self.send::<OpenDm>(Method::POST, "/dms/open", true, Some(json!({ "user_id": user_id })), None).await?.channel_id)
    }

    pub async fn messages(&self, channel_id: &str, before: Option<u64>, after_seq: Option<u64>, limit: Option<u32>) -> Result<MessagePage> {
        let mut url = url::Url::parse(&format!("{}/channels/{}/messages", self.base_url, encode(channel_id)))?;
        if let Some(value) = before { url.query_pairs_mut().append_pair("before", &value.to_string()); }
        if let Some(value) = after_seq { url.query_pairs_mut().append_pair("after", &value.to_string()); }
        if let Some(value) = limit { url.query_pairs_mut().append_pair("limit", &value.to_string()); }
        self.send_url(Method::GET, url, true, None, None).await
    }

    pub async fn send_message(&self, channel_id: &str, message: &SendMessage) -> Result<Option<Message>> {
        let result: SendResponse = self.send(
            Method::POST,
            &format!("/channels/{}/messages", encode(channel_id)),
            true,
            Some(serde_json::to_value(message)?),
            Some(&message.nonce),
        ).await?;
        Ok(result.message)
    }

    pub async fn mark_read(&self, channel_id: &str, seq: u64) -> Result<()> {
        self.send(Method::PUT, &format!("/channels/{}/read", encode(channel_id)), true, Some(json!({ "seq": seq })), None).await
    }

    pub async fn spaces(&self) -> Result<Vec<Space>> { Ok(self.get::<Spaces>("/spaces", true).await?.spaces) }
    pub async fn space(&self, id: &str) -> Result<Space> { Ok(self.get::<SpaceResponse>(&format!("/spaces/{}", encode(id)), true).await?.space) }
    pub async fn create_space(&self, name: &str, description: Option<&str>) -> Result<Space> {
        let nonce = uuid::Uuid::new_v4().to_string();
        Ok(self.send::<SpaceResponse>(Method::POST, "/spaces", true, Some(json!({ "name": name, "description": description, "nonce": nonce })), Some(&nonce)).await?.space)
    }

    pub async fn commands(&self) -> Result<Vec<BotCommand>> { Ok(self.get::<Commands>("/bots/me/commands", true).await?.commands) }
    pub async fn replace_commands(&self, commands: &[BotCommand]) -> Result<Vec<BotCommand>> {
        Ok(self.send::<Commands>(Method::PUT, "/bots/me/commands", true, Some(json!({ "commands": commands })), None).await?.commands)
    }
    pub async fn poll_interactions(&self, timeout: u8) -> Result<Vec<BotInteraction>> {
        Ok(self.get::<Interactions>(&format!("/bots/me/updates?timeout={}", timeout.min(30)), true).await?.interactions)
    }
    pub async fn answer_interaction(&self, id: &str, callback_token: &str, mut response: Value) -> Result<()> {
        if let Value::Object(ref mut object) = response { object.insert("callback_token".into(), callback_token.into()); }
        self.send(Method::POST, &format!("/interactions/{}/callback", encode(id)), true, Some(response), None).await
    }

    async fn get<T: DeserializeOwned>(&self, path: &str, auth: bool) -> Result<T> {
        self.send(Method::GET, path, auth, None, None).await
    }

    async fn send<T: DeserializeOwned>(&self, method: Method, path: &str, auth: bool, body: Option<Value>, idempotency: Option<&str>) -> Result<T> {
        let url = url::Url::parse(&format!("{}{}", self.base_url, path))?;
        self.send_url(method, url, auth, body, idempotency).await
    }

    async fn send_url<T: DeserializeOwned>(&self, method: Method, url: url::Url, auth: bool, body: Option<Value>, idempotency: Option<&str>) -> Result<T> {
        let mut request = self.http.request(method, url).header("Pigeon-Protocol-Version", "1.0");
        if auth {
            if let Some(token) = self.token().await.filter(|token| token != "cookie") { request = request.bearer_auth(token); }
        }
        if let Some(key) = idempotency { request = request.header("Idempotency-Key", key); }
        if let Some(body) = body { request = request.json(&body); }
        decode(request.send().await?).await
    }
}

async fn decode<T: DeserializeOwned>(response: Response) -> Result<T> {
    let status = response.status();
    if status.is_success() {
        if status.as_u16() == 204 { return serde_json::from_value(Value::Null).map_err(Into::into); }
        return Ok(response.json().await?);
    }
    let request_id = response.headers().get("x-request-id").and_then(|value| value.to_str().ok()).map(str::to_owned);
    let envelope = response.json::<ErrorEnvelope>().await.ok();
    Err(Error::Api {
        status: status.as_u16(),
        code: envelope.as_ref().map(|value| value.error.code.clone()).unwrap_or_else(|| "http_error".into()),
        message: envelope.as_ref().map(|value| value.error.message.clone()).unwrap_or_else(|| status.to_string()),
        request_id: envelope.as_ref().and_then(|value| value.request_id.clone()).or(request_id),
        details: envelope.and_then(|value| value.error.details),
    })
}

fn encode(value: &str) -> String { url::form_urlencoded::byte_serialize(value.as_bytes()).collect() }

#[derive(Debug, Deserialize)] pub struct Health { pub ok: bool, pub ts: u64 }
#[derive(Debug, Deserialize)] pub struct Auth { pub token: String, pub user: User }
#[derive(Debug, Deserialize)] struct Me { user: User }
#[derive(Debug, Deserialize)] struct Dms { dms: Vec<DirectMessage> }
#[derive(Debug, Deserialize)] struct OpenDm { channel_id: Snowflake }
#[derive(Debug, Deserialize)] struct SendResponse { message: Option<Message> }
#[derive(Debug, Deserialize)] struct Spaces { spaces: Vec<Space> }
#[derive(Debug, Deserialize)] struct SpaceResponse { space: Space }
#[derive(Debug, Serialize, Deserialize)] struct Commands { commands: Vec<BotCommand> }
#[derive(Debug, Deserialize)] struct Interactions { interactions: Vec<BotInteraction> }
