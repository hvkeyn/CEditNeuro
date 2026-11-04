# App patterns for mobile control

These are stable starting points, not coordinates. Labels and layouts change with app version and language.

## Settings

1. `ui_find role=EditText text=Search` (or open the search box).
2. `ui_act ref=... action=set_text text="<setting>"`.
3. Read the result snapshot; open the row whose label matches exactly.
4. For a permission switch, tap the switch row and verify the checked state in the next snapshot.

## Chrome / browser

1. Focus the address bar, `set_text` the URL or query, press the visible Go/Enter control.
2. Read the page; use the site's search or filters rather than scrolling blindly.
3. A block page, CAPTCHA, or login is a handoff: stop and tell the person.

## Telegram / WhatsApp

1. Open the app, focus the chat-list search field, type the contact or group name.
2. Open the result whose title matches exactly; if two match, ask.
3. Read visible messages; `ui_collect direction=down pages=8` for history, `up` for older.
4. Before sending, verify the recipient title in the next snapshot. Sending is an external write; confirm when the user did not explicitly ask.

## Gmail

1. Search or open the matching thread.
2. Read the body with `ui_collect` if it is long.
3. Compose only when asked; check recipient and subject before Send.

## YouTube / media

1. Search, then open the result whose title matches.
2. Read title/description; do not auto-play, purchase, or subscribe unless asked.
3. Stop on a consent, age, or payment page.

## Maps / shopping

1. Enter the place or query, apply only filters the user named.
2. Read prices/options from the visible labels; do not invent a price.
3. Stop before checkout or payment and hand it to the person.

## Login, OTP, payment, CAPTCHA

Stop. Give the person the screen or a handoff link, and resume only after they confirm. Never read a password, OTP, card number, or recovery code into the transcript.

## Verification

A tap is not success. Check the next snapshot for the changed field, opened dialog, selected row, or sent-message state. If the app changed or the ref is stale, take a fresh `ui_snapshot`.
