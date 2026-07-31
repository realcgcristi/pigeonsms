use serde::{Deserialize, Serialize};
use serde_json::{Map, Value};

pub type Snowflake = String;

#[derive(Clone, Debug, Serialize, Deserialize, PartialEq)]
pub struct ProtocolInfo {
    pub name: String,
    pub versions: Vec<String>,
    pub preferred: String,
}

#[derive(Clone, Debug, Serialize, Deserialize, PartialEq)]
pub struct ServerInfo {
    pub name: String,
    pub version: String,
    pub source: Option<String>,
}

#[derive(Clone, Debug, Serialize, Deserialize, PartialEq)]
pub struct Endpoints {
    pub api: String,
    pub gateway: String,
    pub media: String,
    pub calls: Option<String>,
}

#[derive(Clone, Debug, Serialize, Deserialize, PartialEq)]
pub struct ServerLimits {
    pub message_length: u64,
    pub upload_bytes: u64,
    #[serde(flatten)]
    pub extra: Map<String, Value>,
}

#[derive(Clone, Debug, Serialize, Deserialize, PartialEq)]
pub struct Discovery {
    pub protocol: ProtocolInfo,
    pub server: ServerInfo,
    pub endpoints: Endpoints,
    pub capabilities: Vec<String>,
    pub limits: ServerLimits,
}

#[derive(Clone, Debug, Serialize, Deserialize, PartialEq)]
pub struct User {
    pub id: Snowflake,
    pub username: String,
    pub email: Option<String>,
    pub display_name: Option<String>,
    pub avatar_key: Option<String>,
    pub avatar_original_key: Option<String>,
    pub avatar_square_key: Option<String>,
    pub accent: Option<String>,
    #[serde(default)]
    pub is_admin: bool,
    #[serde(default)]
    pub is_bot: bool,
}

#[derive(Clone, Debug, Serialize, Deserialize, PartialEq)]
pub struct Attachment {
    pub key: String,
    pub name: Option<String>,
    #[serde(rename = "type")]
    pub media_type: Option<String>,
    pub size: Option<u64>,
}

#[derive(Clone, Debug, Serialize, Deserialize, PartialEq)]
pub struct Message {
    pub id: Snowflake,
    pub channel_id: Snowflake,
    pub author_id: Option<Snowflake>,
    pub author: User,
    pub content: String,
    pub seq: Option<u64>,
    pub kind: Option<String>,
    pub nonce: Option<String>,
    pub reply_to: Option<Snowflake>,
    pub attachment: Option<Attachment>,
    pub metadata: Option<Map<String, Value>>,
    pub created_at: u64,
    pub edited_at: Option<u64>,
    pub expires_at: Option<u64>,
    #[serde(default)]
    pub deleted: bool,
    #[serde(default)]
    pub encrypted: bool,
}

#[derive(Clone, Debug, Serialize, Deserialize, PartialEq)]
pub struct Channel {
    pub id: Snowflake,
    pub space_id: Option<Snowflake>,
    pub name: Option<String>,
    pub topic: Option<String>,
    pub kind: Option<String>,
    pub last_seq: Option<u64>,
    pub last_read_seq: Option<u64>,
    #[serde(default)]
    pub unread: u64,
    pub category_id: Option<Snowflake>,
    pub position: Option<u32>,
}

#[derive(Clone, Debug, Serialize, Deserialize, PartialEq)]
pub struct Space {
    pub id: Snowflake,
    pub name: String,
    pub owner_id: Snowflake,
    pub description: Option<String>,
    pub icon_key: Option<String>,
    pub role: Option<String>,
    pub member_count: Option<u64>,
    #[serde(default)]
    pub channels: Vec<Channel>,
}

#[derive(Clone, Debug, Serialize, Deserialize, PartialEq)]
pub struct DirectMessage {
    pub channel_id: Snowflake,
    pub last_seq: u64,
    pub unread: u64,
    pub peer: User,
}

#[derive(Clone, Debug, Serialize, Deserialize, PartialEq)]
pub struct MessageCursor {
    pub first_seq: Option<u64>,
    pub last_seq: Option<u64>,
    pub channel_last_seq: Option<u64>,
    #[serde(default)]
    pub has_more_after: bool,
}

#[derive(Clone, Debug, Serialize, Deserialize, PartialEq)]
pub struct MessagePage {
    pub messages: Vec<Message>,
    pub read: Option<Map<String, Value>>,
    pub cursor: Option<MessageCursor>,
}

#[derive(Clone, Debug, Serialize, Deserialize, PartialEq)]
pub struct SendMessage {
    pub content: String,
    pub nonce: String,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub reply_to: Option<Snowflake>,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub attachment: Option<Attachment>,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub ttl: Option<u64>,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub send_at: Option<u64>,
    #[serde(default, skip_serializing_if = "std::ops::Not::not")]
    pub encrypted: bool,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub kind: Option<String>,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub metadata: Option<Map<String, Value>>,
}

impl SendMessage {
    pub fn text(content: impl Into<String>) -> Self {
        Self {
            content: content.into(),
            nonce: uuid::Uuid::new_v4().to_string(),
            reply_to: None,
            attachment: None,
            ttl: None,
            send_at: None,
            encrypted: false,
            kind: None,
            metadata: None,
        }
    }
}

#[derive(Clone, Debug, Serialize, Deserialize, PartialEq)]
pub struct BotCommand {
    pub id: Option<Snowflake>,
    pub name: String,
    pub description: String,
    pub space_id: Option<Snowflake>,
    #[serde(default = "default_true")]
    pub dm_enabled: bool,
    #[serde(default)]
    pub options: Vec<Value>,
}

fn default_true() -> bool { true }

#[derive(Clone, Debug, Serialize, Deserialize, PartialEq)]
pub struct BotInteraction {
    pub id: Snowflake,
    pub command: String,
    pub channel_id: Snowflake,
    pub space_id: Option<Snowflake>,
    pub user: User,
    #[serde(default)]
    pub options: Map<String, Value>,
    pub callback_token: Option<String>,
    pub created_at: u64,
}

#[derive(Clone, Debug, Serialize, Deserialize, PartialEq)]
pub struct GatewayEvent {
    pub t: String,
    pub d: Value,
}
