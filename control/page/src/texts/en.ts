export const en = {
  inviteForm: {
    heading: 'Crash every ledger mid-run. Will the books still balance?',
    emailLabel: 'Email',
    submit: 'Get a link',
    sent: 'A link is on its way to your inbox.',
    linksPerDay: (times: number) => `You can ask up to ${times} times a day.`,
    terms: (linkDays: number, retentionDays: number) =>
      `The link works for ${linkDays} days. Your address is deleted after ${retentionDays} days without use.`,
    privacyLink: 'Privacy',
    notAnAddress: "This doesn't look like an email address.",
    verificationFailed: 'Verification failed, try again.',
    couldNotSend: 'Could not send now, try again.',
    checkUnavailable: 'The check that keeps bots out did not load; reload the page to try again.',
    tooManyRequests: (wait: string) => `Too many requests today; try again in ${wait}.`,
    waitMinutes: (minutes: number) => `${minutes} min`,
    waitHours: (hours: number) => `${hours} h`,
  },
  privacy: {
    title: 'Privacy',
    heading1: 'heading1',
    text1: 'text1',
    heading2: 'heading2',
    text2: 'text2',
    heading3: 'heading3',
    text3: 'text3',
    heading4: 'heading4',
    text4: 'text4',
    heading5: 'heading5',
    text5: 'text5',
    heading6: 'heading6',
    text6: 'text6',
    heading7: 'heading7',
    text7: 'text7',
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
