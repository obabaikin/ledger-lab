# ADR 0017: Invites by email

Status: accepted · Date: 2026-10-25

## Context

Invites were made by hand (ADR 0012): a visitor without one could not try the bank. The start page
should let anyone get a link within a minute, while a bot must not be able to send mail from the
site's domain to addresses of its choice, or learn which addresses the site knows.

## Decision

- **A request, not an account.** `POST /api/invite-requests` takes an address and mails a link
  `/?ref=<token>`. There is no sign-up, password or cookie; the invite works as a hand-made one.
  An address is trimmed and lowercased. Only its shape is checked, on the page and again by the server:
  one `@` with something on each side, no whitespace, comma or control character, at most 254
  characters; else `400`. Whether the mailbox exists is never told.
- **The same answer for any address.** `202` whether a mail goes out, the address is new or known,
  or its limit is used up. Only the limit of all mails gets its own answer, `429 daily-limit` with
  `Retry-After`: it tells that the service is busy today, not anything about an address.
- **Limits over a sliding 24 hours, counted in mails.** An address gets a link while it got fewer
  than 3 within the window; then one notice naming when its oldest link leaves the window; then
  nothing. All addresses together get at most 50 mails, notices included. A refused request is not
  recorded. The checks and the writes run under one transaction-scoped advisory lock, so requests
  at the same moment cannot pass a limit together. Counting the requests instead would let a run of
  requests move the window, and the time in the notice would not hold.
- **The same link while it works, for 7 more days.** An address with a valid invite gets that invite
  again, and the request extends it to 7 days from then, so the mail's "works for 7 days" holds; a new
  invite (7 days, 10 runs an hour) only once it expired. The run quota is the invite's, so asking again
  gives no new quota.
- **The visit confirms the address.** Opening the link is a visit, as with any invite (ADR 0012);
  there is no separate confirmation mail. A mail scanner that fetches the page without running its
  script opens no session.
- **An outbox, at least once.** The request writes the mail in its own transaction. A scheduled
  sender claims due mails with `FOR UPDATE SKIP LOCKED` and sends them over Mailjet's HTTP API.
  Errors and `429` are retried with a pause that doubles, up to 5 attempts, then the mail is marked
  failed; any other refusal fails it at once. A crash between the provider's answer and the mark
  sends the mail again. Without an API key, as locally and in tests, each mail is logged as not sent.
- **No tracking.** Each mail switches the provider's click and open tracking off: a tracked link
  would pass the invite token through the provider's redirect.
- **Turnstile in front.** The form carries a Cloudflare Turnstile answer, checked by the server
  before anything is recorded: `403 verification-failed` if it fails, `503 busy` if the check does
  not answer. Without a secret the check is off, as locally and in tests.
- **The address is kept 30 days after its last use**, the latest request or visit by its link, or
  until 24 hours after its owner asked to delete it; then it is deleted with its requests. The invite,
  its sessions and runs stay, without the address. The log names a mail by its id and kind only, never
  its address or token.
- **Deletion by the mailed link.** Every mail carries `/delete?ref=<token>`. The page there sends
  `POST /api/address-deletions` only when its button is pressed; the token of any mail sent to the
  address finds it, also after its invite was replaced. The answer is `202` with the deletion time,
  the same on a repeat, and whether an earlier request had already set it; or `404 unknown-link` where no
  address is stored for the token. The page says the deletion starts in 24 hours, or 24 hours after the
  first request; it holds that number itself, so a changed window needs its text changed too. The address
  is deleted 24 hours later, the limit's window, under the same lock as the requests: deleted at once,
  its mails would no longer count toward its limit, and it could be mailed 3 links again right away.

## Considered and rejected

- **"Unknown address" as an answer**, or a different one for a used-up limit: it would tell a bot
  which addresses the site knows.
- **A confirmation mail before the link**: a second mail for the same proof; the visit already
  shows the address works.
- **SMTP**: an HTTP API on port 443 needs no mail port open from the cluster and answers each send.
- **A calendar day for the limits**: a visitor at 23:59 would get a fresh limit a minute later.
- **Limits alone, without Turnstile**: they cap the harm, but a bot could still spend the daily limit
  on other people's addresses and keep real visitors out.
- **A provider that cannot switch tracking off per mail**: the token would pass through its redirect.
- **A form to delete any address**: it would need Turnstile and the same answer for any address, and
  could only confirm by a mail that may not arrive; the link in a mail already proves the address.
- **Deleting at once**: it would reset the address's limit.
- **Deleting on `GET`**: mail scanners open links; a page that deleted on load would delete addresses
  nobody asked to.

## Consequences

- A visitor gets a link without the author.
- A duplicate mail is possible after a crash of the sender; it carries the same link.
- The daily limit is shared: once a bot spends it, real visitors wait for `Retry-After` too.
- One person may hold a hand-made invite and a mailed one; quotas are per invite.
- Asking for a link does not cancel or move a deletion. Once the address is deleted, a request makes it
  new, with a new invite, while the old link works until it expires; the mails of the window stay, without
  an address, and count only toward the limit of all mails, so within one window the address may get up to
  twice its limit, once.
