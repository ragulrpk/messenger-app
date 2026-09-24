-- Local development fixture only. Run after Flyway V2 with psql -v owner=ragul.
-- Inserts sample contacts and messages without changing existing users or passwords.
-- Repeated runs are safe: direct pairs and client message identifiers are unique.
\set ON_ERROR_STOP on
BEGIN;
CREATE TEMP TABLE seed_owner ON COMMIT DROP AS
SELECT id, display_name FROM app_users WHERE username = :'owner' AND enabled = true;
DO $$ BEGIN
    IF (SELECT count(*) FROM seed_owner) <> 1 THEN
        RAISE EXCEPTION 'The requested owner must be an existing enabled account';
    END IF;
END $$;

-- The hash was generated from a discarded random password. These directory
-- contacts have no published login credentials; existing accounts are untouched.
INSERT INTO app_users(id, username, display_name, password_hash, enabled, created_at)
SELECT gen_random_uuid(), username, name,
    '$2a$12$5ufoOfdYc/A5wukLFdrIZ.PrDyC7okjz0gOlu21WwEU3IsGsR.jXO', true, now()
FROM (VALUES
    ('sample.priya', 'Priya (Sample)'),
    ('sample.alex', 'Alex (Sample)'),
    ('sample.indhuja', 'Indhuja (Sample)')
) AS contacts(username, name)
ON CONFLICT (username) DO NOTHING;

CREATE TEMP TABLE seed_pairs ON COMMIT DROP AS
SELECT owner.id AS owner_id, person.id AS contact_id, person.username,
    least(owner.id::text, person.id::text) || ':' || greatest(owner.id::text, person.id::text) AS direct_key
FROM seed_owner owner CROSS JOIN app_users person
WHERE person.username IN ('sample.priya', 'sample.alex', 'sample.indhuja')
  AND person.id <> owner.id;

INSERT INTO chat_conversations(id, direct_key, created_at, updated_at)
SELECT gen_random_uuid(), direct_key, now() - interval '2 days', now() - interval '2 days'
FROM seed_pairs ON CONFLICT (direct_key) DO NOTHING;

INSERT INTO chat_members(conversation_id, user_id)
SELECT c.id, p.owner_id FROM seed_pairs p JOIN chat_conversations c USING (direct_key)
UNION
SELECT c.id, p.contact_id FROM seed_pairs p JOIN chat_conversations c USING (direct_key)
ON CONFLICT DO NOTHING;

-- Match the service's per-conversation write lock while inserting history.
SELECT c.id FROM chat_conversations c JOIN seed_pairs p USING (direct_key) ORDER BY c.id FOR UPDATE OF c;

INSERT INTO chat_messages(id, conversation_id, sender_id, client_id, body, sent_at)
SELECT gen_random_uuid(), c.id,
    CASE WHEN fixture.from_owner THEN p.owner_id ELSE p.contact_id END,
    ('a17e0000-0000-4000-8000-' || lpad(fixture.message_number::text, 12, '0'))::uuid,
    replace(fixture.body, 'Ragul', (SELECT display_name FROM seed_owner)), now() - fixture.minutes_ago * interval '1 minute'
FROM (VALUES
    ('sample.priya', 1, false, 42, 'Hi Ragul! This sample conversation shows how saved messages appear in Messenger.'),
    ('sample.priya', 2, true, 40, 'Thanks, Priya. I can see the conversation in my chat list.'),
    ('sample.priya', 3, false, 38, 'The message panel shows both incoming and outgoing messages with timestamps.'),
    ('sample.priya', 4, true, 35, 'Great. Does the conversation remain after I refresh the page?'),
    ('sample.priya', 5, false, 32, 'Yes. This history is stored in the local PostgreSQL database.'),
    ('sample.priya', 6, true, 28, 'I will check the other sample conversations too.'),
    ('sample.priya', 7, false, 20, 'You can switch chats and keep an unsent draft while the page is open.'),
    ('sample.priya', 8, true, 12, 'Everything is ready for testing the chat window.'),
    ('sample.alex', 101, false, 180, 'Welcome! This is a sample project discussion for testing the chat layout.'),
    ('sample.alex', 102, true, 175, 'Hi Alex. What should we check first?'),
    ('sample.alex', 103, false, 170, 'Start with user search, selecting a conversation, and reading its saved messages.'),
    ('sample.alex', 104, true, 165, 'The sidebar preview makes it easy to find the latest discussion.'),
    ('sample.alex', 105, false, 160, 'Try a longer message as well. Text wraps inside the bubble so the conversation stays readable on smaller screens.'),
    ('sample.alex', 106, true, 155, 'I will test the mobile Back button and return to this conversation.'),
    ('sample.alex', 107, false, 150, 'File uploads are not enabled yet; this sample uses text only.'),
    ('sample.alex', 108, true, 145, 'Understood. The text messaging flow is ready to review.'),
    ('sample.indhuja', 201, false, 1320, 'Hi Ragul. These are sample messages for a planning conversation.'),
    ('sample.indhuja', 202, true, 1315, 'Hi Indhuja. Let us prepare a short test checklist.'),
    ('sample.indhuja', 203, false, 1310, E'Checklist:\n1. Open a conversation\n2. Review message history\n3. Type a new message'),
    ('sample.indhuja', 204, true, 1305, 'I can also use Shift+Enter to write a message on multiple lines.'),
    ('sample.indhuja', 205, false, 1300, 'Correct. Enter sends the message when you are ready.'),
    ('sample.indhuja', 206, true, 1295, 'What happens if the server cannot be reached?'),
    ('sample.indhuja', 207, false, 1290, 'Your draft is kept so you can retry after the connection returns.'),
    ('sample.indhuja', 208, true, 1285, 'Thanks. I will use this checklist during testing.')
) AS fixture(username, message_number, from_owner, minutes_ago, body)
JOIN seed_pairs p ON p.username = fixture.username
JOIN chat_conversations c USING (direct_key)
ORDER BY fixture.minutes_ago DESC
ON CONFLICT (conversation_id, sender_id, client_id) DO NOTHING;

UPDATE chat_conversations c
SET updated_at = greatest(c.updated_at, (SELECT max(m.sent_at) FROM chat_messages m WHERE m.conversation_id = c.id))
WHERE c.direct_key IN (SELECT direct_key FROM seed_pairs);

SELECT u.display_name AS contact, count(m.id) AS messages
FROM seed_pairs p JOIN app_users u ON u.id = p.contact_id
JOIN chat_conversations c USING (direct_key)
JOIN chat_messages m ON m.conversation_id = c.id
GROUP BY u.display_name ORDER BY u.display_name;
COMMIT;
