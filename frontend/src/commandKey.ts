// A changed command (including expectedVersion) has a separate logical identity.
export function commandKey(
    keys: Map<string, string>,
    scope: string,
    body: object,
): string {
    const fingerprint = JSON.stringify([scope, body]);
    let key = keys.get(fingerprint);
    if (!key) {
        key = crypto.randomUUID();
        keys.set(fingerprint, key);
    }
    return key;
}
