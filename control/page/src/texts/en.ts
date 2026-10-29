export const en = {
  inviteForm: {
    heading: 'Crash every ledger mid-run. Will the books still balance?',
    emailLabel: 'Email',
    submit: 'Get a link',
    sent: 'Please check your email.',
    deletionLink: 'Every mail we send has a link to delete your address.',
    linksPerDay: (times: number) => `You can ask up to ${times} times in 24 hours.`,
    terms: (linkDays: number, retentionDays: number) =>
      `The link works for ${linkDays} days. Your address is deleted after ${retentionDays} days without use.`,
    privacyLink: 'Privacy',
    notAnAddress: "This doesn't look like an email address.",
    verificationFailed: 'Verification failed, try again.',
    couldNotSend: 'Could not send now, try again.',
    checking: 'Checking that you are not a bot…',
    checkUnavailable: 'The check that keeps bots out did not work; reload the page to try again.',
    tooManyRequests: (wait: string) => `Too many requests today; try again in ${wait}.`,
    waitMinutes: (minutes: number) => `${minutes} min`,
    waitHours: (hours: number) => `${hours} h`,
  },
  deletePage: {
    title: 'Delete your address',
    explanation: 'This deletes the email address this link was mailed to. Its invite and runs stay, without the address.',
    button: 'Delete my address',
    asked: (hours: number) => `Deletion of your address starts in ${hours} hours.`,
    askedBefore: (hours: number) => `Deletion of your address starts ${hours} hours after your first request.`,
    unknown: 'No address is stored for this link: it was deleted, or the link was not mailed.',
    failed: 'Could not send now, try again.',
    privacyLink: 'Privacy',
    backToStart: 'Back to the start',
  },
  privacy: {
    title: 'Privacy',
    heading1: 'What is stored',
    text1: 'The email address you enter, when you ask for a link, which emails were sent to it, and, for the invite it carries, its visits, sessions and runs.',
    heading2: 'Why, and on what basis',
    text2:
      "We use your email address to send the access link you requested and to control access to the service (Art. 6(1)(b) GDPR). Access links, request limits and bot protection help prevent automated use, abuse and the use of the site's email facility for spam; we process this data on the basis of our legitimate interest in operating and protecting the service (Art. 6(1)(f) GDPR).",
    heading3: 'How long',
    text3: (retentionDays: number) =>
      `The address is deleted ${retentionDays} days after its last use: your last request or your last visit using the link.`,
    text3Remains: 'The invite, its sessions and runs, and the records of the emails sent remain after that, without the email address.',
    heading4: 'Who else processes it',
    text4:
      'The site runs on a server rented from Contabo in Germany; Contabo hosts all data listed here. Mailjet sends the emails, from servers in the EU, with open and click tracking switched off. Cloudflare Turnstile checks that a request comes from a person; it loads only when you click into the email field or start typing in it, and processes technical information about your browser and connection, including your IP address, but not your email address. Cloudflare is based in the USA; the transfer relies on the EU-US Data Privacy Framework, and on the EU standard contractual clauses where that does not apply. All three process the data on our behalf (Art. 28 GDPR).',
    heading5: 'Server log',
    text5:
      "The web server logs each request with technical details such as time, method, status and duration. It does not log your IP address, your browser's User-Agent, or the address of the page you open. To limit requests, it counts them per IP address in memory only, without storing them. The log is limited in size; the oldest entries are overwritten.",
    heading6: 'Deleting it sooner, or asking what is stored',
    text6:
      'Every email we send carries a link to delete your address; your address is deleted after a 24-hour waiting period. You may also write to the contact below to ask what data we hold about you, or to exercise your other rights under the GDPR: access, rectification, erasure, restriction of processing, data portability, and objection to processing based on our legitimate interest (Art. 15–21 GDPR).',
    heading7: 'Controller and contact',
    text7:
      'The controller is the operator named below. You also have the right to lodge a complaint with a data protection supervisory authority, in particular in the EU member state where you live or work.',
    // The free generator's terms ask for this line, in German, with the link to it.
    generatorBefore: 'Erstellt mit kostenlosem ',
    generatorLink: 'Datenschutz-Generator.de',
    generatorUrl: 'https://datenschutz-generator.de/',
    generatorAfter: ' von Dr. Thomas Schwenke',
    backToStart: 'Back to the start',
  },
  impressum: {
    title: 'Impressum',
    responsible: 'Responsible for this site:',
    backToStart: 'Back to the start',
  },
  operator: {
    notSet: '(not set in this deployment)',
  },
  landing: {
    inviteRefused: 'This invite link is not valid or has expired; get a new one below.',
  },
  sessionEnded: {
    message: 'This session has ended or the link is wrong. Open your invite link again to start a new one, or get a link below.',
  },
  crashIllustration: {
    still: 'The way a transfer takes through the bank.',
    moving: 'Illustration: the ledger crashes, transfers wait in Kafka, then it catches up.',
  },
  footer: {
    privacy: 'Privacy',
    impressum: 'Impressum',
  },
}

export type Texts = typeof en
