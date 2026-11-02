import { TeamCredential } from "../../../../core/event/event-models";

export const CREDENTIALS_FILE_NAME = "event-team-credentials.csv";

const HEADER = ["team", "group", "email", "password"];

function csvCell(value: string): string {
  return /[",\r\n]/.test(value) ? `"${value.replace(/"/g, '""')}"` : value;
}

function fields(credential: TeamCredential): string[] {
  return [
    credential.teamName,
    credential.group,
    credential.email,
    credential.password,
  ];
}

/** team,group,email,password — built in the browser; the server never stores these passwords. */
export function credentialsCsv(credentials: readonly TeamCredential[]): string {
  return [HEADER, ...credentials.map(fields)]
    .map((row) => row.map(csvCell).join(","))
    .join("\r\n");
}

/** Tab-separated lines with a header, so a paste into a spreadsheet keeps the columns. */
export function credentialsText(
  credentials: readonly TeamCredential[],
): string {
  return [HEADER, ...credentials.map(fields)]
    .map((row) => row.join("\t"))
    .join("\n");
}

export function downloadCredentialsCsv(
  credentials: readonly TeamCredential[],
): void {
  const url = URL.createObjectURL(
    new Blob([credentialsCsv(credentials)], { type: "text/csv" }),
  );
  const link = document.createElement("a");
  link.href = url;
  link.download = CREDENTIALS_FILE_NAME;
  link.click();
  URL.revokeObjectURL(url);
}
