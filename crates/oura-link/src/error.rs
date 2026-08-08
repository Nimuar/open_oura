//! Error and result types for the BLE link layer.
use thiserror::Error;

pub type Result<T> = std::result::Result<T, Error>;

#[derive(Error, Debug)]
pub enum Error {
    #[error("ble error: {0}")]
    Ble(String),
    #[error("no matching Oura ring found")]
    DeviceNotFound,
    #[error("characteristic not found: {0}")]
    CharacteristicNotFound(String),
    #[error("authentication failed: {0}")]
    Auth(String),
    #[error("protocol error: {0}")]
    Protocol(String),
    #[error(transparent)]
    Io(#[from] std::io::Error),
}

impl From<btleplug::Error> for Error {
    fn from(e: btleplug::Error) -> Self {
        Error::Ble(e.to_string())
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn btleplug_errors_become_ble_errors() {
        let err: Error = btleplug::Error::DeviceNotFound.into();
        assert!(matches!(err, Error::Ble(_)));
        assert_eq!(err.to_string(), "ble error: Device not found");
    }

    #[test]
    fn io_errors_are_transparent() {
        let err: Error = std::io::Error::other("disconnected").into();
        assert_eq!(err.to_string(), "disconnected");
    }

    #[test]
    fn messages_name_the_failing_layer() {
        assert_eq!(Error::DeviceNotFound.to_string(), "no matching Oura ring found");
        assert_eq!(
            Error::CharacteristicNotFound("98ed0002".into()).to_string(),
            "characteristic not found: 98ed0002"
        );
        assert_eq!(Error::Auth("no nonce".into()).to_string(), "authentication failed: no nonce");
        assert_eq!(Error::Protocol("no battery".into()).to_string(), "protocol error: no battery");
    }
}
