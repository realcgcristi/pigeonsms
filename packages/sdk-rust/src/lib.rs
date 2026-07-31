mod bot;
mod client;
mod error;
#[cfg(feature = "gateway")]
mod gateway;
mod models;

pub use bot::PigeonBot;
pub use client::{Auth, Health, PigeonClient};
pub use error::{Error, Result};
#[cfg(feature = "gateway")]
pub use gateway::{GatewayHandle, GatewayStatus, PigeonGateway};
pub use models::*;
