import "server-only";

export function publicContactEmail(): string | null {
  const email = process.env.PROTOTYPE_CONTACT_EMAIL ?? "";
  return email.length <= 254 && /^[A-Za-z0-9.!#$%&'*+/=?^_`{|}~-]+@[A-Za-z0-9.-]+\.[A-Za-z]{2,}$/.test(email)
    ? email : null;
}
