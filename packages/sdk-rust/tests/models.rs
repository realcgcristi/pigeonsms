use pigeonsms_sdk::SendMessage;

#[test]
fn text_messages_have_unique_nonces() {
    let first = SendMessage::text("hello");
    let second = SendMessage::text("hello");
    assert_ne!(first.nonce, second.nonce);
    assert_eq!(first.content, "hello");
}
